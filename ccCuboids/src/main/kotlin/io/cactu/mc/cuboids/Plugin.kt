package io.cactu.mc.cuboids

import io.cactu.mc.chat.createChatInfo
import io.cactu.mc.chat.createChatError
import io.cactu.mc.chat.createChatMode
import io.cactu.mc.doQuery
import io.cactu.mc.doUpdatingQuery
import java.sql.ResultSet
import java.util.UUID
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.Chunk
import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.entity.EntityType
import org.bukkit.entity.TNTPrimed
import org.bukkit.inventory.ItemStack
import org.bukkit.event.Listener
import org.bukkit.event.EventHandler
import org.bukkit.event.block.Action
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
data class ActionBlock( val x:Int, val y:Int, val z:Int, val world:String, val cuboidId:Int, val type:String )
data class CuboidChunk( val x:Int, val z:Int, val world:String, val cuboidId:Int )
data class CuboidMember( val UUID:String, val cuboidId:Int, val owner:Boolean, val manager:Boolean )
data class Cuboid(
  val id:Int,
  val ownerUUID:String,
  var name:String,
  var type:CuboidType,
  val members:MutableMap<String,CuboidMember>
) { val actionBlocks = mutableSetOf<ActionBlock>() }

class Plugin: JavaPlugin(), Listener {
  val messageYouCannotInfereHere = "Nie możesz ingerować na tym obszarze!"
  val messageYoucannotPlaceCampfire = "Ogniska można stawiać jedynie na zabezpieczonym terenie, oraz gdy nie posiada się obozowiska"
  val messageTentTooCloseToAnotherCuboid = "Znajdujesz się zbyt blisko jakiegoś regionu aby zabezpieczyć ten chunk"
  val messageTentCreated = "Obozowisko &3rozbite pomyślnie"
  val messageTentRemoved = "Obozowisko &3rozebrane pomyślnie"

  val distanceTentFromCuboid = 2
  val distanceCuboidFromCuboid = 4
  val cuboidsChunks = mutableSetOf<CuboidChunk>()
  // val activeCuboidsChunks = mutableSetOf<CuboidChunk>()
  val actionBlocks = mutableSetOf<ActionBlock>()
  val cuboids = mutableMapOf<Int,Cuboid>()

