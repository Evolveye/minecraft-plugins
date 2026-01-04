package cc.cactu.minecraft.ccdimensions

import org.bukkit.event.Listener
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.plugin.Plugin
import cc.cactu.minecraft.ccspawn.CcSpawn
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.FireworkEffect
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.attribute.Attribute
import org.bukkit.block.data.type.SculkShrieker
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.*
import org.bukkit.event.EventHandler
import org.bukkit.event.block.Action
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.entity.EnderDragonChangePhaseEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.PrepareAnvilEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerPortalEvent
import org.bukkit.inventory.meta.FireworkMeta
import org.bukkit.inventory.meta.Repairable
import java.io.File
import java.util.*
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

typealias InventoryDamageable = org.bukkit.inventory.meta.Damageable

class CcDimensions : JavaPlugin(), Listener {
    private val pigNBTTagKey = NamespacedKey( this, "lightningPig" )
    private val cooldowns = mutableMapOf<UUID, Long>()
    private lateinit var configFile: File
    private lateinit var config: YamlConfiguration
    private var ccSpawn:CcSpawn? = null

    override fun onEnable() {
        server.pluginManager.registerEvents( this, this )
        logger.info( "Hello ccDimensions" )

        val plugin: Plugin? = server.pluginManager.getPlugin( "ccSpawn" )

        if (plugin != null && plugin.isEnabled) {
            logger.info( "Dependencies found, ${plugin is CcSpawn}" )
            ccSpawn = plugin as CcSpawn
        } else {
            logger.warning( "ccSpawn plugin is missing!" )
        }

        configFile = File( dataFolder, "storage.yml" )

        if (!configFile.exists()) {
            configFile.parentFile.mkdirs()
            saveResource( "storage.yml", false )
        }

        Timer().scheduleAtFixedRate( object : TimerTask() {
            override fun run() {
                config = YamlConfiguration.loadConfiguration( configFile )
//                logger.info( "Configuration synced" )
            }
        }, 0, 1000 * 60 * 30 )

        Bukkit.getScheduler().runTaskTimer( this, Runnable {
            val end = Bukkit.getWorld( "world_the_end" ) ?: return@Runnable
            val centerLoc = Location( end, 0.0, 64.0, 0.0 )
            val players = end.players.filter { it.location.distance( centerLoc ) <= 100 }

            if (players.isEmpty()) return@Runnable

            val phantoms = end.entities.filterIsInstance<Phantom>()


            for (phantom in phantoms.take( phantoms.size / 2 )) {
                if (phantom.location.distance( centerLoc ) >= 200) continue

                phantom.target = players.random()
            }
        }, 0, 20 * 15 )
    }

    fun checkIsNetherOpen():Boolean {
        return config.getBoolean( "nether.open", false )
    }

    fun checkIsEndOpen():Boolean {
        return config.getBoolean( "end.open", false )
    }

    private fun sendHowToNetherInfo( player:Player ) {
        val msg = Component.text( "Tyle tych portali rozsianych po świecie, a gdy już wpadniesz na pomysł aktywowania jednego z nich, to nie chcą Cię wpuścić. ", NamedTextColor.WHITE )
            .append( Component.text( "Może dobrym pomysłem będzie pokazanie piekielnych mocy aby zyskać ich uznanie? ", NamedTextColor.WHITE ) )
            .append( Component.text( "Proponuję transmutację świni poprzez zabawy piorunami. ", NamedTextColor.GOLD ) )
            .append( Component.text( "Jedyną możliwością na zawładnięcie piorunami, jest pozyskanie odpowiednio ", NamedTextColor.WHITE ) )
            .append( Component.text( "zaklętego trójzębu", NamedTextColor.GOLD ) )
            .append( Component.text( ".", NamedTextColor.WHITE ) )

        player.sendMessage( msg )
    }

    @EventHandler
    fun onPlayerInteract( event:PlayerInteractEvent ) {
        val item = event.item
        val clickedBlock = event.clickedBlock

        if (item?.type == Material.ENDER_EYE) return handleEyeOfEnder( event )
        if (clickedBlock?.type?.name?.endsWith( "_BED" ) == true && event.action == Action.RIGHT_CLICK_BLOCK) return handleBedUse( event )
    }

