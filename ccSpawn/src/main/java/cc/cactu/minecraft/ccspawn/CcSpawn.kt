package cc.cactu.minecraft.ccspawn

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.util.*
import kotlin.random.Random


class CcSpawn : JavaPlugin(), Listener {
    private lateinit var spawnConfig: File
    private lateinit var spawnData: YamlConfiguration

    override fun onEnable() {
        server.pluginManager.registerEvents( this, this )
        logger.info( "Hello ccSpawn" )

        spawnConfig = File( dataFolder, "storage.yml")

        if (!spawnConfig.exists()) {
            spawnConfig.parentFile.mkdirs()
            saveResource("storage.yml", false)
        }

        Timer().scheduleAtFixedRate( object : TimerTask() {
            override fun run() {
                spawnData = YamlConfiguration.loadConfiguration( spawnConfig )
            }
        }, 0, 1000 * 60 * 30 )
    }

    @EventHandler
    fun onJoin( e:PlayerJoinEvent) {
        val player = e.player

        if (player.hasPlayedBefore()) return

        val world = player.world
        val randomLocation = getPlayerCustomSpawnLocation( player ) ?: generateRandomLocation( world )

        logger.info( "First join, random teleportation" )
        logger.info( "  - From x=${player.location.x} y=${player.location.y} z=${player.location.z}" )
        logger.info( "  - To x=${randomLocation.x} y=${randomLocation.y} y=${randomLocation.z}" )

        spawnData.set( "${player.uniqueId}.nickname", player.name )
        spawnData.set( "${player.uniqueId}.randomSpawn.x", randomLocation.x )
        spawnData.set( "${player.uniqueId}.randomSpawn.y", randomLocation.y )
        spawnData.set( "${player.uniqueId}.randomSpawn.z", randomLocation.z )

        spawnData.save(spawnConfig)

        player.setBedSpawnLocation( randomLocation )
        player.teleport( randomLocation )
    }

    @EventHandler
    fun onPlayerRespawn( event:PlayerRespawnEvent ) {
        val player = event.player
        val bedSpawnLocation = player.bedSpawnLocation

        if (bedSpawnLocation != null) return

        val playerSpawnLocation: Location? = getPlayerCustomSpawnLocation( player )

        if (playerSpawnLocation != null) event.respawnLocation = playerSpawnLocation
    }

    fun getPlayerSpawnLocation( player:Player ): Location? {
        return player.bedSpawnLocation ?: getPlayerCustomSpawnLocation( player )
    }
    fun teleportPlayerToSpawn( player:Player ) {
        val playerSpawn = getPlayerSpawnLocation( player )

        if (playerSpawn != null) player.teleport( playerSpawn )
        else player.teleport( generateRandomLocation( Bukkit.getWorld( "world" )!! ) )
    }

    private fun getPlayerCustomSpawnLocation( player:Player ): Location? {
        if (spawnData.getString( player.uniqueId.toString() ) == null) return null

        val world = Bukkit.getWorld( "world" ) ?: return null
        val location = Location(
            world,
            spawnData.getDouble( "${player.uniqueId}.randomSpawn.x" ),
            spawnData.getDouble( "${player.uniqueId}.randomSpawn.y" ),
            spawnData.getDouble( "${player.uniqueId}.randomSpawn.z" )
        )

        val x = location.x.toInt()
        val y = location.y.toInt()
        val z = location.z.toInt()

        val block = world.getBlockAt( x, 400, z )
        logger.info( "${location} ${block}" )

        for (y in y..world.maxHeight) {
            location.y = y.toDouble()

            val ground = world.getBlockAt( x, y - 1, z )
            if (ground.type.isEmpty || ground.isLiquid) continue

            val feet = world.getBlockAt( x, y, z )
            val head = world.getBlockAt( x, y + 1, z )

            if (feet.isEmpty && head.isEmpty) break
        }

        return location
    }

    private fun generateRandomLocation( world:World ): Location {
        var goodLocation = false
        var x = 0
        var z = 0
        var y = 0
        var counter = 10

        while (!goodLocation && counter > 0) {
            x = Random.nextInt(10000) - 5000
            z = Random.nextInt(10000) - 5000
            y = world.getHighestBlockYAt( x, z )

            goodLocation = !world.getBlockAt( x, y, z ).isLiquid()
            counter--
        }

        return Location( world, x.toDouble(), y.toDouble() + 5, z.toDouble() )
    }
}
