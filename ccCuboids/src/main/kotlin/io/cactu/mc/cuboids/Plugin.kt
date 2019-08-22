package io.cactu.mc.cuboids

import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.Chunk
import org.bukkit.entity.Player
import org.bukkit.event.Listener
import org.bukkit.event.EventHandler
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.command.CommandSender
import org.bukkit.command.Command
import java.sql.DriverManager
import java.sql.Connection
import java.sql.ResultSet
import io.cactu.mc.chat.createChatInfo
import io.cactu.mc.chat.createChatError

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

  override fun onTabComplete( sender:CommandSender, command:Command, label:String, args:Array<String> ):List<String>? {
    if ( args.size == 1 ) return listOf( "create", "remove" )
    if ( args[ 0 ] == "create" ) {
      if ( args.size == 2 ) return listOf( "<name>" )
      if ( args.size == 3 ) return null
    }
    if ( args[ 0 ] == "remove" ) {
      if ( args.size == 2 ) return listOf( "<name>" )
      if ( args.size == 3 ) return null
    }

    return listOf()
  }

  override fun onCommand( sender:CommandSender, command:Command, label:String, args:Array<String> ):Boolean {
    if ( args.size == 0 ) sender.sendMessage( createChatError( "Nie podałeś akcji" ) )
    else if ( args[ 0 ] == "create" ) {
      if ( args.size == 1 ) sender.sendMessage( createChatError( "Nie podałeś nazwy regionu do utworzenia!" ) )
      else if ( args.size == 2 ) sender.sendMessage( createChatError( "Nie podałeś gracza, do którego należy przypisać region!" ) )
      else if ( getPlayerCuboid( args[ 2 ] ) ) sender.sendMessage( createChatError( "Gracz ten posiada juz swój region!" ) )
      else sender.sendMessage( createChatInfo( 'i', "Tworzymy region o nazwie ${args[ 1 ]}" ) )
    }
    else if ( args[ 0 ] == "remove" ) {
      if ( args.size == 1 ) sender.sendMessage( createChatError( "Nie podałeś nazwy regionu do usunięcia!" ) )
      else if ( getCuboid( args[ 1 ] ) ) sender.sendMessage( createChatError( "Wskazany cuboid nie istnieje!" ) )
      else sender.sendMessage( createChatInfo( 'i', "Usuwamy region o nazwie ${args[ 1 ]}" ) )
    }

    return true
  }

  fun doQuery( query:String ):ResultSet = connection
    .prepareStatement( query )
    .executeQuery()
  fun getPlayerCuboid( playerName:String ):Boolean {
    return false
  }
  fun getCuboid( cuboidName:String ):Boolean {
    return false
  }

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