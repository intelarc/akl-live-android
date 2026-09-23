package nz.aryan.akllive.ui

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import nz.aryan.akllive.AppViewModel
import nz.aryan.akllive.data.MapData
import nz.aryan.akllive.data.Train
import nz.aryan.akllive.data.TrainState
import kotlin.math.sin

/** Map colours: AT's light map, and a night version. */
class MapPalette(
    val water: Color, val land: Color, val cone: Color, val text: Color, val textMinor: Color,
    val halo: Color, val stationFill: Color, val stationRing: Color, val crlGlow: Color, val rim: Color,
)

val LightMap = MapPalette(
    water = Color(0xFFCDE1F3), land = Color(0xFFFAFBFD), cone = Color(0xFFE6EBF2),
    text = Color(0xFF1A2744), textMinor = Color(0xFF46546E), halo = Color(0xDDFAFBFD),
    stationFill = Color.White, stationRing = Color(0xFF1A2744), crlGlow = Color(0x66FFCE66),
    rim = Color(0xFF14203A),
)
val DarkMap = MapPalette(
    water = Color(0xFF0B1628), land = Color(0xFF16233A), cone = Color(0xFF1E2E4A),
    text = Color(0xFFE6ECF7), textMinor = Color(0xFFAAB7CD), halo = Color(0xCC16233A),
    stationFill = Color(0xFFE9EEF6), stationRing = Color(0xFF0B1628), crlGlow = Color(0x2EFFD166),
    rim = Color(0xFF050A14),
)

/** Where the map sits on screen: map point -> screen point. */
class MapView(val size: IntSize, zoom: Float, pan: Offset) {
    private val base = minOf(size.width / 330f, size.height / 228f)
    val scale = base * zoom
    private val cx = size.width / 2f + pan.x
    private val cy = size.height / 2f + pan.y
    fun screen(x: Float, y: Float) = Offset(cx + (x - MID_X) * scale, cy + (y - MID_Y) * scale)
    val originX get() = cx - MID_X * scale
    val originY get() = cy - MID_Y * scale

    companion object {
        const val MID_X = 160f
        const val MID_Y = 130f
    }
}

private fun path(pts: FloatArray, close: Boolean = false) = Path().apply {
    moveTo(pts[0], pts[1])
    var i = 2
    while (i < pts.size) { lineTo(pts[i], pts[i + 1]); i += 2 }
    if (close) close()
}

/** A train's marker position right now, gliding from its previous fix. */
fun trainPos(state: TrainState, t: Train, glide: Float): Offset {
    val b = state.before[t.vehicle.id] ?: return Offset(t.x, t.y)
    return Offset(b.first + (t.x - b.first) * glide, b.second + (t.y - b.second) * glide)
}

