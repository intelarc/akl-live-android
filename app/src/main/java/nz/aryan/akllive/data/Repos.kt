package nz.aryan.akllive.data

import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.sqrt

private val SPACES = Regex("\\s+")

internal fun parseVehicle(e: JSONObject): Vehicle? {
    val v = e.obj("vehicle") ?: return null
    val pos = v.obj("position") ?: return null
    val trip = v.obj("trip")
    val info = v.obj("vehicle")
    val id = info?.optString("id")?.takeIf { it.isNotEmpty() } ?: e.optString("id")
    // trains (59xxx) report m/s as GTFS-realtime says, but buses report km/h:
    // both checked against how far vehicles actually moved between fixes
    val toKmh = if (id.startsWith("59")) 3.6 else 1.0
    return Vehicle(
        id = id,
        label = (info?.optString("label") ?: "").replace(SPACES, " ").trim(),
        tripId = trip?.optString("trip_id")?.takeIf { it.isNotEmpty() },
        routeId = trip?.optString("route_id")?.takeIf { it.isNotEmpty() },
        directionId = trip?.intOrNull("direction_id"),
        startDate = trip?.optString("start_date")?.takeIf { it.isNotEmpty() },
        lat = pos.optDouble("latitude"),
        lon = pos.optDouble("longitude"),
        bearing = pos.doubleOrNull("bearing")?.toFloat(),
        speedKmh = pos.doubleOrNull("speed")?.let { (it * toKmh).toFloat() },
        timestamp = v.longOrNull("timestamp") ?: 0L,
        occupancy = v.intOrNull("occupancy_status"),
    )
}

/** Realtime delay + last stop event for each trip, from tripupdates. */
internal class TripUpdate(val seq: Int?, val delay: Int?, val time: Long?, val cancelled: Boolean)

internal suspend fun tripUpdates(api: AtApi, tripIds: Collection<String>): Map<String, TripUpdate> {
    val out = HashMap<String, TripUpdate>()
    for (chunk in tripIds.distinct().chunked(30)) {
        val j = api.get("/realtime/legacy/tripupdates?tripid=" + chunk.joinToString(",")) ?: continue
        j.obj("response")?.arr("entity")?.objects { ent ->
            val tu = ent.obj("trip_update") ?: return@objects
            val trip = tu.obj("trip") ?: return@objects
            val id = trip.optString("trip_id")
            val cancelled = ent.optBoolean("is_deleted") || trip.optInt("schedule_relationship") == 3
            val stu = tu.stopTimeUpdate()
            val ev = stu?.obj("departure") ?: stu?.obj("arrival")
            out[id] = TripUpdate(
                seq = stu?.intOrNull("stop_sequence"),
                delay = ev?.intOrNull("delay") ?: tu.intOrNull("delay"),
                time = ev?.longOrNull("time"),
                cancelled = cancelled,
            )
        }
    }
    return out
}

// ======================= buses =======================

class BusRepo(private val api: AtApi) {
    private val stops = HashMap<String, JSONObject>()                  // code -> attributes
    private val schedule = HashMap<String, Pair<Long, List<BusDeparture>>>()

