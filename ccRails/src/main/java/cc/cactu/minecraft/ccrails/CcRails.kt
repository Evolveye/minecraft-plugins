package cc.cactu.minecraft.ccrails

import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.kyori.adventure.title.Title
import org.apache.commons.lang3.tuple.MutableTriple
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.Sign
import org.bukkit.block.data.Rail
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Minecart
import org.bukkit.entity.Player
import org.bukkit.entity.Vehicle
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.vehicle.VehicleDestroyEvent
import org.bukkit.event.vehicle.VehicleEntityCollisionEvent
import org.bukkit.event.vehicle.VehicleExitEvent
import org.bukkit.event.vehicle.VehicleMoveEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BookMeta
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.util.Vector
import java.io.File
import java.util.*
import kotlin.collections.component1
import kotlin.collections.component2


data class VehicleData(
    var blockLocation: Location,
    var blockMargin: Boolean = false,
    var choosedCrossroadData: ChoosedCrossroadData? = null,
    var path: MutableTriple<Station, List<Int>?, Int?>? = null,
) {
    fun recalculatePath( pathFinder:PathFinder, element:PathFinderElement ):Boolean {
        if (path == null) return false

        path!!.middle = pathFinder.findPath( element, path!!.left ) as List<Int>?

        if (path!!.middle!!.isEmpty()) return false

        path!!.right = 1

        return true
    }
}

data class CrossroadData(
    val crossroad: Crossroad,
    val railingDirection: Direction,
    val crossBlock: Block,
    val bigCrossBlock: Block?,
)

class CcRails : JavaPlugin(), Listener {
    private lateinit var configFile: File
    private lateinit var config: YamlConfiguration
    private lateinit var railPathFinder: PathFinder
    private lateinit var railsGraph: RailGraph

    private val straitRoadMaxSpeed = 1.4
    private val diagonalRoadMaxSpeed = 0.9
    private val ascendingRoadMaxSpeed = 0.5

    private val vehicleDataset = mutableMapOf<UUID, VehicleData>()
    private val playerDataset = mutableMapOf<String, Long>()

    override fun onEnable() {
        server.pluginManager.registerEvents( this, this )
        forceLoadClasses()
        logger.info( "Hello ccRails" )

        configFile = File( dataFolder, "storage.yml" )

        if (!configFile.exists()) {
            configFile.parentFile.mkdirs()
            saveResource( "storage.yml", false )
        }

        Timer().scheduleAtFixedRate( object : TimerTask() {
            override fun run() {
                config = YamlConfiguration.loadConfiguration( configFile )
                railsGraph = RailGraph( config )
                railPathFinder = PathFinder( railsGraph )
            }
        }, 0, 1000 * 60 * 30 )
    }

    private fun forceLoadClasses() {
        val packageName = this::class.java.packageName
        val loader = this::class.java.classLoader

        val classesToLoad = listOf(
            "Layout",
            "Direction",
            "Station",
            "Crossroad",
            "Edge",
            "Turn",
        )

        for (className in classesToLoad) {
            try {
                Class.forName("$packageName.$className", true, loader)
//                logger.info("Class $className pre-loaded successfully.")
            } catch (e: Exception) {
                logger.warning("Could not pre-load class: $className")
            }
        }
    }

