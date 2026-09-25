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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.material.icons.rounded.ZoomOutMap
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nz.aryan.akllive.gtfs.Pt
import nz.aryan.akllive.gtfs.Rail
import nz.aryan.akllive.gtfs.RailDrawing
import nz.aryan.akllive.gtfs.RailGeo
import nz.aryan.akllive.gtfs.RailSeg
import nz.aryan.akllive.gtfs.RailStation
import nz.aryan.akllive.gtfs.Timetable
import nz.aryan.akllive.gtfs.metres
import nz.aryan.akllive.gtfs.railTrack
import kotlin.math.atan2
import kotlin.math.cos
import nz.aryan.akllive.AppViewModel
import nz.aryan.akllive.data.MapData
import nz.aryan.akllive.data.Nz
import nz.aryan.akllive.data.StationDeparture
import nz.aryan.akllive.data.Train
import nz.aryan.akllive.data.TrainState
import nz.aryan.akllive.data.TripDetail
import nz.aryan.akllive.data.occupancyText

/** The network's stations and segments, plus Te Huia's own stops, for the rail layout. */
object TrainNet {
    val stations: List<RailStation> =
        MapData.STATIONS.map { RailStation(it.name, it.lat, it.lon, it.lines, it.priority) } + Rail.HUIA_STOPS
    val segs: List<RailSeg> = MapData.SEGS.map { RailSeg(it.line, it.from, it.to) }
    /** straight lines between stations, until the timetable's in */
    val straight: RailGeo by lazy { Rail.build(stations, segs, null) }
    @Volatile private var real: Pair<String, RailGeo>? = null

    /** The lines along their real tracks, cut from today's timetable (once a day). */
    fun geo(): RailGeo {
        val net = Timetable.today ?: return straight
        real?.takeIf { it.first == net.date }?.let { return it.second }
        val g = try {
            Rail.build(stations, segs, net.railTrack(Rail.asks(stations, segs, MapData.LINE_IDS.toList())))
        } catch (e: Exception) {
            straight
        }
        real = net.date to g
        return g
    }

    /** Every AT station on the lines showing, to frame the network. */
    fun fit(filter: Set<Int>): List<Pair<Double, Double>> =
        MapData.STATIONS.filter { st -> filter.any { st.lines and (1 shl it) != 0 } }.map { it.lat to it.lon }
}

/** Te Huia's colour: AT's feed says black, which needs lifting in the dark. */
fun huiaColor(dark: Boolean) = if (dark) Color(0xFFB9C2CF) else Color(0xFF2B3140)

private val KX = cos(36.9 * Math.PI / 180)
private fun bearing(a: Pt, b: Pt) = ((Math.toDegrees(atan2((b.x - a.x) * KX, b.y - a.y)) + 360) % 360).toFloat()

/**
 * Trains: Auckland's rail network drawn along the real tracks in AT's line
 * colours (lines side by side where they share rails, Te Huia on to Hamilton),
 * with every train live on its own line where its GPS puts it. Over a quiet
 * diagram map, satellite photos or a street map; tap a train or a station.
 */
