package nz.aryan.akllive.gtfs

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt

private const val INF = 0x3fffffff

/** Stops within walking distance of a point, nearest first: (stop, metres). */
fun Network.around(lat: Double, lon: Double, radius: Int): List<Pair<Int, Double>> {
    val out = ArrayList<Pair<Int, Double>>()
    val dLat = radius / 111000.0
    val dLon = radius / (111000.0 * cos(Math.toRadians(lat)))
    for (s in 0 until nStops) {
        if (stopType[s] != 0 || !served(s)) continue
        val la = stopLat[s]
        val lo = stopLon[s]
        if (abs(la - lat) > dLat || abs(lo - lon) > dLon) continue
        val d = meters(lat, lon, la, lo)
        if (d <= radius) out += s to d
    }
    return out.sortedBy { it.second }
}

/** A found journey, before it's described: legs in pattern terms. */
private class RawLeg(
    val walk: Boolean, val p: Int = -1, val j: Int = -1, val bp: Int = -1, val ap: Int = -1,
    val fromStop: Int, val toStop: Int, var start: Int, var end: Int, val dist: Int = -1,
)

private class RawIt(val rides: Int, val arrive: Int, val legs: List<RawLeg>) {
    /** when you'd actually have to leave: the first ride, less the walk to it */
    fun leaveAt(): Int {
        val i = legs.indexOfFirst { !it.walk }
        if (i < 0) return legs[0].start
        val w = if (i > 0) legs[0].end - legs[0].start else 0
        return legs[i].start - w
    }
}

/**
 * One RAPTOR run (round-based public transit routing): the best arrival at the
 * destination with 1, 2, 3... rides, leaving the origin no earlier than t0
 * (seconds after the service day's midnight).
 */
