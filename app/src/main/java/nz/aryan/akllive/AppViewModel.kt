package nz.aryan.akllive

import android.annotation.SuppressLint
import android.app.Application
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import nz.aryan.akllive.data.Alert
import nz.aryan.akllive.data.AlertsRepo
import nz.aryan.akllive.data.AtApi
import nz.aryan.akllive.data.BusDeparture
import nz.aryan.akllive.data.BusRepo
import nz.aryan.akllive.data.BusTrip
import nz.aryan.akllive.data.Fleet
import nz.aryan.akllive.data.FleetState
import nz.aryan.akllive.data.LiveRepo
import nz.aryan.akllive.data.Nz
import nz.aryan.akllive.data.Places
import nz.aryan.akllive.data.StationDeparture
import nz.aryan.akllive.data.StopBoard
import nz.aryan.akllive.data.StopDeparture
import nz.aryan.akllive.data.StopRepo
import nz.aryan.akllive.data.TrainRepo
import nz.aryan.akllive.data.TrainState
import nz.aryan.akllive.data.TripDetail
import nz.aryan.akllive.data.TripLive
import nz.aryan.akllive.data.Weather
import nz.aryan.akllive.data.liveFor
import nz.aryan.akllive.gtfs.GeoPoint
import nz.aryan.akllive.gtfs.PlanResult
import nz.aryan.akllive.gtfs.RaptorOpts
import nz.aryan.akllive.gtfs.Timetable
import nz.aryan.akllive.gtfs.TtStatus
import nz.aryan.akllive.gtfs.searchStops
import nz.aryan.akllive.gtfs.routesList
import nz.aryan.akllive.system.Glance
import nz.aryan.akllive.system.TrackService
import nz.aryan.akllive.ui.Basemap
import kotlin.math.atan2
import kotlin.math.hypot

/** What Directions is working on: kept here so it survives going into a journey and back. */
data class PlanState(
    val from: Place? = null,
    val to: Place? = null,
    /** null: leave now; otherwise epoch seconds to leave at (or arrive by) */
    val time: Long? = null,
    val arriveBy: Boolean = false,
    val loading: Boolean = false,
    val result: PlanResult? = null,
    val error: String? = null,
    /** live delay and position of each option's rides, by trip id */
    val live: Map<String, TripLive> = emptyMap(),
    val stamp: Long = 0,
)

