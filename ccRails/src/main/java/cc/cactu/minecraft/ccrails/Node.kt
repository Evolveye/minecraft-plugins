package cc.cactu.minecraft.ccrails

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.Sign
import org.bukkit.block.data.Rail
import org.bukkit.block.data.type.NoteBlock
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.PriorityQueue
import kotlin.math.abs

typealias Score = Int


sealed class StationResult {
    data class SUCCESS_CREATED( val station:Station ) : StationResult()
    data class SUCCESS_FOUND( val station:Station ) : StationResult()
    object ERROR_WRONG_NAME : StationResult()
    object ERROR_DUPLICATE : StationResult()
}

interface PathFinderElement {
    val id: Int
}

data class Station(
    override val id: Int,
    val name: String,
    val world: String,
    val x: Int,
    val y: Int,
    val z: Int,
) : PathFinderElement

data class Crossroad(
    override val id: Int,
    val direction: Direction,
    val world: String,
    val x: Int,
    val y: Int,
    val z: Int,
    var edges:MutableMap<Direction, Int> = mutableMapOf(),
) : PathFinderElement

data class Edge(
    override val id: Int,
    val layout: Layout,
    val nodes: MutableList<Pair<Int, Score>>,
) : PathFinderElement

data class ChoosedCrossroadData(
    val crossroad: Crossroad,
    val railingDirection: Direction,
    val choosedDirection: Direction,
    val nodesBehind: MutableList<Pair<PathFinderElement, Score>> = mutableListOf(),
)

class RailGraph( private var config: YamlConfiguration ) {
    val stations = mutableMapOf<String, Int>()
    val pathFinderElements = mutableMapOf<Int, PathFinderElement>()

    init {
        config.getConfigurationSection( "stations" )?.getKeys( false )?.forEach { coords ->
            val sec = config.getConfigurationSection( "stations.$coords" )!!
            val id = sec.getInt( "id" )
            val parts = coords.split( ";" )
            if (parts.size < 4) return@forEach

            val (x, y, z) = parts.takeLast( 3 ).map { it.toInt() }

            stations[ sec.getString( "name" )!! ] = id
            pathFinderElements[ id ] = Station(
                id = id,
                name = sec.getString( "name" )!!,
                world = decodeWorld( parts.dropLast( 3 ).joinToString( ";" ) ),
                x = x,
                y = y,
                z = z,
            )
        }
        config.getConfigurationSection( "crossroads" )?.getKeys( false )?.forEach { coords ->
            val sec = config.getConfigurationSection( "crossroads.$coords" )!!
            val edgesSec = sec.getConfigurationSection( "edges" )
            val id = sec.getInt( "id" )
            val parts = coords.split( ";" )
            if (parts.size < 5) return@forEach

            val direction = Direction.values().firstOrNull { it.name.startsWith( parts[ 0 ] ) } ?: return@forEach
            val (x, y, z) = parts.takeLast( 3 ).map { it.toInt() }

            val edges = mutableMapOf<Direction, Int>()
            if (edgesSec != null) {
                edgesSec.getInt( "north" ).let { if (it != 0) edges[ Direction.NORTH ] = it }
                edgesSec.getInt( "east"  ).let { if (it != 0) edges[ Direction.EAST  ] = it }
                edgesSec.getInt( "south" ).let { if (it != 0) edges[ Direction.SOUTH ] = it }
                edgesSec.getInt( "west"  ).let { if (it != 0) edges[ Direction.WEST  ] = it }
            }

            pathFinderElements[ id ] = Crossroad(
                id = id,
                direction = direction,
                world = decodeWorld( parts.subList( 1, parts.size - 3 ).joinToString( ";" ) ),
                x = x,
                y = y,
                z = z,
                edges = edges,
            )
        }
        config.getConfigurationSection( "edges" )?.getKeys( false )?.forEach { id ->
            val sec = config.getConfigurationSection( "edges.$id" )!!
            val layout = Layout.valueOf( sec.getString( "layout" )!! )
            val nodes = (sec.getList( "nodes" ) as List<Map<String, Int>>).map {
                Pair( it[ "id" ]!!, it[ "score" ]!! )
            }

            pathFinderElements[ id.toInt() ] = Edge(
                id = id.toInt(),
                layout = layout,
                nodes = nodes.toMutableList(),
            )
        }
    }

