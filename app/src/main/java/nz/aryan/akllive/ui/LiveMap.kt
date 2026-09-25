package nz.aryan.akllive.ui

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.Gravity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.MultiLineString
import org.maplibre.geojson.Point
import java.net.URLEncoder
import kotlin.math.hypot
import kotlin.math.sin

/** What's under the live overlays: aerial photos, or a street map. */
enum class Basemap { Satellite, Streets }

/** A route or rail line; each part is a run of (lat, lon) points. */
data class MapLine(
    val parts: List<List<Pair<Double, Double>>>,
    val color: Color,
    val width: Float = 4f,
    val offset: Float = 0f,
)

/** A stop or station dot. Tappable when it has an [id]; [label] draws its name beside it. */
data class MapStop(
    val lat: Double,
    val lon: Double,
    val color: Color,
    val big: Boolean = false,
    val label: String? = null,
    val id: String? = null,
)

/** A vehicle drawn live over the map, gliding between GPS fixes. */
data class MapMarker(
    val id: String,
    val lat: Double,
    val lon: Double,
    val bearing: Float?,
    val color: Color,
    val train: Boolean,
    val tag: String? = null,
    val alert: Color? = null,
)

/**
 * One of many vehicles (every bus in Auckland), drawn by the map itself so a
 * thousand of them stay smooth. [label] (the route) shows once zoomed in.
 */
data class CrowdDot(
    val id: String,
    val lat: Double,
    val lon: Double,
    val bearing: Float?,
    val color: Color,
    val label: String? = null,
)

/** Fly the camera here; a new [key] flies again even to the same spot. */
data class MapFocus(val lat: Double, val lon: Double, val zoom: Double = 14.5, val key: Long = System.nanoTime())

/** Map styles: OpenFreeMap for streets; LINZ aerials (to 7.5 cm in Auckland) with a key, Esri's without. */
object MapStyles {
    private const val ESRI =
        "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"

    fun builder(basemap: Basemap, dark: Boolean, linzKey: String): Style.Builder = when (basemap) {
        Basemap.Streets -> Style.Builder().fromUri("https://tiles.openfreemap.org/styles/" + if (dark) "dark" else "liberty")
        Basemap.Satellite -> Style.Builder().fromJson(satellite(linzKey.trim(), dark))
    }

    fun credit(basemap: Basemap, linzKey: String): String = when {
        basemap == Basemap.Streets -> "© OpenStreetMap contributors · OpenFreeMap"
        linzKey.isNotBlank() -> "Imagery © Toitū Te Whenua LINZ, CC BY 4.0"
        else -> "Imagery: Powered by Esri"
    }

    private fun satellite(linzKey: String, dark: Boolean): String {
        val linz = linzKey.isNotEmpty()
        val url = if (linz) "https://basemaps.linz.govt.nz/v1/tiles/aerial/WebMercatorQuad/{z}/{x}/{y}.webp?api=" +
                            URLEncoder.encode(linzKey, "UTF-8")
                  else ESRI
        val source = JSONObject()
            .put("type", "raster")
            .put("tiles", JSONArray().put(url))
            .put("tileSize", 256)
            .put("maxzoom", if (linz) 21 else 19)
            .put("attribution", if (linz) "© Toitū Te Whenua LINZ, CC BY 4.0"
                                else "Powered by Esri · Esri, Maxar, Earthstar Geographics")
        val layers = JSONArray()
            .put(JSONObject().put("id", "bg").put("type", "background")
                     .put("paint", JSONObject().put("background-color", "#0B1628")))
            .put(JSONObject().put("id", "imagery").put("type", "raster").put("source", "imagery")
                     .put("paint", JSONObject().put("raster-brightness-max", if (dark) 0.82 else 1.0)
                                               .put("raster-saturation", -0.12)))
        // fonts for route labels, from the same place the street style gets them
        return JSONObject().put("version", 8).put("sources", JSONObject().put("imagery", source))
            .put("glyphs", "https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf")
            .put("layers", layers).toString()
    }
}

private const val GLIDE_MS = 10_000f
private const val CROWD_GLIDE_MS = 4_000f

