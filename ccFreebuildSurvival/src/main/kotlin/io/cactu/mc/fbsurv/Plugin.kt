package io.cactu.mc.fbsurv

import io.cactu.mc.doQuery
import io.cactu.mc.chat.createChatInfo
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.command.CommandSender
import org.bukkit.command.Command
import org.bukkit.block.Block
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.event.Listener
import org.bukkit.event.EventHandler
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.player.PlayerInteractEvent
// import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.Location
import org.bukkit.WorldCreator

data class ActionBlock( val x:Int, val y:Int, val z:Int, val world:String, val type:String )

class Plugin: JavaPlugin(), Listener {
  val logsBreakers = setOf( Material.STONE_AXE, Material.IRON_AXE, Material.DIAMOND_AXE )
  val maxCountOfPlanksFromLogs = 3
  val minCountOfPlanksFromLogs = 1
  val maxCountOfGunpowderFromCreeper = 3
  val minCountOfGunpowderFromCreeper = 1
  val actionBlocks = mutableSetOf<ActionBlock>()

  override fun onEnable() {
    server.pluginManager.registerEvents( this, this )
    server.createWorld( WorldCreator( "world_heaven" ) )

    val actionBlocksSQL = doQuery( "SELECT * FROM action_blocks WHERE plugin='ccFreebuildSurvival'")

    while ( actionBlocksSQL.next() ) actionBlocks.add( ActionBlock(
      actionBlocksSQL.getInt( "x" ),
      actionBlocksSQL.getInt( "y" ),
      actionBlocksSQL.getInt( "z" ),
      actionBlocksSQL.getString( "world" ),
      actionBlocksSQL.getString( "type" )
    ) )
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

    if ( player.world.name != "world_heaven" ) return
    if ( player.gameMode == GameMode.CREATIVE ) return

    val block = e.block
    val world = block.world
    val location = block.location
    val typeStr = "${block.type}"
    val itemInMainHand = player.inventory.itemInMainHand

    if ( typeStr.contains( "_LOG" ) && !logsBreakers.contains( itemInMainHand.type ) ) {
      val planksCount = random( minCountOfPlanksFromLogs, maxCountOfPlanksFromLogs )

      e.setDropItems( false )

      if ( typeStr.contains( "OAK" ) )           world.dropItem( location, ItemStack( Material.OAK_PLANKS, planksCount ) )
      else if ( typeStr.contains( "BIRCH" ) )    world.dropItem( location, ItemStack( Material.BIRCH_PLANKS, planksCount ) )
      else if ( typeStr.contains( "SPRUCE" ) )   world.dropItem( location, ItemStack( Material.SPRUCE_PLANKS, planksCount ) )
      else if ( typeStr.contains( "JUNGLE" ) )   world.dropItem( location, ItemStack( Material.JUNGLE_PLANKS, planksCount ) )
      else if ( typeStr.contains( "DARK_OAK" ) ) world.dropItem( location, ItemStack( Material.DARK_OAK_PLANKS, planksCount ) )
      else if ( typeStr.contains( "ACACIA" ) )   world.dropItem( location, ItemStack( Material.ACACIA_PLANKS, planksCount ) )
      else e.setDropItems( true )
    }
    else if ( typeStr.contains( "STONE" ) && itemInMainHand.type == Material.WOODEN_PICKAXE ) {
      e.setDropItems( false )
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
  public fun onInventoryClose( e:PlayerInteractEvent ) {
    val block = e.clickedBlock ?: return

    if ( e.action == Action.RIGHT_CLICK_BLOCK && block.type == Material.DISPENSER ) {
      val player = e.player
      val location = block.location
      val x = location.x.toInt()
      val y = location.y.toInt()
      val z = location.z.toInt()
      val world = location.world?.name ?: return
      val actionBlock = actionBlocks.find { it.x == x && it.y == y && it.z == z && it.world == world } ?: return

      e.setCancelled( true )

      when ( actionBlock.type ) {
        "elytra_launcher" -> {
          if ( player.equipment?.chestplate?.type == Material.ELYTRA ) player.addPotionEffects( mutableSetOf(
            PotionEffect( PotionEffectType.LEVITATION, 80, 50, false, false, false )
          ) )
          else createChatInfo( "Aby użyć wyrzutni musisz mieć na sobie elytrę", player )
        }
      }
    }
  }

  fun random( min:Int, max:Int ):Int {
    return Math.floor( Math.random() * (max - min + 1) ).toInt() + min
  }
}