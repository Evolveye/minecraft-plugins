package io.cactu.mc.cuboids

import org.bukkit.plugin.java.JavaPlugin
import java.sql.DriverManager
import java.sql.Connection
import java.sql.ResultSet

class App: JavaPlugin() {
  val host = "mysql.csrv.pl"
  val port = 3306
  val database = "csrv_651128"
  val username = "csrv_651128"
  val password = "0496eb9412992801d273"
  val connection = DriverManager.getConnection( "jdbc:mysql://$host:$port/$database" , username, password )

  override fun onEnable() {
    val cuboids = doQuery( "SELECT * FROM `cuboids`")

    if ( cuboids.next() )
      logger.info( "UUID of owner of first cuboid: ${cuboids.getString( "ownerUUID" )}")
    else
      logger.info( "oops, error")
  }

  override fun onDisable() {
    connection.close()
  }

  fun doQuery( query:String ):ResultSet = connection
    .prepareStatement( query )
    .executeQuery()
}