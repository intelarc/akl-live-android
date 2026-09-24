package nz.aryan.akllive.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import nz.aryan.akllive.AppViewModel
import nz.aryan.akllive.data.BusDeparture
import nz.aryan.akllive.data.BusModel
import nz.aryan.akllive.data.Fleet
import nz.aryan.akllive.data.Nz
import nz.aryan.akllive.data.RouteData
import nz.aryan.akllive.data.StopBoard
import nz.aryan.akllive.data.Vehicle
import nz.aryan.akllive.data.occupancyText
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Epoch seconds, ticking once a second. */
@Composable
fun rememberNow(): State<Long> = produceState(Nz.nowSec()) {
    while (true) {
        delay(1000 - System.currentTimeMillis() % 1000)
        value = Nz.nowSec()
    }
}

/** Seconds since the screen appeared, updated every frame (read it inside draw). */
@Composable
fun rememberFrameTime(): State<Float> {
    val s = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { s.floatValue = (it - start) / 1e9f }
    }
    return s
}

fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * r * asin(sqrt(a))
}

/** The bus's make and model, when the fleet list knows its fleet number. */
fun Vehicle.busModel(): BusModel? = Fleet.info(label)?.model

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BusScreen(vm: AppViewModel, modifier: Modifier) {
    val boards by vm.boards.collectAsStateWithLifecycle()
    val error by vm.busError.collectAsStateWithLifecycle()
    val pulling by vm.pulling.collectAsStateWithLifecycle()
    val basemap by vm.basemap.collectAsStateWithLifecycle()
    val linzKey by vm.linzKey.collectAsStateWithLifecycle()
    val now by rememberNow()
    val frameT = rememberFrameTime()
    var mapOpen by rememberSaveable { mutableStateOf(false) }

    if (mapOpen) {
        Box(modifier.fillMaxSize()) {
            BusMapScreen(boards, now, vm.prefs.place, basemap, linzKey, frameT, vm::setBasemap) { mapOpen = false }
        }
        return
    }
    PullToRefreshBox(pulling, onRefresh = vm::pullRefresh, modifier = modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { BusHeader(vm.prefs.place, vm.prefs.route, now, boards, error) { vm.pullRefresh() } }
            itemsIndexed(boards, key = { _, b -> b.code }) { i, b -> BusCard(b, now, frameT, i) }
            item {
                RouteMapCard(boards, now, vm.prefs.place, basemap, linzKey, frameT) { mapOpen = true }
            }
            item {
                Text("Live data from Auckland Transport. Buses enter the scene 20 minutes out. Pull down to refresh.",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant,
                     modifier = Modifier.padding(horizontal = 4.dp))
            }
        }
    }
}

