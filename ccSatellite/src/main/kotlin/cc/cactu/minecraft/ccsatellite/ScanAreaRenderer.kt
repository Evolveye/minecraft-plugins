package cc.cactu.minecraft.ccsatellite

import org.bukkit.ChunkSnapshot
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.block.data.Ageable
import org.bukkit.block.data.BlockData
import java.awt.image.BufferedImage

/** Color a crop moves towards while growing, and how much of it a fully grown crop takes. */
private data class RipeColor( val color:Int, val amount:Double )

/** Visible top of a single block column: the ground block, and the water surface above it when flooded. */
private data class Column( val surfaceY:Int, val groundY:Int, val color:Int ) {
    val waterDepth get() = surfaceY - groundY
}

private const val OPAQUE = 0xFF000000.toInt()

/** Height marking a grid cell where nothing was scanned. */
private const val NO_COLUMN = Int.MIN_VALUE

/** Water color; deeper and more saturated than the game's default 0x3F76E4. */
private const val WATER_COLOR = 0x2B5FE0

/** Brightness change per block of slope, measured between both neighbors on each axis. */
private const val SLOPE_STRENGTH = 0.05
private const val SLOPE_DARKEST = -0.2
private const val SLOPE_BRIGHTEST = 0.15

/** Slope lighting of the ground under water is weakened by this factor. */
private const val UNDERWATER_SLOPE_STRENGTH = 0.5

/** Water opacity over the ground: starts at the shallowest, grows per block of depth, up to the deepest. */
private const val WATER_OPACITY_SHALLOWEST = 0.75
private const val WATER_OPACITY_PER_BLOCK = 0.04
private const val WATER_OPACITY_DEEPEST = 0.95

/** Brightness of land touching water, outlining shores and riverbanks. */
private const val SHORE_BRIGHTNESS = 0.85

/** Radius of the area whose average height a column is compared with, to light bumps (tree crowns) and darken dips. */
private const val OCCLUSION_RADIUS = 3

/** Brightness change per block of height above or below that average. Even slopes average out and stay unaffected. */
private const val OCCLUSION_STRENGTH = 0.06
private const val OCCLUSION_DARKEST = -0.3
private const val OCCLUSION_BRIGHTEST = 0.15

/** How many diagonal steps towards the sun are checked for terrain casting a shadow. */
private const val SHADOW_REACH = 12

/** Height (in blocks) a blocker has to rise per diagonal step to shade a column. Lower values give longer shadows. */
private const val SHADOW_SLOPE = 0.8
private const val SHADOW_BRIGHTNESS = 0.88

/** Brightness change per block above or below sea level, so large flat areas still show their altitude. */
private const val ALTITUDE_STRENGTH = 0.0015
private const val ALTITUDE_LIMIT = 0.1

/** Saturation kept from the vanilla map colors; 1 keeps them as they are, 0 turns them gray. */
private const val COLOR_SATURATION = 1.0

/** Water color toned down like the block colors. */
private val SOFT_WATER_COLOR = desaturateColor( WATER_COLOR, COLOR_SATURATION )

/**
 * How far above the snapshot's heightmap the scan starts. The heightmap skips blocks without collision,
 * so crops and grass (2 blocks for tall grass) are above it.
 */
private const val PLANT_SCAN_MARGIN = 2

/** Green that grass tints the ground under it with. */
private const val GRASS_COLOR = 0x3E7A1A

/** How much each kind of grass tints the ground under it; shown as meadow texture instead of noisy dark dots. */
private val GRASS_COVER_AMOUNTS = mapOf(
    Material.SHORT_GRASS to 0.4,
    Material.FERN to 0.4,
    Material.TALL_GRASS to 0.65,
    Material.LARGE_FERN to 0.65,
)

/** Base color of crops; vanilla map colors draw them in the same dark plant green as tree leaves. */
private const val CROP_COLOR = 0xADC743

/** Ripeness colors of crops, mixed into [CROP_COLOR] while they grow. */
private val RIPE_CROP_COLORS = mapOf(
    Material.WHEAT to RipeColor( 0xE8CF4A, 1.0 ),
    Material.CARROTS to RipeColor( 0xE3901D, 0.4 ),
    Material.BEETROOTS to RipeColor( 0xA8323A, 0.4 ),
)

/** Blocks treated as part of the water body, so the map shows the ground under them. */
private val WATER_MATERIALS = setOf( Material.WATER, Material.BUBBLE_COLUMN, Material.KELP, Material.KELP_PLANT, Material.SEAGRASS, Material.TALL_SEAGRASS )

/**
 * Draws a top-down image of `area`, one pixel per block, in the style of MCA Selector / uNmINeD maps,
 * lit by a sun in the north-west. `snapshots` are keyed by chunk position; missing chunks (never generated)
 * and everything outside the area's circles stay transparent. Safe to call off the main thread.
 */
