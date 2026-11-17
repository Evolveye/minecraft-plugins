package cc.cactu.minecraft.ccrails

import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.kyori.adventure.title.Title
import org.apache.commons.lang3.tuple.MutableTriple
import org.bukkit.Bukkit
import org.bukkit.GameEvent
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.Sign
import org.bukkit.block.data.Rail
import org.bukkit.block.data.type.NoteBlock
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Minecart
import org.bukkit.entity.Player
import org.bukkit.entity.Vehicle
import org.bukkit.entity.minecart.HopperMinecart
import org.bukkit.entity.minecart.StorageMinecart
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.NotePlayEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.vehicle.VehicleDestroyEvent
import org.bukkit.event.vehicle.VehicleEntityCollisionEvent
import org.bukkit.event.vehicle.VehicleExitEvent
import org.bukkit.event.vehicle.VehicleMoveEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BookMeta
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.util.StringUtil
import org.bukkit.util.Vector
import java.io.File
import java.util.*
import kotlin.collections.component1
import kotlin.collections.component2


data class VehicleData(
    var blockLocation: Location,
    var blockMargin: Boolean = false,
    var choosedCrossroadData: ChoosedCrossroadData? = null,
    var path: MutableTriple<Station, List<Int>?, Int?>? = null,
    var ownerId: UUID? = null,
) {
    fun recalculatePath( pathFinder:PathFinder, element:PathFinderElement ):Boolean {
        if (path == null) return false

        path!!.middle = pathFinder.findPath( element, path!!.left ) as List<Int>?

        if (path!!.middle!!.isEmpty()) return false

        path!!.right = 1

        return true
    }
}

data class CrossroadData(
    val crossroad: Crossroad,
    val railingDirection: Direction,
    val crossBlock: Block,
    val bigCrossBlock: Block?,
)

class CcRails : JavaPlugin(), Listener {
    private lateinit var configFile: File
    private lateinit var config: YamlConfiguration
    private lateinit var railPathFinder: PathFinder
    private lateinit var railsGraph: RailGraph

    private val straitRoadMaxSpeed = 1.4
    private val diagonalRoadMaxSpeed = 0.9
    private val ascendingRoadMaxSpeed = 0.5
    private val noteblockPulseTicks = 10L

    // Oczyszczanie grafu z elementów, których nie ma już w świecie
    private val graphCleanupBatchSize = 10
    private val graphCleanupIntervalTicks = 20L * 60 * 60 // raz na godzinę
    private val graphCleanupInitialDelayTicks = 20L * 60 * 10 // pierwsze sprawdzenie 10 minut po starcie
    private val fullGraphCleanupBatchSize = 50
    private val fullGraphCleanupBatchDelayTicks = 5L
    private val graphCleanupQueue = kotlin.collections.ArrayDeque<Int>()
    private var graphCleanupRunning = false

    private val vehicleDataset = mutableMapOf<UUID, VehicleData>()
    private val playerDataset = mutableMapOf<String, Long>()

    override fun onEnable() {
        server.pluginManager.registerEvents( this, this )
        forceLoadClasses()
        logger.info( "Hello ccRails" )

        configFile = File( dataFolder, "storage.yml" )

        if (!configFile.exists()) {
            configFile.parentFile.mkdirs()
            saveResource( "storage.yml", false )
        }

        config = YamlConfiguration.loadConfiguration( configFile )
        railsGraph = RailGraph( config )
        railPathFinder = PathFinder( railsGraph )

        server.scheduler.runTaskTimer( this, Runnable { runGraphCleanupBatch() }, graphCleanupInitialDelayTicks, graphCleanupIntervalTicks )
    }

    private fun forceLoadClasses() {
        val packageName = this::class.java.packageName
        val loader = this::class.java.classLoader

        val classesToLoad = listOf(
            "Layout",
            "Direction",
            "Station",
            "Crossroad",
            "Edge",
            "Turn",
        )

        for (className in classesToLoad) {
            try {
                Class.forName("$packageName.$className", true, loader)
            } catch (e: Exception) {
                logger.warning("Could not pre-load class: $className")
            }
        }
    }

    override fun onCommand( sender:CommandSender, command:Command, label:String, args:Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage( "§cPolecenie przeznacozne jedynie dla graczy" )
            return true
        }

        if (args.isEmpty()) {
            sender.sendMessage("§7Skorzystaj z 'tab completion' aby zobaczyć dalszą skłądnię" )
            return true
        }

        if (!sender.hasPermission( "ccrails.admin" )) {
            sender.sendMessage( "§c Brak uprawnień do używania tej komendy." )
            return true
        }

