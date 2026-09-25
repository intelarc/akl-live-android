package nz.aryan.akllive.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A real photo of a bus model: the image, its page on Wikimedia Commons, and who to credit. */
class Photo(val url: String, val page: String, val credit: String)

/**
 * Photos of each bus model from Wikimedia Commons (freely licensed, credited
 * under the photo), found by searching for the model in Auckland. Looked up
 * once a fortnight per model and kept on the phone.
 */
object Photos {
    private const val KEEP_MS = 14L * 24 * 3600 * 1000
    private val mem = HashMap<String, Photo?>()
    private val lock = Mutex()

    suspend fun forModel(ctx: Context, m: BusModel): Photo? = lock.withLock {
        if (mem.containsKey(m.id)) return@withLock mem[m.id]
        val prefs = ctx.getSharedPreferences("photos", Context.MODE_PRIVATE)
        prefs.getString(m.id, null)?.let { raw ->
            val o = runCatching { JSONObject(raw) }.getOrNull()
            if (o != null && System.currentTimeMillis() - o.optLong("t") < KEEP_MS) {
                val p = o.optString("url").takeIf { it.isNotEmpty() }?.let { Photo(it, o.optString("page"), o.optString("credit")) }
                mem[m.id] = p
                return@withLock p
            }
        }
        val found = try {
            search("${m.name} Auckland") ?: search("${m.short} Auckland bus") ?: search("${m.name} bus")
        } catch (e: Exception) {
            return@withLock null                     // offline: try again next time
        }
        mem[m.id] = found
        prefs.edit().putString(m.id, JSONObject().put("t", System.currentTimeMillis()).put("url", found?.url ?: "")
            .put("page", found?.page ?: "").put("credit", found?.credit ?: "").toString()).apply()
        found
    }

    private suspend fun search(q: String): Photo? {
        val url = "https://commons.wikimedia.org/w/api.php?action=query&format=json&generator=search&gsrnamespace=6&gsrlimit=8" +
            "&gsrsearch=" + URLEncoder.encode("$q filetype:bitmap", "UTF-8") +
            "&prop=imageinfo&iiprop=url%7Cextmetadata%7Cmime&iiurlwidth=800"
        val j = httpJson(url) as? JSONObject ?: throw java.io.IOException("Commons didn't answer")
        val pages = j.optJSONObject("query")?.optJSONObject("pages") ?: return null
        val best = pages.keys().asSequence().mapNotNull { pages.optJSONObject(it) }
            .filter { p -> p.optJSONArray("imageinfo")?.optJSONObject(0)?.optString("mime")?.let { it == "image/jpeg" || it == "image/png" } == true }
            .minByOrNull { it.optInt("index", 99) } ?: return null
        val info = best.getJSONArray("imageinfo").getJSONObject(0)
        val meta = info.optJSONObject("extmetadata")
        fun field(k: String) = meta?.optJSONObject(k)?.optString("value")?.replace(Regex("<[^>]+>"), "")?.trim()?.takeIf { it.isNotEmpty() }
        val credit = listOfNotNull(field("Artist")?.take(60), field("LicenseShortName")).joinToString(", ")
        return Photo(info.optString("thumburl").ifEmpty { info.optString("url") }, info.optString("descriptionurl"),
                     "Photo: " + credit.ifEmpty { "Wikimedia Commons" } + if (credit.isNotEmpty()) ", Wikimedia Commons" else "")
    }
}

/** Downloaded images, kept in memory and on disk. */
object Images {
    private val mem = LruCache<String, Bitmap>(24)

    suspend fun load(ctx: Context, url: String): Bitmap? = withContext(Dispatchers.IO) {
        mem.get(url)?.let { return@withContext it }
        val dir = File(ctx.cacheDir, "img").also { it.mkdirs() }
        val file = File(dir, Integer.toHexString(url.hashCode()) + ".img")
        try {
            if (!file.exists()) {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 15_000
                c.readTimeout = 30_000
                c.setRequestProperty("User-Agent", "AKL-Live-Android/1.0 (github.com/intelarc/akl-live-android)")
                try {
                    if (c.responseCode != 200) return@withContext null
                    val part = File(dir, file.name + ".part")
                    c.inputStream.use { i -> part.outputStream().use { o -> i.copyTo(o) } }
                    part.renameTo(file)
                } finally {
                    c.disconnect()
                }
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 900) sample *= 2
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.also { mem.put(url, it) }
        } catch (e: Exception) {
            null
        }
    }
}
