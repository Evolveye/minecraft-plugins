package cc.cactu.minecraft.ccchat

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.CommandSender

var secondaryColor = NamedTextColor.DARK_GRAY

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

fun createChatMessage( nickname:Component, message:String, sender:CommandSender?=null ):Component? {
    val deducedChatMode = chatModes[ message[ 0 ] ]
    val messageData = if (deducedChatMode != null && message.length > 1 && (sender == null || deducedChatMode.permissionsChecker( sender )) ) {
            MessageData( deducedChatMode, message.substring(1 ) )
        } else {
            val defaultChatMode = chatModes[ defaultSign ] ?: return null
            MessageData( defaultChatMode, message )
        }

    val messageInitiation = getChatPlayerOpening( nickname, messageData.chatMode.prefix, messageData.chatMode.messageColor )
    val convertedMessage = messageInitiation.append( Component.text( message, messageData.chatMode.messageColor )  )

    if (sender != null) messageData.chatMode.receiversGetter( sender ).forEach { it.sendMessage( convertedMessage ) }

    return convertedMessage
}