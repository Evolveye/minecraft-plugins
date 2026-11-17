package cc.cactu.minecraft.ccqol

import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.ItemLore
import io.papermc.paper.datacomponent.item.ResolvableProfile
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Keyed
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Mannequin
import org.bukkit.entity.Player
import org.bukkit.entity.Pose
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockDispenseEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityPlaceEvent
import org.bukkit.event.inventory.PrepareItemCraftEvent
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.ItemType
import org.bukkit.inventory.RecipeChoice
import org.bukkit.inventory.ShapelessRecipe
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.util.Vector
import kotlin.math.roundToInt

/** Settings a mannequin keeps in its item when it is broken. */
private class MannequinSettings( val pose:Pose, val scale:Double, val isFloating:Boolean )

/** Equipment slots a mannequin can hold items in. */
private val MANNEQUIN_SLOTS = listOf(
    EquipmentSlot.HAND, EquipmentSlot.OFF_HAND,
    EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
)

/** Poses in the order the pose item cycles through them, with their names. */
private val POSE_NAMES = mapOf(
    Pose.STANDING to "Stoi",
    Pose.SNEAKING to "Kuca",
    Pose.SWIMMING to "Pływa",
    Pose.FALL_FLYING to "Szybuje",
    Pose.SLEEPING to "Śpi",
)

private val DEFAULT_SETTINGS = MannequinSettings( Pose.STANDING, 1.0, false )

/**
 * Mannequins crafted from any head (except a blank one) and an armor stand, wearing the head's skin.
 * They can be pushed, have a few hearts, and drop their item with their pose, size and floating when killed.
 * Shift right-click gives or takes items. Right-click with the configured items changes the size, the pose or floating.
 */
class Mannequins( private val plugin:JavaPlugin, private val playerHeads:PlayerHeads ) : Listener {
    private val recipeKey = NamespacedKey( plugin, "mannequin" )
    private val mannequinKey = NamespacedKey( plugin, "mannequin" )
    private val sourceItemKey = NamespacedKey( plugin, "mannequin_item" )
    private val poseKey = NamespacedKey( plugin, "mannequin_pose" )
    private val scaleKey = NamespacedKey( plugin, "mannequin_scale" )
    private val floatingKey = NamespacedKey( plugin, "mannequin_floating" )

    private val health = plugin.config.getDouble( "mannequins.health", 6.0 ).coerceAtLeast( 1.0 )
    private val growItem = readMaterial( "mannequins.grow-item", Material.RED_MUSHROOM )
    private val shrinkItem = readMaterial( "mannequins.shrink-item", Material.BROWN_MUSHROOM )
    private val poseItem = readMaterial( "mannequins.pose-item", Material.BRUSH )
    private val floatItem = readMaterial( "mannequins.float-item", Material.PHANTOM_MEMBRANE )
    private val scaleStep = plugin.config.getDouble( "mannequins.scale-step", 0.1 ).coerceAtLeast( 0.01 )
    private val minScale = plugin.config.getDouble( "mannequins.min-scale", 0.5 ).coerceIn( 0.0625, 16.0 )
    private val maxScale = plugin.config.getDouble( "mannequins.max-scale", 1.5 ).coerceIn( minScale, 16.0 )
    private val poses = POSE_NAMES.keys.filter { it in Mannequin.validPoses() }

    // The real result depends on the head, so the recipe only gives a placeholder.
    fun registerRecipe() {
        val recipe = ShapelessRecipe( recipeKey, ItemStack( Material.ARMOR_STAND ) )
        recipe.addIngredient( RecipeChoice.itemType( ItemType.PLAYER_HEAD ) )
        recipe.addIngredient( RecipeChoice.exactChoice( ItemStack( Material.ARMOR_STAND ) ) )

        Bukkit.addRecipe( recipe )
    }

    fun unregisterRecipe() {
        Bukkit.removeRecipe( recipeKey )
    }

    @EventHandler
    fun onJoin( event:PlayerJoinEvent ) {
        event.player.discoverRecipe( recipeKey )
    }

