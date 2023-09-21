package cc.cactu.minecraft.ccbase

import org.bukkit.plugin.java.JavaPlugin

class CcBase : JavaPlugin() {
    override fun onEnable() {
        logger.info( "Hello from ccPlugins base" )
    }
}
