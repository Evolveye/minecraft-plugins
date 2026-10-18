package cc.cactu.minecraft.ccBoats

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.entity.Boat
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.vehicle.VehicleUpdateEvent
import org.bukkit.plugin.java.JavaPlugin

class CcBoats : JavaPlugin(), Listener {
    override fun onEnable() {
        server.pluginManager.registerEvents( this, this )
        logger.info( "Hello ccBoats" )
    }

    @EventHandler
    fun onBoatUpdate( event:VehicleUpdateEvent ) {
        val boat = event.vehicle as? Boat ?: return
        val player = boat.passengers.firstOrNull() ?: return
        if (player !is Player) return

        val location = boat.location
        val blockUnder = location.clone().add( 0.0, -0.1, 0.0 ).block.type

        when (blockUnder) {
            Material.ICE -> {
                val addition = if (player.ticksLived % 3 == 0) 5 else 4
                player.freezeTicks = Math.min( player.freezeTicks + addition, 200 )

                if (player.freezeTicks >= 100 && player.ticksLived % 20 == 0) {
                    player.sendActionBar( Component.text( "Szybko zamarzasz na zwykłym lodzie!", NamedTextColor.DARK_BLUE ) )
                }
            }

            Material.PACKED_ICE -> {
                val addition = 3
                player.freezeTicks = Math.min( player.freezeTicks + addition, 200 )

                if (player.freezeTicks >= 100 && player.ticksLived % 20 == 0) {
                    player.sendActionBar( Component.text( "Zamarzasz na spakowanym lodzie!", NamedTextColor.DARK_BLUE ) )
                }
            }

            else -> {}
        }
    }
}
