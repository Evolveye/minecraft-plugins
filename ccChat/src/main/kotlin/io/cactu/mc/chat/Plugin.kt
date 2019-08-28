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

fun replaceVarsToColor( message:String ):String = message
  .replace( "&0", "${ChatColor.BLACK}" )
  .replace( "&1", "${ChatColor.WHITE}" )
  .replace( "&2", "${ChatColor.BLUE}" )
  .replace( "&D2", "${ChatColor.DARK_BLUE}" )
  .replace( "&3", "${ChatColor.GREEN}" )
  .replace( "&D3", "${ChatColor.DARK_GREEN}" )
  .replace( "&4", "${ChatColor.AQUA}" )
  .replace( "&D4", "${ChatColor.DARK_AQUA}" )
  .replace( "&5", "${ChatColor.RED}" )
  .replace( "&D5", "${ChatColor.DARK_RED}" )
  .replace( "&6", "${ChatColor.LIGHT_PURPLE}" )
  .replace( "&D6", "${ChatColor.DARK_PURPLE}" )
  .replace( "&7", "${ChatColor.GRAY}" )
  .replace( "&D7", "${ChatColor.DARK_GRAY}" )
  .replace( "&8", "${ChatColor.YELLOW}" )
  .replace( "&9", "${ChatColor.GOLD}" )
  .replace( "&b", "${ChatColor.BOLD}" )
  .replace( "&s", "${ChatColor.STRIKETHROUGH}" )
  .replace( "&u", "${ChatColor.UNDERLINE}" )
  .replace( "&i", "${ChatColor.ITALIC}" )
  .replace( "&r", "${ChatColor.RESET}" )
fun createChatInfo( message:String, sender:CommandSender?=null ):String = createChatInfo( 'i', message, sender )
fun createChatInfo( sign:Char, message:String, sender:CommandSender?=null ):String {
  val convertedMessage = replaceVarsToColor( "&D7[&1&b$sign&D7] $message" )

  if ( sender != null ) sender.sendMessage( convertedMessage )

  return convertedMessage
}
fun createChatError( message:String, sender:CommandSender?=null ):String {
  val convertedMessage = replaceVarsToColor( "&D7[&5&bX&D7] &1$message" )

  if ( sender != null ) sender.sendMessage( convertedMessage )

  return convertedMessage
}
fun createChatMessage( nickname:String, message:String, sender:CommandSender?=null ):String {
  val default = ChatInfo( '!', message, ChatColor.GRAY )
  val chatInfo = if ( message.length == 1 ) default else when ( message[ 0 ] ) {
    '.' -> ChatInfo( '.', message.substring( 1 ), ChatColor.DARK_AQUA )
    '!' -> ChatInfo( '!', message.substring( 1 ), ChatColor.GRAY )
    '@' -> ChatInfo( '@', message.substring( 1 ), ChatColor.GOLD )
    '>' -> ChatInfo( '>', message.substring( 1 ), ChatColor.GRAY )

    else -> default
  }

  val convertedMessage = (""
    + "${chatInfo.messageColor}[${chatInfo.prefix}]"
    + "${ChatColor.WHITE} $nickname"
    + "${ChatColor.DARK_GRAY} »"
    + "${chatInfo.messageColor} ${chatInfo.message}"
  )

  if ( sender != null ) sender.sendMessage( convertedMessage )

  return convertedMessage
}

data class ChatInfo( val prefix:Char, val message:String, val messageColor:ChatColor)

public class Plugin: JavaPlugin(), Listener {
  private val chatModes = object {
    var player = ".!@"
  }

  override fun onEnable() {
    logger.info( "Plugin enabled" )
    server.pluginManager.registerEvents( this, this )
  }

  override fun onTabComplete( sender:CommandSender, command:Command, label:String, args:Array<String> ):List<String>? {
    if ( label == "m" && args.size > 1 ) return listOf()

    return null
  }

  override fun onCommand( sender:CommandSender, command:Command, label:String, args:Array<String> ):Boolean {
    if ( label == "m" ) {
      if ( args.size > 0 ) {
        var receiver = server.getPlayer( args[ 0 ] )

        if ( args[ 0 ] == "CONSOLE" || receiver != null ) {
          if ( args.size > 1 ) {
            val senderName = if ( sender is Player ) sender.displayName else sender.name
            val reveiverName = if ( receiver is Player ) receiver.displayName else args[ 0 ]
            val message = ">${ChatColor.GRAY}${args.slice( 1..(args.size - 1) ).joinToString( " " )}"
            val nicknameA = "${ChatColor.GREEN}[Ty > ${ChatColor.WHITE}$reveiverName${ChatColor.GREEN}]"
            val nicknameB = "${ChatColor.GREEN}[${ChatColor.WHITE}$senderName${ChatColor.GREEN} > Ty]"

            createChatMessage( nicknameA, message, sender )

            if ( receiver is Player ) createChatMessage( nicknameB, message, receiver )
            else logger.info( createChatMessage( nicknameB, message ) )

            return true
          }
          else createChatError( "Złe użycie polecenia /m: &1Nie podałeś wiadomości", sender )
        }
        else createChatError( "Złe użycie polecenia /m: &1Podany odbiorca nie istnieje", sender )
      }
      else createChatError( "Złe użycie polecenia /m: &1Nie określiłeś gracza", sender )

      createChatError( "Składnia: &1/m <gracz> <...treść>", sender )
    }
    else if ( label == "?" || label == "help" || label == "pomoc" ) {
      createChatInfo( '?', "&3&bOficjalnie dostępne na serwerze polecenia:"
        + "\n &3&b/m&r&7: Prywatna wiadomość"
        + "\n &3&b/r&r&7: Odpowiedź na prywatną wiadomość"
        + "\n &3&b/a&r&7: Spójrz na mapę osiągnięć"
        + "\n &3&b/mod&r&7: Wyślij zgłoszenie (np. dotyczące błędu) do administracji"
        + "\n  Zgłoszenie będzie zawierać informacje o Tobie, i lokalizacji zgłoszenia"
      , sender )
    }

    return true
  }

  @EventHandler
  public fun onJoin( e:PlayerJoinEvent ) {
    val player = e.player
    val playerName = player.displayName

    if ( player.hasPlayedBefore() ) e.joinMessage = createChatInfo('+', "Gracz &1$playerName&D7 dołączył do gry" )
    else e.joinMessage = createChatInfo('+', "&3Gracz &1$playerName&3 wszedł po raz pierwszy na serwer! Życzymy miłej gry" )
  }

  @EventHandler
  public fun onQuit( e:PlayerQuitEvent ) {
    e.quitMessage = createChatInfo('-', "Gracz &1${e.player.displayName}&D7 wyszedł z gry" )
  }

  @EventHandler
  public fun onChat( e:AsyncPlayerChatEvent ) {
    val player = e.player
    val message = if ( player.isOp ) replaceVarsToColor( e.message ) else e.message

    e.format = createChatMessage( player.displayName, message )
  }

  @EventHandler
  public fun onConsoleSay( e:ServerCommandEvent ) {
    val command = e.command

    if ( command.length >= 3 && command.substring( 0, 3 ) != "say" ) return

    e.setCancelled( true )

    Bukkit.broadcastMessage( createChatMessage(
      replaceVarsToColor( "&3${e.sender.name}" ),
      replaceVarsToColor( "!${command.substring( 4 )}" )
    ) )
  }
}