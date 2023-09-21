package cc.cactu.minecraft.ccrails

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Rail
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.configuration.serialization.ConfigurationSerialization
import org.bukkit.entity.Minecart
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.vehicle.VehicleEntityCollisionEvent
import org.bukkit.event.vehicle.VehicleMoveEvent
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.util.Vector
import java.io.File
import java.util.*
import kotlin.math.absoluteValue
import kotlin.math.sign

class CcRails : JavaPlugin(), Listener {
    private lateinit var configFile: File
    private lateinit var config: YamlConfiguration

    val straitRoadMaxSpeed = 1.4
    val diagonalRoadMaxSpeed = 0.9
    val ascendingRoadMaxSpeed = 0.5

    override fun onEnable() {
        server.pluginManager.registerEvents( this, this )
        logger.info( "Hello ccRails" )

        configFile = File( dataFolder, "storage.yml" )

        if (!configFile.exists()) {
            configFile.parentFile.mkdirs()
            saveResource( "storage.yml", false )
        }

        Timer().scheduleAtFixedRate( object : TimerTask() {
            override fun run() {
                config = YamlConfiguration.loadConfiguration( configFile )
                logger.info( "Config synchronized with config file" )
            }
        }, 0, 1000 * 60 * 30 )
    }
    
    fun getCrossroadInfo( location:Location ) {
        var crossroads = config.getList( "crossroads" ) as MutableList<Any>?

        if (crossroads == null) {
            crossroads = mutableListOf()
            config.set( "crossroads", crossroads )
            config.save( configFile )
        }

        val serializedCrossroad = crossroads.find {
            if (it is Map<*, *>) {
                it.get( "x" ) == location.x && it.get( "y" ) == location.y && it.get( "z" ) == location.z
            } else {
                false
            }
        } as Map<*,*>?

        val crossroad = if (serializedCrossroad == null) {
            val crossroad = Crossroad( location.x, location.y, location.z )
            crossroads.add( Crossroad.serialize( crossroad ) )
            config.set( "crossroads", crossroads )
            config.save( configFile )

            crossroad
        } else {
            Crossroad.deserialize( serializedCrossroad )
        }

//        logger.info( "location = ${location.x} ${location.y} ${location.z}" )
        logger.info( "crossroad = ${crossroad} ${}" )
//
//        for ((index, obj) in crossroads.withIndex()) {
//            val objSection = crossroads.createSection( index.toString() )
//            objSection.set("name", obj.name)
//            objSection.set("value", obj.value)
//        }
//
//        return crossroads
    }

