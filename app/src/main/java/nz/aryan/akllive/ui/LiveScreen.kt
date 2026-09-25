package nz.aryan.akllive.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.text.style.TextAlign
import nz.aryan.akllive.data.Nz
import nz.aryan.akllive.gtfs.Timetable
import nz.aryan.akllive.gtfs.TripInfo
import nz.aryan.akllive.gtfs.tripInfo
import nz.aryan.akllive.AppViewModel
import nz.aryan.akllive.data.BusTrip
import nz.aryan.akllive.data.Fleet
import nz.aryan.akllive.data.LiveBus
import nz.aryan.akllive.data.occupancyText

/** The whole region, for framing every bus at once. */
val AKL_BOUNDS = listOf(-36.62 to 174.56, -37.08 to 174.98)

/** Each operator's colour on the maps. */
fun operatorColor(code: String): Color = when (code) {
    "NB" -> Color(0xFF3D8BFF)
    "RT", "PC" -> Color(0xFFFF6B4A)
    "GB" -> Color(0xFF00C2B8)
    "HE" -> Color(0xFFB36BFF)
    "TR" -> Color(0xFF7ED957)
    "WB" -> Color(0xFFFFC53D)
    "BA" -> Color(0xFFFF7FD1)
    else -> Color(0xFF9AA3AE)
}

/** What the Live map shows: everything, one kind of bus, or one operator ("op:NB"). */
private fun LiveBus.passes(filter: String): Boolean = when {
    filter == "electric" -> info.model?.electric == true
    filter == "double" -> info.model?.doubleDeck == true
    filter.startsWith("op:") -> info.code == filter.removePrefix("op:")
    else -> true
}

/** A route ("27" finds 27H, 27W...), a fleet number, or a model ("eT12"). */
private fun LiveBus.matches(q: String): Boolean {
    if (q.isBlank()) return true
    val s = q.trim().replace(" ", "")
    return route?.startsWith(s, ignoreCase = true) == true ||
           info.fleetNo.contains(s, ignoreCase = true) ||
           info.model?.name?.replace(" ", "")?.contains(s, ignoreCase = true) == true
}

fun LiveBus.dot(label: Boolean = true) = CrowdDot(v.id, v.lat, v.lon, v.bearing, operatorColor(info.code),
                                                  if (label) route else null)

fun LiveBus.marker() = MapMarker(v.id, v.lat, v.lon, v.bearing, operatorColor(info.code), train = false,
                                 tag = route ?: info.fleetNo)

