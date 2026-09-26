package nz.aryan.akllive.gtfs

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/*
 * Auckland's rail network drawn along the real tracks, as the desktop app
 * draws it: every line follows the rails its trains run on today (cut from
 * AT's shapes), lines that share rails sit side by side in their own slots
 * and ease on and off the shared track, and a station whose platforms are on
 * different tracks gets a bar joining them. Te Huia runs on to Hamilton.
 */

/** A point: longitude, latitude. */
class Pt(@JvmField val x: Double, @JvmField val y: Double)

private val KX = cos(36.9 * Math.PI / 180)
private const val M = 111320.0

fun metres(a: Pt, b: Pt) = hypot((b.x - a.x) * KX, b.y - a.y) * M

/** A station on the diagram. [lines]: bit 0 E-W, 1 S-C, 2 O-W. [extra]: not an AT station (Te Huia's own). */
class RailStation(val name: String, val lat: Double, val lon: Double, val lines: Int, val priority: Int, val extra: Boolean = false)

/** One line between two neighbouring stations. */
class RailSeg(val line: Int, val from: Int, val to: Int)

/** A stretch to cut from the real track: line id as AT's route ids have it ("E-W"), and its two ends. */
class TrackAsk(val line: String, val lon0: Double, val lat0: Double, val lon1: Double, val lat1: Double)

/** A piece of line to draw: along [coords], [width] px wide, [offset] px to the right of it (both at zoom 12). */
class RailPiece(val line: Int, val coords: MutableList<Pt>, val width: Double, val offset: Double)

class RailDrawing(
    val pieces: List<RailPiece>,
    /** where each station's dots go (several: platforms on different tracks, joined by a bar) */
    val markers: List<List<Pt>>,
    internal val track: Array<MutableList<Pair<DoubleArray, DoubleArray>>>,
) {
    /** The closest point on a line's drawn track to a GPS fix; null when it's off the line (a yard, a depot). */
    fun snap(line: Int, lon: Double, lat: Double): Pt? {
        if (line !in track.indices) return null
        val px = lon * KX
        var bx = 0.0
        var by = 0.0
        var bd = Double.MAX_VALUE
        for ((xs, ys) in track[line]) {
            for (i in 0 until xs.size - 1) {
                val dx = xs[i + 1] - xs[i]
                val dy = ys[i + 1] - ys[i]
                val l2 = dx * dx + dy * dy
                val t = if (l2 > 0) (((px - xs[i]) * dx + (lat - ys[i]) * dy) / l2).coerceIn(0.0, 1.0) else 0.0
                val ex = xs[i] + t * dx - px
                val ey = ys[i] + t * dy - lat
                val d = ex * ex + ey * ey
                if (d < bd) { bd = d; bx = xs[i] + t * dx; by = ys[i] + t * dy }
            }
        }
        if (bd == Double.MAX_VALUE || sqrt(bd) * M > 250) return null
        return Pt(bx / KX, by)
    }

    internal fun trackDist(line: Int, p: Pt): Double {
        if (line !in track.indices) return Double.MAX_VALUE
        val px = p.x * KX
        var bd = Double.MAX_VALUE
        for ((xs, ys) in track[line]) {
            for (i in 0 until xs.size - 1) {
                val dx = xs[i + 1] - xs[i]
                val dy = ys[i + 1] - ys[i]
                val l2 = dx * dx + dy * dy
                val t = if (l2 > 0) (((px - xs[i]) * dx + (p.y - ys[i]) * dy) / l2).coerceIn(0.0, 1.0) else 0.0
                val ex = xs[i] + t * dx - px
                val ey = ys[i] + t * dy - p.y
                bd = min(bd, ex * ex + ey * ey)
            }
        }
        return sqrt(bd) * M
    }
}

/** The network's geometry: each line as one route with its stations along it, and which stations are junctions or ends. */
class RailGeo internal constructor(
    val real: Boolean,
    internal val routes: List<Route>,
    val junction: BooleanArray,
    val terminus: BooleanArray,
) {
    internal class Route(val line: Int, val pts: List<Pt>, val stops: List<Pair<Int, Int>>)   // (station, point index)
}