    suspend fun board(code: String, route: String): StopBoard {
        val attr = stops[code] ?: run {
            val j = api.get("/gtfs/v3/stops?filter%5Bstop_code%5D=$code")
                ?: throw java.io.IOException("Stop $code not found")
            j.arr("data").optJSONObject(0)?.obj("attributes")
                ?: throw java.io.IOException("Stop $code not found")
        }.also { stops[code] = it }
        val stopId = attr.getString("stop_id")
        val now = Nz.nowSec()

        val cached = schedule[code]
        val trips = if (cached == null || now - cached.first > 300 ||
                        cached.second.none { it.scheduled > now + 600 }) {
            fetchSchedule(stopId, route).also { schedule[code] = now to it }
        } else cached.second

        // realtime for everything from half an hour ago to three hours out
        val window = trips.filter { it.scheduled in (now - 1800)..(now + 3 * 3600) }
        val rt = tripUpdates(api, window.map { it.tripId }.take(30))
        val deps = window.map { d ->
            val u = rt[d.tripId] ?: return@map d
            val seq = u.seq
            val delay = when {
                seq == d.stopSeq && u.time != null -> (u.time - d.scheduled).toInt()
                else -> u.delay ?: 0
            }
            d.copy(delay = delay, live = true, cancelled = u.cancelled,
                   gone = seq != null && seq > d.stopSeq, vehicleSeq = seq)
        }
        // where the approaching buses are right now
        val coming = deps.filter { it.live && !it.gone && !it.cancelled }.map { it.tripId }
        val vehicles = vehiclesFor(coming)
        val shown = deps.map { it.copy(vehicle = vehicles[it.tripId]) }
            .filter { !it.gone && it.expected >= now - 45 }
            .sortedBy { it.expected }
        return StopBoard(
            code = code,
            name = attr.optString("stop_name"),
            lat = attr.optDouble("stop_lat"),
            lon = attr.optDouble("stop_lon"),
            route = shown.firstOrNull()?.route ?: route,
            headsign = shown.firstOrNull()?.headsign ?: trips.firstOrNull()?.headsign ?: "",
            departures = shown,
            updated = now,
        )
    }

    private suspend fun fetchSchedule(stopId: String, route: String): List<BusDeparture> {
        val now = Nz.now()
        val queries = mutableListOf(Nz.date(now) to maxOf(0, now.hour - 1))
        if (now.hour < 3) queries += Nz.date(now.minusDays(1)) to (now.hour + 23)
        val out = LinkedHashMap<String, BusDeparture>()
        for ((date, hour) in queries) {
            val j = api.get("/gtfs/v3/stops/$stopId/stoptrips?filter%5Bdate%5D=$date" +
                            "&filter%5Bstart_hour%5D=$hour&hour_range=4") ?: continue
            j.arr("data").objects { e ->
                val a = e.obj("attributes") ?: return@objects
                val r = a.optString("route_id").substringBefore("-")
                if (a.optInt("pickup_type") == 1) return@objects
                if (route.isNotBlank() && !r.equals(route, ignoreCase = true)) return@objects
                val id = a.optString("trip_id")
                out[id] = BusDeparture(
                    tripId = id, route = r,
                    headsign = cleanHeadsign(a.optString("trip_headsign").ifEmpty { a.optString("stop_headsign") }),
                    stopSeq = a.optInt("stop_sequence"),
                    scheduled = Nz.serviceEpoch(a.optString("service_date"), a.optString("departure_time")),
                )
            }
        }
        return out.values.sortedBy { it.scheduled }
    }

    private suspend fun vehiclesFor(tripIds: List<String>): Map<String, Vehicle> {
        if (tripIds.isEmpty()) return emptyMap()
        val j = api.get("/realtime/legacy/vehiclelocations?tripid=" + tripIds.take(30).joinToString(","))
            ?: return emptyMap()
        val out = HashMap<String, Vehicle>()
        j.obj("response")?.arr("entity")?.objects { e ->
            parseVehicle(e)?.let { v -> v.tripId?.let { out[it] = v } }
        }
        return out
    }
}

// ======================= every bus =======================

class LiveRepo(private val api: AtApi) {
    private val headsigns = HashMap<String, String>()

    /** Every bus on the road right now: the whole feed, about 80 KB gzipped. */
    suspend fun all(): List<LiveBus> {
        val j = api.get("/realtime/legacy/vehiclelocations") ?: return emptyList()
        val now = Nz.nowSec()
        val out = ArrayList<LiveBus>(1200)
        j.obj("response")?.arr("entity")?.objects { e ->
            // trains are 59xxx; ferries report unlabelled ship ids; parked buses stop reporting
            val v = parseVehicle(e) ?: return@objects
            if (v.id.startsWith("59") || now - v.timestamp > 900 || v.lat.isNaN()) return@objects
            val info = Fleet.info(v.label) ?: return@objects
            out += LiveBus(v, info)
        }
        return out
    }