private fun Network.raptor(access: List<Pair<Int, Double>>, egress: List<Pair<Int, Double>>, t0: Int,
                           o: RaptorOpts, secPerM: Double): List<RawIt> {
    val n = nStops
    val kMax = o.maxRides
    val tau = Array(kMax + 1) { IntArray(n) { INF } }
    val pType = Array(kMax + 1) { ByteArray(n) }
    val pA = Array(kMax + 1) { IntArray(n) }
    val pB = Array(kMax + 1) { IntArray(n) }
    val pC = Array(kMax + 1) { IntArray(n) }
    val pD = Array(kMax + 1) { IntArray(n) }
    val best = IntArray(n) { INF }
    val egressAt = IntArray(n) { -1 }
    for ((s, d) in egress) egressAt[s] = (d * secPerM).roundToInt()
    var marked = IntList()
    val isMarked = BooleanArray(n)
    for ((s, d) in access) {
        val t = t0 + (d * secPerM).roundToInt()
        if (t < tau[0][s]) {
            tau[0][s] = t; best[s] = t; pType[0][s] = 1; pD[0][s] = d.roundToInt()
            if (!isMarked[s]) { isMarked[s] = true; marked.add(s) }
        }
    }
    var target = INF
    val results = ArrayList<IntArray>()               // (k, egress stop, arrival)
    val nPat = patRoute.size
    val qPos = IntArray(nPat) { -1 }
    val touched = IntList()
    fun modeOk(p: Int) = when (routeType[patRoute[p]]) { 2 -> o.train; 4 -> o.ferry; else -> o.bus }

    var k = 1
    while (k <= kMax && marked.n > 0) {
        touched.n = 0
        for (m in 0 until marked.n) {
            val s = marked[m]
            isMarked[s] = false
            for (i in spStart[s] until spStart[s + 1]) {
                val p = spPat[i]
                val pos = spPos[i]
                if (!modeOk(p)) continue
                if (qPos[p] < 0) { qPos[p] = pos; touched.add(p) } else if (pos < qPos[p]) qPos[p] = pos
            }
        }
        val next = IntList()
        val slack = if (k == 1) 0 else o.change
        for (ti in 0 until touched.n) {
            val p = touched[ti]
            val startPos = qPos[p]
            qPos[p] = -1
            val len = patLen[p]
            val nT = patTripStart[p + 1] - patTripStart[p]
            val base = patStart[p]
            var j = -1
            var boardPos = -1
            for (pos in startPos until len) {
                val s = patStops[base + pos]
                if (j >= 0 && patDrop[base + pos].toInt() == 1) {
                    val a = arrAt(p, j, pos)
                    if (a < best[s] && a < target) {
                        tau[k][s] = a; best[s] = a
                        pType[k][s] = 2; pA[k][s] = p; pB[k][s] = j; pC[k][s] = boardPos; pD[k][s] = pos
                        if (!isMarked[s]) { isMarked[s] = true; next.add(s) }
                    }
                }
                val ready = tau[k - 1][s]
                if (ready < INF && patPick[base + pos].toInt() == 1 && pType[k - 1][s].toInt() != 0) {
                    val r = ready + slack
                    if (j < 0 || r <= depAt(p, j, pos)) {
                        // earliest trip leaving here at r or later (trips are in departure order)
                        var lo = 0
                        val limit = if (j < 0) nT else j
                        var hi = limit
                        while (lo < hi) {
                            val mid = (lo + hi) ushr 1
                            if (depAt(p, mid, pos) >= r) hi = mid else lo = mid + 1
                        }
                        if (lo < limit) { j = lo; boardPos = pos }
                    }
                }
            }
        }
        // walk between nearby stops after riding
        val rode = next.toArray()
        for (s in rode) {
            val ts = tau[k][s]
            for (i in fpStart[s] until fpStart[s + 1]) {
                val t = fpTo[i]
                val d = fpDist[i]
                val a = ts + (d * secPerM).roundToInt() + 30
                if (a < best[t] && a < target) {
                    tau[k][t] = a; best[t] = a
                    pType[k][t] = 3; pA[k][t] = s; pD[k][t] = d
                    if (!isMarked[t]) { isMarked[t] = true; next.add(t) }
                }
            }
        }
        // how's the destination looking with k rides?
        var bestK = INF
        var bestE = -1
        for ((e, _) in egress) {
            if (tau[k][e] < INF) {
                val a = tau[k][e] + egressAt[e]
                if (a < bestK) { bestK = a; bestE = e }
            }
        }
        if (bestK < target) { target = bestK; results += intArrayOf(k, bestE, bestK) }
        marked = next
        k++
    }
    for (m in 0 until marked.n) isMarked[marked[m]] = false
    return results.map { (rk, e, arrive) -> build(rk, e, arrive, tau, pType, pA, pB, pC, pD, secPerM) }
}

/** Follows the parent pointers back from the destination into legs. */
private fun Network.build(rk: Int, e: Int, arrive: Int, tau: Array<IntArray>, pType: Array<ByteArray>,
                          pA: Array<IntArray>, pB: Array<IntArray>, pC: Array<IntArray>, pD: Array<IntArray>,
                          secPerM: Double): RawIt {
    val legs = ArrayList<RawLeg>()
    var s = e
    var k = rk
    legs += RawLeg(walk = true, fromStop = s, toStop = -1, start = tau[k][s], end = arrive)
    for (guard in 0 until 40) {
        when (pType[k][s].toInt()) {
            2 -> {
                val p = pA[k][s]; val j = pB[k][s]; val bp = pC[k][s]; val ap = pD[k][s]
                val from = patStops[patStart[p] + bp]
                legs.add(0, RawLeg(walk = false, p = p, j = j, bp = bp, ap = ap, fromStop = from, toStop = s,
                                   start = depAt(p, j, bp), end = arrAt(p, j, ap)))
                s = from
                k -= 1
            }
            3 -> {
                val from = pA[k][s]
                legs.add(0, RawLeg(walk = true, fromStop = from, toStop = s, start = tau[k][from], end = tau[k][s], dist = pD[k][s]))
                s = from
            }
            1 -> {
                legs.add(0, RawLeg(walk = true, fromStop = -1, toStop = s,
                                   start = tau[k][s] - (pD[k][s] * secPerM).roundToInt(), end = tau[k][s], dist = pD[k][s]))
                break
            }
            else -> break
        }
    }
    return RawIt(rk, arrive, legs)
}