    @EventHandler
    fun onCraftPrepare( event:PrepareItemCraftEvent ) {
        val recipe = event.recipe as? Keyed ?: return
        if (recipe.key != recipeKey) return

        val inventory = event.inventory
        val head = inventory.matrix.firstOrNull { it?.type == Material.PLAYER_HEAD } ?: return
        val profile = head.getData( DataComponentTypes.PROFILE )

        if (profile == null || playerHeads.isBlankHead( head )) {
            inventory.result = null
            return
        }

        inventory.result = createMannequinItem( head, profile )
    }

    @EventHandler(ignoreCancelled = true)
    fun onPlace( event:EntityPlaceEvent ) {
        if (event.entity !is ArmorStand) return

        val player = event.player ?: return
        val item = player.inventory.getItem( event.hand )
        if (!isMannequinItem( item )) return

        val profile = item.getData( DataComponentTypes.PROFILE ) ?: return

        event.isCancelled = true

        spawnMannequin( event.entity.location, profile, item.asOne() )

        // The inventory returns a copy of the item, so the smaller stack has to be put back.
        if (player.gameMode != GameMode.CREATIVE) player.inventory.setItem( event.hand, item.subtract() )
    }

    // A dispenser would place a plain armor stand and lose the skin.
    @EventHandler(ignoreCancelled = true)
    fun onDispense( event:BlockDispenseEvent ) {
        if (isMannequinItem( event.item )) event.isCancelled = true
    }

    // A creative player destroys the mannequin without getting the item back.
    @EventHandler
    fun onDeath( event:EntityDeathEvent ) {
        val mannequin = event.entity as? Mannequin ?: return
        if (!mannequin.persistentDataContainer.has( mannequinKey )) return

        // The equipment drops even for a creative player, unless the game already dropped it.
        val equipment = mannequin.equipment

        for (slot in MANNEQUIN_SLOTS) {
            val item = equipment.getItem( slot )
            if (item.isEmpty || event.drops.any { it.isSimilar( item ) }) continue

            event.drops += item
        }

        val killer = event.damageSource.causingEntity as? Player
        if (killer?.gameMode == GameMode.CREATIVE) return

        event.drops += addSettings( getSourceItem( mannequin ), readSettings( mannequin ) )
    }

    @EventHandler(ignoreCancelled = true)
    fun onInteract( event:PlayerInteractAtEntityEvent ) {
        if (event.hand != EquipmentSlot.HAND) return

        val mannequin = event.rightClicked as? Mannequin ?: return
        if (!mannequin.persistentDataContainer.has( mannequinKey )) return

        val player = event.player
        val itemInHand = player.inventory.itemInMainHand

        if (player.isSneaking) {
            event.isCancelled = true

            if (itemInHand.isEmpty) takeItem( player, mannequin, event.clickedPosition.y / mannequin.height )
            else giveItem( player, mannequin, itemInHand )

            return
        }

        when (itemInHand.type) {
            growItem -> changeScale( player, mannequin, itemInHand, scaleStep )
            shrinkItem -> changeScale( player, mannequin, itemInHand, -scaleStep )
            poseItem -> changePose( player, mannequin )
            floatItem -> toggleFloating( player, mannequin )
            else -> return
        }

        event.isCancelled = true
    }

    private fun readMaterial( path:String, default:Material ) =
        Material.matchMaterial( plugin.config.getString( path ) ?: "" ) ?: default

    private fun isMannequinItem( item:ItemStack ) =
        item.type == Material.ARMOR_STAND && item.persistentDataContainer.has( mannequinKey )

    private fun createMannequinItem( head:ItemStack, profile:ResolvableProfile ): ItemStack {
        val headName = head.getData( DataComponentTypes.CUSTOM_NAME ) ?: Component.text( profile.name() ?: "?" )

        val item = ItemStack( Material.ARMOR_STAND )
        item.setData( DataComponentTypes.PROFILE, profile )
        item.setData( DataComponentTypes.ITEM_NAME, Component.text( "Manekin" ) )
        item.setData( DataComponentTypes.LORE, ItemLore.lore( listOf( createLoreLine( headName ) ) ) )
        item.setData( DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true )
        item.editPersistentDataContainer { it.set( mannequinKey, PersistentDataType.BOOLEAN, true ) }

        return item
    }

