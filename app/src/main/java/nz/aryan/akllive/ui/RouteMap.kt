package nz.aryan.akllive.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nz.aryan.akllive.data.BusDeparture
import nz.aryan.akllive.data.Nz
import nz.aryan.akllive.data.RouteData
import nz.aryan.akllive.data.StopBoard

val DirColors = listOf(Color(0xFF3D8BFF), Color(0xFF00C2B8))

/** Everything the route map draws, built from the live boards. */
class BusMapData(
    val lines: List<MapLine>,
    val stops: List<MapStop>,
    val markers: List<MapMarker>,
    val fit: List<Pair<Double, Double>>,
    val buses: List<Pair<Int, BusDeparture>>,       // direction index, departure
)

fun busMapData(boards: List<StopBoard>, now: Long, place: String): BusMapData {
    val routes = boards.mapNotNull { b -> RouteData.ROUTES[b.code]?.let { b to it.second } }
    val lines = routes.mapIndexed { i, (_, stops) ->
        MapLine(listOf(stops.map { it.lat to it.lon }), DirColors[i % 2], 4.5f, if (i == 0) -2.5f else 2.5f)
    }
    val stops = ArrayList<MapStop>()
    routes.forEachIndexed { i, (b, rs) ->
        rs.forEach { if (it.code != b.code) stops += MapStop(it.lat, it.lon, DirColors[i % 2]) }
    }
    routes.forEachIndexed { i, (b, rs) ->
        rs.firstOrNull { it.code == b.code }?.let {
            stops += MapStop(it.lat, it.lon, DirColors[i % 2], big = true, label = if (i == 0) "You · $place" else null)
        }
    }
    val buses = routes.flatMapIndexed { i, (b, _) ->
        b.departures.filter { it.vehicle != null && !it.cancelled }.map { i to it }
    }
    val markers = buses.map { (i, d) ->
        val v = d.vehicle!!
        MapMarker(d.tripId, v.lat, v.lon, v.bearing, DirColors[i % 2], train = false, tag = countdown(d.expected - now))
    }
    val fit = routes.flatMap { (_, rs) -> rs.map { it.lat to it.lon } }
    return BusMapData(lines, stops, markers, fit, buses)
}

/** The live route map in the bus list: a look at the real streets, tap to explore. */
@Composable
fun RouteMapCard(boards: List<StopBoard>, now: Long, place: String, basemap: Basemap, linzKey: String,
                 frameT: State<Float>, onOpen: () -> Unit) {
    if (boards.none { RouteData.ROUTES.containsKey(it.code) }) return
    val data = busMapData(boards, now, place)
    val route = boards.firstOrNull { it.route.isNotEmpty() }?.route ?: "Route"
    Card(shape = RoundedCornerShape(24.dp), elevation = CardDefaults.cardElevation(3.dp),
         colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("$route live map", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(if (data.buses.isEmpty()) "No buses heading your way are on the road yet"
                         else "${data.buses.size} bus${if (data.buses.size == 1) "" else "es"} on the way to you",
                         style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box(Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onOpen)) {
                    Chip("Open map", MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().height(320.dp).clip(RoundedCornerShape(18.dp))) {
                LiveMap(basemap, linzKey, data.lines, data.stops, data.markers, data.fit, frameT,
                        Modifier.fillMaxSize(), interactive = false)
                // on top of the map, so a drag scrolls the list and a tap opens the map
                Box(Modifier.fillMaxSize().clickable(onClick = onOpen))
            }
            Spacer(Modifier.height(10.dp))
            DirectionLegend(boards)
        }
    }
}

@Composable
private fun DirectionLegend(boards: List<StopBoard>, color: Color = MaterialTheme.colorScheme.onSurface) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        boards.filter { RouteData.ROUTES.containsKey(it.code) }.forEachIndexed { i, b ->
            Box(Modifier.size(10.dp).background(DirColors[i % 2], CircleShape))
            Spacer(Modifier.width(5.dp))
            Text(b.towards, style = MaterialTheme.typography.labelMedium, color = color)
            Spacer(Modifier.width(14.dp))
        }
    }
}

/** The route map, full screen: pan and zoom over the real streets, tap a bus for what it is. */
@Composable
fun BusMapScreen(boards: List<StopBoard>, now: Long, place: String, basemap: Basemap, linzKey: String,
                 frameT: State<Float>, setBasemap: (Basemap) -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val data = busMapData(boards, now, place)
    val sel = data.buses.firstOrNull { it.second.tripId == selected }
    val route = boards.firstOrNull { it.route.isNotEmpty() }?.route ?: "Route"
    Box(Modifier.fillMaxSize()) {
        LiveMap(basemap, linzKey, data.lines, data.stops, data.markers, data.fit, frameT, Modifier.fillMaxSize(),
                selected = selected, onMarker = { selected = it }, onBackground = { selected = null })
        // top bar over a soft shade
        Box(Modifier.fillMaxWidth().height(150.dp).background(
            Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent))))
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
            }
            Column(Modifier.weight(1f)) {
                Text("$route live", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text("${data.buses.size} on the way · ${Nz.time(now)}", color = Color.White.copy(alpha = 0.8f),
                     fontSize = 12.sp)
            }
            BasemapToggle(basemap, setBasemap)
        }
        Column(Modifier.align(Alignment.BottomCenter).padding(12.dp)) {
            AnimatedVisibility(sel != null, enter = slideInVertically { it } + fadeIn(),
                               exit = slideOutVertically { it } + fadeOut()) {
                sel?.let { (i, d) ->
                    val b = boards.filter { RouteData.ROUTES.containsKey(it.code) }.getOrNull(i)
                    SelectedBusCard(d, b, DirColors[i % 2], now) { selected = null }
                }
            }
            if (sel == null) {
                Surface(shape = RoundedCornerShape(50), color = Color.Black.copy(alpha = 0.55f)) {
                    Box(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                        DirectionLegend(boards, Color.White)
                    }
                }
            }
        }
    }
}

/** Satellite / Map switch, for over a map. */
@Composable
fun BasemapToggle(basemap: Basemap, set: (Basemap) -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.45f))
            .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(50)).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for ((b, label) in listOf(Basemap.Satellite to "Satellite", Basemap.Streets to "Map")) {
            val on = b == basemap
            Box(Modifier.clip(RoundedCornerShape(50)).background(if (on) Color.White else Color.Transparent)
                    .clickable { set(b) }.padding(horizontal = 12.dp, vertical = 6.dp)) {
                Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (on) Pal.Navy else Color.White)
            }
        }
    }
}

@Composable
private fun SelectedBusCard(d: BusDeparture, b: StopBoard?, color: Color, now: Long, close: () -> Unit) {
    Surface(Modifier.fillMaxWidth().shadow(12.dp, RoundedCornerShape(22.dp)), shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.clickable(onClick = close).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.background(color, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 3.dp)) {
                    Text(d.route, color = Color.White, fontWeight = FontWeight.Black, fontSize = 17.sp)
                }
                Spacer(Modifier.width(10.dp))
                Text("to ${b?.headsign ?: "…"}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium,
                     modifier = Modifier.weight(1f))
                Text(if (d.cancelled) "Cancelled" else countdown(d.expected - now), fontWeight = FontWeight.Black,
                     fontSize = 22.sp)
            }
            Spacer(Modifier.height(10.dp))
            d.vehicle?.let { VehicleInfo(it, big = true) }
            Spacer(Modifier.height(8.dp))
            Text(busFacts(d, b).joinToString("  ·  "), style = MaterialTheme.typography.bodyMedium,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
