package io.cactu.mc.players

import io.cactu.mc.doQuery
import io.cactu.mc.doUpdatingQuery

import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.Listener
import org.bukkit.event.EventHandler

private val players = mutableMapOf<String,PlayerData>()
private val playersTimes = mutableMapOf<String,Long>()

fun player( uuid:String ) = players.get( uuid )
fun playerName( uuid:String ) = players.get( uuid )?.displayName
fun playerTime( uuid:String ) = players.get( uuid )?.timeOnServer
fun playerDifficulty( uuid:String ) = players.get( uuid )?.difficulty
fun playerPermissions( uuid:String ) = players.get( uuid )?.permissionsLevel

data class PlayerData(
  val UUID:String,
  var displayName:String,
  var timeOnServer:Long,
  var difficulty:Int,
  var permissionsLevel:Int
)

class Plugin: JavaPlugin(), Listener {

  override fun onEnable() {
    logger.info( "Plugin enabled" )
    server.pluginManager.registerEvents( this, this )

    val playersSQL = doQuery( "SELECT * FROM players" )

    while ( playersSQL.next() ) {
      val uuid = playersSQL.getString( "UUID" )
      players.set( uuid, PlayerData(
        uuid,
        playersSQL.getString( "displayName" ),
        playersSQL.getLong( "timeOnServer" ),
        playersSQL.getInt( "difficulty" ),
        playersSQL.getInt( "permissionsLevel" )
      ) )
    }
  }
  override fun onDisable() {
    server.onlinePlayers.forEach { updatePlayerTime( it.uniqueId.toString() ) }
  }

  @EventHandler
  public fun onPlayerJoin( e:PlayerJoinEvent ) {
    val player = e.player
    val uuid = player.uniqueId.toString()

    if ( !players.containsKey( uuid ) ) {
      val displayName = player.displayName
      val playerData = PlayerData( uuid, displayName, 0, 0, 0 )

      players.set( uuid, playerData )
      doUpdatingQuery( "INSERT INTO players VALUES ('$uuid', '$displayName', 0, 0, 0)" )
    }

    playersTimes.set( uuid, System.currentTimeMillis() - players.get( uuid )!!.timeOnServer )
  }
  @EventHandler
  public fun onPlayerQuit( e:PlayerQuitEvent ) {
    updatePlayerTime( e.player.uniqueId.toString() )
  }

  fun updatePlayerTime( uuid:String ) {
    val newTime = System.currentTimeMillis() - playersTimes.get( uuid )!!

    players.get( uuid )!!.timeOnServer = newTime
    doUpdatingQuery( "UPDATE players SET timeOnServer=$newTime" )
  }
}