@Composable
private fun BusHeader(place: String, route: String, now: Long, boards: List<StopBoard>, error: String?,
                      onRefresh: () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(place, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
                val stop = boards.firstOrNull { it.name.isNotEmpty() }?.name ?: "…"
                Text("$route · $stop", style = MaterialTheme.typography.bodyMedium,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(Nz.time(now), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                LiveBadge(boards.maxOfOrNull { it.updated } ?: 0L, now)
            }
            IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, "Refresh") }
        }
        // the next bus each way, at a glance
        val glance = boards.mapNotNull { b -> b.departures.firstOrNull { !it.cancelled }?.let { b to it } }
        if (glance.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                glance.forEachIndexed { i, (b, d) ->
                    Row(Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surface)
                            .padding(start = 6.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(22.dp).background(DirColors[i % 2], CircleShape), contentAlignment = Alignment.Center) {
                            Text(if (i == 0) "↑" else "↓", color = Color.White, fontWeight = FontWeight.Black, fontSize = 12.sp)
                        }
                        Spacer(Modifier.width(7.dp))
                        Text(b.headsign.ifEmpty { "…" }, style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.width(6.dp))
                        Text(countdown(d.expected - now), style = MaterialTheme.typography.labelLarge,
                             fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
                        d.vehicle?.busModel()?.takeIf { it.electric }?.let {
                            Text(" ⚡", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
        if (error != null) {
            Text(error, color = Color.White, style = MaterialTheme.typography.bodySmall,
                 modifier = Modifier.padding(top = 8.dp).fillMaxWidth()
                     .background(Pal.Late, RoundedCornerShape(10.dp)).padding(10.dp))
        }
    }
}

@Composable
fun LiveBadge(updated: Long, now: Long) {
    val pulse by rememberInfiniteTransition(label = "live").animateFloat(
        0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse")
    val age = if (updated == 0L) null else now - updated
    val stale = age != null && age > 120
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape)
                .background((if (stale || age == null) Pal.Warn else Pal.Live).copy(alpha = pulse)))
        Spacer(Modifier.width(5.dp))
        Text(when {
            age == null -> "Connecting…"
            stale -> "Stale · ${age / 60} min"
            age < 5 -> "Live · just now"
            else -> "Live · ${age}s ago"
        }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun BusCard(b: StopBoard, now: Long, frameT: State<Float>, index: Int) {
    val next = b.departures.firstOrNull()
    val city = b.headsign.contains("Britomart", true) || b.headsign.contains("City", true)
    val scenery = remember(city, index) { Scenery(city, index) }
    val measurer = rememberTextMeasurer()
    val route = next?.route ?: b.route
    val dest = remember(route) {
        measurer.measure(route, TextStyle(color = Color(0xFFFFB02E), fontSize = 9.sp,
                                          fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace))
    }
    val model = next?.vehicle?.busModel()
    val look = BusLook(doubleDeck = model?.doubleDeck == true, electric = model?.electric == true)
    // the bus glides as its arrival time counts down; a new bus starts from the left
    val eta = next?.let { it.expected - now }
    val target = eta?.let { (1f - it / 1200f).coerceIn(0f, 1f) }
    val progress = remember(next?.tripId) { Animatable(target ?: 0f) }
    LaunchedEffect(target) { if (target != null) progress.animateTo(target, tween(1500)) }

    Card(shape = RoundedCornerShape(24.dp), elevation = CardDefaults.cardElevation(3.dp),
         colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Box(Modifier.fillMaxWidth().height(210.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                drawBusScene(scenery, Nz.hour(), frameT.value,
                             if (next == null || eta == null || eta > 1500) null else progress.value,
                             moving = next?.live == true && (eta ?: 0) > 30,
                             cancelled = next?.cancelled == true, dest = dest, dp = density, look = look)
            }
            // legibility veil over the top of the sky
            Box(Modifier.fillMaxWidth().height(90.dp).background(
                Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.4f), Color.Transparent))))
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.background(DirColors[index % 2], RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Text(route.ifEmpty { "Bus" }, color = Color.White, fontWeight = FontWeight.Black, fontSize = 20.sp)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    // a soft shadow keeps the text readable over clouds and sun
                    val lift = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.7f), Offset(0f, 1.5f), 8f))
                    Text("to ${b.headsign.ifEmpty { "…" }}", color = Color.White, fontWeight = FontWeight.Bold,
                         fontSize = 19.sp, style = lift)
                    Text(b.name.ifEmpty { "Stop ${b.code}" } + " · stop ${b.code}", color = Color.White,
                         fontSize = 12.sp, fontWeight = FontWeight.SemiBold, style = lift)
                }
                BigCountdown(next, now)
            }
        }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            if (b.error != null && b.departures.isEmpty()) {
                Text(b.error, color = Pal.Late, style = MaterialTheme.typography.bodyMedium)
            } else if (next == null) {
                Text(if (b.updated == 0L) "Loading…" else "No buses in the next few hours",
                     style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                NextBusDetail(next, b, DirColors[index % 2])
            }
            if (b.departures.size > 1) {
                HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.surfaceVariant)
                Text("Next buses", style = MaterialTheme.typography.labelLarge,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
                b.departures.drop(1).take(5).forEach { DepartureRow(it, now) }
            }
        }
    }
}

@Composable
private fun BigCountdown(next: BusDeparture?, now: Long) {
    val text = when {
        next == null -> "--"
        next.cancelled -> "Cancelled"
        else -> countdown(next.expected - now)
    }
    val parts = text.split(" ")
    Column(Modifier.background(Color.White, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 4.dp),
           horizontalAlignment = Alignment.CenterHorizontally) {
        // the number rolls over as it counts down
        AnimatedContent(parts[0], transitionSpec = {
            (slideInVertically { -it } + fadeIn()) togetherWith (slideOutVertically { it } + fadeOut())
        }, label = "countdown") { n ->
            Text(n, fontSize = if (n.length > 3) 16.sp else 30.sp, fontWeight = FontWeight.Black,
                 color = if (next?.cancelled == true) Pal.Late else Pal.Navy)
        }
        if (parts.size > 1) Text(parts.drop(1).joinToString(" "), fontSize = 11.sp, color = Pal.Ink,
                                  fontWeight = FontWeight.Bold)
    }
}

/** How far off a bus is, in words: stops, distance, speed, how full. */
fun busFacts(d: BusDeparture, b: StopBoard?): List<String> = buildList {
    d.stopsAway?.let { add(if (it == 0) "At the stop" else if (it == 1) "1 stop away" else "$it stops away") }
    d.vehicle?.let { v ->
        if (b != null && b.lat != 0.0) add("%.1f km away".format(distanceKm(v.lat, v.lon, b.lat, b.lon)))
        v.speedKmh?.takeIf { it > 1 }?.let { add("${it.toInt()} km/h") }
        occupancyText(v.occupancy)?.let { add(it) }
    }
}

@Composable
private fun NextBusDetail(d: BusDeparture, b: StopBoard, color: Color) {
    val (punct, pc) = if (d.live) punctuality(d.delay) else "Scheduled" to MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically) {
        Chip(if (d.cancelled) "Cancelled" else punct, if (d.cancelled) Pal.Late else pc)
        if (d.live && !d.cancelled) { Spacer(Modifier.width(6.dp)); Chip("● Live GPS", Pal.Live) }
        Spacer(Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Text("Arrives " + Nz.time(d.expected), fontWeight = FontWeight.Bold)
            if (d.live && d.delay / 60 != 0) {
                Text("timetabled " + Nz.time(d.scheduled), style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant,
                     textDecoration = TextDecoration.LineThrough)
            }
        }
    }
    d.vehicle?.let {
        Spacer(Modifier.height(12.dp))
        VehicleInfo(it)
    }
    d.stopsAway?.let { away ->
        Spacer(Modifier.height(12.dp))
        StopsTrack(b, away, color)
    }
    val facts = busFacts(d, b).filter { !it.contains("stop") }
    if (facts.isNotEmpty()) {
        Text(facts.joinToString("  ·  "), style = MaterialTheme.typography.bodyMedium,
             color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
    }
}

