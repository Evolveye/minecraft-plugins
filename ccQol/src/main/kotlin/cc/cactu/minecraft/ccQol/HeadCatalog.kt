package cc.cactu.minecraft.ccqol

import com.google.gson.stream.JsonReader
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration

/** A decorative head from the catalog, with its texture hash from textures.minecraft.net. */
class CatalogHead( val id:Int, val name:String, val texture:String )

private const val DEFAULT_CATALOG_URL = "https://raw.githubusercontent.com/TheSilentPro/heads/main/heads.json"

/** Texture hashes go into a JSON texture value, so only hexadecimal ones are accepted. */
private val TEXTURE_PATTERN = Regex( "[0-9a-f]{1,128}" )

/**
 * Catalog of decorative heads, downloaded from a public dataset into the plugin's data folder.
 * It loads in the background, so until it is ready, lookups find nothing.
 */
class HeadCatalog( private val plugin:JavaPlugin ) {
    private val catalogUrl = plugin.config.getString( "heads.catalog-url" ) ?: DEFAULT_CATALOG_URL
    private val refreshMillis = Duration.ofDays( plugin.config.getLong( "heads.refresh-days", 7 ).coerceAtLeast( 1 ) ).toMillis()
    private val catalogFile = File( plugin.dataFolder, "heads.json" )

    @Volatile
    private var heads = emptyMap<Int, CatalogHead>()

    /** Starts loading the catalog in the background, downloading it first when it is missing or outdated. */
    fun load() {
        Bukkit.getScheduler().runTaskAsynchronously( plugin, Runnable { loadNow() } )
    }

    fun find( id:Int ) = heads[ id ]

    private fun loadNow() {
        val isOutdated = !catalogFile.exists() || System.currentTimeMillis() - catalogFile.lastModified() > refreshMillis
        if (isOutdated) downloadCatalog()

        if (!catalogFile.exists()) return plugin.logger.warning( "Head catalog is unavailable, decorative heads are disabled" )

        try {
            heads = readHeads( catalogFile )
            plugin.logger.info( "Loaded ${heads.size} decorative heads" )
        } catch (exception:Exception) {
            plugin.logger.warning( "Cannot read the head catalog: ${exception.message}" )
        }
    }

    // The download goes to a temporary file, which replaces the catalog only when it is valid.
    private fun downloadCatalog() {
        val downloadFile = File( plugin.dataFolder, "heads.json.download" )
        val request = HttpRequest.newBuilder( URI.create( catalogUrl ) ).timeout( Duration.ofMinutes( 2 ) ).build()

        try {
            plugin.dataFolder.mkdirs()

            val response = HttpClient.newBuilder().followRedirects( HttpClient.Redirect.NORMAL ).build().use { client ->
                client.send( request, HttpResponse.BodyHandlers.ofFile( downloadFile.toPath() ) )
            }

            if (response.statusCode() != 200) throw IllegalStateException( "HTTP ${response.statusCode()}" )
            if (readHeads( downloadFile ).isEmpty()) throw IllegalStateException( "the catalog is empty" )

            Files.move( downloadFile.toPath(), catalogFile.toPath(), StandardCopyOption.REPLACE_EXISTING )
            plugin.logger.info( "Downloaded the head catalog" )
        } catch (exception:Exception) {
            plugin.logger.warning( "Cannot download the head catalog: ${exception.message}" )
        } finally {
            downloadFile.delete()
        }
    }
}

/** Reads the catalog JSON array, skipping entries without an ID, a name or a valid texture hash. */
private fun readHeads( source:File ): Map<Int, CatalogHead> {
    val heads = HashMap<Int, CatalogHead>()

    JsonReader( source.bufferedReader() ).use { reader ->
        reader.beginArray()

        while (reader.hasNext()) {
            val head = readHead( reader ) ?: continue
            heads[ head.id ] = head
        }

        reader.endArray()
    }

    return heads
}

/** Reads one catalog entry, returning null when it is incomplete. */
private fun readHead( reader:JsonReader ): CatalogHead? {
    var id: Int? = null
    var name: String? = null
    var texture: String? = null

    reader.beginObject()

    while (reader.hasNext()) {
        when (reader.nextName()) {
            "id" -> id = reader.nextInt()
            "name" -> name = reader.nextString()
            "texture" -> texture = reader.nextString()
            else -> reader.skipValue()
        }
    }

    reader.endObject()

    if (id == null || name.isNullOrBlank() || texture == null) return null
    if (!TEXTURE_PATTERN.matches( texture )) return null

    return CatalogHead( id, name, texture )
}
