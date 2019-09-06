package io.cactu.mc.fbsurv

import io.cactu.mc.doQuery
import io.cactu.mc.doUpdatingQuery
import io.cactu.mc.chat.createChatInfo
import io.cactu.mc.chat.createChatError

import java.util.Timer

import kotlin.concurrent.scheduleAtFixedRate

import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.command.CommandSender
import org.bukkit.command.Command
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Directional
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.event.Listener
import org.bukkit.event.EventHandler
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntitySpawnEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.Recipe
import org.bukkit.inventory.FurnaceRecipe
import org.bukkit.inventory.BlastingRecipe
import org.bukkit.inventory.meta.Damageable
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.Difficulty
import org.bukkit.Location
import org.bukkit.WorldCreator

data class ActionBlock( val x:Int, val y:Int, val z:Int, val world:String, val type:String )
data class Postument(
  val name:String,
  val x:Int,
  val y:Int,
  val structureTester: (Int, Int, Int, Block) -> Boolean,
  val additionalTests: (Player, Block) -> Boolean
)

class Plugin: JavaPlugin(), Listener {
  val logsBreakers = setOf( Material.STONE_AXE, Material.IRON_AXE, Material.GOLDEN_AXE, Material.DIAMOND_AXE )
  val maxCountOfPlanksFromLogs = 3
  val minCountOfPlanksFromLogs = 1
  val maxCountOfGunpowderFromCreeper = 3
  val minCountOfGunpowderFromCreeper = 1
  val actionBlocks = mutableSetOf<ActionBlock>()
  val postuments = mutableMapOf<String,Postument>()
  var endOpened = false

  override fun onEnable() {
    server.pluginManager.registerEvents( this, this )
    server.createWorld( WorldCreator( "world_heaven" ) )
    server.getWorld( "world_heaven" )!!.difficulty = Difficulty.HARD

    val serverVarsSQL = doQuery( "SELECT * FROM server_variables WHERE name='endOpened'" )

    while ( serverVarsSQL.next() ) when ( serverVarsSQL.getString( "name" ) ) {
      "endOpened" -> endOpened = serverVarsSQL.getBoolean( "boolean" )
    }

    val recipes = mutableSetOf<Recipe>()
    val recipesIterator = server.recipeIterator()

    while ( recipesIterator.hasNext() ) {
      val recipe = recipesIterator.next()

      if ( recipe is FurnaceRecipe || recipe.result == ItemStack( Material.ENDER_CHEST ) ) when ( recipe.result.type ) {
        Material.ENDER_CHEST,

        Material.COOKED_BEEF,
        Material.COOKED_CHICKEN,
        Material.COOKED_COD,
        Material.COOKED_MUTTON,
        Material.COOKED_PORKCHOP,
        Material.COOKED_RABBIT,
        Material.COOKED_SALMON,
        Material.BAKED_POTATO,
        Material.DRIED_KELP,

        Material.COAL,
        Material.IRON_INGOT,
        Material.GOLD_INGOT,
        Material.REDSTONE,
        Material.LAPIS_LAZULI,
        Material.DIAMOND,
        Material.EMERALD,
        Material.QUARTZ -> {
          // info( "remove ${recipe.result.type} ${recipe is BlastingRecipe}")
          // recipesIterator.remove()
        }

        else -> recipes.add( recipe )
      }
      else recipes.add( recipe )
    }

    server.clearRecipes()
    recipes.forEach { server.addRecipe( it ) }

    val actionBlocksSQL = doQuery( "SELECT * FROM action_blocks WHERE plugin='ccFreebuildSurvival'")

    while ( actionBlocksSQL.next() ) actionBlocks.add( ActionBlock(
      actionBlocksSQL.getInt( "x" ),
      actionBlocksSQL.getInt( "y" ),
      actionBlocksSQL.getInt( "z" ),
      actionBlocksSQL.getString( "world" ),
      actionBlocksSQL.getString( "type" )
    ) )

    server.scheduler.runTaskTimer( this, { _ -> server.onlinePlayers.forEach {
      val worldname = it.world.name
      val inventory = it.inventory

      if ( worldname == "world_heaven" ) {
        if ( !inventory.contains( Material.DRAGON_BREATH ) ) it.damage( 0.5 )
      }
      else if ( worldname == "world_nether" ) {
        if ( inventory.helmet != null || inventory.chestplate != null || inventory.leggings != null || inventory.boots != null )
          it.damage( 0.5 )
      }
    } }, 20, 20 )

    setPostuments()
  }

