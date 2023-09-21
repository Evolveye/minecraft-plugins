package cc.cactu.minecraft.ccchat

import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import org.bukkit.command.CommandSender
import org.bukkit.event.EventHandler
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.event.Listener
import org.bukkit.event.server.ServerCommandEvent

class CcChat : JavaPlugin(), Listener {
    override fun onEnable() {
        server.pluginManager.registerEvents(this, this)

        logger.info("Hello ccChat")

        createChatMode( '!', NamedTextColor.WHITE,
            permissionsChecker = fun( _: CommandSender) = true,
            receiversGetter = fun( _:CommandSender ) = server.onlinePlayers,
        )

        defaultSign = '!'
    }

//    override fun onTabComplete( sender:CommandSender, command:Command, label:String, args:Array<String> ):List<String>? {
//        if (label == "m" && args.size > 1) return listOf()
//        return null
//    }

//    override fun onCommand( sender:CommandSender, command:Command, label:String, args:Array<String> ):Boolean {
//        if (label == "m") {
//            if (args.size > 0) {
//                val receiver = server.getPlayer( args[ 0 ] )
//
//                if (args[ 0 ] == "CONSOLE" || receiver != null) {
//                    if (args.size > 1) {
//                        val senderName = if (sender is Player) sender.displayName() else Component.text( "Serwer" )
//                        val reveiverName = if (receiver is Player) receiver.displayName() else Component.text( "Serwer" )
//
//                        val senderNicknameSlot = Component.text( "Ty → ").append( senderName )
//                        val receiverNicknameSlot = reveiverName.append( Component.text( "→ Ty" ) )
//
//                        val senderMsg = getChatPlayerOpening( senderNicknameSlot, '»', NamedTextColor.WHITE )
//                            .append( Component.text( args.slice( 1 until args.size ).joinToString( " " ), NamedTextColor.WHITE ) )
//
//                        val receiverMsg = getChatPlayerOpening( receiverNicknameSlot, '»', NamedTextColor.WHITE )
//                            .append( Component.text( args.slice( 1 until args.size ).joinToString( " " ), NamedTextColor.WHITE ) )
//
//                        sender.sendMessage( senderMsg )
//
//                        if ( receiver is Player ) receiver.sendMessage( receiverMsg )
//                        else logger.info( receiverMsg.toString() )
//
//                        return true
//                    } // else createChatError( "Złe użycie polecenia /m: &1Nie podałeś wiadomości", sender )
//                } // else createChatError( "Złe użycie polecenia /m: &1Podany odbiorca nie istnieje", sender )
//            } // else createChatError( "Złe użycie polecenia /m: &1Nie określiłeś gracza", sender )
//
//            // createChatError( "Składnia: &1/m <gracz> <...treść>", sender )
//        } else if ( label == "?" || label == "help" || label == "pomoc" ) {
//                val msg = getChatIndicator( 'i', NamedTextColor.LIGHT_PURPLE )
//                    .append( Component.text( "Oficjalnie dostępne na serwerze polecenia:", NamedTextColor.WHITE ) )
//                    .appendNewline()
//                    .append( Component.text( " /m", NamedTextColor.GREEN ).append( Component.text(": Prywatna wiadomość", NamedTextColor.WHITE ) ) )
//                    .appendNewline()
//                    .append( Component.text( " /m", NamedTextColor.GREEN ).append( Component.text(": Prywatna wiadomość", NamedTextColor.WHITE ) ) )
//
//                sender.sendMessage( msg )
//
//                return true
//            }
//
//        return false
//    }

    @EventHandler
    fun onJoin( e:PlayerJoinEvent ) {
        val player = e.player

        if (player.hasPlayedBefore()) {
            val txt = getChatIndicator( '+', NamedTextColor.GREEN )
                .append( Component.text( "Gracz ", secondaryColor ) )
                .append( player.displayName().color( NamedTextColor.GREEN ) )
                .append( Component.text( " dołączył do gry", secondaryColor ) )

            e.joinMessage( txt );
        } else {
            val txt = getChatIndicator( '+', NamedTextColor.GREEN )
                .append( Component.text( "Gracz ", NamedTextColor.YELLOW ) )
                .append( player.displayName().color( NamedTextColor.GREEN ) )
                .append( Component.text( " wszedł po raz pierwszy na serwer! Życzymy miłej gry", NamedTextColor.YELLOW ) )

            e.joinMessage( txt );
        }
    }

    @EventHandler
    fun onQuit( e:PlayerQuitEvent ) {
        val player = e.player

        val txt = getChatIndicator( '-', NamedTextColor.RED )
            .append( Component.text( "Gracz ", secondaryColor ) )
            .append( player.displayName().color( NamedTextColor.RED ) )
            .append( Component.text( " wyszedł z gry", secondaryColor ) )

        e.quitMessage( txt )
    }

    @EventHandler
    fun onChat( e:AsyncChatEvent ) {
        e.isCancelled = true

        val player = e.player
        val message = e.message()

        if (message !is TextComponent) return

        val playerName = player.displayName()

        if (playerName !is TextComponent) return

        createChatMessage( playerName, message.content(), player )
        logger.info( playerName.content()+ ": " + message.content() )
    }

    @EventHandler
    fun onConsoleSay( e:ServerCommandEvent ) {
        val command = e.command

        if ( command.length >= 3 && command.substring( 0, 3 ) != "say" ) return

        e.isCancelled = true

        val serverMsg = getChatPlayerOpening( "Serwer", 'i', NamedTextColor.LIGHT_PURPLE )
            .append( Component.text( command.substring( 4 ), NamedTextColor.LIGHT_PURPLE ) )

        Bukkit.getOnlinePlayers().forEach{ it.sendMessage( serverMsg ) }
    }
}
