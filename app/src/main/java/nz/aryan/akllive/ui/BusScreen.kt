package nz.aryan.akllive.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import nz.aryan.akllive.AppViewModel
import nz.aryan.akllive.data.BusDeparture
import nz.aryan.akllive.data.Nz
import nz.aryan.akllive.data.StopBoard
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

@Composable
fun BusScreen(vm: AppViewModel, modifier: Modifier) {
    val boards by vm.boards.collectAsStateWithLifecycle()
    val error by vm.busError.collectAsStateWithLifecycle()
    val now by rememberNow()
    val frameT = rememberFrameTime()
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { BusHeader(vm.prefs.place, vm.prefs.route, now, boards, error) { vm.refresh() } }
        itemsIndexed(boards, key = { _, b -> b.code }) { i, b -> BusCard(b, now, frameT, i) }
        item { RouteMapCard(boards, now) }
        item {
            Text("Live data from Auckland Transport. Buses enter the scene 20 minutes out.",
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant,
                 modifier = Modifier.padding(horizontal = 4.dp))
        }
    }
}

@Composable
private fun BusHeader(place: String, route: String, now: Long, boards: List<StopBoard>, error: String?,
                      onRefresh: () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(place, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
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
    // the bus glides as its arrival time counts down; a new bus starts from the left
    val eta = next?.let { it.expected - now }
    val target = eta?.let { (1f - it / 1200f).coerceIn(0f, 1f) }
    val progress = remember(next?.tripId) { Animatable(target ?: 0f) }
    LaunchedEffect(target) { if (target != null) progress.animateTo(target, tween(1500)) }

    Card(shape = RoundedCornerShape(22.dp), elevation = CardDefaults.cardElevation(3.dp),
         colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Box(Modifier.fillMaxWidth().height(200.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                drawBusScene(scenery, Nz.hour(), frameT.value,
                             if (next == null || eta == null || eta > 1500) null else progress.value,
                             moving = next?.live == true && (eta ?: 0) > 30,
                             cancelled = next?.cancelled == true, dest = dest, dp = density)
            }
            // legibility veil over the top of the sky
            Box(Modifier.fillMaxWidth().height(86.dp).background(
                Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.38f), Color.Transparent))))
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.background(Pal.Bus, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Text(route.ifEmpty { "Bus" }, color = Color.White, fontWeight = FontWeight.Black, fontSize = 20.sp)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("to ${b.headsign.ifEmpty { "…" }}", color = Color.White, fontWeight = FontWeight.Bold,
                         fontSize = 19.sp)
                    Text(b.name.ifEmpty { "Stop ${b.code}" } + " · stop ${b.code}", color = Color.White.copy(alpha = 0.85f),
                         fontSize = 12.sp)
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
                NextBusDetail(next, b)
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
        Text(parts[0], fontSize = if (parts[0].length > 3) 16.sp else 30.sp, fontWeight = FontWeight.Black,
             color = if (next?.cancelled == true) Pal.Late else Pal.Navy)
        if (parts.size > 1) Text(parts.drop(1).joinToString(" "), fontSize = 11.sp, color = Pal.Ink,
                                  fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun NextBusDetail(d: BusDeparture, b: StopBoard) {
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
    val facts = buildList {
        d.stopsAway?.let { add(if (it == 0) "At the stop" else if (it == 1) "1 stop away" else "$it stops away") }
        d.vehicle?.let { v ->
            add("%.1f km away".format(distanceKm(v.lat, v.lon, b.lat, b.lon)))
            v.speedKmh?.takeIf { it > 1 }?.let { add("${it.toInt()} km/h") }
            nz.aryan.akllive.data.occupancyText(v.occupancy)?.let { add(it) }
            if (v.label.isNotEmpty()) add("bus ${v.label}")
        }
    }
    if (facts.isNotEmpty()) {
        Text(facts.joinToString("  ·  "), style = MaterialTheme.typography.bodyMedium,
             color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
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
            d.stopsAway?.let {
                Text("$it stops away", style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(if (d.cancelled) "—" else countdown(d.expected - now), fontWeight = FontWeight.Black, fontSize = 18.sp)
    }
}
