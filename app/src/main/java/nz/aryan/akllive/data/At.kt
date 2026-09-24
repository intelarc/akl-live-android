package nz.aryan.akllive.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** New Zealand time: GTFS times are local, and so is everything we show. */
object Nz {
    val zone: ZoneId = ZoneId.of("Pacific/Auckland")
    private val hmFmt = DateTimeFormatter.ofPattern("h:mm", Locale.ENGLISH)
    private val ampmFmt = DateTimeFormatter.ofPattern("a", Locale.ENGLISH)
    private val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    fun nowSec(): Long = System.currentTimeMillis() / 1000
    fun now(): ZonedDateTime = ZonedDateTime.now(zone)

    fun at(epochSec: Long): ZonedDateTime = Instant.ofEpochSecond(epochSec).atZone(zone)

    /** "3:42 pm" */
    fun time(epochSec: Long): String {
        val t = at(epochSec)
        return t.format(hmFmt) + " " + t.format(ampmFmt).lowercase(Locale.ENGLISH)
    }

    /** The hour of day as a fraction, e.g. 18.5 at half past six. */
    fun hour(): Float {
        val t = now()
        return t.hour + t.minute / 60f + t.second / 3600f
    }

    fun date(t: ZonedDateTime): String = t.format(dateFmt)

    /** A GTFS "HH:MM:SS" (may run past 24:00) on a service date -> epoch seconds. */
    fun serviceEpoch(date: String, hms: String): Long {
        val p = hms.split(":")
        val secs = p[0].toInt() * 3600L + p[1].toInt() * 60L + p.getOrElse(2) { "0" }.toInt()
        val d = if (date.contains('-')) LocalDate.parse(date)
                else LocalDate.parse(date, DateTimeFormatter.BASIC_ISO_DATE)
        return d.atStartOfDay(zone).toEpochSecond() + secs
    }
}

/** Auckland Transport's developer API. */
class AtApi(private val key: () -> String) {
    suspend fun get(path: String): JSONObject? = withContext(Dispatchers.IO) {
        val k = key()
        if (k.isBlank()) throw IOException("No AT API key yet: get a free one at dev-portal.at.govt.nz and paste it in Settings")
        val c = URL("https://api.at.govt.nz$path").openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 25_000
        c.setRequestProperty("Ocp-Apim-Subscription-Key", k)
        c.setRequestProperty("Accept", "application/json")
        try {
            when (val code = c.responseCode) {
                200 -> JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                404 -> null                          // stoptrips: no services in the window
                401 -> throw IOException("AT rejected the API key (401)")
                else -> throw IOException("AT API error $code")
            }
        } finally {
            c.disconnect()
        }
    }
}

// ---------- small JSON helpers ----------
internal fun JSONObject.arr(name: String): JSONArray = optJSONArray(name) ?: JSONArray()
internal fun JSONObject.obj(name: String): JSONObject? = optJSONObject(name)
internal inline fun JSONArray.objects(block: (JSONObject) -> Unit) {
    for (i in 0 until length()) optJSONObject(i)?.let(block)
}
internal fun JSONObject.intOrNull(name: String): Int? =
    if (has(name) && !isNull(name)) optDouble(name).toInt() else null
internal fun JSONObject.doubleOrNull(name: String): Double? =
    if (has(name) && !isNull(name)) optDouble(name).takeIf { !it.isNaN() } else null
internal fun JSONObject.longOrNull(name: String): Long? =
    if (has(name) && !isNull(name)) optDouble(name).toLong() else null

/** The realtime feed's stop_time_update is sometimes an object, sometimes a list. */
internal fun JSONObject.stopTimeUpdate(): JSONObject? {
    val any = opt("stop_time_update") ?: return null
    return when (any) {
        is JSONObject -> any
        is JSONArray -> if (any.length() > 0) any.optJSONObject(any.length() - 1) else null
        else -> null
    }
}

/** "Waikowhai To Britomart Via Hillsborough Rd" -> "Britomart";
 *  "Pukekohe 4 Clockwise To Newmarket 2 Via NKT1" -> "Newmarket". */
fun cleanHeadsign(raw: String): String {
    var s = raw
    val to = s.indexOf(" to ", ignoreCase = true)
    if (to >= 0) s = s.substring(to + 4)
    val via = s.indexOf(" via ", ignoreCase = true)
    if (via >= 0) s = s.substring(0, via)
    s = s.trim().replace(Regex("\\s+\\d+$"), "")
    // give station names their macrons
    MapData.STATIONS.firstOrNull { it.key.equals(s, ignoreCase = true) }?.let { return it.name }
    return s.split(' ').joinToString(" ") { w ->
        if (w.length > 3 && w == w.uppercase()) w.lowercase().replaceFirstChar { it.titlecase() } else w
    }
}

/** GTFS-realtime occupancy, in words. */
fun occupancyText(o: Int?): String? = when (o) {
    0 -> "Empty"
    1 -> "Plenty of seats"
    2 -> "Few seats left"
    3 -> "Standing room"
    4 -> "Very full"
    5 -> "Full"
    6 -> "Not taking passengers"
    else -> null
}
