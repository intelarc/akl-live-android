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

/** A real photo of a bus model: the image, the page it's from, and who to credit. */
class Photo(val url: String, val page: String, val credit: String)

/**
 * Photos of each bus model, from its page on the AT Metro Wiki (the lead photo,
 * CC BY-SA), or failing that a Wikimedia Commons photo whose name says it's this
 * very model. Looked up once a fortnight per model and kept on the phone, and
 * fetched ahead of time so they're there when a bus turns up.
 */
object Photos {
    private const val KEEP_MS = 14L * 24 * 3600 * 1000
    private const val MISS_MS = 24L * 3600 * 1000
    // bumped when the lookup changes, so a wrong photo from before isn't kept
    private const val VERSION = 3
    private const val WIKI = "https://atmetro.fandom.com"
    private val mem = HashMap<String, Photo?>()
    private val lock = Mutex()

    suspend fun forModel(ctx: Context, m: BusModel): Photo? = lock.withLock {
        if (mem.containsKey(m.id)) return@withLock mem[m.id]
        val prefs = ctx.getSharedPreferences("photos", Context.MODE_PRIVATE)
        prefs.getString("v$VERSION:" + m.id, null)?.let { raw ->
            val o = runCatching { JSONObject(raw) }.getOrNull()
            val url = o?.optString("url").orEmpty()
            // a photo keeps a fortnight; not finding one is tried again tomorrow
            if (o != null && System.currentTimeMillis() - o.optLong("t") < (if (url.isEmpty()) MISS_MS else KEEP_MS)) {
                val p = url.takeIf { it.isNotEmpty() }?.let { Photo(it, o.optString("page"), o.optString("credit")) }
                mem[m.id] = p
                return@withLock p
            }
        }
        val found = try { find(m) } catch (e: Exception) { return@withLock null }      // offline: try again next time
        mem[m.id] = found
        prefs.edit().putString("v$VERSION:" + m.id, JSONObject().put("t", System.currentTimeMillis()).put("url", found?.url ?: "")
            .put("page", found?.page ?: "").put("credit", found?.credit ?: "").toString()).apply()
        found
    }

    /** Looks up and downloads every model's photo in the background, so they show straight away later. */
    suspend fun prefetch(ctx: Context) {
        for (m in Fleet.models) {
            val p = try { forModel(ctx, m) } catch (e: Exception) { null } ?: continue
            Images.download(ctx, p.url)
        }
    }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun wikiPage(title: String) = "$WIKI/wiki/" + enc(title.replace(' ', '_')).replace("%2F", "/")
    private fun api(q: String) = "$WIKI/api.php?action=query&format=json&redirects=1&$q"

    /** Its own page on the wiki; a page the wiki's search turns up for it; then Commons, only if the file is plainly this model. */
    private suspend fun find(m: BusModel): Photo? {
        val key = norm(m.key.ifEmpty { m.short.substringBefore(' ') })
        val titles = m.wiki.ifEmpty { listOf(m.name) }
        leadPhoto(titles)?.let { return it }
        val found = (httpJson(api("list=search&srnamespace=0&srlimit=8&srsearch=" + enc(m.name))) as? JSONObject)
            ?.optJSONObject("query")?.optJSONArray("search")
            ?.let { r -> (0 until r.length()).map { r.getJSONObject(it).optString("title") } }
            ?.filter { norm(it).contains(key) }.orEmpty()
        if (found.isNotEmpty()) leadPhoto(found)?.let { return it }
        for (t in (titles + found).distinct()) pagePhoto(t, key)?.let { return it }
        for (q in listOf("${m.name} Auckland", m.name, "${m.short} bus")) commons(q, key)?.let { return it }
        return null
    }

    /** The lead photo of the first of these pages that has one. */
    private suspend fun leadPhoto(titles: List<String>): Photo? {
        val j = httpJson(api("prop=pageimages&piprop=thumbnail&pithumbsize=800&titles=" + enc(titles.joinToString("|"))))
            as? JSONObject ?: throw java.io.IOException("The wiki didn't answer")
        val q = j.optJSONObject("query") ?: return null
        // follow "normalized" and "redirects" so the answer lines up with what was asked
        val moved = HashMap<String, String>()
        for (k in listOf("normalized", "redirects")) q.optJSONArray(k)?.let { a ->
            for (i in 0 until a.length()) a.getJSONObject(i).let { moved[it.optString("from")] = it.optString("to") }
        }
        fun resolve(t: String): String { var x = t; repeat(3) { x = moved[x] ?: return x }; return x }
        val pages = q.optJSONObject("pages") ?: return null
        val byTitle = pages.keys().asSequence().mapNotNull { pages.optJSONObject(it) }.associateBy { it.optString("title") }
        for (t in titles) {
            val page = byTitle[resolve(t)] ?: continue
            val src = page.optJSONObject("thumbnail")?.optString("source").orEmpty()
            if (src.isNotEmpty() && !skip(src)) return Photo(src, wikiPage(page.optString("title")), "Photo: AT Metro Wiki, CC BY-SA")
        }
        return null
    }