@Composable
fun TrainMap(
    state: TrainState,
    filter: Set<Int>,
    selectedTrain: String?,
    selectedStation: Int?,
    zoom: MutableFloatState,
    pan: MutableState<Offset>,
    frameT: androidx.compose.runtime.State<Float>,
    onTrain: (Train) -> Unit,
    onStation: (Int) -> Unit,
    onNothing: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pal = if (isSystemInDarkTheme()) DarkMap else LightMap
    val lines = remember { MapData.LINE_DRAW.map { path(it) } }
    val crl = remember { path(MapData.CRL_LOOP) }
    val land = remember { MapData.LAND.map { path(it, true) } }
    val water = remember { MapData.WATER.map { path(it, true) } }
    val measurer = rememberTextMeasurer()
    val labels = remember(pal) {
        MapData.STATIONS.map { st ->
            measurer.measure(st.name, TextStyle(
                color = if (st.priority == 2) pal.textMinor else pal.text,
                fontSize = if (st.priority == 2) 10.sp else 11.sp,
                fontWeight = if (st.crl) FontWeight.Bold else if (st.priority == 0) FontWeight.SemiBold else FontWeight.Normal))
        }
    }
    val canvasSize = remember { arrayOf(IntSize(1, 1)) }      // plain holder: set while drawing
    val st by rememberUpdatedState(state)
    val flt by rememberUpdatedState(filter)
    val tapTrain by rememberUpdatedState(onTrain)
    val tapStation by rememberUpdatedState(onStation)
    val tapNothing by rememberUpdatedState(onNothing)

    Canvas(
        modifier
            .pointerInput(Unit) {
                detectTransformGestures { centroid, panBy, zoomBy, _ ->
                    val old = zoom.floatValue
                    val new = (old * zoomBy).coerceIn(1f, 9f)
                    val k = new / old
                    val c0 = Offset(size.width / 2f, size.height / 2f)
                    val p = (centroid - c0) - (centroid - c0 - pan.value) * k + panBy
                    val lim = Offset(size.width * new / 2f, size.height * new / 2f)
                    pan.value = Offset(p.x.coerceIn(-lim.x, lim.x), p.y.coerceIn(-lim.y, lim.y))
                    zoom.floatValue = new
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { at ->
                        val old = zoom.floatValue
                        val new = if (old >= 8.5f) 1f else (old * 2f).coerceAtMost(9f)
                        val k = new / old
                        val c0 = Offset(size.width / 2f, size.height / 2f)
                        pan.value = if (new == 1f) Offset.Zero else (at - c0) - (at - c0 - pan.value) * k
                        zoom.floatValue = new
                    },
                    onTap = { at ->
                        val view = MapView(canvasSize[0], zoom.floatValue, pan.value)
                        val g = AppViewModel.glide(st.movedAt, SystemClock.elapsedRealtime())
                        val hitTrain = st.trains.filter { it.line in flt }
                            .minByOrNull { val p = trainPos(st, it, g); (view.screen(p.x, p.y) - at).getDistance() }
                        val tp = hitTrain?.let { val p = trainPos(st, it, g); view.screen(p.x, p.y) }
                        val station = MapData.STATIONS.indices.minByOrNull {
                            (view.screen(MapData.STATIONS[it].x, MapData.STATIONS[it].y) - at).getDistance()
                        }
                        val sp = station?.let { view.screen(MapData.STATIONS[it].x, MapData.STATIONS[it].y) }
                        when {
                            tp != null && (tp - at).getDistance() < 26 * density -> tapTrain(hitTrain!!)
                            sp != null && (sp - at).getDistance() < 24 * density -> tapStation(station!!)
                            else -> tapNothing()
                        }
                    },
                )
            },
    ) {
        canvasSize[0] = IntSize(size.width.toInt(), size.height.toInt())
        val now = frameT.value                                // redraw every frame
        val view = MapView(canvasSize[0], zoom.floatValue, pan.value)
        val dp = density

        drawRect(pal.water)
        withTransform({
            translate(view.originX, view.originY)
            scale(view.scale, view.scale, Offset.Zero)
        }) {
            land.forEach { drawPath(it, pal.land) }
            val r = MapData.LAND_ROUND
            drawRoundRect(pal.land, Offset(r[0], r[1]), Size(r[2] - r[0], r[3] - r[1]), CornerRadius(r[4]))
            water.forEach { drawPath(it, pal.water) }
            val c = MapData.CONES
            for (i in c.indices step 3) drawCircle(pal.cone, c[i + 2], Offset(c[i], c[i + 1]))
            // the City Rail Link tunnels glow
            drawPath(crl, pal.crlGlow, style = Stroke(11f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            // lines: E-W, O-W, then S-C on top (as on AT's map); filtered-out lines fade
            for (li in intArrayOf(0, 2, 1)) {
                val col = Pal.line(li).copy(alpha = if (li in filter) 1f else 0.18f)
                drawPath(lines[li], col, style = Stroke(3.6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            // stations: white pills across interchanges, rings elsewhere
            for (s in MapData.STATIONS) {
                if (s.interchange) {
                    var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE
                    var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
                    for (i in s.pts.indices step 2) {
                        x0 = minOf(x0, s.pts[i]); x1 = maxOf(x1, s.pts[i])
                        y0 = minOf(y0, s.pts[i + 1]); y1 = maxOf(y1, s.pts[i + 1])
                    }
                    val rr = 2.9f
                    drawRoundRect(pal.stationFill, Offset(x0 - rr, y0 - rr), Size(x1 - x0 + 2 * rr, y1 - y0 + 2 * rr),
                                  CornerRadius(rr))
                    drawRoundRect(pal.stationRing, Offset(x0 - rr, y0 - rr), Size(x1 - x0 + 2 * rr, y1 - y0 + 2 * rr),
                                  CornerRadius(rr), style = Stroke(0.9f))
                } else {
                    drawCircle(pal.stationFill, 1.9f, Offset(s.x, s.y))
                    drawCircle(pal.stationRing, 1.9f, Offset(s.x, s.y), style = Stroke(0.7f))
                }
            }
        }

        // selected station: a pulsing ring
        selectedStation?.let {
            val s = MapData.STATIONS[it]
            val p = view.screen(s.x, s.y)
            val pulse = 0.5f + 0.5f * sin(now * 4f)
            drawCircle(Pal.AtBlue.copy(alpha = 0.25f + 0.25f * pulse), (14 + 6 * pulse) * dp, p)
            drawCircle(Pal.AtBlue, 11 * dp, p, style = Stroke(2.5f * dp))
        }

        drawLabels(view, labels, pal, zoom.floatValue, dp)

        // trains
        val g = AppViewModel.glide(state.movedAt, SystemClock.elapsedRealtime())
        val arrow = Path().apply {
            moveTo(0f, -4.5f * dp); lineTo(6.5f * dp, 0f); lineTo(0f, 4.5f * dp); close()
        }
        for (t in state.trains.sortedBy { if (it.vehicle.id == selectedTrain) 2 else if (it.line == 1) 1 else 0 }) {
            if (t.line !in filter) continue
            val m = trainPos(state, t, g)
            val p = view.screen(m.x, m.y)
            val col = Pal.line(t.line)
            val sel = t.vehicle.id == selectedTrain
            val r = (if (sel) 10.5f else 8f) * dp
            if (sel) {
                val pulse = 0.5f + 0.5f * sin(now * 4f)
                drawCircle(col.copy(alpha = 0.18f + 0.2f * pulse), r + (8 + 6 * pulse) * dp, p)
            }
            state.heading[t.vehicle.id]?.let { deg ->
                rotate(deg, p) {
                    withTransform({ translate(p.x + r - 1 * dp, p.y) }) {
                        drawPath(arrow, col)
                        drawPath(arrow, pal.rim, style = Stroke(1.2f * dp, join = StrokeJoin.Round))
                    }
                }
            }
            drawCircle(pal.rim, r, p)
            drawCircle(Color.White, r - 1.6f * dp, p)
            drawCircle(col, r - 3.6f * dp, p)
            // running late? a warning dot on the marker
            val d = t.delay ?: 0
            if (d >= 120) {
                val dc = if (d >= 300) Pal.Late else Pal.Warn
                val q = p + Offset(r * 0.72f, -r * 0.72f)
                drawCircle(Color.White, 3.6f * dp, q)
                drawCircle(dc, 2.6f * dp, q)
            }
        }
    }
}

/** Station names in screen space: constant size, most important first, never overlapping. */
private fun DrawScope.drawLabels(view: MapView, labels: List<androidx.compose.ui.text.TextLayoutResult>,
                                 pal: MapPalette, zoom: Float, dp: Float) {
    val placed = ArrayList<Rect>()
    val order = MapData.STATIONS.indices.sortedWith(compareBy({ MapData.STATIONS[it].priority },
                                                              { if (MapData.STATIONS[it].crl) 0 else 1 }))
    for (i in order) {
        val s = MapData.STATIONS[i]
        if (s.priority == 2 && zoom < 1.6f) continue
        if (s.priority == 1 && zoom < 1.15f) continue
        val lay = labels[i]
        val w = lay.size.width.toFloat()
        val h = lay.size.height.toFloat()
        val p = view.screen(s.x, s.y)
        val dx = s.dx * dp
        val dy = s.dy * dp
        val tl = when (s.anchor) {
            'l' -> Offset(p.x + dx, p.y + dy - h / 2)
            'r' -> Offset(p.x + dx - w, p.y + dy - h / 2)
            't' -> Offset(p.x + dx - w / 2, p.y + dy)
            else -> Offset(p.x + dx - w / 2, p.y + dy - h)
        }
        val box = Rect(tl.x - 3 * dp, tl.y - 1 * dp, tl.x + w + 3 * dp, tl.y + h + 1 * dp)
        if (box.right < 0 || box.left > size.width || box.bottom < 0 || box.top > size.height) continue
        if (placed.any { it.overlaps(box) }) continue
        placed += box
        drawRoundRect(pal.halo, box.topLeft, box.size, CornerRadius(4 * dp))
        drawText(lay, topLeft = tl)
    }
}
