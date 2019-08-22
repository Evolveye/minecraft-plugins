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

data class Cuboid( val id:Int, val ownerUUID:String, val name:String )
data class CuboidChunk( val chunk:Chunk, val cuboidId:Int ) {
  val playersInside = mutableListOf<Player>()
}

class Plugin: JavaPlugin(), Listener {
  val host = "mysql.csrv.pl"
  val port = 3306
  val database = "csrv_651128"
  val username = "csrv_651128"
  val password = "0496eb9412992801d273"
  val connection = DriverManager.getConnection( "jdbc:mysql://$host:$port/$database" , username, password )
  val cuboidsChunks = mutableListOf<Triple<Int,Int,Int>>()
  val cuboidsNearPlayers = mutableMapOf<Pair<Int,Int>,CuboidChunk>()

  override fun onEnable() {
    server.pluginManager.registerEvents( this, this )
    val cuboids = doQuery( "SELECT * FROM `cuboids_chunks`")

    while ( cuboids.next() )
      cuboidsChunks.add( Triple( cuboids.getInt( "x" ), cuboids.getInt( "z" ), cuboids.getInt( "cuboidId" ) ) )
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
      else {
        val player = server.getPlayer( args[ 2 ] )

        if ( player == null ) sender.sendMessage( createChatError( "Wskazany gracz jest offline!" ) )
        else if ( getPlayerCuboid( args[ 2 ] ) != null ) sender.sendMessage( createChatError( "Gracz ten posiada juz swój region!" ) )
        else {
          val chunk = (if ( sender is Player ) sender else player).location.chunk

          if ( createCuboid( args[ 1 ], player, chunk ) ) sender.sendMessage( createChatInfo( 'i', "Region utworzony") )
          else sender.sendMessage( createChatError( "Wystąpił nieznany błąd (prawdopodobnie w kodzie SQL)" ) )
        }
      }
    }
    else if ( args[ 0 ] == "remove" ) {
      if ( args.size == 1 ) sender.sendMessage( createChatError( "Nie podałeś nazwy regionu do usunięcia!" ) )
      else if ( getCuboid( args[ 1 ] ) == null ) sender.sendMessage( createChatError( "Wskazany cuboid nie istnieje!" ) )
      else {
        if ( removeCuboid( args[ 1 ] ) ) sender.sendMessage( createChatInfo( 'i', "Region usunięty"))
        else sender.sendMessage( createChatError( "Wystąpił nieznany błąd (prawdopodobnie w kodzie SQL)" ) )
      }
    }

    return true
  }

  fun doQuery( query:String ):ResultSet = connection
    .prepareStatement( query )
    .executeQuery()
  fun doUpdatingQuery( query:String ):Int = connection
    .prepareStatement( query )
    .executeUpdate()
  fun getPlayerCuboid( playerName:String ):Cuboid? {
    val player = server.getPlayer( playerName )

    if ( player == null ) return null

    val cuboid = doQuery( """
      SELECT *
      FROM ( SELECT * FROM cuboids_members WHERE UUID='${player.uniqueId}' LIMIT 1 ) as m
      JOIN cuboids as c WHERE m.cuboidId=c.id
    """ )

    if ( !cuboid.next() ) return null
    return Cuboid( cuboid.getInt( "id" ), cuboid.getString( "ownerUUID" ), cuboid.getString( "name" ) )
  }
  fun getCuboid( cuboidName:String ):Cuboid? {
    val cuboid = doQuery( "SELECT * FROM cuboids WHERE name='$cuboidName'" )

    if ( !cuboid.next() ) return null
    return Cuboid( cuboid.getInt( "id" ), cuboid.getString( "ownerUUID" ), cuboid.getString( "name" ) )
  }
  fun createCuboid( name:String, player:Player, chunk:Chunk ):Boolean {
    val existingCuboid = doQuery( "SELECT id FROM cuboids WHERE name='$name'" )

    if ( existingCuboid.next() ) return false

    doUpdatingQuery( "INSERT INTO cuboids (ownerUUID, name) VALUES ('${player.uniqueId}', '$name')" )

    val insertedCuboid = doQuery( "SELECT id FROM cuboids ORDER BY id DESC LIMIT 1" )

    insertedCuboid.next()

    val lastCuboidId = insertedCuboid.getInt( "id" )
    val x = chunk.getX()
    val z = chunk.getZ()

    doUpdatingQuery( """
      INSERT INTO cuboids_members (UUID, cuboidId, role)
      VALUES ('${player.uniqueId}', $lastCuboidId, 'Owner')
    """ )
    doUpdatingQuery( """
      INSERT INTO cuboids_chunks (cuboidId, x, z)
      VALUES ($lastCuboidId, $x, $z)
    """ )

    cuboidsChunks.add( Triple( x, z, lastCuboidId ) )
    cuboidsNearPlayers.set( Pair( x, z ), CuboidChunk( chunk, lastCuboidId ) )

    return true
  }
  fun removeCuboid( name:String ):Boolean {
    val existingCuboid = doQuery( "SELECT id FROM cuboids WHERE name='$name'" )

    if ( !existingCuboid.next() ) return false

    val id = existingCuboid.getInt( "id" )

    doUpdatingQuery( "DELETE FROM cuboids WHERE id=$id" )
    doUpdatingQuery( "DELETE FROM cuboids_chunks WHERE cuboidId=$id" )
    doUpdatingQuery( "DELETE FROM cuboids_members WHERE cuboidId=$id" )

    cuboidsChunks.removeAll { it.third == id }
    cuboidsNearPlayers.forEach { if (it.value.cuboidId == id) cuboidsNearPlayers.remove( it.key ) }

    return true
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