package cc.cactu.minecraft.ccapilink

import com.google.gson.GsonBuilder
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Transport between the Minecraft server and the external backend API. Holds no game logic. */
class CcApiLink : JavaPlugin() {
    private companion object {
        const val SECRET_ENV = "CC_API_LINK__EXTERNAL_API_SECRET"
        const val API_PATH = "/api/minecraft"
        const val MAX_ATTEMPTS = 3
        const val RETRY_DELAY_SECONDS = 2L

        val GSON = GsonBuilder().serializeNulls().create()
    }

    private val httpClient = HttpClient.newHttpClient()

    private lateinit var apiSecret:String
    private lateinit var apiUrl:String
    private lateinit var requestTimeout:Duration

    override fun onEnable() {
        saveDefaultConfig()

        val secret = System.getenv( SECRET_ENV )
        if (secret.isNullOrBlank()) return disableWithError( "Environment variable $SECRET_ENV is not set, disabling ccApiLink" )

        val origin = config.getString( "backend-origin" )
        if (origin.isNullOrBlank()) return disableWithError( "Option backend-origin is not set in config.yml, disabling ccApiLink" )

        apiSecret = secret.trim()
        apiUrl = origin.trimEnd( '/' ) + API_PATH
        requestTimeout = Duration.ofSeconds( config.getLong( "request-timeout-seconds", 5 ) )

        logger.info( "Hello ccApiLink" )
    }

    fun postEvent( type:String, player:Player?, data:Map<String, Any?>, occurredAt:Instant = Instant.now() ) {
        val envelope = mapOf(
            "id" to UUID.randomUUID().toString(),
            "type" to type,
            "occurredAt" to occurredAt.toString(),
            "player" to player?.let { serializePlayer( it ) },
            "data" to data,
        )

        post( "/events", envelope )
    }

    fun post( path:String, body:Any ) {
        val request = HttpRequest.newBuilder( URI.create( "$apiUrl$path" ) )
            .timeout( requestTimeout )
            .header( "Authorization", "Bearer $apiSecret" )
            .header( "Content-Type", "application/json" )
            .POST( HttpRequest.BodyPublishers.ofString( GSON.toJson( body ) ) )
            .build()

        send( request, 1 )
    }

    private fun send( request:HttpRequest, attempt:Int ) {
        httpClient.sendAsync( request, HttpResponse.BodyHandlers.discarding() )
            .whenComplete { response, error -> handleResponse( request, attempt, response?.statusCode(), error ) }
    }

    private fun handleResponse( request:HttpRequest, attempt:Int, status:Int?, error:Throwable? ) {
        if (status in 200..299) return

        val reason = error?.message ?: "status $status"

        if (status in 400..499) return logger.warning( "Backend rejected ${request.uri()}: $reason" )
        if (attempt >= MAX_ATTEMPTS || !isEnabled) return logger.warning( "Dropping request to ${request.uri()} after $attempt attempts: $reason" )

        val retryExecutor = CompletableFuture.delayedExecutor( RETRY_DELAY_SECONDS * attempt, TimeUnit.SECONDS )
        CompletableFuture.runAsync( { send( request, attempt + 1 ) }, retryExecutor )
    }

    private fun disableWithError( message:String ) {
        logger.severe( message )
        server.pluginManager.disablePlugin( this )
    }
}

private fun serializePlayer( player:Player ) = mapOf(
    "uuid" to player.uniqueId.toString(),
    "name" to player.name,
    "skinUrl" to player.playerProfile.textures.skin?.toString(),
)
