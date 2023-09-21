package cc.cactu.minecraft.ccrails

class Station( name:String ) {
}

class Crossroad(
    val x: Double,
    val y: Double,
    val z: Double,
    val straitStations: List<String> = listOf(),
    val turnStations: List<String> = listOf(),
) {
    companion object {
        fun serialize( crossroad:Crossroad ):Map<String, Any> =mapOf(
            "x" to crossroad.x,
            "y" to crossroad.y,
            "z" to crossroad.z,
            "straitStations" to crossroad.straitStations,
            "turnStations" to crossroad.turnStations,
        )
        fun deserialize( values:Map<*,*> ):Crossroad = Crossroad(
            values.get( "x" ) as Double,
            values.get( "y" ) as Double,
            values.get( "z" ) as Double,
        )
    }
}