    fun updateTurnRailShape( turnBlock:Block, railingDirection:Direction, turn:Turn ): Rail? {
        val turnRail = getRailBlockData( turnBlock ) ?: return null

        if (!listOf( Rail.Shape.NORTH_EAST, Rail.Shape.NORTH_WEST, Rail.Shape.SOUTH_WEST, Rail.Shape.SOUTH_EAST, Rail.Shape.NORTH_SOUTH, Rail.Shape.EAST_WEST ).contains( turnRail.shape )) return null

        fun setTurnShape( destinationShape:Rail.Shape, x:Int, z:Int, availableRailShapes:List<Rail.Shape> ): Boolean? {
            val afterTurn = getRailBlockData( turnBlock.getRelative( x, 0, z ) ) ?: return null
            if (!availableRailShapes.contains( afterTurn.shape )) return null

            if (turnRail.shape != destinationShape) turnRail.shape = destinationShape

            return true
        }

        if (railingDirection == Direction.EAST) {
            when (turn) {
                Turn.RIGHT -> {
                    setTurnShape( Rail.Shape.SOUTH_WEST, 0, 1, listOf( Rail.Shape.NORTH_EAST, Rail.Shape.NORTH_WEST, Rail.Shape.NORTH_SOUTH ) )
                        ?: setTurnShape( Rail.Shape.EAST_WEST, 1, 0, listOf( Rail.Shape.NORTH_WEST, Rail.Shape.SOUTH_WEST, Rail.Shape.EAST_WEST ) )
                        ?: return null
                }

                Turn.LEFT -> {
                    setTurnShape( Rail.Shape.NORTH_WEST, 0, -1, listOf( Rail.Shape.SOUTH_EAST, Rail.Shape.SOUTH_WEST, Rail.Shape.NORTH_SOUTH ) )
                        ?: setTurnShape( Rail.Shape.EAST_WEST, 1, 0, listOf( Rail.Shape.NORTH_WEST, Rail.Shape.SOUTH_WEST, Rail.Shape.EAST_WEST ) )
                        ?: return null
                }

                else -> {
                    setTurnShape( Rail.Shape.EAST_WEST, 1, 0, listOf( Rail.Shape.NORTH_WEST, Rail.Shape.SOUTH_WEST, Rail.Shape.EAST_WEST ) )
                        ?: return null
                }
            }
        } else if (railingDirection == Direction.WEST) {
            when (turn) {
                Turn.RIGHT -> {
                    setTurnShape( Rail.Shape.NORTH_EAST, 0, -1, listOf( Rail.Shape.SOUTH_EAST, Rail.Shape.SOUTH_WEST, Rail.Shape.NORTH_SOUTH ) )
                        ?: setTurnShape( Rail.Shape.EAST_WEST, -1, 0, listOf( Rail.Shape.NORTH_EAST, Rail.Shape.SOUTH_EAST, Rail.Shape.EAST_WEST ) )
                        ?: return null
                }

                Turn.LEFT -> {
                    setTurnShape( Rail.Shape.SOUTH_EAST, 0, 1, listOf( Rail.Shape.NORTH_EAST, Rail.Shape.NORTH_WEST, Rail.Shape.NORTH_SOUTH ) )
                        ?: setTurnShape( Rail.Shape.EAST_WEST, -1, 0, listOf( Rail.Shape.NORTH_EAST, Rail.Shape.SOUTH_EAST, Rail.Shape.EAST_WEST ) )
                        ?: return null
                }

                else -> {
                    setTurnShape( Rail.Shape.EAST_WEST, -1, 0, listOf( Rail.Shape.NORTH_EAST, Rail.Shape.SOUTH_EAST, Rail.Shape.EAST_WEST ) )
                        ?: return null
                }
            }
        }
        if (railingDirection == Direction.SOUTH) {
            when (turn) {
                Turn.RIGHT -> {
                    setTurnShape( Rail.Shape.NORTH_WEST, -1, 0, listOf( Rail.Shape.NORTH_EAST, Rail.Shape.SOUTH_EAST, Rail.Shape.EAST_WEST ) )
                        ?: setTurnShape( Rail.Shape.NORTH_SOUTH, -1, 0, listOf( Rail.Shape.NORTH_EAST, Rail.Shape.NORTH_WEST, Rail.Shape.NORTH_SOUTH ) )
                        ?: return null
                }

                Turn.LEFT -> {
                    setTurnShape( Rail.Shape.NORTH_EAST, 1, 0, listOf( Rail.Shape.NORTH_WEST, Rail.Shape.SOUTH_WEST, Rail.Shape.EAST_WEST ) )
                        ?: setTurnShape( Rail.Shape.NORTH_SOUTH, 0, 1, listOf( Rail.Shape.NORTH_EAST, Rail.Shape.NORTH_WEST, Rail.Shape.NORTH_SOUTH ) )
                        ?: return null
                }

                else -> {
                    setTurnShape( Rail.Shape.NORTH_SOUTH, 0, 1, listOf( Rail.Shape.NORTH_EAST, Rail.Shape.NORTH_WEST, Rail.Shape.NORTH_SOUTH ) )
                        ?: return null
                }
            }
        } else { // Direction.NORTH
            when (turn) {
                Turn.RIGHT -> {
                    setTurnShape( Rail.Shape.SOUTH_EAST, 1, 0, listOf( Rail.Shape.NORTH_WEST, Rail.Shape.SOUTH_WEST, Rail.Shape.EAST_WEST ) )
                        ?: setTurnShape( Rail.Shape.NORTH_SOUTH, 0, 1, listOf( Rail.Shape.SOUTH_EAST, Rail.Shape.SOUTH_WEST, Rail.Shape.NORTH_SOUTH ) )
                        ?: return null
                }

                Turn.LEFT -> {
                    setTurnShape( Rail.Shape.SOUTH_WEST, -1, 0, listOf( Rail.Shape.NORTH_EAST, Rail.Shape.SOUTH_EAST, Rail.Shape.EAST_WEST ) )
                        ?: setTurnShape( Rail.Shape.NORTH_SOUTH, 0, 1, listOf( Rail.Shape.SOUTH_EAST, Rail.Shape.SOUTH_WEST, Rail.Shape.NORTH_SOUTH ) )
                        ?: return null
                }

                else -> {
                    setTurnShape( Rail.Shape.NORTH_SOUTH, 0, -1, listOf( Rail.Shape.SOUTH_EAST, Rail.Shape.SOUTH_WEST, Rail.Shape.NORTH_SOUTH ) )
                        ?: return null
                }
            }
        }

        return turnRail
    }