object Rail {
    /** Te Huia (Hamilton to Auckland) is line 3, after E-W, S-C and O-W. */
    const val HUIA = 3
    const val LINE_W = 4.2
    const val HUIA_W = 3.0
    private const val GAP = 1.2
    private const val IN_M = 25.0            // rails this close and parallel are drawn as one track...
    private const val OUT_M = 40.0           // ...until they're this far apart
    private const val STEP_M = 20.0
    private const val EASE_M = 200.0           // lines slide between slots over this far either side...
    private const val EASE_STEPS = 10          // ...in this many steps

    val HUIA_STOPS = listOf(
        RailStation("The Strand", -36.84853, 174.77934, 1 shl HUIA, 1, true),
        RailStation("Huntly", -37.55787, 175.15971, 1 shl HUIA, 1, true),
        RailStation("Hamilton Rotokauri", -37.74904, 175.23015, 1 shl HUIA, 2, true),
        RailStation("Hamilton Frankton", -37.79121, 175.26538, 1 shl HUIA, 0, true),
    )
    /** from The Strand, Te Huia shares AT's rails out along the eastern line to Pukekohe */
    private val HUIA_PATH = listOf("Ōrākei", "Meadowbank", "Glen Innes", "Panmure", "Sylvia Park", "Ōtāhuhu", "Middlemore", "Papatoetoe",
                                   "Puhinui", "Homai", "Manurewa", "Te Māhia", "Takaanini", "Papakura", "Drury", "Paerātā", "Pukekohe")
    private val HUIA_SOUTH = listOf("Pukekohe", "Huntly", "Hamilton Rotokauri", "Hamilton Frankton")
    /** where Te Huia stops (it runs through the rest) */
    private val HUIA_CALL_NAMES = setOf("The Strand", "Puhinui", "Pukekohe", "Huntly", "Hamilton Rotokauri", "Hamilton Frankton")

    /** The stretches to cut from the real track, for [cut]: every segment, then Te Huia's run south of Pukekohe. */
    fun asks(stations: List<RailStation>, segs: List<RailSeg>, lineIds: List<String>): List<TrackAsk> {
        fun piece(line: String, a: Int, b: Int) = TrackAsk(line, stations[a].lon, stations[a].lat, stations[b].lon, stations[b].lat)
        val idx = { n: String -> stations.indexOfFirst { it.name == n } }
        return segs.map { piece(lineIds[it.line], it.from, it.to) } +
            HUIA_SOUTH.zipWithNext().map { (a, b) -> piece("HUIA", idx(a), idx(b)) }
    }