/** Where a marker is drawn: it glides from its last spot to each new fix. */
private class Glide(var fromLat: Double, var fromLon: Double, var toLat: Double, var toLon: Double, var start: Long,
                    val ms: Float = GLIDE_MS) {
    fun at(now: Long): Pair<Double, Double> {
        val t = ((now - start) / ms).coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }
        return (fromLat + (toLat - fromLat) * t) to (fromLon + (toLon - fromLon) * t)
    }
}

/**
 * A real map (MapLibre) with lines and stops as map layers, and vehicles drawn
 * live on top. [fit] is what the camera frames when the map first appears.
 */
@Composable
fun LiveMap(
    basemap: Basemap,
    linzKey: String,
    lines: List<MapLine>,
    stops: List<MapStop>,
    markers: List<MapMarker>,
    fit: List<Pair<Double, Double>>,
    frameT: State<Float>,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
    selected: String? = null,
    topInset: Float = 0f,
    bottomInset: Float = 0f,
    crowd: List<CrowdDot> = emptyList(),
    focus: MapFocus? = null,
    onCrowd: (String) -> Unit = {},
    onMarker: (String) -> Unit = {},
    onStop: (String) -> Unit = {},
    onBackground: () -> Unit = {},
    onLongPress: ((Double, Double) -> Unit)? = null,
) {
    val context = LocalContext.current
    val dark = LocalDark.current
    val density = LocalDensity.current.density
    val mapView = remember {
        MapLibre.getInstance(context.applicationContext)
        val lat = fit.map { it.first }.average().takeIf { !it.isNaN() } ?: -36.87
        val lon = fit.map { it.second }.average().takeIf { !it.isNaN() } ?: 174.76
        MapView(context, MapLibreMapOptions.createFromAttributes(context)
            .textureMode(true)
            .logoEnabled(false)
            .compassEnabled(false)
            .attributionEnabled(true)
            .camera(CameraPosition.Builder().target(LatLng(lat, lon)).zoom(11.5).build()))
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    val styleGen = remember { intArrayOf(0) }
    val camTick = remember { mutableIntStateOf(0) }
    val glides = remember { HashMap<String, Glide>() }
    val marks by rememberUpdatedState(markers)
    val dots by rememberUpdatedState(stops)
    val tapMarker by rememberUpdatedState(onMarker)
    val tapStop by rememberUpdatedState(onStop)
    val tapNothing by rememberUpdatedState(onBackground)
    val tapCrowd by rememberUpdatedState(onCrowd)
    val longPress by rememberUpdatedState(onLongPress)
    val crowdGlides = remember { HashMap<String, Glide>() }

    // MapView follows the screen's lifecycle, and is torn down with this composable
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, mapView) {
        mapView.onCreate(null)
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose {
            lifecycle.removeObserver(obs)
            val st = lifecycle.currentState
            if (st.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            if (st.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
            mapView.onDestroy()
        }
    }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { m ->
            m.setMinZoomPreference(8.0)
            m.setMaxZoomPreference(19.5)
            m.uiSettings.setAttributionGravity(Gravity.BOTTOM or Gravity.END)
            m.addOnCameraMoveListener { camTick.intValue++ }
            m.addOnCameraIdleListener { camTick.intValue++ }
            m.addOnMapClickListener { ll ->
                val proj = m.projection
                val at = proj.toScreenLocation(ll)
                val now = SystemClock.elapsedRealtime()
                fun dist(lat: Double, lon: Double): Float {
                    val p = proj.toScreenLocation(LatLng(lat, lon))
                    return hypot(p.x - at.x, p.y - at.y)
                }
                val mk = marks.minByOrNull { mk ->
                    val (la, lo) = glides[mk.id]?.at(now) ?: (mk.lat to mk.lon)
                    dist(la, lo)
                }
                val mkD = mk?.let { val (la, lo) = glides[it.id]?.at(now) ?: (it.lat to it.lon); dist(la, lo) }
                val st = dots.filter { it.id != null }.minByOrNull { dist(it.lat, it.lon) }
                val stD = st?.let { dist(it.lat, it.lon) }
                val hitMarker = mk != null && mkD!! < 26 * density
                // the crowd is drawn by the map, so ask the map what's under the finger
                val crowdHit = if (hitMarker) null else {
                    val r = 16 * density
                    val hits = if (m.style?.getLayer("crowd-dots") == null) emptyList()
                               else m.queryRenderedFeatures(RectF(at.x - r, at.y - r, at.x + r, at.y + r), "crowd-dots")
                    hits.filter { it.hasProperty("id") }.minByOrNull { f ->
                        (f.geometry() as? Point)?.let { dist(it.latitude(), it.longitude()) } ?: Float.MAX_VALUE
                    }?.getStringProperty("id")
                }
                when {
                    hitMarker -> tapMarker(mk!!.id)
                    crowdHit != null -> tapCrowd(crowdHit)
                    st != null && stD!! < 20 * density -> tapStop(st.id!!)
                    else -> tapNothing()
                }
                true
            }
            // long-press anywhere: drop a pin there (directions from or to it)
            m.addOnMapLongClickListener { ll ->
                val f = longPress ?: return@addOnMapLongClickListener false
                f(ll.latitude, ll.longitude)
                true
            }
            map = m
        }
    }

    LaunchedEffect(map, interactive) {
        map?.uiSettings?.apply {
            setAllGesturesEnabled(interactive)
            // north stays up, so vehicle headings read straight off the map
            setRotateGesturesEnabled(false)
            setTiltGesturesEnabled(false)
            setAttributionEnabled(interactive)
        }
    }

    LaunchedEffect(map, basemap, dark, linzKey) {
        val m = map ?: return@LaunchedEffect
        val gen = ++styleGen[0]
        style = null
        m.setStyle(MapStyles.builder(basemap, dark, linzKey)) { s -> if (gen == styleGen[0]) style = s }
    }

    LaunchedEffect(style, lines, stops) {
        val s = style ?: return@LaunchedEffect
        if (s.isFullyLoaded) drawLayers(s, lines, stops)
    }

    // the crowd: retarget each vehicle's glide, then feed the map positions for a few seconds
    LaunchedEffect(style, crowd) {
        val s = style ?: return@LaunchedEffect
        if (!s.isFullyLoaded) return@LaunchedEffect
        if (crowd.isEmpty() && s.getSource("crowd") == null) return@LaunchedEffect   // maps without a crowd
        val src = crowdSource(s, density)
        val start = SystemClock.elapsedRealtime()
        val seen = HashSet<String>(crowd.size * 2)
        for (d in crowd) {
            seen += d.id
            val g = crowdGlides[d.id]
            if (g == null) {
                crowdGlides[d.id] = Glide(d.lat, d.lon, d.lat, d.lon, start, CROWD_GLIDE_MS)
            } else if (g.toLat != d.lat || g.toLon != d.lon) {
                val (cl, co) = g.at(start)
                val far = hypot(cl - d.lat, (co - d.lon) * 0.8) > 0.02
                g.fromLat = if (far) d.lat else cl; g.fromLon = if (far) d.lon else co
                g.toLat = d.lat; g.toLon = d.lon; g.start = start
            }
        }
        crowdGlides.keys.retainAll(seen)
        val hex = HashMap<Color, String>()
        while (true) {
            val now = SystemClock.elapsedRealtime()
            src.setGeoJson(FeatureCollection.fromFeatures(crowd.map { d ->
                val (la, lo) = crowdGlides[d.id]?.at(now) ?: (d.lat to d.lon)
                Feature.fromGeometry(Point.fromLngLat(lo, la)).also { f ->
                    f.addStringProperty("id", d.id)
                    f.addStringProperty("c", hex.getOrPut(d.color) { "#%06X".format(0xFFFFFF and d.color.toArgb()) })
                    d.bearing?.let { f.addNumberProperty("b", it) }
                    d.label?.let { f.addStringProperty("r", it) }
                }
            }))
            if (now - start >= CROWD_GLIDE_MS) break
            delay(90)
        }
    }

    LaunchedEffect(map, focus) {
        val m = map ?: return@LaunchedEffect
        val f = focus ?: return@LaunchedEffect
        m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(f.lat, f.lon), maxOf(m.cameraPosition.zoom, f.zoom)), 700)
    }

    LaunchedEffect(map, fit) {
        val m = map ?: return@LaunchedEffect
        if (fit.isEmpty()) return@LaunchedEffect
        while (mapView.width == 0 || mapView.height == 0) delay(50)
        if (fit.size == 1) {
            m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(fit[0].first, fit[0].second), 14.0))
        } else {
            val b = LatLngBounds.Builder()
            fit.forEach { b.include(LatLng(it.first, it.second)) }
            val pad = (36 * density).toInt()
            m.moveCamera(CameraUpdateFactory.newLatLngBounds(b.build(), pad, pad + (topInset * density).toInt(), pad,
                                                               pad + (bottomInset * density).toInt()))
        }
    }

    val measurer = rememberTextMeasurer(cacheSize = 64)
    val credit = MapStyles.credit(basemap, linzKey)
    Box(modifier) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        Canvas(Modifier.fillMaxSize()) {
            camTick.intValue                                   // redraw as the camera moves
            val t = frameT.value                               // ... and every frame, for the glides
            val m = map ?: return@Canvas
            val proj = m.projection
            val now = SystemClock.elapsedRealtime()
            val dp = density
            fun screen(lat: Double, lon: Double): Offset {
                val p = proj.toScreenLocation(LatLng(lat, lon))
                return Offset(p.x, p.y)
            }
            // stop names, first come first served, never overlapping
            val placed = ArrayList<Rect>()
            for (s in stops) {
                val label = s.label ?: continue
                val box = drawTag(measurer, label, screen(s.lat, s.lon) + Offset(if (s.big) 12 * dp else 6 * dp, 0f),
                                  Color(0xE6FFFFFF), Pal.Navy, dp, anchorLeft = true, avoid = placed)
                if (box != null) placed += box
            }
            val seen = HashSet<String>()
            for (mk in markers.sortedBy { if (it.id == selected) 1 else 0 }) {
                seen += mk.id
                val g = glides[mk.id]
                val (la, lo) = when {
                    g == null -> { glides[mk.id] = Glide(mk.lat, mk.lon, mk.lat, mk.lon, now); mk.lat to mk.lon }
                    g.toLat != mk.lat || g.toLon != mk.lon -> {
                        val (cl, co) = g.at(now)
                        // a big jump (new trip, bad fix) snaps rather than sliding across town
                        val far = hypot(cl - mk.lat, (co - mk.lon) * 0.8) > 0.02
                        g.fromLat = if (far) mk.lat else cl; g.fromLon = if (far) mk.lon else co
                        g.toLat = mk.lat; g.toLon = mk.lon; g.start = now
                        g.at(now)
                    }
                    else -> g.at(now)
                }
                val p = screen(la, lo)
                if (p.x < -60 * dp || p.y < -60 * dp || p.x > size.width + 60 * dp || p.y > size.height + 60 * dp) continue
                val sel = mk.id == selected
                if (sel) {
                    val pulse = 0.5f + 0.5f * sin(t * 4f)
                    drawCircle(mk.color.copy(alpha = 0.2f + 0.2f * pulse), (20 + 8 * pulse) * dp, p)
                }
                if (mk.train) drawTrainMarker(p, mk, sel, dp) else drawBusMarker(p, mk, dp)
                mk.tag?.let { drawTag(measurer, it, p + Offset(0f, -(if (mk.train) 14 else 16) * dp), mk.color, Color.White, dp) }
            }
            glides.keys.retainAll(seen)
            drawText(measurer, credit, topLeft = Offset(8 * dp, size.height - 16 * dp),
                     style = TextStyle(fontSize = 9.sp, color = Color.White.copy(alpha = 0.85f),
                                       shadow = androidx.compose.ui.graphics.Shadow(Color.Black, Offset(0f, 1f), 3f)))
        }
    }
}