@Composable
fun TrainScreen(vm: AppViewModel, modifier: Modifier) {
    val state by vm.trains.collectAsStateWithLifecycle()
    val trip by vm.trip.collectAsStateWithLifecycle()
    val station by vm.station.collectAsStateWithLifecycle()
    val s by vm.settings.collectAsStateWithLifecycle()
    val tt by vm.timetable.collectAsStateWithLifecycle()
    val linzKey by vm.linzKey.collectAsStateWithLifecycle()
    val now by rememberNow()
    val frameT = rememberFrameTime()
    val dark = LocalDark.current
    var selTrain by remember { mutableStateOf<String?>(null) }
    var selStation by remember { mutableStateOf<Int?>(null) }
    var filter by rememberSaveable { mutableStateOf(listOf(0, 1, 2)) }
    val shown = filter.toSet()
    val mode = s.trainView

    // straight lines between stations until the timetable has loaded, then the real track
    val geo by produceState(TrainNet.straight, tt.ready) {
        if (tt.ready) value = withContext(Dispatchers.Default) { TrainNet.geo() }
    }
    val drawing by produceState<RailDrawing?>(null, geo, filter) {
        value = withContext(Dispatchers.Default) { Rail.layout(TrainNet.stations, geo, shown) }
    }
    var fitKey by remember { mutableIntStateOf(0) }
    // framed when the screen opens and when "whole network" is tapped, not on every filter change
    val fit = remember(fitKey) { TrainNet.fit(shown) }
    val headings = remember { HashMap<String, Pair<Pt, Float?>>() }
    var titleTaps by remember { mutableIntStateOf(0) }

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

    val d = drawing
    val lines = remember(d, dark) {
        d?.pieces?.map { p ->
            MapLine(listOf(p.coords.map { it.y to it.x }), if (p.line == Rail.HUIA) huiaColor(dark) else Pal.line(p.line),
                    p.width.toFloat(), p.offset.toFloat(), z = true)
        } ?: emptyList()
    }
    val stopsAndLinks = remember(d, geo, selStation, dark) {
        val stops = ArrayList<MapStop>()
        val links = ArrayList<MapLine>()
        if (d != null) TrainNet.stations.forEachIndexed { i, st ->
            val on = (0..2).filter { it in shown && st.lines and (1 shl it) != 0 }
            if (!st.extra && on.isEmpty()) return@forEachIndexed
            val pts = d.markers[i].ifEmpty { listOf(Pt(st.lon, st.lat)) }
            val r = when {
                st.extra -> 1.1f
                geo.junction[i] -> 1.6f
                on.size > 1 -> 1.35f
                geo.terminus[i] -> 1.2f
                else -> 1f
            }
            val color = when {
                pts.size > 1 || (!st.extra && (on.size > 1 || i == selStation)) -> Pal.Navy
                st.extra -> huiaColor(dark)
                else -> Pal.line(on[0])
            }
            if (pts.size > 1) links += MapLine(listOf(pts.map { it.y to it.x }), color)
            pts.forEachIndexed { k, p ->
                stops += MapStop(p.y, p.x, color, big = i == selStation, label = if (k == 0) st.name else null,
                                 id = if (st.extra) null else i.toString(), r = r, rank = st.priority)
            }
        }
        stops to links
    }
    // every train where its GPS says, put on its own line's track, pointing the way it's going
    val markers = remember(state.updated, d, filter, mode) {
        val seen = HashSet<String>()
        state.trains.filter { it.line in shown }.map { t ->
            val v = t.vehicle
            val at = d?.snap(t.line, v.lon, v.lat) ?: Pt(v.lon, v.lat)
            seen += v.id
            var b = v.bearing?.takeIf { it != 0f }            // 0 comes through when a train doesn't know
            val h = headings[v.id]
            if (b == null && h != null) b = if (metres(h.first, at) > 25) bearing(h.first, at) else h.second
            headings[v.id] = if (h == null || metres(h.first, at) > 25) at to b else h.first to b
            val late = t.delay ?: 0
            MapMarker(v.id, at.y, at.x, b, Pal.line(t.line), train = true,
                      tag = if (mode == Basemap.Diagram) null else MapData.LINE_IDS[t.line],
                      alert = if (late >= 300) Pal.Late else if (late >= 120) Pal.Warn else null)
        }.also { headings.keys.retainAll(seen) }
    }

    val map: @Composable (Modifier) -> Unit = { m ->
        Box(m.clip(RoundedCornerShape(bottomStart = 22.dp, bottomEnd = 22.dp))) {
            LiveMap(mode, linzKey, lines, stopsAndLinks.first, markers, fit, frameT, Modifier.fillMaxSize(),
                    selected = selTrain, topInset = 70f, fitKey = fitKey, links = stopsAndLinks.second,
                    onMarker = { id -> state.trains.firstOrNull { it.vehicle.id == id }?.let(pickTrain) },
                    onStop = { id -> id.toIntOrNull()?.let(pickStation) }, onBackground = clear)
            MapHeader(state, now, shown, onTitle = {
                titleTaps++
                if (titleTaps % 5 == 0) vm.toast.tryEmit(if (titleTaps >= 15) "All aboard the tap train 🚂🚃🚃🚃" else "Choo choo! 🚂")
            }) { li ->
                filter = (if (li in shown && shown.size > 1) shown - li else shown + li).sorted()
            }
            if (!tt.ready && tt.loading) {
                Surface(Modifier.align(Alignment.BottomCenter).padding(bottom = 56.dp), shape = RoundedCornerShape(50),
                        color = Color.Black.copy(alpha = 0.6f)) {
                    Text("Drawing the real tracks once the timetable's in… ${tt.pct}%", color = Color.White, fontSize = 12.sp,
                         modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                }
            }
            Box(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
                FilledTonalIconButton(onClick = { fitKey++ }) { Icon(Icons.Rounded.ZoomOutMap, "Whole network") }
            }
            TrainViewToggle(mode, Modifier.align(Alignment.BottomEnd).padding(10.dp)) { b -> vm.update { it.copy(trainView = b) } }
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

/** Diagram / Satellite / Map, floating over the bottom-right of the map. */
@Composable
private fun TrainViewToggle(mode: Basemap, modifier: Modifier, set: (Basemap) -> Unit) {
    Row(modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.55f))
            .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(50)).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for ((b, label) in listOf(Basemap.Diagram to "Diagram", Basemap.Satellite to "Satellite", Basemap.Streets to "Map")) {
            val on = mode == b
            Box(Modifier.clip(RoundedCornerShape(50)).background(if (on) Color.White else Color.Transparent)
                    .clickable { set(b) }.padding(horizontal = 11.dp, vertical = 6.dp)) {
                Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (on) Pal.Navy else Color.White)
            }
        }
    }
}

@Composable
private fun MapHeader(state: TrainState, now: Long, filter: Set<Int>, onTitle: () -> Unit = {}, toggle: (Int) -> Unit) {
    val counts = state.counts()
    Column(Modifier.fillMaxWidth().background(Pal.AtBlue).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Ngā Tereina", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp,
                 modifier = Modifier.clickable(interactionSource = null, indication = null, onClick = onTitle))
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
         "departures from every platform. The lines follow the real tracks, side by side where they share rails, " +
         "with every train where its GPS puts it, pointing the way it's heading. Te Huia runs on to Hamilton. " +
         "Satellite and Map show it all over aerial photos or a street map.",
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
                // every Auckland train is a CAF-built AM class electric unit
                Text("${MapData.LINE_NAMES[t.line]} line · ${v.label.ifEmpty { "train ${v.id}" }} · AM class EMU",
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
