package io.cactu.mc.cuboids

import io.cactu.mc.chat.createChatInfo
import io.cactu.mc.chat.createChatError
import io.cactu.mc.chat.createChatMode
import io.cactu.mc.chat.createModuledChatMessage
import io.cactu.mc.chat.ModuledChatMessage
import io.cactu.mc.doQuery
import io.cactu.mc.doUpdatingQuery
import io.cactu.mc.players.playerName

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
import org.bukkit.entity.TNTPrimed
import org.bukkit.inventory.ItemStack
import org.bukkit.event.Listener
import org.bukkit.event.EventHandler
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent

enum class CuboidType { TENT, REGION, COLONY, RESERVE }
data class CuboidChunkCost( val ironIngots:Int, val emeralds:Int )
data class ActionBlock( val x:Int, val y:Int, val z:Int, val world:String, val cuboidId:Int, val type:String )
data class CuboidChunk( val x:Int, val z:Int, val world:String, val cuboidId:Int )
data class CuboidMember( val UUID:String, val cuboidId:Int, val owner:Boolean, val manager:Boolean )
data class Cuboid(
  val id:Int,
  val ownerUUID:String,
  var name:String,
  var type:CuboidType,
  val members:MutableMap<String,CuboidMember>,
  var parentId:Int?=null
)

class Plugin: JavaPlugin(), Listener {
  companion object messages {
    val youCannotInfereHere = "Nie możesz ingerować na tym obszarze!"
    val youCannotPlaceCampfire = "Ogniska można stawiać jedynie na zabezpieczonym terenie, oraz gdy nie posiada się obozowiska"
    val youEnteredACuboid = "Wkroczyłeś na"
    val youLeavedACuboid = "Opuszczony teren"
    val nobodyTerrain = "Ten teren do nikogo nie należy"
    val cuboidName = "Nazwa"
    val cuboidSize = "Powierzchnia"
    val cuboidColoniesSize = "Powierzchnia kolonii"
    val cuboidOwner = "Właściciel"
    val cuboidManagers = "Zarządcy"
    val cuboidMembers = "Mieszkańcy"
    val tentTooCloseToAnotherCuboid = "Znajdujesz się zbyt blisko jakiegoś regionu aby zabezpieczyć ten chunk"
    val tentCreated = "Obozowisko &3rozbite pomyślnie"
    val tentRemoved = "Obozowisko &3rozebrane pomyślnie"
    val regionCreated = "&3Region utworzony pomyślnie"
    val regionExpanded = "&3Region powiększony pomyślnie"
    val colonyCreated = "&3Kolonia utworzona pomyślnie"
    val colonyExpanded = "&3Kolonia powiększona pomyślnie"
    val chunkForSell = "Chunk na sprzedaż"
    val chunkForBuy = "Chunk do kupienia"
    val chunkForBuyNeededItems = "Wymagane przedmioty"
    val chunkForBuyIronIngots = "Sztabki żelaza"
    val chunkForBuyEmeralds = "Emeraldy"
    val buyChunk = "&1&uWykup ten teren"
    val buy = "&1&ukup"
    val sell = "&1&usprzedaj"
    val noActions = "Brak akcji"
    val infoAboutCuboid = "Informacje o regionie"
    val tentToRegionPosibility = "&3Masz możliwość stworzenia regionu"
    val tentToRegionInability = "Aby kupić region potrzebujesz bloku emeraldu"
    val tentToColonyPosibility = "&3Masz możliwość stworzenia kolonii"
    val tentToColonyInability = "Aby kupić kolonię potrzebujesz 5 bloków emeraldu"
  }

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
      val actionBlock = ActionBlock(
        actionBlocksSQL.getInt( "x" ),
        actionBlocksSQL.getInt( "y" ),
        actionBlocksSQL.getInt( "z" ),
        actionBlocksSQL.getString( "world" ),
        cuboidId,
        actionBlocksSQL.getString( "type" )
      )

