package nz.aryan.akllive.system

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import nz.aryan.akllive.Place
import nz.aryan.akllive.data.Nz
import nz.aryan.akllive.data.TripLive
import nz.aryan.akllive.gtfs.Itinerary
import nz.aryan.akllive.gtfs.RideLeg
import nz.aryan.akllive.gtfs.WalkLeg
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Where you are in a journey. */
enum class Phase { Walking, Waiting, OnBoard, Arrived }

/** Something worth a heads-up. */
sealed interface TripEvent {
    data class BusSoon(val ride: RideLeg, val secs: Long) : TripEvent
    data class Boarded(val ride: RideLeg) : TripEvent
    data class GetOffNext(val ride: RideLeg) : TripEvent
    data class Missed(val ride: RideLeg) : TripEvent
    data object Arrived : TripEvent
}

data class TripProgress(
    val itinerary: Itinerary? = null,
    val from: Place? = null,
    val to: Place? = null,
    val leg: Int = 0,
    val phase: Phase = Phase.Walking,
    val lat: Double? = null,
    val lon: Double? = null,
    val accuracy: Float = 0f,
    val headline: String = "",
    val detail: String = "",
    /** on board: stops passed and the ride's stops, for a progress bar */
    val stopsDone: Int = 0,
    val stopsTotal: Int = 0,
    val live: Map<String, TripLive> = emptyMap(),
    val updated: Long = 0,
    internal val boardHits: Int = 0,
    internal val offHits: Int = 0,
    internal val told: Set<String> = emptySet(),
) {
    val active: Boolean get() = itinerary != null
    val ride: RideLeg? get() = itinerary?.legs?.getOrNull(leg) as? RideLeg
}

/**
 * Follows you through a planned journey with GPS: walking to the stop, waiting
 * for the bus, on board (you're with the bus: next to its live position, or
 * moving along its route past the stop you got on at), then off again, ride by
 * ride. On board it works out your next stop and how many are left, and gives a
 * heads-up the stop before yours. TripService feeds it fixes and live data.
 */
object TripTracker {
    private val _state = MutableStateFlow(TripProgress())
    val state: StateFlow<TripProgress> = _state
    private val stopIdx = HashMap<String, IntArray>()
    private val KX = cos(Math.toRadians(36.9))

    fun start(ctx: Context, it: Itinerary, from: Place?, to: Place?) {
        stopIdx.clear()
        _state.value = TripProgress(it, from, to, 0, if (it.legs.firstOrNull() is RideLeg) Phase.Waiting else Phase.Walking,
                                    headline = "Finding you…", detail = "Waiting for a GPS fix", updated = Nz.nowSec())
        ContextCompat.startForegroundService(ctx, Intent(ctx, TripService::class.java))
        TripWidget.nudge(ctx, force = true)
    }

    fun stop(ctx: Context) {
        // the service may already be gone (the trip finished on its own)
        try { ctx.startService(Intent(ctx, TripService::class.java).setAction(TripService.ACTION_STOP)) } catch (_: Exception) { }
        _state.value = TripProgress()
        TripWidget.nudge(ctx, force = true)
    }

    internal fun clear() { _state.value = TripProgress() }

    internal fun setLive(live: Map<String, TripLive>) {
        val s = _state.value
        if (s.active) _state.value = s.copy(live = s.live + live)
    }

    /** The rides whose live data matters now: this one and the next. */
    internal fun watching(): List<String> {
        val s = _state.value
        val legs = s.itinerary?.legs ?: return emptyList()
        return legs.drop(s.leg).filterIsInstance<RideLeg>().take(2).map { it.tripId }
    }

    private fun metres(aLat: Double, aLon: Double, bLat: Double, bLon: Double) =
        hypot((bLon - aLon) * KX, bLat - aLat) * 111_320.0