    /** Where a trip is going and how late it is, for a tapped bus. */
    suspend fun trip(tripId: String): BusTrip {
        val head = headsigns[tripId] ?: api.get("/gtfs/v3/trips/$tripId")?.obj("data")?.obj("attributes")
            ?.optString("trip_headsign")?.let(::cleanHeadsign)?.takeIf { it.isNotEmpty() }
            ?.also { headsigns[tripId] = it }
        val delay = try { tripUpdates(api, listOf(tripId))[tripId]?.delay } catch (_: Exception) { null }
        return BusTrip(tripId, head, delay)
    }
}

// ======================= trains =======================

/** Snaps GPS fixes onto the schematic: nearest station-to-station stretch of the train's line. */
object Placement {
    private val kx = cos(Math.toRadians(36.9))
    private class S(val seg: Seg, val cum: FloatArray, val ax: Double, val ay: Double,
                    val dx: Double, val dy: Double, val l2: Double)

    private val byLine: List<List<S>> = MapData.LINE_IDS.indices.map { li ->
        MapData.SEGS.filter { it.line == li }.map { s ->
            val p = s.poly
            val n = p.size / 2
            val cum = FloatArray(n)
            for (i in 1 until n) {
                val dx = p[i * 2] - p[i * 2 - 2]
                val dy = p[i * 2 + 1] - p[i * 2 - 1]
                cum[i] = cum[i - 1] + sqrt(dx * dx + dy * dy)
            }
            val ax = s.lon0 * kx
            val ay = s.lat0
            val dx = s.lon1 * kx - ax
            val dy = s.lat1 - ay
            S(s, cum, ax, ay, dx, dy, dx * dx + dy * dy)
        }
    }

    /** Map position for a train on [line] at (lat, lon), or null if it's off the line (depot). */
    fun place(line: Int, lat: Double, lon: Double): Pair<Float, Float>? {
        val px = lon * kx
        var best: S? = null
        var bestT = 0.0
        var bd = Double.MAX_VALUE
        for (s in byLine[line]) {
            val t = if (s.l2 == 0.0) 0.0
                    else (((px - s.ax) * s.dx + (lat - s.ay) * s.dy) / s.l2).coerceIn(0.0, 1.0)
            val ex = s.ax + t * s.dx - px
            val ey = s.ay + t * s.dy - lat
            val d = ex * ex + ey * ey
            if (d < bd) { bd = d; best = s; bestT = t }
        }
        val s = best ?: return null
        if (bd > 0.015 * 0.015) return null              // >~1.5km off the line
        val p = s.seg.poly
        val goal = (bestT * s.cum.last()).toFloat()
        for (i in 0 until s.cum.size - 1) {
            if (goal <= s.cum[i + 1] || i == s.cum.size - 2) {
                val span = s.cum[i + 1] - s.cum[i]
                val f = if (span == 0f) 0f else (goal - s.cum[i]) / span
                return (p[i * 2] + (p[i * 2 + 2] - p[i * 2]) * f) to
                       (p[i * 2 + 1] + (p[i * 2 + 3] - p[i * 2 + 1]) * f)
            }
        }
        return p[p.size - 2] to p[p.size - 1]
    }
}

class TrainRepo(private val api: AtApi) {
    private var ids: String? = null
    private var discovered = 0L
    private val trips = HashMap<String, TripDetail>()
    private val platformStation: Map<String, Int> = HashMap<String, Int>().apply {
        MapData.STATIONS.forEachIndexed { i, st -> st.platforms.keys.forEach { put(it, i) } }
    }