    // The mannequin keeps its item without the settings, so a mannequin broken with default settings drops the crafted item.
    private fun spawnMannequin( location:Location, profile:ResolvableProfile, item:ItemStack ) {
        val settings = readSettings( item )
        val sourceItem = removeSettings( item )

        location.world.spawn( location, Mannequin::class.java ) { mannequin ->
            mannequin.profile = profile
            mannequin.description = null
            mannequin.isPersistent = true
            mannequin.removeWhenFarAway = false

            mannequin.getAttribute( Attribute.MAX_HEALTH )?.baseValue = health
            mannequin.health = health

            mannequin.setPose( settings.pose, true )
            mannequin.getAttribute( Attribute.SCALE )?.baseValue = settings.scale
            mannequin.setGravity( !settings.isFloating )

            mannequin.persistentDataContainer.set( mannequinKey, PersistentDataType.BOOLEAN, true )
            mannequin.persistentDataContainer.set( sourceItemKey, PersistentDataType.BYTE_ARRAY, sourceItem.serializeAsBytes() )
        }
    }

    // Mannequins placed before items had the glint get it when broken.
    private fun getSourceItem( mannequin:Mannequin ): ItemStack {
        val bytes = mannequin.persistentDataContainer.get( sourceItemKey, PersistentDataType.BYTE_ARRAY ) ?: return ItemStack( Material.ARMOR_STAND )

        val item = ItemStack.deserializeBytes( bytes )
        item.setData( DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true )

        return item
    }

    private fun readSettings( mannequin:Mannequin ) = MannequinSettings(
        mannequin.pose,
        mannequin.getAttribute( Attribute.SCALE )?.baseValue ?: 1.0,
        !mannequin.hasGravity(),
    )

    private fun readSettings( item:ItemStack ): MannequinSettings {
        val container = item.persistentDataContainer
        val poseName = container.get( poseKey, PersistentDataType.STRING )

        return MannequinSettings(
            poses.find { it.name == poseName } ?: Pose.STANDING,
            container.get( scaleKey, PersistentDataType.DOUBLE ) ?: 1.0,
            container.has( floatingKey ),
        )
    }

    // Only settings different from the defaults are saved, each with its own lore line added after the head's name.
    private fun addSettings( item:ItemStack, settings:MannequinSettings ): ItemStack {
        val loreLines = mutableListOf<Component>()

        item.editPersistentDataContainer { container ->
            if (settings.pose != DEFAULT_SETTINGS.pose) {
                container.set( poseKey, PersistentDataType.STRING, settings.pose.name )
                loreLines += Component.text( POSE_NAMES[ settings.pose ] ?: settings.pose.name )
            }

            if (settings.scale != DEFAULT_SETTINGS.scale) {
                container.set( scaleKey, PersistentDataType.DOUBLE, settings.scale )
                loreLines += Component.text( "Rozmiar: ${(settings.scale * 100).roundToInt()}%" )
            }

            if (settings.isFloating) {
                container.set( floatingKey, PersistentDataType.BOOLEAN, true )
                loreLines += Component.text( "Unosi się w powietrzu" )
            }
        }

        if (loreLines.isEmpty()) return item

        val lore = item.getData( DataComponentTypes.LORE )?.lines().orEmpty()
        item.setData( DataComponentTypes.LORE, ItemLore.lore( lore + loreLines.map { createLoreLine( it ) } ) )

        return item
    }

    // Settings lore lines are always the last ones, one per saved setting.
    private fun removeSettings( item:ItemStack ): ItemStack {
        val settingKeys = listOf( poseKey, scaleKey, floatingKey ).filter { item.persistentDataContainer.has( it ) }
        if (settingKeys.isEmpty()) return item

        item.editPersistentDataContainer { container -> settingKeys.forEach { container.remove( it ) } }

        val lore = item.getData( DataComponentTypes.LORE )?.lines().orEmpty()
        item.setData( DataComponentTypes.LORE, ItemLore.lore( lore.dropLast( settingKeys.size ) ) )

        return item
    }

