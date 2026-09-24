package nz.aryan.akllive

import android.app.Application
import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import nz.aryan.akllive.data.AtApi
import nz.aryan.akllive.data.BusRepo
import nz.aryan.akllive.data.BusTrip
import nz.aryan.akllive.data.FleetState
import nz.aryan.akllive.data.LiveRepo
import nz.aryan.akllive.data.Nz
import nz.aryan.akllive.data.StationDeparture
import nz.aryan.akllive.data.StopBoard
import nz.aryan.akllive.data.TrainRepo
import nz.aryan.akllive.data.TrainState
import nz.aryan.akllive.data.TripDetail
import nz.aryan.akllive.data.Fleet
import nz.aryan.akllive.data.Weather
import nz.aryan.akllive.ui.Basemap
import kotlin.math.atan2
import kotlin.math.hypot

/** User settings. The API key defaults to the one built into the APK. */
class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("akl", Context.MODE_PRIVATE)
    var apiKey: String
        get() = p.getString("key", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.AT_API_KEY
        set(v) = p.edit().putString("key", v.trim()).apply()
    var stops: List<String>
        get() = (p.getString("stops", null) ?: "8669,8664").split(',').map { it.trim() }.filter { it.isNotEmpty() }
        set(v) = p.edit().putString("stops", v.joinToString(",")).apply()
    var route: String
        get() = p.getString("route", null) ?: "27H"
        set(v) = p.edit().putString("route", v.trim()).apply()
    var place: String
        get() = p.getString("place", null) ?: "Hillsborough"
        set(v) = p.edit().putString("place", v.trim()).apply()
    var keepOn: Boolean
        get() = p.getBoolean("keepOn", false)
        set(v) = p.edit().putBoolean("keepOn", v).apply()
    val keyIsBuiltIn get() = p.getString("key", null).isNullOrBlank() && BuildConfig.AT_API_KEY.isNotBlank()
    /** LINZ Basemaps key for NZ's sharpest aerials; without one the satellite view uses Esri's imagery. */
    var linzKey: String
        get() = p.getString("linz", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.LINZ_API_KEY
        set(v) = p.edit().putString("linz", v.trim()).apply()
    val linzIsBuiltIn get() = p.getString("linz", null).isNullOrBlank() && BuildConfig.LINZ_API_KEY.isNotBlank()
    var basemap: Basemap
        get() = if (p.getString("basemap", null) == "streets") Basemap.Streets else Basemap.Satellite
        set(v) = p.edit().putString("basemap", if (v == Basemap.Streets) "streets" else "satellite").apply()
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val prefs = Prefs(app)
    private val api = AtApi { prefs.apiKey }
    private val busRepo = BusRepo(api)
    private val trainRepo = TrainRepo(api)
    private val liveRepo = LiveRepo(api)

    private val _boards = MutableStateFlow(prefs.stops.map { StopBoard(it) })
    val boards: StateFlow<List<StopBoard>> = _boards
    private val _busError = MutableStateFlow<String?>(null)
    val busError: StateFlow<String?> = _busError
    /** true from a pull-to-refresh until the buses have reloaded */
    private val _pulling = MutableStateFlow(false)
    val pulling: StateFlow<Boolean> = _pulling

    private val _basemap = MutableStateFlow(prefs.basemap)
    val basemap: StateFlow<Basemap> = _basemap
    private val _linzKey = MutableStateFlow(prefs.linzKey)
    val linzKey: StateFlow<String> = _linzKey

    private val _weather = MutableStateFlow<Weather?>(null)
    val weather: StateFlow<Weather?> = _weather

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
    /** the model page open on the Fleet tab ("unknown" for the unidentified buses) */
    val fleetModel = MutableStateFlow<String?>(null)
    private var liveWatchers = 0

    private val kick = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val liveKick = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    @Volatile private var visible = false

    init {
        Fleet.load(app)
        viewModelScope.launch { loop(30_000) { refreshBuses() } }
        viewModelScope.launch { loop(15_000) { refreshTrains() } }
        viewModelScope.launch { loop(900_000) { Weather.fetch()?.let { _weather.value = it } } }
        viewModelScope.launch {
            while (true) {
                while (!visible || liveWatchers <= 0) delay(400)
                refreshLive()
                withTimeoutOrNull(20_000) { liveKick.first() }
            }
        }
    }

    /** Screens showing every bus call this while they're up; the feed is only fetched then. */
    fun watchLive(on: Boolean) {
        liveWatchers = maxOf(0, liveWatchers + if (on) 1 else -1)
        if (on) liveKick.tryEmit(Unit)
    }

    private suspend fun refreshLive() {
        try {
            _fleet.value = FleetState(liveRepo.all(), Nz.nowSec(), loading = false)
        } catch (e: Exception) {
            _fleet.value = _fleet.value.copy(loading = false, error = e.message)
        }
    }

    /** A bus was tapped on the big map: look up where it's going. */
    fun selectBus(tripId: String?) {
        if (_busTrip.value?.tripId == tripId && tripId != null) return
        _busTrip.value = tripId?.let { BusTrip(it) }
        if (tripId == null) return
        viewModelScope.launch {
            val t = try { liveRepo.trip(tripId) } catch (_: Exception) { return@launch }
            if (_busTrip.value?.tripId == tripId) _busTrip.value = t
        }
    }

    /** Poll only while the app is on screen; refresh straight away when it comes back. */
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
    }

    fun setBasemap(b: Basemap) {
        prefs.basemap = b
        _basemap.value = b
    }

    private suspend fun loop(every: Long, work: suspend () -> Unit) {
        while (true) {
            while (!visible) delay(400)
            try { work() } catch (e: Exception) { /* each refresh records its own error */ }
            withTimeoutOrNull(every) { kick.first() }
        }
    }

    private suspend fun refreshBuses() {
        val codes = prefs.stops
        val out = codes.map { code ->
            try {
                busRepo.board(code, prefs.route)
            } catch (e: Exception) {
                (_boards.value.firstOrNull { it.code == code } ?: StopBoard(code)).copy(error = e.message)
            }
        }
        _boards.value = out
        _busError.value = out.firstNotNullOfOrNull { it.error }
        _pulling.value = false
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
        // keep showing the current list while it refreshes
        val keep = _station.value?.takeIf { it.first == index }?.second
        _station.value = index?.let { it to keep }
        if (index == null) return
        viewModelScope.launch {
            val deps = try { trainRepo.departures(index) } catch (_: Exception) { emptyList() }
            if (_station.value?.first == index) _station.value = index to deps
        }
    }

    fun saveSettings(key: String, stops: String, route: String, place: String, linz: String) {
        if (key.isNotBlank()) prefs.apiKey = key
        if (linz.isNotBlank()) {
            prefs.linzKey = linz
            _linzKey.value = prefs.linzKey
        }
        prefs.stops = stops.split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }
        prefs.route = route
        prefs.place = place
        _boards.value = prefs.stops.map { StopBoard(it) }
        refresh()
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