/**
 * The options worth showing, best first. RAPTOR finds the earliest arrivals,
 * which at night can mean "leave at 3 am and wait three hours for the first
 * train": those go when a much shorter trip gets there about as soon.
 * Near-duplicates (same times give or take two minutes, more changes) go too.
 * Best is door-to-door time, plus four minutes a change, plus half of
 * how much later than the earliest it arrives (or earlier than the latest it
 * leaves, arriving by a time).
 */
private fun rank(found: List<RawIt>, arriveBy: Boolean): List<RawIt> {
    if (found.isEmpty()) return found
    val slack = 120
    fun dur(x: RawIt) = x.arrive - x.leaveAt()
    val minArrive = found.minOf { it.arrive }
    val maxLeave = found.maxOf { it.leaveAt() }
    fun cost(x: RawIt): Double = dur(x) + 240.0 * maxOf(0, x.rides - 1) +
        0.5 * (if (arriveBy) maxLeave - x.leaveAt() else x.arrive - minArrive)
    val order = found.withIndex().associate { (i, x) -> x to i }
    fun better(b: RawIt, a: RawIt) = cost(b) < cost(a) || (cost(b) == cost(a) && order.getValue(b) < order.getValue(a))
    val kept = found.filter { a ->
        found.none { b ->
            if (b === a) return@none false
            // as good in every way, give or take two minutes, and better overall
            val same = b.arrive <= a.arrive + slack && b.leaveAt() >= a.leaveAt() - slack && b.rides <= a.rides && better(b, a)
            // far shorter, and gets there (or leaves) within 45 minutes of it
            val wasteful = dur(a) - dur(b) >= 30 * 60 && dur(b) <= dur(a) * 0.6 &&
                (if (arriveBy) b.leaveAt() >= a.leaveAt() - 45 * 60 else b.arrive <= a.arrive + 45 * 60)
            same || wasteful
        }
    }
    return kept.sortedBy { cost(it) }
}

/**
 * Journeys from [from] to [to]. [time] is epoch seconds (leave at, or arrive
 * by); [dayStart] is the epoch second of this network's service-day midnight.
 */
fun Network.plan(from: GeoPoint, to: GeoPoint, time: Long, dayStart: Long, arriveBy: Boolean,
                 o: RaptorOpts, count: Int = 6): PlanResult {
    val secPerM = 1.28 / o.walkSpeed               // 1.28: streets aren't straight lines
    val access = around(from.lat, from.lon, o.maxWalk).take(60)
    val egress = around(to.lat, to.lon, o.maxWalk).take(60)
    val direct = meters(from.lat, from.lon, to.lat, to.lon)
    val walkOnly = if (direct <= 1500) (direct * secPerM).roundToInt() else null
    if (access.isEmpty() || egress.isEmpty()) {
        return PlanResult(emptyList(), direct.roundToInt(), walkOnly,
                          if (access.isEmpty()) "No stops within walking distance of the start"
                          else "No stops within walking distance of the end")
    }
    val found = ArrayList<RawIt>()
    val seen = HashSet<String>()
    fun addAll(list: List<RawIt>) {
        for (it in list) {
            val rides = it.legs.filter { l -> !l.walk }
            if (rides.isEmpty()) continue
            val key = rides.joinToString("|") { l -> tripId[tripAt(l.p, l.j)] + "@" + l.bp + "-" + l.ap }
            if (seen.add(key)) found += it
        }
    }
    val t = (time - dayStart).toInt()
    if (arriveBy) {
        // try leaving at steps back from the deadline; keep whatever still arrives in time
        var back = 10 * 60
        while (back <= 180 * 60 && found.size < 14) {
            addAll(raptor(access, egress, t - back, o, secPerM).filter { it.arrive <= t })
            back += if (back < 60 * 60) 4 * 60 else 10 * 60
        }
        found.sortWith(compareByDescending<RawIt> { it.leaveAt() }.thenBy { it.arrive })
    } else {
        var at = t
        // a few more than asked for: some get ranked out as wasteful
        for (i in 0 until 10) {
            if (found.size >= count + 3) break
            val res = raptor(access, egress, at, o, secPerM)
            if (res.isEmpty()) break
            addAll(res)
            val firstRide = res[0].legs.firstOrNull { !it.walk } ?: break
            at = firstRide.start - (res[0].legs[0].end - res[0].legs[0].start) + 60
        }
        found.sortWith(compareBy<RawIt> { it.arrive }.thenByDescending { it.leaveAt() })
    }
    val kept = rank(found, arriveBy)
    return PlanResult(kept.take(count).map { describe(it, from, to, dayStart) }, direct.roundToInt(), walkOnly)
}