/** What the bus is: its model (when the fleet list knows it), operator and fleet number. */
@Composable
fun VehicleInfo(v: Vehicle, big: Boolean = false) {
    val info = Fleet.info(v.label) ?: return
    val model = info.model
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        BusGlyph(model, Modifier.size(width = if (big) 56.dp else 48.dp, height = 34.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(model?.name ?: "${info.operator ?: "Bus"} ${info.fleetNo}", fontWeight = FontWeight.Bold,
                 style = if (big) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                 maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (model == null) "Model not in the fleet list yet"
                 else listOfNotNull(info.operator, info.fleetNo).joinToString(" · "),
                 style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (model != null) {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (model.electric) Chip("⚡ Electric", Pal.Live)
                if (model.doubleDeck) Chip("Double-decker", Pal.AtBlue)
            }
        }
    }
}

/** A little side view of the bus: single or double deck, with a bolt if it's electric. */
@Composable
private fun BusGlyph(model: BusModel?, modifier: Modifier) {
    val look = BusLook(doubleDeck = model?.doubleDeck == true, electric = model?.electric == true)
    Canvas(modifier) {
        val bl = size.width
        val bh = if (look.doubleDeck) size.height / 1.6f else size.height / 1.6f * 0.95f
        drawBus(0f, size.height, bl, bh, 0f, night = false, moving = false, cancelled = false, dest = null,
                dp = density, look = look)
    }
}

/** The stops between the bus and you, with the bus on the last one it passed. */
@Composable
private fun StopsTrack(b: StopBoard, away: Int, color: Color) {
    val stops = RouteData.ROUTES[b.code]?.second ?: return
    val mine = stops.indexOfFirst { it.code == b.code }
    if (mine < 0) return
    val at = (mine - away).coerceIn(0, mine)
    val shown = minOf(away, 9)                          // at most this many stops drawn before yours
    val track = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
    val surface = MaterialTheme.colorScheme.surface
    Column {
        Canvas(Modifier.fillMaxWidth().height(26.dp)) {
            val pad = 10.dp.toPx()
            val y = size.height / 2
            val right = size.width - pad
            val left = if (shown > 0) pad else right
            val step = if (shown > 0) (right - left) / shown else 0f
            // further back than we draw: the track fades in from the edge
            if (away > shown) drawLine(track, Offset(0f, y), Offset(left, y), 4.dp.toPx(), StrokeCap.Round)
            drawLine(color, Offset(left, y), Offset(right, y), 4.dp.toPx(), StrokeCap.Round)
            for (k in 1..shown) {
                val x = left + k * step
                val yours = k == shown
                val r = (if (yours) 7 else 4).dp.toPx()
                drawCircle(surface, r, Offset(x, y))
                drawCircle(color, r, Offset(x, y), style = Stroke(2.dp.toPx()))
            }
            // the bus, on the stop it last passed
            drawCircle(color.copy(alpha = 0.25f), 13.dp.toPx(), Offset(left, y))
            drawCircle(color, 9.dp.toPx(), Offset(left, y))
            drawCircle(Color.White, 4.dp.toPx(), Offset(left, y))
        }
        Row {
            Text(if (away == 0) "At your stop" else "Last stop: ${stops[at].name}",
                 style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold,
                 modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (away > 0) {
                Text(if (away == 1) "1 stop away" else "$away stops away", style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun Chip(text: String, color: Color) {
    Box(Modifier.background(color.copy(alpha = 0.14f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp)) {
        Text(text, color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun DepartureRow(d: BusDeparture, now: Long) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(78.dp)) {
            Text(Nz.time(d.expected), fontWeight = FontWeight.Bold)
            if (d.live && d.delay / 60 != 0) {
                Text(Nz.time(d.scheduled), style = MaterialTheme.typography.bodySmall,
                     textDecoration = TextDecoration.LineThrough, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(Modifier.weight(1f)) {
            val (p, c) = when {
                d.cancelled -> "Cancelled" to Pal.Late
                d.live -> punctuality(d.delay)
                else -> "Scheduled" to MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(p, color = c, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            val sub = listOfNotNull(
                d.stopsAway?.let { if (it == 1) "1 stop away" else "$it stops away" },
                d.vehicle?.busModel()?.let { m -> m.short + if (m.electric) " ⚡" else "" },
            )
            if (sub.isNotEmpty()) {
                Text(sub.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(if (d.cancelled) "—" else countdown(d.expected - now), fontWeight = FontWeight.Black, fontSize = 18.sp)
    }
}
