package cc.cactu.minecraft.ccrails

import org.bukkit.Location
import org.bukkit.block.Block
import org.bukkit.block.data.Rail
import org.bukkit.util.Vector
import kotlin.math.sign

enum class Layout { NS, WE }
enum class Direction {
    NORTH, SOUTH, EAST, WEST;

    fun getLayout():Layout = if (this == NORTH || this == SOUTH) Layout.NS else Layout.WE

    fun getOpposite():Direction {
        return if (this == NORTH) SOUTH
            else if (this == EAST)  WEST
            else if (this == SOUTH) NORTH
            else  EAST
    }

    fun getAfterTurn( turn:Turn ):Direction {
        return if (this == NORTH) {
            if (turn == Turn.STRAIGHT) NORTH
            if (turn == Turn.LEFT) WEST
            if (turn == Turn.RIGHT) EAST
        } else if (this == EAST){
            if (turn == Turn.STRAIGHT) EAST
            if (turn == Turn.LEFT) NORTH
            if (turn == Turn.RIGHT) SOUTH
        } else if (this == SOUTH) {
            if (turn == Turn.STRAIGHT) SOUTH
            if (turn == Turn.LEFT) EAST
            if (turn == Turn.RIGHT) WEST
        } else {
            if (turn == Turn.STRAIGHT) WEST
            if (turn == Turn.LEFT) SOUTH
            if (turn == Turn.RIGHT) NORTH
        }
    }
}
enum class Turn {
    LEFT, STRAIGHT, RIGHT;

    companion object {
        fun fromDirectiona( a:Direction, b:Direction ):Turn? {
            return if (a == Direction.NORTH) {
                if (b == Direction.NORTH) STRAIGHT
                else if (b == Direction.EAST) RIGHT
                else if (b == Direction.WEST) LEFT
                else null
            } else if (a == Direction.SOUTH) {
                if (b == Direction.SOUTH) STRAIGHT
                else if (b == Direction.EAST) LEFT
                else if (b == Direction.WEST) RIGHT
                else null
            } else if (a == Direction.EAST) {
                if (b == Direction.EAST) STRAIGHT
                else if (b == Direction.NORTH) LEFT
                else if (b == Direction.SOUTH) RIGHT
                else null
            } else { // if (a == Direction.WEST)
                if (b == Direction.WEST) STRAIGHT
                else if (b == Direction.NORTH) RIGHT
                else if (b == Direction.SOUTH) LEFT
                else null
            }
        }
    }
}

fun getRailBlockData(rail:Block):Rail? = if (rail.blockData is Rail) rail.blockData as Rail else null

fun isRailCrossroad( turnBlock:Block ):Boolean =if (getRailBlockData( turnBlock ) == null) false else {
    var waysCount = 4

    if (getRailBlockData( turnBlock.getRelative(  1, 0,  0 ) ) == null) waysCount -= 1
    if (getRailBlockData( turnBlock.getRelative( -1, 0,  0 ) ) == null) waysCount -= 1
    if (getRailBlockData( turnBlock.getRelative(  0, 0,  1 ) ) == null) waysCount -= 1
    if (getRailBlockData( turnBlock.getRelative(  0, 0, -1 ) ) == null) waysCount -= 1

    waysCount >= 3
}

fun straighten(vec: Vector, speed: Double):Vector {
    val max = Math.max(
        Math.max(
            Math.abs( vec.x ),
            Math.abs( vec.y ),
        ),
        Math.abs( vec.z )
    )
    if (max < speed || max == 0.0) return vec

    val nx = vec.x / max * speed
    val ny = vec.y / max * speed
    val nz = vec.z / max * speed

    return Vector( nx, ny, nz )
}

fun getRelativeToRailByVelocity( location:Location, velocity:Vector, distance:Double = 1.0 ):Block {
    return getRelativeToRailByVelocity( location.block, velocity, distance )
}
fun getRelativeToRailByVelocity( block:Block, velocity:Vector, distance:Int = 1 ):Block {
    return getRelativeToRailByVelocity( block, velocity, distance.toDouble() )
}
fun getRelativeToRailByVelocity( block:Block, velocity:Vector, distance:Double ):Block {
    val nextBlock = if (Math.abs( velocity.x ) > Math.abs( velocity.z )) {
        block.getRelative( (velocity.x.sign * distance).toInt(), 0, 0 )
    } else {
        block.getRelative( 0, 0, (velocity.z.sign * distance).toInt() )
    }

    return nextBlock
}
fun getRelativeToRailByDirection( block:Block, direction:Direction, distance:Int = 1 ):Block {
    return if (direction == Direction.NORTH) {
        block.getRelative( 0, 0, -distance )
    } else if (direction == Direction.EAST) {
        block.getRelative(  distance, 0, 0 )
    } else if (direction == Direction.SOUTH) {
        block.getRelative( 0, 0,  distance )
    } else {
        block.getRelative( -distance, 0, 0 )
    }
}

fun getRailingDiretion( velocity:Vector ):Direction {
    return if (Math.abs( velocity.x ) > Math.abs( velocity.z )) {
        if (velocity.x > 0) Direction.EAST
        else Direction.WEST
    } else {
        if (velocity.z > 0) Direction.SOUTH
        else Direction.NORTH
    }
}
