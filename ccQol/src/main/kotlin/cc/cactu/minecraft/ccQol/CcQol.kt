package cc.cactu.minecraft.ccqol

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.sign.Side
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import org.bukkit.block.Chest as ChestState
import org.bukkit.block.Sign as SignState
import org.bukkit.block.data.type.Chest as ChestData
import org.bukkit.block.data.type.Sign as SignData
import kotlin.random.Random

class CcQol : JavaPlugin(), Listener {
    private companion object {
        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern( "dd.MM.yyyy HH:mm" )
        val CARDINAL = setOf( BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST )
        val WATER_LIKE = setOf(
            Material.WATER, Material.BUBBLE_COLUMN, Material.KELP,
            Material.KELP_PLANT, Material.SEAGRASS, Material.TALL_SEAGRASS
        )

        const val SEARCH_UP = 8
    }

    override fun onEnable() {
        server.pluginManager.registerEvents( this, this )

        logger.info( "Hello ccQol" )
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

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPlayerDeath( event:PlayerDeathEvent ) {
        if (event.keepInventory || event.drops.isEmpty()) return

        val player = event.entity
        val graveBlock = findGraveBlock( player.location ) ?: return

        val remaining = event.drops.toMutableList()
        val guaranteed = mutableListOf<ItemStack>()

        val inventory = player.inventory
        val priority = inventory.armorContents.filterNotNull() + (0..8).mapNotNull { inventory.getItem(it) }

        for (item in priority) {
            if (item.isEmpty) continue
            if (remaining.remove( item )) guaranteed += item.clone()
        }

        val chance = config.getDouble( "tombstone.extra-items-chance", 0.5 ).coerceIn( 0.0, 1.0 )
        val extras = mutableListOf<ItemStack>()
        val rest = mutableListOf<ItemStack>()
        for (item in remaining) {
            if (Random.nextDouble() < chance) extras += item
            else rest += item
        }

        val toStore = guaranteed + extras
        if (toStore.isEmpty()) return

        val leftovers = buildGrave( graveBlock, player, toStore )

        event.drops.clear()
        event.drops.addAll( rest )
        event.drops.addAll( leftovers )

        player.sendMessage( Component.text( "Twój nagrobek: ${graveBlock.x} ${graveBlock.y} ${graveBlock.z} (${graveBlock.world.name})", NamedTextColor.GRAY ) )
    }

    private fun findGraveBlock( loc:Location ): Block? {
        val world = loc.world
        val minY = world.minHeight
        val maxY = world.maxHeight - 2
        val x = loc.blockX
        val z = loc.blockZ

        if (loc.y < minY) {
            for (y in minY..maxY) {
                val block = world.getBlockAt( x, y, z )
                if (hasRoom( block )) return block
            }

            return null
        }

        var y = loc.blockY.coerceIn( minY, maxY )

        if (isFluid( world.getBlockAt( x, y, z ) ) ) {
            while (y > minY && isFluid( world.getBlockAt( x, y - 1, z ) ) ) y--
        }

        for (dy in 0..SEARCH_UP) {
            val yy = y + dy
            if (yy > maxY) break

            val block = world.getBlockAt( x, yy, z )
            if (hasRoom( block )) return block
        }
        return null
    }

    private fun hasRoom( chestBlock:Block ) = chestBlock.isReplaceable && chestBlock.getRelative( BlockFace.UP ).isReplaceable
    private fun isWater( block:Block ) = block.type in WATER_LIKE
    private fun isFluid( block:Block ) = isWater( block ) || block.type == Material.LAVA

    private fun buildGrave( chestBlock:Block, player:Player, items:List<ItemStack> ): Collection<ItemStack> {
        val signBlock = chestBlock.getRelative( BlockFace.UP )
        val chestWater = isWater( chestBlock )
        val signWater = isWater( signBlock )

        val front = player.facing.let { if (it in CARDINAL) it.oppositeFace else BlockFace.SOUTH }

        // Chest
        val chestData = Material.CHEST.createBlockData() as ChestData
        chestData.facing = front
        chestData.isWaterlogged = chestWater
        chestBlock.setBlockData( chestData, false )

        val chestState = chestBlock.state as ChestState
        val chestInventory = chestState.blockInventory

        val slots = (0 until chestInventory.size).shuffled().iterator()
        val leftovers = mutableListOf<ItemStack>()
        for (item in items) {
            if (slots.hasNext()) chestInventory.setItem( slots.next(), item )
            else leftovers += item
        }

        // Sign
        val signData = Material.OAK_SIGN.createBlockData() as SignData
        signData.rotation = front
        signData.isWaterlogged = signWater
        signBlock.setBlockData(signData, false)

        val sign = signBlock.state as SignState
        val side = sign.getSide( Side.FRONT )
        side.line(0, Component.text( "[*]" ) )
        side.line(1, Component.text( player.name ) )
        side.line(2, Component.text( LocalDateTime.now().format( DATE_FORMAT ) ) )
        sign.isWaxed = true
        sign.update()

        return leftovers
    }
}
