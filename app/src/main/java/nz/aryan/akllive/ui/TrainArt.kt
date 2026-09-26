package nz.aryan.akllive.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import nz.aryan.akllive.data.Train
import nz.aryan.akllive.gtfs.Pt
import nz.aryan.akllive.gtfs.Rail
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.Style
import org.maplibre.android.snapshotter.MapSnapshotter
import org.maplibre.android.style.sources.GeoJsonSource
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.tan

/**
 * The train map for the home-screen widget: the app's Diagram view (the quiet
 * map, the lines along their real tracks side by side, the stations and their
 * names), drawn once off screen at the widget's size and kept on the phone,
 * with the trains put on it each time, as in the app.
 *
 * Drawing the map needs the internet (the map's tiles and fonts), which a
 * phone may not give an app in the background, so it's done whenever it can
 * be (the app open is the sure time) and the saved one is used after that.
 */
object TrainArt {
    private const val VERSION = 1
    private val lock = Mutex()
    private val SHOWN = setOf(0, 1, 2)

    /** Where the saved map is looking, to put trains on it: Web Mercator centre, world size in dp, bitmap px per dp. */
    private class Frame(val cx: Double, val cy: Double, val world: Double, val w: Int, val h: Int, val ratio: Float) {
        fun x(lon: Double) = ((w / 2.0 + (mx(lon) - cx) * world) * ratio).toFloat()
        fun y(lat: Double) = ((h / 2.0 + (my(lat) - cy) * world) * ratio).toFloat()
    }

    /**
     * The map at [w] x [h] dp with every train on it; [top] dp are kept clear for the
     * widget's header. Null when there's no map yet and it can't be drawn right now.
     */
    suspend fun render(ctx: Context, trains: List<Train>, w: Int, h: Int, top: Int): Bitmap? = lock.withLock {
        val app = ctx.applicationContext
        val geo = withContext(Dispatchers.Default) { TrainNet.geo(app) }
        val key = "$VERSION|$w|$h|$top|${geo.real}"
        val dir = File(app.filesDir, "trainart").also { it.mkdirs() }
        val png = File(dir, "base.png")
        val meta = File(dir, "base.json")
        var saved = load(png, meta)
        // draw the map again when it's missing, the widget's changed size, or the real tracks are in
        if (saved == null || saved.first != key) {
            draw(app, geo, w, h, top)?.let { (bmp, frame) ->
                save(png, meta, key, bmp, frame)
                saved = Triple(key, bmp, frame)
            }
        }
        val (_, base, frame) = saved ?: return@withLock null
        withContext(Dispatchers.Default) { withTrains(base, frame, trains, geo) }
    }

    private fun load(png: File, meta: File): Triple<String, Bitmap, Frame>? = try {
        if (!png.exists() || !meta.exists()) null
        else {
            val j = JSONObject(meta.readText())
            val bmp = BitmapFactory.decodeFile(png.path)
            if (bmp == null) null
            else Triple(j.getString("key"), bmp, Frame(j.getDouble("cx"), j.getDouble("cy"), j.getDouble("world"),
                                                       j.getInt("w"), j.getInt("h"), j.getDouble("ratio").toFloat()))
        }
    } catch (e: Exception) {
        null
    }

    private fun save(png: File, meta: File, key: String, bmp: Bitmap, f: Frame) {
        try {
            val part = File(png.path + ".part")
            part.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            part.renameTo(png)
            meta.writeText(JSONObject().put("key", key).put("cx", f.cx).put("cy", f.cy).put("world", f.world)
                               .put("w", f.w).put("h", f.h).put("ratio", f.ratio.toDouble()).toString())
        } catch (_: Exception) { }
    }

