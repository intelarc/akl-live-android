package nz.aryan.akllive.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nz.aryan.akllive.Place
import nz.aryan.akllive.PlaceKind
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Plain GET for the open services (Photon, OSRM); they ask for a proper User-Agent. */
internal suspend fun httpJson(url: String): Any? = withContext(Dispatchers.IO) {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 12_000
    c.readTimeout = 20_000
    c.setRequestProperty("User-Agent", "AKL-Live-Android/1.0 (github.com/intelarc/akl-live-android)")
    c.setRequestProperty("Accept", "application/json")
    try {
        if (c.responseCode != 200) return@withContext null
        val text = c.inputStream.bufferedReader().use { it.readText() }
        if (text.trimStart().startsWith("[")) JSONArray(text) else JSONObject(text)
    } finally {
        c.disconnect()
    }
}

// ======================= service alerts =======================

enum class AlertKind(val label: String) {
    NoService("No service"), Reduced("Reduced service"), Delays("Delays"), Detour("Detour"),
    Extra("Extra service"), Changed("Changed service"), StopMoved("Stop moved"), Other("Heads up"),
}

/** One of AT's disruptions: detours, moved stops, no service. */
data class Alert(
    val id: String,
    val header: String,
    val description: String,
    val kind: AlertKind,
    val cause: String,
    val url: String,
    val active: Boolean,
    val start: Long,
    /** Long.MAX_VALUE: until further notice */
    val end: Long,
    val routes: List<String>,
    val stopIds: List<String>,
) {
    val stopCodes get() = stopIds.map { it.substringBefore('-') }

    fun touches(routeSet: Set<String>, stopSet: Set<String>) =
        routes.any { it in routeSet } || stopIds.any { it in stopSet } || stopCodes.any { it in stopSet }

    fun whenText(): String {
        val now = Nz.nowSec()
        fun day(t: Long) = Nz.at(t).let { "${it.dayOfWeek.name.take(3).lowercase().replaceFirstChar { c -> c.uppercase() }} ${it.dayOfMonth}" }
        val until = if (end == Long.MAX_VALUE) "until further notice"
                    else "until ${Nz.time(end)}" + if (day(end) != day(now)) " ${day(end)}" else ""
        return if (active) "Now, $until" else "From ${day(start)} ${Nz.time(start)}"
    }
}

object AlertsRepo {
    // AT's text arrives as UTF-8 read as Windows-1252 ("â€¢" for "•"): put it back
    private val CP1252 = mapOf(0x20AC to 0x80, 0x201A to 0x82, 0x0192 to 0x83, 0x201E to 0x84, 0x2026 to 0x85,
        0x2020 to 0x86, 0x2021 to 0x87, 0x02C6 to 0x88, 0x2030 to 0x89, 0x0160 to 0x8A, 0x2039 to 0x8B, 0x0152 to 0x8C,
        0x017D to 0x8E, 0x2018 to 0x91, 0x2019 to 0x92, 0x201C to 0x93, 0x201D to 0x94, 0x2022 to 0x95, 0x2013 to 0x96,
        0x2014 to 0x97, 0x02DC to 0x98, 0x2122 to 0x99, 0x0161 to 0x9A, 0x203A to 0x9B, 0x0153 to 0x9C, 0x017E to 0x9E,
        0x0178 to 0x9F)
    private val MOJIBAKE = Regex("[ÂÃâ][\\u0080-\\u20FF]")

    internal fun fix(s: String): String {
        if (!MOJIBAKE.containsMatchIn(s)) return s
        val bytes = ArrayList<Byte>()
        var i = 0
        while (i < s.length) {
            val c = s.codePointAt(i)
            i += Character.charCount(c)
            when {
                c < 0x100 -> bytes += c.toByte()
                CP1252.containsKey(c) -> bytes += CP1252.getValue(c).toByte()
                else -> return s
            }
        }
        val out = String(bytes.toByteArray(), Charsets.UTF_8)
        return if (out.contains('�')) s else out
    }

    private fun text(t: JSONObject?): String {
        val tr = t?.optJSONArray("translation") ?: return ""
        for (i in 0 until tr.length()) tr.optJSONObject(i)?.optString("text")?.takeIf { it.isNotBlank() }?.let { return fix(it) }
        return ""
    }