/** Route lines (with a dark casing so they read over photos) and stop dots, as map layers. */
private fun drawLayers(s: Style, lines: List<MapLine>, stops: List<MapStop>) {
    fun put(layer: Layer) = if (s.getLayer("crowd-dots") != null) s.addLayerBelow(layer, "crowd-dots") else s.addLayer(layer)
    s.layers.filter { it.id.startsWith("akl-") }.forEach { s.removeLayer(it) }
    s.sources.filter { it.id.startsWith("akl-") }.forEach { s.removeSource(it) }
    lines.forEachIndexed { i, line ->
        val geom = MultiLineString.fromLngLats(line.parts.map { part -> part.map { Point.fromLngLat(it.second, it.first) } })
        s.addSource(GeoJsonSource("akl-line-$i", Feature.fromGeometry(geom)))
        put(LineLayer("akl-line-$i-case", "akl-line-$i").withProperties(
            PropertyFactory.lineColor(Color(0xFF0B1628).toArgb()),
            PropertyFactory.lineOpacity(0.55f),
            PropertyFactory.lineWidth(line.width + 3f),
            PropertyFactory.lineOffset(line.offset),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)))
        put(LineLayer("akl-line-$i", "akl-line-$i").withProperties(
            PropertyFactory.lineColor(line.color.toArgb()),
            PropertyFactory.lineWidth(line.width),
            PropertyFactory.lineOffset(line.offset),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)))
    }
    stops.groupBy { it.color to it.big }.entries.forEachIndexed { k, (key, group) ->
        val (color, big) = key
        val fc = FeatureCollection.fromFeatures(group.map { Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)) })
        s.addSource(GeoJsonSource("akl-stops-$k", fc))
        put(CircleLayer("akl-stops-$k", "akl-stops-$k").withProperties(
            PropertyFactory.circleRadius(if (big) 7f else 3.4f),
            PropertyFactory.circleColor(Color.White.toArgb()),
            PropertyFactory.circleStrokeColor(color.toArgb()),
            PropertyFactory.circleStrokeWidth(if (big) 4f else 2f)))
    }
}

