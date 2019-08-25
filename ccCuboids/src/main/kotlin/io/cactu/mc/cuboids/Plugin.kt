package io.cactu.mc.cuboids

import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.Chunk
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.Listener
import org.bukkit.event.EventHandler
import org.bukkit.event.block.BlockCanBuildEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.command.CommandSender
import org.bukkit.command.Command
import java.sql.DriverManager
import java.sql.Connection
import java.sql.ResultSet
import io.cactu.mc.chat.createChatInfo
import io.cactu.mc.chat.createChatError

enum class CuboidType { TENT, REGION, COLONY }
data class ActionBlock( val type:String )
data class CuboidChunk( val x:Int, val z:Int, val world:String, val cuboidId:Int )
data class CuboidMember( val UUID:String, val owner:Boolean, val manager:Boolean )
data class Cuboid(
  val id:Int,
  val ownerUUID:String,
  val name:String,
  val type:CuboidType,
  val members:MutableMap<String,CuboidMember>
)

class Plugin: JavaPlugin(), Listener {
  val host = "mysql.csrv.pl"
  val port = 3306
  val database = "csrv_651128"
  val username = "csrv_651128"
  val password = "0496eb9412992801d273"
  val connection = DriverManager.getConnection( "jdbc:mysql://$host:$port/$database" , username, password )

  val distanceTentFromCuboid = 2
  val distanceCuboidFromCuboid = 11
  val cuboidsChunks = mutableSetOf<CuboidChunk>()
  val activeCuboidsChunks = mutableSetOf<CuboidChunk>()
  val actionBlocks = mutableMapOf<Triple<Int,Int,Int>,ActionBlock>()
  val cuboids = mutableMapOf<Int,Cuboid>()

  override fun onEnable() {
    server.pluginManager.registerEvents( this, this )

    val cuboidsChunksSQL = doQuery( "SELECT * FROM cuboids_chunks")
    val actionBlocksSQL = doQuery( "SELECT * FROM action_blocks WHERE plugin='ccCuboids' and type='tent_core'")

    while ( cuboidsChunksSQL.next() ) cuboidsChunks.add( CuboidChunk(
      cuboidsChunksSQL.getInt( "x" ),
      cuboidsChunksSQL.getInt( "z" ),
      cuboidsChunksSQL.getString( "world" ),
      cuboidsChunksSQL.getInt( "cuboidId" )
    ) )

    while ( actionBlocksSQL.next() ) actionBlocks.set( Triple(
      actionBlocksSQL.getInt( "x" ),
      actionBlocksSQL.getInt( "y" ),
      actionBlocksSQL.getInt( "z" )
    ), ActionBlock( actionBlocksSQL.getString( "type" ) ) )
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
    if ( args.size == 0 ) createChatError( "Nie podałeś akcji", sender )
    else if ( args[ 0 ] == "create" ) {
      if ( args.size == 1 ) createChatError( "Nie podałeś nazwy regionu do utworzenia!", sender )
      else if ( args.size == 2 ) createChatError( "Nie podałeś gracza, do którego należy przypisać region!", sender )
      else {
        val player = server.getPlayer( args[ 2 ] )

        if ( player == null ) createChatError( "Wskazany gracz jest offline!", sender )
        else if ( getCuboid( player ) != null ) createChatError( "Gracz ten posiada juz swój region!", sender )
        else {
          val chunk = (if ( sender is Player ) sender else player).location.chunk

          if ( createCuboid( args[ 1 ], CuboidType.REGION, player, chunk ) ) createChatInfo( "Region utworzony", sender )
          else createChatError( "Wystąpił nieznany błąd (prawdopodobnie w kodzie SQL)", sender )
        }
      }
    }
    else if ( args[ 0 ] == "remove" ) {
      if ( args.size == 1 ) createChatError( "Nie podałeś nazwy regionu do usunięcia!", sender )
      else if ( getCuboid( args[ 1 ] ) == null ) createChatError( "Wskazany cuboid nie istnieje!", sender )
      else {
        if ( removeCuboid( args[ 1 ] ) ) createChatInfo( "Region usunięty", sender )
        else createChatError( "Wystąpił nieznany błąd (prawdopodobnie w kodzie SQL)", sender )
      }
    }

    return true
  }

