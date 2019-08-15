package io.cactu.mc.chat

import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.event.Listener
import org.bukkit.event.EventHandler
import org.bukkit.event.player.*
import org.bukkit.ChatColor

data class ChatInfo(
  val prefix:Char,
  val nickColor:ChatColor,
  val messageColor:ChatColor,
  val signsColor:ChatColor,
  val message:String
)

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
    val chatModesPrefixex = ".!@"
    val message = e.getMessage()

    if ( chatModesPrefixex.contains( message[ 0 ] ) && message.length == 1 )
      return e.setCancelled( true )

    val chatInfo = when ( message[ 0 ] ) {
      '.' -> ChatInfo( '.', ChatColor.WHITE, ChatColor.DARK_AQUA, ChatColor.DARK_BLUE, message.substring( 1 ) )
      '!' -> ChatInfo( '!', ChatColor.WHITE, ChatColor.GRAY, ChatColor.DARK_GRAY, message.substring( 1 ) )
      '@' -> ChatInfo( '@', ChatColor.WHITE, ChatColor.GOLD, ChatColor.YELLOW, message.substring( 1 ) )

      else -> ChatInfo( '!', ChatColor.WHITE, ChatColor.GRAY, ChatColor.DARK_GRAY, message )
    }

    e.setFormat( ""
      + "${chatInfo.signsColor}[${chatInfo.prefix}]"
      + "${chatInfo.nickColor} %s"
      + "${chatInfo.signsColor} >>"
      + "${chatInfo.messageColor} ${chatInfo.message}"
    )
  }
}