fun Network.stopInfo(s: Int) = StopInfo(s, stopId[s], stopCode[s], stopName[s], stopLat[s], stopLon[s], stopPlat[s],
                                         if (stopParent[s] >= 0) stopName[stopParent[s]] else "")

/** A found journey in plain terms, with times as epoch seconds. */
private fun Network.describe(it: RawIt, from: GeoPoint, to: GeoPoint, day: Long): Itinerary {
    val legs = ArrayList<Leg>()
    for (l in it.legs) {
        if (l.walk) {
            val a = if (l.fromStop >= 0) stopInfo(l.fromStop) else null
            val b = if (l.toStop >= 0) stopInfo(l.toStop) else null
            val aLat = a?.lat ?: from.lat; val aLon = a?.lon ?: from.lon
            val bLat = b?.lat ?: to.lat; val bLon = b?.lon ?: to.lon
            legs += WalkLeg(a?.name ?: from.name, aLat, aLon, b?.name ?: to.name, bLat, bLon, a, b,
                            day + l.start, day + l.end,
                            if (l.dist >= 0) l.dist else meters(aLat, aLon, bLat, bLon).roundToInt())
        } else {
            val ti = tripAt(l.p, l.j)
            val r = patRoute[l.p]
            val stops = (l.bp..l.ap).map { k ->
                val s = patStops[patStart[l.p] + k]
                LegStop(stopInfo(s), day + arrAt(l.p, l.j, k), day + depAt(l.p, l.j, k), seqs[seqStart[ti] + k])
            }
            legs += RideLeg(Mode.of(routeType[r]), routeId[r], routeShort[r], routeLong[r], routeType[r], routeAgency[r],
                            tripId[ti], tripHead[ti], day + tripOff[ti], stops, day + l.start, day + l.end, cutShape(ti, stops))
        }
    }
    // two walks in a row become one
    var i = legs.size - 1
    while (i > 0) {
        val a = legs[i - 1]
        val b = legs[i]
        if (a is WalkLeg && b is WalkLeg) {
            legs[i - 1] = a.copy(toName = b.toName, toLat = b.toLat, toLon = b.toLon, toStop = b.toStop, end = b.end,
                                 dist = a.dist + b.dist)
            legs.removeAt(i)
        }
        i--
    }
    // the first walk starts when it has to for the first ride
    val first = legs[0]
    if (first is WalkLeg && legs.size > 1) {
        val dur = first.end - first.start
        legs[0] = first.copy(start = legs[1].start - dur, end = legs[1].start)
    }
    val rides = legs.count { it is RideLeg }
    return Itinerary(legs.first().start, legs.last().end, legs.filterIsInstance<WalkLeg>().sumOf { it.dist },
                     maxOf(0, rides - 1), legs)
}

/** The trip's shape between two of its stops, as (lat, lon). */
private fun Network.cutShape(ti: Int, stops: List<LegStop>): List<Pair<Double, Double>> {
    val line = stops.map { it.stop.lat to it.stop.lon }
    val si = tripShapeIdx[ti]
    if (si < 0) return line
    val a0 = shapeStart[si]
    val a1 = shapeStart[si + 1]
    fun nearest(lat: Double, lon: Double, from: Int): Int {
        var best = from
        var bd = Double.MAX_VALUE
        val k = cos(Math.toRadians(lat))
        for (i in from until a1) {
            val dx = (shapeXY[i * 2] - lon) * k
            val dy = shapeXY[i * 2 + 1] - lat
            val d = dx * dx + dy * dy
            if (d < bd) { bd = d; best = i }
            if (bd < 1e-9) break
        }
        return best
    }
    val i0 = nearest(stops.first().stop.lat, stops.first().stop.lon, a0)
    val i1 = nearest(stops.last().stop.lat, stops.last().stop.lon, i0)
    if (i1 <= i0) return line
    return (i0..i1).map { shapeXY[it * 2 + 1].toDouble() to shapeXY[it * 2].toDouble() }
}

