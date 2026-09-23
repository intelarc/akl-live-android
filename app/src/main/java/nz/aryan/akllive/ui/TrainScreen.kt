package nz.aryan.akllive.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nz.aryan.akllive.AppViewModel
import nz.aryan.akllive.data.MapData
import nz.aryan.akllive.data.Nz
import nz.aryan.akllive.data.StationDeparture
import nz.aryan.akllive.data.Train
import nz.aryan.akllive.data.TrainState
import nz.aryan.akllive.data.TripDetail
import nz.aryan.akllive.data.occupancyText

@Composable
fun TrainScreen(vm: AppViewModel, modifier: Modifier) {
    val state by vm.trains.collectAsStateWithLifecycle()
    val trip by vm.trip.collectAsStateWithLifecycle()
    val station by vm.station.collectAsStateWithLifecycle()
    val now by rememberNow()
    val frameT = rememberFrameTime()
    var selTrain by remember { mutableStateOf<String?>(null) }
    var selStation by remember { mutableStateOf<Int?>(null) }
    var filter by remember { mutableStateOf(setOf(0, 1, 2)) }
    val zoom = remember { mutableFloatStateOf(1f) }
    val pan = remember { mutableStateOf(Offset.Zero) }

    // keep a selected station's departures fresh
    LaunchedEffect(selStation, state.updated) { selStation?.let { vm.selectStation(it) } }

    val pickTrain: (Train) -> Unit = { t ->
        selTrain = t.vehicle.id
        selStation = null
        vm.selectStation(null)
        vm.selectTrain(t.vehicle.tripId, t.vehicle.startDate)
    }
    val pickStation: (Int) -> Unit = { i ->
        selStation = i
        selTrain = null
        vm.selectTrain(null, null)
    }
    val clear = {
        selTrain = null
        selStation = null
        vm.selectTrain(null, null)
        vm.selectStation(null)
    }

    val map: @Composable (Modifier) -> Unit = { m ->
        Box(m.clip(RoundedCornerShape(bottomStart = 22.dp, bottomEnd = 22.dp))) {
            TrainMap(state, filter, selTrain, selStation, zoom, pan, frameT,
                     pickTrain, pickStation, clear, Modifier.fillMaxSize())
            MapHeader(state, now, filter) { li ->
                filter = if (li in filter && filter.size > 1) filter - li else filter + li
            }
            Column(Modifier.align(Alignment.BottomEnd).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallFloatingActionButton(onClick = { zoom.floatValue = (zoom.floatValue * 1.6f).coerceAtMost(9f) }) {
                    Text("+", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
                SmallFloatingActionButton(onClick = {
                    zoom.floatValue = (zoom.floatValue / 1.6f).coerceAtLeast(1f)
                    if (zoom.floatValue == 1f) pan.value = Offset.Zero
                }) { Text("−", fontSize = 22.sp, fontWeight = FontWeight.Bold) }
                SmallFloatingActionButton(onClick = { zoom.floatValue = 1f; pan.value = Offset.Zero }) {
                    Icon(Icons.Filled.Home, "Whole network")
                }
            }
        }
    }
    val panel: @Composable (Modifier) -> Unit = { m ->
        Surface(m, color = MaterialTheme.colorScheme.background) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                val train = state.trains.firstOrNull { it.vehicle.id == selTrain }
                when {
                    train != null -> TrainPanel(train, trip?.takeIf { it.tripId == train.vehicle.tripId }, now, clear)
                    selStation != null -> StationPanel(selStation!!, station?.takeIf { it.first == selStation }?.second,
                                                       now, clear)
                    else -> Overview(state, now)
                }
            }
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val maxH = maxHeight                  // read here: inner layout scopes can't see it
        if (maxWidth > maxH) {
            Row(Modifier.fillMaxSize()) {
                map(Modifier.weight(1.5f).fillMaxHeight())
                panel(Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                map(Modifier.fillMaxWidth().weight(1f))
                panel(Modifier.fillMaxWidth().heightIn(max = maxH * 0.48f))
            }
        }
    }
}

@Composable
private fun MapHeader(state: TrainState, now: Long, filter: Set<Int>, toggle: (Int) -> Unit) {
    val counts = state.counts()
    Column(Modifier.fillMaxWidth().background(Pal.AtBlue).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Ngā Tereina", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Spacer(Modifier.width(8.dp))
            Text("Trains · live", color = Color(0xFFBED4F0), fontSize = 14.sp)
            Spacer(Modifier.weight(1f))
            Text(Nz.time(now), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (li in MapData.LINE_IDS.indices) {
                val on = li in filter
                Row(
                    Modifier.clip(RoundedCornerShape(50))
                        .background(if (on) Color.White else Color.White.copy(alpha = 0.12f))
                        .border(1.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(50))
                        .clickable { toggle(li) }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(10.dp).background(Pal.line(li), CircleShape))
                    Spacer(Modifier.width(6.dp))
                    Text("${MapData.LINE_IDS[li]}  ${counts[li]}", fontWeight = FontWeight.Bold, fontSize = 13.sp,
                         color = if (on) Pal.Navy else Color.White)
                }
            }
        }
        state.error?.let {
            Text(it, color = Color(0xFFFFD2D2), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun Overview(state: TrainState, now: Long) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("${state.trains.size} trains running", style = MaterialTheme.typography.titleLarge,
             fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        LiveBadge(state.updated, now)
    }
    Spacer(Modifier.height(10.dp))
    for (li in MapData.LINE_IDS.indices) {
        val trains = state.trains.filter { it.line == li }
        val known = trains.mapNotNull { it.delay }
        val late = known.count { it >= 120 }
        val avg = if (known.isEmpty()) null else known.average().toInt()
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            LinePill(li, big = true)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(MapData.LINE_NAMES[li], fontWeight = FontWeight.Bold)
                Text(when {
                    trains.isEmpty() -> "No trains running"
                    known.isEmpty() -> "Waiting for live times"
                    late == 0 -> "All on time"
                    else -> "$late running late · average ${punctuality(avg).first.lowercase()}"
                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("${trains.size}", fontWeight = FontWeight.Black, fontSize = 24.sp)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
    }
    Spacer(Modifier.height(12.dp))
    Text("Tap a train for where it's going, its speed and its next stops. Tap a station for live " +
         "departures from every platform. Pinch or double-tap to zoom.",
         style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun PanelTitle(close: () -> Unit, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { content() }
        IconButton(onClick = close) { Icon(Icons.Filled.Close, "Close") }
    }
}

@Composable
private fun TrainPanel(t: Train, trip: TripDetail?, now: Long, close: () -> Unit) {
    val v = t.vehicle
    PanelTitle(close) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LinePill(t.line, big = true)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(if (trip != null) "to ${trip.headsign}" else "Loading trip…",
                     style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("${MapData.LINE_NAMES[t.line]} line · ${v.label.ifEmpty { "train ${v.id}" }}",
                     style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val (p, c) = punctuality(t.delay)
        Chip(p, c)
        Chip(v.speedKmh?.let { if (it < 2) "Stopped" else "${it.toInt()} km/h" } ?: "Speed unknown", Pal.AtBlue)
        occupancyText(v.occupancy)?.let { Chip(it, Pal.Ink) }
        if (v.timestamp > 0) Chip("GPS ${maxOf(0, now - v.timestamp)}s ago", Pal.Ink)
    }
    Spacer(Modifier.height(14.dp))
    Text("Next stops", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(6.dp))
    if (trip == null) {
        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        return
    }
    val delay = t.delay ?: 0
    val ahead = trip.stops.filter { it.seq > (t.stopSeq ?: 0) && it.scheduled + delay >= now - 60 }
    if (ahead.isEmpty()) {
        Text("Arriving at the end of the line", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    ahead.take(10).forEachIndexed { i, s ->
        Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            // a little timeline in the line's colour
            Box(Modifier.width(22.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                Box(Modifier.width(4.dp).fillMaxHeight().background(Pal.line(t.line).copy(alpha = if (i == 0) 0.5f else 1f)))
                Box(Modifier.size(if (i == 0) 14.dp else 10.dp).background(Color.White, CircleShape)
                        .border(3.dp, Pal.line(t.line), CircleShape))
            }
            Spacer(Modifier.width(10.dp))
            Text(s.name, fontWeight = if (i == 0) FontWeight.Bold else FontWeight.Normal,
                 modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Column(horizontalAlignment = Alignment.End) {
                Text(Nz.time(s.scheduled + delay), fontWeight = FontWeight.Bold)
                Text(countdown(s.scheduled + delay - now), style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun StationPanel(index: Int, deps: List<StationDeparture>?, now: Long, close: () -> Unit) {
    val st = MapData.STATIONS[index]
    PanelTitle(close) {
        Column {
            Text(st.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                for (li in MapData.LINE_IDS.indices) {
                    if (st.lines and (1 shl li) != 0) { LinePill(li); Spacer(Modifier.width(5.dp)) }
                }
                if (st.crl) Chip("City Rail Link", Color(0xFFB7791F))
                Spacer(Modifier.width(5.dp))
                Text("${st.platforms.size} platform${if (st.platforms.size == 1) "" else "s"}",
                     style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    when {
        deps == null -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        deps.isEmpty() -> Text("No departures in the next two hours",
                               color = MaterialTheme.colorScheme.onSurfaceVariant)
        else -> deps.forEach { d ->
            Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center) {
                    Text("P${d.platform}", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                Spacer(Modifier.width(10.dp))
                LinePill(d.line)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(d.headsign, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val (p, c) = punctuality(d.delay)
                    Text("${Nz.time(d.expected)} · $p", color = c, style = MaterialTheme.typography.bodySmall)
                }
                Text(countdown(d.expected - now), fontWeight = FontWeight.Black, fontSize = 17.sp)
            }
        }
    }
}
