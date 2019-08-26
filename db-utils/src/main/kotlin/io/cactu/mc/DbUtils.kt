package io.cactu.mc

import java.sql.DriverManager
import java.sql.Connection
import java.sql.ResultSet
import org.bukkit.plugin.java.JavaPlugin

private var port = 0
private lateinit var host:String
private lateinit var database:String
private lateinit var username:String
private lateinit var password:String
private lateinit var connection:Connection

fun doQuery( query:String ):ResultSet = connection
  .prepareStatement( query )
  .executeQuery()
fun doUpdatingQuery( query:String ):Int = connection
  .prepareStatement( query )
  .executeUpdate()

class DbUtils: JavaPlugin() {
  override fun onEnable() {
    host = "mysql.csrv.pl"
    port = 3306
    database = "csrv_651128"
    username = "csrv_651128"
    password = "0496eb9412992801d273"
    connection = DriverManager.getConnection( "jdbc:mysql://$host:$port/$database" , username, password )
  }
  override fun onDisable() {
    connection.close()
  }
}