        when (args[0].lowercase()) {
            "tp" -> {
                if (args.size < 2) {
                    sender.sendMessage( "§cNależy podać cel" )
                    return true
                }

                val target = args[ 1 ]
                val id = target.toIntOrNull() ?: railsGraph.stations[ target ]
                val graphElement = railsGraph.pathFinderElements[ id ]
                val destination = if (graphElement is Station) Location( Bukkit.getWorld( graphElement.world ), graphElement.x + 0.5, graphElement.y + 1.0, graphElement.z + 0.5 )
                    else if (graphElement is Crossroad) Location( Bukkit.getWorld( graphElement.world ), graphElement.x + 0.5, graphElement.y.toDouble(), graphElement.z + 0.5 )
                    else {
                        sender.sendMessage( "§cPodany element kolejowy nie istnieje" )
                        return true
                    }

                if (destination.world == null) {
                    sender.sendMessage( "§cŚwiat tego elementu nie jest załadowany" )
                    return true
                }

                sender.teleport( destination )
            }

            "clear" -> {
                config.set( "crossroads", mapOf<String, Crossroad>() )
                config.set( "stations", mapOf<String, Crossroad>() )
                config.set( "edges", mapOf<String, Edge>() )

                config.save( configFile )

                railsGraph = RailGraph( config )
                railPathFinder = PathFinder( railsGraph )
            }

            "cleanup" -> {
                if (graphCleanupRunning) {
                    sender.sendMessage( "§cOczyszczanie grafu już trwa" )
                    return true
                }

                val ids = railsGraph.pathFinderElements.values.filter { it is Station || it is Crossroad }.map { it.id }

                sender.sendMessage( "§7Rozpoczynam pełne oczyszczanie grafu (§f${ids.size}§7 węzłów)..." )
                graphCleanupRunning = true

                runFullGraphCleanup( ids ) { checked, removed ->
                    graphCleanupRunning = false
                    sender.sendMessage( "§aOczyszczanie grafu zakończone. Sprawdzono: §f$checked§a, usunięto: §f$removed" )
                }
            }
        }


        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String>? {
        val completions = mutableListOf<String>()

        if (args.size == 1) {
            val subcommands = mutableListOf( "tp", "clear", "cleanup" )

            StringUtil.copyPartialMatches( args[ 0 ], subcommands, completions )

            completions.sort()
        } else if (args.size == 2 && args[0].equals( "tp", ignoreCase = true )) {
            val stations = railsGraph.stations.keys.sorted()
            val railGraphElementsIds = railsGraph.pathFinderElements.keys
                .sorted()
                .map { "§r$it" }

            val suggestions = stations + railGraphElementsIds
            val currentArg = args[ 1 ].lowercase()

            for (s in suggestions) {
                if (s.lowercase().startsWith( currentArg )) {
                    completions.add( s )
                }
            }
        }

        return completions
    }

    private fun runOnMain( task:() -> Unit ) {
        if (Bukkit.isPrimaryThread()) task()
        else server.scheduler.runTask( this, Runnable { task() } )
    }

    private fun nextGraphCleanupBatch():List<Int> {
        val batch = mutableListOf<Int>()

        while (batch.size < graphCleanupBatchSize) {
            if (graphCleanupQueue.isEmpty()) {
                if (batch.isNotEmpty()) break

                graphCleanupQueue.addAll( railsGraph.pathFinderElements.values.filter { it is Station || it is Crossroad }.map { it.id } )
                if (graphCleanupQueue.isEmpty()) break
            }

            val id = graphCleanupQueue.removeFirst()
            if (railsGraph.pathFinderElements.containsKey( id )) batch.add( id )
        }

        return batch
    }

    private fun runGraphCleanupBatch() {
        if (graphCleanupRunning) return

        verifyGraphElements( nextGraphCleanupBatch() ) { _, _ -> }
    }

    private fun runFullGraphCleanup( ids:List<Int>, onDone:( checked:Int, removed:Int ) -> Unit ) {
        var checked = 0
        var removed = 0

        fun processNext( rest:List<Int> ) {
            if (rest.isEmpty()) {
                onDone( checked, removed )
                return
            }

            verifyGraphElements( rest.take( fullGraphCleanupBatchSize ) ) { batchChecked, batchRemoved ->
                checked += batchChecked
                removed += batchRemoved

                server.scheduler.runTaskLater( this, Runnable { processNext( rest.drop( fullGraphCleanupBatchSize ) ) }, fullGraphCleanupBatchDelayTicks )
            }
        }

        processNext( ids )
    }

    private fun verifyGraphElements( ids:List<Int>, onDone:( checked:Int, removed:Int ) -> Unit ) {
        val graph = railsGraph
        val elements = ids.mapNotNull { graph.pathFinderElements[ it ] }.filter { it is Station || it is Crossroad }

        if (elements.isEmpty()) {
            onDone( 0, 0 )
            return
        }

        val missing = mutableListOf<Int>()
        var pending = elements.size

        fun finishOne() {
            if (--pending > 0) return

            val isCurrent = graph === railsGraph
            if (isCurrent && missing.isNotEmpty()) {
                graph.removeElements( missing, configFile )
                logger.info( "Oczyszczanie grafu: usunięto ${missing.size} nieistniejących węzłów" )
            }

            onDone( elements.size, if (isCurrent) missing.size else 0 )
        }

        for (element in elements) {
            val position = graph.locationOf( element )
            val world = position?.let { Bukkit.getWorld( it.first ) }

            if (position == null || world == null) {
                finishOne()
                continue
            }

            world.getChunkAtAsync( position.second shr 4, position.third shr 4, false ).whenComplete { chunk, error ->
                runOnMain {
                    if (error == null && (chunk == null || graph.existsInWorld( element ) == false)) missing.add( element.id )
                    finishOne()
                }
            }
        }
    }