    /** Each line as one route along its track ([cut]: the real track per ask, null for straight lines between stations). */
    fun build(stations: List<RailStation>, segs: List<RailSeg>, cut: List<List<Pt>?>?): RailGeo {
        val idx = { n: String -> stations.indexOfFirst { it.name == n } }
        fun p(n: Int) = Pt(stations[n].lon, stations[n].lat)
        val pieces = segs.mapIndexed { i, s -> Piece(s.line, s.from, s.to, cut?.getOrNull(i) ?: listOf(p(s.from), p(s.to))) }.toMutableList()
        meet(stations.size, pieces, listOf(0, 1, 2), 25.0)
        // Te Huia: The Strand is a short branch off the eastern line; from there it runs on AT's rails to Pukekohe
        fun onRails(a: Int, b: Int): List<Pt> {
            val r = pieces.filter { (it.a == a && it.b == b) || (it.a == b && it.b == a) }.minByOrNull { it.line }
            return if (r == null) listOf(p(a), p(b)) else if (r.a == a) r.coords else r.coords.reversed()
        }
        val strand = idx("The Strand")
        val path = HUIA_PATH.map(idx)
        if (strand >= 0 && path.all { it >= 0 }) {
            val wo = pieces.firstOrNull { it.line == 0 && stations[it.a].name == "Waitematā" && stations[it.b].name == "Ōrākei" }
            var spur = listOf(p(strand), p(path[0]))
            if (wo != null && wo.coords.size > 2) {
                var k = 1
                var kd = Double.MAX_VALUE
                wo.coords.forEachIndexed { i, c ->
                    val d = metres(c, p(strand))
                    if (i > 0 && i < wo.coords.size - 1 && d < kd) { kd = d; k = i }
                }
                spur = listOf(p(strand)) + wo.coords.drop(k)
            }
            pieces += Piece(HUIA, strand, path[0], spur)
            for (i in 0 until path.size - 1) pieces += Piece(HUIA, path[i], path[i + 1], onRails(path[i], path[i + 1]))
            HUIA_SOUTH.map(idx).zipWithNext().forEachIndexed { i, (a, b) ->
                if (a >= 0 && b >= 0) pieces += Piece(HUIA, a, b, cut?.getOrNull(segs.size + i) ?: listOf(p(a), p(b)))
            }
            meet(stations.size, pieces, listOf(HUIA), 25.0)
        }
        // each line as one continuous route (in its direction of travel), and where its stations fall along it
        val routes = listOf(0, 1, 2, HUIA).map { line ->
            val pts = ArrayList<Pt>()
            val stops = ArrayList<Pair<Int, Int>>()
            for (g in pieces.filter { it.line == line }) {
                val d = densify(g.coords, STEP_M)
                if (pts.isNotEmpty() && metres(pts.last(), d[0]) < 0.5) pts.addAll(d.drop(1))
                else { stops += g.a to pts.size; pts.addAll(d) }            // a gap: another platform
                stops += g.b to pts.size - 1
            }
            RailGeo.Route(line, pts, stops)
        }
        // junctions (three ways out) and stations where one line ends but another carries on
        val nb = List(stations.size) { HashSet<Int>() }
        val lineEnds = IntArray(stations.size)
        for (g in segs) { nb[g.from] += g.to; nb[g.to] += g.from }
        for (li in 0..2) {
            val deg = HashMap<Int, Int>()
            for (g in segs) if (g.line == li) { deg[g.from] = (deg[g.from] ?: 0) + 1; deg[g.to] = (deg[g.to] ?: 0) + 1 }
            for ((st, d) in deg) if (d == 1) lineEnds[st]++
        }
        fun nLines(s: RailStation) = (0..2).count { s.lines and (1 shl it) != 0 }
        val junction = BooleanArray(stations.size) { nb[it].size >= 3 || (lineEnds[it] > 0 && nLines(stations[it]) > 1) }
        val terminus = BooleanArray(stations.size) { nb[it].size == 1 }
        return RailGeo(cut != null && cut.any { it != null }, routes, junction, terminus)
    }

    private class Piece(val line: Int, val a: Int, val b: Int, var coords: List<Pt>)

    /** Where a line's stretches meet at a station, pull ends within [m] metres to one point. */
    private fun meet(nStations: Int, pieces: List<Piece>, lines: List<Int>, m: Double) {
        class Group(var at: Pt, val ends: MutableList<Pair<Piece, Int>>)
        for (n in 0 until nStations) {
            for (li in lines) {
                val groups = ArrayList<Group>()
                for (g in pieces) {
                    if (g.line != li) continue
                    for (i in listOf(if (g.a == n) 0 else -1, if (g.b == n) g.coords.size - 1 else -1)) {
                        if (i < 0) continue
                        val p = g.coords[i]
                        val q = groups.firstOrNull { metres(it.at, p) < m }
                        if (q != null) {
                            q.ends += g to i
                            val k = q.ends.size
                            q.at = Pt((q.at.x * (k - 1) + p.x) / k, (q.at.y * (k - 1) + p.y) / k)
                        } else groups += Group(p, mutableListOf(g to i))
                    }
                }
                for (q in groups) for ((g, i) in q.ends) g.coords = g.coords.toMutableList().also { it[i] = q.at }
            }
        }
    }

