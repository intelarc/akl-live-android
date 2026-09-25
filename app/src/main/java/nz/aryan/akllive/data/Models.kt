package nz.aryan.akllive.data

/** A vehicle from AT's realtime feed. */
data class Vehicle(
    val id: String,
    val label: String,
    val tripId: String?,
    val routeId: String?,
    val directionId: Int?,
    val startDate: String?,
    val lat: Double,
    val lon: Double,
    val bearing: Float?,
    val speedKmh: Float?,
    val timestamp: Long,
    val occupancy: Int?,
)

/** A bus somewhere on the network, for the every-bus map and the fleet pages. */
data class LiveBus(val v: Vehicle, val info: BusInfo) {
    /** "27H" from a route id like "27H-203"; null when it isn't in service */
    val route: String? get() = v.routeId?.substringBefore("-")?.takeIf { it.isNotEmpty() }
}

data class FleetState(
    val buses: List<LiveBus> = emptyList(),
    val updated: Long = 0,
    val loading: Boolean = true,
    val error: String? = null,
)

/** Where a tapped bus is going and how late it's running, fetched on demand. */
data class BusTrip(val tripId: String, val headsign: String? = null, val delay: Int? = null)

/** One bus due at our stop. */
data class BusDeparture(
    val tripId: String,
    val route: String,
    val headsign: String,
    val stopSeq: Int,
    val scheduled: Long,
    val delay: Int = 0,
    val live: Boolean = false,
    val cancelled: Boolean = false,
    val gone: Boolean = false,
    val vehicleSeq: Int? = null,
    val vehicle: Vehicle? = null,
) {
    val expected: Long get() = scheduled + delay
    /** How many stops away the bus is, from its last realtime stop event. */
    val stopsAway: Int? get() = vehicleSeq?.let { stopSeq - it }?.takeIf { it >= 0 }
}

data class StopBoard(
    val code: String,
    val name: String = "",
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val route: String = "",
    val headsign: String = "",
    val departures: List<BusDeparture> = emptyList(),
    val updated: Long = 0,
    val error: String? = null,
) {
    /** "to Britomart", or the stop's name when nothing says where its buses go */
    val towards: String get() = if (headsign.isNotEmpty()) "to $headsign" else name.ifEmpty { "Stop $code" }
}

/** A train placed on the schematic map. */
data class Train(
    val vehicle: Vehicle,
    val line: Int,
    val x: Float,
    val y: Float,
    val delay: Int? = null,
    val stopSeq: Int? = null,
)

data class TrainState(
    val trains: List<Train> = emptyList(),
    /** where each train was before the latest fix, so markers can glide */
    val before: Map<String, Pair<Float, Float>> = emptyMap(),
    /** direction of travel on the map, degrees, per vehicle */
    val heading: Map<String, Float> = emptyMap(),
    /** android.os.SystemClock.elapsedRealtime() of the latest fix */
    val movedAt: Long = 0,
    val updated: Long = 0,
    val error: String? = null,
) {
    fun counts(): IntArray {
        val c = IntArray(MapData.LINE_IDS.size)
        trains.forEach { c[it.line]++ }
        return c
    }
}

/** A train's run: where it's going and the stops still ahead. */
data class TripDetail(
    val tripId: String,
    val headsign: String,
    val stops: List<TripStop>,
)

data class TripStop(val seq: Int, val station: Int?, val name: String, val scheduled: Long)

/** A departure from a train station, for the station panel. */
data class StationDeparture(
    val tripId: String,
    val line: Int,
    val platform: String,
    val headsign: String,
    val scheduled: Long,
    val delay: Int? = null,
) {
    val expected: Long get() = scheduled + (delay ?: 0)
}