fun renderScanArea( area:ScanArea, snapshots:Map<Pair<Int, Int>, ChunkSnapshot>, minHeight:Int, maxHeight:Int, seaLevel:Int ): BufferedImage {
    val grid = ColumnGrid( area.widthInBlocks, area.heightInBlocks )
    val image = BufferedImage( grid.width, grid.height, BufferedImage.TYPE_INT_ARGB )

    for ((position, snapshot) in snapshots) {
        val chunkWestX = position.first * CHUNK_SIZE
        val chunkNorthZ = position.second * CHUNK_SIZE

        for (z in 0 until CHUNK_SIZE) for (x in 0 until CHUNK_SIZE) {
            // Columns outside every circle stay empty: transparent, and ignored by the shading of their neighbors.
            if (!area.containsBlock( chunkWestX + x, chunkNorthZ + z )) continue

            val column = scanColumn( snapshot, x, z, minHeight, maxHeight ) ?: continue

            grid.write( chunkWestX + x - area.westX, chunkNorthZ + z - area.northZ, column )
        }
    }

    for (pixelZ in 0 until grid.height) for (pixelX in 0 until grid.width) {
        val column = grid.readColumn( pixelX, pixelZ ) ?: continue

        image.setRGB( pixelX, pixelZ, shadeColumn( grid, pixelX, pixelZ, column, seaLevel ) )
    }

    return image
}

/** Walks down the column from its highest block, through air, see-through blocks and water, to the first visible block. */
private fun scanColumn( snapshot:ChunkSnapshot, x:Int, z:Int, minHeight:Int, maxHeight:Int ): Column? {
    val heightmapY = snapshot.getHighestBlockYAt( x, z )
    val topY = minOf( heightmapY + PLANT_SCAN_MARGIN, maxHeight - 1 )
    var waterSurfaceY:Int? = null
    var grassCover = 0.0

    for (y in topY downTo minHeight) {
        val block = snapshot.getBlockData( x, y, z )

        // Grass only tints the ground under it, keeping its height for the shading.
        val grassAmount = GRASS_COVER_AMOUNTS[ block.material ]

        if (grassAmount != null) {
            grassCover = maxOf( grassCover, grassAmount )
            continue
        }

        // Above the heightmap only crops count; e.g. flowers would make meadows noisy.
        if (y > heightmapY && !Tag.CROPS.isTagged( block.material )) continue

        if (block.material in WATER_MATERIALS) {
            if (waterSurfaceY == null) waterSurfaceY = y
            continue
        }

        // Map color 0 means "not drawn on maps" (air, glass, barriers, ...).
        val color = pickBlockColor( block )
        if (color == 0) continue

        val coveredColor = blendColors( color, GRASS_COLOR, grassCover )

        return Column( waterSurfaceY ?: y, y, desaturateColor( coveredColor, COLOR_SATURATION ) )
    }

    return null
}

/** Map color of a block; crops get their own color, tinted by how ripe they are. */
private fun pickBlockColor( block:BlockData ): Int {
    if (!Tag.CROPS.isTagged( block.material )) return block.mapColor.asRGB()

    val ripeColor = RIPE_CROP_COLORS[ block.material ] ?: return CROP_COLOR
    if (block !is Ageable) return CROP_COLOR

    val ripeness = block.age.toDouble() / block.maximumAge

    return blendColors( CROP_COLOR, ripeColor.color, ripeness * ripeColor.amount )
}

/** Final pixel color: ground lit by slope, shadows and altitude, or ground seen through water. */
private fun shadeColumn( grid:ColumnGrid, x:Int, z:Int, column:Column, seaLevel:Int ): Int {
    val slopeLight = measureSlopeLight( grid, x, z, column )

    if (column.waterDepth > 0) {
        val ground = scaleColor( column.color, 1 + slopeLight * UNDERWATER_SLOPE_STRENGTH )
        val waterOpacity = (WATER_OPACITY_SHALLOWEST + (column.waterDepth - 1) * WATER_OPACITY_PER_BLOCK).coerceAtMost( WATER_OPACITY_DEEPEST )

        return blendColors( ground, SOFT_WATER_COLOR, waterOpacity ) or OPAQUE
    }

    val altitudeLight = ((column.surfaceY - seaLevel) * ALTITUDE_STRENGTH).coerceIn( -ALTITUDE_LIMIT, ALTITUDE_LIMIT )
    val occlusionLight = measureOcclusionLight( grid, x, z, column )
    val shadow = if (isInShadow( grid, x, z, column )) SHADOW_BRIGHTNESS else 1.0
    val shore = if (isTouchingWater( grid, x, z )) SHORE_BRIGHTNESS else 1.0

    return scaleColor( column.color, (1 + slopeLight + altitudeLight + occlusionLight) * shadow * shore ) or OPAQUE
}

/**
 * Brightness offset from the column's height compared to the average around it: bumps like tree crowns get brighter,
 * dips like gaps between trees get darker. Missing neighbors are left out of the average.
 */
