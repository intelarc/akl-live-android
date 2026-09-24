package nz.aryan.akllive.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Auckland's weather right now, from Open-Meteo (free, no key), for the bus scenes. */
data class Weather(
    val tempC: Double,
    /** WMO weather code: 0 clear, 1-3 cloud, 45/48 fog, 51-67 drizzle/rain, 80-82 showers, 95+ thunder */
    val code: Int,
    val cloudCover: Int,
    val precipMm: Double,
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

    fun describe(night: Boolean): String = when {
        thunder -> "⛈ Thunder"
        rain >= 2 && code in 80..82 -> "🌦 Showers"
        rain >= 2 -> "🌧 Rain"
        rain == 1 -> "🌦 Drizzle"
        foggy -> "🌫 Fog"
        code == 3 || cloudCover > 85 -> "☁ Overcast"
        code in 1..2 || cloudCover > 30 -> if (night) "☁ Partly cloudy" else "⛅ Partly cloudy"
        else -> if (night) "☾ Clear" else "☀ Sunny"
    }

    companion object {
        suspend fun fetch(): Weather? = withContext(Dispatchers.IO) {
            val c = URL("https://api.open-meteo.com/v1/forecast?latitude=-36.87&longitude=174.76" +
                        "&current=temperature_2m,weather_code,cloud_cover,precipitation&timezone=Pacific%2FAuckland")
                .openConnection() as HttpURLConnection
            c.connectTimeout = 15_000
            c.readTimeout = 20_000
            try {
                if (c.responseCode != 200) return@withContext null
                val cur = JSONObject(c.inputStream.bufferedReader().use { it.readText() }).obj("current")
                    ?: return@withContext null
                Weather(
                    tempC = cur.optDouble("temperature_2m"),
                    code = cur.optInt("weather_code"),
                    cloudCover = cur.optInt("cloud_cover"),
                    precipMm = cur.optDouble("precipitation", 0.0),
                )
            } finally {
                c.disconnect()
            }
        }
    }
}