    private fun encodeWorld( name:String ) = name.replace( ".", "%2E" )
    private fun decodeWorld( key:String ) = key.replace( "%2E", "." )

    fun stationKey( world:String, x:Int, y:Int, z:Int ) = "${encodeWorld( world )};$x;$y;$z"
    fun crossroadKey( direction:Direction, world:String, x:Int, y:Int, z:Int ) =
        "${direction.name.substring( 0, 1 )};${encodeWorld( world )};$x;$y;$z"

    fun getCrossroad( location:Location, direction:Direction, file:File ):Crossroad {
        val world = location.world!!.name
        val x = location.blockX
        val y = location.blockY
        val z = location.blockZ

        val prefix = "crossroads.${crossroadKey( direction, world, x, y, z )}"

        val existing = pathFinderElements[ config.getInt( "${prefix}.id" ) ] as? Crossroad
        if (existing != null) return existing

        val crossroadId = getNextId()

        config.set( "${prefix}.id", crossroadId )
        config.save( file )

        val crossroad = Crossroad( crossroadId, direction, world, x, y, z )

        pathFinderElements[ crossroadId ] = crossroad
        return crossroad
    }

    fun getStation( location:Location, name:String, file:File ):StationResult {
        val isValidStationName = name.firstOrNull()?.isLetter() == true
        if (!isValidStationName) return StationResult.ERROR_WRONG_NAME

        val stationId = stations[ name ]
        var station = pathFinderElements[ stationId ] as Station?

        val world = location.world!!.name
        val x = location.blockX
        val y = location.blockY
        val z = location.blockZ

        if (station != null) {
            val isItPrevLocation = station.world == world && station.x == x && station.y == y && station.z == z

            if (isItPrevLocation || location.block.blockData !is NoteBlock) return StationResult.SUCCESS_FOUND( station )
            if (existsInWorld( station ) != false) return StationResult.ERROR_DUPLICATE

            removeElements( listOf( station.id ), file )
            station = null
        }

        findStationAt( world, x, y, z )?.let { removeElements( listOf( it.id ), file ) }

        station = Station( getNextId(), name, world, x, y, z )

        val prefix = "stations.${stationKey( world, x, y, z )}"

        config.set( "$prefix.name", name )
        config.set( "$prefix.id", station.id )
        config.save( file )

        pathFinderElements[ station.id ] = station
        stations[ name ] = station.id

        return StationResult.SUCCESS_CREATED( station )
    }

    fun findStationAt( world:String, x:Int, y:Int, z:Int ):Station? =
        pathFinderElements.values.filterIsInstance<Station>().firstOrNull {
            it.world == world && it.x == x && it.y == y && it.z == z
        }

    fun locationOf( element:PathFinderElement ):Triple<String, Int, Int>? = when (element) {
        is Station -> Triple( element.world, element.x, element.z )
        is Crossroad -> Triple( element.world, element.x, element.z )
        else -> null
    }

    fun existsInWorld( element:PathFinderElement ):Boolean? {
        when (element) {
            is Station -> {
                val world = Bukkit.getWorld( element.world ) ?: return null
                return hasStationStructure( world.getBlockAt( element.x, element.y, element.z ), element.name )
            }

            is Crossroad -> {
                val world = Bukkit.getWorld( element.world ) ?: return null
                val railBlock = world.getBlockAt( element.x, element.y, element.z )

                if (getRailBlockData( railBlock ) == null) return false

                val turnBlock = getRelativeToRailByDirection( railBlock, element.direction, 2 )
                return (isRailCrossroad( turnBlock )?.size ?: 0) >= 3
            }

            else -> return null
        }
    }

