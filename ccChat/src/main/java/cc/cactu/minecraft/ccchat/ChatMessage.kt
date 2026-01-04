package cc.cactu.minecraft.ccchat

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextReplacementConfig
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.command.CommandSender

val secondaryColor = NamedTextColor.DARK_GRAY
private val urlRegex = Regex("(https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+)")

data class MessageData(
    val chatMode: ChatMode,
    val message: String,
)

fun getChatIndicator( prefix:Char, color:NamedTextColor ):Component {
    return Component.text( "" )
        .append( Component.text( "[", secondaryColor ) )
        .append( Component.text( prefix ).color( color ) )
        .append( Component.text( "] ", secondaryColor ) )
}

fun getChatPlayerOpening( nickname:String, prefix:Char, messageColor:NamedTextColor ):Component {
    return getChatPlayerOpening( Component.text( nickname ).color( NamedTextColor.WHITE ), prefix, messageColor )
}

fun getChatPlayerOpening( nickname:Component, prefix:Char, color:NamedTextColor ):Component {
    return Component.text( "" )
        .append( getChatIndicator( prefix, color ) )
        .append( nickname )
        .append( Component.text( " » ", secondaryColor ) )
}

fun getChatMessageInitiation( nickname:Component, message:String, sender:CommandSender?=null ):Pair<Component, ChatMode>? {
    val deducedChatMode = chatModes[ message[ 0 ] ]
    val messageData = if (deducedChatMode != null && message.length > 1 && (sender == null || deducedChatMode.permissionsChecker( sender )) ) {
        MessageData( deducedChatMode, message.substring(1 ) )
    } else {
        val defaultChatMode = chatModes[ defaultSign ] ?: return null
        MessageData( defaultChatMode, message )
    }

    return Pair( getChatPlayerOpening( nickname, messageData.chatMode.prefix, messageData.chatMode.messageColor ), messageData.chatMode )
}

fun applyLinkComponentsToString( message:String ):Component {
    var component = Component.empty()

    var lastIndex = 0
    for (match in urlRegex.findAll( message )) {
        val start = match.range.first
        val end = match.range.last + 1

        if (start > lastIndex) {
            component = component.append( Component.text( message.substring(lastIndex, start) ) )
        }

        val url = match.value

        component = component.append(
            Component.text( url )
                .decorate( TextDecoration.UNDERLINED )
                .clickEvent( ClickEvent.openUrl( url ) )
                .hoverEvent( HoverEvent.showText( Component.text( "Kliknij, aby otworzyć" ) ) )
        )

        lastIndex = end
    }

    if (lastIndex < message.length) {
        component = component.append( Component.text( message.substring(lastIndex) ) )
    }

    return component
}

fun createChatMessage( nickname:Component, message:String, sender:CommandSender?=null ):Component? {
    val (messageInitiation, chatMode) = getChatMessageInitiation( nickname, message, sender ) ?: return null
    var transformedMessage:Component = Component.text( "", chatMode.messageColor )
        .append( applyLinkComponentsToString( message ) )

    if (sender != null) {
        val nicknames = sender.server.onlinePlayers.map { PlainTextComponentSerializer.plainText().serialize( it.displayName() ) }

        if (nicknames.size > 0) {
            val replaceConfig = TextReplacementConfig.builder()
                .match( "\\b(${nicknames.joinToString("|")})\\b".toRegex().pattern )
                .replacement { matchResult -> Component.text( "${matchResult.content()}", NamedTextColor.YELLOW ) }
                .build()

            transformedMessage = transformedMessage.replaceText( replaceConfig )
        }
    }

    val fullMessage = messageInitiation.append( transformedMessage )
    if (sender != null) chatMode.receiversGetter( sender ).forEach { it.sendMessage( fullMessage ) }

    return fullMessage
}