package nz.aryan.akllive.gtfs

import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.zip.ZipFile
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal const val DAY = 86400
private const val TO_RAD = Math.PI / 180

/** Metres between two points (flat-earth: fine across a city). */
internal fun meters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val x = (lon2 - lon1) * TO_RAD * cos((lat1 + lat2) / 2 * TO_RAD)
    val y = (lat2 - lat1) * TO_RAD
    return sqrt(x * x + y * y) * 6371000
}

/** One trip's stop events as they're read. */
private class Ev {
    var n = 0
    var s = IntArray(24); var a = IntArray(24); var d = IntArray(24); var q = IntArray(24)
    var pu = ByteArray(24); var dof = ByteArray(24)

    fun add(stop: Int, arr: Int, dep: Int, seq: Int, pickupNone: Boolean, dropNone: Boolean) {
        if (n == s.size) {
            val c = n * 2
            s = s.copyOf(c); a = a.copyOf(c); d = d.copyOf(c); q = q.copyOf(c); pu = pu.copyOf(c); dof = dof.copyOf(c)
        }
        s[n] = stop; a[n] = arr; d[n] = dep; q[n] = seq
        pu[n] = if (pickupNone) 1 else 0; dof[n] = if (dropNone) 1 else 0
        n++
    }

    /** a couple of trips list their stops out of order */
    fun sortBySeq() {
        for (k in 1 until n) if (q[k] < q[k - 1]) {
            val idx = (0 until n).sortedBy { q[it] }
            s = IntArray(n) { s[idx[it]] }; a = IntArray(n) { a[idx[it]] }; d = IntArray(n) { d[idx[it]] }
            q = IntArray(n) { q[idx[it]] }; pu = ByteArray(n) { pu[idx[it]] }; dof = ByteArray(n) { dof[idx[it]] }
            return
        }
    }
}

/** Reads AT's gtfs.zip and compiles one service day into a [Network]. */
object Compiler {
    private val YMD = DateTimeFormatter.BASIC_ISO_DATE

    fun compile(zip: File, ymd: String, key: String, progress: (String, Int) -> Unit = { _, _ -> }): Network =
        ZipFile(zip).use { z -> compile(z, ymd, key, progress) }

    private fun table(z: ZipFile, name: String, pick: List<String>): List<Array<String>> {
        val e = z.getEntry(name) ?: return emptyList()
        return z.getInputStream(e).use { ins ->
            val r = CsvReader(ins)
            if (!r.next()) return@use emptyList()
            val h = r.header()
            val idx = pick.map { h[it] ?: -1 }
            val out = ArrayList<Array<String>>()
            while (r.next()) out += Array(pick.size) { r.str(idx[it]) }
            out
        }
    }

    private fun activeServices(cal: List<Array<String>>, dates: List<Array<String>>, ymd: String): Set<String> {
        val dow = LocalDate.parse(ymd, YMD).dayOfWeek.value          // 1 Monday .. 7 Sunday
        val on = HashSet<String>()
        // cal: service_id, monday..sunday, start_date, end_date
        for (r in cal) if (r[8] <= ymd && ymd <= r[9] && r[dow] == "1") on += r[0]
        for (r in dates) if (r[1] == ymd) { if (r[2] == "1") on += r[0] else on -= r[0] }
        return on
    }