    fun getCrossroadData( railBlock:Block, vehicle:Vehicle ): CrossroadData? {
        val velocity = vehicle.velocity
        val railingDirection = getRailingDiretion( velocity )

        val turnBlock = getRelativeToRailByDirection( railBlock, railingDirection, 2 )
        val isCross = isRailCrossroad( turnBlock )
        if (!isCross) return null

        val nextTurnBlock = getRelativeToRailByDirection( turnBlock, railingDirection, 2 )
        val isBigCross = isRailCrossroad( nextTurnBlock )

        val crossroad = railsGraph.getCrossroad( railBlock.location, railingDirection, configFile )

        return CrossroadData(
            crossroad,
            railingDirection,
            turnBlock,
            if (isBigCross) nextTurnBlock else null,
        )
    }

    fun getPredefinedTurn( minecart:Minecart, crossroadData:CrossroadData ):Pair<Boolean, Turn?> {
        val vehicleData = vehicleDataset[ minecart.uniqueId ]
        val vehiclePath = vehicleData?.path ?: return Pair( true, null )

        val predefinedTurn = if (vehiclePath == null) null else {
            val nextPathElement = if (vehiclePath.middle != null) {
                val nextStep = vehiclePath.right!! + 2

                if (vehiclePath.middle!!.size <= nextStep) null else {
                    vehiclePath.right = nextStep
                    vehiclePath.middle?.get( nextStep )
                }
            } else {
                if (!vehicleData!!.recalculatePath( railPathFinder, crossroadData.crossroad )) return Pair( false, null )
                vehiclePath.middle?.get( vehiclePath.right!! )
            }

            val edge = crossroadData.crossroad.edges.entries.find { (_, value) -> value == nextPathElement }
            if (edge == null) null
            else Turn.fromDirectiona( crossroadData.railingDirection, edge.key )
        }

        return Pair( true, predefinedTurn )
    }

