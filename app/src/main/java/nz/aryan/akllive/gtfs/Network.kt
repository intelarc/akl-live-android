package nz.aryan.akllive.gtfs

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * AT's whole timetable compiled for one service day: routes, stops, the trips
 * running (including yesterday's that run past midnight), grouped into
 * patterns (trips of a route with the same stops) for RAPTOR, plus walking
 * links between nearby stops and the shapes rides follow on the map.
 *
 * Times are seconds after the service day's midnight. Everything is flat
 * arrays, so a day loads from disk in a blink.
 */
class Network(
    val key: String,
    val date: String,                       // yyyyMMdd
    // routes
    val routeId: Array<String>,
    val routeShort: Array<String>,
    val routeLong: Array<String>,
    val routeType: IntArray,                // GTFS route_type: 2 train, 3 bus, 4 ferry
    val routeAgency: Array<String>,
    // stops
    val stopId: Array<String>,
    val stopCode: Array<String>,
    val stopName: Array<String>,
    val stopPlat: Array<String>,
    val stopLat: DoubleArray,
    val stopLon: DoubleArray,
    val stopType: IntArray,                 // 0 stop/platform, 1 station
    val stopParent: IntArray,               // -1 if none
    val stopModes: IntArray,                // 1 bus, 2 train, 4 ferry
    // trips (one per service instance)
    val tripId: Array<String>,
    val tripRoute: IntArray,
    val tripHead: Array<String>,
    val tripDir: IntArray,
    val tripOff: IntArray,                  // 0 today, -86400 yesterday's
    val tripPat: IntArray,
    val tripPos: IntArray,
    val tripShapeIdx: IntArray,
    val seqStart: IntArray,
    val seqs: IntArray,                     // stop_sequence numbers, as realtime uses them
    // patterns
    val patRoute: IntArray,
    val patStart: IntArray,
    val patLen: IntArray,
    val patStops: IntArray,
    val patPick: ByteArray,                 // 1 = can board here
    val patDrop: ByteArray,                 // 1 = can get off here
    val patTripStart: IntArray,
    val patTrip: IntArray,
    val patTimeStart: IntArray,
    val arr: IntArray,
    val dep: IntArray,
    // stop -> patterns through it
    val spStart: IntArray,
    val spPat: IntArray,
    val spPos: IntArray,
    // walking links
    val fpStart: IntArray,
    val fpTo: IntArray,
    val fpDist: IntArray,
    // shapes: lon, lat pairs
    val shapeStart: IntArray,
    val shapeXY: FloatArray,
) {
    val nStops get() = stopId.size
    val nameLower: Array<String> = Array(stopName.size) { stopName[it].lowercase() }
    private val byId: HashMap<String, Int> by lazy { HashMap<String, Int>(nStops * 2).apply { stopId.forEachIndexed { i, s -> put(s, i) } } }
    private val byCode: HashMap<String, Int> by lazy {
        HashMap<String, Int>(nStops * 2).apply { stopCode.forEachIndexed { i, s -> if (s.isNotEmpty() && !containsKey(s)) put(s, i) } }
    }
    private val tripIdx: HashMap<String, Int> by lazy { HashMap<String, Int>(tripId.size * 2).apply { tripId.forEachIndexed { i, s -> if (!containsKey(s) || tripOff[i] == 0) put(s, i) } } }

    fun stopIndex(idOrCode: String): Int = byId[idOrCode] ?: byCode[idOrCode] ?: -1
    fun tripIndex(id: String): Int = tripIdx[id] ?: -1

    fun tripAt(p: Int, j: Int) = patTrip[patTripStart[p] + j]
    fun depAt(p: Int, j: Int, k: Int) = dep[patTimeStart[p] + j * patLen[p] + k]
    fun arrAt(p: Int, j: Int, k: Int) = arr[patTimeStart[p] + j * patLen[p] + k]
    fun served(s: Int) = spStart[s + 1] > spStart[s]

    fun save(file: File) {
        val tmp = File(file.path + ".part")
        DataOutputStream(BufferedOutputStream(FileOutputStream(tmp), 1 shl 16)).use { o ->
            o.writeInt(MAGIC)
            o.writeUTF(key); o.writeUTF(date)
            for (a in listOf(routeId, routeShort, routeLong, routeAgency, stopId, stopCode, stopName, stopPlat, tripId, tripHead)) wStrings(o, a)
            for (a in listOf(routeType, stopType, stopParent, stopModes, tripRoute, tripDir, tripOff, tripPat, tripPos,
                             tripShapeIdx, seqStart, seqs, patRoute, patStart, patLen, patStops, patTripStart, patTrip,
                             patTimeStart, arr, dep, spStart, spPat, spPos, fpStart, fpTo, fpDist, shapeStart)) wInts(o, a)
            wDoubles(o, stopLat); wDoubles(o, stopLon)
            wBytes(o, patPick); wBytes(o, patDrop)
            o.writeInt(shapeXY.size)
            for (v in shapeXY) o.writeFloat(v)
        }
        file.delete()
        tmp.renameTo(file)
    }

    companion object {
        private const val MAGIC = 0x414B4C31      // "AKL1"

        fun load(file: File): Network = DataInputStream(BufferedInputStream(FileInputStream(file), 1 shl 16)).use { i ->
            require(i.readInt() == MAGIC) { "not a timetable file" }
            val key = i.readUTF()
            val date = i.readUTF()
            val s = List(10) { rStrings(i) }
            val n = List(28) { rInts(i) }
            val lat = rDoubles(i)
            val lon = rDoubles(i)
            val pick = rBytes(i)
            val drop = rBytes(i)
            val xy = FloatArray(i.readInt()) { i.readFloat() }
            Network(key, date, s[0], s[1], s[2], n[0], s[3], s[4], s[5], s[6], s[7], lat, lon, n[1], n[2], n[3],
                    s[8], n[4], s[9], n[5], n[6], n[7], n[8], n[9], n[10], n[11],
                    n[12], n[13], n[14], n[15], pick, drop, n[16], n[17], n[18], n[19], n[20],
                    n[21], n[22], n[23], n[24], n[25], n[26], n[27], xy)
        }

        private fun wStrings(o: DataOutputStream, a: Array<String>) { o.writeInt(a.size); for (x in a) o.writeUTF(x) }
        private fun wInts(o: DataOutputStream, a: IntArray) { o.writeInt(a.size); for (x in a) o.writeInt(x) }
        private fun wDoubles(o: DataOutputStream, a: DoubleArray) { o.writeInt(a.size); for (x in a) o.writeDouble(x) }
        private fun wBytes(o: DataOutputStream, a: ByteArray) { o.writeInt(a.size); o.write(a) }
        private fun rStrings(i: DataInputStream) = Array(i.readInt()) { i.readUTF() }
        private fun rInts(i: DataInputStream) = IntArray(i.readInt()) { i.readInt() }
        private fun rDoubles(i: DataInputStream) = DoubleArray(i.readInt()) { i.readDouble() }
        private fun rBytes(i: DataInputStream) = ByteArray(i.readInt()).also { i.readFully(it) }
    }
}

// ======================= what the screens get =======================

enum class Mode { Walk, Bus, Train, Ferry;
    companion object {
        fun of(routeType: Int) = when (routeType) { 2 -> Train; 4 -> Ferry; else -> Bus }
    }
}

data class StopInfo(
    val idx: Int, val id: String, val code: String, val name: String,
    val lat: Double, val lon: Double, val platform: String, val parent: String,
)

/** A stop in a search, a nearby list, or a station's platforms. */
data class StopHit(
    val stop: StopInfo,
    val station: Boolean,
    val modes: Int,
    val routes: List<String>,
    val dist: Int = 0,
)

data class RouteAt(val short: String, val type: Int, val trips: Int)

data class StopDetail(
    val stop: StopInfo,
    val station: Boolean,
    val modes: Int,
    val routes: List<RouteAt>,
    val platforms: List<StopHit>,
)

/** A stop along a ride, with its times (epoch seconds) and realtime's stop sequence. */
data class LegStop(val stop: StopInfo, val arr: Long, val dep: Long, val seq: Int)

sealed interface Leg {
    val start: Long
    val end: Long
}

data class WalkLeg(
    val fromName: String, val fromLat: Double, val fromLon: Double,
    val toName: String, val toLat: Double, val toLon: Double,
    val fromStop: StopInfo?, val toStop: StopInfo?,
    override val start: Long, override val end: Long,
    val dist: Int,
) : Leg

data class RideLeg(
    val mode: Mode,
    val routeId: String, val route: String, val routeLong: String, val routeType: Int, val agency: String,
    val tripId: String, val headsign: String,
    /** midnight of the trip's own service day (yesterday's trips run past midnight) */
    val serviceDay: Long,
    val stops: List<LegStop>,
    override val start: Long, override val end: Long,
    /** the path the ride follows, as (lat, lon) */
    val shape: List<Pair<Double, Double>>,
) : Leg {
    val from get() = stops.first()
    val to get() = stops.last()
}

data class Itinerary(val start: Long, val end: Long, val walk: Int, val transfers: Int, val legs: List<Leg>) {
    val rides get() = legs.filterIsInstance<RideLeg>()
    /** when to leave: the first ride, less the walk to it */
    val leave get() = start
}

data class PlanResult(val itineraries: List<Itinerary>, val direct: Int, val walkOnly: Int?, val note: String? = null)

data class GeoPoint(val name: String, val lat: Double, val lon: Double)

data class RaptorOpts(
    val maxWalk: Int = 900,
    val walkSpeed: Double = 1.3,
    val bus: Boolean = true,
    val train: Boolean = true,
    val ferry: Boolean = true,
    val maxRides: Int = 4,
    val change: Int = 90,
)

data class RouteSummary(val short: String, val type: Int, val long: String, val trips: Int)

data class RouteDir(val dir: Int, val trips: Int, val headsign: String, val stops: List<StopInfo>)

data class RouteInfo(
    val short: String, val long: String, val type: Int, val agency: String,
    val trips: Int, val first: Int, val last: Int,
    val dirs: List<RouteDir>,
    /** each distinct path, as (lat, lon) */
    val lines: List<List<Pair<Double, Double>>>,
)

data class TripInfo(
    val tripId: String, val route: String, val type: Int, val headsign: String,
    /** seconds after the service day's midnight */
    val stops: List<Triple<StopInfo, Int, Int>>,
    val seqs: List<Int>,
    val offset: Int,
    val shape: List<Pair<Double, Double>>,
)

/** Every served stop, for the map layer. */
data class StopDot(val id: String, val code: String, val name: String, val lat: Double, val lon: Double, val modes: Int, val station: Boolean)
