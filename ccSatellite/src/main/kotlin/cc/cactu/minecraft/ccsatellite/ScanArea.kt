package cc.cactu.minecraft.ccsatellite

import org.bukkit.World
import kotlin.math.floor

/** Circle of terrain seen by one satellite station, in block coordinates. */
data class ScanCircle( val centerX:Double, val centerZ:Double, val radius:Double ) {
    /** Whether the center of the block at `x`, `z` lies inside. */
    fun containsBlock( x:Int, z:Int ): Boolean {
        val offsetX = x + 0.5 - centerX
        val offsetZ = z + 0.5 - centerZ

        return offsetX * offsetX + offsetZ * offsetZ <= radius * radius
    }

    /** Whether any part of the chunk at `chunkX`, `chunkZ` lies inside. */
    fun touchesChunk( chunkX:Int, chunkZ:Int ): Boolean {
        val chunkWestX = chunkX * CHUNK_SIZE.toDouble()
        val chunkNorthZ = chunkZ * CHUNK_SIZE.toDouble()

        // Offset to the chunk's point closest to the center.
        val offsetX = centerX.coerceIn( chunkWestX, chunkWestX + CHUNK_SIZE ) - centerX
        val offsetZ = centerZ.coerceIn( chunkNorthZ, chunkNorthZ + CHUNK_SIZE ) - centerZ

        return offsetX * offsetX + offsetZ * offsetZ <= radius * radius
    }
}

const val CHUNK_SIZE = 16

/** Terrain scanned in one world: the circles of all its stations, drawn together on one image covering the chunks they touch. */
class ScanArea( val world:World, val circles:List<ScanCircle> ) {
    val minChunkX = circles.minOf { floorToChunk( it.centerX - it.radius ) }
    val minChunkZ = circles.minOf { floorToChunk( it.centerZ - it.radius ) }
    val maxChunkX = circles.maxOf { floorToChunk( it.centerX + it.radius ) }
    val maxChunkZ = circles.maxOf { floorToChunk( it.centerZ + it.radius ) }

    /** Block coordinates of the image's north-west corner. */
    val westX get() = minChunkX * CHUNK_SIZE
    val northZ get() = minChunkZ * CHUNK_SIZE

    val widthInBlocks get() = (maxChunkX - minChunkX + 1) * CHUNK_SIZE
    val heightInBlocks get() = (maxChunkZ - minChunkZ + 1) * CHUNK_SIZE

    fun containsBlock( x:Int, z:Int ) = circles.any { it.containsBlock( x, z ) }

    /** Positions of the chunks touched by any circle, the only ones that need loading. */
    fun listChunkPositions() = (minChunkZ..maxChunkZ).flatMap { chunkZ ->
        (minChunkX..maxChunkX).filter { chunkX -> circles.any { it.touchesChunk( chunkX, chunkZ ) } }.map { chunkX -> chunkX to chunkZ }
    }
}

private fun floorToChunk( blockCoordinate:Double ) = floor( blockCoordinate / CHUNK_SIZE ).toInt()
