package cc.cactu.minecraft.ccqol

import io.papermc.paper.datacomponent.DataComponentTypes
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.Tag
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.sign.Side
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockDamageAbortEvent
import org.bukkit.event.block.BlockDamageEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockPhysicsEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPistonRetractEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemRarity
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.random.Random
import org.bukkit.block.Chest as ChestState
import org.bukkit.block.Sign as SignState
import org.bukkit.block.data.type.Chest as ChestData
import org.bukkit.block.data.type.Sign as SignData

/** A player digging a tomb sign, with the task driving the digging effects. */
private class DigSession( val sign:Block ) {
    val startTick = Bukkit.getCurrentTick()
    var progress = 0.0
    var reachedMilestones = 0
    var ticks = 0L
    lateinit var task:BukkitTask
}

/**
 * Death tombstones: a chest with the most important items and a waxed sign above it.
 * A tomb is created only when the drops contain something valuable, and its sign takes long to break.
 */
class Tombstones( private val plugin:JavaPlugin ) : Listener {
    private companion object {
        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern( "dd.MM.yyyy HH:mm" )
        val CARDINAL = setOf( BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST )
        val WATER_LIKE = setOf(
            Material.WATER, Material.BUBBLE_COLUMN, Material.KELP,
            Material.KELP_PLANT, Material.SEAGRASS, Material.TALL_SEAGRASS
        )

        const val SEARCH_UP = 8
        // Diamond axe with Efficiency IV: base 8 + level² + 1.
        const val REFERENCE_TOOL_SPEED = 8.0 + 4 * 4 + 1
        const val REACH = 6
        const val EFFECTS_PERIOD_TICKS = 5L
        const val KNOCK_PERIOD_TICKS = 20L
        const val CRACK_MILESTONES = 4
        const val MIN_BREAK_PROGRESS = 0.7
    }

    private val ownerKey = NamespacedKey( plugin, "tomb_owner" )
    private val slowDiggingKey = NamespacedKey( plugin, "tomb_slow_digging" )
    private val digSessions = mutableMapOf<UUID, DigSession>()

    private val extraItemsChance = plugin.config.getDouble( "tombstone.extra-items-chance", 0.5 ).coerceIn( 0.0, 1.0 )
    private val signBreakTicks = (plugin.config.getDouble( "tombstone.sign-break-seconds", 60.0 ) * 20).coerceAtLeast( 1.0 )
    private val valuableRarities = readValuableRarities()
    private val valuableItems = readValuableItems()