    fun updateTurnRailShape( turnBlock:Block, railingDirection:Direction, turn:Turn ): Rail? {
        val turnRail = getRailBlockData( turnBlock ) ?: return null

        if (!listOf( Rail.Shape.NORTH_EAST, Rail.Shape.NORTH_WEST, Rail.Shape.SOUTH_WEST, Rail.Shape.SOUTH_EAST, Rail.Shape.NORTH_SOUTH, Rail.Shape.EAST_WEST ).contains( turnRail.shape )) return null

        fun setTurnShape( destinationShape:Rail.Shape, x:Int, z:Int, availableRailShapes:List<Rail.Shape> ): Boolean? {
            val afterTurn = getRailBlockData( turnBlock.getRelative( x, 0, z ) ) ?: return null
            if (!availableRailShapes.contains( afterTurn.shape )) return null

            if (turnRail.shape != destinationShape) turnRail.shape = destinationShape

            return true
        }

        if (railingDirection == Direction.EAST) {
            when (turn) {
                Turn.RIGHT -> {
                    setTurnShape( Rail.Shape.SOUTH_WEST, 0, 1, listOf( Rail.Shape.NORTH_EAST, Rail.Shape.NORTH_WEST, Rail.Shape.NORTH_SOUTH ) )
                        ?: setTurnShape( Rail.Shape.EAST_WEST, 1, 0, listOf( Rail.Shape.NORTH_WEST, Rail.Shape.SOUTH_WEST, Rail.Shape.EAST_WEST ) )
                        ?: return null
                }

                Turn.LEFT -> {
                    setTurnShape( Rail.Shape.NORTH_WEST, 0, -1, listOf( Rail.Shape.SOUTH_EAST, Rail.Shape.SOUTH_WEST, Rail.Shape.NORTH_SOUTH ) )
                        ?: setTurnShape( Rail.Shape.EAST_WEST, 1, 0, listOf( Rail.Shape.NORTH_WEST, Rail.Shape.SOUTH_WEST, Rail.Shape.EAST_WEST ) )
                        ?: return null
                }

                else -> {
                    setTurnShape( Rail.Shape.EAST_WEST, 1, 0, listOf( Rail.Shape.NORTH_WEST, Rail.Shape.SOUTH_WEST, Rail.Shape.EAST_WEST ) )
                        ?: return null
                }
            }
        } else if (railingDirection == Direction.WEST) {
            when (turn) {
                Turn.RIGHT -> {
                    setTurnShape( Rail.Shape.NORTH_EAST, 0, -1, listOf( Rail.Shape.SOUTH_EAST, Rail.Shape.SOUTH_WEST, Rail.Shape.NORTH_SOUTH ) )
                        ?: setTurnShape( Rail.Shape.EAST_WEST, -1, 0, listOf( Rail.Shape.NORTH_EAST, Rail.Shape.SOUTH_EAST, Rail.Shape.EAST_WEST ) )
                        ?: return null
                }

                Turn.LEFT -> {
                    setTurnShape( Rail.Shape.SOUTH_EAST, 0, 1, listOf( Rail.Shape.NORTH_EAST, Rail.Shape.NORTH_WEST, Rail.Shape.NORTH_SOUTH ) )
                        ?: setTurnShape( Rail.Shape.EAST_WEST, -1, 0, listOf( Rail.Shape.NORTH_EAST, Rail.Shape.SOUTH_EAST, Rail.Shape.EAST_WEST ) )
                        ?: return null
                }

                else -> {
                    setTurnShape( Rail.Shape.EAST_WEST, -1, 0, listOf( Rail.Shape.NORTH_EAST, Rail.Shape.SOUTH_EAST, Rail.Shape.EAST_WEST ) )
                        ?: return null
                }
            }
        } else if (railingDirection == Direction.SOUTH) {
            when (turn) {
                Turn.RIGHT -> {
                    setTurnShape( Rail.Shape.NORTH_WEST, -1, 0, listOf( Rail.Shape.NORTH_EAST, Rail.Shape.SOUTH_EAST, Rail.Shape.EAST_WEST ) )
//                        ?: setTurnShape( Rail.Shape.NORTH_SOUTH, -1, 0, listOf( Rail.Shape.NORTH_EAST, Rail.Shape.NORTH_WEST, Rail.Shape.NORTH_SOUTH ) )
                        ?: setTurnShape( Rail.Shape.NORTH_SOUTH, 0, 1, listOf( Rail.Shape.NORTH_EAST, Rail.Shape.NORTH_WEST, Rail.Shape.NORTH_SOUTH ) )
                        ?: return null
                }

                Turn.LEFT -> {
                    setTurnShape( Rail.Shape.NORTH_EAST, 1, 0, listOf( Rail.Shape.NORTH_WEST, Rail.Shape.SOUTH_WEST, Rail.Shape.EAST_WEST ) )
//                        ?: setTurnShape( Rail.Shape.NORTH_SOUTH, 0, 1, listOf( Rail.Shape.NORTH_EAST, Rail.Shape.NORTH_WEST, Rail.Shape.NORTH_SOUTH ) )
                        ?: setTurnShape( Rail.Shape.NORTH_SOUTH, 0, -1, listOf( Rail.Shape.SOUTH_EAST, Rail.Shape.SOUTH_WEST, Rail.Shape.NORTH_SOUTH ) )
                        ?: return null
                }

                else -> {
                    setTurnShape( Rail.Shape.NORTH_SOUTH, 0, 1, listOf( Rail.Shape.NORTH_EAST, Rail.Shape.NORTH_WEST, Rail.Shape.NORTH_SOUTH ) )
                        ?: return null
                }
            }
        } else { // Direction.NORTH
            when (turn) {
                Turn.RIGHT -> {
                    setTurnShape( Rail.Shape.SOUTH_EAST, 1, 0, listOf( Rail.Shape.NORTH_WEST, Rail.Shape.SOUTH_WEST, Rail.Shape.EAST_WEST ) )
                        ?: setTurnShape( Rail.Shape.NORTH_SOUTH, 0, 1, listOf( Rail.Shape.SOUTH_EAST, Rail.Shape.SOUTH_WEST, Rail.Shape.NORTH_SOUTH ) )
                        ?: return null
                }

                Turn.LEFT -> {
                    setTurnShape( Rail.Shape.SOUTH_WEST, -1, 0, listOf( Rail.Shape.NORTH_EAST, Rail.Shape.SOUTH_EAST, Rail.Shape.EAST_WEST ) )
//                        ?: setTurnShape( Rail.Shape.NORTH_SOUTH, 0, 1, listOf( Rail.Shape.SOUTH_EAST, Rail.Shape.SOUTH_WEST, Rail.Shape.NORTH_SOUTH ) )
                        ?: setTurnShape( Rail.Shape.NORTH_SOUTH, 0, -1, listOf( Rail.Shape.SOUTH_EAST, Rail.Shape.SOUTH_WEST, Rail.Shape.NORTH_SOUTH ) )
                        ?: return null
                }

                else -> {
                    setTurnShape( Rail.Shape.NORTH_SOUTH, 0, -1, listOf( Rail.Shape.SOUTH_EAST, Rail.Shape.SOUTH_WEST, Rail.Shape.NORTH_SOUTH ) )
                        ?: return null
                }
            }
        }

        return turnRail
    }