  fun setPostuments() {
    postuments.set( "elytra_launcher", Postument( "elytra_launcher", 3, 6,
      fun( x:Int, y:Int, z:Int, block:Block ):Boolean = when ( Triple( x, y, z ) ) {
        Triple(  1, 0,  1 ),
        Triple( -1, 0,  1 ),
        Triple( -1, 0, -1 ),
        Triple(  1, 0, -1 ) ->
          if ( block.blockData is Directional && (block.blockData as Directional).facing == BlockFace.DOWN ) true
          else false
        Triple(  1, 1,  1 ),
        Triple( -1, 1,  1 ),
        Triple( -1, 1, -1 ),
        Triple(  1, 1, -1 ) -> if ( "${block.type}".contains( "COBBLESTONE_WALL" ) ) true else false
        Triple(  1, 2,  1 ),
        Triple( -1, 2,  1 ),
        Triple( -1, 2, -1 ),
        Triple(  1, 2, -1 ) ->
          if ( "${block.type}".contains( "FENCE" ) && !"${block.type}".contains( "GATE" ) ) true else false
        Triple(  1, 3,  1 ),
        Triple( -1, 3,  1 ),
        Triple( -1, 3, -1 ),
        Triple(  1, 3, -1 ) -> if ( block.type == Material.END_ROD ) true else false
        Triple(  1, 4,  1 ),
        Triple( -1, 4,  1 ),
        Triple( -1, 4, -1 ),
        Triple(  1, 4, -1 ) -> if ( "${block.type}".contains( "GLASS_PANE" ) ) true else false
        Triple(  0, 1,  0 ),
        Triple(  0, 2,  0 ),
        Triple(  0, 3,  0 ),
        Triple(  0, 4,  0 ),
        Triple(  0, 5,  0 ),
        Triple(  1, 2,  0 ),
        Triple( -1, 2,  0 ),
        Triple(  0, 2,  1 ),
        Triple(  0, 2, -1 ),
        Triple(  1, 3,  0 ),
        Triple( -1, 3,  0 ),
        Triple(  0, 3,  1 ),
        Triple(  0, 3, -1 ),
        Triple(  1, 4,  0 ),
        Triple( -1, 4,  0 ),
        Triple(  0, 4,  1 ),
        Triple(  0, 4, -1 ) -> if ( block.type == Material.AIR ) true else false

        else -> true
      },
      fun( player:Player, dispenser:Block ):Boolean {
        val playerLoc = player.location
        val dispenserLoc = dispenser.location
        val pX = Math.floor( playerLoc.x ).toInt()
        val pY = Math.floor( playerLoc.y ).toInt()
        val pZ = Math.floor( playerLoc.z ).toInt()
        val dX = dispenserLoc.x.toInt()
        val dY = dispenserLoc.y.toInt()
        val dZ = dispenserLoc.z.toInt()

        if ( pX != dX || pZ != dZ || pY < dY ) {
          createChatError( "Aby użyć wyrzutni musisz znajdować się nad nią", player )
          return false
        }
        if ( player.equipment?.chestplate?.type != Material.ELYTRA ) {
          createChatError( "Aby użyć wyrzutni musisz mieć na sobie elytrę", player )
          return false
        }

        player.addPotionEffects( mutableSetOf(
          PotionEffect( PotionEffectType.LEVITATION, 20 * 4, 50, false, false, false )
        ) )

        return true
      }
    ) )
  }

