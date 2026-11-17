package cc.cactu.minecraft.ccreporter

import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import java.time.Instant
import java.util.UUID

/** A join or quit of a player, ready to be reported. */
data class PresenceChange( val type:String, val player:Player, val occurredAt:Instant, val data:Map<String, Any?> )

/**
 * Limits join/quit reports of players who rapidly reconnect. The first `maxEvents` changes within `windowSeconds`
 * are reported right away. Later ones are held, and once the player stays calm for `quietSeconds`,
 * only the final state is reported, and only if it differs from the last reported one.
 */
class PresenceThrottle(
    private val plugin:JavaPlugin,
    private val maxEvents:Int,
    private val windowSeconds:Long,
    private val quietSeconds:Long,
    private val send:(PresenceChange) -> Unit,
) {
    private val states = mutableMapOf<UUID, PresenceState>()

    /** Reports `change` right away, or holds it until the player stops reconnecting. */
    fun submit( change:PresenceChange ) {
        val state = states.getOrPut( change.player.uniqueId ) { PresenceState() }
        val windowStart = change.occurredAt.minusSeconds( windowSeconds )

        state.sentAt.removeAll { it.isBefore( windowStart ) }

        if (state.pending == null && state.sentAt.size < maxEvents) return sendNow( state, change )

        // Every further change restarts the quiet period.
        state.pending = change
        state.pendingTask?.cancel()
        state.pendingTask = plugin.server.scheduler.runTaskLater( plugin, Runnable { flush( state ) }, quietSeconds * 20 )
    }

    /** Reports every held change immediately, e.g. when the plugin is being disabled. */
    fun flushAll() = states.values.forEach { flush( it ) }

    private fun flush( state:PresenceState ) {
        val change = state.pending ?: return

        state.pendingTask?.cancel()
        state.pending = null
        state.pendingTask = null

        // The outside world already knows this state, e.g. quit + join of a player reported as online.
        if (change.type == state.lastSentType) return

        sendNow( state, change )
    }

    private fun sendNow( state:PresenceState, change:PresenceChange ) {
        state.sentAt.addLast( change.occurredAt )
        state.lastSentType = change.type

        send( change )
    }
}

/** Reporting history and the held change of a single player. */
private class PresenceState {
    val sentAt = ArrayDeque<Instant>()
    var lastSentType:String? = null
    var pending:PresenceChange? = null
    var pendingTask:BukkitTask? = null
}