    // The held stack goes to the mannequin as one item, and whatever it held before goes back to the player.
    private fun giveItem( player:Player, mannequin:Mannequin, itemInHand:ItemStack ) {
        val slot = itemInHand.getData( DataComponentTypes.EQUIPPABLE )?.slot() ?: itemInHand.type.equipmentSlot
        if (slot !in MANNEQUIN_SLOTS) return

        val equipment = mannequin.equipment
        val previousItem = equipment.getItem( slot )
        equipment.setItem( slot, itemInHand.asOne() )

        val remainingStack = if (player.gameMode == GameMode.CREATIVE) itemInHand else itemInHand.subtract()
        player.inventory.setItemInMainHand( remainingStack )

        if (!previousItem.isEmpty) player.inventory.addItem( previousItem ).values.forEach { player.world.dropItemNaturally( player.location, it ) }

        player.world.playSound( mannequin.location, Sound.ITEM_ARMOR_EQUIP_GENERIC, 1f, 1f )
    }

    // Like an armor stand, the clicked height picks the armor piece; the hands are taken when that slot is empty.
    private fun takeItem( player:Player, mannequin:Mannequin, clickedHeight:Double ) {
        val clickedSlot = when {
            clickedHeight < 0.25 -> EquipmentSlot.FEET
            clickedHeight < 0.5 -> EquipmentSlot.LEGS
            clickedHeight < 0.75 -> EquipmentSlot.CHEST
            else -> EquipmentSlot.HEAD
        }

        val equipment = mannequin.equipment
        val slot = listOf( clickedSlot, EquipmentSlot.HAND, EquipmentSlot.OFF_HAND ).firstOrNull { !equipment.getItem( it ).isEmpty } ?: return

        player.inventory.setItemInMainHand( equipment.getItem( slot ) )
        equipment.setItem( slot, null )

        player.world.playSound( mannequin.location, Sound.ITEM_ARMOR_EQUIP_GENERIC, 1f, 0.8f )
    }

    // An item is used only when the size really changes; rounding keeps sizes like 1.2 exact after many steps.
    private fun changeScale( player:Player, mannequin:Mannequin, itemInHand:ItemStack, scaleChange:Double ) {
        val scale = mannequin.getAttribute( Attribute.SCALE ) ?: return
        val newScale = ((scale.baseValue + scaleChange) * 100).roundToInt().div( 100.0 ).coerceIn( minScale, maxScale )
        if (newScale == scale.baseValue) return

        scale.baseValue = newScale
        if (player.gameMode != GameMode.CREATIVE) player.inventory.setItemInMainHand( itemInHand.subtract() )

        val center = mannequin.location.add( 0.0, mannequin.height / 2, 0.0 )
        val pitch = if (scaleChange > 0) 0.8f else 1.4f

        mannequin.world.spawnParticle( Particle.HAPPY_VILLAGER, center, 12, 0.3, mannequin.height / 3, 0.3 )
        mannequin.world.playSound( center, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, pitch )
        player.sendActionBar( Component.text( "Rozmiar: ${(newScale * 100).roundToInt()}%" ) )
    }

    private fun changePose( player:Player, mannequin:Mannequin ) {
        val nextPose = poses[ (poses.indexOf( mannequin.pose ) + 1) % poses.size ]
        mannequin.setPose( nextPose, true )

        mannequin.world.playSound( mannequin.location, Sound.ITEM_BRUSH_BRUSHING_GENERIC, 1f, 1f )
        player.sendActionBar( Component.text( POSE_NAMES[ nextPose ] ?: nextPose.name ) )
    }

    // A floating mannequin stops in place and can still be pushed around.
    private fun toggleFloating( player:Player, mannequin:Mannequin ) {
        val isFloating = mannequin.hasGravity()
        mannequin.setGravity( !isFloating )
        if (isFloating) mannequin.velocity = Vector()

        mannequin.world.playSound( mannequin.location, Sound.ENTITY_PHANTOM_FLAP, 1f, if (isFloating) 1.2f else 0.8f )
        player.sendActionBar( Component.text( if (isFloating) "Unosi się w powietrzu" else "Opada" ) )
    }
}

/** Formats an item lore line: gray and not italic. */
private fun createLoreLine( text:Component ) = text.color( NamedTextColor.GRAY ).decoration( TextDecoration.ITALIC, false )