    private fun kind(effect: String) = when (effect) {
        "NO_SERVICE" -> AlertKind.NoService
        "REDUCED_SERVICE" -> AlertKind.Reduced
        "SIGNIFICANT_DELAYS" -> AlertKind.Delays
        "DETOUR" -> AlertKind.Detour
        "ADDITIONAL_SERVICE" -> AlertKind.Extra
        "MODIFIED_SERVICE" -> AlertKind.Changed
        "STOP_MOVED" -> AlertKind.StopMoved
        else -> AlertKind.Other
    }

    /** "27H-203" -> "27H", "E-W-201" -> "E-W" */
    fun routeShort(id: String): String = id.lastIndexOf('-').let { if (it > 0) id.substring(0, it) else id }

    suspend fun fetch(api: AtApi): List<Alert> {
        val j = api.get("/realtime/legacy/servicealerts") ?: return emptyList()
        val now = Nz.nowSec()
        val out = ArrayList<Alert>()
        j.obj("response")?.arr("entity")?.objects { e ->
            val a = e.obj("alert") ?: return@objects
            val periods = ArrayList<Pair<Long, Long>>()
            a.arr("active_period").objects { p ->
                periods += (p.optLong("start", 0)) to (p.optLong("end", 0).takeIf { it > 0 } ?: Long.MAX_VALUE)
            }
            val current = periods.firstOrNull { it.first <= now && now <= it.second }
            val upcoming = periods.filter { it.first > now }.minByOrNull { it.first }
            if (periods.isNotEmpty() && current == null && upcoming == null) return@objects       // all in the past
            val routes = LinkedHashSet<String>()
            val stops = LinkedHashSet<String>()
            a.arr("informed_entity").objects { ie ->
                ie.optString("route_id").takeIf { it.isNotEmpty() }?.let { routes += routeShort(it) }
                ie.optString("stop_id").takeIf { it.isNotEmpty() }?.let { stops += it }
                ie.obj("trip")?.optString("route_id")?.takeIf { it.isNotEmpty() }?.let { routes += routeShort(it) }
            }
            val period = current ?: upcoming
            out += Alert(
                id = e.optString("id"),
                header = text(a.obj("header_text")),
                description = text(a.obj("description_text")),
                kind = kind(a.optString("effect")),
                cause = a.optString("cause"),
                url = text(a.obj("url")),
                active = periods.isEmpty() || current != null,
                start = period?.first ?: 0,
                end = period?.second ?: Long.MAX_VALUE,
                routes = routes.sortedWith { x, y -> nz.aryan.akllive.gtfs.RouteOrder.compare(x, y) },
                stopIds = stops.toList(),
            )
        }
        // what's happening now first, then the soonest
        return out.sortedWith(compareByDescending<Alert> { it.active }.thenBy { it.start })
    }
}

// ======================= places and walking =======================

/** Walking directions: the path, and turn by turn. */
data class WalkRoute(val path: List<Pair<Double, Double>>, val dist: Double?, val secs: Double?,
                     val steps: List<Pair<String, Double>>, val straight: Boolean = false)

object Places {
    private const val BBOX = "174.2,-37.45,175.35,-36.2"        // greater Auckland
    private val cache = HashMap<String, Any>()

    private fun label(f: JSONObject, lat: Double, lon: Double): Place {
        val pr = f.optJSONObject("properties") ?: JSONObject()
        val hn = pr.optString("housenumber"); val street = pr.optString("street")
        val addr = listOf(hn, street).filter { it.isNotEmpty() }.joinToString(" ")
        val name = pr.optString("name").ifEmpty { addr.ifEmpty { street.ifEmpty { "Unnamed place" } } }
        val where = listOf(if (pr.optString("name").isNotEmpty()) addr else "",
                           pr.optString("district").ifEmpty { pr.optString("locality") },
                           pr.optString("city").takeIf { it != "Auckland" } ?: "")
            .filter { it.isNotEmpty() && it != name }.distinct()
        return Place(name, where.joinToString(", ").ifEmpty { "Auckland" }, lat, lon, PlaceKind.Place)
    }

