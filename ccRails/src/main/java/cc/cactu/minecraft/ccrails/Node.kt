package cc.cactu.minecraft.ccrails

import org.bukkit.Location
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.PriorityQueue
import kotlin.math.abs

typealias Score = Int


interface PathFinderElement {
    val id: Int
}

data class Station(
    override val id: Int,
    val name: String,
    val x: Int,
    val y: Int,
    val z: Int,
) : PathFinderElement

data class Crossroad(
    override val id: Int,
    val x: Int,
    val y: Int,
    val z: Int,
    var edges:MutableMap<Direction, Int> = mutableMapOf(),
) : PathFinderElement

data class Edge(
    override val id: Int,
    val layout: Layout,
    val nodes: List<Pair<Int, Score>>,
) : PathFinderElement

data class ChoosedCrossroadData(
    val crossroad: Crossroad,
    val choosedDirection: Direction,
    val nodesBehind: MutableList<Pair<PathFinderElement, Score>> = mutableListOf(),
)

class RailGraph( private var config: YamlConfiguration ) {
    val stations = mutableMapOf<String, Int>()
    val pathFinderElements = mutableMapOf<Int, PathFinderElement>()

    init {
        config.getConfigurationSection( "stations" )?.getKeys( false )?.forEach { id ->
            val sec = config.getConfigurationSection( "stations.$id" )!!

            stations[ sec.getString( "name" )!! ] = id.toInt()
            pathFinderElements[ id.toInt() ] = Station(
                id = id.toInt(),
                name = sec.getString( "name" )!!,
                x = sec.getInt( "x" ),
                y = sec.getInt( "y" ),
                z = sec.getInt( "z" )
            )
        }
        config.getConfigurationSection( "crossroads" )?.getKeys( false )?.forEach { coords ->
            val sec = config.getConfigurationSection( "crossroads.$coords" )!!
            val edgesSec = sec.getConfigurationSection( "edges" )
            val id = sec.getInt( "id" )
            val (_dir, x, y, z) = coords.split( ";" )

            val edges = mutableMapOf<Direction, Int>()
            if (edgesSec != null) {
                edgesSec.getInt( "north" ).let { if (it != 0) edges[ Direction.NORTH ] = it }
                edgesSec.getInt( "east"  ).let { if (it != 0) edges[ Direction.EAST  ] = it }
                edgesSec.getInt( "south" ).let { if (it != 0) edges[ Direction.SOUTH ] = it }
                edgesSec.getInt( "west"  ).let { if (it != 0) edges[ Direction.WEST  ] = it }
            }

            pathFinderElements[ id ] = Crossroad(
                id = id,
                x = x.toInt(),
                y = y.toInt(),
                z = z.toInt(),
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
                nodes = nodes,
            )
        }
    }

    fun getCrossroad( location:Location, direction:Direction, file:File ):Crossroad {
        val dir = direction.name.substring( 0, 1 )
        val x = location.x.toInt()
        val y = location.y.toInt()
        val z = location.z.toInt()

        val prefix = "crossroads.$dir;$x;$y;$z"
        var crossroadId = config.getInt( "${prefix}.id" )

        return if (crossroadId != 0) {
            println( "crossroad found ($crossroadId)" )
            pathFinderElements[ crossroadId ] as Crossroad
        } else {
            crossroadId = getNextId()

            println( "Creating new crossroad ($crossroadId)" )
            config.set( "${prefix}.id", crossroadId )
            config.save( file )

            val crossroad = Crossroad( crossroadId, x, y, z )

            pathFinderElements[ crossroadId ] = crossroad
            crossroad
        }
    }

    fun getStation( location:Location, name:String, file:File ):Station {
        val stationId = stations[ name ]
        var station = pathFinderElements[ stationId ] as Station?
        if (station != null) return station

        val x = location.x.toInt()
        val y = location.y.toInt()
        val z = location.z.toInt()

        station = Station( getNextId(), name, x, y, z )

        val prefix = "stations.${station.id}"

//        config.set( "${prefix}.id", station.id )
        config.set( "$prefix.x", x )
        config.set( "$prefix.y", y )
        config.set( "$prefix.z", z )
        config.save( file )

        pathFinderElements[ station.id ] = station
        stations[ name ] = station.id

        return station
    }

    fun upsertCrossroad(crossroadData:ChoosedCrossroadData, file:File ):Boolean {
        val choosedDir = crossroadData.choosedDirection
        val layout = choosedDir.getLayout()
        var updated = false

        val seenStationIds = mutableSetOf<Int>()
        val nodes = crossroadData.nodesBehind.map { (element, score) ->
            if (element is Station) {
                if (seenStationIds.contains(element.id)) return false
                seenStationIds.add(element.id)

                val stationPath = "stations.${element.id}"
                if (!config.contains( stationPath )) {
                    config.set( "$stationPath.name", element.name )
                    config.set( "$stationPath.x", element.x )
                    config.set( "$stationPath.y", element.y )
                    config.set( "$stationPath.z", element.z )
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
            println( "New edge $edgeId" )
            updated = true
        }

        println( "Edge $edgeId with nodes = $nodes | crossroad ${crossroad.id} with dir =  $choosedDir" )

        // Edge config update
        val newEdge = Edge( edgeId, layout, nodes )
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

        // Starting crossroad
        crossroad.edges[ choosedDir ] = edgeId

        val coordsKey = "${crossroad.x};${crossroad.y};${crossroad.z}"
        config.set( "crossroads.$coordsKey.edges.${choosedDir.name.lowercase()}", edgeId )

        config.save( file )
        return updated
    }

    fun getNextId() = (pathFinderElements.keys.maxOrNull() ?: 0) + 1
}

class PathFinder(private val graph: RailGraph) {
    fun findPath( start:PathFinderElement, target:PathFinderElement ):List<Int> {
        val startValidated = if (start !is Station) start else {
            graph.pathFinderElements.values.find {
                if (it is Edge) it.nodes.any { n -> n.first == start.id }
                else false
            }!!
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

                    val neighborElem = graph.pathFinderElements[ neighbor ]!!
                    val f = tentativeG + heuristic( neighborElem, target )
                    fScore[ neighbor ] = f

                    openSet.add( neighbor to f )
                }
            }
        }

        return emptyList() // brak ścieżki
    }

    private fun neighbors( elem:PathFinderElement ):List<Pair<Int, Int>> = when (elem) {
        is Crossroad -> elem.edges.values.map { Pair( graph.pathFinderElements[ it ]!!.id, 0 ) }
        is Edge -> elem.nodes.map { (nodeId, cost) -> nodeId to cost }
        else -> listOf()
    }

    private fun positionOf(elem: PathFinderElement):Triple<Int, Int, Int>? = when (elem) {
        is Crossroad -> Triple( elem.x, elem.y, elem.z )
        is Station -> Triple( elem.x, elem.y, elem.z )
        else -> null
    }

    private fun heuristic( a:PathFinderElement, b:PathFinderElement ):Int {
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