    /** The point of a ride's path nearest a spot: (index, metres away). */
    private fun nearest(shape: List<Pair<Double, Double>>, lat: Double, lon: Double, from: Int = 0): Pair<Int, Double> {
        var bi = from
        var bd = Double.MAX_VALUE
        for (i in from until shape.size) {
            val d = metres(lat, lon, shape[i].first, shape[i].second)
            if (d < bd) { bd = d; bi = i }
        }
        return bi to bd
    }

    /** Where each of a ride's stops falls along its path. */
    private fun stopsAlong(r: RideLeg): IntArray = stopIdx.getOrPut(r.tripId + "@" + r.from.seq) {
        var from = 0
        IntArray(r.stops.size) { k ->
            val (i, _) = nearest(r.shape, r.stops[k].stop.lat, r.stops[k].stop.lon, from)
            from = i
            i
        }
    }

    /** Are you on this ride: with its bus, or moving along its route past where you'd get on? */
    private fun aboard(r: RideLeg, lat: Double, lon: Double, speed: Float?, acc: Float, live: TripLive?, now: Long): Boolean {
        val v = live?.vehicle
        if (v != null && now - v.timestamp < 120 && metres(lat, lon, v.lat, v.lon) < maxOf(70f, acc * 1.5f)) {
            // standing at the stop next to a stopped bus isn't riding it yet
            if ((speed ?: 0f) > 2.5f || metres(lat, lon, r.from.stop.lat, r.from.stop.lon) > 120) return true
        }
        if (r.shape.size < 2) return false
        val along = stopsAlong(r)
        val (i, d) = nearest(r.shape, lat, lon)
        return d < maxOf(35f, acc) && i > along[0] + 3 && (speed ?: 0f) > 3f &&
            metres(lat, lon, r.from.stop.lat, r.from.stop.lon) > 150
    }