  override fun onEnable() {
    server.pluginManager.registerEvents( this, this )

    createChatMode( '@', ChatColor.GOLD,
      test = fun( player:CommandSender ) = if ( getCuboid( player as Player ) == null ) false else true,
      receivers = fun( player:CommandSender ):MutableSet<Player> {
        val playersSet = mutableSetOf<Player>()

        getCuboid( player as Player )!!.members.forEach {
          val cuboidMember = server.getPlayer( UUID.fromString( it.value.UUID ) )

          if ( cuboidMember != null ) playersSet.add( cuboidMember )
        }

        return playersSet
      }
    )

    val cuboidsSQL = doQuery( "SELECT * FROM cuboids")
    val cuboidsChunksSQL = doQuery( "SELECT * FROM cuboids_chunks")
    val actionBlocksSQL = doQuery( "SELECT * FROM action_blocks WHERE plugin='ccCuboids' and type='tent_core'")

    while ( cuboidsSQL.next() ) cuboids.set( cuboidsSQL.getInt( "id" ), buildCuboidFromQuery( cuboidsSQL ) )

    while ( cuboidsChunksSQL.next() ) cuboidsChunks.add( CuboidChunk(
      cuboidsChunksSQL.getInt( "x" ),
      cuboidsChunksSQL.getInt( "z" ),
      cuboidsChunksSQL.getString( "world" ),
      cuboidsChunksSQL.getInt( "cuboidId" )
    ) )

    while ( actionBlocksSQL.next() ) {
      val cuboidId = actionBlocksSQL.getString( "data" ).toInt()
      val cuboid = getCuboid( cuboidId )!!
      val actionBlock = ActionBlock(
        actionBlocksSQL.getInt( "x" ),
        actionBlocksSQL.getInt( "y" ),
        actionBlocksSQL.getInt( "z" ),
        actionBlocksSQL.getString( "world" ),
        cuboidId,
        actionBlocksSQL.getString( "type" )
      )

      actionBlocks.add( actionBlock )
      cuboid.actionBlocks.add( actionBlock )
    }
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

          if ( createCuboid( args[ 1 ], CuboidType.REGION, player, chunk ) != null ) createChatInfo( "Region utworzony", sender )
          else createChatError( "Cuboid o tej nazwie już istnieje!", sender )
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
    val entity = e.entity
    val blocksList = e.blockList()
    val blocksListTemp = mutableSetOf<Block>()
    val isTntFiredByPlayer = entity is TNTPrimed && entity.source is Player

    for ( block in blocksList ) {
      val chunk = block.chunk

      if ( isTntFiredByPlayer && canPlayerInfere( chunk, (entity as TNTPrimed).source as Player ) ) continue
      if ( getCuboidChunk( chunk.x, chunk.z, chunk.world.name ) != null ) blocksListTemp.add( block )
    }

    blocksListTemp.forEach { blocksList.remove( it ) }
  }
  @EventHandler
  public fun onPlayerInteract( e:PlayerInteractEvent ) {
    val block = e.clickedBlock ?: return
    val chunk = block.chunk
    val player = e.player
    val typeStr = block.type.toString()
    val cuboidMember = getCuboidMember( block.chunk, player.uniqueId.toString() )
    val cuboid = getCuboid( chunk )

    if ( e.action == Action.LEFT_CLICK_BLOCK && player.inventory.itemInMainHand.type == Material.LANTERN ) {
      if ( cuboid == null ) {
        if ( canPlayerBuyChunk( player, chunk ) ) createChatInfo( "Chunk do kupienia", player )
      }
      else {
        if ( canChunkBeSaled( chunk ) ) createChatInfo( "Chunk na sprzedaż", player )

        if ( cuboidMember == null ) {}
        else if ( cuboid.type == CuboidType.TENT ) {
          if ( player.inventory.contains( Material.EMERALD_BLOCK ) ) {
            player.inventory.removeItem( ItemStack( Material.EMERALD_BLOCK, 1 ) )

            updateCuboid( cuboid, CuboidType.REGION )

            createChatInfo( "&3Region utworzony pomyślnie", player )
          }
        }
      }

      return e.setCancelled( true )
    }

    if ( cuboidMember == null ) {
      if ( isChunkCuboid( block.chunk ) ) {
        if ( !typeStr.contains( "STONE" ) && (typeStr.contains( "BUTTON" ) || typeStr.contains( "PLATE" ) ) ) return
        if ( typeStr.contains( "DOOR" ) && block.type != Material.IRON_DOOR ) return

        createChatError( messageYouCannotInfereHere, player )

        return e.setCancelled( true )
      }
      else return
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
        val world = block.world.name
        val cuboid = createCuboid(
          "Obozowisko gracza ${player.displayName}", CuboidType.TENT, player, block.chunk
          ) ?: return
        val tentCore = ActionBlock( x, y, z, block.world.name, cuboid.id, "tent_core" )

        doUpdatingQuery( """
          INSERT INTO action_blocks (plugin, type, world, data, x, y, z)
          VALUES ('ccCuboids', 'tent_core', '$world', '${cuboid.id}', $x, $y, $z)
        """ )
        cuboid.actionBlocks.add( tentCore )
        actionBlocks.add( tentCore )
        createChatInfo( messageTentCreated, player )
      }
    }
  }
  @EventHandler
  public fun onBlockBreak( e:BlockBreakEvent ) {
    val player = e.player
    val block = e.block

    if ( block.type == Material.CAMPFIRE ) {
      val x = block.x
      val y = block.y
      val z = block.z
      val world = block.world.name

      if ( !actionBlocks.removeIf { it.x == x && it.y == y && it.z == z && it.world == world } ) return

      removeCuboid( "Obozowisko gracza ${player.displayName}" )
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
  fun isChunkCuboid( chunk:Chunk ):Boolean {
    return if ( getCuboidChunk( chunk.x, chunk.z, chunk.world.name ) == null ) false else true
  }
  fun canPlayerBuyChunk( player:Player, chunk:Chunk ):Boolean {
    val cuboidId = getCuboid( player )?.id ?: return false

    return canChunkBeBought( cuboidId, chunk )
  }
  fun canChunkBeBought( cuboidId:Int, chunk:Chunk ):Boolean {
    val world = chunk.world.name
    val baseX = chunk.x
    val baseZ = chunk.z
    var chunkHaveCuboidNeighbour = false

    for ( x in -1..1 ) for ( z in -1..1 ) if ( x != 0 || z != 0 ) {
      val neighbour = getCuboidChunk( baseX + x, baseZ + z, world ) ?: continue

      if ( neighbour.cuboidId != cuboidId ) return false
      else if ( (Math.abs( x ) == 1) xor (Math.abs( z ) == 1) ) chunkHaveCuboidNeighbour = true
    }

    return chunkHaveCuboidNeighbour
  }
  fun canChunkBeSaled( chunk:Chunk ):Boolean {
    val world = chunk.world.name
    val baseX = chunk.x
    val baseZ = chunk.z

    getCuboidChunk( baseX, baseZ, world ) ?: return false

    for ( x in -1..1 ) for ( z in -1..1 ) if ( (Math.abs( x ) == 1) xor (Math.abs( z ) == 1) )
      getCuboidChunk( baseX + x, baseZ + z, world ) ?: return true

    return false
  }
  fun canPlayerInfere( chunk:Chunk, player:Player ):Boolean {
    return canPlayerInfere( chunk, player.uniqueId.toString() )
  }
  fun canPlayerInfere( chunk:Chunk, playerUUID:String ):Boolean {
    val cuboidId = getCuboidChunk( chunk.x, chunk.z, chunk.world.name )?.cuboidId ?: return true
    val cuboid = getCuboid( cuboidId )!!

    getCuboidMember( cuboid, playerUUID ) ?: return false

    return true
  }

  fun getCuboid( chunk:Chunk ):Cuboid? {
    val cuboidChunk = getCuboidChunk( chunk ) ?: return null

    return getCuboid( cuboidChunk.cuboidId )
  }
  fun getCuboid( player:Player, type:CuboidType?=null ):Cuboid? {
    val playerUUID = player.uniqueId.toString()

    for ( cuboid in cuboids.values )
      if ( type == null || cuboid.type == type ) for ( member in cuboid.members.values )
        if ( member.UUID == playerUUID ) return cuboid

    val cuboid = doQuery( """
      SELECT *
      FROM ( SELECT * FROM cuboids_members WHERE UUID='$playerUUID' LIMIT 1 ) as m
      JOIN cuboids as c WHERE m.cuboidId=c.id
    """ )

    if ( !cuboid.next() ) return null

    val newCuboid = buildCuboidFromQuery( cuboid )

    cuboids.set( newCuboid.id, newCuboid )

    return if ( type == null || newCuboid.type == type ) newCuboid else null
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
  fun getCuboidChunk( chunk:Chunk ):CuboidChunk? {
    val x = chunk.x
    val z = chunk.z
    val worldName = chunk.world.name

    return cuboidsChunks.find { it.x == x && it.z == z && it.world == worldName }
  }
  fun getCuboidChunk( x:Int, z:Int, worldName:String ):CuboidChunk? {
    return cuboidsChunks.find { it.x == x && it.z == z && it.world == worldName }
  }
  fun getCuboidMember( chunk:Chunk, playerUUID:String ):CuboidMember? {
    val cuboidId = getCuboidChunk( chunk.x, chunk.z, chunk.world.name )?.cuboidId ?: return null

    return getCuboidMember( getCuboid( cuboidId )!!, playerUUID )
  }
  fun getCuboidMember( cuboidId:Int, playerUUID:String ):CuboidMember? {
    return getCuboidMember( getCuboid( cuboidId )!!, playerUUID )
  }
  fun getCuboidMember( cuboid:Cuboid, playerUUID:String ):CuboidMember? {
    for ( member in cuboid.members.values )
      if ( member.UUID == playerUUID ) return member

    return null
  }

  fun buildCuboidFromQuery( cuboidFromQuery:ResultSet ):Cuboid {
    val id = cuboidFromQuery.getInt( "id" )
    val membersSQL = doQuery( "SELECT * FROM cuboids_members WHERE cuboidId=$id" )
    val members = mutableMapOf<String,CuboidMember>()

    while ( membersSQL.next() ) {
      val uuid = membersSQL.getString( "UUID" )

      members.set( uuid, CuboidMember(
        uuid,
        id,
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
  fun createCuboid( cuboidName:String, type:CuboidType, player:Player, chunk:Chunk ):Cuboid? {
    val name = cuboidName.replace( ' ', '_' )
    val existingCuboid = doQuery( "SELECT id FROM cuboids WHERE name='$name'" )
    val playerUUID = player.uniqueId.toString()

    if ( existingCuboid.next() ) return null

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

    newCuboid.members.set( playerUUID, CuboidMember( playerUUID, newCuboidId, true, true ) )

    // if( chunk.isLoaded ) activeCuboidsChunks.add( cuboidChunk )
    cuboidsChunks.add( cuboidChunk )
    cuboids.set( newCuboidId, newCuboid )

    return newCuboid
  }
  fun removeCuboid( name:String ):Boolean {
    val existingCuboid = doQuery( "SELECT id FROM cuboids WHERE name='${name.replace( ' ', '_' )}'" )

    if ( !existingCuboid.next() ) return false

    val id = existingCuboid.getInt( "id" )

    doUpdatingQuery( "DELETE FROM cuboids WHERE id=$id" )
    doUpdatingQuery( "DELETE FROM cuboids_chunks WHERE cuboidId=$id" )
    doUpdatingQuery( "DELETE FROM cuboids_members WHERE cuboidId=$id" )

    // activeCuboidsChunks.removeAll { it.cuboidId == id }
    cuboidsChunks.removeAll { it.cuboidId == id }

    val cuboid = cuboids.remove( id ) ?: return true

    for ( (x, y, z, world) in cuboid.actionBlocks )
      doUpdatingQuery( "DELETE FROM action_blocks WHERE x=$x and y=$y and z=$z and world='$world'" )

    return true
  }
  fun updateCuboid( cuboid:Cuboid, type:CuboidType ) {
    if ( cuboid.type == CuboidType.TENT ) {
      val playerName = server.getPlayer( UUID.fromString( cuboid.ownerUUID ) )?.displayName ?: return

      for ( (x, y, z, world) in cuboid.actionBlocks )
        doUpdatingQuery( "DELETE FROM action_blocks WHERE x=$x and y=$y and z=$z and world='$world'" )

      actionBlocks.removeAll { it.type == "tent_core" && it.cuboidId == cuboid.id }
      cuboid.actionBlocks.removeAll { it.type == "tent_core" }
      cuboid.name = "Region_gracza_$playerName"
    }

    cuboid.type = type

    doUpdatingQuery( "UPDATE cuboids SET type='$type', name='${cuboid.name}' WHERE id=${cuboid.id}" )
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