    /** The map with the lines and stations, no trains: MapLibre, off screen, framed on the network below the header. */
    private suspend fun draw(ctx: Context, geo: nz.aryan.akllive.gtfs.RailGeo, w: Int, h: Int, top: Int): Pair<Bitmap, Frame>? {
        val akl = withContext(Dispatchers.Default) {
            val d = Rail.layout(TrainNet.stations, geo, SHOWN)
            val (stops, links) = TrainNet.stopsAndLinks(d, geo, SHOWN, null, dark = true)
            aklData(TrainNet.lines(d, dark = true), links, stops)
        }
        // frame the network: fit it inside the widget less the header and a margin, as the app does
        val fit = TrainNet.fit(SHOWN)
        val x0 = fit.minOf { mx(it.second) }
        val x1 = fit.maxOf { mx(it.second) }
        val y0 = fit.minOf { my(it.first) }
        val y1 = fit.maxOf { my(it.first) }
        val padL = 14.0
        val padR = 14.0
        val padT = top + 6.0
        val padB = 12.0
        val world = minOf((w - padL - padR) / (x1 - x0), (h - padT - padB) / (y1 - y0))
        val cx = (x0 + x1) / 2 - (padL - padR) / 2 / world
        val cy = (y0 + y1) / 2 - (padT - padB) / 2 / world
        val zoom = log2(world / 512.0)
        val scale = ctx.resources.displayMetrics.density.coerceIn(1f, 2.5f)
        val shot = withContext(Dispatchers.Main) {
            withTimeoutOrNull(25_000) {
                MapLibre.getInstance(ctx)
                val style = Style.Builder().fromJson(MapStyles.diagramJson(dark = true))
                    .withSources(GeoJsonSource("akl-lines", akl.first), GeoJsonSource("akl-links", akl.second),
                                 GeoJsonSource("akl-stops", akl.third))
                var below = "dg-places"
                for (layer in aklLayers(Basemap.Diagram, dark = true)) {
                    style.withLayerAbove(layer, below)
                    below = layer.id
                }
                val options = MapSnapshotter.Options(w, h)
                    .withStyleBuilder(style)
                    .withCameraPosition(CameraPosition.Builder().target(LatLng(lat(cy), lon(cx))).zoom(zoom).build())
                    .withPixelRatio(scale)
                    .withLogo(false)
                    .withAttribution(false)
                suspendCancellableCoroutine<Bitmap?> { cont ->
                    val snapper = MapSnapshotter(ctx, options)
                    cont.invokeOnCancellation { try { snapper.cancel() } catch (_: Exception) { } }
                    snapper.start({ s -> if (cont.isActive) cont.resume(s.bitmap) }, { _ -> if (cont.isActive) cont.resume(null) })
                }
            }
        } ?: return null
        val bmp = if (shot.isMutable) shot else shot.copy(Bitmap.Config.ARGB_8888, true)
        // however MapLibre sized it, px per dp comes from what came back
        val ratio = bmp.width.toFloat() / w
        shade(bmp, top * ratio)
        return bmp to Frame(cx, cy, world, w, h, ratio)
    }

    /** Every train on its own line, as in the app: a dark edge, a white ring, its line's colour, a dot if it's late. */
    private fun withTrains(base: Bitmap, f: Frame, trains: List<Train>, geo: nz.aryan.akllive.gtfs.RailGeo): Bitmap {
        val bmp = base.copy(Bitmap.Config.ARGB_8888, true)
        if (trains.isEmpty()) return bmp
        val d = Rail.layout(TrainNet.stations, geo, SHOWN)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val k = f.ratio
        for (t in trains.filter { it.line in SHOWN }) {
            val v = t.vehicle
            val at = d.snap(t.line, v.lon, v.lat) ?: Pt(v.lon, v.lat)
            val x = f.x(at.x)
            val y = f.y(at.y)
            p.color = 0xFF050A14.toInt(); c.drawCircle(x, y, 7.2f * k, p)
            p.color = android.graphics.Color.WHITE; c.drawCircle(x, y, 5.8f * k, p)
            p.color = Pal.line(t.line).toArgb(); c.drawCircle(x, y, 4.1f * k, p)
            val late = t.delay ?: 0
            if (late >= 120) {
                p.color = android.graphics.Color.WHITE; c.drawCircle(x + 5.2f * k, y - 5.2f * k, 3.3f * k, p)
                p.color = (if (late >= 300) Pal.Late else Pal.Warn).toArgb(); c.drawCircle(x + 5.2f * k, y - 5.2f * k, 2.4f * k, p)
            }
        }
        return bmp
    }

    /** A soft shade over the top, as in the app, so the header reads over the map. */
    private fun shade(bmp: Bitmap, px: Float) {
        val h = (px * 1.6f).coerceAtMost(bmp.height.toFloat())
        val paint = Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, h, Color.Black.copy(alpha = 0.6f).toArgb(), 0, Shader.TileMode.CLAMP)
        }
        Canvas(bmp).drawRect(0f, 0f, bmp.width.toFloat(), h, paint)
    }

    // Web Mercator, 0..1 across the world
    private fun mx(lon: Double) = (lon + 180.0) / 360.0
    private fun my(lat: Double): Double {
        val r = Math.toRadians(lat)
        return (1 - ln(tan(r) + 1 / kotlin.math.cos(r)) / PI) / 2
    }
    private fun lon(x: Double) = x * 360.0 - 180.0
    private fun lat(y: Double) = Math.toDegrees(atan(kotlin.math.sinh(PI * (1 - 2 * y))))
}
