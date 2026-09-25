package nz.aryan.akllive.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CatchingPokemon
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nz.aryan.akllive.AppViewModel
import nz.aryan.akllive.data.BusModel
import nz.aryan.akllive.data.Fleet
import nz.aryan.akllive.data.Nz
import nz.aryan.akllive.Spotted

private val TIPS = listOf(
    "Shake your phone to refresh everything.",
    "Long-press any map to drop a pin, then get directions there.",
    "Star a stop and it shows up on Home, in your app-icon shortcuts and in search.",
    "Tap the bus scene on Home. Go on.",
    "Track a bus and the countdown lives in your notifications, even with the app closed.",
    "Pull down the quick settings and add the Next bus tile.",
    "The home screen widget shows your next bus each way.",
    "Every bus model that pulls up at your stops goes in your fleet dex.",
    "Search takes fleet numbers too: try the number on the back of a bus.",
    "Settings has six colour themes, each named after something Auckland.",
    "Pick \"Arrive by\" in Directions to find out when you really need to leave.",
)

private class Entry(val route: String, val label: String, val sub: String, val icon: ImageVector)

/** Everything else: search, alerts, routes, the fleet, settings. */
@Composable
fun MoreScreen(vm: AppViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val alerts by vm.alerts.collectAsStateWithLifecycle()
    val tt by vm.timetable.collectAsStateWithLifecycle()
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val view = LocalView.current
    val haptics = LocalHaptics.current
    val mine = remember(alerts, s) { vm.myAlerts(alerts, s).size }
    var tip by rememberSaveable { mutableIntStateOf((Nz.nowSec() / 3600 % TIPS.size).toInt()) }
    val entries = listOf(
        Entry("search", "Search", "Stops, places, buses", Icons.Rounded.Search),
        Entry("alerts", "Alerts", if (mine > 0) "$mine on your routes" else "${alerts.count { it.active }} across Auckland", Icons.Rounded.Warning),
        Entry("routes", "Routes", "Every route today", Icons.Rounded.Route),
        Entry("fleet", "Fleet", "${Fleet.models.size} bus models", AklIcons.Fleet),
        Entry("dex", "Fleet dex", "${s.spotted.size} of ${Fleet.models.size} spotted", Icons.Rounded.CatchingPokemon),
        Entry("busmap", "${s.route.ifEmpty { "Route" }} map", "Your buses, live", Icons.Rounded.Map),
        Entry("settings", "Settings", "Stops, theme, keys", Icons.Rounded.Settings),
    )
    LazyVerticalGrid(GridCells.Fixed(2), Modifier.fillMaxSize().statusBarsPadding(),
                     contentPadding = PaddingValues(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
                     verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item(span = { GridItemSpan(2) }) {
            Column(Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp)) {
                Text("More", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
                Text("AKL Live · Tāmaki Makaurau on the move", style = MaterialTheme.typography.bodyMedium,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item(span = { GridItemSpan(2) }) {
            Card(onClick = { haptics.tick(view); tip = (tip + 1) % TIPS.size },
                 colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Lightbulb, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Did you know?", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onTertiaryContainer)
                        Text(TIPS[tip], style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
                    }
                }
            }
        }
        items(entries, key = { it.route }) { e ->
            ElevatedCard(onClick = { haptics.tick(view); nav.go(e.route) }) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    if (e.route == "alerts" && mine > 0) {
                        BadgedBox(badge = { Badge { Text("$mine") } }) { Icon(e.icon, null, tint = MaterialTheme.colorScheme.primary) }
                    } else Icon(e.icon, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(10.dp))
                    Text(e.label, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Text(e.sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                         maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        item(key = "share") {
            ElevatedCard(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,
                    "AKL Live: Auckland's buses, trains and ferries, live. https://github.com/intelarc/akl-live-android/releases/latest")
                try { ctx.startActivity(Intent.createChooser(send, "Share AKL Live")) } catch (_: Exception) { }
            }) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Icon(Icons.Rounded.Share, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(10.dp))
                    Text("Share the app", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Text("Tell a mate", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item(span = { GridItemSpan(2) }) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CloudDownload, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("AT's timetable", style = MaterialTheme.typography.titleSmall)
                        Text(when {
                            tt.ready -> "%,d stops · %,d trips · %d routes today".format(tt.stops, tt.trips, tt.routes)
                            tt.loading -> tt.text
                            else -> tt.error ?: "Loads once you've added your AT key"
                        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (tt.loading) LinearProgressIndicator(progress = { tt.pct / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                    }
                }
            }
        }
    }
}

// ======================= the fleet dex =======================

private fun rank(n: Int, total: Int): String {
    val f = if (total == 0) 0f else n / total.toFloat()
    return when {
        n == 0 -> "Fresh at the kerb"
        f < 0.25f -> "Rookie spotter"
        f < 0.5f -> "Bus botherer"
        f < 0.75f -> "Kerbside connoisseur"
        f < 1f -> "Depot legend"
        else -> "Ultimate spotter. Every model, caught 🏆"
    }
}

/** Every bus model: the ones you've seen pull up at your stops in colour, the rest as mysteries. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DexScreen(vm: AppViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val openModel = LocalOpenModel.current
    val models = remember(s.spotted) {
        Fleet.models.sortedWith(compareByDescending<BusModel> { it.id in s.spotted }.thenBy { it.name })
    }
    val got = s.spotted.keys.count { id -> Fleet.models.any { it.id == id } }
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    Scaffold(contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
             topBar = {
                 BackBar("Fleet dex", "$got of ${Fleet.models.size} spotted") {
                     Box {
                         IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                         DropdownMenu(menu, { menu = false }) {
                             DropdownMenuItem(text = { Text("Start over") }, onClick = { menu = false; confirm = true })
                         }
                     }
                 }
             }) { pad ->
        LazyVerticalGrid(GridCells.Adaptive(160.dp), Modifier.fillMaxSize().padding(pad),
                         contentPadding = PaddingValues(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
                         verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(rank(got, Fleet.models.size), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black,
                             color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text("A model counts once one pulls up at one of your Home stops while the app's open.",
                             style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(progress = { if (Fleet.models.isEmpty()) 0f else got / Fleet.models.size.toFloat() },
                                                modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            items(models, key = { it.id }) { m -> DexCard(m, s.spotted[m.id]) { if (m.id in s.spotted) openModel(m.id) else
                vm.toast.tryEmit("Not spotted yet. Keep an eye out for a ${if (m.doubleDeck) "double-decker" else if (m.electric) "quiet electric one" else "new face"} at your stop") } }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Start the dex over?") },
        text = { Text("Every model you've spotted gets forgotten.") },
        confirmButton = { TextButton(onClick = { confirm = false; vm.update { it.copy(spotted = emptyMap()) } }) { Text("Start over") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Keep them") } },
    )
}

@Composable
private fun DexCard(m: BusModel, seen: Spotted?, onClick: () -> Unit) {
    Card(onClick = onClick, colors = CardDefaults.cardColors(
        containerColor = if (seen != null) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().height(70.dp).background(
                if (seen != null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center) {
                BusGlyph(m, Modifier.size(width = 90.dp, height = 50.dp).alpha(if (seen != null) 1f else 0.18f))
                if (seen == null) Text("?", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black,
                                       color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            Text(if (seen != null) m.short else "???", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                 maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            Text(if (seen != null) m.maker else m.kind, style = MaterialTheme.typography.labelSmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (seen != null) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).background(Pal.Live, CircleShape))
                    Spacer(Modifier.width(4.dp))
                    Text("${seen.count}× · last ${seen.fleetNo}", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
                Text("since " + Nz.at(seen.first).let { "${it.dayOfMonth} ${it.month.name.take(3).lowercase().replaceFirstChar { c -> c.uppercase() }}" },
                     style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ======================= wrappers for the older full screens =======================

/** Every bus model on the road, from anywhere in the app. */
@Composable
fun FleetRoute(vm: AppViewModel) {
    val open by vm.fleetModel.collectAsStateWithLifecycle()
    DisposableEffect(Unit) { onDispose { vm.fleetModel.value = null } }
    if (open != null) {
        Box(Modifier.fillMaxSize().statusBarsPadding()) { FleetScreen(vm, Modifier) }
    } else {
        Column(Modifier.fillMaxSize()) {
            BackBar("Fleet", "Every bus model in Auckland")
            FleetScreen(vm, Modifier.weight(1f))
        }
    }
}

/** Your route's live map, full screen. */
@Composable
fun BusMapRoute(vm: AppViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val boards by vm.boards.collectAsStateWithLifecycle()
    val basemap by vm.basemap.collectAsStateWithLifecycle()
    val linzKey by vm.linzKey.collectAsStateWithLifecycle()
    val now by rememberNow()
    val frameT = rememberFrameTime()
    BusMapScreen(boards, now, s.place, basemap, linzKey, frameT, vm::setBasemap, LocalBack.current)
}
