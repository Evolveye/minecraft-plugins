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

data class CuboidChunk( val chunk:Chunk, val cuboidId:String ) {
  val playersInside = mutableListOf<Player>()
}

class App: JavaPlugin(), Listener {
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
  public fun onPlayerMove( e:PlayerMoveEvent ) {
    val playerChunk = e.to?.chunk
    val playerLastChunk = e.from.chunk

    if ( playerChunk == playerLastChunk || playerChunk == null ) return

    val cuboidChunkFrom = cuboidsNearPlayers.get( Pair( playerLastChunk.getX(), playerLastChunk.getZ() ) )
    val cuboidChunkTo = cuboidsNearPlayers.get( Pair( playerChunk.getX(), playerChunk.getZ() ) )

    if ( !((cuboidChunkFrom == null).xor( cuboidChunkTo == null )) ) return

    if ( cuboidChunkFrom == null ) e.player.sendMessage( "wkroczyles na teren cuboidu ${cuboidChunkTo!!.cuboidId}" )
    else e.player.sendMessage( "Wyszedles z cuboidu ${cuboidChunkFrom.cuboidId}" )
  }
}