/** The crowd's source and layers: a dot per vehicle, a heading arrow, and the route once zoomed in. */
private fun crowdSource(s: Style, dp: Float): GeoJsonSource {
    s.getSourceAs<GeoJsonSource>("crowd")?.let { return it }
    val src = GeoJsonSource("crowd")
    s.addSource(src)
    s.addImage("crowd-arrow", arrowBitmap(dp))
    val zoom = Expression.zoom()
    s.addLayer(CircleLayer("crowd-dots", "crowd").withProperties(
        PropertyFactory.circleColor(Expression.toColor(Expression.get("c"))),
        PropertyFactory.circleRadius(Expression.interpolate(Expression.linear(), zoom,
            Expression.stop(9, 2.4f), Expression.stop(11, 3.4f), Expression.stop(13, 5f), Expression.stop(15, 7.5f))),
        PropertyFactory.circleStrokeColor(Color.White.toArgb()),
        PropertyFactory.circleStrokeWidth(Expression.interpolate(Expression.linear(), zoom,
            Expression.stop(9, 0.5f), Expression.stop(13, 1.5f)))))
    s.addLayer(SymbolLayer("crowd-arrows", "crowd").withProperties(
        PropertyFactory.iconImage("crowd-arrow"),
        PropertyFactory.iconRotate(Expression.get("b")),
        PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
        PropertyFactory.iconAllowOverlap(true),
        PropertyFactory.iconIgnorePlacement(true),
        PropertyFactory.iconSize(Expression.interpolate(Expression.linear(), zoom,
            Expression.stop(12, 0.6f), Expression.stop(15, 1f))),
    ).withFilter(Expression.has("b")).also { it.minZoom = 12f })
    s.addLayer(SymbolLayer("crowd-labels", "crowd").withProperties(
        PropertyFactory.textField(Expression.get("r")),
        PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
        PropertyFactory.textSize(11f),
        PropertyFactory.textColor(Color.White.toArgb()),
        PropertyFactory.textHaloColor(Color(0xFF0B1628).toArgb()),
        PropertyFactory.textHaloWidth(1.6f),
        PropertyFactory.textAnchor(Property.TEXT_ANCHOR_BOTTOM),
        PropertyFactory.textOffset(arrayOf(0f, -0.75f)),
    ).withFilter(Expression.has("r")).also { it.minZoom = 13.5f })
    return src
}