    /** A new GPS fix: where in the journey that puts you, and anything worth telling you. */
    internal fun onFix(lat: Double, lon: Double, speed: Float?, acc: Float): List<TripEvent> {
        var s = _state.value
        val it = s.itinerary ?: return emptyList()
        val now = Nz.nowSec()
        val events = ArrayList<TripEvent>()
        fun once(key: String, e: TripEvent) { if (key !in s.told) { s = s.copy(told = s.told + key); events += e } }
        fun advance(): Boolean {
            val next = s.leg + 1
            s = if (next >= it.legs.size) s.copy(phase = Phase.Arrived)
                else s.copy(leg = next, phase = if (it.legs[next] is RideLeg) Phase.Waiting else Phase.Walking, boardHits = 0, offHits = 0)
            return next < it.legs.size
        }
        s = s.copy(lat = lat, lon = lon, accuracy = acc, updated = now)
        var guard = 0
        while (guard++ < it.legs.size + 1) {
            if (s.phase == Phase.Arrived) {
                s = s.copy(headline = "You're there", detail = s.to?.name?.let { "Welcome to $it" } ?: "Journey done", stopsDone = 0, stopsTotal = 0)
                once("arrived", TripEvent.Arrived)
                break
            }
            when (val l = it.legs[s.leg]) {
                is WalkLeg -> {
                    val next = it.legs.getOrNull(s.leg + 1) as? RideLeg
                    // already on the next ride (tracking started late, or a quick walk)
                    if (next != null && aboard(next, lat, lon, speed, acc, s.live[next.tripId], now)) {
                        advance(); s = s.copy(phase = Phase.OnBoard); continue
                    }
                    val d = metres(lat, lon, l.toLat, l.toLon)
                    if (next == null && d < 50) { advance(); continue }
                    if (next != null && d < 60) { advance(); continue }
                    val mins = maxOf(1, (d * 1.25 / 1.3 / 60).roundToInt())
                    val due = next?.let { r -> r.from.dep + (s.live[r.tripId]?.delay ?: 0) - now }
                    s = s.copy(phase = Phase.Walking, headline = "Walk to ${l.toName}", stopsDone = 0, stopsTotal = 0,
                               detail = listOfNotNull("${dist(d)}, about $mins min",
                                                      next?.let { r -> "the ${r.route} ${inWords(due!!)}" }).joinToString(" · "))
                    if (next != null && due!! in 0..(mins * 60L + 60)) once("hurry-${s.leg}", TripEvent.BusSoon(next, due))
                    break
                }
                is RideLeg -> {
                    val lv = s.live[l.tripId]
                    val delay = lv?.delay ?: 0
                    if (s.phase != Phase.OnBoard) {
                        if (aboard(l, lat, lon, speed, acc, lv, now)) {
                            s = s.copy(boardHits = s.boardHits + 1)
                            if (s.boardHits >= 2) { s = s.copy(phase = Phase.OnBoard); once("board-${s.leg}", TripEvent.Boarded(l)); continue }
                        } else s = s.copy(boardHits = 0)
                        val due = l.from.dep + delay - now
                        val away = lv?.seq?.let { l.from.seq - it }?.takeIf { it >= 0 }
                        s = s.copy(phase = Phase.Waiting, stopsDone = 0, stopsTotal = 0,
                                   headline = "Wait for the ${l.route} at ${l.from.stop.name}",
                                   detail = listOfNotNull(if (lv?.cancelled == true) "Cancelled: check the app" else "Due ${inWords(due)}",
                                                          away?.let { if (it == 0) "at the stop" else if (it == 1) "1 stop away" else "$it stops away" },
                                                          l.from.stop.platform.takeIf { p -> p.isNotEmpty() }?.let { p -> "platform $p" })
                                       .joinToString(" · "))
                        if (due in 0..120 || away == 1) once("soon-${s.leg}", TripEvent.BusSoon(l, due))
                        break
                    }
                    // on board: which stop is next, from where you are along the route (or the bus's last stop)
                    val along = stopsAlong(l)
                    val last = l.stops.lastIndex
                    var nextK: Int
                    val (i, off) = if (l.shape.size > 1) nearest(l.shape, lat, lon, along[0]) else 0 to Double.MAX_VALUE
                    nextK = (1..last).firstOrNull { k -> along[k] > i } ?: (last + 1)
                    if (off > 150 || acc > 120) {
                        lv?.seq?.let { seq -> nextK = (1..last).firstOrNull { k -> l.stops[k].seq > seq } ?: (last + 1) }
                    }
                    val toStop = l.to.stop
                    val dOff = metres(lat, lon, toStop.lat, toStop.lon)
                    // off: at your stop and walking (or stopped), twice in a row
                    if (dOff < 80 && (speed ?: 0f) < 2.2f) s = s.copy(offHits = s.offHits + 1) else s = s.copy(offHits = 0)
                    if (s.offHits >= 2) { advance(); continue }
                    if (nextK > last && dOff > 250 && (speed ?: 0f) > 4f) once("missed-${s.leg}", TripEvent.Missed(l))
                    val left = (last - nextK + 1).coerceAtLeast(0)
                    val offAt = Nz.time(l.to.arr + delay)
                    s = s.copy(stopsDone = (nextK - 1).coerceIn(0, last), stopsTotal = last,
                               headline = "On the ${l.route} to ${l.headsign}",
                               detail = when {
                                   left <= 0 -> "Your stop: get off at ${toStop.name}"
                                   left == 1 -> "Get off at the next stop: ${toStop.name}"
                                   else -> "$left stops to ${toStop.name} · next ${l.stops[nextK].stop.name} · off about $offAt"
                               })
                    if (left <= 1 && dOff < 900) once("off-${s.leg}", TripEvent.GetOffNext(l))
                    break
                }
            }
        }
        _state.value = s
        return events
    }

    private fun dist(m: Double) = if (m >= 1000) "%.1f km".format(m / 1000) else "${(m / 10).roundToInt() * 10} m"

    private fun inWords(secs: Long) = when {
        secs < -60 -> "${-secs / 60} min ago"
        secs < 45 -> "now"
        secs < 3600 -> "in ${secs / 60} min"
        else -> "at ${Nz.time(Nz.nowSec() + secs)}"
    }
}
