package io.cactu.mc.chat

import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import java.util.logging.Logger

public class App : JavaPlugin() {
  override fun onEnable() {
    logger.info { "Example plugin started!" }
  }
  override fun onDisable() {
    logger.info { "Example plugin stopped!" }
  }
}