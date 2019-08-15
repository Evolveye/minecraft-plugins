package io.cactu.mc.chat

import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.event.Listener
import org.bukkit.event.EventHandler
import org.bukkit.event.player.*
import org.bukkit.event.server.ServerCommandEvent
import org.bukkit.ChatColor

data class ChatInfo(
  val prefix:Char,
  val nickColor:ChatColor,
  val messageColor:ChatColor,
  val signsColor:ChatColor,
  val message:String
)

public class App: JavaPlugin(), Listener {
  private val chatModes = object {
    var player = ".!@"
  }

  override fun onEnable() {
    logger.info( "Plugin enabled" )

    getServer().getPluginManager().registerEvents( this, this )
  }

  fun getChatFormat( nickname:String, message:String ):String {
    val chatInfo = when ( message[ 0 ] ) {
      '.' -> ChatInfo( '.', ChatColor.WHITE, ChatColor.DARK_AQUA, ChatColor.DARK_BLUE, message.substring( 1 ) )
      '!' -> ChatInfo( '!', ChatColor.WHITE, ChatColor.GRAY, ChatColor.DARK_GRAY, message.substring( 1 ) )
      '@' -> ChatInfo( '@', ChatColor.WHITE, ChatColor.GOLD, ChatColor.YELLOW, message.substring( 1 ) )
      '$' -> ChatInfo( '$', ChatColor.GREEN, ChatColor.WHITE, ChatColor.DARK_GRAY, message.substring( 1 ) )

      else -> ChatInfo( '!', ChatColor.WHITE, ChatColor.GRAY, ChatColor.DARK_GRAY, message )
    }

    return (""
      + "${chatInfo.messageColor}[${chatInfo.prefix}]"
      + "${chatInfo.nickColor} ${nickname}"
      + "${chatInfo.signsColor} >>"
      + "${chatInfo.messageColor} ${chatInfo.message}"
    )
  }

  @EventHandler
  public fun onJoin( e:PlayerJoinEvent ) {
    val player = e.getPlayer()

    player.sendMessage( "Dzień dobry ${e.getPlayer().getName()}" )
  }

  @EventHandler
  public fun onChat( e:AsyncPlayerChatEvent ) {
    val message = e.getMessage()

    if ( chatModes.player.contains( message[ 0 ] ) && message.length == 1 )
      return e.setCancelled( true )

    e.setFormat( getChatFormat( e.getPlayer().getDisplayName(), message ) )
  }

  @EventHandler
  public fun onConsoleSay( e:ServerCommandEvent ) {
    if ( e.getCommand() != "say" ) return

    e.setCancelled( true )

    Bukkit.broadcastMessage( getChatFormat( e.getSender().getName(), "$${e.getCommand().substring( 4 )}" ) )
  }
}