    fun removeElements( ids:Collection<Int>, file:File ):Boolean {
        val queue = ArrayDeque( ids )
        var changed = false

        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            val element = pathFinderElements.remove( id ) ?: continue
            changed = true

            when (element) {
                is Station -> {
                    if (stations[ element.name ] == id) stations.remove( element.name )
                    config.set( "stations.${stationKey( element.world, element.x, element.y, element.z )}", null )
                }

                is Crossroad -> {
                    config.set( "crossroads.${crossroadKey( element.direction, element.world, element.x, element.y, element.z )}", null )
                    queue.addAll( element.edges.values )
                }

                is Edge -> {
                    config.set( "edges.$id", null )

                    for (crossroad in pathFinderElements.values.filterIsInstance<Crossroad>()) {
                        for (dir in crossroad.edges.filterValues { it == id }.keys.toList()) {
                            crossroad.edges.remove( dir )
                            config.set( "crossroads.${crossroadKey( crossroad.direction, crossroad.world, crossroad.x, crossroad.y, crossroad.z )}.edges.${dir.name.lowercase()}", null )
                        }
                    }
                }
            }

            if (element is Edge) continue

            for (edge in pathFinderElements.values.filterIsInstance<Edge>()) {
                if (!edge.nodes.removeIf { it.first == id }) continue

                if (edge.nodes.size < 2) queue.add( edge.id )
                else config.set( "edges.${edge.id}.nodes", edge.nodes.map { mapOf(
                    "id" to it.first,
                    "score" to it.second
                ) } )
            }
        }

        if (changed) config.save( file )
        return changed
    }

    fun upsertCrossroad( crossroadData:ChoosedCrossroadData, file:File ):Boolean {
        val choosedDir = crossroadData.choosedDirection
        val entryDir = crossroadData.railingDirection
        val layout = choosedDir.getLayout()
        var updated = false

        val seenStationIds = mutableSetOf<Int>()
        val nodes = crossroadData.nodesBehind.map { (element, score) ->
            if (!pathFinderElements.containsKey( element.id )) return false

            if (element is Station) {
                if (seenStationIds.contains(element.id)) return false
                seenStationIds.add(element.id)

                val stationPath = "stations.${stationKey( element.world, element.x, element.y, element.z )}"
                if (!config.contains( stationPath )) {
                    config.set( "$stationPath.name", element.name )
                    config.set( "$stationPath.id", element.id )
                } else if (config.getString( "$stationPath.name" ) != element.name) {
                    config.set("$stationPath.name", element.name)
                }
            }

            Pair( element.id, score )
        }

        val crossroad = pathFinderElements[ crossroadData.crossroad.id ] as? Crossroad ?: return false
        var edgeId = crossroad.edges[ choosedDir ]

        if (edgeId == null) {
            edgeId = getNextId()
            updated = true
        }

        val newEdge = Edge( edgeId, layout, nodes.toMutableList() )
        if (!updated) {
            val currEdge = pathFinderElements[ edgeId ]

            updated = currEdge !is Edge
                    || currEdge.layout != newEdge.layout
                    || currEdge.nodes != newEdge.nodes
        }

        pathFinderElements[ edgeId ] = newEdge

        val edgePath = "edges.$edgeId"

        config.set( "$edgePath.layout", layout.name )
        config.set( "$edgePath.nodes", nodes.map { mapOf(
            "id" to it.first,
            "score" to it.second
        ) } )

        crossroad.edges[ choosedDir ] = edgeId
        val coordsKey = crossroadKey( crossroad.direction, crossroad.world, crossroad.x, crossroad.y, crossroad.z )
        config.set( "crossroads.$coordsKey.edges.${choosedDir.name.lowercase()}", edgeId )

        config.save( file )
        return updated
    }

    fun getNextId():Int {
        val id = maxOf( config.getInt( "nextId", 1 ), (pathFinderElements.keys.maxOrNull() ?: 0) + 1 )
        config.set( "nextId", id + 1 )

        return id
    }

    private fun hasStationStructure( noteblock:Block, name:String ):Boolean {
        if (noteblock.blockData !is NoteBlock) return false
        if (noteblock.getRelative( 0, -1, 0 ).type.isAir) return false

        return Direction.values().any { direction ->
            val railBlock = getRelativeToRailByDirection( noteblock, direction )
            if (railBlock.type != Material.ACTIVATOR_RAIL) return@any false

            val shape = getRailBlockData( railBlock )?.shape ?: return@any false
            val noteblockIsOnXAxis = direction == Direction.EAST || direction == Direction.WEST

            if ((shape == Rail.Shape.NORTH_SOUTH) != noteblockIsOnXAxis) return@any false

            val signOffsets = if (shape == Rail.Shape.NORTH_SOUTH) listOf( Triple( 0, 0, 1 ), Triple( 0, 0, -1 ), Triple( 0, 1, 0 ), Triple( 0, -2, 0 ) )
            else listOf( Triple( 1, 0, 0 ), Triple( -1, 0, 0 ), Triple( 0, 1, 0 ), Triple( 0, -2, 0 ) )

            val sign = signOffsets
                .map { noteblock.getRelative( it.first, it.second, it.third ) }
                .firstOrNull { it.type == Material.OAK_WALL_SIGN || it.type == Material.OAK_SIGN }

            val signState = sign?.state as? Sign ?: return@any false
            val signText = signState.lines().joinToString( " " ) { PlainTextComponentSerializer.plainText().serialize( it ) }.trim()

            signText == name
        }
    }
}

