package io.cactu.mc.fbsurv

import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.command.CommandSender
import org.bukkit.command.Command
import org.bukkit.block.Block
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.event.Listener
import org.bukkit.event.EventHandler
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.Location
import org.bukkit.WorldCreator

class Plugin: JavaPlugin(), Listener {
  val logsBreakers = setOf( Material.STONE_AXE, Material.IRON_AXE, Material.DIAMOND_AXE )
  val maxCountOfPlanksFromLogs = 3
  val minCountOfPlanksFromLogs = 1
  val maxCountOfGunpowderFromCreeper = 3
  val minCountOfGunpowderFromCreeper = 2

  override fun onEnable() {
    server.pluginManager.registerEvents( this, this )
    server.createWorld( WorldCreator( "world_heaven" ) )
  }
  override fun onCommand( sender:CommandSender, command:Command, label:String, args:Array<String> ):Boolean {
    if ( sender !is Player ) return false

    val player:Player = sender
    val worldname = player.location.world?.name ?: return false

    logger.info( "player is a world" )

    val world =
      if ( worldname == "world" ) Location( server.getWorld( "world_heaven" )!!, 100.0, 57.0, -10.0 )
      else Location( server.getWorld( "world" )!!, -34.0, 71.0, -573.0 )

    logger.info( "world setted" )

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
    val type = block.type
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

  fun random( min:Int, max:Int ):Int {
    return Math.floor( Math.random() * (max - min + 1) ).toInt() + min
  }
}