/** A white arrowhead just outside a crowd dot, pointing north; the layer turns it to the heading. */
private fun arrowBitmap(dp: Float): Bitmap {
    val size = (40 * dp).toInt()
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    val mid = size / 2f
    val path = android.graphics.Path().apply {
        moveTo(mid, mid - 16 * dp)
        lineTo(mid + 5 * dp, mid - 9.5f * dp)
        lineTo(mid - 5 * dp, mid - 9.5f * dp)
        close()
    }
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.style = Paint.Style.FILL
    paint.color = android.graphics.Color.WHITE
    c.drawPath(path, paint)
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 1.2f * dp
    paint.strokeJoin = Paint.Join.ROUND
    paint.color = 0xFF0B1628.toInt()
    c.drawPath(path, paint)
    return bmp
}

/**
 * A text pill: centred above [at], or starting at [at] when [anchorLeft]. Skipped (returns null)
 * if it would overlap anything in [avoid] or sit off screen; otherwise returns where it went.
 */
private fun DrawScope.drawTag(measurer: TextMeasurer, text: String, at: Offset, bg: Color, fg: Color, dp: Float,
                              anchorLeft: Boolean = false, avoid: List<Rect>? = null): Rect? {
    val lay = measurer.measure(text, TextStyle(fontSize = 11.sp, color = fg, fontWeight = FontWeight.Bold))
    val w = lay.size.width.toFloat()
    val h = lay.size.height.toFloat()
    val tl = if (anchorLeft) Offset(at.x + 5 * dp, at.y - h / 2) else Offset(at.x - w / 2, at.y - h - 2 * dp)
    val box = Rect(tl.x - 5 * dp, tl.y - 2 * dp, tl.x + w + 5 * dp, tl.y + h + 2 * dp)
    if (avoid != null) {
        if (box.right < 0 || box.left > size.width || box.bottom < 0 || box.top > size.height) return null
        if (avoid.any { it.overlaps(box) }) return null
    }
    drawRoundRect(Color.Black.copy(alpha = 0.25f), box.topLeft + Offset(0f, 1 * dp), box.size, CornerRadius(8 * dp))
    drawRoundRect(bg, box.topLeft, box.size, CornerRadius(8 * dp))
    drawText(lay, topLeft = tl)
    return box
}