  @EventHandler
  public fun onChunkLoad( e:ChunkLoadEvent ) {
    if ( e.isNewChunk ) return

    val x = e.chunk.getX()
    val z = e.chunk.getZ()
    val world = e.chunk.world.name
    val cuboidChunk = cuboidsChunks.find { it.x == x && it.z == z && it.world == world } ?: return
    val cuboidId = cuboidChunk.cuboidId

    activeCuboidsChunks.add( cuboidChunk )

    if ( !cuboids.containsKey( cuboidId ) ) cuboids.set( cuboidId, getCuboid( cuboidId )!! )
  }
  @EventHandler
  public fun onChunkUnload( e:ChunkUnloadEvent ) {
    val x = e.chunk.getX()
    val z = e.chunk.getZ()
    val world = e.chunk.world.name
    val cuboidChunk = cuboidsChunks.find { it.x == x && it.z == z && it.world == world } ?: return
    val cuboidId = cuboidChunk.cuboidId

    activeCuboidsChunks.remove( cuboidChunk )

    if ( activeCuboidsChunks.find { it.cuboidId == cuboidId } != null ) return

    cuboids.remove( cuboidId )
  }
  @EventHandler
  public fun onPlayerMove( e:PlayerMoveEvent ) {
    val chunkTo = e.to?.chunk ?: return
    val chunkFrom = e.from.chunk

    if ( chunkTo == chunkFrom ) return

    val cuboidChunkTo = activeCuboidsChunks.find { it.x == chunkTo.getX() && it.z == chunkTo.getZ() }
    val cuboidChunkFrom = activeCuboidsChunks.find { it.x == chunkFrom.getX() && it.z == chunkFrom.getZ() }

    if ( (cuboidChunkFrom == null) == (cuboidChunkTo == null) ) return
    if ( cuboidChunkFrom == null ) {
      val cuboidName = cuboids.get( cuboidChunkTo!!.cuboidId )!!.name.replace( '_', ' ' )

      createChatInfo( "Wszedłeś na region: &7$cuboidName", e.player )
    }
    else {
      val cuboidName = cuboids.get( cuboidChunkFrom.cuboidId )!!.name.replace( '_', ' ' )

      createChatInfo( "Wyszedleś z regionu: &7$cuboidName", e.player )
    }
  }
  @EventHandler
  public fun onPlayerInteract( e:PlayerInteractEvent ) {
    val block = e.clickedBlock
    val player = e.player
    val chunk = if ( block == null ) player.location.chunk else block.chunk

    if ( !canPlayerInfere( chunk, e.player ) ) {
      createChatError( "Nie możesz ingerować na tym terenie!", player )
      e.setCancelled( true )
    }
  }
  @EventHandler
  public fun onBlockPlace( e:BlockPlaceEvent ) {
    val block = e.blockPlaced
    val player = e.player

    if ( block.type == Material.CAMPFIRE ) {
      if ( getCuboid( player, CuboidType.TENT ) != null ) {
        createChatInfo(
          "Ogniska można stawiać jedynie na zabezpieczonym terenie, oraz gdy nie posiada się obozowiska", player
        )
        e.setCancelled( true )
      }
      else if ( !isGoodPlaceForCuboid( block.chunk, CuboidType.TENT ) ) {
        createChatInfo( "Znajdujesz się zbyt blisko jakiegoś regionu aby zabezpieczyć ten chunk", player )
        e.setCancelled( true )
      }
      else {
        val x = block.getX()
        val y = block.getY()
        val z = block.getZ()

        doUpdatingQuery(
          "INSERT INTO action_blocks (plugin, type, x, y, z) VALUES ('ccCuboids', 'tent_core', $x, $y, $z)"
        )
        createCuboid( "Obozowisko gracza ${player.displayName}", CuboidType.TENT, player, block.chunk )
        actionBlocks.set( Triple( x, y, z ), ActionBlock( "tent_core" ) )
        createChatInfo( "Obozowisko rozbite pomyślnie", player )
      }
    }
  }
  @EventHandler
  public fun onBlockBreak( e:BlockBreakEvent ) {
    val player = e.player
    val block = e.block
    val x = block.getX()
    val y = block.getY()
    val z = block.getZ()

    if ( block.type == Material.CAMPFIRE ) {
      actionBlocks.remove( Triple( x, y, z ) ) ?: return

      removeCuboid( "Obozowisko gracza ${player.displayName}" )
      doUpdatingQuery( "DELETE FROM action_blocks WHERE x=$x and y=$y and z=$z" )
      createChatInfo( "Obozowisko rozebrane pomyślnie", player )
    }
  }

  fun doQuery( query:String ):ResultSet = connection
    .prepareStatement( query )
    .executeQuery()
  fun doUpdatingQuery( query:String ):Int = connection
    .prepareStatement( query )
    .executeUpdate()

  fun isGoodPlaceForCuboid( chunk:Chunk, type:CuboidType ):Boolean {
    val newCuboidX = chunk.getX()
    val newCuboidZ = chunk.getZ()
    val REGION = CuboidType.REGION

    for ( cuboidChunk in cuboidsChunks ) {
      val x = newCuboidX - cuboidChunk.x
      val z = newCuboidZ - cuboidChunk.z
      val distance = Math.hypot( x.toDouble(), z.toDouble() )

      if ( distance < distanceTentFromCuboid ) return false
      if ( type == REGION && distance < distanceCuboidFromCuboid ) return false
    }

    return true
  }
  fun canPlayerInfere( chunk:Chunk, player:Player ):Boolean {
    return canPlayerInfere( chunk, player.uniqueId.toString() )
  }
  fun canPlayerInfere( chunk:Chunk, playerUUID:String ):Boolean {
    val x = chunk.getX()
    val z = chunk.getZ()
    val cuboidId = cuboidsChunks.find { it.x == x && it.z == z }?.cuboidId ?: return true
    val cuboid = getCuboid( cuboidId )!!

    for ( member in cuboid.members.values )
      if ( member.UUID == playerUUID ) return true

    return false
  }

