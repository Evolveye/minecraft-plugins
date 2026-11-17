package cc.cactu.minecraft.ccqol

import com.destroystokyo.paper.profile.ProfileProperty
import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.ResolvableProfile
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Creeper
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.PrepareAnvilEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.ItemType
import org.bukkit.inventory.RecipeChoice
import org.bukkit.inventory.ShapedRecipe
import org.bukkit.plugin.java.JavaPlugin
import java.util.Base64
import java.util.UUID

/** A head ID typed in an anvil, with or without the leading `#`. */
private val HEAD_ID_PATTERN = Regex( "#?(\\d{1,9})" )

private const val DEFAULT_BLANK_HEAD_TEXTURE = "111314ea76bf86857ead0e7aabbb3167946f680cf53be198d3368f6e5b8a3e90"

/**
 * Player and decorative heads.
 * A blank head is crafted and turned into any catalog head in an anvil by renaming it to the head's ID.
 * A real player's head drops only when a charged creeper kills that player.
 */
class PlayerHeads( private val plugin:JavaPlugin, private val catalog:HeadCatalog ) : Listener {
    private val recipeKey = NamespacedKey( plugin, "blank_head" )
    private val anvilCost = plugin.config.getInt( "heads.anvil-cost", 1 ).coerceAtLeast( 1 )
    private val blankHeadTexture = plugin.config.getString( "heads.blank-head-texture" ) ?: DEFAULT_BLANK_HEAD_TEXTURE
    private val blankHeadTextureValue = createTextureValue( blankHeadTexture )

    fun registerRecipe() {
        val recipe = ShapedRecipe( recipeKey, createDecorativeHead( "Pusta głowa", blankHeadTexture ) )
        recipe.shape( "CRC", "EBE", "CCC" )
        recipe.setIngredient( 'C', Material.CLAY_BALL )
        recipe.setIngredient( 'R', RecipeChoice.itemType( ItemType.BRAIN_CORAL, ItemType.BRAIN_CORAL_FAN ) )
        recipe.setIngredient( 'E', Material.SPIDER_EYE )
        recipe.setIngredient( 'B', Material.BONE_BLOCK )

        Bukkit.addRecipe( recipe )
    }

    fun unregisterRecipe() {
        Bukkit.removeRecipe( recipeKey )
    }

    fun isBlankHead( item:ItemStack ): Boolean {
        val profile = item.getData( DataComponentTypes.PROFILE ) ?: return false
        return profile.properties().any { it.name == "textures" && it.value == blankHeadTextureValue }
    }

    @EventHandler
    fun onJoin( event:PlayerJoinEvent ) {
        event.player.discoverRecipe( recipeKey )
    }

    @EventHandler
    fun onAnvilPrepare( event:PrepareAnvilEvent ) {
        val view = event.view
        val renameText = view.renameText ?: return
        val idMatch = HEAD_ID_PATTERN.matchEntire( renameText.trim() ) ?: return

        val inventory = event.inventory
        val heads = inventory.firstItem ?: return
        if (heads.type != Material.PLAYER_HEAD || isPlayerHead( heads )) return
        if (inventory.secondItem?.isEmpty == false) return

        // An unknown ID gives nothing, instead of a head renamed to a number.
        val catalogHead = catalog.find( idMatch.groupValues[ 1 ].toInt() )

        if (catalogHead == null) {
            event.result = null
            return
        }

        event.result = createDecorativeHead( catalogHead.name, catalogHead.texture, heads.amount )
        view.repairCost = anvilCost
        view.repairItemCountCost = 0
    }

    @EventHandler
    fun onPlayerDeath( event:PlayerDeathEvent ) {
        val creeper = event.damageSource.directEntity as? Creeper ?: return
        if (!creeper.isPowered) return

        val player = event.entity
        val head = ItemStack( Material.PLAYER_HEAD )
        head.setData( DataComponentTypes.PROFILE, ResolvableProfile.resolvableProfile( player.playerProfile ) )

        if (event.keepInventory) player.world.dropItemNaturally( player.location, head )
        else event.drops += head
    }
}

/**
 * A real player's head has the player's name, or a texture signed by Mojang.
 * Decorative heads have neither.
 */
private fun isPlayerHead( item:ItemStack ): Boolean {
    val profile = item.getData( DataComponentTypes.PROFILE ) ?: return false
    return profile.name() != null || profile.properties().any { it.name == "textures" && it.isSigned }
}

/**
 * Creates a head with the given texture hash.
 * Its UUID comes from the texture, so heads with the same texture stack together.
 */
private fun createDecorativeHead( name:String, texture:String, amount:Int = 1 ): ItemStack {
    val profile = ResolvableProfile.resolvableProfile()
        .uuid( UUID.nameUUIDFromBytes( "ccqol-head:$texture".toByteArray() ) )
        .addProperty( ProfileProperty( "textures", createTextureValue( texture ) ) )
        .build()

    // A custom name, unlike an item name, stays on the head when it is placed and broken.
    val head = ItemStack( Material.PLAYER_HEAD, amount )
    head.setData( DataComponentTypes.PROFILE, profile )
    head.setData( DataComponentTypes.CUSTOM_NAME, Component.text( name ).decoration( TextDecoration.ITALIC, false ) )

    return head
}

/** Creates the base64 texture value pointing to the given texture hash. */
private fun createTextureValue( texture:String ): String {
    val json = """{"textures":{"SKIN":{"url":"http://textures.minecraft.net/texture/$texture"}}}"""
    return Base64.getEncoder().encodeToString( json.toByteArray() )
}
