package cc.cactu.minecraft.ccsatellite

import cc.cactu.minecraft.ccapilink.CcApiLink
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.ChunkSnapshot
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import javax.imageio.ImageIO

/** Timestamp in manual scan file names, sortable and safe for file systems. */
private val MANUAL_SCAN_TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern( "yyyy-MM-dd_HH-mm-ss" )

/**
 * Satellite radar: players build satellite stations, and once everyone leaves the server, the terrain around
 * all stations is scanned into one top-down image per world.
 */
class CcSatellite : JavaPlugin(), Listener {
    private lateinit var apiLink:CcApiLink
    private lateinit var stations:StationStore

    private var radiusPerRing = 16
    private var maxRings = 16
    private var minStationDistance = 128.0
    private var maxImageSize = 4096
    private var minRunInterval = Duration.ofMinutes( 15 )

    private var lastRunAt:Instant? = null
    private var pendingRun:BukkitTask? = null

    @Volatile
    private var isRunning = false

    override fun onEnable() {
        val link = server.pluginManager.getPlugin( "ccApiLink" ) as? CcApiLink
        if (link == null || !link.isEnabled) return disableWithError( "ccApiLink is not enabled, disabling ccSatellite" )

        apiLink = link

        saveDefaultConfig()
        radiusPerRing = config.getInt( "radius-per-ring", 16 )
        maxRings = config.getInt( "max-rings", 16 )
        minStationDistance = config.getDouble( "min-station-distance", 128.0 )
        maxImageSize = config.getInt( "max-image-size", 4096 )
        minRunInterval = Duration.ofMinutes( config.getLong( "min-run-interval-minutes", 15 ) )

        stations = StationStore( File( dataFolder, "stations.yml" ) )
        stations.load()

        server.pluginManager.registerEvents( this, this )

        logger.info( "Hello ccSatellite, ${stations.all.size} satellite station(s) registered" )
    }

    override fun onDisable() {
        pendingRun?.cancel()
    }

    override fun onCommand( sender:CommandSender, command:Command, label:String, args:Array<String> ):Boolean {
        when (args.firstOrNull()) {
            "scan" -> {
                if (isRunning) return true.also { sender.sendMessage( "Satellites are already running" ) }

                runSatellites( isManual = true )
                sender.sendMessage( "Satellites started, results will be in the server log" )
            }

            "list" -> {
                val list = stations.all
                if (list.isEmpty()) return true.also { sender.sendMessage( "No satellite stations registered" ) }

                sender.sendMessage( "Satellite stations (${list.size}):" )
                list.forEach { sender.sendMessage( " - ${it.worldName} ${it.x} ${it.y} ${it.z}, owner ${server.getOfflinePlayer( it.ownerId ).name ?: it.ownerId}" ) }
            }

            else -> return false
        }

        return true
    }

    override fun onTabComplete( sender:CommandSender, command:Command, label:String, args:Array<String> ):List<String> {
        if (args.size != 1) return emptyList()

        return listOf( "scan", "list" ).filter { it.startsWith( args[ 0 ], ignoreCase = true ) }
    }

