package cc.cactu.minecraft.ccsatellite

import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Directional
import java.time.Instant
import java.util.UUID

/**
 * Satellite station built by a player, positioned at its amethyst block:
 *
 *    R    lightning rod standing vertically (up or upside down), with open sky above
 *    L    lodestone
 *   CAC   amethyst block surrounded by complete square rings of copper blocks
 */
data class SatelliteStation( val worldName:String, val x:Int, val y:Int, val z:Int, val ownerId:UUID, val registeredAt:Instant ) {
    /** Identity of the station's place; one place holds at most one station. */
    val key get() = "$worldName/$x/$y/$z"
}

/** What keeps a station from working, with a hint for the player. */
enum class StationProblem( val hint:String ) {
    NO_LODESTONE( "Place a lodestone on the amethyst block" ),
    NO_AMETHYST( "Place the lodestone on an amethyst block" ),
    NO_ROD( "Place a lightning rod on top of the lodestone" ),
    ROD_SIDEWAYS( "The lightning rod has to stand vertically, not sideways" ),
    SKY_BLOCKED( "The lightning rod needs open sky, remove the blocks above it" ),
    NO_RING( "Surround the amethyst block with a ring of copper blocks" ),
}

/** Result of checking a station's structure: its complete copper rings and the first problem found, if any. */
data class StationCheck( val rings:Int, val problem:StationProblem? ) {
    val isWorking get() = problem == null
}

/** Name endings of copper blocks accepted in the rings: plain, cut, chiseled, grate and bulb. */
private val COPPER_BLOCK_SUFFIXES = listOf( "COPPER", "COPPER_BLOCK", "COPPER_GRATE", "COPPER_BULB" )

/** Every accepted copper block, in any oxidation stage, waxed or not. */
private val COPPER_BLOCKS = Material.entries
    .filter { !it.isLegacy && it.isBlock && !it.name.startsWith( "RAW_" ) && COPPER_BLOCK_SUFFIXES.any { suffix -> it.name.endsWith( suffix ) } }
    .toSet()

/** Directions a station's lightning rod may point in; upside down works too. */
private val VERTICAL_FACES = setOf( BlockFace.UP, BlockFace.DOWN )

/** Lightning rods in any oxidation stage, waxed or not. */
private val LIGHTNING_RODS = Material.entries.filter { !it.isLegacy && it.name.endsWith( "LIGHTNING_ROD" ) }.toSet()

/**
 * Whether the lodestone stands on an amethyst block and has a lightning rod (pointing anywhere) on top: the shape of
 * a station, worth a full [checkStation]. Cheap, so regular lodestones are skipped without further work.
 */
fun isStationLodestone( lodestone:Block ) =
    lodestone.getRelative( BlockFace.DOWN ).type == Material.AMETHYST_BLOCK && lodestone.getRelative( BlockFace.UP ).type in LIGHTNING_RODS

/**
 * Checks the station whose amethyst block is at `x`, `y`, `z`: counts its complete copper rings (up to `maxRings`)
 * and finds the first problem keeping it from working. Reads blocks from the world, so it runs on the main thread.
 */
fun checkStation( world:World, x:Int, y:Int, z:Int, maxRings:Int ): StationCheck {
    val hasAmethyst = world.getType( x, y, z ) == Material.AMETHYST_BLOCK
    val rings = if (hasAmethyst) countCopperRings( world, x, y, z, maxRings ) else 0

    return StationCheck( rings, findStationProblem( world, x, y, z, rings ) )
}

private fun findStationProblem( world:World, x:Int, y:Int, z:Int, rings:Int ): StationProblem? {
    if (world.getType( x, y + 1, z ) != Material.LODESTONE) return StationProblem.NO_LODESTONE
    if (world.getType( x, y, z ) != Material.AMETHYST_BLOCK) return StationProblem.NO_AMETHYST

    val rod = world.getBlockData( x, y + 2, z )
    if (rod.material !in LIGHTNING_RODS) return StationProblem.NO_ROD
    if ((rod as? Directional)?.facing !in VERTICAL_FACES) return StationProblem.ROD_SIDEWAYS

    // Glass, leaves, slabs and other see-through blocks don't block the sky, full opaque blocks do.
    if ((y + 3 until world.maxHeight).any { world.getType( x, it, z ).isOccluding }) return StationProblem.SKY_BLOCKED

    if (rings == 0) return StationProblem.NO_RING

    return null
}

/** Number of complete copper rings around the amethyst block, counted outwards until the first incomplete one. */
private fun countCopperRings( world:World, x:Int, y:Int, z:Int, maxRings:Int ): Int {
    for (ring in 1..maxRings) if (!isCopperRing( world, x, y, z, ring )) return ring - 1

    return maxRings
}

/** Whether every block of the square ring `ring` blocks away from the amethyst block is a copper block. */
private fun isCopperRing( world:World, x:Int, y:Int, z:Int, ring:Int ): Boolean {
    for (offset in -ring..ring) {
        val edgeOffsets = listOf( offset to -ring, offset to ring, -ring to offset, ring to offset )

        if (edgeOffsets.any { (offsetX, offsetZ) -> world.getType( x + offsetX, y, z + offsetZ ) !in COPPER_BLOCKS }) return false
    }

    return true
}
