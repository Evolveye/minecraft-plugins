package io.cactu.mc.db

import org.bukkit.plugin.java.JavaPlugin
import java.sql.DriverManager


/** Database plugin
 */
class App: JavaPlugin() {
  override fun onEnable() {
    logger.info( "Plugin enabled" )

    setup()
  }

  public fun setup() {
    val host = "mysql.csrv.pl"
    val port = 3306
    val database = "csrv_651128"
    val username = "csrv_651128"
    val password = "0496eb9412992801d273"

    /*
    INSERT INTO `cuboids` (`ownerUUID`, `managersUUIDs`, `inhabitantsUUIDs`, `arasCorners`, `outposts`)
    VALUES ('a48ea883-3ba5-367f-bd5d-2e045175edfd', '', '', '-510 72 -874 6 8 6', '')
    */


    //Class.forName( "com.mysql.jdbc.Driver" )
    val results = DriverManager
      .getConnection( "jdbc:mysql://$host:$port/$database" , username, password )
      .prepareStatement( "SELECT * FROM `cuboids`" )
      .executeQuery()

    if ( results.next() )
      logger.info( "UUID of owner of first cuboid: ${results.getString( "ownerUUID" )}")
    else
      logger.info( "oops, error")
  }
}