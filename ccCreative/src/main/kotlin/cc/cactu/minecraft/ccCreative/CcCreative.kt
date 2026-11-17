package cc.cactu.minecraft.ccCreative

import com.destroystokyo.paper.event.player.PlayerAdvancementCriterionGrantEvent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.FluidCollisionMode
import org.bukkit.GameMode
import org.bukkit.GameRules
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.World
import org.bukkit.WorldCreator
import org.bukkit.WorldType
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.TileState
import org.bukkit.block.structure.Mirror
import org.bukkit.block.structure.StructureRotation
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCreativeEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerBedEnterEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerPortalEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.permissions.PermissionAttachment
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.potion.PotionEffect
import org.bukkit.util.BlockVector
import org.bukkit.util.io.BukkitObjectInputStream
import org.bukkit.util.io.BukkitObjectOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.Random
import java.util.UUID

class CcCreative : JavaPlugin(), Listener {
    private lateinit var configFile: File
    private lateinit var config: YamlConfiguration

    private val permissionAttachments = mutableMapOf<UUID, PermissionAttachment>()
    private val creativeWorldName = "world_creative"
    private val spawnProtectionRange = 60
    private val maxTeleportDistance = 400.0

    private val internalTeleports = mutableSetOf<UUID>()
    private fun isCreativeZone( world:World? ) = world?.name == creativeWorldName

    private val templateWorldName = "${creativeWorldName}_regen_tmp"
    private var regenInProgress = false

    private val selectionTool = Material.SPECTRAL_ARROW

    private val gameModeAliases = mapOf(
        "creative" to GameMode.CREATIVE, "c" to GameMode.CREATIVE, "1" to GameMode.CREATIVE,
        "survival" to GameMode.SURVIVAL, "s" to GameMode.SURVIVAL, "0" to GameMode.SURVIVAL,
        "adventure" to GameMode.ADVENTURE, "a" to GameMode.ADVENTURE, "2" to GameMode.ADVENTURE,
        "spectator" to GameMode.SPECTATOR, "sp" to GameMode.SPECTATOR, "3" to GameMode.SPECTATOR,
    )

    override fun onEnable() {
        server.pluginManager.registerEvents( this, this )
        server.pluginManager.registerEvents( TerrainProtection( creativeWorldName, spawnProtectionRange ), this )
        logger.info( "Hello ccCreative" )

        if (server.getWorld( creativeWorldName ) == null) {
            val file = File( server.worldContainer, "world/dimensions/minecraft/$creativeWorldName" )

            if (file.exists()) {
                logger.info("Loading the creative world")
                WorldCreator( creativeWorldName ).createWorld()
            } else {
                logger.warning("World folder $creativeWorldName not found!")
            }
        }

        configFile = File( dataFolder, "storage.yml" )

        if (!configFile.exists()) {
            configFile.parentFile.mkdirs()
            saveResource( "storage.yml", false )
        }

        config = YamlConfiguration.loadConfiguration( configFile )
    }

    override fun onCommand( sender:CommandSender, command:Command, label:String, args:Array<out String>): Boolean {
        val player = sender
        if (player !is Player) return true

        if (command.name.equals( "cccreative", ignoreCase = true )) {
            return handleAdminCommand( sender, args )
        }

        if (command.name.equals( "gm", ignoreCase = true )) {
            return handleGameModeCommand( player, args )
        }

        if (server.getWorld( creativeWorldName ) == null) {
            player.sendMessage( "§eTworzenie świata creative... To może chwilę potrwać." )

            createNormalWorld( creativeWorldName )?.let {
                it.setGameRule( GameRules.ADVANCE_TIME, false )
                it.setGameRule( GameRules.ADVANCE_TIME, false )
                it.setGameRule( GameRules.SPAWN_MOBS, false )
                it.time = 6000

                it.worldBorder.center = it.spawnLocation
                it.worldBorder.size = 1500.0
                it.worldBorder.damageAmount = 1.0
                it.worldBorder.warningDistance = 5
            }
        }

        if (player.world.name == creativeWorldName) {
            savePlayerStatus( player, creativeWorldName )

            revokeCreativePermissions( player )
            player.gameMode = GameMode.SURVIVAL

            loadPlayerStatus( player, "world_survival" )

            player.sendMessage( "§aPowrót do świata Survival!" )
        } else {
            savePlayerStatus( player, "world_survival" )

            grantCreativePermissions( player )
            player.gameMode = GameMode.CREATIVE

            loadPlayerStatus( player, creativeWorldName )

            player.sendMessage( "§bWitaj w świecie Creative!" )
        }

        return true
    }