    fun getCrossroadData( railBlock:Block, vehicle:Vehicle ): CrossroadData? {
        val velocity = vehicle.velocity
        val railingDirection = getRailingDiretion( velocity )

        val turnBlock = getRelativeToRailByDirection( railBlock, railingDirection, 2 )

        val isCross = isRailCrossroad( turnBlock ) ?: return null
        if (isCross.size < 3) return null

        val nextTurnBlock = getRelativeToRailByDirection( turnBlock, railingDirection, 2 )
        val isBigCross = run {
            val bigCrossDirs = isRailCrossroad( nextTurnBlock ) ?: return@run false
            if (bigCrossDirs.size < 3) return@run false
            if (isCross.size == 3) return@run isCross == bigCrossDirs
            bigCrossDirs.size == 4 || !bigCrossDirs.contains( railingDirection )
        }

        val crossroad = railsGraph.getCrossroad( railBlock.location, railingDirection, configFile )

        return CrossroadData(
            crossroad,
            railingDirection,
            turnBlock,
            if (isBigCross) nextTurnBlock else null,
        )
    }

    fun getPredefinedTurn( minecart:Minecart, crossroadData:CrossroadData ):Pair<Boolean, Turn?> {
        val vehicleData = vehicleDataset[ minecart.uniqueId ]
        val vehiclePath = vehicleData?.path ?: return Pair( true, null )

        val predefinedTurn = run {
            val nextPathElement = if (vehiclePath.middle != null) {
                val nextStep = vehiclePath.right!! + 2

                if (vehiclePath.middle!!.size <= nextStep) null else {
                    vehiclePath.right = nextStep
                    vehiclePath.middle?.get( nextStep )
                }
            } else {
                if (!vehicleData.recalculatePath( railPathFinder, crossroadData.crossroad )) return Pair( false, null )
                vehiclePath.middle?.get( vehiclePath.right!! )
            }

            val edge = crossroadData.crossroad.edges.entries.find { (_, value) -> value == nextPathElement }
            if (edge == null) null
            else Turn.fromDirectiona( crossroadData.railingDirection, edge.key )
        }

        return Pair( true, predefinedTurn )
    }

