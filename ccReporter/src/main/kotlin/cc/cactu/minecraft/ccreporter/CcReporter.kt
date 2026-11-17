package cc.cactu.minecraft.ccreporter

import cc.cactu.minecraft.ccapilink.CcApiLink
import com.google.gson.JsonElement
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerAdvancementDoneEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import java.time.Instant

/** Listens to server events and reports them to the backend through ccApiLink. */
class CcReporter : JavaPlugin(), Listener {
    private lateinit var apiLink:CcApiLink
    private lateinit var presenceThrottle:PresenceThrottle

    override fun onEnable() {
        val link = server.pluginManager.getPlugin( "ccApiLink" ) as? CcApiLink
        if (link == null || !link.isEnabled) return disableWithError( "ccApiLink is not enabled, disabling ccReporter" )

        apiLink = link

        saveDefaultConfig()

        presenceThrottle = PresenceThrottle( this,
            maxEvents = config.getInt( "join-quit-throttle.max-events", 2 ),
            windowSeconds = config.getLong( "join-quit-throttle.window-seconds", 60 ),
            quietSeconds = config.getLong( "join-quit-throttle.quiet-seconds", 30 ),
        ) { apiLink.postEvent( it.type, it.player, it.data, it.occurredAt ) }

        server.pluginManager.registerEvents( this, this )

        logger.info( "Hello ccReporter" )
    }

    override fun onDisable() {
        if (::presenceThrottle.isInitialized) presenceThrottle.flushAll()
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onAdvancement( event:PlayerAdvancementDoneEvent ) {
        // Recipe unlocks, roots and hidden technical advancements are not announced in game either.
        val display = event.advancement.display ?: return
        if (!display.doesAnnounceToChat()) return

        apiLink.postEvent( "advancement", event.player, mapOf(
            "key" to event.advancement.key.asString(),
            "title" to serializeText( display.title() ),
            "description" to serializeText( display.description() ),
            "frame" to display.frame().name.lowercase(),
        ) )
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDeath( event:PlayerDeathEvent ) {
        val player = event.player
        val location = player.location
        val damageSource = event.damageSource

        apiLink.postEvent( "death", player, mapOf(
            "message" to event.deathMessage()?.let { serializeText( it ) },
            "cause" to damageSource.damageType.key.asString(),
            "killer" to damageSource.causingEntity?.let { serializeKiller( it ) },
            "world" to location.world.name,
            "location" to mapOf( "x" to location.blockX, "y" to location.blockY, "z" to location.blockZ ),
        ) )
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin( event:PlayerJoinEvent ) {
        presenceThrottle.submit( PresenceChange( "join", event.player, Instant.now(), mapOf( "firstJoin" to !event.player.hasPlayedBefore() ) ) )
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit( event:PlayerQuitEvent ) {
        presenceThrottle.submit( PresenceChange( "quit", event.player, Instant.now(), emptyMap() ) )
    }

    private fun disableWithError( message:String ) {
        logger.severe( message )
        server.pluginManager.disablePlugin( this )
    }
}

/** Untranslated text in Minecraft's JSON text format, for translating on the backend side. */
private fun serializeText( text:Component ): JsonElement = GsonComponentSerializer.gson().serializeToTree( text )

/** Entity that caused a death; `player` is filled only when the killer is a player. */
private fun serializeKiller( killer:Entity ) = mapOf(
    "type" to killer.type.key.asString(),
    "player" to (killer as? Player)?.let { mapOf( "uuid" to it.uniqueId.toString(), "name" to it.name ) },
)