    @EventHandler
    fun onNetherLeave( event:PlayerPortalEvent ) {
        val player = event.player
        val fromWorld = event.from.world
        val toWorld = event.to.world

        if (fromWorld.name == "world" && toWorld.name == "world_nether") {
            if (checkIsNetherOpen()) {
                config.set( "nether.playerEnterLocations.${player.uniqueId}.x", player.location.x )
                config.set( "nether.playerEnterLocations.${player.uniqueId}.y", player.location.y )
                config.set( "nether.playerEnterLocations.${player.uniqueId}.z", player.location.z )
                config.save( configFile )
                return
            }

            event.from.block.type = Material.AIR
            event.isCancelled = true

            sendHowToNetherInfo( player )
        } else if (fromWorld.name == "world_nether" && toWorld.name == "world") {
            val playerNetherEnterString: String? = config.getString( "nether.playerEnterLocations.${player.uniqueId}")
            if (playerNetherEnterString != null) {
                player.teleport( Location(
                    Bukkit.getWorld( "world" ),
                    config.getDouble( "nether.playerEnterLocations.${player.uniqueId}.x" ),
                    config.getDouble( "nether.playerEnterLocations.${player.uniqueId}.y" ),
                    config.getDouble( "nether.playerEnterLocations.${player.uniqueId}.z" )
                ) )
            } else {
                val msg = Component.text( "Mhm, portale nie działają dwukierunkowo. Będzie trzeba znaleźć na to jakiś sposób.", NamedTextColor.WHITE )
                player.sendMessage( msg )
                ccSpawn?.teleportPlayerToSpawn( player ) ?: return
            }

            event.isCancelled = true
        }
    }

    @EventHandler
    fun onEntityDamage( event:EntityDamageByEntityEvent ) {
        val entity = event.entity
        val damager = event.damager

        if (entity.type == EntityType.PIG && damager.type == EntityType.TRIDENT) return handlePigTrident( event )
        if (entity.type == EntityType.ENDER_DRAGON && damager.type == EntityType.ARROW) return handleDragonArrow( event )
    }

    @EventHandler
    fun onDragonSpawn( event:CreatureSpawnEvent ) {
        if (event.entityType != EntityType.ENDER_DRAGON) return
        if (event.location.world.environment != World.Environment.THE_END) return

        handleFirstDragon( event )

        val dragon = event.entity as EnderDragon
        logger.info( "New dragon has been spawned" )

        dragon.getAttribute( Attribute.MAX_HEALTH )?.baseValue = 600.0
        dragon.health = 600.0
    }

    @EventHandler
    fun onDragonPhaseChange( event:EnderDragonChangePhaseEvent ) {
        val dragon = event.entity
        val world = dragon.world
        val maxHelath = dragon.getAttribute( Attribute.MAX_HEALTH )?.baseValue ?: return
        val healthPercent = dragon.health / maxHelath * 100.0

        if (event.currentPhase == EnderDragon.Phase.LAND_ON_PORTAL) {
//            if (healthPercent < 25) {
//                val endermiteLoc = dragon.location.clone().add(
//                    Random.nextInt( 0, 1 ).toDouble(),
//                    -4.0,
//                    Random.nextInt( 0, 1 ).toDouble()
//                )
//
//                world.spawn( endermiteLoc, Endermite::class.java )
//                logger.info( "Spawn endermite at $endermiteLoc" )
//            }

            val destroyedCrystals = getDestroyedEndCrystalsLocations()
            if (destroyedCrystals.isEmpty()) return

            val spawnLoc = destroyedCrystals.random()

            world.spawn( spawnLoc, EnderCrystal::class.java ) { it.isShowingBottom = true }
            world.strikeLightningEffect( spawnLoc )
        } else when (event.newPhase) {
            EnderDragon.Phase.ROAR_BEFORE_ATTACK -> {
                val destroyedCrystals = getDestroyedEndCrystalsLocations()
                if (destroyedCrystals.isEmpty()) return

                val players = world.players.filter { it.gameMode == GameMode.SURVIVAL }
                val player = players.random()

                repeat( ceil( destroyedCrystals.size.toDouble() / 2.0 ).toInt() ) {
                    val spawnLoc = player.location.clone().add( Random.nextDouble( 0.0, 3.0 ), 25.0, Random.nextDouble( 0.0, 3.0 ) )
                    val phantom = world.spawn( spawnLoc, Phantom::class.java )

                    phantom.target = player
                }
            }

            EnderDragon.Phase.LEAVE_PORTAL -> {
                if (healthPercent < 40) spawnLightningsInCircle( dragon.location, 20,  6.0 )
                if (healthPercent < 30) spawnLightningsInCircle( dragon.location, 30, 12.0 )
                if (healthPercent < 20) spawnLightningsInCircle( dragon.location, 50, 18.0 )
                if (healthPercent < 10) spawnLightningsInCircle( dragon.location, 75, 24.0 )
            }

            else -> {}
        }
    }