/** A little bus from above, pointing where it's heading. */
private fun DrawScope.drawBusMarker(c: Offset, mk: MapMarker, dp: Float) {
    val bw = 24 * dp
    val bh = 12 * dp
    rotate((mk.bearing ?: 90f) - 90f, c) {
        drawRoundRect(Color.Black.copy(alpha = 0.3f), c - Offset(bw / 2, bh / 2 - 2 * dp), Size(bw + 2 * dp, bh + 2 * dp),
                      CornerRadius(5 * dp))
        drawRoundRect(Color.White, c - Offset(bw / 2 + 2 * dp, bh / 2 + 2 * dp), Size(bw + 4 * dp, bh + 4 * dp),
                      CornerRadius(5 * dp))
        drawRoundRect(mk.color, c - Offset(bw / 2, bh / 2), Size(bw, bh), CornerRadius(4 * dp))
        // roof hatches, and the windscreen at the front
        drawRect(Color.White.copy(alpha = 0.35f), c + Offset(-bw / 2 + 5 * dp, -bh / 2 + 3 * dp), Size(4 * dp, bh - 6 * dp))
        drawRect(Color.White.copy(alpha = 0.35f), c + Offset(-2 * dp, -bh / 2 + 3 * dp), Size(4 * dp, bh - 6 * dp))
        drawRect(Color.White.copy(alpha = 0.9f), c + Offset(bw / 2 - 5 * dp, -bh / 2 + 2 * dp), Size(3 * dp, bh - 4 * dp))
    }
}

/** A train: a ring in its line's colour, an arrow for its heading, a dot if it's late. */
private fun DrawScope.drawTrainMarker(p: Offset, mk: MapMarker, sel: Boolean, dp: Float) {
    val r = (if (sel) 10.5f else 8.5f) * dp
    mk.bearing?.let { deg ->
        val arrow = Path().apply { moveTo(0f, -4.5f * dp); lineTo(6.5f * dp, 0f); lineTo(0f, 4.5f * dp); close() }
        rotate(deg - 90f, p) {
            withTransform({ translate(p.x + r - 1 * dp, p.y) }) {
                drawPath(arrow, mk.color)
                drawPath(arrow, Color(0xFF050A14), style = Stroke(1.2f * dp, join = StrokeJoin.Round))
            }
        }
    }
    drawCircle(Color(0xFF050A14), r, p)
    drawCircle(Color.White, r - 1.6f * dp, p)
    drawCircle(mk.color, r - 3.6f * dp, p)
    mk.alert?.let {
        val q = p + Offset(r * 0.72f, -r * 0.72f)
        drawCircle(Color.White, 3.6f * dp, q)
        drawCircle(it, 2.6f * dp, q)
    }
}
