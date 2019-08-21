package io.cactu.mc.cuboids

import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.Chunk
import org.bukkit.entity.Player
import org.bukkit.event.Listener
import org.bukkit.event.EventHandler
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.event.player.PlayerMoveEvent
import java.sql.DriverManager
import java.sql.Connection
import java.sql.ResultSet
import io.cactu.mc.chat.createChatInfo

data class CuboidChunk( val chunk:Chunk, val cuboidId:String ) {
  val playersInside = mutableListOf<Player>()
}

class Plugin: JavaPlugin(), Listener {
  val host = "mysql.csrv.pl"
  val port = 3306
  val database = "csrv_651128"
  val username = "csrv_651128"
  val password = "0496eb9412992801d273"
  val connection = DriverManager.getConnection( "jdbc:mysql://$host:$port/$database" , username, password )
  val cuboidsChunks = mutableListOf<Triple<Int,Int,String>>()
  val cuboidsNearPlayers = mutableMapOf<Pair<Int,Int>,CuboidChunk>()

  override fun onEnable() {
    server.pluginManager.registerEvents( this, this )
    val cuboids = doQuery( "SELECT * FROM `cuboids_chunks`")

    while ( cuboids.next() )
      cuboidsChunks.add( Triple( cuboids.getInt( "x" ), cuboids.getInt( "z" ), cuboids.getString( "cuboidId" ) ) )
  }

  override fun onDisable() {
    connection.close()
  }

  fun doQuery( query:String ):ResultSet = connection
    .prepareStatement( query )
    .executeQuery()

  @EventHandler
  public fun onChunkLoad( e:ChunkLoadEvent ) {
    if ( e.isNewChunk ) return

    val x = e.chunk.getX()
    val z = e.chunk.getZ()
    val cuboidChunk = cuboidsChunks.find { it.first == x && it.second == z }

    if ( cuboidChunk == null ) return

    cuboidsNearPlayers.set( Pair( x, z ), CuboidChunk( e.chunk, cuboidChunk.third ) )
  }

  @EventHandler
  public fun onChunkUnload( e:ChunkUnloadEvent ) {
    val coords = Pair( e.chunk.getX(), e.chunk.getZ() )

    if ( cuboidsNearPlayers.containsKey( coords ) ) cuboidsNearPlayers.remove( coords )
  }

  @EventHandler
  public fun onPlayerMove( e:PlayerMoveEvent ) {
    val playerChunk = e.to?.chunk
    val playerLastChunk = e.from.chunk

    if ( playerChunk == playerLastChunk || playerChunk == null ) return

    val cuboidChunkFrom = cuboidsNearPlayers.get( Pair( playerLastChunk.getX(), playerLastChunk.getZ() ) )
    val cuboidChunkTo = cuboidsNearPlayers.get( Pair( playerChunk.getX(), playerChunk.getZ() ) )

    if ( !((cuboidChunkFrom == null).xor( cuboidChunkTo == null )) ) return

    val message =
      if ( cuboidChunkFrom == null ) "Wszedłeś na region o nazwie: &7${cuboidChunkTo!!.cuboidId}"
      else "Wyszedleś z regionu o nazwie: &7${cuboidChunkFrom.cuboidId}"

    e.player.sendMessage( createChatInfo( 'i', message ) )
  }
}