    /** Every train on the network right now, placed on the map. */
    suspend fun poll(): List<Train> {
        val now = Nz.nowSec()
        val path = "/realtime/legacy/vehiclelocations?vehicleid="
        val j = if (ids == null || now - discovered > 1800) {
            // every AT train is an AM-class EMU with a 59xxx id
            api.get(path + (59000..59999).joinToString(",")).also { discovered = now }
        } else api.get(path + ids)
        val vehicles = ArrayList<Vehicle>()
        val seen = ArrayList<String>()
        j?.obj("response")?.arr("entity")?.objects { e ->
            parseVehicle(e)?.let { vehicles += it; seen += it.id }
        }
        if (seen.isNotEmpty()) ids = seen.sorted().joinToString(",")
        val placed = vehicles.mapNotNull { v ->
            val code = v.routeId?.substringBeforeLast("-") ?: return@mapNotNull null
            val li = MapData.LINE_IDS.indexOf(code)
            if (li < 0) return@mapNotNull null
            val (x, y) = Placement.place(li, v.lat, v.lon) ?: return@mapNotNull null
            Train(v, li, x, y)
        }
        val rt = tripUpdates(api, placed.mapNotNull { it.vehicle.tripId })
        return placed.map { t ->
            val u = t.vehicle.tripId?.let { rt[it] } ?: return@map t
            t.copy(delay = u.delay, stopSeq = u.seq)
        }
    }

    /** Where a train is going and its stops, cached per trip. */
    suspend fun trip(tripId: String, startDate: String?): TripDetail {
        trips[tripId]?.let { return it }
        val t = api.get("/gtfs/v3/trips/$tripId")?.obj("data")?.obj("attributes")
        val headsign = cleanHeadsign(t?.optString("trip_headsign") ?: "")
        val date = startDate ?: Nz.date(Nz.now())
        val stops = ArrayList<TripStop>()
        api.get("/gtfs/v3/trips/$tripId/stoptimes")?.arr("data")?.objects { e ->
            val a = e.obj("attributes") ?: return@objects
            val st = platformStation[a.optString("stop_id")]
            stops += TripStop(
                seq = a.optInt("stop_sequence"),
                station = st,
                name = st?.let { MapData.STATIONS[it].name } ?: a.optString("stop_headsign"),
                scheduled = Nz.serviceEpoch(date, a.optString("departure_time").ifEmpty { a.optString("arrival_time") }),
            )
        }
        stops.sortBy { it.seq }
        return TripDetail(tripId, headsign, stops).also { trips[tripId] = it }
    }

    /** The next trains from every platform of a station, with live delays. */
    suspend fun departures(station: Int): List<StationDeparture> {
        val st = MapData.STATIONS[station]
        val now = Nz.now()
        val nowSec = Nz.nowSec()
        val rows = ArrayList<Pair<StationDeparture, Int>>()          // departure, stop sequence
        for ((stopId, platform) in st.platforms) {
            val j = api.get("/gtfs/v3/stops/$stopId/stoptrips?filter%5Bdate%5D=${Nz.date(now)}" +
                            "&filter%5Bstart_hour%5D=${now.hour}&hour_range=2") ?: continue
            j.arr("data").objects { e ->
                val a = e.obj("attributes") ?: return@objects
                if (a.optInt("pickup_type") == 1) return@objects      // arrivals that terminate here
                val li = MapData.LINE_IDS.indexOf(a.optString("route_id").substringBeforeLast("-"))
                if (li < 0) return@objects
                val sched = Nz.serviceEpoch(a.optString("service_date"), a.optString("departure_time"))
                if (sched < nowSec - 1800) return@objects
                rows += StationDeparture(a.optString("trip_id"), li, platform,
                                         cleanHeadsign(a.optString("trip_headsign")), sched) to
                        a.optInt("stop_sequence")
            }
        }
        val soon = rows.sortedBy { it.first.scheduled }.take(30)
        val rt = tripUpdates(api, soon.map { it.first.tripId })
        return soon.map { (d, seq) ->
            val u = rt[d.tripId] ?: return@map d
            val delay = if (u.seq == seq && u.time != null) (u.time - d.scheduled).toInt() else u.delay
            d.copy(delay = delay)
        }.filter { it.expected >= nowSec - 30 }.sortedBy { it.expected }.take(14)
    }
}