    /** Addresses and places matching the text, Auckland first. */
    @Suppress("UNCHECKED_CAST")
    suspend fun search(q: String): List<Place> {
        val t = q.trim()
        if (t.length < 3) return emptyList()
        (cache["s:$t"] as? List<Place>)?.let { return it }
        val j = httpJson("https://photon.komoot.io/api/?q=${URLEncoder.encode(t, "UTF-8")}&lat=-36.87&lon=174.76" +
                         "&limit=8&bbox=$BBOX&lang=en") as? JSONObject ?: return emptyList()
        val out = ArrayList<Place>()
        j.arr("features").objects { f ->
            val c = f.obj("geometry")?.optJSONArray("coordinates") ?: return@objects
            out += label(f, c.optDouble(1), c.optDouble(0))
        }
        cache["s:$t"] = out
        return out
    }

    /** What's at a point (a long-press on the map). */
    suspend fun reverse(lat: Double, lon: Double): Place {
        try {
            val j = httpJson("https://photon.komoot.io/reverse?lat=$lat&lon=$lon&limit=1&lang=en") as? JSONObject
            val f = j?.arr("features")?.optJSONObject(0)
            if (f != null) return label(f, lat, lon).copy(kind = PlaceKind.Pin)
        } catch (_: Exception) { }
        return Place("Dropped pin", "%.5f, %.5f".format(lat, lon), lat, lon, PlaceKind.Pin)
    }

    /** Walking directions between two points (FOSSGIS OSRM, OpenStreetMap). */
    suspend fun walk(aLat: Double, aLon: Double, bLat: Double, bLon: Double): WalkRoute {
        val key = "w:" + listOf(aLon, aLat, bLon, bLat).joinToString(",") { "%.5f".format(it) }
        (cache[key] as? WalkRoute)?.let { return it }
        val straight = WalkRoute(listOf(aLat to aLon, bLat to bLon), null, null, emptyList(), straight = true)
        val r = try {
            val j = httpJson("https://routing.openstreetmap.de/routed-foot/route/v1/driving/$aLon,$aLat;$bLon,$bLat" +
                             "?overview=full&geometries=geojson&steps=true") as? JSONObject
            val route = j?.optJSONArray("routes")?.optJSONObject(0) ?: return straight
            val coords = route.obj("geometry")?.optJSONArray("coordinates") ?: JSONArray()
            val path = (0 until coords.length()).map { coords.getJSONArray(it).let { p -> p.getDouble(1) to p.getDouble(0) } }
            val steps = ArrayList<Pair<String, Double>>()
            route.arr("legs").optJSONObject(0)?.arr("steps")?.objects { s ->
                val m = s.obj("maneuver") ?: JSONObject()
                val name = s.optString("name")
                val type = m.optString("type")
                val mod = m.optString("modifier")
                val t = when {
                    type == "depart" -> "Head ${compass(m.optDouble("bearing_after", 0.0))}" + if (name.isNotEmpty()) " on $name" else ""
                    type == "arrive" -> "Arrive"
                    mod == "straight" || type == "continue" || type == "new name" -> "Continue" + if (name.isNotEmpty()) " along $name" else ""
                    mod.isNotEmpty() -> "Turn ${mod.replace("slight ", "slightly ")}" + if (name.isNotEmpty()) " onto $name" else ""
                    else -> "Continue" + if (name.isNotEmpty()) " onto $name" else ""
                }
                val d = s.optDouble("distance", 0.0)
                if (steps.isNotEmpty() && steps.last().first == t) steps[steps.size - 1] = t to (steps.last().second + d)
                else steps += t to d
            }
            WalkRoute(path, route.optDouble("distance"), route.optDouble("duration"), steps)
        } catch (_: Exception) {
            straight
        }
        cache[key] = r
        return r
    }

    private fun compass(deg: Double) =
        listOf("north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west")[(Math.round(deg / 45) % 8).toInt()]
}

// ======================= departures from any stop =======================

/** A departure from any stop or station platform. */
data class StopDeparture(
    val tripId: String,
    val route: String,
    val routeId: String,
    val headsign: String,
    val platform: String,
    val stopId: String,
    val stopSeq: Int,
    val scheduled: Long,
    val delay: Int = 0,
    val live: Boolean = false,
    val cancelled: Boolean = false,
    val vehicleSeq: Int? = null,
    val vehicle: Vehicle? = null,
) {
    val expected: Long get() = scheduled + delay
    val stopsAway: Int? get() = vehicleSeq?.let { stopSeq - it }?.takeIf { it >= 0 }
    /** as the home screen's bus boards have them, for the shared rows and tracking */
    fun asBus() = BusDeparture(tripId, route, headsign, stopSeq, scheduled, delay, live, cancelled, false, vehicleSeq, vehicle)
}

