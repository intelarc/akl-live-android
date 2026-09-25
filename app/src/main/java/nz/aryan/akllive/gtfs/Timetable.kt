package nz.aryan.akllive.gtfs

import android.content.Context
import android.net.ConnectivityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class TtStatus(
    val ready: Boolean = false,
    val loading: Boolean = false,
    val text: String = "Timetable not loaded yet",
    val pct: Int = 0,
    val error: String? = null,
    val stops: Int = 0,
    val trips: Int = 0,
    val routes: Int = 0,
    /** yyyyMMdd of the day compiled */
    val date: String? = null,
    /** bytes of AT's gtfs.zip on the phone */
    val zipBytes: Long = 0,
)

class WaitingForWifi : IOException("Waiting for Wi-Fi to download AT's timetable (about 29 MB)")

/**
 * AT's whole timetable on the phone, for the journey planner, stop search and
 * route maps. gtfs.zip (about 29 MB) is checked every six hours and kept for
 * offline; each service day is compiled once and cached.
 */
object Timetable {
    private const val VERSION = 1
    private const val URL_ZIP = "https://gtfs.at.govt.nz/gtfs.zip"
    private const val RECHECK_MS = 6 * 3600 * 1000L
    private val ZONE: ZoneId = ZoneId.of("Pacific/Auckland")
    private val YMD: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE

    private val _status = MutableStateFlow(TtStatus())
    val status: StateFlow<TtStatus> = _status