    fun turn( minecart:Minecart, crossroadData:CrossroadData, predefinedTurn:Turn?=null ): Pair<Boolean, Direction> {
        val turn = predefinedTurn ?: run {
            val player = minecart.passengers.firstOrNull()

            if (player !is Player) Turn.STRAIGHT
            else if (player.inventory.itemInMainHand.type == Material.OAK_SIGN) Turn.RIGHT
            else if (player.inventory.itemInOffHand.type == Material.OAK_SIGN) Turn.LEFT
            else Turn.STRAIGHT
        }

        var turned = false
        var effectiveTurn = turn

        fun setTurn( block:Block, rail:Rail? ):Boolean {
            if (rail == null) return false

            val currentData = block.blockData as? Rail ?: return false
            if (currentData.shape == rail.shape) return false

            block.setBlockData( rail, true )
            return true
        }

        if (crossroadData.bigCrossBlock != null && turn == Turn.RIGHT) {
            val rightTurnRail = updateTurnRailShape( crossroadData.crossBlock, crossroadData.railingDirection, Turn.RIGHT )
            val hasRightRoad = rightTurnRail != null
                && rightTurnRail.shape != Rail.Shape.NORTH_SOUTH
                && rightTurnRail.shape != Rail.Shape.EAST_WEST

            if (!hasRightRoad) effectiveTurn = Turn.STRAIGHT
        }

        if (crossroadData.bigCrossBlock != null && effectiveTurn != Turn.RIGHT) {
            val firstTurnRail = updateTurnRailShape( crossroadData.crossBlock, crossroadData.railingDirection, Turn.STRAIGHT )
            setTurn( crossroadData.crossBlock, firstTurnRail )

            var nextTurnBlockUpdate = updateTurnRailShape( crossroadData.bigCrossBlock, crossroadData.railingDirection, effectiveTurn )

            if (nextTurnBlockUpdate == null) {
                val fallbackTurn = if (effectiveTurn == Turn.LEFT) Turn.STRAIGHT
                    else if (turn == Turn.RIGHT) Turn.RIGHT
                    else Turn.LEFT

                nextTurnBlockUpdate = updateTurnRailShape( crossroadData.bigCrossBlock, crossroadData.railingDirection, fallbackTurn )

                if (effectiveTurn == Turn.STRAIGHT && nextTurnBlockUpdate != null) {
                    effectiveTurn = fallbackTurn
                }
            }

            setTurn( crossroadData.bigCrossBlock, nextTurnBlockUpdate )

            if (effectiveTurn == Turn.LEFT) {
                val dirAfterLeftTurn = crossroadData.railingDirection.getAfterTurn( Turn.LEFT )
                val thirdTurnBlock = getRelativeToRailByDirection( crossroadData.bigCrossBlock, dirAfterLeftTurn, 2 )
                val thirdTurnBlockUpdate = updateTurnRailShape( thirdTurnBlock, dirAfterLeftTurn, Turn.STRAIGHT )

                if (thirdTurnBlockUpdate == null) {
                    effectiveTurn = Turn.STRAIGHT

                    val correctedTurnBlockUpdate = updateTurnRailShape( crossroadData.bigCrossBlock, crossroadData.railingDirection, Turn.STRAIGHT )
                    setTurn( crossroadData.bigCrossBlock, correctedTurnBlockUpdate )
                } else {
                    setTurn( thirdTurnBlock, thirdTurnBlockUpdate )
                }
            }

            turned = effectiveTurn != Turn.STRAIGHT
        } else {
            val candidates = listOf( effectiveTurn, Turn.STRAIGHT, Turn.LEFT, Turn.RIGHT ).distinct()

            var crossRail: Rail? = null
            for (candidate in candidates) {
                val rail = updateTurnRailShape( crossroadData.crossBlock, crossroadData.railingDirection, candidate ) ?: continue

                val isStraight = rail.shape == Rail.Shape.NORTH_SOUTH || rail.shape == Rail.Shape.EAST_WEST
                if (isStraight != (candidate == Turn.STRAIGHT)) continue

                crossRail = rail
                effectiveTurn = candidate
                break
            }

            val railChanged = setTurn( crossroadData.crossBlock, crossRail )

            turned = railChanged || (crossroadData.bigCrossBlock != null && effectiveTurn == Turn.RIGHT)
        }

        val finalDir = Pair( turned, crossroadData.railingDirection.getAfterTurn( effectiveTurn ) )

        return finalDir
    }

    private fun getStationNameFromBook( item:ItemStack ):String? {
        if (item.type != Material.WRITABLE_BOOK && item.type != Material.WRITTEN_BOOK) return null

        val meta = item.itemMeta as? BookMeta ?: return null
        if (!meta.hasPages()) return null

        val firstPage = meta.page(1) as? TextComponent ?: return null
        val stationName = firstPage.content().split( "\n" ).firstOrNull()?.trim()

        return if (stationName.isNullOrEmpty()) null else stationName
    }

    @EventHandler
    fun onVehicleDestroy( event:VehicleDestroyEvent ) {
        vehicleDataset.remove( event.vehicle.uniqueId )
    }

