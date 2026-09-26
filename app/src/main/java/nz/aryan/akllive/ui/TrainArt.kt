package nz.aryan.akllive.ui

import android.content.Context
import android.graphics.Bitmap
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
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.Style
import org.maplibre.android.snapshotter.MapSnapshotter
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import kotlin.coroutines.resume

/**
 * The train map for the home-screen widget: the app's Diagram view (the quiet
 * map, the lines along their real tracks side by side, the stations and their
 * names, every train on its own line), drawn off screen at the widget's size.
 */
object TrainArt {
    private val lock = Mutex()

    /** [w] x [h] in dp; [top] dp kept clear for the widget's header. Null when the map couldn't be drawn. */
    suspend fun render(ctx: Context, trains: List<Train>, w: Int, h: Int, top: Int): Bitmap? = lock.withLock {
        val app = ctx.applicationContext
        val shown = setOf(0, 1, 2)
        val data = withContext(Dispatchers.Default) {
            val geo = TrainNet.geo(app)
            val d = Rail.layout(TrainNet.stations, geo, shown)
            val (stops, links) = TrainNet.stopsAndLinks(d, geo, shown, null, dark = true)
            val trainFc = FeatureCollection.fromFeatures(trains.filter { it.line in shown }.map { t ->
                val v = t.vehicle
                val at = d.snap(t.line, v.lon, v.lat) ?: Pt(v.lon, v.lat)
                Feature.fromGeometry(Point.fromLngLat(at.x, at.y)).also { f ->
                    f.addStringProperty("c", hex(Pal.line(t.line)))
                    val late = t.delay ?: 0
                    if (late >= 120) f.addStringProperty("a", hex(if (late >= 300) Pal.Late else Pal.Warn))
                }
            })
            Triple(aklData(TrainNet.lines(d, dark = true), links, stops), trainFc, TrainNet.fit(shown))
        }
        val scale = app.resources.displayMetrics.density.coerceIn(1f, 2.5f)
        val shot = withContext(Dispatchers.Main) {
            withTimeoutOrNull(20_000) { snapshot(app, data.first, data.second, data.third, w, h, top, scale) }
        } ?: return@withLock null
        shade(shot, top * scale)
    }

    private suspend fun snapshot(ctx: Context, akl: Triple<FeatureCollection, FeatureCollection, FeatureCollection>,
                                 trains: FeatureCollection, fit: List<Pair<Double, Double>>, w: Int, h: Int, top: Int,
                                 scale: Float): Bitmap? {
        MapLibre.getInstance(ctx)
        val style = Style.Builder().fromJson(MapStyles.diagramJson(dark = true))
            .withSources(GeoJsonSource("akl-lines", akl.first), GeoJsonSource("akl-links", akl.second),
                         GeoJsonSource("akl-stops", akl.third), GeoJsonSource("trains", trains))
        // on top of the map, in order: the lines, stations and names, then the trains
        var below = "dg-places"
        for (layer in aklLayers(Basemap.Diagram, dark = true) + trainLayers()) {
            style.withLayerAbove(layer, below)
            below = layer.id
        }
        val bounds = LatLngBounds.Builder().includes(fit.map { LatLng(it.first, it.second) }).build()
        val options = MapSnapshotter.Options(w, h)
            .withStyleBuilder(style)
            .withRegion(bounds)
            .withPadding(14, top + 4, 14, 10)
            .withPixelRatio(scale)
            .withLogo(false)
            .withAttribution(false)
        return suspendCancellableCoroutine { cont ->
            val snapper = MapSnapshotter(ctx, options)
            cont.invokeOnCancellation { try { snapper.cancel() } catch (_: Exception) { } }
            snapper.start({ shot -> if (cont.isActive) cont.resume(shot.bitmap) },
                          { _ -> if (cont.isActive) cont.resume(null) })
        }
    }

    /** A train as in the app: a dark edge, a white ring, its line's colour, and a dot if it's running late. */
    private fun trainLayers(): List<Layer> {
        val color = Expression.toColor(Expression.get("c"))
        return listOf(
            CircleLayer("trains-edge", "trains").withProperties(
                PropertyFactory.circleColor(0xFF050A14.toInt()), PropertyFactory.circleRadius(7.2f)),
            CircleLayer("trains", "trains").withProperties(
                PropertyFactory.circleColor(color), PropertyFactory.circleRadius(4.2f),
                PropertyFactory.circleStrokeColor(Color.White.toArgb()), PropertyFactory.circleStrokeWidth(1.7f)),
            CircleLayer("trains-late", "trains").withProperties(
                PropertyFactory.circleColor(Expression.toColor(Expression.get("a"))), PropertyFactory.circleRadius(2.4f),
                PropertyFactory.circleStrokeColor(Color.White.toArgb()), PropertyFactory.circleStrokeWidth(1f),
                PropertyFactory.circleTranslate(arrayOf(5.2f, -5.2f)))
                .withFilter(Expression.has("a")),
        )
    }

    /** A soft shade over the top, as in the app, so the header reads over the map. */
    private fun shade(src: Bitmap, px: Float): Bitmap {
        val bmp = if (src.isMutable) src else src.copy(Bitmap.Config.ARGB_8888, true)
        val h = (px * 1.6f).coerceAtMost(bmp.height.toFloat())
        val paint = Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, h, Color.Black.copy(alpha = 0.6f).toArgb(), 0, Shader.TileMode.CLAMP)
        }
        Canvas(bmp).drawRect(0f, 0f, bmp.width.toFloat(), h, paint)
        return bmp
    }

    private fun hex(c: Color) = "#%06X".format(0xFFFFFF and c.toArgb())
}
