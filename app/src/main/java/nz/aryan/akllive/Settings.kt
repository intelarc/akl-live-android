package nz.aryan.akllive

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import nz.aryan.akllive.ui.Basemap
import nz.aryan.akllive.ui.Palette
import nz.aryan.akllive.ui.ThemeMode

enum class PlaceKind { Home, Work, Stop, Recent, Pin, Place, Here }

/** Somewhere to go from or to: an address, a stop, home. */
data class Place(
    val name: String,
    val sub: String,
    val lat: Double,
    val lon: Double,
    val kind: PlaceKind = PlaceKind.Place,
    val stopId: String? = null,
    val code: String? = null,
    /** 1 bus, 2 train, 4 ferry, for stops */
    val modes: Int = 0,
) {
    fun same(o: Place) = Math.abs(lat - o.lat) < 1e-5 && Math.abs(lon - o.lon) < 1e-5

    fun json(): JSONObject = JSONObject().put("name", name).put("sub", sub).put("lat", lat).put("lon", lon)
        .put("kind", kind.name).put("stopId", stopId ?: "").put("code", code ?: "").put("modes", modes)

    companion object {
        fun from(o: JSONObject?): Place? = o?.let {
            Place(it.optString("name"), it.optString("sub"), it.optDouble("lat"), it.optDouble("lon"),
                  runCatching { PlaceKind.valueOf(it.optString("kind")) }.getOrDefault(PlaceKind.Place),
                  it.optString("stopId").ifEmpty { null }, it.optString("code").ifEmpty { null }, it.optInt("modes"))
        }
    }
}

/** A stop you've starred, with an optional nickname ("Work stop"). */
data class FavStop(
    val id: String,
    val code: String,
    val name: String,
    val nickname: String = "",
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val modes: Int = 1,
) {
    val title get() = nickname.ifBlank { name }
    fun json(): JSONObject = JSONObject().put("id", id).put("code", code).put("name", name).put("nick", nickname)
        .put("lat", lat).put("lon", lon).put("modes", modes)

    companion object {
        fun from(o: JSONObject) = FavStop(o.optString("id"), o.optString("code"), o.optString("name"),
                                          o.optString("nick"), o.optDouble("lat", 0.0), o.optDouble("lon", 0.0),
                                          o.optInt("modes", 1))
    }
}

/** A bus model you've seen come to your stop (the fleet dex). */
data class Spotted(val count: Int, val first: Long, val last: Long, val fleetNo: String)

data class PlanOpts(
    val maxWalk: Int = 900,
    val walkSpeed: Double = 1.3,
    val bus: Boolean = true,
    val train: Boolean = true,
    val ferry: Boolean = true,
    val maxRides: Int = 4,
)

/** Everything the user has set. Immutable: change it with AppViewModel.update. */
data class Settings(
    val apiKey: String = "",
    val linzKey: String = "",
    /** the stops on the Home screen, with their live scenes */
    val stops: List<String> = listOf("8669", "8664"),
    val route: String = "27H",
    val place: String = "Hillsborough",
    val keepOn: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.System,
    val palette: Palette = Palette.Waitemata,
    val dynamicColor: Boolean = false,
    val pureBlack: Boolean = false,
    val basemap: Basemap = Basemap.Satellite,
    /** the Trains map: the quiet diagram, satellite or streets */
    val trainView: Basemap = Basemap.Diagram,
    /** how long it takes to walk to your stop, for "leave in" */
    val walkMin: Int = 4,
    /** heads-up this many minutes before a tracked bus */
    val alertMin: Int = 5,
    val haptics: Boolean = true,
    val shake: Boolean = true,
    /** Kiwi turns of phrase here and there */
    val kiwi: Boolean = true,
    val home: Place? = null,
    val work: Place? = null,
    val recents: List<Place> = emptyList(),
    val favs: List<FavStop> = emptyList(),
    val spotted: Map<String, Spotted> = emptyMap(),
    val planOpts: PlanOpts = PlanOpts(),
    /** only download the 29 MB timetable on Wi-Fi */
    val wifiOnly: Boolean = false,
) {
    fun isFav(id: String) = favs.any { it.id == id || it.code == id }
}