    /**
     * Right click on a lodestone with an empty hand: checks the station below it, registers it when it works,
     * and tells the player its state. Clicks with an item (e.g. placing blocks) are left alone.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onLodestoneClick( event:PlayerInteractEvent ) {
        if (event.action != Action.RIGHT_CLICK_BLOCK || event.hand != EquipmentSlot.HAND || event.item != null) return
        if (event.useInteractedBlock() == Event.Result.DENY) return

        val lodestone = event.clickedBlock ?: return
        if (lodestone.type != Material.LODESTONE || !isStationLodestone( lodestone )) return

        val player = event.player
        val station = SatelliteStation( lodestone.world.name, lodestone.x, lodestone.y - 1, lodestone.z, player.uniqueId, Instant.now() )
        val check = checkStation( lodestone.world, station.x, station.y, station.z, maxRings )

        // Only new stations keep the distance; registered ones stay as they are.
        val nearbyStation = if (check.isWorking && !stations.contains( station )) stations.findNearby( station, minStationDistance ) else null
        val isNew = check.isWorking && nearbyStation == null && stations.add( station )

        player.sendMessage( describeStation( check, isNew, nearbyStation?.let { measureDistance( station, it ).toInt() } ) )
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit( event:PlayerQuitEvent ) {
        // The leaving player is still listed as online during this event.
        if (server.onlinePlayers.any { it != event.player }) return

        scheduleRun()
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin( event:PlayerJoinEvent ) {
        // The run waits until everyone leaves again.
        pendingRun?.cancel()
        pendingRun = null
    }

    /**
     * Checks the structure of every station, forgets the broken ones, and scans the circles of the rest,
     * one image per world. Stations in unloaded worlds are left for later. Main thread only.
     * Manual runs keep every scan and don't delay the automatic ones; see [saveMap].
     */
    fun runSatellites( isManual:Boolean = false ) {
        if (isRunning) return

        isRunning = true
        if (!isManual) lastRunAt = Instant.now()

        val checks = stations.all.mapNotNull { station ->
            val world = server.getWorld( station.worldName ) ?: return@mapNotNull null

            checkStationAsync( world, station ).thenApply { check -> station to check }
        }

        CompletableFuture.allOf( *checks.toTypedArray() )
            .thenCompose { scanStations( checks.map { it.join() }, isManual ) }
            .whenComplete { _, error ->
                if (error != null) logger.warning( "Satellite run failed: ${error.message}" )
                isRunning = false
            }
    }

    /**
     * Renders the generated chunks of `area`. Must be called on the main thread: chunks are loaded and snapshotted there,
     * the drawing happens off it. Chunks are never generated; missing ones stay transparent.
     */
    fun renderArea( area:ScanArea ): CompletableFuture<BufferedImage> {
        val world = area.world
        val minHeight = world.minHeight
        val maxHeight = world.maxHeight
        val seaLevel = world.seaLevel

        // Paper completes chunk futures on the main thread, so snapshots are taken right where the chunk got loaded.
        val snapshotFutures = area.listChunkPositions().map { position ->
            world.getChunkAtAsync( position.first, position.second, false )
                .thenApply<Pair<Pair<Int, Int>, ChunkSnapshot>?> { chunk -> chunk?.let { position to it.getChunkSnapshot( true, true, false ) } }
        }

        return CompletableFuture.allOf( *snapshotFutures.toTypedArray() )
            .thenApplyAsync { renderScanArea( area, snapshotFutures.mapNotNull { it.join() }.toMap(), minHeight, maxHeight, seaLevel ) }
    }

    /**
     * Writes a rendered map as a PNG and returns the file. Automatic scans keep one file per world and month in `scans`,
     * a later scan in the same month replacing the earlier one. Manual scans are all kept, timestamped, in `scans/manual`.
     * Safe to call off the main thread.
     */
    fun saveMap( area:ScanArea, image:BufferedImage, isManual:Boolean ): File {
        val directory = File( dataFolder, if (isManual) "scans/manual" else "scans" ).apply { mkdirs() }
        val period = if (isManual) LocalDateTime.now().format( MANUAL_SCAN_TIMESTAMP_FORMAT ) else YearMonth.now().toString()
        val file = File( directory, "${area.world.name}_$period.png" )

        // Written aside first, so a failed write never leaves a broken scan in place of the previous one.
        val partFile = File( directory, "${file.name}.part" )

        ImageIO.write( image, "png", partFile )
        Files.move( partFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE )

        return file
    }

    /**
     * Sends a rendered map as a base64 PNG. Automatic scans are one per world and month, so the backend should replace
     * an earlier `monthly` scan with the same `world` and `month`; `manual` scans should all be kept.
     * Safe to call off the main thread.
     */
    fun uploadMap( area:ScanArea, image:BufferedImage, isManual:Boolean ) {
        val png = ByteArrayOutputStream()
        ImageIO.write( image, "png", png )

        apiLink.post( "/maps", mapOf(
            "id" to UUID.randomUUID().toString(),
            "type" to if (isManual) "manual" else "monthly",
            "month" to YearMonth.now().toString(),
            "renderedAt" to Instant.now().toString(),
            "world" to area.world.name,
            "origin" to mapOf( "x" to area.westX, "z" to area.northZ ),
            "circles" to area.circles.map { mapOf( "x" to it.centerX, "z" to it.centerZ, "radius" to it.radius ) },
            "image" to mapOf(
                "format" to "png",
                "width" to image.width,
                "height" to image.height,
                "data" to Base64.getEncoder().encodeToString( png.toByteArray() ),
            ),
        ) )
    }