  @EventHandler
  public fun onChangeWorld( e:PlayerChangedWorldEvent ) {
    val player = e.player
    val world = player.world
    val inventory = player.inventory

    if ( world.name == "world_the_end" && !endOpened ) {
      doUpdatingQuery( "UPDATE server_variables SET boolean=false WHERE name='endOpened'" )
      endOpened = true
    }
    else if ( world.name == "world_heaven" ) {
      if ( !inventory.contains( Material.DRAGON_BREATH ) )
        createChatInfo( '!', "&1Bez oddechu smoka tutaj nie przetrwasz! &D7Tutejsze powietrze nie nadaje się do oddychania", player )
    }
    else if ( world.name == "world_nether" ) {
      if ( inventory.helmet != null || inventory.chestplate != null || inventory.leggings != null || inventory.boots != null )
        createChatInfo( '!', "&1Zbroja w gorącym netherze!? &D7 Tu jest tak gorąco, że jakikolwiek element ubioru zabija ze spiekoty", player )
    }
  }
  @EventHandler
  public fun onEntitySpawn( e:EntitySpawnEvent ) {
    if ( e.entityType == EntityType.PHANTOM && e.entity.world.name == "world" ) e.setCancelled( true )
    else if ( e.entityType == EntityType.ENDERMAN && !endOpened ) e.setCancelled( false )
  }
  @EventHandler
  public fun onChunkLoad( e:ChunkLoadEvent ) {
    if ( e.isNewChunk && e.world.name == "world" ) {
      val chunk = e.chunk

      for ( x in 0..15 ) for ( y in 0..30 ) for ( z in 0..15 ) {
        val block = chunk.getBlock( x, y, z )

        if ( block.type == Material.DIAMOND_ORE ) block.setType( Material.EMERALD_ORE )
      }
    }
  }
  @EventHandler
  public fun onPlayerMove( e:PlayerMoveEvent ) {
    val location = e.to ?: return
    val world = location.world ?: return
    val player = e.player

    if ( location.y > 300 && world.name == "world" ) {
      val heaven = server.getWorld( "world_heaven" )!!
      val x = location.x / 8
      val z = location.z / 8

      player.teleport( Location( heaven, x, -20.0, z ) )
    }
    else if ( location.y < -55 && world.name == "world_heaven" ) {
      val earth = server.getWorld( "world" )!!
      val x = location.x * 8
      val z = location.z * 8

      player.teleport( Location( earth, x, 300.0, z) )
    }
  }
  @EventHandler
  public fun onEntityExplode( e:EntityExplodeEvent ) {
    val worldName = e.entity.world.name
    // val blocksList = e.blockList()
    // val blocksListTemp = mutableSetOf<Block>()

    if ( e.entityType == EntityType.CREEPER && (worldName == "world" || worldName == "world_heaven") ){
      e.blockList().clear()
      // for ( block in blocksList )
      //   if ( block.y > 60 ) blocksListTemp.add( block )

      // blocksListTemp.forEach { blocksList.remove( it ) }
    }
  }
  @EventHandler
  public fun onBlockBreak( e:BlockBreakEvent ) {
    val player = e.player

    if ( player.gameMode == GameMode.CREATIVE ) return

    val block = e.block
    val world = block.world
    val location = block.location
    val typeStr = "${block.type}"
    val itemInMainHand = player.inventory.itemInMainHand

    if ( typeStr.contains( "_LOG" ) && !logsBreakers.contains( itemInMainHand.type ) ) {
      val planksCount = random( minCountOfPlanksFromLogs, maxCountOfPlanksFromLogs )

      e.setDropItems( false )

      if      ( typeStr.contains( "OAK" ) )      world.dropItem( location, ItemStack( Material.OAK_PLANKS,      planksCount ) )
      else if ( typeStr.contains( "BIRCH" ) )    world.dropItem( location, ItemStack( Material.BIRCH_PLANKS,    planksCount ) )
      else if ( typeStr.contains( "SPRUCE" ) )   world.dropItem( location, ItemStack( Material.SPRUCE_PLANKS,   planksCount ) )
      else if ( typeStr.contains( "JUNGLE" ) )   world.dropItem( location, ItemStack( Material.JUNGLE_PLANKS,   planksCount ) )
      else if ( typeStr.contains( "DARK_OAK" ) ) world.dropItem( location, ItemStack( Material.DARK_OAK_PLANKS, planksCount ) )
      else if ( typeStr.contains( "ACACIA" ) )   world.dropItem( location, ItemStack( Material.ACACIA_PLANKS,   planksCount ) )
      else e.setDropItems( true )
    }
    else if ( block.type == Material.IRON_ORE && block.getRelative( BlockFace.DOWN ).type == Material.CAMPFIRE ) {
      when ( itemInMainHand.type ) {
        Material.STONE_PICKAXE,
        Material.IRON_PICKAXE,
        Material.DIAMOND_PICKAXE -> {
          block.world.dropItem( location, ItemStack( Material.IRON_INGOT, 1 ) )
          e.setDropItems( false )
        }

        else -> {}
      }
    }
    // else if ( itemInMainHand.type == Material.COAL || itemInMainHand.type == Material.CHARCOAL ) {
    //   if ( block.type == Material.IRON_ORE && block.getRelative( BlockFace.DOWN ).type == Material.CAMPFIRE ) {
    //     block.world.dropItem( location, ItemStack( Material.IRON_INGOT, 1 ) )
    //   }
    // }
    else if ( itemInMainHand.type == Material.FLINT ) {
      if ( block.type == Material.IRON_ORE && block.getRelative( BlockFace.UP ).type == Material.LAVA ) {
        val flintAndSteel = ItemStack( Material.FLINT_AND_STEEL, 1 )
        val meta = flintAndSteel.itemMeta!! as Damageable

        meta.damage = 63

        flintAndSteel.itemMeta = meta as ItemMeta

        player.inventory.removeItem( ItemStack( Material.FLINT, 1 ) )
        player.inventory.addItem( flintAndSteel )
      }
    }
    else if ( player.world.name == "world_heaven" ) {
      if ( itemInMainHand.type == Material.IRON_PICKAXE ) {
        when ( block.type ) {
          Material.REDSTONE_ORE,
          Material.LAPIS_ORE,
          Material.DIAMOND_ORE,
          Material.EMERALD_ORE -> {
            createChatInfo( "Zdecydowanie za twarde. &1Wydobędziesz ten surowiec za pomocą TNT lub lepszym kilofem", player)
            e.setDropItems( false )
          }

          else -> {}
        }
      }
      else if ( itemInMainHand.type == Material.STONE_PICKAXE ) {
        if ( block.type == Material.IRON_ORE ) {
          createChatInfo( "Zdecydowanie za twarde. &1Wydobędziesz ten surowiec za pomocą TNT lub lepszym kilofem", player)
          e.setDropItems( false )
        }
      }
      else if ( itemInMainHand.type == Material.WOODEN_PICKAXE ) {
        if ( typeStr.contains( "STONE" ) && block.type != Material.COBBLESTONE ) {
          createChatInfo( "Tutejszy kamień jest twardszy niż ten na ziemi. Drewnem go nie wykopiesz", player)
          e.setDropItems( false )
        }
      }
    }
  }
  @EventHandler
  public fun onEntityDeath( e:EntityDeathEvent ) {
    val entity = e.entity

    if ( entity.type == EntityType.CREEPER ) {
      val gunpowderCount = random( minCountOfGunpowderFromCreeper, maxCountOfGunpowderFromCreeper )

      entity.world.dropItem( entity.location, ItemStack( Material.GUNPOWDER, gunpowderCount ) )
      e.drops.clear()
    }
  }
  @EventHandler
  public fun onPlayerInteract( e:PlayerInteractEvent ) {
    val block = e.clickedBlock ?: return

    if ( e.action == Action.RIGHT_CLICK_BLOCK && block.type == Material.DISPENSER && !e.player.isSneaking() ) {
      val player = e.player
      val location = block.location
      val intX = location.x.toInt()
      val intY = location.y.toInt()
      val intZ = location.z.toInt()
      val world = location.world?.name ?: return
      val actionBlock = actionBlocks.find {
        it.x == intX && it.y == intY && it.z == intZ && it.world == world
      } ?: return
      val postument = postuments.get( actionBlock.type ) ?: return
      val postumentX:Int = postument.x / 2
      val postumentY:Int = postument.y - 1

      e.setCancelled( true )

      for ( x in -postumentX..postumentX ) for ( z in -postumentX..postumentX ) for ( y in 0..postumentY )
        if ( !postument.structureTester( x, y, z, block.getRelative( x, y, z ) ) ) {
          createChatError( "Ten postument ma niewłaściwą strukturę!", player )
          return
        }

      if ( !postument.additionalTests( player, block ) ) return
    }
  }
  @EventHandler
  public fun onPlayerEntityInteract( e:PlayerInteractEntityEvent ) {
    val entity = e.rightClicked

    if ( entity.type == EntityType.VILLAGER ) {
      createChatInfo( "Akcja tymczasowo niemożliwa", e.player )
      e.setCancelled( true )
    }
  }

  fun random( min:Int, max:Int ):Int {
    return Math.floor( Math.random() * (max - min + 1) ).toInt() + min
  }
}