/** Settings on disk (SharedPreferences, the same file older versions used). */
class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("akl", Context.MODE_PRIVATE)

    fun load(): Settings {
        val d = Settings()
        return Settings(
            apiKey = p.getString("key", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.AT_API_KEY,
            linzKey = p.getString("linz", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.LINZ_API_KEY,
            stops = (p.getString("stops", null) ?: "8669,8664").split(',').map { it.trim() }.filter { it.isNotEmpty() },
            route = p.getString("route", null) ?: d.route,
            place = p.getString("place", null) ?: d.place,
            keepOn = p.getBoolean("keepOn", false),
            themeMode = enumOr(p.getString("themeMode", null), d.themeMode),
            palette = enumOr(p.getString("palette", null), d.palette),
            dynamicColor = p.getBoolean("dynamic", false),
            pureBlack = p.getBoolean("pureBlack", false),
            basemap = if (p.getString("basemap", null) == "streets") Basemap.Streets else Basemap.Satellite,
            trainView = enumOr(p.getString("trainView", null), d.trainView),
            walkMin = p.getInt("walkMin", d.walkMin),
            alertMin = p.getInt("alertMin", d.alertMin),
            haptics = p.getBoolean("haptics", true),
            shake = p.getBoolean("shake", true),
            kiwi = p.getBoolean("kiwi", true),
            home = Place.from(obj("home")),
            work = Place.from(obj("work")),
            recents = arr("recents").mapNotNull { Place.from(it) },
            favs = arr("favs").map { FavStop.from(it) },
            spotted = obj("spotted")?.let { o ->
                o.keys().asSequence().associateWith { k ->
                    val s = o.getJSONObject(k)
                    Spotted(s.optInt("n"), s.optLong("first"), s.optLong("last"), s.optString("no"))
                }
            } ?: emptyMap(),
            planOpts = obj("planOpts")?.let {
                PlanOpts(it.optInt("maxWalk", 900), it.optDouble("walkSpeed", 1.3), it.optBoolean("bus", true),
                         it.optBoolean("train", true), it.optBoolean("ferry", true), it.optInt("maxRides", 4))
            } ?: PlanOpts(),
            wifiOnly = p.getBoolean("wifiOnly", false),
        )
    }

    fun save(s: Settings) {
        val spotted = JSONObject()
        s.spotted.forEach { (k, v) ->
            spotted.put(k, JSONObject().put("n", v.count).put("first", v.first).put("last", v.last).put("no", v.fleetNo))
        }
        val o = s.planOpts
        p.edit()
            // a built-in key isn't saved, so a later build's key still applies
            .putString("key", if (s.apiKey == BuildConfig.AT_API_KEY) "" else s.apiKey)
            .putString("linz", if (s.linzKey == BuildConfig.LINZ_API_KEY) "" else s.linzKey)
            .putString("stops", s.stops.joinToString(","))
            .putString("route", s.route)
            .putString("place", s.place)
            .putBoolean("keepOn", s.keepOn)
            .putString("themeMode", s.themeMode.name)
            .putString("palette", s.palette.name)
            .putBoolean("dynamic", s.dynamicColor)
            .putBoolean("pureBlack", s.pureBlack)
            .putString("basemap", if (s.basemap == Basemap.Streets) "streets" else "satellite")
            .putString("trainView", s.trainView.name)
            .putInt("walkMin", s.walkMin)
            .putInt("alertMin", s.alertMin)
            .putBoolean("haptics", s.haptics)
            .putBoolean("shake", s.shake)
            .putBoolean("kiwi", s.kiwi)
            .putString("home", s.home?.json()?.toString() ?: "")
            .putString("work", s.work?.json()?.toString() ?: "")
            .putString("recents", JSONArray(s.recents.map { it.json() }).toString())
            .putString("favs", JSONArray(s.favs.map { it.json() }).toString())
            .putString("spotted", spotted.toString())
            .putString("planOpts", JSONObject().put("maxWalk", o.maxWalk).put("walkSpeed", o.walkSpeed)
                .put("bus", o.bus).put("train", o.train).put("ferry", o.ferry).put("maxRides", o.maxRides).toString())
            .putBoolean("wifiOnly", s.wifiOnly)
            .apply()
    }

    /** A small cache the widget and the quick settings tile read: the next bus each way. */
    var glance: String
        get() = p.getString("glance", "") ?: ""
        set(v) = p.edit().putString("glance", v).apply()

    private fun obj(k: String): JSONObject? = p.getString(k, null)?.takeIf { it.startsWith("{") }
        ?.let { runCatching { JSONObject(it) }.getOrNull() }

    private fun arr(k: String): List<JSONObject> = p.getString(k, null)?.takeIf { it.startsWith("[") }
        ?.let { runCatching { JSONArray(it) }.getOrNull() }
        ?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } } ?: emptyList()

    private inline fun <reified E : Enum<E>> enumOr(v: String?, d: E): E =
        v?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: d
}