    fun turn(minecart:Minecart, crossroadData:CrossroadData, predefinedTurn:Turn?=null ): Pair<Boolean, Direction> {
        val turn = predefinedTurn ?: run {
            val player = minecart.passengers.firstOrNull()

            if (player !is Player) Turn.STRAIGHT
            else if (player.inventory.itemInMainHand.type == Material.OAK_SIGN) Turn.RIGHT
            else if (player.inventory.itemInOffHand.type == Material.OAK_SIGN) Turn.LEFT
            else Turn.STRAIGHT
        }

        var turned = false
        fun setTurn( block:Block, rail:Rail? ):Boolean {
            if (rail == null) return false
            block.setBlockData( rail, true )
            return true
        }

        if (crossroadData.bigCrossBlock != null && turn != Turn.RIGHT) {
            val firstTurnRail = updateTurnRailShape( crossroadData.crossBlock, crossroadData.railingDirection, Turn.STRAIGHT )
            setTurn( crossroadData.crossBlock, firstTurnRail )

            val nextTurnBlockUpdate = updateTurnRailShape( crossroadData.bigCrossBlock, crossroadData.railingDirection, turn )
                ?: updateTurnRailShape( crossroadData.bigCrossBlock, crossroadData.railingDirection, if (turn == Turn.LEFT) Turn.STRAIGHT else Turn.LEFT )

            setTurn( crossroadData.bigCrossBlock, nextTurnBlockUpdate )

            if (turn == Turn.LEFT) {
                val dirAfterLeftTurn = crossroadData.railingDirection.getAfterTurn( Turn.LEFT )
                val thirdTurnBlock = getRelativeToRailByDirection( crossroadData.bigCrossBlock, dirAfterLeftTurn, 2 )
                val thirdTurnBlockUpdate = updateTurnRailShape( thirdTurnBlock, dirAfterLeftTurn, Turn.STRAIGHT )
                setTurn( thirdTurnBlock, thirdTurnBlockUpdate )
            }

            turned = true
        } else {
            turned = setTurn(
                crossroadData.crossBlock,
                updateTurnRailShape( crossroadData.crossBlock, crossroadData.railingDirection, turn )
            )
        }

        val finalDir = Pair( turned, crossroadData.railingDirection.getAfterTurn( turn ) )

//        println( "isBigCross=$isBigCross predefinedTurn=$predefinedTurn | " + getRailingDiretion( velocity ).toString() + " -> $turn = $finalDir" )

        return finalDir
    }

    @EventHandler
    fun onVehicleMove( event:VehicleMoveEvent ) {
        val vehicle = event.vehicle
        if (vehicle !is Minecart) return

        val vehicleId = vehicle.uniqueId
        val to = event.to

        var vehicleData = vehicleDataset[ vehicleId ]
        if (vehicleData == null) {
            vehicleDataset[ vehicleId ] = VehicleData( to )
            vehicleData = vehicleDataset[ vehicleId ]
        } else {
            if (vehicleData.blockMargin) {
                if (to.distance( vehicleData.blockLocation ) <= 1.0) return
            } else {
                val blockLoc = vehicleData.blockLocation
                if (to.x == blockLoc.x && to.y == blockLoc.y && to.z == blockLoc.z) return
            }

            vehicleData.blockMargin = false
            vehicleData.blockLocation = to
        }

        val railBlock = if (vehicle.location.block.type === Material.AIR) vehicle.location.block.getRelative( BlockFace.DOWN )
        else vehicle.location.block

        var maxSpeed:Double? = null

        if (railBlock.type == Material.DETECTOR_RAIL) maxSpeed = handleDetectorRail( event, vehicleData!!, railBlock )
        else if (railBlock.type == Material.ACTIVATOR_RAIL) {
            if (handleActivatorRails( event, vehicleData!!, railBlock )) return
        }

        if (maxSpeed == null) {
            val railData = getRailBlockData( railBlock ) ?: return

            maxSpeed = when (railData.shape) {
                Rail.Shape.NORTH_EAST, Rail.Shape.NORTH_WEST, Rail.Shape.SOUTH_EAST, Rail.Shape.SOUTH_WEST -> diagonalRoadMaxSpeed
                Rail.Shape.ASCENDING_NORTH, Rail.Shape.ASCENDING_SOUTH, Rail.Shape.ASCENDING_EAST, Rail.Shape.ASCENDING_WEST -> ascendingRoadMaxSpeed
                else -> {
                    val nextBlock = getRelativeToRailByVelocity( railBlock, vehicle.velocity )
                    val nextRailData = getRailBlockData( nextBlock )

                    if (nextRailData == null) straitRoadMaxSpeed
                    else when (nextRailData.shape) {
                        Rail.Shape.ASCENDING_NORTH, Rail.Shape.ASCENDING_SOUTH, Rail.Shape.ASCENDING_EAST, Rail.Shape.ASCENDING_WEST -> ascendingRoadMaxSpeed
                        else -> straitRoadMaxSpeed
                    }
                }
            }
        }

        vehicle.maxSpeed = maxSpeed
        vehicle.velocity = straighten( vehicle.velocity, vehicle.maxSpeed )
    }

    @EventHandler
    fun onVehicleDestroy( event:VehicleDestroyEvent ) {
        vehicleDataset.remove( event.vehicle.uniqueId )
    }