  fun getCuboid( player:Player, type:CuboidType=CuboidType.REGION ):Cuboid? {
    val playerUUID = player.uniqueId.toString()

    for ( cuboid in cuboids.values )
      if ( cuboid.type == type ) for ( member in cuboid.members.values )
        if ( member.UUID == playerUUID ) return cuboid

    val cuboid = doQuery( """
      SELECT *
      FROM ( SELECT * FROM cuboids_members WHERE UUID='$playerUUID' LIMIT 1 ) as m
      JOIN cuboids as c WHERE m.cuboidId=c.id
    """ )

    if ( !cuboid.next() ) return null

    val newCuboid = buildCuboidFromQuery( cuboid )

    cuboids.set( newCuboid.id, newCuboid )

    return newCuboid
  }
  fun getCuboid( cuboidName:String ):Cuboid? {
    val cuboidInMemoy = cuboids.entries.find { (_, it) -> it.name == cuboidName }

    if ( cuboidInMemoy != null ) return cuboidInMemoy.value

    val cuboid = doQuery( "SELECT * FROM cuboids WHERE name='$cuboidName'" )

    if ( !cuboid.next() ) return null

    val newCuboid = buildCuboidFromQuery( cuboid )

    cuboids.set( newCuboid.id, newCuboid )

    return newCuboid
  }
  fun getCuboid( id:Int ):Cuboid? {
    if ( cuboids.containsKey( id ) ) return cuboids.get( id )

    val cuboid = doQuery( "SELECT * FROM cuboids WHERE id=$id" )

    cuboid.next()

    return buildCuboidFromQuery( cuboid )
  }
  fun buildCuboidFromQuery( cuboidFromQuery:ResultSet ):Cuboid {
    val id = cuboidFromQuery.getInt( "id" )
    val membersSQL = doQuery( "SELECT * FROM cuboids_members WHERE cuboidId=$id" )
    val members = mutableMapOf<String,CuboidMember>()

    while ( membersSQL.next() ) {
      val uuid = membersSQL.getString( "UUID" )

      members.set( uuid, CuboidMember(
        uuid,
        membersSQL.getBoolean( "owner" ),
        membersSQL.getBoolean( "manager" )
      ) )
    }

    return Cuboid(
      id,
      cuboidFromQuery.getString( "ownerUUID" ),
      cuboidFromQuery.getString( "name" ),
      CuboidType.valueOf( cuboidFromQuery.getString( "type" ) ),
      members
    )
  }

  fun createCuboid( cuboidName:String, type:CuboidType, player:Player, chunk:Chunk ):Boolean {
    val name = cuboidName.replace( ' ', '_' )
    val existingCuboid = doQuery( "SELECT id FROM cuboids WHERE name='$name'" )
    val playerUUID = player.uniqueId.toString()

    if ( existingCuboid.next() ) return false

    doUpdatingQuery(
      "INSERT INTO cuboids (ownerUUID, name, type) VALUES ('$playerUUID', '$name', '$type')"
    )

    val newCuboid = run {
      val cuboid = doQuery( "SELECT * FROM cuboids ORDER BY id DESC LIMIT 1" )

      cuboid.next()

      buildCuboidFromQuery( cuboid )
    }

    val worldName = chunk.world.name
    val newCuboidId = newCuboid.id
    val x = chunk.getX()
    val z = chunk.getZ()
    val cuboidChunk = CuboidChunk( x, z, worldName, newCuboidId )

    doUpdatingQuery(
      "INSERT INTO cuboids_members (UUID, cuboidId, owner, manager) VALUES ('$playerUUID', $newCuboidId, true, true)"
    )
    doUpdatingQuery(
      "INSERT INTO cuboids_chunks (cuboidId, world, x, z) VALUES ($newCuboidId, '$worldName', $x, $z)"
    )

    newCuboid.members.set( playerUUID, CuboidMember( playerUUID, true, true))

    if( chunk.isLoaded ) activeCuboidsChunks.add( cuboidChunk )
    cuboidsChunks.add( cuboidChunk )
    cuboids.set( newCuboidId, newCuboid )

    return true
  }
  fun removeCuboid( name:String ):Boolean {
    val existingCuboid = doQuery( "SELECT id FROM cuboids WHERE name='${name.replace( ' ', '_' )}'" )

    if ( !existingCuboid.next() ) return false

    val id = existingCuboid.getInt( "id" )

    doUpdatingQuery( "DELETE FROM cuboids WHERE id=$id" )
    doUpdatingQuery( "DELETE FROM cuboids_chunks WHERE cuboidId=$id" )
    doUpdatingQuery( "DELETE FROM cuboids_members WHERE cuboidId=$id" )

    cuboidsChunks.removeAll { it.cuboidId == id }
    activeCuboidsChunks.removeAll { it.cuboidId == id }
    cuboids.entries.removeIf { it.value.id == id }

    return true
  }
}