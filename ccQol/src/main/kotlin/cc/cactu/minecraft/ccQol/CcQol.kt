package cc.cactu.minecraft.ccqol

import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin

class CcQol : JavaPlugin(), Listener {
    private var tombstones: Tombstones? = null
    private var playerHeads: PlayerHeads? = null
    private var mannequins: Mannequins? = null

    override fun onEnable() {
        saveDefaultConfig()

        val tombstones = Tombstones( this )
        this.tombstones = tombstones

        val headCatalog = HeadCatalog( this )
        headCatalog.load()

        val playerHeads = PlayerHeads( this, headCatalog )
        playerHeads.registerRecipe()
        this.playerHeads = playerHeads

        val mannequins = Mannequins( this, playerHeads )
        mannequins.registerRecipe()
        this.mannequins = mannequins

        server.pluginManager.registerEvents( this, this )
        server.pluginManager.registerEvents( tombstones, this )
        server.pluginManager.registerEvents( playerHeads, this )
        server.pluginManager.registerEvents( mannequins, this )

        logger.info( "Hello ccQol" )
    }

    override fun onDisable() {
        tombstones?.shutdown()
        playerHeads?.unregisterRecipe()
        mannequins?.unregisterRecipe()
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onBannerEquip( event:InventoryClickEvent ) {
        if (event.click != ClickType.LEFT && event.click != ClickType.RIGHT) return
        if (event.slotType != InventoryType.SlotType.ARMOR || event.rawSlot != 5) return

        val player = event.whoClicked as? Player ?: return
        val bannerInCursor = event.cursor
        if (!bannerInCursor.type.name.endsWith( "_BANNER" ) ) return

        event.isCancelled = true

        val oldHelmet = swapHelmetWithBanner( player, bannerInCursor )
        event.view.setCursor( oldHelmet )
    }

    @EventHandler
    fun onRightClickEquip( event:PlayerInteractEvent ) {
        if (event.action != Action.RIGHT_CLICK_AIR) return
        if (event.hand != EquipmentSlot.HAND) return

        val player = event.player
        val itemInHand = event.item ?: return
        if (!itemInHand.type.name.endsWith( "_BANNER" )) return

        event.isCancelled = true

        val oldHelmet = swapHelmetWithBanner( player, itemInHand.clone() )
        player.inventory.setItemInMainHand( oldHelmet )
    }

    private fun swapHelmetWithBanner( player:Player, bannerStack:ItemStack ): ItemStack {
        val inventory = player.inventory
        val currentHelmet = inventory.helmet

        inventory.setHelmet( bannerStack )

        player.updateInventory()

        return currentHelmet
    }
}