    @EventHandler
    fun onVehicleExit( event:VehicleExitEvent ) {
        val vehicle = event.vehicle
        if (vehicle !is Minecart) return

        if (vehicleDataset[ vehicle.uniqueId ]?.path != null) {
            for (passenger in vehicle.passengers) {
                vehicle.removePassenger( passenger )

                val safeLoc = passenger.location.clone()
                safeLoc.y = Math.floor( safeLoc.y ) + 1.0

                passenger.teleport( safeLoc )
            }

            vehicle.remove()
        }

        vehicleDataset.remove( vehicle.uniqueId )
    }

    @EventHandler
    fun onEntityRemove( event:EntityRemoveFromWorldEvent ) {
        vehicleDataset.remove( event.entity.uniqueId )
    }

    @EventHandler
    fun onPlayerMove( event:PlayerMoveEvent ) {
        val vehicle = event.player.vehicle ?: return
//        if (vehicle is Boat) return handleBoatMove( event )
    }

    @EventHandler
    fun onMinecartCollision( event:VehicleEntityCollisionEvent ) {
        val vehicle = event.vehicle
        val entity = event.entity

        if (vehicle is Minecart && vehicle.velocity.length() > 0.4) {
            val direction = vehicle.velocity.clone().normalize().add( Vector( 0.0, 1.0, 0.0 ) )
            val angleRadians = Math.toRadians( 45.0 )

            direction.x = direction.x * Math.cos( angleRadians ) - direction.z * Math.sin( angleRadians )
            direction.z = direction.x * Math.sin( angleRadians ) + direction.z * Math.cos( angleRadians )
            direction.multiply( 1.5 )

            entity.velocity = direction

            event.isCancelled = true
        }
    }

    @EventHandler( ignoreCancelled = true )
    fun onRailClick( event:PlayerInteractEvent ) {
        val player = event.player
        val block = event.clickedBlock ?: return
        val item = event.item ?: return

        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        if (block.blockData !is Rail) return

        if (item.type != Material.WRITABLE_BOOK && item.type != Material.WRITTEN_BOOK) return

        val meta = item.itemMeta as? BookMeta ?: return
        if (!meta.hasPages()) return

        val firstPage = meta.page(1) as TextComponent
        val stationName = firstPage.content().split( "\n" ).firstOrNull()?.trim()
        if (stationName.isNullOrEmpty()) return

        val stationId = railsGraph.stations[ stationName ]
        val station = railsGraph.pathFinderElements[ stationId ]

        event.isCancelled = true
        player.server.scheduler.runTask(this, Runnable {
            player.closeInventory()
        } )

        if (station == null) {
            player.sendMessage( "§aPodana stacja (§f$stationName§a) nie istnieje." )
            return
        }

        val railLocation = block.location.add(0.5, 0.2, 0.5 )
        val minecart = block.world.spawn( railLocation, Minecart::class.java )

        minecart.addPassenger( player )

        val direction = player.location.direction

        direction.y = 0.0
        if (direction.length() > 0) direction.normalize()

        minecart.velocity = direction.multiply( 0.5 )

        player.sendMessage( "§aWyruszasz w kierunku stacji: §f$stationName" )

        vehicleDataset[ minecart.uniqueId ] = VehicleData(
            blockLocation =  minecart.location,
            path = MutableTriple( station as Station, null, null )
        )

        println( "[RailSystem] Gracz ${player.name} wybiera stację: $stationName" )
    }

