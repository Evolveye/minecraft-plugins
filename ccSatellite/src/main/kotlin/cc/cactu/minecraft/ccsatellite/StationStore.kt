package cc.cactu.minecraft.ccsatellite

import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.math.hypot

/** Registered satellite stations, kept in a YAML file so they survive restarts. Main thread only. */
class StationStore( private val file:File ) {
    private val stations = linkedMapOf<String, SatelliteStation>()

    val all get() = stations.values.toList()

    fun contains( station:SatelliteStation ) = station.key in stations

    /** Another station in the same world within `distance` blocks horizontally, or `null` when there's none. */
    fun findNearby( station:SatelliteStation, distance:Double ) = stations.values.firstOrNull { other ->
        other.key != station.key && other.worldName == station.worldName && measureDistance( station, other ) <= distance
    }

    fun load() {
        val yaml = YamlConfiguration.loadConfiguration( file )

        for (entry in yaml.getMapList( "stations" )) {
            val station = parseStation( entry ) ?: continue

            stations[ station.key ] = station
        }
    }

    /** Adds a station unless its place already holds one; returns whether it was added. */
    fun add( station:SatelliteStation ): Boolean {
        if (station.key in stations) return false

        stations[ station.key ] = station
        save()

        return true
    }

    fun removeAll( removed:Collection<SatelliteStation> ) {
        if (removed.isEmpty()) return

        removed.forEach { stations.remove( it.key ) }
        save()
    }

    private fun save() {
        val yaml = YamlConfiguration()

        yaml.set( "stations", stations.values.map { serializeStation( it ) } )
        yaml.save( file )
    }
}

/** Horizontal distance between two stations, in blocks. */
fun measureDistance( station:SatelliteStation, other:SatelliteStation ) = hypot( (station.x - other.x).toDouble(), (station.z - other.z).toDouble() )

private fun serializeStation( station:SatelliteStation ) = mapOf(
    "world" to station.worldName,
    "x" to station.x,
    "y" to station.y,
    "z" to station.z,
    "owner" to station.ownerId.toString(),
    "registeredAt" to station.registeredAt.toString(),
)

/** Station read back from its YAML entry, or `null` when the entry is malformed. */
private fun parseStation( entry:Map<*, *> ): SatelliteStation? {
    val worldName = entry[ "world" ] as? String ?: return null

    val x = (entry[ "x" ] as? Number)?.toInt() ?: return null
    val y = (entry[ "y" ] as? Number)?.toInt() ?: return null
    val z = (entry[ "z" ] as? Number)?.toInt() ?: return null

    val ownerId = runCatching { UUID.fromString( entry[ "owner" ] as String ) }.getOrNull() ?: return null

    val registeredAt = runCatching { Instant.parse( entry[ "registeredAt" ] as String ) }.getOrDefault( Instant.now() )

    return SatelliteStation( worldName, x, y, z, ownerId, registeredAt )
}