/** Every bus in Auckland, live, on one map. */
@Composable
fun LiveScreen(vm: AppViewModel, modifier: Modifier) {
    DisposableEffect(Unit) {
        vm.watchLive(true)
        onDispose { vm.watchLive(false); vm.selectBus(null) }
    }
    val state by vm.fleet.collectAsStateWithLifecycle()
    val trip by vm.busTrip.collectAsStateWithLifecycle()
    val basemap by vm.basemap.collectAsStateWithLifecycle()
    val linzKey by vm.linzKey.collectAsStateWithLifecycle()
    val now by rememberNow()
    val frameT = rememberFrameTime()
    var filter by rememberSaveable { mutableStateOf("all") }
    var query by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val focusManager = LocalFocusManager.current

    val tt by vm.timetable.collectAsStateWithLifecycle()
    val nav = LocalNav.current
    val shown = remember(state.buses, filter, query) { state.buses.filter { it.passes(filter) && it.matches(query) } }
    val sel = state.buses.firstOrNull { it.v.id == selected }
    val crowd = remember(shown, selected) { shown.filter { it.v.id != selected }.map { it.dot() } }
    // the tapped bus's trip, fresh with every update of the fleet
    LaunchedEffect(sel?.v?.tripId, state.updated) { vm.selectBus(sel?.v?.tripId, refresh = true) }
    // its whole route and the stops still to come, from the timetable
    val info = remember(sel?.v?.tripId, tt.ready) { sel?.v?.tripId?.let { Timetable.today?.tripInfo(it) } }
    val liveTrip = trip?.takeIf { it.tripId == sel?.v?.tripId }
    val ahead = remember(info, liveTrip, now / 30) { info?.let { upcoming(it, liveTrip) } ?: emptyList() }
    val routeColor = sel?.let { operatorColor(it.info.code) } ?: Pal.AtBlue
    val routeLines = remember(info, routeColor) { info?.let { listOf(MapLine(listOf(it.shape), routeColor, 5f)) } ?: emptyList() }
    val routeStops = remember(ahead, routeColor) {
        ahead.mapIndexed { i, a ->
            val last = i == ahead.lastIndex
            MapStop(a.stop.lat, a.stop.lon, routeColor, big = last, label = if (last) a.stop.name else null, id = a.stop.id)
        }
    }
    var follow by remember { mutableStateOf(false) }
    var focus by remember { mutableStateOf<MapFocus?>(null) }
    LaunchedEffect(selected) { follow = false }
    LaunchedEffect(follow, sel?.v?.lat, sel?.v?.lon) {
        val b = sel ?: return@LaunchedEffect
        if (follow) focus = MapFocus(b.v.lat, b.v.lon, 15.5)
    }

    // a search frames what it found (once, not on every refresh); clearing it goes back to the region
    var fit by remember { mutableStateOf(AKL_BOUNDS) }
    LaunchedEffect(query, filter, state.updated > 0) {
        delay(450)
        val hits = if (query.isBlank() && filter == "all") emptyList() else shown
        fit = when {
            hits.isEmpty() -> AKL_BOUNDS
            hits.size == 1 -> listOf(hits[0].v.lat to hits[0].v.lon)
            else -> hits.map { it.v.lat to it.v.lon }
        }
    }

    Box(modifier.fillMaxSize()) {
        LiveMap(basemap, linzKey, routeLines, routeStops, listOfNotNull(sel?.marker()), fit, frameT,
                Modifier.fillMaxSize(), selected = selected, topInset = 150f, crowd = crowd, focus = focus,
                onCrowd = { selected = it; focusManager.clearFocus() },
                onMarker = { selected = it }, onBackground = { selected = null; focusManager.clearFocus() },
                onStop = { id -> nav.go("stop/${android.net.Uri.encode(id)}") })

        // header over a soft shade
        Box(Modifier.fillMaxWidth().height(220.dp).background(
            Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.62f), Color.Transparent))))
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 6.dp)) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Every bus", color = Color.White, fontWeight = FontWeight.Black, fontSize = 24.sp)
                    val count = state.buses.size
                    Text(when {
                        state.loading -> "Finding every bus in Auckland…"
                        state.error != null && count == 0 -> state.error ?: ""
                        query.isNotBlank() || filter != "all" -> "%,d of %,d shown · %s".format(shown.size, count, liveAgo(state.updated, now))
                        else -> "%,d on the road · %s".format(count, liveAgo(state.updated, now))
                    }, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
                }
                BasemapToggle(basemap, vm::setBasemap)
            }
            Spacer(Modifier.height(10.dp))
            SearchPill(query, { query = it }, Modifier.padding(horizontal = 16.dp)) { focusManager.clearFocus() }
            Spacer(Modifier.height(8.dp))
            FilterRow(state.buses, filter) { filter = if (filter == it) "all" else it }
        }

        AnimatedVisibility(sel != null, Modifier.align(Alignment.BottomCenter).padding(12.dp),
                           enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
            sel?.let {
                LiveBusCard(it, liveTrip, ahead, now, follow,
                            onFollow = { follow = !follow },
                            onZoom = { focus = MapFocus(it.v.lat, it.v.lon, 16.0) },
                            onStop = { id -> nav.go("stop/${android.net.Uri.encode(id)}") }) { selected = null }
            }
        }
    }
}

/** A stop still to come on a bus's trip, and when it should get there. */
class Ahead(val stop: nz.aryan.akllive.gtfs.StopInfo, val eta: Long)

/** The stops a trip has still to make: after the last one realtime says it reached, or by the clock. */
fun upcoming(info: TripInfo, live: BusTrip?): List<Ahead> {
    val day = Timetable.dayStart(Timetable.today?.date ?: Timetable.todayYmd()) + info.offset
    val delay = live?.delay ?: 0
    val passed = live?.seq
    val now = Nz.nowSec()
    return info.stops.indices.filter { k ->
        if (passed != null) info.seqs[k] > passed else day + info.stops[k].second + delay > now - 30
    }.map { k -> Ahead(info.stops[k].first, day + info.stops[k].second + delay) }
}

/** "Live · 12s ago" */
fun liveAgo(updated: Long, now: Long): String {
    if (updated == 0L) return "connecting…"
    val s = (now - updated).coerceAtLeast(0)
    return if (s < 5) "live, just now" else if (s < 90) "live, ${s}s ago" else "updated ${s / 60} min ago"
}

