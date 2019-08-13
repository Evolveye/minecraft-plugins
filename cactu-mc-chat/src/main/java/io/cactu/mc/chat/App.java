package io.cactu.mc.chat;

import org.bukkit.plugin.java.JavaPlugin;


/** Hello Spigot!
 */
public class App extends JavaPlugin {
    @Override
    public void onEnable() {
        getLogger().info( "Hello, Spigot!" );
    }
    @Override
    public void onDisable() {
        getLogger().info( "See you again, Spigot!" );
    }
}