    private val NOT_A_BUS = Regex("(?i)logo|icon|wordmark|favicon|placeholder|\\bmap\\b|route_?map|diagram|\\.svg|\\.gif")

    private fun skip(name: String) = NOT_A_BUS.containsMatchIn(name)

    /** When the wiki doesn't mark a lead photo: the photos on the model's page, ones naming the model first. */
    private suspend fun pagePhoto(title: String, key: String): Photo? {
        val j = httpJson(api("prop=images&imlimit=50&titles=" + enc(title))) as? JSONObject ?: return null
        val pages = j.optJSONObject("query")?.optJSONObject("pages") ?: return null
        val page = pages.keys().asSequence().mapNotNull { pages.optJSONObject(it) }.firstOrNull { !it.has("missing") } ?: return null
        val files = page.optJSONArray("images")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("title") } }
            ?.filter { !skip(it) && Regex("(?i)\\.(jpe?g|png|webp)$").containsMatchIn(it) }.orEmpty()
        if (files.isEmpty()) return null
        val ordered = files.sortedByDescending { norm(it).contains(key) }.take(20)
        val info = httpJson(api("prop=imageinfo&iiprop=url%7Cmime&iiurlwidth=800&titles=" + enc(ordered.joinToString("|"))))
            as? JSONObject ?: return null
        val ip = info.optJSONObject("query")?.optJSONObject("pages") ?: return null
        val urls = ip.keys().asSequence().mapNotNull { ip.optJSONObject(it) }.associate { p ->
            p.optString("title") to p.optJSONArray("imageinfo")?.optJSONObject(0)?.let { it.optString("thumburl").ifEmpty { it.optString("url") } }
        }
        val best = ordered.firstNotNullOfOrNull { t -> urls[t]?.takeIf { it.isNotEmpty() } } ?: return null
        return Photo(best, wikiPage(page.optString("title")), "Photo: AT Metro Wiki, CC BY-SA")
    }

    // things that come up searching for a model number and are anything but a bus
    private val OFF_TOPIC = Regex("(?i)ship|boat|launch|ferry|yacht|vessel|aircraft|plane|locomotive|tram|truck|lorry|car\\b|tractor|map|logo")

    /** A Commons photo, only when its file name has the model in it and says bus or Auckland. */
    private suspend fun commons(q: String, key: String): Photo? {
        val url = "https://commons.wikimedia.org/w/api.php?action=query&format=json&generator=search&gsrnamespace=6&gsrlimit=15" +
            "&gsrsearch=" + enc("$q filetype:bitmap") + "&prop=imageinfo&iiprop=url%7Cextmetadata%7Cmime&iiurlwidth=800"
        val j = httpJson(url) as? JSONObject ?: return null
        val pages = j.optJSONObject("query")?.optJSONObject("pages") ?: return null
        val best = pages.keys().asSequence().mapNotNull { pages.optJSONObject(it) }
            .filter { p -> p.optJSONArray("imageinfo")?.optJSONObject(0)?.optString("mime")?.let { it == "image/jpeg" || it == "image/png" } == true }
            .filter { p ->
                val t = p.optString("title")
                norm(t).contains(key) && !OFF_TOPIC.containsMatchIn(t) &&
                    Regex("(?i)bus|auckland|nz|metro|link|kinetic|ritchies|go ?bus|tranzurban|howick").containsMatchIn(t)
            }
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

    /** Kept in files, not the cache folder, so Android doesn't clear them out from under us. */
    private fun fileFor(ctx: Context, url: String): File =
        File(File(ctx.filesDir, "photos").also { it.mkdirs() }, Integer.toHexString(url.hashCode()) + ".img")

    /** Makes sure the image is on the phone; true when it is. */
    suspend fun download(ctx: Context, url: String): Boolean = withContext(Dispatchers.IO) {
        val file = fileFor(ctx, url)
        if (file.exists()) return@withContext true
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "AKL-Live-Android/1.0 (github.com/intelarc/akl-live-android)")
            try {
                if (c.responseCode != 200) return@withContext false
                val part = File(file.parentFile, file.name + ".part")
                c.inputStream.use { i -> part.outputStream().use { o -> i.copyTo(o) } }
                part.renameTo(file)
            } finally {
                c.disconnect()
            }
        } catch (e: Exception) {
            false
        }
    }

    suspend fun load(ctx: Context, url: String): Bitmap? = withContext(Dispatchers.IO) {
        mem.get(url)?.let { return@withContext it }
        val file = fileFor(ctx, url)
        try {
            if (!download(ctx, url)) return@withContext null
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
