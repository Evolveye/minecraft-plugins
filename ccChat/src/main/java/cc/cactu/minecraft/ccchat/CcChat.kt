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
import org.bukkit.command.Command
import org.bukkit.entity.Player
import org.bukkit.event.Listener
import org.bukkit.event.server.ServerCommandEvent
import java.util.UUID

class CcChat : JavaPlugin(), Listener {
    private val consoleLabel = "CONSOLE"
    private val lastMessages = mutableMapOf<String, String>()

    override fun onEnable() {
        server.pluginManager.registerEvents(this, this)

        logger.info("Hello ccChat")

        createChatMode( '!', NamedTextColor.WHITE,
            permissionsChecker = fun( _: CommandSender) = true,
            receiversGetter = fun( _:CommandSender ) = server.onlinePlayers,
        )

        defaultSign = '!'
    }

    override fun onTabComplete( sender:CommandSender, command:Command, label:String, args:Array<String> ):List<String>? {
        if (command.name.equals( "m", ignoreCase = true )) {

            return when (args.size) {
                1 -> {
                    val players = Bukkit.getOnlinePlayers()
                        .map { it.name }
                        .toMutableList()

                    players.add( consoleLabel )

                    players.filter {
                        it.startsWith( args[0], ignoreCase = true )
                    }
                }

                else -> emptyList()
            }
        }

        if (command.name.equals( "r", ignoreCase = true )) {
            return emptyList()
        }

        return emptyList()
    }

    override fun onCommand( sender:CommandSender, command:Command, label:String, args:Array<String> ):Boolean {
        if (label == "m") return handleCommandM( sender, command, label, args )
        if (label == "r") return handleCommandR( sender, command, label, args )
        return true
    }

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

        val (msgInit) = getChatMessageInitiation( playerName, message.content(), player ) ?: return

        Bukkit.getConsoleSender().sendMessage(
            msgInit.append( Component.text( message.content() ) )
        )
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

    private fun handleCommandM( sender:CommandSender, command:Command, label:String, args:Array<String> ):Boolean {
        if (args.size <= 0) return false  // else createChatError( "Złe użycie polecenia /m: &1Nie określiłeś gracza", sender )

        val receiver = server.getPlayer( args[ 0 ] )
        if (args[ 0 ] != consoleLabel && receiver == null) return false // createChatError( "Złe użycie polecenia /m: &1Podany odbiorca nie istnieje", sender )

        if (args.size <= 1) return false // createChatError( "Złe użycie polecenia /m: &1Nie podałeś wiadomości", sender )

        return handleCommandResponse( sender, receiver, args.slice( 1 until args.size ).joinToString( " " ) )
    }

    private fun handleCommandR( sender:CommandSender, command:Command, label:String, args:Array<String> ):Boolean {
        if (args.size < 1) return false

        val senderUuid = if (sender is Player) sender.uniqueId.toString() else consoleLabel
        val receiverUuid = lastMessages[ senderUuid ]

        if (receiverUuid == null) {
            sender.sendMessage( "Nie prowadzisz aktywnej rozmowy prywatnej. Użyj \"/m\"" )
            return true
        }

        val receiver = server.getPlayer( UUID.fromString( receiverUuid ) )

        logger.info( lastMessages.toString() )
        logger.info( "${receiverUuid} ${receiver == null}" )

        return handleCommandResponse( sender, receiver, args.joinToString( " " ) )
    }

    private fun handleCommandResponse( sender:CommandSender, receiver:Player?, message:String ):Boolean {
        val senderName = if (sender is Player) sender.displayName() else Component.text( "Serwer" )
        val reveiverName = if (receiver is Player) receiver.displayName() else Component.text( "Serwer" )

        val prefixColor = NamedTextColor.BLUE
        val messageColor = NamedTextColor.GRAY
        val senderNicknameSlot = Component.text( "Ty")
            .append( Component.text( " → ", prefixColor ) )
            .append( reveiverName )

        val receiverNicknameSlot = senderName
            .append( Component.text( " → ", prefixColor ) )
            .append( Component.text( "Ty" ) )

        val senderMsg = getChatPlayerOpening( senderNicknameSlot, '»', prefixColor )
            .append( Component.text( message, messageColor ) )

        val receiverMsg = getChatPlayerOpening( receiverNicknameSlot, '»', prefixColor )
            .append( Component.text( message, messageColor ) )

        sender.sendMessage( senderMsg )

        if (sender is Player) {
            val senderUuid = sender.uniqueId.toString()

            if (receiver is Player) {
                val receiverUuid = receiver.uniqueId.toString()

                lastMessages[ receiverUuid ] = senderUuid
                lastMessages[ senderUuid ] = receiverUuid
            } else {
                lastMessages[ senderUuid ] = consoleLabel
                lastMessages[ consoleLabel ] = senderUuid
            }
        }

        if (receiver is Player) receiver.sendMessage( receiverMsg )

        return true
    }
}
