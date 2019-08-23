package io.cactu.mc.cuboids

import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.Chunk
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.Listener
import org.bukkit.event.EventHandler
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.block.BlockBreakEvent
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

enum class CuboidType {
  TENT,
  REGION,
  COLONY
}

data class Cuboid(
  val id:Int,
  val ownerUUID:String,
  val name:String,
  val type:CuboidType
)
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
  val cuboids = mutableSetOf<Cuboid>()
  val tentsCores = mutableSetOf<Triple<Int,Int,Int>>()

  override fun onEnable() {
    server.pluginManager.registerEvents( this, this )

    val cuboids = doQuery( "SELECT * FROM `cuboids_chunks`")
    val tents = doQuery( "SELECT * FROM `action_blocks WHERE plugin='ccCuboids' ans type='tent_core'`")

    while ( cuboids.next() ) cuboidsChunks.add( Triple(
      cuboids.getInt( "x" ),
      cuboids.getInt( "z" ),
      cuboids.getInt( "cuboidId" )
    ) )

    while ( tents.next() ) tentsCores.add( Triple(
      cuboids.getInt( "x" ),
      cuboids.getInt( "y" ),
      cuboids.getInt( "z" )
    ) )
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

          if ( createCuboid( args[ 1 ], CuboidType.REGION, player, chunk ) ) sender.sendMessage( createChatInfo( "Region utworzony") )
          else sender.sendMessage( createChatError( "Wystąpił nieznany błąd (prawdopodobnie w kodzie SQL)" ) )
        }
      }
    }
    else if ( args[ 0 ] == "remove" ) {
      if ( args.size == 1 ) sender.sendMessage( createChatError( "Nie podałeś nazwy regionu do usunięcia!" ) )
      else if ( getCuboid( args[ 1 ] ) == null ) sender.sendMessage( createChatError( "Wskazany cuboid nie istnieje!" ) )
      else {
        if ( removeCuboid( args[ 1 ] ) ) sender.sendMessage( createChatInfo( "Region usunięty"))
        else sender.sendMessage( createChatError( "Wystąpił nieznany błąd (prawdopodobnie w kodzie SQL)" ) )
      }
    }

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
    if ( cuboidChunkFrom == null ) {
      val cuboid = cuboids.find { it.id == cuboidChunkTo!!.cuboidId }!!

      e.player.sendMessage( createChatInfo( "Wszedłeś na region: &7${cuboid.name.replace( '_', ' ' )}" ) )
    }
    else {
      val cuboid = cuboids.find { it.id == cuboidChunkFrom.cuboidId }!!

      e.player.sendMessage( createChatInfo( "Wyszedleś z regionu: &7${cuboid.name.replace( '_', ' ' )}" ) )
    }
  }
  @EventHandler
  public fun onBlockPlace( e:BlockPlaceEvent ) {
    val block = e.blockPlaced
    val player = e.player

    if ( block.type == Material.CAMPFIRE ) {
      val myTent = cuboids.find { it.type == CuboidType.TENT && it.ownerUUID == player.uniqueId.toString() }

      if ( myTent != null ) {
        if ( cuboidsNearPlayers.get( Pair( block.chunk.getX(), block.chunk.getZ() ) ) != null ) return

        e.player.sendMessage( createChatInfo( "Jeśli chciałeś postawić obozowisko, informuję że już jedno posiadasz" ) )
      }
      else {
        val x = block.getX()
        val y = block.getY()
        val z = block.getZ()

        doUpdatingQuery( """
          INSERT INTO action_blocks (fromPlugin, type, x, y, z)
          VALUES ('ccCuboids', 'tent_core', $x, $y, $z)
        """ )
        createCuboid( "Obozowisko gracza ${player.displayName}", CuboidType.TENT, player, block.chunk )
        tentsCores.add( Triple( x, y, z ) )
        e.player.sendMessage( createChatInfo( "Obozowisko rozbite pomyślnie" ) )
      }
    }
  }
  @EventHandler
  public fun onBlockBreak( e:BlockBreakEvent ) {
    val block = e.block
    val x = block.getX()
    val y = block.getY()
    val z = block.getZ()

    if ( block.type == Material.CAMPFIRE ) {
      val tentCore = tentsCores.find { x == it.first && y == it.second && z == it.third }

      if ( tentCore == null ) return

      tentsCores.remove( Triple( x, y, z ) )
      removeCuboid( "Obozowisko gracza ${e.player.displayName}" )
      doUpdatingQuery( "DELETE FROM action_blocks WHERE x=$x and y=$y and z=$z" )
      e.player.sendMessage( createChatInfo( "Obozowisko rozebrane pomyślnie" ) )
    }
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

    val cuboidInMemory = cuboids.find { it.ownerUUID == player.uniqueId.toString() }

    if ( cuboidInMemory != null ) return cuboidInMemory

    val cuboid = doQuery( """
      SELECT *
      FROM ( SELECT * FROM cuboids_members WHERE UUID='${player.uniqueId}' LIMIT 1 ) as m
      JOIN cuboids as c WHERE m.cuboidId=c.id
    """ )

    if ( !cuboid.next() ) return null

    val newCuboid = cuboidFromQuery( cuboid )

    cuboids.add( newCuboid )

    return newCuboid
  }
  fun getCuboid( cuboidName:String ):Cuboid? {
    val cuboidInMemoy = cuboids.find { it.name == cuboidName }

    if ( cuboidInMemoy != null ) return cuboidInMemoy

    val cuboid = doQuery( "SELECT * FROM cuboids WHERE name='$cuboidName'" )

    if ( !cuboid.next() ) return null

    val newCuboid = cuboidFromQuery( cuboid )

    cuboids.add( newCuboid )

    return newCuboid
  }

  fun cuboidFromQuery( cuboidFromQuery:ResultSet ):Cuboid = Cuboid(
    id = cuboidFromQuery.getInt( "id" ),
    ownerUUID = cuboidFromQuery.getString( "ownerUUID" ),
    name = cuboidFromQuery.getString( "name" ),
    type = CuboidType.valueOf( cuboidFromQuery.getString( "type" ) )
  )
  fun createCuboid( cuboidName:String, type:CuboidType, player:Player, chunk:Chunk ):Boolean {
    val name = cuboidName.replace( ' ', '_' )
    val existingCuboid = doQuery( "SELECT id FROM cuboids WHERE name='$name'" )

    if ( existingCuboid.next() ) return false

    doUpdatingQuery( """
      INSERT INTO cuboids (ownerUUID, name, type) VALUES ('${player.uniqueId}', '$name', '$type')
    """ )

    val newCuboid = run {
      val cuboid = doQuery( "SELECT * FROM cuboids ORDER BY id DESC LIMIT 1" )

      cuboid.next()

      cuboidFromQuery( cuboid )
    }

    val lastCuboidId = newCuboid.id
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
    cuboids.add( newCuboid )

    return true
  }
  fun removeCuboid( name:String ):Boolean {
    val existingCuboid = doQuery( "SELECT id FROM cuboids WHERE name='${name.replace( ' ', '_' )}'" )

    if ( !existingCuboid.next() ) return false

    val id = existingCuboid.getInt( "id" )

    doUpdatingQuery( "DELETE FROM cuboids WHERE id=$id" )
    doUpdatingQuery( "DELETE FROM cuboids_chunks WHERE cuboidId=$id" )
    doUpdatingQuery( "DELETE FROM cuboids_members WHERE cuboidId=$id" )

    cuboidsChunks.removeAll { it.third == id }
    cuboidsNearPlayers.entries.removeIf { it.value.cuboidId == id }
    cuboids.removeAll { it.id == id }

    return true
  }
}