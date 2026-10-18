package cc.cactu.minecraft.ccCreative

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.block.Block
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockFromToEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.util.Vector
import kotlin.math.abs

class TerrainProtection(
    private val creativeWorldName:String,
    private val protectionRadius:Int,
) : Listener {
    @EventHandler
    fun onLiquidFlow( event:BlockFromToEvent ) {
        if (event.toBlock.world.name == creativeWorldName) {
            if (isInsideProtectedZone( event.toBlock.location )) {
                event.isCancelled = true
            }
        }
    }

    @EventHandler
    fun onBlockBreak( event: BlockBreakEvent ) {
        if (checkIsInSpawnProtection( event.player, event.block )) event.isCancelled = true
    }

    @EventHandler
    fun onBlockPlace( event:BlockPlaceEvent ) {
        if (checkIsInSpawnProtection( event.player, event.block )) event.isCancelled = true
    }

    @EventHandler
    fun onInteract( event:PlayerInteractEvent ) {
        val block = event.clickedBlock ?: return
        if (checkIsInSpawnProtection( event.player, block )) event.isCancelled = true
    }

    @EventHandler
    fun onEntityExplode( event:EntityExplodeEvent ) {
        if (checkIsInSpawnProtection( event.entity, null )) event.isCancelled = true
    }

    @EventHandler
    fun onEntityChangeBlock( event:EntityChangeBlockEvent ) {
        if (checkIsInSpawnProtection( event.entity, event.block )) event.isCancelled = true
    }

    @EventHandler( priority = EventPriority.HIGHEST )
    fun onCommandEdit( event:PlayerCommandPreprocessEvent ) {
        val player = event.player
        if (player.isOp || player.world.name != creativeWorldName) return

        val message = event.message.trim()
        val args = message.split( Regex( "\\s+" ) )
        if (args.isEmpty()) return

        val cmd = args[ 0 ].lowercase().removePrefix( "/" ).removePrefix( "minecraft:" )
        val editCommands = listOf("fill", "setblock", "clone", "fillbiome")

        if (cmd !in editCommands) return

        try {
            when (cmd) {
                "setblock" -> {
                    if (args.size >= 4) {
                        val loc = parseLoc( player, args[ 1 ], args[ 2 ], args[ 3 ] )
                        if (isInsideProtectedZone( loc )) {
                            event.isCancelled = true
                            return
                        }
                    }
                }
                "fill", "fillbiome" -> {
                    if (args.size >= 7) {
                        val l1 = parseLoc( player, args[ 1 ], args[ 2 ], args[ 3 ] )
                        val l2 = parseLoc( player, args[ 4 ], args[ 5 ], args[ 6 ] )
                        if (isAreaViolated( l1, l2 )) {
                            event.isCancelled = true
                            return
                        }
                    }
                }
                "clone" -> {
                    if (args.size >= 10) {
                        val src1 = parseLoc( player, args[ 1 ], args[ 2 ], args[ 3 ] )
                        val src2 = parseLoc( player, args[ 4 ], args[ 5 ], args[ 6 ] )
                        val dest = parseLoc( player, args[ 7 ], args[ 8 ], args[ 9 ] )

                        val sizeX = abs( src1.x - src2.x )
                        val sizeZ = abs( src1.z - src2.z )
                        val dest2 = dest.clone().add( sizeX, 0.0, sizeZ )

                        if (isAreaViolated( src1, src2 ) || isAreaViolated( dest, dest2 )) {
                            event.isCancelled = true
                            return
                        }
                    }
                }
            }
        } catch (e:Exception) {
            player.sendMessage("§cSystemu ochrony nie potrafi przetworzyć tej komendy. Zablokowaliśmy ją ze względów bezpieczeństwa.")
            event.isCancelled = true
        }
    }

    private fun getProtectedCenter():Location? = Bukkit.getWorld( creativeWorldName )?.spawnLocation

    fun checkIsInSpawnProtection( player:Entity, block:Block? ):Boolean {
        if (player.world.name != creativeWorldName || player.isOp) return false
        return isInsideProtectedZone( block?.location ?: player.location )
    }

    private fun isInsideProtectedZone( loc:Location ):Boolean {
        val spawnLoc = getProtectedCenter() ?: return false

        return abs( loc.x - spawnLoc.x ) <= protectionRadius &&
                abs( loc.z - spawnLoc.z ) <= protectionRadius
    }

    private fun isAreaViolated(loc1: Location, loc2: Location): Boolean {
        val spawnLoc = getProtectedCenter() ?: return false

        val minX = loc1.x.coerceAtMost( loc2.x )
        val maxX = loc1.x.coerceAtLeast( loc2.x )
        val minZ = loc1.z.coerceAtMost( loc2.z )
        val maxZ = loc1.z.coerceAtLeast( loc2.z )

        val areaOverlapsX = minX <= (spawnLoc.x + protectionRadius) && maxX >= (spawnLoc.x - protectionRadius)
        val areaOverlapsZ = minZ <= (spawnLoc.z + protectionRadius) && maxZ >= (spawnLoc.z - protectionRadius)

        return areaOverlapsX && areaOverlapsZ
    }

    private fun parseLoc( player:Player, xStr:String, yStr:String, zStr:String):Location {

        if (xStr.startsWith("^") || yStr.startsWith("^") || zStr.startsWith("^")) {
            val left = xStr.substring(1).toDoubleOrNull() ?: 0.0
            val up = yStr.substring(1).toDoubleOrNull() ?: 0.0
            val forward = zStr.substring(1).toDoubleOrNull() ?: 0.0
            val origin = player.location.clone()

            // Pobieramy kierunek patrzenia (Forward)
            val lookDir = origin.direction.normalize()

            // Obliczamy wektor "W górę" (Lokalne UP)
            // W Minecraft, jeśli patrzysz prosto w górę/dół, wektor pomocniczy musi się zmienić,
            // ale dla standardowego budowania używamy Cross Product z wektorem (0,1,0)
            val tempUp = Vector(0, 1, 0)
            val localRight = lookDir.clone().crossProduct(tempUp).normalize()

            // Jeśli gracz patrzy pionowo w górę, localRight będzie zerowy.
            // Wtedy musimy przyjąć alternatywny wektor pomocniczy.
            if (localRight.lengthSquared() < 0.001) {
                localRight.copy(Vector(1, 0, 0).rotateAroundY(Math.toRadians(-origin.yaw.toDouble())))
            }

            val localUp = localRight.clone().crossProduct(lookDir).normalize()

            // Finalna pozycja: Start + (Right * -left) + (Up * up) + (Forward * forward)
            // Uwaga: Pierwszy parametr ^ to "lewo", więc odejmujemy wektor "Right"
            return origin.add(localRight.multiply(-left))
                .add(localUp.multiply(up))
                .add(lookDir.multiply(forward))
        }

        return Location(
            player.world,
            parseCoord( xStr, player.location.x ),
            parseCoord( yStr, player.location.y ),
            parseCoord( zStr, player.location.z )
        )
    }

    private fun parseCoord( valStr:String, relativeTo:Double ):Double {
        return if (valStr.startsWith( "~" )) {
            val offset = if (valStr.length > 1) valStr.substring( 1 ).toDoubleOrNull() ?: 0.0 else 0.0
            relativeTo + offset
        } else {
            valStr.toDoubleOrNull() ?: relativeTo
        }
    }
}