    /** Runs the satellites as soon as the minimal interval since the last run allows. */
    private fun scheduleRun() {
        val now = Instant.now()
        val allowedAt = lastRunAt?.plus( minRunInterval ) ?: now
        val delayTicks = Duration.between( now, allowedAt ).toMillis().coerceAtLeast( 0 ) / 50

        pendingRun?.cancel()
        pendingRun = server.scheduler.runTaskLater( this, Runnable {
            pendingRun = null
            runSatellites()
        }, delayTicks )
    }

    /** Chat message with the station's state; `nearbyDistance` is set when another station is too close to register this one. */
    private fun describeStation( check:StationCheck, isNew:Boolean, nearbyDistance:Int? ): Component {
        val ringsText = "${check.rings} copper ring${if (check.rings == 1) "" else "s"}"

        val state = when {
            !check.isWorking -> "<red>Not working:</red> ${check.problem?.hint} ($ringsText)"
            nearbyDistance != null -> "<red>Too close:</red> another satellite station is $nearbyDistance blocks away, " +
                "stations need ${minStationDistance.toInt()} blocks between them. Extend the existing one with copper rings instead."
            isNew -> "<green>Registered!</green> Scans <yellow>${check.rings * radiusPerRing}</yellow> blocks around ($ringsText)"
            else -> "<green>Working</green>, scans <yellow>${check.rings * radiusPerRing}</yellow> blocks around ($ringsText)"
        }

        return MiniMessage.miniMessage().deserialize( "<gold>Satellite station</gold> - $state" )
    }

    /** Loads the chunks a station may span, then checks it; see [checkStation]. */
    private fun checkStationAsync( world:World, station:SatelliteStation ): CompletableFuture<StationCheck> {
        val minChunkX = (station.x - maxRings) shr 4
        val maxChunkX = (station.x + maxRings) shr 4
        val minChunkZ = (station.z - maxRings) shr 4
        val maxChunkZ = (station.z + maxRings) shr 4

        val chunkLoads = (minChunkX..maxChunkX).flatMap { chunkX -> (minChunkZ..maxChunkZ).map { chunkZ -> world.getChunkAtAsync( chunkX, chunkZ, false ) } }

        return CompletableFuture.allOf( *chunkLoads.toTypedArray() ).thenApply { checkStation( world, station.x, station.y, station.z, maxRings ) }
    }

    /** Forgets broken stations and scans the circles of the working ones, one image per world. */
    private fun scanStations( results:List<Pair<SatelliteStation, StationCheck>>, isManual:Boolean ): CompletableFuture<Void> {
        val (working, broken) = results.partition { (_, check) -> check.isWorking }

        stations.removeAll( broken.map { it.first } )
        if (broken.isNotEmpty()) logger.info( "Removed ${broken.size} broken satellite station(s)" )

        val circlesByWorld = working.groupBy( { it.first.worldName }, { (station, check) -> ScanCircle( station.x + 0.5, station.z + 0.5, check.rings * radiusPerRing.toDouble() ) } )

        val scans = circlesByWorld.mapNotNull { (worldName, circles) ->
            val world = server.getWorld( worldName ) ?: return@mapNotNull null
            val area = ScanArea( world, circles )

            if (maxOf( area.widthInBlocks, area.heightInBlocks ) > maxImageSize) {
                logger.warning( "Satellite stations in $worldName span ${area.widthInBlocks}x${area.heightInBlocks} blocks, over max-image-size $maxImageSize, skipping" )
                return@mapNotNull null
            }

            renderArea( area ).thenAccept { image -> logger.info( "Satellite scan of $worldName saved to ${saveMap( area, image, isManual ).path}" ) }
        }

        return CompletableFuture.allOf( *scans.toTypedArray() )
    }

    private fun disableWithError( message:String ) {
        logger.severe( message )
        server.pluginManager.disablePlugin( this )
    }
}
