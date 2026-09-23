package nz.aryan.akllive.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nz.aryan.akllive.data.RouteData
import nz.aryan.akllive.data.StopBoard
import kotlin.math.cos
import kotlin.math.hypot

private val DirColors = listOf(Color(0xFF235EA8), Color(0xFF00A3A6))

/** The whole 27H drawn from its stops' real positions, with every bus on the way. */
@Composable
fun RouteMapCard(boards: List<StopBoard>, now: Long) {
    val routes = boards.mapNotNull { b -> RouteData.ROUTES[b.code]?.let { b to it } }
    if (routes.isEmpty()) return
    val measurer = rememberTextMeasurer()
    val onSurface = MaterialTheme.colorScheme.onSurface
    val land = MaterialTheme.colorScheme.surfaceVariant
    val labelStyle = TextStyle(fontSize = 11.sp, color = onSurface, fontWeight = FontWeight.SemiBold)
    val tagStyle = TextStyle(fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)

    // projection: fit every stop of both directions in the card
    val all = routes.flatMap { it.second.second }
    val kx = cos(Math.toRadians(-36.9))
    val minX = all.minOf { it.lon * kx }
    val maxX = all.maxOf { it.lon * kx }
    val minY = all.minOf { -it.lat }
    val maxY = all.maxOf { -it.lat }
    val ends = remember(routes.size) {
        val r = routes.first().second.second
        listOf(r.first(), r.last())
    }
    val buses = routes.flatMapIndexed { i, (b, _) ->
        b.departures.filter { it.vehicle != null && !it.cancelled }.map { Triple(i, it, it.vehicle!!) }
    }

    Card(shape = RoundedCornerShape(22.dp), elevation = CardDefaults.cardElevation(3.dp),
         colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp)) {
            Text("27H route, live", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(if (buses.isEmpty()) "No buses heading your way are on the road yet"
                 else "${buses.size} bus${if (buses.size == 1) "" else "es"} on the way to you",
                 style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            Canvas(Modifier.fillMaxWidth().height(340.dp)
                       .background(land, RoundedCornerShape(16.dp))) {
                val pad = 26.dp.toPx()
                val sx = (size.width - 2 * pad) / (maxX - minX).toFloat()
                val sy = (size.height - 2 * pad) / (maxY - minY).toFloat()
                val s = minOf(sx, sy)
                val ox = (size.width - (maxX - minX).toFloat() * s) / 2
                val oy = (size.height - (maxY - minY).toFloat() * s) / 2
                fun p(lat: Double, lon: Double) =
                    Offset(ox + ((lon * kx - minX) * s).toFloat(), oy + ((-lat - minY) * s).toFloat())

                // the two directions, side by side
                routes.forEachIndexed { i, (_, r) ->
                    val pts = r.second.map { p(it.lat, it.lon) }
                    val off = if (i == 0) -2.5f else 2.5f
                    val path = Path()
                    pts.forEachIndexed { k, pt ->
                        val a = pts[maxOf(0, k - 1)]
                        val b = pts[minOf(pts.size - 1, k + 1)]
                        val len = hypot(b.x - a.x, b.y - a.y).coerceAtLeast(0.01f)
                        val q = pt + Offset(-(b.y - a.y) / len, (b.x - a.x) / len) * (off * density)
                        if (k == 0) path.moveTo(q.x, q.y) else path.lineTo(q.x, q.y)
                    }
                    drawPath(path, DirColors[i % 2], style = Stroke(4.dp.toPx(), cap = StrokeCap.Round,
                                                                   join = StrokeJoin.Round))
                    pts.forEach { drawCircle(Color.White, 2.dp.toPx(), it) }
                }
                // your stops
                routes.forEachIndexed { i, (b, r) ->
                    val st = r.second.firstOrNull { it.code == b.code } ?: return@forEachIndexed
                    val c = p(st.lat, st.lon)
                    drawCircle(DirColors[i % 2], 9.dp.toPx(), c)
                    drawCircle(Color.White, 5.dp.toPx(), c)
                }
                routes.firstOrNull()?.let { (b, r) ->
                    r.second.firstOrNull { it.code == b.code }?.let { st ->
                        val t = measurer.measure("You (Aldersgate Rd)", labelStyle)
                        val c = p(st.lat, st.lon)
                        drawText(t, topLeft = c + Offset(12.dp.toPx(), -t.size.height / 2f))
                    }
                }
                // the two ends of the route
                for ((k, st) in ends.withIndex()) {
                    val c = p(st.lat, st.lon)
                    drawCircle(onSurface, 5.dp.toPx(), c)
                    val name = if (k == 0) "Hillsborough Heights" else "Britomart (city)"
                    val t = measurer.measure(name, labelStyle)
                    val left = c.x + 9.dp.toPx() + t.size.width > size.width
                    drawText(t, topLeft = Offset(if (left) c.x - 9.dp.toPx() - t.size.width else c.x + 9.dp.toPx(),
                                                 c.y - t.size.height / 2f))
                }
                // every bus on its way, pointing where it's heading, with its countdown
                for ((i, d, v) in buses) {
                    val c = p(v.lat, v.lon)
                    val col = DirColors[i % 2]
                    val bw = 22.dp.toPx()
                    val bh = 12.dp.toPx()
                    rotate((v.bearing ?: 90f) - 90f, c) {
                        drawRoundRect(Color.White, c - Offset(bw / 2 + 2.dp.toPx(), bh / 2 + 2.dp.toPx()),
                                      Size(bw + 4.dp.toPx(), bh + 4.dp.toPx()), CornerRadius(5.dp.toPx()))
                        drawRoundRect(col, c - Offset(bw / 2, bh / 2), Size(bw, bh), CornerRadius(4.dp.toPx()))
                        drawRect(Color.White.copy(alpha = 0.85f), c + Offset(bw / 2 - 5.dp.toPx(), -bh / 2 + 2.dp.toPx()),
                                 Size(3.dp.toPx(), bh - 4.dp.toPx()))
                    }
                    val t = measurer.measure(countdown(d.expected - now), tagStyle)
                    val tl = c + Offset(-t.size.width / 2f, -bh - t.size.height - 2.dp.toPx())
                    drawRoundRect(col, tl - Offset(5.dp.toPx(), 2.dp.toPx()),
                                  Size(t.size.width + 10.dp.toPx(), t.size.height + 4.dp.toPx()),
                                  CornerRadius(8.dp.toPx()))
                    drawText(t, topLeft = tl)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                routes.forEachIndexed { i, (b, _) ->
                    Box(Modifier.size(10.dp).background(DirColors[i % 2], CircleShape))
                    Spacer(Modifier.width(5.dp))
                    Text("to ${b.headsign.ifEmpty { "…" }}", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.width(14.dp))
                }
            }
        }
    }
}