      actionBlocks.add( actionBlock )
    }
  }
  override fun onTabComplete( sender:CommandSender, command:Command, label:String, args:Array<String> ):List<String>? {
    if ( args.size == 1 ) return if ( sender.isOp() ) listOf( "create", "remove", "buy", "sell" ) else listOf( "buy", "sell" )
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
    else if ( args[ 0 ] == "create" && (sender !is Player || sender.isOp()) ) {
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
    else if ( args[ 0 ] == "buy" && sender is Player ) {
      val argX:Int? = if ( args.size >= 3 ) args[ 1 ].toIntOrNull() else null
      val argZ:Int? = if ( args.size >= 3 ) args[ 2 ].toIntOrNull() else null
      val chunk = if ( args.size >= 3 && argX != null && argZ != null ) sender.world.getChunkAt( argX, argZ ) else sender.location.chunk
      val cuboidToExpand = cuboidWhichBeExpandedByChunk( chunk )
      val senderRegion = getCuboid( sender, CuboidType.REGION )
      val cuboidChunk = getCuboid( chunk )
      val inventory = sender.inventory

      if ( cuboidChunk != null ) {
        if ( cuboidChunk.type == CuboidType.TENT && cuboidChunk.ownerUUID == sender.uniqueId.toString() ) {
          if ( inventory.contains( Material.EMERALD_BLOCK, 1 ) && senderRegion == null ) {
            inventory.removeItem( ItemStack( Material.EMERALD_BLOCK, 1 ) )

            updateCuboid( cuboidChunk, CuboidType.REGION )

            createChatInfo( messages.regionCreated, sender )
          }
          else if ( inventory.contains( Material.EMERALD_BLOCK, 5 ) && senderRegion != null ) {
            inventory.removeItem( ItemStack( Material.EMERALD_BLOCK, 5 ) )

            updateCuboid( cuboidChunk, CuboidType.COLONY, senderRegion.id )

            createChatInfo( messages.colonyCreated, sender )
          }
          else createChatError( "Nie stać Cię na kupno regionu!", sender )
        }
        else createChatError( "Nie możesz wykupić tego terenu!", sender )
      }
      else if ( senderRegion != null && cuboidToExpand != null ) {
        val cuboidMember = getCuboidMember( cuboidToExpand, sender.uniqueId.toString(), true )

        if ( cuboidMember != null && cuboidMember.manager ) {
          val cost = getNextCuboidChunkCost( sender )!!
          val emeralds = inventory.contains( Material.EMERALD, cost.emeralds )
          val ironIngots = inventory.contains( Material.IRON_INGOT, cost.ironIngots )

          if ( emeralds && ironIngots ) {
            val message = if ( cuboidToExpand.type == CuboidType.REGION ) messages.regionExpanded else messages.colonyExpanded

            inventory.removeItem(
              ItemStack( Material.EMERALD, cost.emeralds ),
              ItemStack( Material.IRON_INGOT, cost.ironIngots )
            )

            createCuboidChunk( cuboidToExpand, chunk )

            createChatInfo( message, sender )
          }
          else createChatError( "Nie stać Cię na wykupienie tego chunka!", sender )
        }
        else createChatError( "Nie możesz wykupić tego terenu!", sender )
      }
      else createChatError( "Nie możesz wykupić tego terenu!", sender )
    }
    else if ( args[ 0 ] == "sell" && sender is Player ) {
      val argX:Int? = if ( args.size >= 3 ) args[ 1 ].toIntOrNull() else null
      val argZ:Int? = if ( args.size >= 3 ) args[ 2 ].toIntOrNull() else null
      val chunk = if ( args.size >= 3 && argX != null && argZ != null ) sender.world.getChunkAt( argX, argZ ) else sender.location.chunk
      val cuboidChunk = getCuboid( chunk )

      if ( cuboidChunk != null ) {
        val cuboidMember = getCuboidMember( cuboidChunk, sender.uniqueId.toString() )!!

        if ( cuboidMember.manager ) {
          if ( canChunkBeSaled( chunk ) ) {
            val cost = getNextCuboidChunkCost( sender )!!
            val cuboidChunks = cuboidsChunks.filter { it.cuboidId == cuboidChunk.id }

            if ( cuboidChunks.size == 1 ) {
              removeCuboid( cuboidChunk )
              createChatInfo( "&3Region usunięty pomyślnie", sender )
            }
            else {
              val neighbours = mutableSetOf<CuboidChunk>()
              val baseX = chunk.x
              val baseZ = chunk.z

              for ( x in -1..1 ) for ( z in -1..1 ) if ( (x == 0) xor (z == 0) ) {
                val neighbour = cuboidChunks.find { it.x == baseX + x && it.z == baseZ + z } ?: continue

                neighbours.add( neighbour )
              }

              if ( neighbours.size > 1 && !connectionBetweenCuboidChunks( neighbours ) )
                createChatError( "Nie możesz usunać tego chunka, poniewaz podzieli on region na części!", sender )
              else {
                sender.inventory.addItem(
                  ItemStack( Material.EMERALD, (cost.emeralds * 0.8).toInt() ),
                  ItemStack( Material.IRON_INGOT, (cost.ironIngots * 0.8).toInt() )
                )

                removeCuboidChunk( chunk )
                createChatInfo( "&3Chunk wypisany z regionu pomyślnie", sender )
              }
            }
          }
          else createChatError( "Ten chunk nie jest na sprzedaż!", sender )
        }
        else createChatError( "Nie posiadasz uprawnień aby to zrobić!", sender )
      }
      else createChatError( "Nie znajdujesz się na cuboidzie, który mógłbyś sprzedać!", sender )
    }
    else if ( args[ 0 ] == "remove" && (sender !is Player || sender.isOp())  ) {
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

    val message = if ( cuboidChunkFrom == null ) messages.youEnteredACuboid else messages.youLeavedACuboid
    val cuboid = cuboids.get( (if ( cuboidChunkFrom == null ) cuboidChunkTo else cuboidChunkFrom)!!.cuboidId )!!
    val name = ("&7"
      + (if ( cuboid.parentId == 0 ) "" else cuboids.get( cuboid.parentId )!!.name.replace( '_', ' ' ) + " &D7::&7 ")
      + cuboid.name.replace( '_', ' ' )
    )

    createChatInfo( "$message: $name", e.player )
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

    if ( e.action == Action.LEFT_CLICK_BLOCK && player.inventory.itemInMainHand.type == Material.LANTERN ) {
      val cuboidOnChunk = getCuboid( chunk )
      val region = getCuboid( player, CuboidType.REGION )

      if ( cuboidOnChunk != null && cuboidOnChunk.type == CuboidType.TENT ) {
        if ( region == null ) {

            // Create region
            if ( player.inventory.contains( Material.EMERALD_BLOCK ) )
              createModuledChatMessage( "${messages.tentToRegionPosibility}&D7: " )
                .addNextText( messages.buy )
                .clickCommand( "/cuboids buy ${chunk.x} ${chunk.z}" )
                .sendTo( player )
            else createModuledChatMessage( messages.tentToRegionInability ).sendTo( player )

        }
        else {

            // Create colony
            if ( player.inventory.contains( Material.EMERALD_BLOCK, 5 ) )
              createModuledChatMessage( "${messages.tentToColonyPosibility}&D7: " )
                .addNextText( messages.buy )
                .clickCommand( "/cuboids buy ${chunk.x} ${chunk.z}" )
                .sendTo( player )
            else createModuledChatMessage( messages.tentToColonyInability ).sendTo( player )

        }
      }
      else {
        val info = createModuledChatMessage( "&3${messages.infoAboutCuboid}:\n" )

        if ( cuboidOnChunk != null ) {
          val allMembers = cuboidOnChunk.members.values
          val managers = allMembers.filter { it.manager }.map { (UUID) -> playerName( UUID ) }.joinToString( ", " )
          val members = allMembers.map { (UUID) -> playerName( UUID ) }.joinToString( ", " )
          val size = cuboidsChunks.filter { it.cuboidId == cuboidOnChunk.id }.size
          val coloniesSize = cuboidsChunks.filter { getCuboid( it.cuboidId )!!.parentId == cuboidOnChunk.id }.size

          info
            .addNextText( " &3-&7 ${messages.cuboidName}: &1${cuboidOnChunk.name.replace( '_', ' ' )}\n" )
            .addNextText( " &3-&7 ${messages.cuboidSize}: &1$size\n" )
            .addNextText( " &3-&7 ${messages.cuboidColoniesSize}: &1$coloniesSize\n" )
            .addNextText( " &3-&7 ${messages.cuboidOwner}: &1${playerName( cuboidOnChunk.ownerUUID )}\n" )
            .addNextText( " &3-&7 ${messages.cuboidManagers}: &1$managers\n" )
            .addNextText( " &3-&7 ${messages.cuboidMembers}: &1$members\n" )
        }
        else info.addNextText( "   &7&i${messages.nobodyTerrain}\n\n")

        if ( region != null ) {
          val cost = getNextCuboidChunkCost( player )!!

          if ( cuboidOnChunk == null || cuboidOnChunk.type == CuboidType.RESERVE ) {

              // Buy chunk
              if ( canPlayerBuyChunk( player, chunk ) ) info
                .addNextText( "   ${messages.buyChunk}"  )
                .clickCommand( "/cuboids buy ${chunk.x} ${chunk.z}" )
                .hoverText( "&7${messages.chunkForBuyIronIngots}: &3${cost.ironIngots}\n&7${messages.chunkForBuyEmeralds}: &3${cost.emeralds}" )
              else info.addNextText( "   &7&i${messages.noActions}" )

          }
          else if ( cuboidOnChunk.type == CuboidType.REGION || cuboidOnChunk.type == CuboidType.COLONY ) {

              // Sell chunk
              if ( canChunkBeSaled( chunk ) ) info
                .addNextText( " &3-&7 ${messages.chunkForSell}: " )
                .addNextText( messages.sell )
                .clickCommand( "/cuboids sell ${chunk.x} ${chunk.z}" )

          }
        }

        info.sendTo( player )
      }

      return e.setCancelled( true )
    }
    else if ( cuboidMember == null && isChunkCuboid( chunk ) ) {
      if ( !typeStr.contains( "STONE" ) && (typeStr.contains( "BUTTON" ) || typeStr.contains( "PLATE" ) ) ) return
      if ( typeStr.contains( "DOOR" ) && block.type != Material.IRON_DOOR ) return

      createChatError( messages.youCannotInfereHere, player )

      return e.setCancelled( true )
    }
  }
  @EventHandler
  public fun onDamage( e:EntityDamageByEntityEvent ) {
    val damager = e.damager

    if ( damager !is Player ) return
    if ( !canPlayerInfere( e.entity.location.chunk, damager ) ) {
      createChatError( messages.youCannotInfereHere, damager )
      e.setCancelled( true )
    }
  }
  @EventHandler
  public fun onBlockPlace( e:BlockPlaceEvent ) {
    val block = e.blockPlaced
    val player = e.player

    if ( block.type == Material.CAMPFIRE ) {
      val tent = getCuboid( player, CuboidType.TENT )
      val cuboidMember = getCuboidMember( block.chunk, player.uniqueId.toString() )
      val goodDistance = isGoodPlaceForCuboid( block.chunk, CuboidType.TENT )

      if ( cuboidMember == null ) {
        if ( goodDistance ) {
          if ( tent == null ) {
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

            actionBlocks.add( tentCore )
            createChatInfo( messages.tentCreated, player )
          }
          else {
            createChatInfo( messages.youCannotPlaceCampfire, player )
            e.setCancelled( true )
          }
        }
        else {
          createChatInfo( messages.tentTooCloseToAnotherCuboid, player )
          e.setCancelled( true )
        }
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
      createChatInfo( messages.tentRemoved, player )
    }
  }

  fun isGoodPlaceForCuboid( chunk:Chunk, type:CuboidType ):Boolean {
    val newCuboidX = chunk.x
    val newCuboidZ = chunk.z
    val REGION = CuboidType.REGION

    for ( cuboidChunk in cuboidsChunks ) {
      if ( getCuboid( cuboidChunk.cuboidId )!!.type == CuboidType.RESERVE ) continue

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
    val cuboidWhichCanBeExpanded = cuboidWhichBeExpandedByChunk( chunk ) ?: return false

    return getCuboidMember( cuboidWhichCanBeExpanded, player.uniqueId.toString() )?.manager ?: false
  }
  fun canChunkBeSaled( chunk:Chunk ):Boolean {
    val world = chunk.world.name
    val baseX = chunk.x
    val baseZ = chunk.z
    val cuboidChunk = getCuboidChunk( baseX, baseZ, world ) ?: return false

    if ( getCuboid( cuboidChunk.cuboidId )!!.type == CuboidType.TENT ) return false

    for ( x in -1..1 ) for ( z in -1..1 ) if ( (Math.abs( x ) == 1) xor (Math.abs( z ) == 1) )
      getCuboidChunk( baseX + x, baseZ + z, world ) ?: return true

    return false
  }
  fun canPlayerInfere( chunk:Chunk, player:Player ):Boolean {
    return canPlayerInfere( chunk, player.uniqueId.toString() )
  }
  fun canPlayerInfere( chunk:Chunk, playerUUID:String ):Boolean {
    val cuboidId = getCuboidChunk( chunk.x, chunk.z, chunk.world.name )?.cuboidId ?: return true

    return canPlayerInfere( cuboidId, playerUUID )
  }
  fun canPlayerInfere( cuboidId:Int, playerUUID:String ):Boolean {
    val cuboid = getCuboid( cuboidId )!!
    val cuboidMember = getCuboidMember( cuboid, playerUUID )

    if ( cuboidMember != null ) return true
    else if ( cuboid.parentId == null ) return false
    else return canPlayerInfere( cuboid.parentId!!, playerUUID )
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
  fun getCuboidMember( chunk:Chunk, playerUUID:String, deepTest:Boolean=false ):CuboidMember? {
    val cuboidId = getCuboidChunk( chunk.x, chunk.z, chunk.world.name )?.cuboidId ?: return null

    return getCuboidMember( getCuboid( cuboidId )!!, playerUUID, deepTest )
  }
  fun getCuboidMember( cuboidId:Int, playerUUID:String, deepTest:Boolean=false ):CuboidMember? {
    return getCuboidMember( getCuboid( cuboidId )!!, playerUUID, deepTest )
  }
  fun getCuboidMember( cuboid:Cuboid, playerUUID:String, deepTest:Boolean=false ):CuboidMember? {
    for ( member in cuboid.members.values ) if ( member.UUID == playerUUID ) return member

    if ( deepTest && cuboid.parentId != null ) return getCuboidMember(
      getCuboid( cuboid.parentId!! )!!,
      playerUUID,
      deepTest
    )

    return null
  }
  fun cuboidWhichBeExpandedByChunk( chunk:Chunk ):Cuboid? {
    val world = chunk.world.name
    val baseX = chunk.x
    val baseZ = chunk.z
    var chunkNeighbour:Cuboid? = null

    for ( x in -1..1 ) for ( z in -1..1 ) if ( x != 0 || z != 0 ) {
      val neighbour = getCuboidChunk( baseX + x, baseZ + z, world ) ?: continue
      val cuboidOnChunk = getCuboid( neighbour.cuboidId )!!

      if ( chunkNeighbour != null && neighbour.cuboidId != chunkNeighbour.id ) return null
      if ( (x == 0) xor (z == 0) ) chunkNeighbour = cuboidOnChunk
    }

    return chunkNeighbour
  }
  fun getNextCuboidChunkCost( player:Player ):CuboidChunkCost? {
    val cuboid = getCuboid( player ) ?: return null

    return getNextCuboidChunkCost( cuboid.id )
  }
  fun getNextCuboidChunkCost( cuboid:Cuboid ):CuboidChunkCost = getNextCuboidChunkCost( cuboid.id )
  fun getNextCuboidChunkCost( cuboidId:Int ):CuboidChunkCost {
    val chunksCount = cuboidsChunks.filter { it.cuboidId == cuboidId }.size

    return CuboidChunkCost( chunksCount % 5, chunksCount / 5 * 2 )
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

    val cuboid = Cuboid(
      id,
      cuboidFromQuery.getString( "ownerUUID" ),
      cuboidFromQuery.getString( "name" ),
      CuboidType.valueOf( cuboidFromQuery.getString( "type" ) ),
      members,
      cuboidFromQuery.getInt( "parentCuboidId" )
    )

    return cuboid
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
  fun createCuboidChunk( cuboid:Cuboid, chunk:Chunk ) {
    val id = cuboid.id
    val world = chunk.world.name
    val x = chunk.x
    val z = chunk.z

    doUpdatingQuery(
      "INSERT INTO cuboids_chunks (cuboidId, world, x, z) VALUES ($id, '$world', $x, $z)"
    )

    cuboidsChunks.add( CuboidChunk( x, z, world, id ) )
  }
  fun removeCuboid( cuboid:Cuboid ) = removeCuboid( cuboid.name )
  fun removeCuboid( name:String ):Boolean {
    val existingCuboid = doQuery( "SELECT id FROM cuboids WHERE name='${name.replace( ' ', '_' )}'" )

    if ( !existingCuboid.next() ) return false

    val id = existingCuboid.getInt( "id" )

    cuboids.forEach { _, it ->
      val idToRemove = it.id

      if ( idToRemove == id || it.parentId == id && it.type == CuboidType.COLONY ) {
        // activeCuboidsChunks.removeAll { it.cuboidId == idToRemove }
        cuboidsChunks.removeAll { it.cuboidId == idToRemove }

        doUpdatingQuery( "DELETE FROM cuboids WHERE id=$idToRemove or type='COLONY' and parentCuboidId=$idToRemove" )
        doUpdatingQuery( "DELETE FROM cuboids_chunks WHERE cuboidId=$idToRemove" )
        doUpdatingQuery( "DELETE FROM cuboids_members WHERE cuboidId=$idToRemove" )
      }
    }

    val cuboid = cuboids.remove( id ) ?: return true

    for ( (x, y, z, world) in actionBlocks.filter { it.cuboidId == cuboid.id } )
      doUpdatingQuery( "DELETE FROM action_blocks WHERE x=$x and y=$y and z=$z and world='$world'" )

    return true
  }
  fun removeCuboidChunk( chunk:Chunk ):Boolean {
    val cuboid = getCuboid( chunk ) ?: return false
    val id = cuboid.id
    val world = chunk.world.name
    val x = chunk.x
    val z = chunk.z

    doUpdatingQuery(
      "DELETE FROM cuboids_chunks WHERE cuboidId=$id and world='$world' and x=$x and z=$z"
    )

    cuboidsChunks.removeIf { it.cuboidId == id && it.world == world && it.x == x && it.z == z }

    return true
  }
  fun updateCuboid( cuboid:Cuboid, type:CuboidType, parentId:Int?=null ) {
    if ( cuboid.type == CuboidType.TENT ) {
      val playerName = server.getPlayer( UUID.fromString( cuboid.ownerUUID ) )?.displayName ?: return

      for ( (x, y, z, world) in actionBlocks.filter { it.cuboidId == cuboid.id } )
        doUpdatingQuery( "DELETE FROM action_blocks WHERE x=$x and y=$y and z=$z and world='$world'" )

      actionBlocks.removeAll { it.type == "tent_core" && it.cuboidId == cuboid.id }

      when ( type ) {
        CuboidType.REGION -> cuboid.name = "Region_gracza_$playerName"
        CuboidType.COLONY -> cuboid.name = "Kolonia_gracza_$playerName"

        else -> {}
      }
    }

    val newParentId = if ( parentId != null ) parentId else cuboid.parentId

    cuboid.type = type
    cuboid.parentId = newParentId

    doUpdatingQuery(
      "UPDATE cuboids SET type='$type', name='${cuboid.name}', parentCuboidId=$newParentId WHERE id=${cuboid.id}"
    )
  }

  fun connectionBetweenCuboidChunks( chunksToCheck:MutableSet<CuboidChunk> ):Boolean { // A*
    if ( chunksToCheck.isEmpty() ) return true

    val startChunk = chunksToCheck.first()
    val closedSet = mutableSetOf<CuboidChunk>()
    val openSet = chunksToCheck
      .filter { it.cuboidId != startChunk.cuboidId }
      .toMutableSet()

    fun checkNeighbours( chunk:CuboidChunk? ):Int {
      if ( chunk == null || closedSet.find { it == chunk} != null ) return 0

      chunksToCheck.remove( chunk )
      openSet.remove( chunk )
      closedSet.add( chunk )

      if ( openSet.isEmpty() ) return -1
      if ( chunksToCheck.isEmpty() ) return 1

      val up =    checkNeighbours( openSet.find { it.x == chunk.x && it.z == chunk.z + 1 } )
      val down =  checkNeighbours( openSet.find { it.x == chunk.x && it.z == chunk.z - 1 } )
      val left =  checkNeighbours( openSet.find { it.x == chunk.x - 1 && it.z == chunk.z } )
      val right = checkNeighbours( openSet.find { it.x == chunk.x + 1 && it.z == chunk.z } )

      if ( up != 0 )    return up
      if ( down != 0 )  return down
      if ( left != 0 )  return left
      if ( right != 0 ) return right

      return 0
    }

    return if ( checkNeighbours( startChunk ) == 1 ) true else false
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