    /** today's network, once it's loaded */
    @Volatile var today: Network? = null
        private set
    private val days = HashMap<String, Network>()
    private val mutex = Mutex()
    private lateinit var dir: File
    private lateinit var app: Context

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        dir = File(ctx.filesDir, "timetable").also { it.mkdirs() }
        val zip = File(dir, "gtfs.zip")
        if (zip.exists()) _status.value = _status.value.copy(zipBytes = zip.length())
    }

    fun todayYmd(): String = LocalDate.now(ZONE).format(YMD)

    /** Epoch second of a service day's "midnight" (GTFS: noon minus 12 h, right on DST days too). */
    fun dayStart(ymd: String): Long = LocalDate.parse(ymd, YMD).atTime(12, 0).atZone(ZONE).toEpochSecond() - 12 * 3600

    /** Today's network, downloading and compiling as needed. Null (with the reason in status) if it can't. */
    suspend fun ready(wifiOnly: Boolean): Network? = withContext(Dispatchers.Default) {
        val ymd = todayYmd()
        today?.takeIf { it.date == ymd }?.let { return@withContext it }
        mutex.withLock {
            today?.takeIf { it.date == ymd }?.let { return@withLock it }
            try {
                set { it.copy(loading = true, error = null, text = "Loading the timetable…", pct = 2) }
                val net = open(ymd, wifiOnly, primary = true)
                today = net
                synchronized(days) { days.keys.retainAll(setOf(ymd, tomorrow())); days[ymd] = net }
                set {
                    it.copy(ready = true, loading = false, text = "Ready", pct = 100, date = ymd, stops = net.nStops,
                            trips = net.tripId.size, routes = net.routeShort.distinct().size)
                }
                net
            } catch (e: Exception) {
                set { it.copy(loading = false, error = e.message ?: "The timetable couldn't load", text = e.message ?: "Error") }
                null
            }
        }
    }

    /** The network for another day (tomorrow's trips for "leave tomorrow at"). */
    suspend fun forDay(ymd: String, wifiOnly: Boolean): Network? = withContext(Dispatchers.Default) {
        if (ymd == todayYmd()) return@withContext ready(wifiOnly)
        synchronized(days) { days[ymd] }?.let { return@withContext it }
        mutex.withLock {
            synchronized(days) { days[ymd] }?.let { return@withLock it }
            try {
                open(ymd, wifiOnly, primary = false).also { synchronized(days) { days[ymd] = it } }
            } catch (e: Exception) {
                null
            }
        }
    }

    /** Journeys, on whichever day [time] falls. */
    suspend fun plan(from: GeoPoint, to: GeoPoint, time: Long, arriveBy: Boolean, o: RaptorOpts, wifiOnly: Boolean): PlanResult {
        val ymd = java.time.Instant.ofEpochSecond(time).atZone(ZONE).toLocalDate().format(YMD)
        val net = forDay(ymd, wifiOnly) ?: throw IOException(_status.value.error ?: "The timetable isn't loaded")
        return withContext(Dispatchers.Default) { net.plan(from, to, time, dayStart(ymd), arriveBy, o) }
    }

    /** Forget everything downloaded (Settings). */
    suspend fun clear() = mutex.withLock {
        today = null
        synchronized(days) { days.clear() }
        dir.listFiles()?.forEach { it.delete() }
        _status.value = TtStatus()
    }

    private fun tomorrow() = LocalDate.now(ZONE).plusDays(1).format(YMD)

    private fun set(f: (TtStatus) -> TtStatus) { _status.value = f(_status.value) }

    private fun open(ymd: String, wifiOnly: Boolean, primary: Boolean): Network {
        val etag = etag(wifiOnly, primary)
        val safe = etag.filter { it.isLetterOrDigit() }.take(40)
        val file = File(dir, "v${VERSION}_${safe}_$ymd.bin")
        if (file.exists()) {
            try { return Network.load(file) } catch (e: Exception) { file.delete() }
        }
        val net = Compiler.compile(File(dir, "gtfs.zip"), ymd, "$VERSION|$etag|$ymd") { text, pct ->
            if (primary) set { it.copy(text = text, pct = pct) }
        }
        net.save(file)
        // keep only today's and tomorrow's
        val keep = setOf(todayYmd(), tomorrow())
        dir.listFiles()?.filter { it.name.endsWith(".bin") && keep.none { d -> it.name.endsWith("_$d.bin") } }?.forEach { it.delete() }
        return net
    }

    /** The timetable's version, downloading a new one if it changed. */
    private fun etag(wifiOnly: Boolean, primary: Boolean): String {
        val zip = File(dir, "gtfs.zip")
        val metaFile = File(dir, "gtfs.json")
        val meta = try { JSONObject(metaFile.readText()) } catch (e: Exception) { null }
        val have = zip.exists() && meta != null
        if (have && System.currentTimeMillis() - meta!!.optLong("checked") < RECHECK_MS) return meta.getString("etag")
        val cm = app.getSystemService(ConnectivityManager::class.java)
        if (wifiOnly && cm.isActiveNetworkMetered) {
            if (have) return meta!!.getString("etag")
            throw WaitingForWifi()
        }
        try {
            val head = open("HEAD")
            val etag = (head.getHeaderField("ETag") ?: head.getHeaderField("Last-Modified")
                        ?: System.currentTimeMillis().toString()).replace("\"", "")
            head.disconnect()
            if (!have || meta!!.optString("etag") != etag) {
                val c = open("GET")
                if (c.responseCode != 200) throw IOException("AT's timetable download failed (${c.responseCode})")
                val total = c.contentLengthLong
                val part = File(dir, "gtfs.zip.part")
                c.inputStream.use { i ->
                    part.outputStream().use { o ->
                        val buf = ByteArray(1 shl 16)
                        var got = 0L
                        var lastPct = -1
                        while (true) {
                            val n = i.read(buf)
                            if (n < 0) break
                            o.write(buf, 0, n)
                            got += n
                            val pct = if (total > 0) (got * 100 / total).toInt() else 0
                            if (primary && pct != lastPct) {
                                lastPct = pct
                                set { it.copy(text = "Downloading AT's timetable… ${got / 1_000_000} of ${maxOf(1, total / 1_000_000)} MB",
                                              pct = 2 + pct * 3 / 100) }
                            }
                        }
                    }
                }
                c.disconnect()
                zip.delete()
                if (!part.renameTo(zip)) throw IOException("Couldn't save the timetable")
                set { it.copy(zipBytes = zip.length()) }
            }
            metaFile.writeText(JSONObject().put("etag", etag).put("checked", System.currentTimeMillis()).toString())
            return etag
        } catch (e: IOException) {
            // offline: yesterday's timetable is better than none
            if (have) return meta!!.getString("etag")
            throw IOException("Couldn't download AT's timetable: ${e.message}")
        }
    }

    private fun open(method: String): HttpURLConnection =
        (URL(URL_ZIP).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 60_000
        }
}