    /** Removes every slow digging modifier, so a reload leaves no player slowed down. */
    fun shutdown() {
        for (playerId in digSessions.keys.toList()) endDigSession( playerId )
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPlayerDeath( event:PlayerDeathEvent ) {
        if (event.keepInventory || event.drops.isEmpty()) return

        val metPatterns = findMetValuablePatterns( event.drops )
        if (event.drops.none { isValuable( it, metPatterns ) }) return

        val player = event.entity
        val remaining = event.drops.toMutableList()
        val guaranteed = mutableListOf<ItemStack>()

        // Valuable items from armor, offhand and hotbar always go into the chest.
        val inventory = player.inventory
        val prioritySlots = inventory.armorContents.toList() + inventory.itemInOffHand + (0..8).map { inventory.getItem( it ) }

        for (item in prioritySlots) {
            if (item == null || item.isEmpty || !isValuable( item, metPatterns )) continue
            if (remaining.remove( item )) guaranteed += item.clone()
        }

        val extras = mutableListOf<ItemStack>()
        val rest = mutableListOf<ItemStack>()

        for (item in remaining) {
            if (Random.nextDouble() < extraItemsChance) extras += item
            else rest += item
        }

        val toStore = guaranteed + extras
        if (toStore.isEmpty()) return

        event.drops.clear()
        event.drops.addAll( rest )

        // An explosion removes blocks after killing the player, so the tomb is placed a tick later, in the crater.
        val deathLocation = player.location
        val facing = player.facing

        Bukkit.getScheduler().runTask( plugin, Runnable { placeGrave( player, deathLocation, facing, toStore ) } )
    }

    @EventHandler(ignoreCancelled = true)
    fun onSignDamage( event:BlockDamageEvent ) {
        val player = event.player
        val session = digSessions[ player.uniqueId ]

        if (session != null && session.sign == event.block) return
        if (session != null) endDigSession( player.uniqueId )

        if (player.gameMode == GameMode.CREATIVE) return
        if (!isTombSign( event.block )) return

        startDigSession( player, event.block )
    }

    @EventHandler
    fun onSignDamageAbort( event:BlockDamageAbortEvent ) {
        endDigSession( event.player.uniqueId )
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onSignBreak( event:BlockBreakEvent ) {
        val sign = event.block
        if (!isTombSign( sign )) return

        val player = event.player
        if (player.gameMode == GameMode.CREATIVE) return

        // Paper decides about an instant break before the digging gets slowed down, so the progress is checked here.
        // Like vanilla, digging counts as done at 70% of the slowed progress.
        val session = digSessions[ player.uniqueId ]
        val diggingTicks = session?.let { Bukkit.getCurrentTick() - it.startTick + 1 } ?: 0

        if (session?.sign != sign || diggingTicks * sign.getBreakSpeed( player ) < MIN_BREAK_PROGRESS) {
            event.isCancelled = true
            return
        }

        endDigSession( player.uniqueId )
        playFinalBreakEffects( sign )
    }

    @EventHandler
    fun onQuit( event:PlayerQuitEvent ) {
        endDigSession( event.player.uniqueId )
    }

    // A tomb sign keeps floating when the chest under it is gone. Paper reports the shape update
    // that would pop the sign at the changed block, so the block below the sign is checked too.
    @EventHandler(ignoreCancelled = true)
    fun onSignPhysics( event:BlockPhysicsEvent ) {
        val block = event.block
        if (isTombSign( block ) || isTombSign( block.getRelative( BlockFace.UP ) )) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true)
    fun onEntityExplode( event:EntityExplodeEvent ) {
        event.blockList().removeIf { isTombSign( it ) }
    }

    @EventHandler(ignoreCancelled = true)
    fun onBlockExplode( event:BlockExplodeEvent ) {
        event.blockList().removeIf { isTombSign( it ) }
    }

    @EventHandler(ignoreCancelled = true)
    fun onPistonExtend( event:BlockPistonExtendEvent ) {
        if (event.blocks.any { isTombSign( it ) }) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true)
    fun onPistonRetract( event:BlockPistonRetractEvent ) {
        if (event.blocks.any { isTombSign( it ) }) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true)
    fun onEntityChangeBlock( event:EntityChangeBlockEvent ) {
        if (isTombSign( event.block )) event.isCancelled = true
    }

    private fun readValuableRarities() = plugin.config.getStringList( "tombstone.valuable-rarities" )
        .mapNotNull { name -> ItemRarity.entries.find { it.name.equals( name, ignoreCase = true ) } }
        .toSet()

    private fun readValuableItems(): Map<Regex, Int> {
        val section = plugin.config.getConfigurationSection( "tombstone.valuable-items" ) ?: return emptyMap()

        return section.getKeys( false ).associate { pattern ->
            val regex = Regex( pattern.uppercase().split( "*" ).joinToString( ".*" ) { Regex.escape( it ) } )
            regex to section.getInt( pattern, 1 ).coerceAtLeast( 1 )
        }
    }

    // Patterns whose matching items reach the configured total amount among the drops.
    private fun findMetValuablePatterns( drops:List<ItemStack> ) = valuableItems
        .filter { (pattern, minAmount) -> drops.filter { pattern.matches( it.type.name ) }.sumOf { it.amount } >= minAmount }
        .keys

    private fun isValuable( item:ItemStack, metPatterns:Set<Regex> ) =
        getEffectiveRarity( item ) in valuableRarities || metPatterns.any { it.matches( item.type.name ) }

    // Like vanilla, enchanting raises the rarity shown in the item's name color.
    private fun getEffectiveRarity( item:ItemStack ): ItemRarity {
        val baseRarity = item.getData( DataComponentTypes.RARITY ) ?: ItemRarity.COMMON
        if (item.enchantments.isEmpty()) return baseRarity

        return when (baseRarity) {
            ItemRarity.COMMON, ItemRarity.UNCOMMON -> ItemRarity.RARE
            ItemRarity.RARE, ItemRarity.EPIC -> ItemRarity.EPIC
        }
    }

    private fun isTombSign( block:Block ): Boolean {
        if (!Tag.STANDING_SIGNS.isTagged( block.type )) return false

        val sign = block.getState( false ) as? SignState ?: return false
        return sign.persistentDataContainer.has( ownerKey, PersistentDataType.STRING )
    }

    private fun startDigSession( player:Player, sign:Block ) {
        val diggingMultiplier = sign.type.hardness * 30 / (REFERENCE_TOOL_SPEED * signBreakTicks)
        val modifier = AttributeModifier( slowDiggingKey, diggingMultiplier - 1, AttributeModifier.Operation.MULTIPLY_SCALAR_1 )

        player.getAttribute( Attribute.BLOCK_BREAK_SPEED )?.apply {
            removeModifier( slowDiggingKey )
            addTransientModifier( modifier )
        }

        val session = DigSession( sign )
        session.task = Bukkit.getScheduler().runTaskTimer( plugin, Runnable { updateDigSession( player, session ) }, 0L, EFFECTS_PERIOD_TICKS )

        digSessions[ player.uniqueId ] = session
    }

    private fun updateDigSession( player:Player, session:DigSession ) {
        val sign = session.sign
        val isStillDigging = player.isOnline && player.gameMode != GameMode.CREATIVE && player.getTargetBlockExact( REACH ) == sign && isTombSign( sign )
        if (!isStillDigging) return endDigSession( player.uniqueId )

        // The real progress of the player's tool, so effects match the crack animation for every tool.
        session.ticks += EFFECTS_PERIOD_TICKS
        session.progress = (session.progress + sign.getBreakSpeed( player ) * EFFECTS_PERIOD_TICKS).coerceAtMost( 1.0 )

        val progress = session.progress
        val center = sign.location.add( 0.5, 0.5, 0.5 )
        val world = sign.world

        // More and more souls escape the grave while the sign cracks.
        world.spawnParticle( Particle.SOUL, center, 1 + (progress * 10).toInt(), 0.3, 0.4, 0.3, 0.01 )

        // A knock every second, getting higher as the sign gives way.
        if (session.ticks % KNOCK_PERIOD_TICKS == 0L) world.playSound( center, Sound.BLOCK_WOOD_HIT, 1f, 0.5f + progress.toFloat() * 0.5f )

        // A crack at every quarter of the progress.
        val milestones = (progress * CRACK_MILESTONES).toInt().coerceAtMost( CRACK_MILESTONES - 1 )

        if (milestones > session.reachedMilestones) {
            session.reachedMilestones = milestones
            world.playSound( center, Sound.BLOCK_WOOD_BREAK, 0.8f, 0.6f )
        }

        if (Random.nextDouble() < progress * 0.2) world.playSound( center, Sound.PARTICLE_SOUL_ESCAPE, 1f, 0.8f + Random.nextFloat() * 0.4f )
    }

    private fun endDigSession( playerId:UUID ) {
        val session = digSessions.remove( playerId ) ?: return
        session.task.cancel()

        Bukkit.getPlayer( playerId )?.getAttribute( Attribute.BLOCK_BREAK_SPEED )?.removeModifier( slowDiggingKey )
    }

    private fun playFinalBreakEffects( sign:Block ) {
        val center = sign.location.add( 0.5, 0.5, 0.5 )

        sign.world.spawnParticle( Particle.SOUL, center, 40, 0.4, 0.6, 0.4, 0.05 )
        sign.world.playSound( center, Sound.BLOCK_BELL_USE, 1f, 0.5f )
        sign.world.playSound( center, Sound.BLOCK_BELL_RESONATE, 0.6f, 0.5f )
    }

    private fun findGraveBlock( loc:Location ): Block? {
        val world = loc.world
        val minY = world.minHeight
        val maxY = world.maxHeight - 2
        val x = loc.blockX
        val z = loc.blockZ

        // Fall through air and fluids to the ground below, or to the bottom of the world above the void.
        var y = loc.blockY.coerceIn( minY, maxY )
        while (y > minY && world.getBlockAt( x, y, z ).isReplaceable && world.getBlockAt( x, y - 1, z ).isReplaceable) y--

        // From there, find room for the chest and the sign above it. Below the world, the whole column is searched.
        val searchTop = if (loc.y < minY) maxY else minOf( y + SEARCH_UP, maxY )

        for (chestY in y..searchTop) {
            val block = world.getBlockAt( x, chestY, z )
            if (hasRoom( block )) return block
        }

        return null
    }

    private fun hasRoom( chestBlock:Block ) = chestBlock.isReplaceable && chestBlock.getRelative( BlockFace.UP ).isReplaceable
    private fun isWater( block:Block ) = block.type in WATER_LIKE

    // Items without a place for the tomb, or not fitting into its chest, drop like on a normal death.
    private fun placeGrave( player:Player, deathLocation:Location, facing:BlockFace, items:List<ItemStack> ) {
        val graveBlock = findGraveBlock( deathLocation )

        if (graveBlock == null) {
            for (item in items) deathLocation.world.dropItemNaturally( deathLocation, item )
            return
        }

        val leftovers = buildGrave( graveBlock, player, facing, items )
        for (item in leftovers) graveBlock.world.dropItemNaturally( graveBlock.location, item )

        player.sendMessage( Component.text( "Twój nagrobek: ${graveBlock.x} ${graveBlock.y} ${graveBlock.z} (${graveBlock.world.name})", NamedTextColor.GRAY ) )
    }

    private fun buildGrave( chestBlock:Block, player:Player, facing:BlockFace, items:List<ItemStack> ): Collection<ItemStack> {
        val signBlock = chestBlock.getRelative( BlockFace.UP )
        val chestWater = isWater( chestBlock )
        val signWater = isWater( signBlock )

        val front = facing.let { if (it in CARDINAL) it.oppositeFace else BlockFace.SOUTH }

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
        signBlock.setBlockData( signData, false )

        val sign = signBlock.state as SignState
        val side = sign.getSide( Side.FRONT )
        side.line( 0, Component.text( "[*]" ) )
        side.line( 1, Component.text( player.name ) )
        side.line( 2, Component.text( LocalDateTime.now().format( DATE_FORMAT ) ) )
        sign.isWaxed = true
        sign.persistentDataContainer.set( ownerKey, PersistentDataType.STRING, player.uniqueId.toString() )
        sign.update()

        return leftovers
    }
}