    override fun onTabComplete( sender:CommandSender, command:Command, alias:String, args:Array<out String> ): List<String>? {
        if (command.name.equals( "gm", ignoreCase = true )) {
            return if (args.size == 1) listOf( "creative", "survival", "adventure", "spectator" ).filter { it.startsWith( args[0], ignoreCase = true ) }
                else emptyList()
        }

        if (!command.name.equals( "cccreative", ignoreCase = true )) return null
        if (!sender.hasPermission( "cccreative.admin" )) return emptyList()

        return if (args.size == 1) listOf( "regen-chunk" ).filter { it.startsWith( args[0], ignoreCase = true ) }
            else emptyList()
    }

    @EventHandler
    fun onAdvancementCriterion( event:PlayerAdvancementCriterionGrantEvent ) {
        val player = event.player
        if (player.world.name == creativeWorldName) event.isCancelled = true
    }

    @EventHandler
    fun onJoin( event:PlayerJoinEvent ) {
        val player = event.player

        if (player.world.name == creativeWorldName) {
            grantCreativePermissions( player )
        }
    }

    @EventHandler
    fun onQuit( event:PlayerQuitEvent ) {
        permissionAttachments.remove( event.player.uniqueId )
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onCrossZoneTeleport( event:PlayerTeleportEvent ) {
        val player = event.player

        if (internalTeleports.contains( player.uniqueId )) return

        val from = event.from.world
        val to = event.to.world

        if (isCreativeZone( from ) == isCreativeZone( to )) return

        event.isCancelled = true

        if (event.cause == PlayerTeleportEvent.TeleportCause.ENDER_PEARL) {
            player.sendMessage( "§cPerła kresu nie może przenieść Cię między tymi światami!" )
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onWorldChanged( event:PlayerChangedWorldEvent ) {
        val player = event.player
        if (internalTeleports.contains( player.uniqueId )) return

        val wasInCreative = isCreativeZone( event.from )
        val inCreative = isCreativeZone( player.world )

        if (wasInCreative == inCreative) return

        // Entering always starts in creative, leaving always ends in survival, whatever mode was picked with /gm.
        if (inCreative) {
            player.gameMode = GameMode.CREATIVE
            grantCreativePermissions( player )
        } else {
            revokeCreativePermissions( player )
            player.gameMode = GameMode.SURVIVAL
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onPlayerRespawn( event:PlayerRespawnEvent ) {
        val world = event.player.world

        if (world.name != creativeWorldName) return

        event.respawnLocation = world.spawnLocation
    }

    @EventHandler
    fun onCompassUse( event:PlayerInteractEvent ) {
        val player = event.player

        if (player.gameMode != GameMode.CREATIVE) return
        if (event.item?.type != Material.COMPASS) return
        if (event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK) return
        if (event.hand != EquipmentSlot.HAND) return

        event.isCancelled = true

        val result = player.world.rayTraceBlocks(
            player.eyeLocation,
            player.eyeLocation.direction,
            maxTeleportDistance,
            FluidCollisionMode.SOURCE_ONLY,
            true,
        ) ?: return

        val hitBlock = result.hitBlock ?: return
        val face = result.hitBlockFace

        val target = if (face == BlockFace.UP || face == null) {
                hitBlock.location.add( 0.5, 1.0, 0.5 )
            } else {
                hitBlock.getRelative( face ).location.add( 0.5, 0.0, 0.5 )
            }

        target.yaw = player.location.yaw
        target.pitch = player.location.pitch

        player.teleport( target )
        player.playSound( target, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.2f )
    }

    @EventHandler
    fun onRespawnChange( event:PlayerInteractEvent ) {
        val player = event.player
        if (player.world.name != creativeWorldName) return

        val block = event.clickedBlock ?: return

        if (event.action == Action.RIGHT_CLICK_BLOCK) {
            if (block.type == Material.RESPAWN_ANCHOR || block.type.name.contains("BED") && !block.type.name.contains("BEDROCK")) {
                event.isCancelled = true
                player.sendMessage( "§cNa świecie Creative punkt odrodzenia jest niezmienny!" )
            }
        }
    }

    @EventHandler
    fun onCreativePortalUse( event:PlayerPortalEvent ) {
        if (event.player.world.name == creativeWorldName) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onSelectionTool( event:PlayerInteractEvent ) {
        val player = event.player

        if (player.gameMode != GameMode.CREATIVE) return
        if (event.hand != EquipmentSlot.HAND) return
        if (event.item?.type != selectionTool) return

        val block = event.clickedBlock ?: return

        val label = when (event.action) {
            Action.LEFT_CLICK_BLOCK -> "A"
            Action.RIGHT_CLICK_BLOCK -> "B"
            else -> return
        }

        // zapobiega niszczeniu bloku w creative oraz otwieraniu skrzyń itp.
        event.isCancelled = true

        player.sendMessage( selectionMessage( label, block ) )
        player.playSound( player.location, Sound.UI_BUTTON_CLICK, 0.5f, if (label == "A") 1.2f else 1.6f )
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onSelectionToolBreak( event:BlockBreakEvent ) {
        if (event.player.gameMode != GameMode.CREATIVE) return
        if (event.player.inventory.itemInMainHand.type == selectionTool) event.isCancelled = true
    }

    fun savePlayerStatus( player:Player, worldName:String ) {
        val path = "inventories.${player.uniqueId}"
        val worldPath = "$path.$worldName"

        val effects = player.activePotionEffects.toList()
        val contents = player.inventory.contents
        val outputStream = ByteArrayOutputStream()

        BukkitObjectOutputStream( outputStream ).use { it.writeObject( contents ) }

        val inventory = Base64.getEncoder().encodeToString( outputStream.toByteArray() )

        config.set( "$path.nickname", player.name )
        config.set( "$worldPath.inventory", inventory )
        config.set( "$worldPath.level", player.level )
        config.set( "$worldPath.exp", player.exp )
        config.set( "$worldPath.effects", effects )
        config.set( "$worldPath.health", player.health )
        config.set( "$worldPath.food", player.foodLevel )
        config.set( "$worldPath.saturation", player.saturation )
        config.set( "$worldPath.location", player.location )

        config.save( configFile )
    }

    fun loadPlayerStatus( player:Player, worldName:String ) {
        val path = "inventories.${player.uniqueId}"
        val worldPath = "$path.$worldName"

        player.inventory.clear()
        player.level = 0
        player.exp = 0f
        player.activePotionEffects.forEach { player.removePotionEffect( it.type ) }
        player.health = 20.0
        player.foodLevel = 20
        player.saturation = 5f

        if (config.contains( worldPath )) {
            val inventory = config.getString( "$worldPath.inventory" )

            if (inventory != null) {
                val inputStream = ByteArrayInputStream( Base64.getDecoder().decode( inventory ) )

                BukkitObjectInputStream( inputStream ).use {
                    val items = it.readObject() as Array<ItemStack>
                    player.inventory.contents = items
                }
            }

            player.level = config.getInt( "$worldPath.level" )
            player.exp = config.getDouble( "$worldPath.exp" ).toFloat()
            player.health = config.getDouble( "$worldPath.health" )
            player.foodLevel = config.getInt( "$worldPath.food" )
            player.saturation = config.get( "$worldPath.saturation").toString().toFloat()

            val effects = config.getList( "$worldPath.effects" ) as? List<PotionEffect>
            effects?.forEach { player.addPotionEffect( it ) }
        }

        val loc = config.get( "$worldPath.location" ) as? Location ?: run {
            val spawn = server.getWorld( worldName )?.spawnLocation ?: return@run null
            spawn.x += 0.5
            spawn.z += 0.5
            spawn
        }

        if (loc != null) {
            if (player.isSleeping) player.wakeup(false)

            if (player.shoulderEntityLeft != null) {
                player.world.spawnEntity( player.location, player.shoulderEntityLeft!!.type )
                player.shoulderEntityLeft = null
            }

            if (player.shoulderEntityRight != null) {
                player.world.spawnEntity( player.location, player.shoulderEntityRight!!.type )
                player.shoulderEntityRight = null
            }

            Bukkit.getScheduler().runTask( this, Runnable {
                internalTeleports.add( player.uniqueId )

                try {
                    player.teleport( loc )
                } finally {
                    internalTeleports.remove( player.uniqueId )
                }
            })
        }
    }

    fun grantCreativePermissions( player:Player ) {
        val attachment = player.addAttachment( this )

        attachment.setPermission( "minecraft.command.fill", true )
        attachment.setPermission( "minecraft.command.fillbiome", true )
        attachment.setPermission( "minecraft.command.setblock", true )
        attachment.setPermission( "minecraft.command.clone", true )
        attachment.setPermission( "minecraft.command.summon", true )
        attachment.setPermission( "minecraft.command.time", true )
        attachment.setPermission( "minecraft.command.weather", true )
        attachment.setPermission( "minecraft.command.give", true )

        permissionAttachments[ player.uniqueId ] = attachment

        player.updateCommands()
    }

    fun revokeCreativePermissions(player: Player) {
        val attachment = permissionAttachments.remove( player.uniqueId )
        if (attachment != null) player.removeAttachment( attachment )

        player.updateCommands()
    }

    fun createFlatWorld( creativeWorldName:String ): World? {
        val creator = WorldCreator( creativeWorldName )

        val settings = """
                {
                  "layers": [
                    {"block": "minecraft:air", "height": 63},
                    {"block": "minecraft:smooth_quartz", "height": 1}
                  ],
                  "biome": "minecraft:dark_forest",
                  "structure_overrides": []
                }
            """.trimIndent()

        creator.type( WorldType.FLAT )
        creator.generatorSettings( settings )
        creator.generateStructures( false )

        return creator.createWorld()?.let {
            it.setSpawnLocation( 0, it.getHighestBlockYAt( 0, 0 ) + 1, 0 )
            it
        }
    }

    fun createNormalWorld( creativeWorldName:String ): World? {
        val creator = WorldCreator( creativeWorldName )

        creator.type( WorldType.NORMAL )
        creator.seed( 1920597806638889562 )
        creator.generateStructures( true )

        return creator.createWorld()?.let {
            it.setSpawnLocation( 602, 121, 156 )
            it
        }
    }

    private fun handleAdminCommand( sender:CommandSender, args:Array<out String> ): Boolean {
        if (!sender.hasPermission( "cccreative.admin" )) {
            sender.sendMessage( "§cNie masz uprawnień do tej komendy." )
            return true
        }

        when (args.firstOrNull()?.lowercase()) {
            "regen-chunk" -> {
                if (sender !is Player) {
                    sender.sendMessage( "§cTa komenda jest dostępna tylko dla graczy." )
                    return true
                }
                regenerateChunk( sender )
            }
            else -> sender.sendMessage( "§eUżycie: /cccreative regen-chunk" )
        }

        return true
    }

    private fun handleGameModeCommand( player:Player, args:Array<out String> ): Boolean {
        if (!isCreativeZone( player.world )) {
            player.sendMessage( "§cTryb gry możesz zmieniać tylko na świecie Creative." )
            return true
        }

        val gameMode = gameModeAliases[ args.firstOrNull()?.lowercase() ]

        if (gameMode == null) {
            player.sendMessage( "§eUżycie: /gm <creative|survival|adventure|spectator>" )
            return true
        }

        player.gameMode = gameMode
        player.sendMessage( "§aTryb gry zmieniony na ${gameMode.name.lowercase()}." )

        return true
    }

    private fun regenerateChunk( player:Player ) {
        val world = player.world

        if (regenInProgress) {
            player.sendMessage( "§cRegeneracja jest już w toku, spróbuj za chwilę." )
            return
        }

        regenInProgress = true

        val target = player.location.chunk
        val cx = target.x
        val cz = target.z

        player.sendMessage( "§eRegeneruję chunk [$cx, $cz]... Może to chwilę potrwać." )

        val template = server.getWorld( templateWorldName ) ?: WorldCreator( templateWorldName )
            .type( WorldType.NORMAL )
            .environment( world.environment )
            .seed( world.seed )
            .generateStructures( true )
            .createWorld()

        if (template == null) {
            regenInProgress = false
            player.sendMessage( "§cNie udało się utworzyć świata-wzorca." )
            return
        }

        template.getChunkAtAsync( cx, cz, true ).whenComplete { source, error ->
            server.scheduler.runTask( this, Runnable {
                try {
                    if (error != null || source == null) throw error ?: IllegalStateException( "Brak chunka wzorcowego" )

                    copyChunk( source, world.getChunkAt( cx, cz ) )
                    player.sendMessage( "§aChunk [$cx, $cz] został zregenerowany." )
                } catch (e:Exception) {
                    logger.severe( "Regeneracja chunka [$cx, $cz] nie powiodła się: ${e.message}" )
                    player.sendMessage( "§cRegeneracja nie powiodła się, szczegóły w konsoli." )
                } finally {
                    server.unloadWorld( template, false )
                    regenInProgress = false
                }
            })
        }
    }

    private fun copyChunk( source:Chunk, target:Chunk ) {
        val world = target.world
        target.load( true )

        // usuń wszystkie byty poza graczami (jak wcześniej)
         target.entities.filter { it !is Player }.forEach { it.remove() }

        val minY = world.minHeight
        val size = BlockVector( 16, world.maxHeight - minY, 16 )

        val sourceOrigin = Location( source.world, (source.x shl 4).toDouble(), minY.toDouble(), (source.z shl 4).toDouble() )
        val targetOrigin = Location( world, (target.x shl 4).toDouble(), minY.toDouble(), (target.z shl 4).toDouble() )

        val structure = Bukkit.getStructureManager().createStructure()

        structure.fill( sourceOrigin, size, false )
        structure.place( targetOrigin, false, StructureRotation.NONE, Mirror.NONE, 0, 1.0f, Random() )
    }

    private fun selectionMessage( label:String, block:Block ): Component {
        val coords = "${block.x} ${block.y} ${block.z}"

        val button = Component.text( "[$coords]", NamedTextColor.AQUA )
            .clickEvent( ClickEvent.copyToClipboard( coords ) )
            .insertion( "$coords " )
            .hoverEvent( HoverEvent.showText( Component.text( "Klik: kopiuj do schowka\nShift+klik: dopisz do czatu" ) ) )

        return Component.text( "Narożnik $label: ", NamedTextColor.GREEN ).append( button )
    }
}
