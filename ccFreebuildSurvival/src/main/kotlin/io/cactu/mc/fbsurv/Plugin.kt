package io.cactu.mc.fbsurv

import io.cactu.mc.doQuery
import io.cactu.mc.chat.createChatInfo
import io.cactu.mc.chat.createChatError
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
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.Recipe
import org.bukkit.inventory.FurnaceRecipe
import org.bukkit.inventory.BlastingRecipe
import org.bukkit.inventory.meta.Damageable
import org.bukkit.inventory.meta.ItemMeta
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

  override fun onEnable() {
    server.pluginManager.registerEvents( this, this )
    server.createWorld( WorldCreator( "world_heaven" ) )

    val recipes = mutableSetOf<Recipe>()
    val recipesIterator = server.recipeIterator()

    while ( recipesIterator.hasNext() ) {
      val recipe = recipesIterator.next()

      if ( recipe is FurnaceRecipe ) when ( recipe.result.type ) {
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

    setPostuments()
  }
  override fun onCommand( sender:CommandSender, command:Command, label:String, args:Array<String> ):Boolean {
    if ( sender !is Player ) return false

    val player:Player = sender
    val worldname = player.location.world?.name ?: return false

    val world =
      if ( worldname == "world" ) Location( server.getWorld( "world_heaven" )!!, 100.5, 58.0, -15.5 )
      else Location( server.getWorld( "world" )!!, -34.5, 71.0, -573.5 )

    player.teleport( world )

    return true
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
          PotionEffect( PotionEffectType.LEVITATION, 80, 50, false, false, false )
        ) )

        return true
      }
    ) )
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

    // if ( player.world.name != "world_heaven" ) return
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
    else if ( itemInMainHand.type == Material.IRON_PICKAXE ) {
      when ( block.type ) {
        Material.REDSTONE_ORE,
        Material.LAPIS_ORE,
        Material.DIAMOND_ORE,
        Material.EMERALD_ORE -> {
          createChatInfo( "Ups. &1Ten surowiec mozesz wydobyć z pomocą TNT lub lepszym kilofem", player)
          e.setDropItems( false )
        }

        else -> {}
      }
    }
    else if ( itemInMainHand.type == Material.STONE_PICKAXE ) {
      if ( block.type == Material.IRON_ORE ) {
        createChatInfo( "Ups. &1Ten surowiec mozesz wydobyć z pomocą TNT lub lepszym kilofem", player)
        e.setDropItems( false )
      }
    }
    else if ( itemInMainHand.type == Material.WOODEN_PICKAXE ) {
      if ( typeStr.contains( "STONE" ) && block.type != Material.COBBLESTONE ) {
        createChatInfo( "Kopanie kamienia drewnem nie nalezy do madrych pomysłów", player)
        e.setDropItems( false )
      }
    }
    else if ( itemInMainHand.type == Material.COAL || itemInMainHand.type == Material.CHARCOAL ) {
      if ( block.type == Material.IRON_ORE && block.getRelative( BlockFace.DOWN ).type == Material.CAMPFIRE ) {
        block.world.dropItem( location, ItemStack( Material.IRON_INGOT, 1 ) )
      }
    }
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
  public fun onInteract( e:PlayerInteractEvent ) {
    val block = e.clickedBlock ?: return

    if ( e.action == Action.RIGHT_CLICK_BLOCK && block.type == Material.DISPENSER ) {
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

  fun random( min:Int, max:Int ):Int {
    return Math.floor( Math.random() * (max - min + 1) ).toInt() + min
  }
}