    @EventHandler
    fun onDragonDeath( event:EntityDeathEvent ) {
        val victim = event.entity

        if (victim is EnderDragon && victim.world.environment == World.Environment.THE_END) {
            if (config.getBoolean( "end.isFirstDragonDefeated", false )) return

            config.set( "end.isFirstDragonDefeated", true )

            val world = victim.world

            world.worldBorder.reset()
        }
    }

    @EventHandler
    fun onPlayerDeath( event:PlayerDeathEvent ) {
        val end = Bukkit.getWorld( "world_the_end" ) ?: return

        val dragons = end.entities.filterIsInstance<EnderDragon>()
        if (dragons.size == 0) return

        val dragon = dragons.first()

        val player = event.entity
        val playerLoc = player.location

        if (player.world.environment != World.Environment.THE_END || playerLoc.distance( dragon.location ) > 75 ) return

        Bukkit.getScheduler().runTaskLater( this, Runnable {
            end.getNearbyEntities( playerLoc, 3.0, 3.0, 3.0 ).forEach {
                if (it is Item) {
                    it.isInvulnerable = true
                    it.isVisualFire = false
                }
            }
        }, 1L )
    }

    @EventHandler
    fun onFireworkUse(event: PlayerInteractEvent) {
        val player = event.player
        val item = event.item

        if (!player.isGliding) return
        if (event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK) return
        if (item?.type != Material.FIREWORK_ROCKET) return

        val meta = item.itemMeta as? FireworkMeta ?: return
        val hasBigEffect = meta.effects.any { it.type == FireworkEffect.Type.BALL_LARGE }

        if (!hasBigEffect) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onPrepareAnvil( event:PrepareAnvilEvent ) {
        val firstItem = event.inventory.getItem( 0 ) ?: return
        val secondItem = event.inventory.getItem( 1 ) ?: return
        val result = event.result ?: return

        if (result.type == Material.ELYTRA) {
            val meta = result.itemMeta ?: return

            // Mending and Unbreaking
            if (meta.hasEnchant( Enchantment.MENDING) || meta.hasEnchant( Enchantment.UNBREAKING )) {
                meta.removeEnchant( Enchantment.MENDING)
                meta.removeEnchant( Enchantment.UNBREAKING )
            }

            // Repairing
            if (meta is InventoryDamageable && firstItem.itemMeta is InventoryDamageable) {
                val originalDamage = (firstItem.itemMeta as InventoryDamageable).damage
                val repairPerMembrane = 43 // default max_health=432, repair_per_membrane=108=25%
                val amountUsed = secondItem.amount

                val newDamage = originalDamage - (repairPerMembrane * amountUsed)
                meta.damage = newDamage.coerceAtLeast( 0 )
            }
            if (meta is Repairable) {
                meta.repairCost = 1
            }

            result.itemMeta = meta
            event.result = result
        }
    }

    private fun handleFirstDragon( event:CreatureSpawnEvent ) {
        if (getEndCrystalsLocations().size != 0) return

        val world = Bukkit.getWorld("world_the_end") ?: return
        val portalLoc = world.getHighestBlockAt( 0, 0 ).location ?: return
        val crystals = world.entities
            .filterIsInstance<EnderCrystal>()
            .sortedBy { it.location.distanceSquared( portalLoc ) }
            .take( 10 )
            .map { it.location.block.location }

        config.set( "end.crystals", crystals )
        config.save( configFile )

        world.worldBorder.let {
            it.setCenter( 0.0, 0.0 )
            it.size = 1000.0
            it.warningDistance = 100
            it.damageAmount = 10.0
        }
    }

    private fun handleEyeOfEnder( event:PlayerInteractEvent ) {
        if (event.player.world.environment != World.Environment.NORMAL) return

        val playerId = event.player.uniqueId
        val now = System.currentTimeMillis()
        if (now - (cooldowns[playerId] ?: 0L) < 50) return
        cooldowns[playerId] = now

        val player = event.player
        val block = player.location.block

        if (block.type == Material.SCULK_SHRIEKER) {
//            val data = block.blockData as SculkShrieker
//            if (!data.isCanSummon) {
//                player.sendMessage( Component.text( "To wrzeszczydełko zostało pozbawione mroku, które mogłoby zostać wykorzystane przez oko", NamedTextColor.GRAY ) )
//            } else {
//            }

            player.sendMessage( Component.text( "Spojrzawszy w mrok, oko ujrzało kres i podążyło w jego stronę.", NamedTextColor.GRAY ) )

            if (!checkIsEndOpen()) {
                config.set( "end.open", true )
                config.save( configFile )
            }

            return
        } else if (event.clickedBlock?.type == Material.END_PORTAL_FRAME) {
            if (checkIsEndOpen()) return
            else player.sendMessage( Component.text( "Oko endu nie przepełniło się jeszcze wystarczajacym mrokiem", NamedTextColor.GRAY ) )
        } else {
            player.sendMessage( Component.text( "Oko endu nie wyczuwa stąd żadnej bramy kresu. Stanie nad sclukowym wrzeszczydełkiem pozwoli mu zajrzeć w mrok", NamedTextColor.GRAY ) )
        }

        event.isCancelled = true
    }

    private fun handleBedUse( event:PlayerInteractEvent ) {
        val block = event.clickedBlock ?: return
        if (block.world.environment != World.Environment.THE_END) return
        event.isCancelled = true
    }

    private fun handlePigTrident( event:EntityDamageByEntityEvent ) {
        if (checkIsNetherOpen()) return

        val entity = event.entity
        val damager = event.damager as Trident

        if (!entity.world.isThundering || !damager.itemStack.enchantments.containsKey( Enchantment.CHANNELING )) return

        config.set( "nether.open", true )
        config.save( configFile )

        val msg = Component.text( "Transmutacja dokonana! ", NamedTextColor.WHITE )
            .append( Component.text( "Mieszkaniec Netheru został sprowadzony na świat ludzkimi rękami! ", NamedTextColor.RED ) )
            .append( Component.text( "Teraz wymiar ten, powinien stanać rpzed nami otworem!", NamedTextColor.WHITE ) )

        server.onlinePlayers.forEach { it.sendMessage( msg ) }
        logger.info( "${msg}" )
    }

    private fun handleDragonArrow( event:EntityDamageByEntityEvent ) {
        val arrow = event.damager as Arrow
        val velocity = arrow.velocity
        val verticalSpeed = velocity.y

        if (verticalSpeed < -1.2) {
            event.isCancelled = true
        }
    }

    private fun spawnLightningsInCircle( center:Location, count:Int, radius:Double ) {
        val world = center.world ?: return

        repeat( count ) {
            val angle = Random.nextDouble( 0.0, 2 * Math.PI )
            val x = center.x + radius * cos( angle )
            val z = center.z + radius * sin( angle )

            val strikeLocation = world.getHighestBlockAt( x.toInt(), z.toInt() ).location

            world.strikeLightning( strikeLocation )
        }
    }

    fun getEndCrystalsLocations(): MutableSet<Location> {
        val value = config.getList( "end.crystals" ) as MutableList<Location>?
        return value?.toMutableSet() ?: mutableSetOf()
    }

    fun getDestroyedEndCrystalsLocations(): List<Location> {
        val end = Bukkit.getWorld("world_the_end") ?: return listOf()

        return getEndCrystalsLocations()
            .filter { loc ->
                end.getNearbyEntities( loc, 0.0, 64.0, 0.0 )
                    .none { it is EnderCrystal }
            }
    }
}