    @EventHandler
    fun onVehicleExit( event:VehicleExitEvent ) {
        val vehicle = event.vehicle
        if (vehicle !is Minecart) return

        if (vehicleDataset[ vehicle.uniqueId ]?.path != null) {
            for (passenger in vehicle.passengers) {
                vehicle.removePassenger( passenger )

                val safeLoc = passenger.location.clone()
                safeLoc.y = Math.floor( safeLoc.y ) + 1.0

                passenger.teleport( safeLoc )
            }

            vehicle.remove()
        }

        vehicleDataset.remove( vehicle.uniqueId )
    }

    @EventHandler
    fun onPlayerQuit( event:PlayerQuitEvent ) {
        val player = event.player

        val vehicle = player.vehicle
        if (vehicle !is Minecart) return

        if (vehicleDataset.containsKey( vehicle.uniqueId )) {
            vehicleDataset.remove(vehicle.uniqueId)

            vehicle.eject()
            vehicle.remove()
        }
    }

    @EventHandler
    fun onEntityRemove( event:EntityRemoveFromWorldEvent ) {
        vehicleDataset.remove( event.entity.uniqueId )
    }

    @EventHandler
    fun onMinecartCollision( event:VehicleEntityCollisionEvent ) {
        val vehicle = event.vehicle
        val entity = event.entity

        if (vehicle is Minecart && vehicle.velocity.length() > 0.4) {
            val direction = vehicle.velocity.clone().normalize().add( Vector( 0.0, 1.0, 0.0 ) )
            val angleRadians = Math.toRadians( 45.0 )

            direction.x = direction.x * Math.cos( angleRadians ) - direction.z * Math.sin( angleRadians )
            direction.z = direction.x * Math.sin( angleRadians ) + direction.z * Math.cos( angleRadians )
            direction.multiply( 1.5 )

            entity.velocity = direction

            event.isCancelled = true
        }
    }

    @EventHandler( ignoreCancelled = true )
    fun onRailClick( event:PlayerInteractEvent ) {
        val player = event.player
        val block = event.clickedBlock ?: return
        val item = event.item ?: return

        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        if (block.blockData !is Rail) return

        val stationName = getStationNameFromBook( item ) ?: return

        val stationId = railsGraph.stations[ stationName ]
        val station = railsGraph.pathFinderElements[ stationId ]

        event.isCancelled = true
        player.server.scheduler.runTask(this, Runnable {
            player.closeInventory()
        } )

        if (station == null) {
            player.sendMessage( "§aPodana stacja (§f$stationName§a) nie istnieje." )
            return
        }

        val railLocation = block.location.add(0.5, 0.2, 0.5 )
        val minecart = block.world.spawn( railLocation, Minecart::class.java )

        minecart.addPassenger( player )

        val direction = player.location.direction

        direction.y = 0.0
        if (direction.length() > 0) direction.normalize()

        minecart.velocity = direction.multiply( 0.5 )

        player.sendMessage( "§aWyruszasz w kierunku stacji: §f$stationName" )

        vehicleDataset[ minecart.uniqueId ] = VehicleData(
            blockLocation =  minecart.location,
            path = MutableTriple( station as Station, null, null )
        )

        println( "[RailSystem] Gracz ${player.name} wybiera stację: $stationName" )
    }

    @EventHandler( ignoreCancelled = true )
    fun onCargoMinecartClick( event:PlayerInteractEntityEvent ) {
        if (event.hand != EquipmentSlot.HAND) return

        val minecart = event.rightClicked
        if (minecart !is StorageMinecart && minecart !is HopperMinecart) return

        val player = event.player
        val stationName = getStationNameFromBook( player.inventory.itemInMainHand )
            ?: getStationNameFromBook( player.inventory.itemInOffHand )
            ?: return

        event.isCancelled = true

        val stationId = railsGraph.stations[ stationName ]
        val station = railsGraph.pathFinderElements[ stationId ]

        if (station == null) {
            player.sendMessage( "§aPodana stacja (§f$stationName§a) nie istnieje." )
            return
        }

        val railBlock = if (minecart.location.block.type === Material.AIR) minecart.location.block.getRelative( BlockFace.DOWN )
            else minecart.location.block

        if (getRailBlockData( railBlock ) == null) {
            player.sendMessage( "§cWagonik musi stać na torze." )
            return
        }

        val direction = player.location.direction

        direction.y = 0.0
        if (direction.length() > 0) direction.normalize()

        minecart.velocity = direction.multiply( 0.5 )

        player.sendMessage( "§aWagonik wyrusza w kierunku stacji: §f$stationName" )

        vehicleDataset[ minecart.uniqueId ] = VehicleData(
            blockLocation = minecart.location,
            path = MutableTriple( station as Station, null, null ),
            ownerId = player.uniqueId,
        )
    }

    @EventHandler
    fun onNoteBlockBreak( event:BlockBreakEvent ) {
        val block = event.block
        if (block.type != Material.NOTE_BLOCK) return

        val station = railsGraph.findStationAt( block.world.name, block.x, block.y, block.z ) ?: return

        event.player.sendMessage( "§cZniszczyłeś stację: §f${station.name}" )

        railsGraph.removeElements( listOf( station.id ), configFile )
    }

