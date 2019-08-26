package io.cactu.mc.cuboids

import io.cactu.mc.chat.createChatInfo
import io.cactu.mc.chat.createChatError
import io.cactu.mc.doQuery
import io.cactu.mc.doUpdatingQuery
import java.sql.ResultSet
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.Chunk
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.entity.EntityType
import org.bukkit.event.Listener
import org.bukkit.event.EventHandler
import org.bukkit.event.block.BlockCanBuildEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent

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
  val messageYouCannotInfereHere = "Nie możesz ingerować na tym obszarze!"
  val messageYoucannotPlaceCampfire = "Ogniska można stawiać jedynie na zabezpieczonym terenie, oraz gdy nie posiada się obozowiska"
  val messageTentTooCloseToAnotherCuboid = "Znajdujesz się zbyt blisko jakiegoś regionu aby zabezpieczyć ten chunk"
  val messageTentCreated = "Obozowisko &3rozbite pomyślnie"
  val messageTentRemoved = "Obozowisko &3rozebrane pomyślnie"

  val distanceTentFromCuboid = 2
  val distanceCuboidFromCuboid = 11
  val cuboidsChunks = mutableSetOf<CuboidChunk>()
  // val activeCuboidsChunks = mutableSetOf<CuboidChunk>()
  val actionBlocks = mutableMapOf<Triple<Int,Int,Int>,ActionBlock>()
  val cuboids = mutableMapOf<Int,Cuboid>()

  override fun onEnable() {
    server.pluginManager.registerEvents( this, this )

    val cuboidsSQL = doQuery( "SELECT * FROM cuboids")
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

    while ( cuboidsSQL.next() ) cuboids.set( cuboidsSQL.getInt( "id" ), buildCuboidFromQuery( cuboidsSQL ) )
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
  public fun onPlayerMove( e:PlayerMoveEvent ) {
    val chunkTo = e.to?.chunk ?: return
    val chunkFrom = e.from.chunk

    if ( chunkTo == chunkFrom ) return

    // val cuboidChunkTo = activeCuboidsChunks.find { it.x == chunkTo.x && it.z == chunkTo.z }
    // val cuboidChunkFrom = activeCuboidsChunks.find { it.x == chunkFrom.x && it.z == chunkFrom.z }
    val cuboidChunkTo = getCuboidChunk( chunkTo.x, chunkTo.z, chunkTo.world.name )
    val cuboidChunkFrom = getCuboidChunk( chunkFrom.x, chunkFrom.z, chunkFrom.world.name )

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
  public fun onEntityExplode( e:EntityExplodeEvent ) {
    val blocksList = e.blockList()
    val blocksListTemp = mutableSetOf<Block>()
    val worldName = e.entity.world.name

    if ( e.entityType == EntityType.CREEPER && worldName == "world" ) blocksList.clear()
    else {
      for ( block in blocksList ) {
        val chunk = block.chunk

        if ( getCuboidChunk( chunk.x, chunk.z, chunk.world.name ) != null ) blocksListTemp.add( block )
      }

      blocksListTemp.forEach { blocksList.remove( it ) }
    }
  }
  @EventHandler
  public fun onPlayerInteract( e:PlayerInteractEvent ) {
    val block = e.clickedBlock ?: return
    val player = e.player

    if ( !canPlayerInfere( block.chunk, player ) ) {
      createChatError( messageYouCannotInfereHere, player )
      e.setCancelled( true )
    }
  }
  @EventHandler
  public fun onDamage( e:EntityDamageByEntityEvent ) {
    val damager = e.damager

    if ( damager !is Player ) return
    if ( !canPlayerInfere( e.entity.location.chunk, damager ) ) {
      createChatError( messageYouCannotInfereHere, damager )
      e.setCancelled( true )
    }
  }
  @EventHandler
  public fun onBlockPlace( e:BlockPlaceEvent ) {
    val block = e.blockPlaced
    val player = e.player

    if ( block.type == Material.CAMPFIRE ) {
      if ( getCuboid( player, CuboidType.TENT ) != null ) {
        createChatInfo( messageYoucannotPlaceCampfire, player )
        e.setCancelled( true )
      }
      else if ( !isGoodPlaceForCuboid( block.chunk, CuboidType.TENT ) ) {
        createChatInfo( messageTentTooCloseToAnotherCuboid, player )
        e.setCancelled( true )
      }
      else {
        val x = block.x
        val y = block.y
        val z = block.z

        doUpdatingQuery(
          "INSERT INTO action_blocks (plugin, type, x, y, z) VALUES ('ccCuboids', 'tent_core', $x, $y, $z)"
        )
        createCuboid( "Obozowisko gracza ${player.displayName}", CuboidType.TENT, player, block.chunk )
        actionBlocks.set( Triple( x, y, z ), ActionBlock( "tent_core" ) )
        createChatInfo( messageTentCreated, player )
      }
    }
  }
  @EventHandler
  public fun onBlockBreak( e:BlockBreakEvent ) {
    val player = e.player
    val block = e.block
    val x = block.x
    val y = block.y
    val z = block.z

    if ( block.type == Material.CAMPFIRE ) {
      actionBlocks.remove( Triple( x, y, z ) ) ?: return

      removeCuboid( "Obozowisko gracza ${player.displayName}" )
      doUpdatingQuery( "DELETE FROM action_blocks WHERE x=$x and y=$y and z=$z" )
      createChatInfo( messageTentRemoved, player )
    }
  }

  fun isGoodPlaceForCuboid( chunk:Chunk, type:CuboidType ):Boolean {
    val newCuboidX = chunk.x
    val newCuboidZ = chunk.z
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
    val cuboidId = getCuboidChunk( chunk.x, chunk.z, chunk.world.name )?.cuboidId ?: return true
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
  fun getCuboidChunk( x:Int, z:Int, worldName:String ):CuboidChunk? {
    return cuboidsChunks.find { it.x == x && it.z == z && it.world == worldName }
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
    val x = chunk.x
    val z = chunk.z
    val cuboidChunk = CuboidChunk( x, z, worldName, newCuboidId )

    doUpdatingQuery(
      "INSERT INTO cuboids_members (UUID, cuboidId, owner, manager) VALUES ('$playerUUID', $newCuboidId, true, true)"
    )
    doUpdatingQuery(
      "INSERT INTO cuboids_chunks (cuboidId, world, x, z) VALUES ($newCuboidId, '$worldName', $x, $z)"
    )

    newCuboid.members.set( playerUUID, CuboidMember( playerUUID, true, true))

    // if( chunk.isLoaded ) activeCuboidsChunks.add( cuboidChunk )
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
    // activeCuboidsChunks.removeAll { it.cuboidId == id }
    cuboids.entries.removeIf { it.value.id == id }

    return true
  }
}


// * Something is wrong with nether chunks coordinates - that's why it is commented
//
// @EventHandler
// public fun onChunkLoad( e:ChunkLoadEvent ) {
//   if ( e.isNewChunk ) return
//
//   val chunk = e.chunk
//   val cuboidChunk = getCuboidChunk( chunk.x, chunk.z, chunk.world.name ) ?: return
//   val cuboidId = cuboidChunk.cuboidId
//
//   activeCuboidsChunks.add( cuboidChunk )
//
//   logger.info( "${activeCuboidsChunks.toString()}" )
//
//   if ( !cuboids.containsKey( cuboidId ) ) cuboids.set( cuboidId, getCuboid( cuboidId )!! )
// }
// @EventHandler
// public fun onChunkUnload( e:ChunkUnloadEvent ) {
//   val chunk = e.chunk
//   val cuboidChunk = getCuboidChunk( chunk.x, chunk.z, chunk.world.name ) ?: return
//   val cuboidId = cuboidChunk.cuboidId

//   activeCuboidsChunks.remove( cuboidChunk )

//   if ( activeCuboidsChunks.find { it.cuboidId == cuboidId } != null ) return

//   cuboids.remove( cuboidId )
// }