package io.cactu.mc.chat

import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.entity.Player
import org.bukkit.event.Listener
import org.bukkit.event.EventHandler
import org.bukkit.event.player.*
import org.bukkit.event.server.ServerCommandEvent
import org.bukkit.ChatColor
import org.bukkit.command.CommandSender
import org.bukkit.command.Command

data class ChatInfo(
  val prefix:Char,
  val nickColor:ChatColor,
  val messageColor:ChatColor,
  val message:String
)
fun getChatFormat( nickname:String, message:String ):String {
  val chatInfo = when ( message[ 0 ] ) {
    '.' -> ChatInfo( '.', ChatColor.WHITE, ChatColor.DARK_AQUA, message.substring( 1 ) )
    '!' -> ChatInfo( '!', ChatColor.WHITE, ChatColor.GRAY, message.substring( 1 ) )
    '@' -> ChatInfo( '@', ChatColor.WHITE, ChatColor.GOLD, message.substring( 1 ) )
    '$' -> ChatInfo( '$', ChatColor.GREEN, ChatColor.WHITE, message.substring( 1 ) )

    else -> ChatInfo( '!', ChatColor.WHITE, ChatColor.GRAY, message )
  }

  return (""
    + "${chatInfo.messageColor}[${chatInfo.prefix}]"
    + "${chatInfo.nickColor} ${nickname}"
    + "${ChatColor.DARK_GRAY} >>"
    + "${chatInfo.messageColor} ${chatInfo.message}"
  )
}

public class App: JavaPlugin(), Listener {
  private val chatModes = object {
    var player = ".!@"
  }

  override fun onEnable() {
    logger.info( "Plugin enabled" )
    server.pluginManager.registerEvents( this, this )
  }

  @EventHandler
  public fun onJoin( e:PlayerJoinEvent ) {
    val player = e.player

    if ( player.hasPlayedBefore() ) {
      e.joinMessage = (""
        + "${ChatColor.DARK_GRAY}[${ChatColor.WHITE}+${ChatColor.DARK_GRAY}] Gracz"
        + "${ChatColor.WHITE} ${player.displayName}"
        + "${ChatColor.DARK_GRAY} dołączył do gry"
      )
    }
    else {
      e.joinMessage = (""
        + "${ChatColor.DARK_GRAY}[${ChatColor.WHITE}+${ChatColor.DARK_GRAY}]"
        + "${ChatColor.GREEN} Gracz"
        + "${ChatColor.WHITE} ${player.displayName}"
        + "${ChatColor.GREEN} wszedł po raz pierwszy na serwer! Życzymy miłej gry"
      )
    }
  }

  @EventHandler
  public fun onJoin( e:PlayerQuitEvent ) {
    e.quitMessage = (""
      + "${ChatColor.DARK_GRAY}[${ChatColor.WHITE}-${ChatColor.DARK_GRAY}] Gracz"
      + "${ChatColor.WHITE} ${e.player.displayName}"
      + "${ChatColor.DARK_GRAY} wyszedł z gry"
    )
  }

  @EventHandler
  public fun onChat( e:AsyncPlayerChatEvent ) {
    val message = e.message

    if ( chatModes.player.contains( message[ 0 ] ) && message.length == 1 )
      return e.setCancelled( true )

    e.format = ( getChatFormat( e.player.displayName, message ) )
  }

  @EventHandler
  public fun onConsoleSay( e:ServerCommandEvent ) {
    val command = e.command

    if ( command.substring( 0, 3 ) != "say" ) return

    e.setCancelled( true )

    Bukkit.broadcastMessage( getChatFormat( e.sender.name, "$${command.substring( 4 )}" ) )
  }
}