    @EventHandler
    fun onVehicleMove( event:VehicleMoveEvent ) {
        val vehicle = event.vehicle
        if (vehicle !is Minecart) return
        if (vehicle.passengers.firstOrNull() !is Player && vehicleDataset[ vehicle.uniqueId ]?.path == null) return

        val vehicleId = vehicle.uniqueId
        val to = event.to

        var vehicleData = vehicleDataset[ vehicleId ]
        if (vehicleData == null) {
            vehicleDataset[ vehicleId ] = VehicleData( to )
            vehicleData = vehicleDataset[ vehicleId ]
        } else {
            if (vehicleData.blockMargin) {
                if (to.distance( vehicleData.blockLocation ) <= 1.0) return
            } else {
                val blockLoc = vehicleData.blockLocation
                if (to.x == blockLoc.x && to.y == blockLoc.y && to.z == blockLoc.z) return
            }

            vehicleData.blockMargin = false
            vehicleData.blockLocation = to
        }

        val railBlock = if (vehicle.location.block.type === Material.AIR) vehicle.location.block.getRelative( BlockFace.DOWN )
        else vehicle.location.block

        var maxSpeed:Double? = null

        if (railBlock.type == Material.DETECTOR_RAIL) maxSpeed = handleDetectorRail( event, vehicleData!!, railBlock )
        else if (railBlock.type == Material.ACTIVATOR_RAIL) {
            if (handleActivatorRails( event, vehicleData!!, railBlock )) return
        }

        if (maxSpeed == null) {
            val railData = getRailBlockData( railBlock ) ?: return

            maxSpeed = when (railData.shape) {
                Rail.Shape.NORTH_EAST, Rail.Shape.NORTH_WEST, Rail.Shape.SOUTH_EAST, Rail.Shape.SOUTH_WEST -> diagonalRoadMaxSpeed
                Rail.Shape.ASCENDING_NORTH, Rail.Shape.ASCENDING_SOUTH, Rail.Shape.ASCENDING_EAST, Rail.Shape.ASCENDING_WEST -> ascendingRoadMaxSpeed
                else -> {
                    val nextBlock = getRelativeToRailByVelocity( railBlock, vehicle.velocity )
                    val nextRailData = getRailBlockData( nextBlock )

                    if (nextRailData == null) straitRoadMaxSpeed
                    else when (nextRailData.shape) {
                        Rail.Shape.ASCENDING_NORTH, Rail.Shape.ASCENDING_SOUTH, Rail.Shape.ASCENDING_EAST, Rail.Shape.ASCENDING_WEST -> ascendingRoadMaxSpeed
                        else -> straitRoadMaxSpeed
                    }
                }
            }
        }

        vehicle.maxSpeed = maxSpeed
        vehicle.velocity = straighten( vehicle.velocity, vehicle.maxSpeed )
    }

    fun handleDetectorRail( event:VehicleMoveEvent, vehicleData:VehicleData, railBlock:Block ):Double? {
        val minecart = event.vehicle as Minecart
        vehicleData.blockMargin = true

        val crossroadData = getCrossroadData( railBlock, minecart ) ?: return null
        val (isPredefinedTurnCalculated, predefinedTurn) = getPredefinedTurn( minecart, crossroadData )

        if (!isPredefinedTurnCalculated) {
            val receiver = minecart.passengers.firstOrNull() as? Player ?: vehicleData.ownerId?.let { Bukkit.getPlayer( it ) }
            receiver?.sendMessage( "§aNie znaleziono sprawdzonej trasy do wybranej stacji (§f${vehicleData.path!!.left.name}§a)." )

            if (minecart.passengers.firstOrNull() is Player) minecart.remove()
            else {
                vehicleDataset.remove( minecart.uniqueId )
                server.scheduler.runTask( this, Runnable { minecart.velocity = Vector( 0, 0, 0 ) } )
            }

            return null
        }

        val (turned, dirAfterTurn) = turn( minecart, crossroadData, predefinedTurn )

        if (vehicleData.choosedCrossroadData != null) {
            vehicleData.choosedCrossroadData!!.nodesBehind.add( Pair( crossroadData.crossroad, 0 ) )

            if (railsGraph.upsertCrossroad( vehicleData.choosedCrossroadData!!, configFile ) && vehicleData.path?.middle != null) {
                vehicleData.recalculatePath( railPathFinder, crossroadData.crossroad )
            }
        }

        vehicleData.choosedCrossroadData = ChoosedCrossroadData(
            crossroad = crossroadData.crossroad,
            railingDirection = crossroadData.railingDirection,
            choosedDirection = dirAfterTurn,
            nodesBehind = mutableListOf( Pair( crossroadData.crossroad, 0 ) )
        )

        if (turned) return Math.min( Math.min( straitRoadMaxSpeed, diagonalRoadMaxSpeed ), ascendingRoadMaxSpeed )
        return null
    }

