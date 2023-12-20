package cc.cactu.minecraft.ccrails

import org.bukkit.Location
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Rail
import org.bukkit.util.Vector
import kotlin.math.absoluteValue
import kotlin.math.sign

fun getRailBlockData(rail:Block):Rail? = if (rail.blockData is Rail) rail.blockData as Rail else null

fun stringifyVec( vec:Vector ): String {
    var stringifiedVec = String.format( "%.3f", vec.x )
    stringifiedVec += ", " + String.format( "%.3f", vec.y )
    stringifiedVec += ", " + String.format( "%.3f", vec.z )

    return stringifiedVec
}

fun stringifylOC( loc:Location ): String {
    return stringifyVec( Vector( loc.x, loc.y, loc.z ) )
}

fun straighten(vec: Vector, speed: Double): Vector {
    val max = Math.max(
        Math.max(
            vec.x.absoluteValue,
            vec.y.absoluteValue
        ),
        vec.z.absoluteValue
    )
    if (max < speed || max == 0.0) return vec

    val nx = vec.x / max * speed
    val ny = vec.y / max * speed
    val nz = vec.z / max * speed

    return Vector( nx, ny, nz )
}

fun getDirection(delta: Vector): BlockFace {
    if (Math.abs(delta.x) > Math.abs(delta.z)) {
        return if (delta.x > 0) BlockFace.EAST else BlockFace.WEST
    } else {
        return if (delta.z > 0) BlockFace.SOUTH else BlockFace.NORTH
    }
}


fun getRelativeToRailByVelocity( rail:Block, velocity:Vector ): Block {
    val nextBlock = if (velocity.x.absoluteValue > velocity.z.absoluteValue) {
        rail.getRelative( velocity.x.sign.toInt(), 0, 0 )
    } else {
        rail.getRelative( 0, 0, velocity.z.sign.toInt() )
    }

    return nextBlock
}