/** One line of the global search. */
sealed interface SearchHit {
    data class StopHit(val hit: nz.aryan.akllive.gtfs.StopHit) : SearchHit
    data class PlaceHit(val place: Place) : SearchHit
    data class RouteHit(val short: String, val long: String, val type: Int) : SearchHit
    data class ModelHit(val id: String, val name: String, val kind: String) : SearchHit
    data class FleetNoHit(val fleetNo: String, val operator: String, val model: String?) : SearchHit
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)
    private val _settings = MutableStateFlow(prefs.load())
    val settings: StateFlow<Settings> = _settings

    /** Change settings: saved straight away, and every screen sees it. */
    fun update(f: (Settings) -> Settings) {
        val old = _settings.value
        val new = f(old)
        if (new == old) return
        prefs.save(new)
        _settings.value = new
        if (new.stops != old.stops || new.route != old.route) {
            _boards.value = new.stops.map { StopBoard(it) }
            refresh()
        }
        if (new.apiKey != old.apiKey) { refresh(); refreshAlerts() }
    }

    private val api = AtApi { _settings.value.apiKey }
    private val busRepo = BusRepo(api)
    private val trainRepo = TrainRepo(api)
    private val liveRepo = LiveRepo(api)
    val stopRepo = StopRepo(api)

    private val _boards = MutableStateFlow(_settings.value.stops.map { StopBoard(it) })
    val boards: StateFlow<List<StopBoard>> = _boards
    private val _busError = MutableStateFlow<String?>(null)
    val busError: StateFlow<String?> = _busError
    /** true from a pull-to-refresh until the buses have reloaded */
    private val _pulling = MutableStateFlow(false)
    val pulling: StateFlow<Boolean> = _pulling

    val basemap: StateFlow<Basemap> = _settings.map { it.basemap }.stateIn(viewModelScope, SharingStarted.Eagerly, _settings.value.basemap)
    val linzKey: StateFlow<String> = _settings.map { it.linzKey }.stateIn(viewModelScope, SharingStarted.Eagerly, _settings.value.linzKey)

    private val _weather = MutableStateFlow<Weather?>(null)
    val weather: StateFlow<Weather?> = _weather

    private val _alerts = MutableStateFlow<List<Alert>>(emptyList())
    val alerts: StateFlow<List<Alert>> = _alerts
    private val _alertsError = MutableStateFlow<String?>(null)
    val alertsError: StateFlow<String?> = _alertsError

    private val _trains = MutableStateFlow(TrainState())
    val trains: StateFlow<TrainState> = _trains
    private val _trip = MutableStateFlow<TripDetail?>(null)
    val trip: StateFlow<TripDetail?> = _trip
    private val _station = MutableStateFlow<Pair<Int, List<StationDeparture>?>?>(null)
    val station: StateFlow<Pair<Int, List<StationDeparture>?>?> = _station

    /** every bus on the network, polled only while a screen that shows them is open */
    private val _fleet = MutableStateFlow(FleetState())
    val fleet: StateFlow<FleetState> = _fleet
    private val _busTrip = MutableStateFlow<BusTrip?>(null)
    val busTrip: StateFlow<BusTrip?> = _busTrip
    /** the model page open on the Fleet screen ("unknown" for the unidentified buses) */
    val fleetModel = MutableStateFlow<String?>(null)
    private var liveWatchers = 0
    private var trainWatchers = 0

    val timetable: StateFlow<TtStatus> = Timetable.status

    private val _plan = MutableStateFlow(PlanState())
    val plan: StateFlow<PlanState> = _plan

    /** a one-off message for the snackbar */
    val toast = MutableSharedFlow<String>(extraBufferCapacity = 4)

    private val kick = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val liveKick = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    @Volatile private var visible = false

    init {
        Fleet.load(app)
        Timetable.init(app)
        viewModelScope.launch { loop(30_000) { refreshBuses() } }
        viewModelScope.launch {
            while (true) {
                while (!visible || trainWatchers <= 0) delay(400)
                try { refreshTrains() } catch (_: Exception) { }
                withTimeoutOrNull(15_000) { kick.first() }
            }
        }
        viewModelScope.launch { loop(900_000) { Weather.fetch()?.let { _weather.value = it } } }
        viewModelScope.launch { loop(300_000) { loadAlerts() } }
        viewModelScope.launch {
            while (true) {
                while (!visible || liveWatchers <= 0) delay(400)
                refreshLive()
                withTimeoutOrNull(20_000) { liveKick.first() }
            }
        }
        // the timetable loads in the background once there's a key
        viewModelScope.launch {
            while (_settings.value.apiKey.isBlank()) delay(2000)
            Timetable.ready(_settings.value.wifiOnly)
        }
    }

    // ======================= polling =======================

    fun watchLive(on: Boolean) {
        liveWatchers = maxOf(0, liveWatchers + if (on) 1 else -1)
        if (on) liveKick.tryEmit(Unit)
    }

    /** The Trains screen polls the network only while it's up. */
    fun watchTrains(on: Boolean) {
        trainWatchers = maxOf(0, trainWatchers + if (on) 1 else -1)
        if (on) kick.tryEmit(Unit)
    }

    fun setVisible(v: Boolean) {
        visible = v
        if (v) {
            refresh()
            liveKick.tryEmit(Unit)
        }
    }

    fun refresh() { kick.tryEmit(Unit) }

    fun pullRefresh() {
        _pulling.value = true
        refresh()
        liveKick.tryEmit(Unit)
        refreshAlerts()
    }

    fun setBasemap(b: Basemap) = update { it.copy(basemap = b) }

    private suspend fun loop(every: Long, work: suspend () -> Unit) {
        while (true) {
            while (!visible) delay(400)
            try { work() } catch (e: Exception) { /* each refresh records its own error */ }
            withTimeoutOrNull(every) { kick.first() }
        }
    }

    private suspend fun refreshLive() {
        try {
            _fleet.value = FleetState(liveRepo.all(), Nz.nowSec(), loading = false)
        } catch (e: Exception) {
            _fleet.value = _fleet.value.copy(loading = false, error = e.message)
        }
    }

    fun selectBus(tripId: String?) {
        if (_busTrip.value?.tripId == tripId && tripId != null) return
        _busTrip.value = tripId?.let { BusTrip(it) }
        if (tripId == null) return
        viewModelScope.launch {
            val t = try { liveRepo.trip(tripId) } catch (_: Exception) { return@launch }
            if (_busTrip.value?.tripId == tripId) _busTrip.value = t
        }
    }

    private suspend fun refreshBuses() {
        val s = _settings.value
        if (s.apiKey.isBlank()) {
            _pulling.value = false
            return
        }
        val out = s.stops.map { code ->
            try {
                busRepo.board(code, s.route)
            } catch (e: Exception) {
                (_boards.value.firstOrNull { it.code == code } ?: StopBoard(code)).copy(error = e.message)
            }
        }
        _boards.value = out
        _busError.value = out.firstNotNullOfOrNull { it.error }
        _pulling.value = false
        spot(out.flatMap { it.departures })
        Glance.save(getApplication(), out)
    }

    fun refreshAlerts() { viewModelScope.launch { loadAlerts() } }

    private suspend fun loadAlerts() {
        if (_settings.value.apiKey.isBlank()) return
        try {
            _alerts.value = AlertsRepo.fetch(api)
            _alertsError.value = null
        } catch (e: Exception) {
            _alertsError.value = e.message
        }
    }

    /** Alerts about your route, your stops and your favourites. */
    fun myAlerts(list: List<Alert>, s: Settings): List<Alert> {
        val routes = setOf(s.route)
        val stops = (s.stops + s.favs.flatMap { listOf(it.code, it.id) }).toSet()
        return list.filter { it.active && it.touches(routes, stops) }
    }

    private suspend fun refreshTrains() {
        try {
            val fresh = trainRepo.poll()
            val old = _trains.value
            val elapsed = SystemClock.elapsedRealtime()
            // glide from wherever each marker is right now
            val before = HashMap<String, Pair<Float, Float>>()
            val heading = HashMap(old.heading)
            val f = glide(old.movedAt, elapsed)
            for (t in old.trains) {
                val b = old.before[t.vehicle.id]
                before[t.vehicle.id] = if (b == null) t.x to t.y
                    else (b.first + (t.x - b.first) * f) to (b.second + (t.y - b.second) * f)
            }
            for (t in fresh) {
                val b = before[t.vehicle.id] ?: continue
                val dx = t.x - b.first
                val dy = t.y - b.second
                if (hypot(dx, dy) > 0.25f) heading[t.vehicle.id] =
                    Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
            }
            _trains.value = TrainState(fresh, before, heading, elapsed, Nz.nowSec(), null)
        } catch (e: Exception) {
            _trains.value = _trains.value.copy(error = e.message)
        }
    }

    fun selectTrain(tripId: String?, startDate: String?) {
        _trip.value = null
        if (tripId == null) return
        viewModelScope.launch {
            try { _trip.value = trainRepo.trip(tripId, startDate) } catch (_: Exception) {}
        }
    }

    fun selectStation(index: Int?) {
        val keep = _station.value?.takeIf { it.first == index }?.second
        _station.value = index?.let { it to keep }
        if (index == null) return
        viewModelScope.launch {
            val deps = try { trainRepo.departures(index) } catch (_: Exception) { emptyList() }
            if (_station.value?.first == index) _station.value = index to deps
        }
    }

    // ======================= stops, search =======================

    suspend fun stopDepartures(stops: Map<String, String>): List<StopDeparture> = stopRepo.departures(stops)

    fun toggleFav(f: FavStop) {
        val s = _settings.value
        val had = s.favs.any { it.id == f.id }
        update { st -> st.copy(favs = if (had) st.favs.filter { it.id != f.id } else st.favs + f) }
        toast.tryEmit(if (had) "Removed from favourites" else if (s.kiwi) "Sweet as, ${f.title} is a favourite" else "Added to favourites")
        nz.aryan.akllive.system.Shortcuts.syncFavs(getApplication(), _settings.value.favs)
    }

    fun renameFav(id: String, nick: String) = update { s -> s.copy(favs = s.favs.map { if (it.id == id) it.copy(nickname = nick) else it }) }

    /** Everything that matches: stops, places, routes, bus models, fleet numbers. */
    suspend fun search(q: String): List<SearchHit> {
        val t = q.trim()
        if (t.isEmpty()) return emptyList()
        val out = ArrayList<SearchHit>()
        val net = Timetable.today
        // a fleet number ("NB5775") or a model ("eT12")
        Fleet.info(t)?.let { out += SearchHit.FleetNoHit(it.fleetNo.uppercase(), it.operator ?: it.code, it.model?.name) }
        Fleet.models.filter { it.name.contains(t, true) || it.short.contains(t, true) }.take(3)
            .forEach { out += SearchHit.ModelHit(it.id, it.name, it.kind) }
        if (net != null) {
            withContext(Dispatchers.Default) {
                net.routesList().filter { it.short.equals(t, true) || (t.length >= 2 && it.short.startsWith(t, true)) }.take(4)
                    .forEach { out += SearchHit.RouteHit(it.short, it.long, it.type) }
                net.searchStops(t, 8).forEach { out += SearchHit.StopHit(it) }
            }
        }
        try { Places.search(t).take(6).forEach { out += SearchHit.PlaceHit(it) } } catch (_: Exception) { }
        return out
    }

    // ======================= directions =======================

    private var planJob: Job? = null

    fun setPlan(f: (PlanState) -> PlanState, run: Boolean = true) {
        _plan.value = f(_plan.value)
        if (run) runPlan()
    }

    fun rememberPlace(p: Place) {
        if (p.kind == PlaceKind.Home || p.kind == PlaceKind.Work || p.kind == PlaceKind.Pin || p.kind == PlaceKind.Here) return
        update { s -> s.copy(recents = (listOf(p.copy(kind = if (p.kind == PlaceKind.Stop) PlaceKind.Stop else PlaceKind.Recent)) +
                                         s.recents.filter { !it.same(p) }).take(8)) }
    }

    fun runPlan() {
        val st = _plan.value
        val from = st.from ?: return
        val to = st.to ?: return
        planJob?.cancel()
        _plan.value = st.copy(loading = true, error = null)
        planJob = viewModelScope.launch {
            try {
                val s = _settings.value
                val o = s.planOpts
                val time = st.time ?: Nz.nowSec()
                val r = Timetable.plan(GeoPoint(from.name, from.lat, from.lon), GeoPoint(to.name, to.lat, to.lon), time, st.arriveBy,
                                       RaptorOpts(o.maxWalk, o.walkSpeed, o.bus, o.train, o.ferry, o.maxRides), s.wifiOnly)
                _plan.value = _plan.value.copy(loading = false, result = r, error = null, stamp = Nz.nowSec(), live = emptyMap())
                refreshPlanLive()
            } catch (e: Exception) {
                _plan.value = _plan.value.copy(loading = false, error = e.message ?: "Couldn't plan that trip")
            }
        }
    }

    /** Live delays and positions for the first ride of each option (and every ride of the one open). */
    fun refreshPlanLive(extra: List<String> = emptyList()) {
        val r = _plan.value.result ?: return
        val ids = (r.itineraries.mapNotNull { it.rides.firstOrNull()?.tripId } + extra).distinct()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            try {
                val live = liveFor(api, ids)
                _plan.value = _plan.value.copy(live = _plan.value.live + live)
            } catch (_: Exception) { }
        }
    }

    // ======================= location =======================

    /** Where the phone is, if location is allowed and on (null otherwise). */
    @SuppressLint("MissingPermission")
    suspend fun here(): Location? {
        val ctx = getApplication<Application>()
        val ok = ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!ok) return null
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        val provider = when {
            lm.isProviderEnabled(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> return null
        }
        lm.getLastKnownLocation(provider)?.takeIf { System.currentTimeMillis() - it.time < 120_000 }?.let { return it }
        val d = CompletableDeferred<Location?>()
        LocationManagerCompat.getCurrentLocation(lm, provider, android.os.CancellationSignal(),
                                                 ContextCompat.getMainExecutor(ctx)) { d.complete(it) }
        return withTimeoutOrNull(12_000) { d.await() } ?: lm.getLastKnownLocation(provider)
    }

    // ======================= bus spotting =======================

    /** A bus of a known model pulling up at one of your stops goes in the fleet dex. */
    private fun spot(deps: List<BusDeparture>) {
        val now = Nz.nowSec()
        val seen = deps.filter { (it.stopsAway == 0 || it.expected - now in -30..60) && it.vehicle != null && !it.cancelled }
            .mapNotNull { d -> Fleet.info(d.vehicle!!.label)?.let { i -> i.model?.let { it.id to i.fleetNo } } }
        if (seen.isEmpty()) return
        val s = _settings.value
        val newOnes = seen.filter { (id, _) -> id !in s.spotted }.map { it.first }.distinct()
        update { st ->
            val m = st.spotted.toMutableMap()
            for ((id, no) in seen) {
                val old = m[id]
                // one sighting per bus per ten minutes
                if (old != null && old.fleetNo == no && now - old.last < 600) continue
                m[id] = if (old == null) Spotted(1, now, now, no) else old.copy(count = old.count + 1, last = now, fleetNo = no)
            }
            st.copy(spotted = m)
        }
        for (id in newOnes) {
            val name = Fleet.model(id)?.name ?: continue
            toast.tryEmit(if (s.kiwi) "Chur! New bus spotted: $name" else "New bus spotted: $name")
        }
    }

    // ======================= tracking =======================

    fun track(board: StopBoard, d: BusDeparture) {
        val ctx = getApplication<Application>()
        val i = Intent(ctx, TrackService::class.java)
            .putExtra(TrackService.EX_TRIP, d.tripId)
            .putExtra(TrackService.EX_ROUTE, d.route)
            .putExtra(TrackService.EX_HEAD, d.headsign)
            .putExtra(TrackService.EX_STOP, board.name.ifEmpty { "Stop ${board.code}" })
            .putExtra(TrackService.EX_SEQ, d.stopSeq)
            .putExtra(TrackService.EX_SCHED, d.scheduled)
            .putExtra(TrackService.EX_LAT, board.lat)
            .putExtra(TrackService.EX_LON, board.lon)
        try {
            ContextCompat.startForegroundService(ctx, i)
            toast.tryEmit("Tracking the ${d.route}: it's in your notifications")
        } catch (e: Exception) {
            toast.tryEmit("Couldn't start tracking: ${e.message}")
        }
    }

    companion object {
        const val GLIDE_MS = 12_000f

        /** 0..1 eased progress of the glide to the latest fix. */
        fun glide(movedAt: Long, now: Long): Float {
            val t = ((now - movedAt) / GLIDE_MS).coerceIn(0f, 1f)
            return t * t * (3 - 2 * t)
        }
    }
}
