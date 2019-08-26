package io.cactu.mc.fbsurv

import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.command.CommandSender
import org.bukkit.command.Command
import org.bukkit.block.Block
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.Location
import org.bukkit.WorldCreator

class Plugin: JavaPlugin() {
  override fun onEnable() {
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
}