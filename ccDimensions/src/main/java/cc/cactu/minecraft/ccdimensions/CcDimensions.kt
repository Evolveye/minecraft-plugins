package cc.cactu.minecraft.ccdimensions

import org.bukkit.event.Listener
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.plugin.Plugin
import cc.cactu.minecraft.ccspawn.CcSpawn
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.*
import org.bukkit.event.EventHandler
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityTransformEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerPortalEvent
import org.bukkit.persistence.PersistentDataType
import java.io.File
import java.util.*

class CcDimensions : JavaPlugin(), Listener {
    private val pigNBTTagKey = NamespacedKey( this, "lightningPig" )
    private lateinit var configFile: File
    private lateinit var config: YamlConfiguration
    private var ccSpawn:CcSpawn? = null

    override fun onEnable() {
        server.pluginManager.registerEvents( this, this )
        logger.info( "Hello ccDimensions" )

        val plugin: Plugin? = server.pluginManager.getPlugin("ccSpawn")

        if (plugin != null && plugin.isEnabled) {
            logger.info( "Dependencies found, ${plugin is CcSpawn}" )
            ccSpawn = plugin as CcSpawn
        } else {
            logger.warning( "ccSpawn plugin is missing!" )
        }

        configFile = File( dataFolder, "storage.yml")

        if (!configFile.exists()) {
            configFile.parentFile.mkdirs()
            saveResource("storage.yml", false)
        }

        Timer().scheduleAtFixedRate( object : TimerTask() {
            override fun run() {
                config = YamlConfiguration.loadConfiguration( configFile )
                logger.info( "Configuration synced" )
            }
        }, 0, 1000 * 60 * 30 )
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
        val player = event.player
        val clickedBlock = event.clickedBlock
        val item = event.item

        if (item != null && item.type == Material.ENDER_EYE) {
            if (checkIsEndOpen()) return

            val msg = Component.text( "Oko endu nie wyczuwa jeszcze żadnej bramy do kresu... ", NamedTextColor.WHITE )
            player.sendMessage( msg )
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onNetherLeave( event:PlayerPortalEvent ) {
        val player = event.player
        val fromWorld = event.from.world
        val toWorld = event.to.world

        if (fromWorld.name == "world" && toWorld.name == "world_nether") {
            if (checkIsNetherOpen()) return

            event.from.block.type = Material.AIR
            event.isCancelled = true

            sendHowToNetherInfo( player )
        } else if (fromWorld.name == "world_nether" && toWorld.name == "world") {
            ccSpawn?.teleportPlayerToSpawn( player ) ?: return
            event.isCancelled = true

            val msg = Component.text( "Mhm, portale nie działają dwukierunkowo. Będzie trzeba znaleźć na to jakiś sposób.", NamedTextColor.WHITE )
            player.sendMessage( msg )
        }
    }

    @EventHandler
    fun onEntityDamage( event:EntityDamageByEntityEvent ) {
        val entity = event.entity
        val damager = event.damager

        if (entity.type == EntityType.PIG && damager.type == EntityType.TRIDENT) {
            if (checkIsNetherOpen()) return

            val tridentItem = (damager as Trident).item

            if (!entity.world.isThundering || !tridentItem.enchantments.containsKey( Enchantment.CHANNELING )) return

            config.set( "nether.open", true )
            config.save( configFile )

            val msg = Component.text( "Transmutacja dokonana! ", NamedTextColor.WHITE )
                .append( Component.text( "Mieszkaniec Netheru został sprowadzony na świat ludzkimi rękami! ", NamedTextColor.RED ) )
                .append( Component.text( "Teraz wymiar ten, powinien stanać rpzed nami otworem!", NamedTextColor.WHITE ) )

            server.onlinePlayers.forEach { it.sendMessage( msg ) }
            logger.info( "${msg}" )
        }
    }
}