    fun handleDetectorRail( event:VehicleMoveEvent, vehicleData:VehicleData, railBlock:Block ):Double? {
        val minecart = event.vehicle as Minecart
        vehicleData.blockMargin = true

        val crossroadData = getCrossroadData( railBlock, minecart ) ?: return null
        val (isPredefinedTurnCalculated, predefinedTurn) = getPredefinedTurn( minecart, crossroadData )

        if (!isPredefinedTurnCalculated) {
            minecart.passengers.first().sendMessage( "§aNie znaleziono sprawdzonej trasy do wybranej stacji (§f${vehicleData.path!!.left.name}§a)." )
            minecart.remove()
            return null
        }

        val (turned, dirAfterTurn) = turn( minecart, crossroadData, predefinedTurn )

        println( "is crossroad ${railBlock.location}" )

        if (vehicleData.choosedCrossroadData != null) {
            vehicleData.choosedCrossroadData!!.nodesBehind.add( Pair( crossroadData.crossroad, 0 ) )

            if (railsGraph.upsertCrossroad( vehicleData.choosedCrossroadData!!, configFile ) && vehicleData.path?.middle != null) {
                vehicleData.recalculatePath( railPathFinder, crossroadData.crossroad )
            }
        }

        vehicleData.choosedCrossroadData = ChoosedCrossroadData(
            crossroad = crossroadData.crossroad,
            choosedDirection = dirAfterTurn,
            nodesBehind = mutableListOf( Pair( crossroadData.crossroad, 0 ) )
        )

        if (turned) return Math.min( Math.min( straitRoadMaxSpeed, diagonalRoadMaxSpeed ), ascendingRoadMaxSpeed )
        return null
    }

    fun handleActivatorRails( event:VehicleMoveEvent, vehicleData:VehicleData, railBlock:Block ):Boolean {
        val vehicle = event.vehicle as Minecart
        val player = vehicle.passengers.firstOrNull()

        vehicleData.blockMargin = true

        if (player !is Player) return false

        val noteblockCoords:List<Vector>
        val stationSignCoords:List<Vector>
        val railData = getRailBlockData( railBlock ) ?: return false

        if (railData.shape == Rail.Shape.NORTH_SOUTH) {
            noteblockCoords = listOf(
                Vector( -1, 0, 0 ), Vector( 1, 0, 0 ),
                Vector( -1, 0, 1 ), Vector( 1, 0, 1 ),
                Vector( -1, 0, -1 ), Vector( 1, 0, -1 ),
            )
            stationSignCoords = listOf(
                Vector( 0, 0, 1 ),
                Vector( 0, 0, -1 ),
                Vector( 0, 1, 0 ),
                Vector( 0, -3, 0 ),
            )
        } else {
            noteblockCoords = listOf(
                Vector( 0, 0, -1 ), Vector( 0, 0, 1 ),
                Vector( 1, 0, -1 ), Vector( 1, 0, 1 ),
                Vector( -1, 0, -1 ), Vector( -1, 0, 1 ),
            )
            stationSignCoords = listOf(
                Vector( 1, 0, 0 ),
                Vector( -1, 0, 0 ),
                Vector( 0, 1, 0 ),
                Vector( 0, -3, 0 ),
            )
        }

        var noteblock:Block? = null
        for (coord in noteblockCoords) {
            noteblock = railBlock.getRelative( coord.x.toInt(), coord.y.toInt(), coord.z.toInt() )
            if (noteblock.type == Material.NOTE_BLOCK) break
        }

        if (noteblock == null) return false

        var sign:Block? = null
        for (coord in stationSignCoords) {
            sign = noteblock.getRelative( coord.x.toInt(), coord.y.toInt(), coord.z.toInt() )
            if (sign.type == Material.OAK_WALL_SIGN || sign.type == Material.OAK_SIGN) break
        }

        val signState = sign?.state

        if (signState !is Sign) return false

        val signText = signState.lines().map { PlainTextComponentSerializer.plainText().serialize( it ) }.joinToString( " " ).trim()
        val blockBelow = noteblock.getRelative( 0, -2, 0 )

        if (blockBelow.type != Material.REDSTONE_WALL_TORCH) return false

        val station = railsGraph.getStation( noteblock.location, signText, configFile )
        val path = vehicleData.path

        if (vehicleData.choosedCrossroadData != null) {
            vehicleData.choosedCrossroadData!!.nodesBehind.add( Pair( station, 0 ) )
            railsGraph.upsertCrossroad( vehicleData.choosedCrossroadData!!, configFile )
        }

        if (path != null && path.left.name != signText) return false
        if (path == null) vehicle.world.dropItem( vehicle.location, ItemStack( Material.MINECART ) )

        vehicleDataset.remove( event.vehicle.uniqueId )

        vehicle.velocity = Vector( 0, 0, 0 )
        vehicle.remove()

        val title = Title.title(
            Component.text( signText, NamedTextColor.GREEN ),
            Component.text( "Oto stacja, na której się znajdujesz" ),
        )

        player.showTitle( title )

        return true
    }
}