class PathFinder(private val graph: RailGraph) {
    fun findPath( start:PathFinderElement, target:PathFinderElement ):List<Int> {
        val startValidated = if (start !is Station) start else {
            graph.pathFinderElements.values.find {
                if (it is Edge) it.nodes.any { n -> n.first == start.id }
                else false
            } ?: return emptyList()
        }

        val openSet = PriorityQueue(compareBy<Pair<Int, Int>> { it.second })
        val cameFrom = mutableMapOf<Int, Int?>()

        val gScore = mutableMapOf<Int, Int>().withDefault { Int.MAX_VALUE }
        val fScore = mutableMapOf<Int, Int>().withDefault { Int.MAX_VALUE }

        val startId = startValidated.id
        val targetId = target.id

        gScore[ startId ] = 0
        fScore[ startId ] = heuristic( startValidated, target )

        openSet.add( startId to fScore[ startId ]!! )

        while (openSet.isNotEmpty()) {
            val (currentId, _) = openSet.poll()
            val current = graph.pathFinderElements[ currentId ] ?: continue

            if (currentId == targetId) {
                return reconstructPath( cameFrom, currentId )
            }

            for ((neighbor, cost) in neighbors( current )) {
                val tentativeG = gScore.getValue( currentId ) + cost

                if (tentativeG < gScore.getValue( neighbor )) {
                    cameFrom[ neighbor ] = currentId
                    gScore[ neighbor ] = tentativeG

                    val neighborElem = graph.pathFinderElements[ neighbor ] ?: continue
                    val f = tentativeG + heuristic( neighborElem, target )
                    fScore[ neighbor ] = f

                    openSet.add( neighbor to f )
                }
            }
        }

        return emptyList()
    }

    private fun neighbors( elem:PathFinderElement ):List<Pair<Int, Int>> = when (elem) {
        is Crossroad -> elem.edges.values.mapNotNull { edgeId -> graph.pathFinderElements[ edgeId ]?.let { Pair( it.id, 0 ) } }
        is Edge -> elem.nodes.filter { graph.pathFinderElements.containsKey( it.first ) }.map { (nodeId, cost) -> nodeId to cost }
        else -> listOf()
    }

    private fun positionOf( elem:PathFinderElement ):Triple<Int, Int, Int>? = when (elem) {
        is Crossroad -> Triple( elem.x, elem.y, elem.z )
        is Station -> Triple( elem.x, elem.y, elem.z )
        else -> null
    }

    private fun worldOf( elem:PathFinderElement ):String? = when (elem) {
        is Crossroad -> elem.world
        is Station -> elem.world
        else -> null
    }

    private fun heuristic( a:PathFinderElement, b:PathFinderElement ):Int {
        val worldA = worldOf( a )
        val worldB = worldOf( b )
        if (worldA != null && worldB != null && worldA != worldB) return 0

        val pa = positionOf( a ) ?: return 0
        val pb = positionOf( b ) ?: return 0

        return abs( pa.first - pb.first ) +
                abs( pa.second - pb.second ) +
                abs( pa.third - pb.third )
    }

    private fun reconstructPath( cameFrom:Map<Int, Int?>, current:Int ):List<Int> {
        val path = mutableListOf<Int>()
        var cur: Int? = current

        while (cur != null) {
            path.add( cur )
            cur = cameFrom[ cur ]
        }

        return path.reversed()
    }
}