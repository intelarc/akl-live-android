package nz.aryan.akllive.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** One hour of the forecast. */
data class WeatherHour(val hour: Int, val tempC: Double, val code: Int, val rainChance: Int)

/** Auckland's weather right now and the next hours, from Open-Meteo (free, no key). */
data class Weather(
    val tempC: Double,
    /** WMO weather code: 0 clear, 1-3 cloud, 45/48 fog, 51-67 drizzle/rain, 80-82 showers, 95+ thunder */
    val code: Int,
    val cloudCover: Int,
    val precipMm: Double,
    val windKmh: Double = 0.0,
    val hourly: List<WeatherHour> = emptyList(),
) {
    val foggy get() = code == 45 || code == 48
    val thunder get() = code >= 95
    /** 0 none, 1 drizzle, 2 rain, 3 heavy */
    val rain: Int get() = when {
        code in 51..57 -> 1
        code in 61..67 || code in 80..82 || thunder -> if (code == 65 || code == 67 || code == 82 || precipMm > 4) 3 else 2
        precipMm > 0.2 -> 1
        else -> 0
    }
    /** rain on the way in the next few hours */
    val rainSoon: Boolean get() = hourly.take(4).any { it.rainChance >= 50 }

    fun describe(night: Boolean): String = "${emoji(code, cloudCover, night)} ${words(code, cloudCover, night)}"

    companion object {
        fun emoji(code: Int, cloud: Int, night: Boolean) = when {
            code >= 95 -> "⛈"
            code in 80..82 -> "🌦"
            code in 61..67 -> "🌧"
            code in 51..57 -> "🌦"
            code == 45 || code == 48 -> "🌫"
            code == 3 || cloud > 85 -> "☁"
            code in 1..2 || cloud > 30 -> if (night) "☁" else "⛅"
            else -> if (night) "☾" else "☀"
        }

        fun words(code: Int, cloud: Int, night: Boolean) = when {
            code >= 95 -> "Thunder"
            code in 80..82 -> "Showers"
            code in 61..67 -> "Rain"
            code in 51..57 -> "Drizzle"
            code == 45 || code == 48 -> "Fog"
            code == 3 || cloud > 85 -> "Overcast"
            code in 1..2 || cloud > 30 -> "Partly cloudy"
            else -> if (night) "Clear" else "Sunny"
        }

        suspend fun fetch(): Weather? = withContext(Dispatchers.IO) {
            val c = URL("https://api.open-meteo.com/v1/forecast?latitude=-36.87&longitude=174.76" +
                        "&current=temperature_2m,weather_code,cloud_cover,precipitation,wind_speed_10m" +
                        "&hourly=temperature_2m,weather_code,precipitation_probability&forecast_hours=12" +
                        "&timezone=Pacific%2FAuckland")
                .openConnection() as HttpURLConnection
            c.connectTimeout = 15_000
            c.readTimeout = 20_000
            try {
                if (c.responseCode != 200) return@withContext null
                val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                val cur = j.obj("current") ?: return@withContext null
                val h = j.obj("hourly")
                val times = h?.optJSONArray("time")
                val hours = if (h == null || times == null) emptyList() else (0 until times.length()).map { i ->
                    WeatherHour(
                        hour = times.optString(i).substringAfter('T').substringBefore(':').toIntOrNull() ?: 0,
                        tempC = h.optJSONArray("temperature_2m")?.optDouble(i) ?: 0.0,
                        code = h.optJSONArray("weather_code")?.optInt(i) ?: 0,
                        rainChance = h.optJSONArray("precipitation_probability")?.optInt(i) ?: 0,
                    )
                }
                Weather(
                    tempC = cur.optDouble("temperature_2m"),
                    code = cur.optInt("weather_code"),
                    cloudCover = cur.optInt("cloud_cover"),
                    precipMm = cur.optDouble("precipitation", 0.0),
                    windKmh = cur.optDouble("wind_speed_10m", 0.0),
                    hourly = hours,
                )
            } finally {
                c.disconnect()
            }
        }
    }
}
