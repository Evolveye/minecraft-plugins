package cc.cactu.minecraft.ccchat

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

typealias ChatModeTester = (CommandSender) -> Boolean
typealias ChatModeReceivers = (CommandSender) -> Collection<Player>
typealias MessageConstructor = (CommandSender) -> Component

val chatModes = mutableMapOf<Char, ChatMode>()
var defaultSign:Char? = null;

data class ChatMode(
    val prefix: Char,
    val messageColor: NamedTextColor,
    val permissionsChecker: ChatModeTester,
    val receiversGetter: ChatModeReceivers,
)

fun createChatMode(
    sign:Char,
    messageColor: NamedTextColor,
    permissionsChecker:ChatModeTester,
    receiversGetter:ChatModeReceivers,
):Boolean {
    if (chatModes.containsKey( sign )) return false
    chatModes[ sign ] = ChatMode( sign, messageColor, permissionsChecker, receiversGetter )
    return true
}