    @EventHandler
    fun onVehicleMove(event: VehicleMoveEvent) {
        val vehicle = event.vehicle

        if (vehicle !is Minecart) return

        val rail = if (vehicle.location.block.type === Material.AIR) vehicle.location.block.getRelative( BlockFace.DOWN ) else vehicle.location.block
        val railData = getRailBlockData( rail ) ?: return
        val maxSpeed:Double

        if (rail.type == Material.DETECTOR_RAIL) {
            maxSpeed = Math.min( Math.min( straitRoadMaxSpeed, diagonalRoadMaxSpeed ), ascendingRoadMaxSpeed)

            getCrossroadInfo( rail.location )
//            logger.info( "${getCrossroadInfo( rail.location )}" )
        } else {
            maxSpeed = when (railData.shape) {
                Rail.Shape.NORTH_EAST, Rail.Shape.NORTH_WEST, Rail.Shape.SOUTH_EAST, Rail.Shape.SOUTH_WEST -> diagonalRoadMaxSpeed
                Rail.Shape.ASCENDING_NORTH, Rail.Shape.ASCENDING_SOUTH, Rail.Shape.ASCENDING_EAST, Rail.Shape.ASCENDING_WEST -> ascendingRoadMaxSpeed
                else -> {
                    val vehicleVel = vehicle.velocity
                    val nextBlock = if (vehicleVel.x.absoluteValue > vehicleVel.z.absoluteValue) {
                        rail.getRelative( vehicleVel.x.sign.toInt(), 0, 0 )
                    } else {
                        rail.getRelative( 0, 0, vehicleVel.z.sign.toInt() )
                    }

                    val nextRailData = getRailBlockData( nextBlock )

                    //                logger.info( "rail.xyz=${stringifylOC( rail.location )} nextBlock.xyz=${stringifylOC( nextBlock.location )} nextBlock.face=${nextRailData?.shape}")

                    if (nextRailData == null) straitRoadMaxSpeed
                    else {
                        when (nextRailData.shape) {
                            Rail.Shape.ASCENDING_NORTH, Rail.Shape.ASCENDING_SOUTH, Rail.Shape.ASCENDING_EAST, Rail.Shape.ASCENDING_WEST -> ascendingRoadMaxSpeed
                            else -> straitRoadMaxSpeed
                        }
                    }
                }
            }
        }

        vehicle.maxSpeed = maxSpeed
        vehicle.velocity = straighten( vehicle.velocity, vehicle.maxSpeed )

//        logger.info( "vehicle.velocity=${stringifyVec( vehicle.velocity )} vehicle.maxSpeed=${vehicle.maxSpeed}")

//        if (rail.type != Material.POWERED_RAIL && !isDiagonal) {
//            if (vehicle.persistentDataContainer.has( speedTagKey )) {
//                vehicle.persistentDataContainer.remove( speedTagKey )
//            }
//
//            if (vehicle.velocity.length() > 1.0) {
////                logger.info( "vehicle.velocity=${vehicle.velocity}, friction=${friction}" )
//                vehicle.velocity = vehicle.velocity.multiply( friction )
//            }
//
//            return
//        }
//
//        logger.info( "Speed!" )
//        if (!vehicle.persistentDataContainer.has( speedTagKey )) {
////            logger.info( " " )
////            logger.info( "vehicle.persistentDataContainer.has = ${vehicle.persistentDataContainer.has( speedTagKey )} CALCULATING" )
//
//            val railData = getRailBlockData( rail ) ?: return
//            val (velocityXSign, relativeY, velocityZSign) = when (railData.shape) {
//                Rail.Shape.NORTH_SOUTH      -> Triple( 0,                               0,                                      vehicle.velocity.z.sign.toInt() )
//                Rail.Shape.EAST_WEST        -> Triple( vehicle.velocity.x.sign.toInt(), 0,                                      0 )
//                Rail.Shape.ASCENDING_NORTH  -> Triple( 0,                               vehicle.velocity.z.sign.toInt() *  1,   vehicle.velocity.z.sign.toInt() )
//                Rail.Shape.ASCENDING_SOUTH  -> Triple( 0,                               vehicle.velocity.z.sign.toInt() * -1,   vehicle.velocity.z.sign.toInt() )
//                Rail.Shape.ASCENDING_EAST   -> Triple( vehicle.velocity.x.sign.toInt(), vehicle.velocity.x.sign.toInt() *  1,   0 )
//                Rail.Shape.ASCENDING_WEST   -> Triple( vehicle.velocity.x.sign.toInt(), vehicle.velocity.x.sign.toInt() * -1,   0 )
//                else -> return
//            }
//
//            var poweredRailsInRow = 1
//            var nextRail = rail
//
////            logger.info( "railShape=${(nextRail.blockData as Rail).shape}, railLoc=(${nextRail.location.x}, ${nextRail.location.y}, ${nextRail.location.z}), rail.type=${nextRail.type} velocityXSign=${velocityXSign} velocityZSign=${velocityZSign}" )
//
//            for (i in 0 until 10) {
//                val nextRailShape = (nextRail.blockData as Rail).shape
//
//                nextRail = when (nextRailShape) {
//                    Rail.Shape.NORTH_SOUTH      -> rail.getRelative( 0,                         0,                              velocityZSign * (i + 1) )
//                    Rail.Shape.EAST_WEST        -> rail.getRelative( velocityXSign * (i + 1),   0,                              0 )
//                    Rail.Shape.ASCENDING_NORTH  -> rail.getRelative( 0,                         relativeY + i * relativeY.sign, velocityXSign * (i + 1) )
//                    Rail.Shape.ASCENDING_SOUTH  -> rail.getRelative( 0,                         relativeY + i * relativeY.sign, velocityXSign * (i + 1) )
//                    Rail.Shape.ASCENDING_EAST   -> rail.getRelative( velocityXSign * (i + 1),   relativeY + i * relativeY.sign, 0 )
//                    Rail.Shape.ASCENDING_WEST   -> rail.getRelative( velocityXSign * (i + 1),   relativeY + i * relativeY.sign, 0 )
//                    else -> break
//                }
//
////                logger.info( "nextRailLoc=(${nextRail.location.x}, ${nextRail.location.y}, ${nextRail.location.z}), nextRail.type=${nextRail.type} relativePos=(${velocityXSign * i}, ${relativeY + i}, ${velocityZSign * i})" )
//                if (nextRail.type != Material.POWERED_RAIL) break
//
//                poweredRailsInRow++
//            }
//
////            logger.info( "poweredRailsInRow = ${poweredRailsInRow}" )
//            vehicle.persistentDataContainer.set( speedTagKey, PersistentDataType.INTEGER, poweredRailsInRow )
//        }
//
//        val speedMultiplier = vehicle.persistentDataContainer.get( speedTagKey, PersistentDataType.INTEGER ) ?: return
//
//        if (speedMultiplier < 3) return
//
////        logger.info( "Speed prev! ${speedMultiplier} ${vehicle.velocity}" )
////        vehicle.velocity = vehicle.velocity.multiply( 1.01 )
    }

    @EventHandler
    fun onMinecartCollision(event: VehicleEntityCollisionEvent) {
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
}