    fun pulseNoteblock( noteblock:Block ) {
        val poweredData = noteblock.blockData as? NoteBlock ?: return
        if (poweredData.isPowered) return

        poweredData.isPowered = true
        noteblock.blockData = poweredData

        server.scheduler.runTaskLater( this, Runnable {
            val unpoweredData = noteblock.blockData as? NoteBlock ?: return@Runnable
            if (!unpoweredData.isPowered || noteblock.isBlockIndirectlyPowered) return@Runnable

            unpoweredData.isPowered = false
            noteblock.blockData = unpoweredData
        }, noteblockPulseTicks )
    }

    fun handleActivatorRails( event:VehicleMoveEvent, vehicleData:VehicleData, railBlock:Block ):Boolean {
        val vehicle = event.vehicle as Minecart
        val passenger = vehicle.passengers.firstOrNull()

        vehicleData.blockMargin = true

        if (passenger !is Player && vehicleData.path == null) return false

        val player = passenger as? Player ?: vehicleData.ownerId?.let { Bukkit.getPlayer( it ) }
        val noteblockCoords:List<Vector>
        val stationSignCoords:List<Vector>
        val railData = getRailBlockData( railBlock ) ?: return false

        if (railData.shape == Rail.Shape.NORTH_SOUTH) {
            noteblockCoords = listOf( Vector( -1, 0, 0 ), Vector( 1, 0, 0 ) )
            stationSignCoords = listOf(
                Vector( 0, 0, 1 ),
                Vector( 0, 0, -1 ),
                Vector( 0, 1, 0 ),
                Vector( 0, -2, 0 ),
            )
        } else {
            noteblockCoords = listOf( Vector( 0, 0, -1 ), Vector( 0, 0, 1 ) )
            stationSignCoords = listOf(
                Vector( 1, 0, 0 ),
                Vector( -1, 0, 0 ),
                Vector( 0, 1, 0 ),
                Vector( 0, -2, 0 ),
            )
        }

        var noteblock:Block? = null
        for (coord in noteblockCoords) {
            noteblock = railBlock.getRelative( coord.x.toInt(), coord.y.toInt(), coord.z.toInt() )
            if (noteblock.type == Material.NOTE_BLOCK) break
        }

        if (noteblock == null) return false

        var sign:Block? = null
        for (coord in stationSignCoords) {
            sign = noteblock.getRelative( coord.x.toInt(), coord.y.toInt(), coord.z.toInt() )
            if (sign.type == Material.OAK_WALL_SIGN || sign.type == Material.OAK_SIGN) break
        }

        val signState = sign?.state

        if (signState !is Sign) return false

        val signText = signState.lines().map { PlainTextComponentSerializer.plainText().serialize( it ) }.joinToString( " " ).trim()
        val stationResult = railsGraph.getStation( noteblock.location, signText, configFile )

        val path = vehicleData.path
        if (path != null && path.left.name != signText) return false
        if (path == null && player?.gameMode != GameMode.CREATIVE) vehicle.world.dropItem( vehicle.location, ItemStack( Material.MINECART ) )

        val noteblockData = noteblock.blockData as NoteBlock
        noteblock.world.playNote( noteblock.location, noteblockData.instrument, noteblockData.note )
        noteblock.world.sendGameEvent( passenger, GameEvent.NOTE_BLOCK_PLAY, noteblock.location.toCenterLocation().toVector() )
        pulseNoteblock( noteblock )

        vehicleDataset.remove( event.vehicle.uniqueId )
        vehicle.velocity = Vector( 0, 0, 0 )
        if (passenger is Player) vehicle.remove()

        val subtitle = when (stationResult) {
            is StationResult.SUCCESS_CREATED, is StationResult.SUCCESS_FOUND -> {
                val station = if (stationResult is StationResult.SUCCESS_CREATED) stationResult.station
                    else (stationResult as StationResult.SUCCESS_FOUND).station

                val choosedCrossroadData = vehicleData.choosedCrossroadData
                if (choosedCrossroadData != null) {
                    choosedCrossroadData.nodesBehind.add( Pair( station, 0 ) )
                    railsGraph.upsertCrossroad( choosedCrossroadData, configFile )

                    if (stationResult is StationResult.SUCCESS_CREATED) {
                        player?.sendMessage( Component.text( "Stację dodano do systemu!", NamedTextColor.GREEN ) )
                    }
                }

                Component.text( "Oto stacja, na której się znajdujesz" )
            }

            else -> {
                val baseMsg = Component.text( "Stacja nie została dodana do systemu! ", NamedTextColor.RED )
                val description = when (stationResult) {
                    is StationResult.ERROR_WRONG_NAME -> Component.text( "Nazwa stacji powinna zaczynać się od litery.", NamedTextColor.GRAY )
                    is StationResult.ERROR_DUPLICATE -> Component.text( "Taka stacja już istnieje. Najpierw należy zdemontować poprzednią stację", NamedTextColor.GRAY )
                }

                player?.sendMessage( baseMsg.append( description ) )

                Component.text( "To " )
                    .append( Component.text( "tymczasowa stacja", NamedTextColor.RED ) )
                    .append( Component.text( ", na której się znajdujesz" ) )
            }
        }

        player?.showTitle( Title.title( Component.text( signText, NamedTextColor.GREEN ), subtitle ) )

        return true
    }
}