@Composable
private fun SearchPill(query: String, set: (String) -> Unit, modifier: Modifier, done: () -> Unit) {
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.5f))
            .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Search, null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text("Route, fleet number or model", color = Color.White.copy(alpha = 0.6f), fontSize = 15.sp)
            }
            BasicTextField(query, set, singleLine = true, cursorBrush = SolidColor(Color.White),
                           textStyle = TextStyle(color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium),
                           keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters,
                                                             imeAction = ImeAction.Search),
                           keyboardActions = KeyboardActions(onSearch = { done() }),
                           modifier = Modifier.fillMaxWidth())
        }
        if (query.isNotEmpty()) {
            Icon(Icons.Filled.Close, "Clear", tint = Color.White, modifier = Modifier.size(20.dp).clickable { set(""); done() })
        }
    }
}

@Composable
private fun FilterRow(buses: List<LiveBus>, filter: String, pick: (String) -> Unit) {
    val byOp = remember(buses) { buses.groupingBy { it.info.code }.eachCount() }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterPill("⚡ Electric", buses.count { it.info.model?.electric == true }, null, filter == "electric") { pick("electric") }
        FilterPill("Double-deckers", buses.count { it.info.model?.doubleDeck == true }, null, filter == "double") { pick("double") }
        for ((code, name) in Fleet.OPERATORS) {
            val n = byOp[code] ?: continue
            FilterPill(name, n, operatorColor(code), filter == "op:$code") { pick("op:$code") }
        }
    }
}

@Composable
private fun FilterPill(text: String, count: Int, dot: Color?, on: Boolean, click: () -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(50)).background(if (on) Color.White else Color.Black.copy(alpha = 0.5f))
            .border(1.dp, Color.White.copy(alpha = if (on) 1f else 0.28f), RoundedCornerShape(50))
            .clickable(onClick = click).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically) {
        dot?.let {
            Box(Modifier.size(9.dp).background(it, CircleShape).border(1.dp, Color.White, CircleShape))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = if (on) Pal.Navy else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(5.dp))
        Text("$count", color = if (on) Pal.AtBlue else Color.White.copy(alpha = 0.7f), fontSize = 13.sp,
             fontWeight = FontWeight.Medium)
    }
}

/** The tapped bus: its route and destination, what it is, how it's going, and its next stops. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LiveBusCard(b: LiveBus, trip: BusTrip?, ahead: List<Ahead>, now: Long, follow: Boolean,
                        onFollow: () -> Unit, onZoom: () -> Unit, onStop: (String) -> Unit, close: () -> Unit) {
    val color = operatorColor(b.info.code)
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainer,
            shadowElevation = 6.dp) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.background(color, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 3.dp)) {
                    Text(b.route ?: "—", color = Color.White, fontWeight = FontWeight.Black, fontSize = 17.sp)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(when {
                            b.route == null -> "Not in service"
                            trip?.headsign != null -> "to ${trip.headsign}"
                            else -> "…"
                         }, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium,
                         maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${b.info.operator ?: ""} · ${b.info.fleetNo}", style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                IconButton(onClick = close) { Icon(Icons.Filled.Close, "Close", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Spacer(Modifier.height(8.dp))
            VehicleInfo(b.v, big = true)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (b.route != null) {
                    val (text, c) = punctuality(trip?.delay)
                    Chip(if (trip?.delay == null) "Timing unknown" else text, if (trip?.delay == null) Pal.Ink else c)
                }
                Chip(b.v.speedKmh?.let { if (it < 2) "Stopped" else "${it.toInt()} km/h" } ?: "Speed unknown", Pal.AtBlue)
                occupancyText(b.v.occupancy)?.let { Chip(it, Pal.Ink) }
                Chip("seen ${ago(b.v.timestamp, now)}", Pal.Ink)
            }
            if (ahead.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("Next stops", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ahead.take(3).forEach { a ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onStop(a.stop.id) }.padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(9.dp).background(color, CircleShape))
                        Spacer(Modifier.width(10.dp))
                        Text(a.stop.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                             style = MaterialTheme.typography.bodyMedium)
                        Text(Nz.time(a.eta), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(10.dp))
                        Text(countdown(a.eta - now), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                             modifier = Modifier.width(56.dp), textAlign = TextAlign.End)
                    }
                }
                if (ahead.size > 3) Text("…then ${ahead.size - 3} more to ${ahead.last().stop.name}", style = MaterialTheme.typography.bodySmall,
                                         color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (follow) Button(onClick = onFollow) { Text("Following") } else FilledTonalButton(onClick = onFollow) { Text("Follow") }
                OutlinedButton(onClick = onZoom) { Text("Zoom to it") }
            }
        }
    }
}