class StopRepo(private val api: AtApi) {
    private val ids = HashMap<String, String>()

    /** A stop's id from the number on its sign ("8669"), for before the timetable has loaded. */
    private suspend fun idOf(key: String): String {
        if (!key.all { it.isDigit() }) return key
        ids[key]?.let { return it }
        val id = api.get("/gtfs/v3/stops?filter%5Bstop_code%5D=$key")?.arr("data")?.optJSONObject(0)
            ?.obj("attributes")?.optString("stop_id")?.takeIf { it.isNotEmpty() } ?: key
        ids[key] = id
        return id
    }

    /** Departures in the next few hours from these stops (a station's platforms: stop id -> platform). */
    suspend fun departures(stops: Map<String, String>, hours: Int = 3): List<StopDeparture> {
        val now = Nz.now()
        val nowSec = Nz.nowSec()
        val rows = ArrayList<StopDeparture>()
        for ((key, platform) in stops) {
            val stopId = idOf(key)
            val queries = mutableListOf(Nz.date(now) to maxOf(0, now.hour - 1))
            if (now.hour < 3) queries += Nz.date(now.minusDays(1)) to (now.hour + 23)
            for ((date, hour) in queries) {
                val j = api.get("/gtfs/v3/stops/$stopId/stoptrips?filter%5Bdate%5D=$date" +
                                "&filter%5Bstart_hour%5D=$hour&hour_range=${hours + 1}") ?: continue
                j.arr("data").objects { e ->
                    val a = e.obj("attributes") ?: return@objects
                    if (a.optInt("pickup_type") == 1) return@objects          // arrivals that end here
                    val sched = Nz.serviceEpoch(a.optString("service_date"), a.optString("departure_time"))
                    if (sched < nowSec - 1800 || sched > nowSec + hours * 3600) return@objects
                    val routeId = a.optString("route_id")
                    rows += StopDeparture(
                        tripId = a.optString("trip_id"), route = AlertsRepo.routeShort(routeId), routeId = routeId,
                        headsign = cleanHeadsign(a.optString("trip_headsign").ifEmpty { a.optString("stop_headsign") }),
                        platform = platform, stopId = stopId, stopSeq = a.optInt("stop_sequence"), scheduled = sched,
                    )
                }
            }
        }
        val soon = rows.distinctBy { it.tripId + it.stopId }.sortedBy { it.scheduled }.take(40)
        val rt = tripUpdates(api, soon.map { it.tripId })
        val withRt = soon.map { d ->
            val u = rt[d.tripId] ?: return@map d
            val delay = if (u.seq == d.stopSeq && u.time != null) (u.time - d.scheduled).toInt() else u.delay ?: 0
            d.copy(delay = delay, live = true, cancelled = u.cancelled, vehicleSeq = u.seq)
        }.filter { it.vehicleSeq == null || it.vehicleSeq <= it.stopSeq }
        val vehicles = vehiclesFor(api, withRt.filter { it.live && !it.cancelled }.map { it.tripId })
        return withRt.map { it.copy(vehicle = vehicles[it.tripId]) }
            .filter { it.expected >= nowSec - 45 }.sortedBy { it.expected }
    }
}

/** Where these trips' vehicles are right now. */
suspend fun vehiclesFor(api: AtApi, tripIds: List<String>): Map<String, Vehicle> {
    val out = HashMap<String, Vehicle>()
    for (chunk in tripIds.distinct().chunked(30)) {
        val j = api.get("/realtime/legacy/vehiclelocations?tripid=" + chunk.joinToString(",")) ?: continue
        j.obj("response")?.arr("entity")?.objects { e ->
            parseVehicle(e)?.let { v -> v.tripId?.let { out[it] = v } }
        }
    }
    return out
}

/** Live delay and position for trips (a planned journey's rides). */
data class TripLive(val delay: Int?, val seq: Int?, val cancelled: Boolean, val vehicle: Vehicle?)

suspend fun liveFor(api: AtApi, tripIds: List<String>): Map<String, TripLive> {
    if (tripIds.isEmpty()) return emptyMap()
    val rt = tripUpdates(api, tripIds)
    val v = vehiclesFor(api, tripIds)
    return tripIds.associateWith { id -> rt[id].let { TripLive(it?.delay, it?.seq, it?.cancelled == true, v[id]) } }
}