private fun measureOcclusionLight( grid:ColumnGrid, x:Int, z:Int, column:Column ): Double {
    var heightSum = 0
    var neighborCount = 0

    for (neighborZ in z - OCCLUSION_RADIUS..z + OCCLUSION_RADIUS) for (neighborX in x - OCCLUSION_RADIUS..x + OCCLUSION_RADIUS) {
        val neighborY = grid.readSurfaceY( neighborX, neighborZ )
        if (neighborY == NO_COLUMN) continue

        heightSum += neighborY
        neighborCount++
    }

    val averageHeight = heightSum.toDouble() / neighborCount

    return ((column.surfaceY - averageHeight) * OCCLUSION_STRENGTH).coerceIn( OCCLUSION_DARKEST, OCCLUSION_BRIGHTEST )
}

/** Whether any of the four direct neighbors is flooded. */
private fun isTouchingWater( grid:ColumnGrid, x:Int, z:Int ) = listOf( x + 1 to z, x - 1 to z, x to z + 1, x to z - 1 )
    .any { (neighborX, neighborZ) -> grid.isFlooded( neighborX, neighborZ ) }

/**
 * Brightness offset from the ground slope. Ground rising towards the south-east faces the north-west sun and gets
 * brighter, ground rising towards it gets darker. Missing neighbors count as flat.
 */
private fun measureSlopeLight( grid:ColumnGrid, x:Int, z:Int, column:Column ): Double {
    val readGroundY = { neighborX:Int, neighborZ:Int ->
        val neighborY = grid.readGroundY( neighborX, neighborZ )
        if (neighborY == NO_COLUMN) column.groundY else neighborY
    }

    val slopeX = readGroundY( x + 1, z ) - readGroundY( x - 1, z )
    val slopeZ = readGroundY( x, z + 1 ) - readGroundY( x, z - 1 )

    return ((slopeX + slopeZ) * SLOPE_STRENGTH).coerceIn( SLOPE_DARKEST, SLOPE_BRIGHTEST )
}

/** Whether higher terrain towards the north-west sun blocks the light falling on the column. */
private fun isInShadow( grid:ColumnGrid, x:Int, z:Int, column:Column ): Boolean {
    for (step in 1..SHADOW_REACH) {
        val blockerY = grid.readSurfaceY( x - step, z - step )
        if (blockerY == NO_COLUMN) return false

        if (blockerY - column.surfaceY >= step * SHADOW_SLOPE) return true
    }

    return false
}

/** Mixes `overlay` into `base`; `amount` 0 keeps `base`, 1 gives `overlay`. */
private fun blendColors( base:Int, overlay:Int, amount:Double ): Int {
    val mixChannel = { shift:Int -> ((base shr shift and 0xFF) * (1 - amount) + (overlay shr shift and 0xFF) * amount).toInt() }

    return (mixChannel( 16 ) shl 16) or (mixChannel( 8 ) shl 8) or mixChannel( 0 )
}

/** Moves an RGB color towards its own gray; `saturation` 1 keeps it, 0 gives the gray. */
private fun desaturateColor( color:Int, saturation:Double ): Int {
    val gray = (0.299 * (color shr 16 and 0xFF) + 0.587 * (color shr 8 and 0xFF) + 0.114 * (color and 0xFF)).toInt()
    val grayColor = (gray shl 16) or (gray shl 8) or gray

    return blendColors( color, grayColor, 1 - saturation )
}

/** Multiplies every channel of an RGB color by `factor`. */
private fun scaleColor( color:Int, factor:Double ): Int {
    val scaleChannel = { shift:Int -> ((color shr shift and 0xFF) * factor).toInt().coerceIn( 0, 255 ) }

    return (scaleChannel( 16 ) shl 16) or (scaleChannel( 8 ) shl 8) or scaleChannel( 0 )
}

/**
 * Visible tops of all columns of the rendered area, stored in flat arrays to keep big scans small in memory.
 * Cells where nothing was scanned, and reads outside of the grid, give [NO_COLUMN] heights.
 */
private class ColumnGrid( val width:Int, val height:Int ) {
    private val surfaceYs = IntArray( width * height ) { NO_COLUMN }
    private val groundYs = IntArray( width * height ) { NO_COLUMN }
    private val colors = IntArray( width * height )

    fun readSurfaceY( x:Int, z:Int ) = if (isInside( x, z )) surfaceYs[ z * width + x ] else NO_COLUMN

    fun readGroundY( x:Int, z:Int ) = if (isInside( x, z )) groundYs[ z * width + x ] else NO_COLUMN

    fun readColumn( x:Int, z:Int ): Column? {
        val surfaceY = readSurfaceY( x, z )
        if (surfaceY == NO_COLUMN) return null

        return Column( surfaceY, groundYs[ z * width + x ], colors[ z * width + x ] )
    }

    fun isFlooded( x:Int, z:Int ): Boolean {
        val surfaceY = readSurfaceY( x, z )
        if (surfaceY == NO_COLUMN) return false

        return surfaceY > groundYs[ z * width + x ]
    }

    fun write( x:Int, z:Int, column:Column ) {
        surfaceYs[ z * width + x ] = column.surfaceY
        groundYs[ z * width + x ] = column.groundY
        colors[ z * width + x ] = column.color
    }

    private fun isInside( x:Int, z:Int ) = x in 0 until width && z in 0 until height
}
