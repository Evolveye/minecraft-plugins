package io.cactu.mc.chat

import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.event.Listener
import org.bukkit.event.EventHandler
import org.bukkit.event.player.*
import org.bukkit.ChatColor

public class App: JavaPlugin(), Listener {
  override fun onEnable() {
    logger.info( "Plugin enabled" )

    getServer().getPluginManager().registerEvents( this, this )
  }

  @EventHandler
  public fun onJoin( e:PlayerJoinEvent ) {
    val player = e.getPlayer()

    player.sendMessage( "Dzień dobry ${e.getPlayer().getName()}" )
  }

  @EventHandler
  public fun onChat( e:AsyncPlayerChatEvent ) {
    e.setFormat( "${ChatColor.GRAY}%s${ChatColor.RESET}:${ChatColor.GRAY} %s" )
  }
}