// ======================= lookups for the screens =======================

fun Network.routesAt(s: Int): List<RouteAt> {
    val seen = LinkedHashMap<String, IntArray>()                 // short -> (type, trips)
    for (i in spStart[s] until spStart[s + 1]) {
        val p = spPat[i]
        val r = patRoute[p]
        val e = seen.getOrPut(routeShort[r]) { intArrayOf(routeType[r], 0) }
        e[1] += patTripStart[p + 1] - patTripStart[p]
    }
    return seen.map { (short, v) -> RouteAt(short, v[0], v[1]) }.sortedWith(RouteOrder.by { it.short })
}

/** 1 bus, 2 train, 4 ferry; a station's are its platforms'. */
fun Network.modesOf(i: Int): Int {
    if (stopType[i] != 1) return stopModes[i]
    var m = 0
    for (s in 0 until nStops) if (stopParent[s] == i) m = m or stopModes[s]
    return m
}

private fun Network.hit(i: Int, dist: Int = 0) =
    StopHit(stopInfo(i), stopType[i] == 1, modesOf(i),
            if (stopType[i] == 1) platformsOf(i).flatMap { routesAt(it).map { r -> r.short } }.distinct().take(12)
            else routesAt(i).map { it.short }.take(12), dist)

private fun Network.platformsOf(i: Int): List<Int> = (0 until nStops).filter { stopParent[it] == i }

fun Network.searchStops(q: String, limit: Int = 12): List<StopHit> {
    val s = q.trim().lowercase()
    if (s.isEmpty()) return emptyList()
    val words = s.split(Regex("\\s+"))
    val numeric = s.all { it.isDigit() }
    val out = ArrayList<Pair<Int, Int>>()
    for (i in 0 until nStops) {
        val station = stopType[i] == 1
        if (!station && !served(i)) continue
        if (stopType[i] > 1) continue
        var score = 0
        if (stopCode[i] == s) score = 100
        else if (numeric && stopCode[i].startsWith(s)) score = 60
        else if (words.all { nameLower[i].contains(it) }) {
            score = 30 + (if (nameLower[i].startsWith(words[0])) 20 else 0) + (if (station) 15 else 0) +
                    minOf(10, spStart[i + 1] - spStart[i])
        }
        if (score > 0) out += score to i
    }
    return out.sortedByDescending { it.first }.take(limit).map { hit(it.second) }
}

fun Network.nearby(lat: Double, lon: Double, radius: Int = 600, limit: Int = 12): List<StopHit> =
    around(lat, lon, radius).take(limit).map { (s, d) -> hit(s, d.roundToInt()) }

fun Network.stopDetail(idOrCode: String): StopDetail? {
    val s = stopIndex(idOrCode)
    if (s < 0) return null
    val station = stopType[s] == 1
    val kids = if (station) platformsOf(s).map { hit(it) } else emptyList()
    val routes = if (station) platformsOf(s).flatMap { routesAt(it) }.groupBy { it.short }
        .map { (short, rs) -> RouteAt(short, rs[0].type, rs.sumOf { it.trips }) }.sortedWith(RouteOrder.by { it.short })
    else routesAt(s)
    return StopDetail(stopInfo(s), station, modesOf(s), routes, kids)
}

fun Network.allStops(): List<StopDot> = (0 until nStops).filter { stopType[it] == 1 || (stopType[it] == 0 && served(it)) }
    .map { StopDot(stopId[it], stopCode[it], stopName[it], stopLat[it], stopLon[it], modesOf(it), stopType[it] == 1) }

