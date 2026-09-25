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

    val shown = remember(state.buses, filter, query) { state.buses.filter { it.passes(filter) && it.matches(query) } }
    val sel = state.buses.firstOrNull { it.v.id == selected }
    val crowd = remember(shown, selected) { shown.filter { it.v.id != selected }.map { it.dot() } }
    LaunchedEffect(sel?.v?.tripId) { vm.selectBus(sel?.v?.tripId) }

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
        LiveMap(basemap, linzKey, emptyList(), emptyList(), listOfNotNull(sel?.marker()), fit, frameT,
                Modifier.fillMaxSize(), selected = selected, topInset = 150f, crowd = crowd,
                onCrowd = { selected = it; focusManager.clearFocus() },
                onMarker = { selected = it }, onBackground = { selected = null; focusManager.clearFocus() })

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

        Column(Modifier.align(Alignment.BottomCenter).padding(12.dp)) {
            AnimatedVisibility(sel != null, enter = slideInVertically { it } + fadeIn(),
                               exit = slideOutVertically { it } + fadeOut()) {
                sel?.let { LiveBusCard(it, trip?.takeIf { t -> t.tripId == it.v.tripId }, now) { selected = null } }
            }
            if (sel == null && !state.loading) {
                val what = if (query.isNotBlank() || filter != "all") "%,d shown".format(shown.size) else "Tap a bus to see what it is"
                Surface(shape = RoundedCornerShape(50), color = Color.Black.copy(alpha = 0.6f)) {
                    Text(what, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                         modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                }
            }
        }
    }
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

/** The tapped bus: its route and destination, what it is, and how it's going. */
@Composable
private fun LiveBusCard(b: LiveBus, trip: BusTrip?, now: Long, close: () -> Unit) {
    val color = operatorColor(b.info.code)
    Surface(Modifier.fillMaxWidth().shadow(12.dp, RoundedCornerShape(22.dp)), shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.background(color, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 3.dp)) {
                    Text(b.route ?: "—", color = Color.White, fontWeight = FontWeight.Black, fontSize = 17.sp)
                }
                Spacer(Modifier.width(10.dp))
                Text(when {
                        b.route == null -> "Not in service"
                        trip?.headsign != null -> "to ${trip.headsign}"
                        else -> "…"
                     }, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium,
                     maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Icon(Icons.Filled.Close, "Close", Modifier.size(22.dp).clickable(onClick = close),
                     tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(10.dp))
            VehicleInfo(b.v, big = true)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (b.route != null) {
                    val (text, c) = punctuality(trip?.delay)
                    Chip(if (trip?.delay == null) "Timing unknown" else text, if (trip?.delay == null) Pal.Ink else c)
                    Spacer(Modifier.width(8.dp))
                }
                Text(listOfNotNull(
                        b.v.speedKmh?.let { if (it < 2) "Stopped" else "${it.toInt()} km/h" },
                        occupancyText(b.v.occupancy),
                        "seen ${(now - b.v.timestamp).coerceAtLeast(0)}s ago",
                     ).joinToString("  ·  "), style = MaterialTheme.typography.bodyMedium,
                     color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