    /** Points no more than [step] metres apart along a line. */
    private fun densify(c: List<Pt>, step: Double): List<Pt> {
        val out = ArrayList<Pt>(c.size * 4)
        out += c[0]
        for (i in 1 until c.size) {
            val n = max(1, ceil(metres(c[i - 1], c[i]) / step).toInt())
            for (k in 1..n) out += Pt(c[i - 1].x + (c[i].x - c[i - 1].x) * k / n, c[i - 1].y + (c[i].y - c[i - 1].y) * k / n)
        }
        return out
    }

    private class Near(val d: Double, val q: Pt, val dx: Double, val dy: Double, val gi: Int, val i: Int)
    private class Info(var set: List<Int>, var key: String, val all: Map<Int, Near>, val lat: Map<Int, Double>,
                       val dx: Double, val dy: Double)

    /**
     * Works out, all along every line, which other lines are on the same rails,
     * and cuts the lines into runs: in each run a line sits in its slot beside
     * the others (E-W, S-C, O-W, then Te Huia), drawn along the first one's
     * track. [shown]: the AT lines showing (Te Huia always is).
     */
    fun layout(stations: List<RailStation>, geo: RailGeo, shown: Set<Int>): RailDrawing {
        val lines = geo.routes.filter { it.line == HUIA || it.line in shown }
        // every little segment of track, in a grid, so finding the rails near a point is quick
        val cell = 0.0015
        val grid = HashMap<Long, MutableList<Long>>()
        fun cellKey(cx: Int, cy: Int) = (cx.toLong() shl 32) or (cy.toLong() and 0xffffffffL)
        lines.forEachIndexed { gi, g ->
            for (i in 0 until g.pts.size - 1) {
                for (p in listOf(g.pts[i], g.pts[i + 1])) {
                    val list = grid.getOrPut(cellKey(floor(p.x / cell).toInt(), floor(p.y / cell).toInt())) { ArrayList() }
                    val code = gi * 1_000_000L + i
                    if (list.lastOrNull() != code) list += code
                }
            }
        }
        fun unit(a: Pt, b: Pt): Pair<Double, Double> {
            val x = (b.x - a.x) * KX
            val y = b.y - a.y
            val l = hypot(x, y).takeIf { it > 0 } ?: 1.0
            return x / l to y / l
        }

        // 1. at every point of every line: which other lines are on the same rails, and where
        class LineInfo(val g: RailGeo.Route, val info: MutableList<Info>, val runs: List<Pair<Int, Int>>)
        val infos = lines.map { g ->
            val pts = g.pts
            val n = pts.size
            val inside = HashMap<Int, Boolean>()          // hysteresis: join under IN_M, part over OUT_M
            val info = ArrayList<Info>(n)
            for (k in 0 until n) {
                val v = pts[k]
                val (dx, dy) = unit(pts[max(0, k - 1)], pts[min(n - 1, k + 1)])
                val near = HashMap<Int, Near>()
                val cx = floor(v.x / cell).toInt()
                val cy = floor(v.y / cell).toInt()
                for (ox in -1..1) for (oy in -1..1) {
                    for (code in grid[cellKey(cx + ox, cy + oy)] ?: continue) {
                        val gi = (code / 1_000_000L).toInt()
                        val i = (code % 1_000_000L).toInt()
                        val o = lines[gi]
                        if (o.line == g.line) continue
                        val a = o.pts[i]
                        val b = o.pts[i + 1]
                        val ax = (b.x - a.x) * KX
                        val ay = b.y - a.y
                        val l2 = ax * ax + ay * ay
                        val t = if (l2 > 0) ((((v.x - a.x) * KX) * ax + (v.y - a.y) * ay) / l2).coerceIn(0.0, 1.0) else 0.0
                        val q = Pt(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
                        val d = metres(q, v)
                        if (d > OUT_M) continue
                        val (odx, ody) = unit(a, b)
                        if (abs(odx * dx + ody * dy) < 0.85) continue          // crossing, not sharing
                        val cur = near[o.line]
                        if (cur == null || d < cur.d) near[o.line] = Near(d, q, odx, ody, gi, if (t < 0.5) i else i + 1)
                    }
                }
                val all = HashMap(near)
                for (li in listOf(0, 1, 2, HUIA)) {
                    val x = near[li]
                    val on = x != null && (if (inside[li] == true) x.d <= OUT_M else x.d <= IN_M)
                    inside[li] = on
                    if (!on) near.remove(li)
                }
                // how far to the right of this line each other line is (m)
                val lat = HashMap<Int, Double>()
                for ((li, x) in near) lat[li] = (dy * (x.q.x - v.x) * KX - dx * (x.q.y - v.y)) * M
                val set = (listOf(g.line) + near.keys).sorted()
                info += Info(set, set.joinToString(","), all, lat, dx, dy)
            }
            // lines parting and meeting can flicker in and out for a few points: smooth that over
            for (k in 1 until n) {
                if (info[k].key == info[k - 1].key) continue
                var e = k
                while (e < n && info[e].key == info[k].key) e++
                if (e - k < 8 && e < n && info[e].key == info[k - 1].key) {
                    for (j in k until e) { info[j].set = info[k - 1].set; info[j].key = info[k - 1].key }
                }
            }
            // runs of the same company
            val runs = ArrayList<Pair<Int, Int>>()
            var s = 0
            for (k in 1..n) if (k == n || info[k].key != info[s].key) { runs += s to k - 1; s = k }
            LineInfo(g, info, runs)
        }

        // 2. each shared run is laid out by its first line (the reference): the others sit
        //    to its left or right in the order they really are, so lines meet and part without crossing
        val orderAt = infos.map { arrayOfNulls<List<Int>>(it.info.size) }
        infos.forEachIndexed { gi, x ->
            for ((a, b) in x.runs) {
                val set = x.info[a].set
                if (set[0] != x.g.line) continue
                // which side each line comes in from and goes out to: the first and last 160 m of the run
                val side = HashMap<Int, Double>().apply { set.forEach { put(it, 0.0) } }
                for (k in a..b) {
                    if (k - a >= 8 && b - k >= 8) continue
                    for ((li, d) in x.info[k].lat) if (li in side) side[li] = side.getValue(li) + d.coerceIn(-30.0, 30.0)
                }
                val order = set.sortedWith(compareBy<Int> { side.getValue(it) }.thenBy { it })
                for (k in a..b) orderAt[gi][k] = order
            }
        }

        // 3. every run of every line in its slot, along the reference line's track
        val out = ArrayList<RailPiece>()
        val track = Array<MutableList<Pair<DoubleArray, DoubleArray>>>(3) { ArrayList() }
        val ends = List(stations.size) { ArrayList<Pair<Int, Pt>>() }
        class Terminus(val f: RailPiece, val start: Boolean, val st: Int)
        val termini = ArrayList<Terminus>()
        infos.forEachIndexed { gi, x ->
            val g = x.g
            val info = x.info
            val first = out.size
            // along shared rails a line is drawn on the reference line's track; ease on and off it
            // over ±80 m so lines converge where they meet instead of jumping across
            val disp = info.mapIndexed { k, it ->
                val r = if (it.set[0] == g.line) null else it.all[it.set[0]]
                if (r != null) Pt(r.q.x - g.pts[k].x, r.q.y - g.pts[k].y) else Pt(0.0, 0.0)
            }
            fun moved(d: Pt) = d.x != 0.0 || d.y != 0.0
            val at = g.pts.mapIndexed { k, p ->
                val lo = max(0, k - 4)
                val hi = min(disp.size - 1, k + 4)
                if (!moved(disp[k]) && (lo..hi).none { moved(disp[it]) }) p
                else {
                    var sx = 0.0
                    var sy = 0.0
                    for (j in lo..hi) { sx += disp[j].x; sy += disp[j].y }
                    val c = hi - lo + 1
                    Pt(p.x + sx / c, p.y + sy / c)
                }
            }
            for ((a, b) in x.runs) {
                val set = info[a].set
                val mid = (a + b) / 2
                val m = info[mid]
                var order = set
                var same = true
                if (set[0] == g.line) order = orderAt[gi][mid] ?: set
                else {
                    val r = m.all[set[0]]
                    val o = r?.let { orderAt[it.gi][min(it.i, orderAt[it.gi].size - 1)] }
                    if (o != null && o.sorted().joinToString(",") == m.key) order = o
                    if (r != null) same = r.dx * m.dx + r.dy * m.dy >= 0
                }
                val slot = order.indexOf(g.line)
                if (slot < 0) continue
                val w = order.map { if (it == HUIA) HUIA_W else LINE_W }
                val total = w.sum() + GAP * (order.size - 1)
                var off = -total / 2 + w[slot] / 2
                for (j in 0 until slot) off += w[j] + GAP
                val run = at.subList(a, min(at.size, b + 2))
                if (run.size > 1) out += RailPiece(g.line, ArrayList(run), w[slot], if (same) off else -off)
            }
            // where lines meet and part, slide across between slots instead of stepping
            if (out.size - first > 1) {
                val eased = ease(out.subList(first, out.size).toList())
                while (out.size > first) out.removeAt(out.size - 1)
                out += eased
            }
            if (g.line != HUIA) track[g.line] += DoubleArray(at.size) { at[it].x * KX } to DoubleArray(at.size) { at[it].y }
            for ((st, k) in g.stops) ends[st] += g.line to at[k]
            if (out.size > first && g.stops.isNotEmpty()) {
                termini += Terminus(out[first], true, g.stops.first().first)
                termini += Terminus(out[out.size - 1], false, g.stops.last().first)
            }
        }
        val drawing = RailDrawing(out, emptyList(), track)
        val calls = stations.indices.filter { stations[it].name in HUIA_CALL_NAMES }.toSet()
        val markers = stations.indices.map { stationPoints(drawing, ends[it], it in calls, shown) }
        // a line that ends at a station runs right up to its dot
        for (t in termini) {
            val c = t.f.coords
            val end = if (t.start) c.first() else c.last()
            var best: Pt? = null
            var bd = 160.0
            for (p in markers[t.st]) { val d = metres(p, end); if (d < bd) { bd = d; best = p } }
            if (best != null && bd > 2) { if (t.start) c.add(0, best) else c.add(best) }
        }
        return RailDrawing(out, markers, track)
    }

    /**
     * A line's pieces with the change in slot (offset and width) between one and the next
     * spread over [EASE_M] either side, in [EASE_STEPS] little pieces, so a line glides
     * across to its new place beside the others where they meet or part.
     */
    private fun ease(pieces: List<RailPiece>): List<RailPiece> {
        val out = ArrayList<RailPiece>()
        var cur = pieces[0]
        for (i in 1 until pieces.size) {
            val next = pieces[i]
            val joined = metres(cur.coords.last(), next.coords.first()) < 1.0
            val change = abs(cur.offset - next.offset) > 0.05 || abs(cur.width - next.width) > 0.05
            val l = minOf(EASE_M, length(cur.coords) * 0.45, length(next.coords) * 0.45)
            if (!joined || !change || l < 5) { out += cur; cur = next; continue }
            val (keepA, tailA) = cut(cur.coords, length(cur.coords) - l)
            val (headB, keepB) = cut(next.coords, l)
            if (keepA.size > 1) out += RailPiece(cur.line, keepA, cur.width, cur.offset)
            var rest: List<Pt> = tailA + headB.drop(1)
            val step = length(rest) / EASE_STEPS
            for (j in 0 until EASE_STEPS) {
                val t = (j + 0.5) / EASE_STEPS
                val split: Pair<List<Pt>, List<Pt>> = if (j == EASE_STEPS - 1) rest to emptyList() else cut(rest, step)
                val (part, after) = split
                rest = after
                if (part.size > 1) out += RailPiece(cur.line, ArrayList(part), cur.width + (next.width - cur.width) * t,
                                                    cur.offset + (next.offset - cur.offset) * t)
            }
            cur = RailPiece(next.line, ArrayList(keepB), next.width, next.offset)
        }
        out += cur
        return out
    }

    private fun length(pts: List<Pt>): Double {
        var d = 0.0
        for (i in 1 until pts.size) d += metres(pts[i - 1], pts[i])
        return d
    }

    /** A line cut [at] metres along it: the part before and the part after, both with the cut point. */
    private fun cut(pts: List<Pt>, at: Double): Pair<MutableList<Pt>, MutableList<Pt>> {
        val before = arrayListOf(pts[0])
        var d = 0.0
        for (i in 1 until pts.size) {
            val seg = metres(pts[i - 1], pts[i])
            if (d + seg >= at) {
                val t = if (seg > 0) ((at - d) / seg).coerceIn(0.0, 1.0) else 0.0
                val c = Pt(pts[i - 1].x + (pts[i].x - pts[i - 1].x) * t, pts[i - 1].y + (pts[i].y - pts[i - 1].y) * t)
                before += c
                val after = arrayListOf(c)
                for (j in i until pts.size) after += pts[j]
                return before to after
            }
            d += seg
            before += pts[i]
        }
        return before to arrayListOf(pts.last())
    }

    /**
     * Where a station's dot (or dots) go: its platforms on the lines showing. A
     * line's own ends there count once; lines on the same track share a dot.
     * Several dots means the lines use different tracks there, and a bar joins them.
     */
    private fun stationPoints(d: RailDrawing, ends: List<Pair<Int, Pt>>, huiaCalls: Boolean, shown: Set<Int>): List<Pt> {
        class Acc(var at: Pt, var n: Int, val line: Int)
        fun Acc.add(p: Pt) { n++; at = Pt((at.x * (n - 1) + p.x) / n, (at.y * (n - 1) + p.y) / n) }
        val byLine = LinkedHashMap<Int, MutableList<Acc>>()
        for ((line, p) in ends) {
            if (if (line == HUIA) !huiaCalls else line !in shown) continue
            val list = byLine.getOrPut(line) { ArrayList() }
            val q = list.firstOrNull { metres(it.at, p) < 45 }
            if (q != null) q.add(p) else list += Acc(p, 1, line)
        }
        // the same platform: close by, or further along the same rails (Henderson, where O-W stops short)
        fun onTrack(p: Pt, li: Int) = li != HUIA && d.trackDist(li, p) < 6
        fun samePlace(q: Acc, line: Int, at: Pt) = metres(q.at, at) < 12 ||
            (metres(q.at, at) < 160 && (onTrack(at, q.line) || onTrack(q.at, line)))
        val pts = ArrayList<Acc>()
        for ((line, list) in byLine) for (e in list) {
            val q = pts.firstOrNull { samePlace(it, line, e.at) }
            if (q != null) q.add(e.at) else pts += Acc(e.at, 1, line)
        }
        if (pts.size < 3) return pts.map { it.at }
        // three or more: in order along the bar
        var a = 0
        var b = 1
        var far = 0.0
        pts.forEachIndexed { x, p -> pts.forEachIndexed { y, q -> val dd = metres(p.at, q.at); if (dd > far) { far = dd; a = x; b = y } } }
        val o = pts[a].at
        val dx = (pts[b].at.x - o.x) * KX
        val dy = pts[b].at.y - o.y
        return pts.map { it.at }.sortedBy { (it.x - o.x) * KX * dx + (it.y - o.y) * dy }
    }
}

/**
 * The real track between pairs of stations, cut from the shapes AT's trains
 * run today. Null for a stretch no train's shape covers.
 */
fun Network.railTrack(asks: List<TrackAsk>): List<List<Pt>?> {
    class Shape(val si: Int, val xs: DoubleArray, val ys: DoubleArray, val cum: DoubleArray)
    val shapes = HashMap<String, MutableList<Shape>>()
    for (p in patRoute.indices) {
        val r = patRoute[p]
        if (routeType[r] != 2) continue
        val id = routeId[r]
        val line = if (id.lastIndexOf('-') > 0) id.substring(0, id.lastIndexOf('-')) else id
        val si = tripShapeIdx[patTrip[patTripStart[p]]]
        if (si < 0) continue
        val list = shapes.getOrPut(line) { ArrayList() }
        if (list.any { it.si == si }) continue
        val xs = ArrayList<Double>()
        val ys = ArrayList<Double>()
        for (i in shapeStart[si] until shapeStart[si + 1]) {
            val x = shapeXY[i * 2].toDouble()
            val y = shapeXY[i * 2 + 1].toDouble()
            if (xs.isNotEmpty() && xs.last() == x && ys.last() == y) continue          // repeated points
            xs += x
            ys += y
        }
        val cum = DoubleArray(xs.size)
        for (i in 1 until xs.size) cum[i] = cum[i - 1] + hypot((xs[i] - xs[i - 1]) * KX, ys[i] - ys[i - 1]) * M
        list += Shape(si, xs.toDoubleArray(), ys.toDoubleArray(), cum)
    }
    // the closest point of each pass within 400 m
    fun passes(o: Shape, lon: Double, lat: Double): List<Pair<Int, Double>> {
        val out = ArrayList<Pair<Int, Double>>()
        fun d(i: Int) = hypot((o.xs[i] - lon) * KX, o.ys[i] - lat) * M
        var prev = Double.MAX_VALUE
        var di = if (o.xs.isNotEmpty()) d(0) else return out
        for (i in o.xs.indices) {
            val next = if (i + 1 < o.xs.size) d(i + 1) else Double.MAX_VALUE
            if (di < 400 && di < prev && di <= next) out += i to di
            prev = di
            di = next
        }
        return out
    }
    return asks.map { g ->
        var best: Triple<Shape, Int, Int>? = null
        var bestCost = Double.MAX_VALUE
        for (o in shapes[g.line] ?: emptyList()) {
            val pa = passes(o, g.lon0, g.lat0)
            val pb = passes(o, g.lon1, g.lat1)
            for ((i, da) in pa) for ((j, db) in pb) {
                // prefer a shape that runs from the first station to the second, so lines that
                // share rails are cut from the same track (not one from each direction's)
                val cost = abs(o.cum[j] - o.cum[i]) + 2 * (da + db) + (if (i > j) 60 else 0)
                if (i != j && cost < bestCost) { bestCost = cost; best = Triple(o, i, j) }
            }
        }
        val (o, i, j) = best ?: return@map null
        val step = if (i < j) 1 else -1
        val out = ArrayList<Pt>()
        var k = i
        while (true) {
            out += Pt(o.xs[k], o.ys[k])
            if (k == j) break
            k += step
        }
        // AT's points are every 2 m or so and wobble a little, which makes lines drawn
        // side by side ragged: keep only the points that matter (within 1.5 m)
        simplify(out, 1.5 / M)
    }
}

/** Douglas-Peucker, in degrees of latitude (longitude scaled). */
private fun simplify(pts: List<Pt>, tol: Double): List<Pt> {
    if (pts.size < 3) return pts
    val keep = BooleanArray(pts.size)
    keep[0] = true
    keep[pts.size - 1] = true
    val stack = ArrayDeque<Pair<Int, Int>>()
    stack.addLast(0 to pts.size - 1)
    while (stack.isNotEmpty()) {
        val (a, b) = stack.removeLast()
        val ax = pts[a].x * KX
        val ay = pts[a].y
        val dx = pts[b].x * KX - ax
        val dy = pts[b].y - ay
        val l = hypot(dx, dy)
        var far = -1
        var fd = tol
        for (i in a + 1 until b) {
            val px = pts[i].x * KX - ax
            val py = pts[i].y - ay
            val d = if (l > 0) abs(px * dy - py * dx) / l else hypot(px, py)
            if (d > fd) { fd = d; far = i }
        }
        if (far >= 0) { keep[far] = true; stack.addLast(a to far); stack.addLast(far to b) }
    }
    return pts.filterIndexed { i, _ -> keep[i] }
}