fun Network.routesList(): List<RouteSummary> {
    val by = LinkedHashMap<String, RouteSummary>()
    for (p in patRoute.indices) {
        val r = patRoute[p]
        val n = patTripStart[p + 1] - patTripStart[p]
        val o = by[routeShort[r]]
        by[routeShort[r]] = o?.copy(trips = o.trips + n) ?: RouteSummary(routeShort[r], routeType[r], routeLong[r], n)
    }
    return by.values.sortedWith(RouteOrder.by { it.short })
}

/** A route's paths and main stops each way, for drawing it. */
fun Network.routeInfo(short: String): RouteInfo? {
    val pats = patRoute.indices.filter { routeShort[patRoute[it]].equals(short, ignoreCase = true) }
    if (pats.isEmpty()) return null
    val byDir = HashMap<Int, IntArray>()                         // dir -> (pattern, trips, first trip)
    for (p in pats) {
        val t0 = patTrip[patTripStart[p]]
        val n = patTripStart[p + 1] - patTripStart[p]
        val cur = byDir[tripDir[t0]]
        if (cur == null || n > cur[1]) byDir[tripDir[t0]] = intArrayOf(p, n, t0)
    }
    val shapes = HashSet<Int>()
    val lines = ArrayList<List<Pair<Double, Double>>>()
    for (p in pats) {
        val si = tripShapeIdx[patTrip[patTripStart[p]]]
        if (si < 0 || !shapes.add(si)) continue
        lines += (shapeStart[si] until shapeStart[si + 1] step 2).map { shapeXY[it * 2 + 1].toDouble() to shapeXY[it * 2].toDouble() }
    }
    val dirs = byDir.entries.sortedBy { it.key }.map { (dir, v) ->
        val p = v[0]
        RouteDir(dir, v[1], tripHead[v[2]], (0 until patLen[p]).map { stopInfo(patStops[patStart[p] + it]) })
    }
    var first = INF
    var last = -INF
    var trips = 0
    for (p in pats) {
        val nT = patTripStart[p + 1] - patTripStart[p]
        trips += nT
        first = minOf(first, depAt(p, 0, 0))
        last = maxOf(last, depAt(p, nT - 1, 0))
    }
    val r = patRoute[pats[0]]
    return RouteInfo(routeShort[r], routeLong[r], routeType[r], routeAgency[r], trips, first, last, dirs, lines)
}

/** A trip's stops and times (static), and its whole shape. */
fun Network.tripInfo(id: String): TripInfo? {
    val ti = tripIndex(id)
    if (ti < 0) return null
    val p = tripPat[ti]
    val j = tripPos[ti]
    if (p < 0) return null
    val stops = (0 until patLen[p]).map { k -> Triple(stopInfo(patStops[patStart[p] + k]), arrAt(p, j, k), depAt(p, j, k)) }
    val seq = (0 until patLen[p]).map { seqs[seqStart[ti] + it] }
    val si = tripShapeIdx[ti]
    val shape = if (si >= 0) (shapeStart[si] until shapeStart[si + 1]).map { shapeXY[it * 2 + 1].toDouble() to shapeXY[it * 2].toDouble() }
                else stops.map { it.first.lat to it.first.lon }
    val r = patRoute[p]
    return TripInfo(id, routeShort[r], routeType[r], tripHead[ti], stops, seq, tripOff[ti], shape)
}

/** "2" < "10" < "27H" < "NX1": numbers first, in number order, then the rest. */
object RouteOrder {
    fun <T> by(key: (T) -> String): Comparator<T> = Comparator { a, b -> compare(key(a), key(b)) }

    fun compare(a: String, b: String): Int {
        val na = a.takeWhile { it.isDigit() }
        val nb = b.takeWhile { it.isDigit() }
        if (na.isNotEmpty() && nb.isNotEmpty()) {
            val c = na.toInt().compareTo(nb.toInt())
            if (c != 0) return c
            return a.compareTo(b)
        }
        if (na.isNotEmpty()) return -1
        if (nb.isNotEmpty()) return 1
        return a.compareTo(b)
    }
}