    private fun compile(z: ZipFile, ymd: String, key: String, progress: (String, Int) -> Unit): Network {
        progress("Reading the timetable…", 5)
        val cal = table(z, "calendar.txt", listOf("service_id", "monday", "tuesday", "wednesday", "thursday",
                                                   "friday", "saturday", "sunday", "start_date", "end_date"))
        val calDates = table(z, "calendar_dates.txt", listOf("service_id", "date", "exception_type"))
        val prev = LocalDate.parse(ymd, YMD).minusDays(1).format(YMD)
        val onToday = activeServices(cal, calDates, ymd)
        val onPrev = activeServices(cal, calDates, prev)

        // routes
        val routeRows = table(z, "routes.txt", listOf("route_id", "agency_id", "route_short_name", "route_long_name", "route_type"))
        if (routeRows.isEmpty()) throw IOException("The timetable has no routes")
        val routeIdx = HashMap<String, Int>()
        routeRows.forEachIndexed { i, r -> routeIdx[r[0]] = i }
        val routeId = Array(routeRows.size) { routeRows[it][0] }
        val routeShort = Array(routeRows.size) { routeRows[it][2].ifEmpty { routeRows[it][0].substringBefore('-') } }
        val routeLong = Array(routeRows.size) { routeRows[it][3] }
        val routeType = IntArray(routeRows.size) { routeRows[it][4].toIntOrNull() ?: 3 }
        val routeAgency = Array(routeRows.size) { routeRows[it][1] }

        // stops
        progress("Reading stops…", 12)
        val stopRows = table(z, "stops.txt", listOf("stop_id", "stop_code", "stop_name", "stop_lat", "stop_lon",
                                                   "location_type", "parent_station", "platform_code"))
        val nStops = stopRows.size
        val stopIdx = HashMap<String, Int>(nStops * 2)
        stopRows.forEachIndexed { i, r -> stopIdx[r[0]] = i }
        val stopParent = IntArray(nStops) { stopRows[it][6].takeIf { p -> p.isNotEmpty() }?.let { p -> stopIdx[p] } ?: -1 }

        // trips running today, and yesterday's that run past midnight
        progress("Reading trips…", 18)
        val tripRows = table(z, "trips.txt", listOf("route_id", "service_id", "trip_id", "trip_headsign", "direction_id", "shape_id"))
        val inst = HashMap<String, IntArray>(tripRows.size * 2)
        val tripId = ArrayList<String>(); val tripRoute = IntList(); val tripHead = ArrayList<String>()
        val tripDir = IntList(); val tripShape = ArrayList<String>(); val tripOff = IntList()
        for (r in tripRows) {
            val today = r[1] in onToday
            val yest = r[1] in onPrev
            if (!today && !yest) continue
            val list = IntList(2)
            for (off in intArrayOf(if (today) 0 else 1, if (yest) -DAY else 1)) {
                if (off == 1) continue
                list.add(tripId.size)
                tripId += r[2]; tripRoute.add(routeIdx[r[0]] ?: 0); tripHead += r[3]
                tripDir.add(r[4].toIntOrNull() ?: 0); tripShape += r[5]; tripOff.add(off)
            }
            inst[r[2]] = list.toArray()
        }
        val nInst = tripId.size

        // stop times, straight from bytes: the big one
        progress("Reading stop times…", 26)
        val events = arrayOfNulls<Ev>(nInst)
        run {
            val e = z.getEntry("stop_times.txt") ?: throw IOException("The timetable has no stop times")
            z.getInputStream(e).use { ins ->
                val r = CsvReader(ins)
                if (!r.next()) return@use
                val h = r.header()
                val cTrip = h["trip_id"] ?: -1; val cArr = h["arrival_time"] ?: -1; val cDep = h["departure_time"] ?: -1
                val cStop = h["stop_id"] ?: -1; val cSeq = h["stop_sequence"] ?: -1
                val cPu = h["pickup_type"] ?: -1; val cDo = h["drop_off_type"] ?: -1
                var curBytes = ByteArray(64)
                var curLen = -1
                var cur: List<Pair<Ev, Int>>? = null
                var rows = 0
                while (r.next()) {
                    if (++rows % 200_000 == 0) progress("Reading stop times… ${rows / 1000}k", (26 + rows / 40_000).coerceAtMost(60))
                    // rows come grouped by trip: only decode the id when it changes
                    if (curLen < 0 || !r.sameAs(cTrip, curBytes, curLen)) {
                        val (b, n) = r.copy(cTrip, curBytes)
                        curBytes = b; curLen = n
                        val list = inst[String(curBytes, 0, curLen, Charsets.UTF_8)]
                        cur = list?.map { i -> Ev().also { events[i] = it } to tripOff[i] }
                    }
                    val c = cur ?: continue
                    val s = stopIdx[r.str(cStop)] ?: continue
                    var a = r.hms(cArr)
                    var d = r.hms(cDep)
                    if (a < 0) a = d
                    if (d < 0) d = a
                    val q = r.int(cSeq)
                    val pu = r.digit(cPu) == 1
                    val dof = r.digit(cDo) == 1
                    for ((ev, off) in c) ev.add(s, a + off, d + off, q, pu, dof)
                }
            }
        }

        // patterns: trips of one route with the same stops (and pickup rules) share one
        progress("Building the network…", 62)
        val patKey = HashMap<String, Int>()
        val patTrips = ArrayList<IntList>()
        val sb = StringBuilder()
        for (ti in 0 until nInst) {
            val o = events[ti] ?: continue
            if (o.n < 2) continue
            if (tripOff[ti] < 0 && o.a[o.n - 1] < 0) continue          // yesterday's run, over before midnight
            if (tripOff[ti] == 0 && o.d[0] >= DAY + 4 * 3600) continue
            o.sortBySeq()
            sb.setLength(0)
            sb.append(tripRoute[ti]).append('|')
            for (k in 0 until o.n) sb.append(o.s[k]).append(',')
            sb.append('|')
            for (k in 0 until o.n) sb.append(o.pu[k].toInt()).append(o.dof[k].toInt())
            val keyS = sb.toString()
            val p = patKey.getOrPut(keyS) { patTrips.add(IntList(4)); patTrips.size - 1 }
            patTrips[p].add(ti)
        }
        val nPat = patTrips.size
        var stopsTotal = 0; var tripsTotal = 0; var timesTotal = 0
        for (p in 0 until nPat) {
            val L = events[patTrips[p][0]]!!.n
            stopsTotal += L; tripsTotal += patTrips[p].n; timesTotal += L * patTrips[p].n
        }
        val patRoute = IntArray(nPat); val patStart = IntArray(nPat + 1); val patLen = IntArray(nPat)
        val patTripStart = IntArray(nPat + 1); val patTimeStart = IntArray(nPat + 1)
        val patStops = IntArray(stopsTotal); val patPick = ByteArray(stopsTotal); val patDrop = ByteArray(stopsTotal)
        val patTrip = IntArray(tripsTotal)
        val arr = IntArray(timesTotal); val dep = IntArray(timesTotal)
        var ps = 0; var pt = 0; var ptime = 0
        for (p in 0 until nPat) {
            val list = patTrips[p].toArray().sortedBy { events[it]!!.d[0] }
            val first = events[list[0]]!!
            val L = first.n
            patRoute[p] = tripRoute[list[0]]
            patStart[p] = ps; patTripStart[p] = pt; patTimeStart[p] = ptime; patLen[p] = L
            for (k in 0 until L) {
                patStops[ps + k] = first.s[k]
                patPick[ps + k] = if (first.pu[k].toInt() == 1) 0 else 1
                patDrop[ps + k] = if (first.dof[k].toInt() == 1) 0 else 1
            }
            ps += L
            for (ti in list) {
                val o = events[ti]!!
                patTrip[pt++] = ti
                System.arraycopy(o.a, 0, arr, ptime, L)
                System.arraycopy(o.d, 0, dep, ptime, L)
                ptime += L
            }
        }
        patStart[nPat] = ps; patTripStart[nPat] = pt; patTimeStart[nPat] = ptime

        // trip -> (pattern, index in pattern), and the stop sequence numbers realtime uses
        val tripPat = IntArray(nInst) { -1 }
        val tripPos = IntArray(nInst) { -1 }
        for (p in 0 until nPat) for (j in patTripStart[p] until patTripStart[p + 1]) {
            tripPat[patTrip[j]] = p; tripPos[patTrip[j]] = j - patTripStart[p]
        }
        val seqStart = IntArray(nInst + 1)
        var seqTotal = 0
        for (t in 0 until nInst) {
            seqStart[t] = seqTotal
            if (tripPat[t] >= 0) seqTotal += events[t]!!.n
        }
        seqStart[nInst] = seqTotal
        val seqs = IntArray(seqTotal)
        for (t in 0 until nInst) if (tripPat[t] >= 0) System.arraycopy(events[t]!!.q, 0, seqs, seqStart[t], events[t]!!.n)

        // stop -> patterns through it
        progress("Linking stops…", 74)
        val cnt = IntArray(nStops)
        for (i in 0 until stopsTotal) cnt[patStops[i]]++
        val spStart = IntArray(nStops + 1)
        for (s in 0 until nStops) spStart[s + 1] = spStart[s] + cnt[s]
        val spPat = IntArray(stopsTotal); val spPos = IntArray(stopsTotal)
        val fill = spStart.copyOf(nStops)
        for (p in 0 until nPat) for (k in 0 until patLen[p]) {
            val s = patStops[patStart[p] + k]
            spPat[fill[s]] = p; spPos[fill[s]] = k; fill[s]++
        }
        // which modes stop where (1 bus, 2 train, 4 ferry)
        val stopModes = IntArray(nStops)
        for (p in 0 until nPat) {
            val bit = when (routeType[patRoute[p]]) { 2 -> 2; 4 -> 4; else -> 1 }
            for (k in 0 until patLen[p]) stopModes[patStops[patStart[p] + k]] = stopModes[patStops[patStart[p] + k]] or bit
        }

        val stopLat = DoubleArray(nStops) { stopRows[it][3].toDoubleOrNull() ?: 0.0 }
        val stopLon = DoubleArray(nStops) { stopRows[it][4].toDoubleOrNull() ?: 0.0 }
        val stopType = IntArray(nStops) { stopRows[it][5].toIntOrNull() ?: 0 }

        // walking between nearby stops (up to 450 m), found with a grid
        progress("Working out transfers…", 80)
        val cell = 0.005
        val grid = HashMap<Long, IntList>()
        fun gkey(gy: Long, gx: Long) = gy * 1_000_000L + gx
        for (s in 0 until nStops) if (stopType[s] == 0 && spStart[s + 1] > spStart[s]) {
            grid.getOrPut(gkey(floor(stopLat[s] / cell).toLong(), floor(stopLon[s] / cell).toLong())) { IntList(8) }.add(s)
        }
        val fpTo = IntList(nStops * 8); val fpDist = IntList(nStops * 8)
        val fpStart = IntArray(nStops + 1)
        for (s in 0 until nStops) {
            fpStart[s] = fpTo.n
            if (stopType[s] != 0 || spStart[s + 1] == spStart[s]) continue
            val gy = floor(stopLat[s] / cell).toLong()
            val gx = floor(stopLon[s] / cell).toLong()
            for (dy in -1L..1L) for (dx in -1L..1L) {
                val g = grid[gkey(gy + dy, gx + dx)] ?: continue
                for (i in 0 until g.n) {
                    val t = g[i]
                    if (t == s) continue
                    val d = meters(stopLat[s], stopLon[s], stopLat[t], stopLon[t])
                    if (d <= 450) { fpTo.add(t); fpDist.add(d.roundToInt()) }
                }
            }
        }
        fpStart[nStops] = fpTo.n

        // shapes, for drawing rides on the map (only the ones running today)
        progress("Reading route shapes…", 86)
        val want = HashMap<String, Int>()
        for (t in 0 until nInst) if (tripPat[t] >= 0) { val sid = tripShape[t]; if (sid.isNotEmpty() && sid !in want) want[sid] = want.size }
        val shapeSeq = Array(want.size) { IntList(64) }
        val shapeLon = Array(want.size) { DoubleArrayList() }
        val shapeLat = Array(want.size) { DoubleArrayList() }
        z.getEntry("shapes.txt")?.let { e ->
            z.getInputStream(e).use { ins ->
                val r = CsvReader(ins)
                if (!r.next()) return@use
                val h = r.header()
                val cId = h["shape_id"] ?: -1; val cSeq = h["shape_pt_sequence"] ?: -1
                val cLat = h["shape_pt_lat"] ?: -1; val cLon = h["shape_pt_lon"] ?: -1
                var lastBytes = ByteArray(64)
                var lastLen = -1
                var idx = -1
                while (r.next()) {
                    if (lastLen < 0 || !r.sameAs(cId, lastBytes, lastLen)) {
                        val (b, n) = r.copy(cId, lastBytes)
                        lastBytes = b; lastLen = n
                        idx = want[String(lastBytes, 0, lastLen, Charsets.UTF_8)] ?: -1
                    }
                    if (idx < 0) continue
                    shapeSeq[idx].add(r.int(cSeq)); shapeLon[idx].add(r.double(cLon)); shapeLat[idx].add(r.double(cLat))
                }
            }
        }
        var shapeTotal = 0
        for (i in 0 until want.size) shapeTotal += shapeSeq[i].n
        val shapeStart = IntArray(want.size + 1)
        val shapeXY = FloatArray(shapeTotal * 2)
        var so = 0
        for (i in 0 until want.size) {
            shapeStart[i] = so
            val order = (0 until shapeSeq[i].n).sortedBy { shapeSeq[i][it] }
            for (k in order) {
                shapeXY[so * 2] = shapeLon[i][k].toFloat(); shapeXY[so * 2 + 1] = shapeLat[i][k].toFloat(); so++
            }
        }
        shapeStart[want.size] = so
        val tripShapeIdx = IntArray(nInst) { want[tripShape[it]] ?: -1 }

        progress("Saving for next time…", 95)
        return Network(
            key = key, date = ymd,
            routeId = routeId, routeShort = routeShort, routeLong = routeLong, routeType = routeType, routeAgency = routeAgency,
            stopId = Array(nStops) { stopRows[it][0] }, stopCode = Array(nStops) { stopRows[it][1] },
            stopName = Array(nStops) { stopRows[it][2] }, stopPlat = Array(nStops) { stopRows[it][7] },
            stopLat = stopLat, stopLon = stopLon, stopType = stopType, stopParent = stopParent, stopModes = stopModes,
            tripId = tripId.toTypedArray(), tripRoute = tripRoute.toArray(), tripHead = tripHead.toTypedArray(),
            tripDir = tripDir.toArray(), tripOff = tripOff.toArray(), tripPat = tripPat, tripPos = tripPos,
            tripShapeIdx = tripShapeIdx, seqStart = seqStart, seqs = seqs,
            patRoute = patRoute, patStart = patStart, patLen = patLen, patStops = patStops, patPick = patPick, patDrop = patDrop,
            patTripStart = patTripStart, patTrip = patTrip, patTimeStart = patTimeStart, arr = arr, dep = dep,
            spStart = spStart, spPat = spPat, spPos = spPos,
            fpStart = fpStart, fpTo = fpTo.toArray(), fpDist = fpDist.toArray(),
            shapeStart = shapeStart, shapeXY = shapeXY,
        )
    }
}

internal class DoubleArrayList(cap: Int = 64) {
    private var a = DoubleArray(cap)
    var n = 0
        private set
    fun add(v: Double) {
        if (n == a.size) a = a.copyOf(a.size * 2)
        a[n++] = v
    }
    operator fun get(i: Int) = a[i]
}
