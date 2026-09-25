package nz.aryan.akllive.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Business
import androidx.compose.material.icons.rounded.CatchingPokemon
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nz.aryan.akllive.AppViewModel
import nz.aryan.akllive.FavStop
import nz.aryan.akllive.PlaceKind
import nz.aryan.akllive.Settings
import nz.aryan.akllive.data.Fleet
import nz.aryan.akllive.data.Nz
import nz.aryan.akllive.data.StopBoard
import nz.aryan.akllive.data.StopDeparture
import nz.aryan.akllive.data.Weather
import nz.aryan.akllive.gtfs.StopHit
import nz.aryan.akllive.gtfs.Timetable
import nz.aryan.akllive.gtfs.nearby

/** Polls [fetch] every [everyMs] while the app is on screen. */
@Composable
fun <T> rememberPolled(key: Any?, everyMs: Long, fetch: suspend () -> T): State<T?> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return produceState<T?>(null, key) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                try { value = fetch() } catch (_: Exception) { }
                delay(everyMs)
            }
        }
    }
}

/** Kia ora, in the right words for the time of day. */
fun greeting(kiwi: Boolean): String {
    val h = Nz.now().hour
    return if (kiwi) when (h) {
        in 5..11 -> "Mōrena"
        in 12..16 -> "Kia ora"
        in 17..20 -> "Ahiahi mārie"
        else -> "Pō mārie"
    } else when (h) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        in 17..20 -> "Good evening"
        else -> "Night owl"
    }
}

/** A line about how things are looking, from the live data. */
fun vibe(boards: List<StopBoard>, w: Weather?, s: Settings, now: Long): String {
    val next = boards.mapNotNull { b -> b.departures.firstOrNull { it.expected >= now - 30 } }.minByOrNull { it.expected }
    val model = next?.vehicle?.busModel()
    val late = next?.let { Math.round(it.delay / 60f) } ?: 0
    val k = s.kiwi
    return when {
        s.apiKey.isBlank() -> "Add your free AT key to get going"
        next == null -> if (k) "Quiet out there. No buses for a bit" else "No buses coming soon"
        next.cancelled -> if (k) "Your ${next.route} got cancelled. Gutted." else "Your next ${next.route} is cancelled"
        late >= 5 -> if (k) "The ${next.route}'s running $late min late, eh" else "The ${next.route} is $late min late"
        w != null && w.rain >= 2 -> if (k) "Bit wet out there. Grab a brolly ☔" else "It's raining"
        model?.doubleDeck == true -> "Double-decker incoming: front seat upstairs? 🚌"
        model?.electric == true -> "Your next ${next.route} is electric ⚡"
        w != null && w.rainSoon -> if (k) "Rain's on the way. Brolly time? ☂" else "Rain later"
        Nz.now().hour !in 6..21 -> if (k) "Night owl mode 🦉" else "Late services"
        else -> if (k) "Sweet as, the ${next.route} is on time" else "The ${next.route} is on time"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: AppViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val boards by vm.boards.collectAsStateWithLifecycle()
    val error by vm.busError.collectAsStateWithLifecycle()
    val pulling by vm.pulling.collectAsStateWithLifecycle()
    val weather by vm.weather.collectAsStateWithLifecycle()
    val alerts by vm.alerts.collectAsStateWithLifecycle()
    val tt by vm.timetable.collectAsStateWithLifecycle()
    val basemap by vm.basemap.collectAsStateWithLifecycle()
    val now by rememberNow()
    val frameT = rememberFrameTime()
    val nav = LocalNav.current
    val mine = remember(alerts, s) { vm.myAlerts(alerts, s) }
    val notify = rememberNotifyPermission()

    PullToRefreshBox(pulling, onRefresh = vm::pullRefresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { HomeHeader(vm, s, boards, weather, now) }
            item { SearchPill { nav.go("search") } }
            item { QuickActions(vm, s) }
            if (s.apiKey.isBlank()) item { NoKeyCard { nav.go("settings") } }
            if (mine.isNotEmpty()) item { AlertBanner(mine.size, mine.first().header) { nav.go("alerts") } }
            if (tt.loading) item { TimetableCard(tt.text, tt.pct) }
            if (error != null && boards.all { it.departures.isEmpty() }) item {
                Text(error!!, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium,
                     modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                         .background(MaterialTheme.colorScheme.errorContainer).padding(14.dp))
            }
            item { NextUp(boards, s, now) }
            itemsIndexed(boards, key = { _, b -> "board-" + b.code }) { i, b ->
                BusCard(b, now, frameT, i, weather,
                        onTrack = { d -> notify { vm.track(b, d) } },
                        onOpen = { nav.go("stop/${android.net.Uri.encode(b.code)}") })
            }
            weather?.takeIf { it.hourly.isNotEmpty() }?.let { w -> item { WeatherStrip(w) } }
            if (s.favs.isNotEmpty()) {
                item { SectionHeader("Favourite stops", action = "Find more") { nav.go("search") } }
                items(s.favs, key = { "fav-" + it.id }) { f -> FavCard(vm, f, now) }
            }
            item { NearMe(vm) }
            item {
                RouteMapCard(boards, now, s.place, basemap, s.linzKey, frameT) { nav.go("busmap") }
            }
            item { DexTeaser(s.spotted.size) { nav.go("dex") } }
            item {
                Text("Live from Auckland Transport. Pull down or shake to refresh. Buses enter the scene 20 minutes out; tap one to say hi.",
                     style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                     modifier = Modifier.padding(horizontal = 4.dp))
            }
        }
    }
}

@Composable
private fun HomeHeader(vm: AppViewModel, s: Settings, boards: List<StopBoard>, w: Weather?, now: Long) {
    val nav = LocalNav.current
    Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(top = 14.dp, start = 4.dp),
        verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text("${greeting(s.kiwi)},", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text(s.place, style = MaterialTheme.typography.headlineLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(vibe(boards, w, s, now), style = MaterialTheme.typography.bodyMedium,
                 color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(Nz.time(now), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
            w?.let {
                Text("${Math.round(it.tempC)}° ${Weather.emoji(it.code, it.cloudCover, isNight(Nz.hour()))}",
                     style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(4.dp))
            FilledTonalIconButton(onClick = { nav.go("settings") }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Rounded.Settings, "Settings", Modifier.size(20.dp))
            }
        }
    }
}

@Composable
fun SearchPill(onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth().height(54.dp)) {
        Row(Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Text("Stops, places, routes, buses…", color = MaterialTheme.colorScheme.onSurfaceVariant,
                 style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun QuickActions(vm: AppViewModel, s: Settings) {
    val nav = LocalNav.current
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val home = s.home
        AssistChip(onClick = {
                       if (home != null) { vm.setPlan({ it.copy(from = null, to = home, time = null, arriveBy = false) }); nav.go("plan") }
                       else nav.go("settings")
                   },
                   label = { Text(if (home != null) "Take me home" else "Set home") },
                   leadingIcon = { Icon(Icons.Rounded.Home, null, Modifier.size(AssistChipDefaults.IconSize)) })
        val work = s.work
        if (work != null) AssistChip(onClick = { vm.setPlan({ it.copy(from = null, to = work, time = null, arriveBy = false) }); nav.go("plan") },
                                     label = { Text("To work") },
                                     leadingIcon = { Icon(Icons.Rounded.Business, null, Modifier.size(AssistChipDefaults.IconSize)) })
        AssistChip(onClick = { nav.go("live") }, label = { Text("Every bus") },
                   leadingIcon = { Icon(Icons.Rounded.Map, null, Modifier.size(AssistChipDefaults.IconSize)) })
        AssistChip(onClick = { nav.go("alerts") }, label = { Text("Alerts") },
                   leadingIcon = { Icon(Icons.Rounded.Warning, null, Modifier.size(AssistChipDefaults.IconSize)) })
    }
}

@Composable
private fun NoKeyCard(onClick: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Key, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Add your AT API key", style = MaterialTheme.typography.titleMedium,
                     color = MaterialTheme.colorScheme.onTertiaryContainer)
                Text("It's free from dev-portal.at.govt.nz. Everything live needs one.",
                     style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
            Button(onClick = onClick) { Text("Add") }
        }
    }
}

@Composable
private fun AlertBanner(n: Int, first: String, onClick: () -> Unit) {
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (n == 1) "1 alert on your route" else "$n alerts on your route", style = MaterialTheme.typography.titleSmall,
                     color = MaterialTheme.colorScheme.onErrorContainer)
                Text(first, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer,
                     maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

@Composable
fun TimetableCard(text: String, pct: Int) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(14.dp)) {
            Text("Getting AT's timetable ready", style = MaterialTheme.typography.titleSmall,
                 color = MaterialTheme.colorScheme.onSecondaryContainer)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(progress = { pct / 100f }, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** The next bus each way, and when to leave for it. */
@Composable
private fun NextUp(boards: List<StopBoard>, s: Settings, now: Long) {
    val rows = boards.mapIndexedNotNull { i, b ->
        val soon = b.departures.filter { !it.cancelled && it.expected >= now - 30 }
        soon.firstOrNull()?.let { Triple(i, b, soon) }
    }
    if (rows.isEmpty()) return
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for ((i, b, soon) in rows) {
            val d = soon.first()
            val walk = s.walkMin * 60
            val leave = d.expected - walk - now
            // missed this one? the next you can still make
            val catchable = soon.firstOrNull { it.expected - walk - now >= -30 }
            val (big, small) = when {
                leave > 60 -> "Leave in ${leave / 60} min" to "for the ${Nz.time(d.expected)} ${d.route}"
                leave >= -30 -> "Leave now!" to "the ${d.route} is ${countdown(d.expected - now)} away"
                catchable != null -> "Next one: ${countdown(catchable.expected - walk - now).lowercase()}" to "leave for the ${Nz.time(catchable.expected)}"
                else -> "Run for it! 🏃" to "the ${d.route} is ${countdown(d.expected - now)} away"
            }
            ElevatedCard(Modifier.width(220.dp)) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).background(DirColors[i % 2], CircleShape))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(b.towards, style = MaterialTheme.typography.labelMedium,
                             color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        Text(big, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black, maxLines = 1)
                        Text(small, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun WeatherStrip(w: Weather) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(vertical = 12.dp)) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Auckland weather", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text("${Math.round(w.tempC)}° · ${Weather.words(w.code, w.cloudCover, isNight(Nz.hour()))} · ${Math.round(w.windKmh)} km/h wind",
                     style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (h in w.hourly.take(12)) {
                    Column(Modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                               .padding(horizontal = 10.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        val hr = h.hour % 12
                        Text("${if (hr == 0) 12 else hr}${if (h.hour < 12) "am" else "pm"}", style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(Weather.emoji(h.code, 0, h.hour !in 7..19), fontSize = 20.sp)
                        Text("${Math.round(h.tempC)}°", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        if (h.rainChance >= 20) Text("${h.rainChance}%", style = MaterialTheme.typography.labelSmall, color = Pal.Bus)
                    }
                }
            }
        }
    }
}

/** A favourite stop: its next few departures, live. */
@Composable
private fun FavCard(vm: AppViewModel, f: FavStop, now: Long) {
    val nav = LocalNav.current
    val deps by rememberPolled(f.id, 30_000) { vm.stopDepartures(platformMap(f.id)) }
    ElevatedCard(onClick = { nav.go("stop/${android.net.Uri.encode(f.id)}") }, modifier = Modifier.animateContentSize()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Star, null, tint = Color(0xFFF2B01E), modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(f.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (f.nickname.isNotBlank()) Text(f.name, style = MaterialTheme.typography.bodySmall,
                                                      color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                modesIcons(f.modes).forEach { Icon(it, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Spacer(Modifier.height(8.dp))
            val list = deps
            when {
                list == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                list.isEmpty() -> Text("Nothing in the next few hours", style = MaterialTheme.typography.bodySmall,
                                       color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> list.take(3).forEach { d -> MiniDeparture(d, now) }
            }
        }
    }
}

@Composable
fun MiniDeparture(d: StopDeparture, now: Long) {
    val mode = modeOf(Timetable.today?.let { n -> n.routeType.getOrNull(n.routeId.indexOf(d.routeId)) } ?: 3)
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RouteBadge(d.route, mode, Modifier.width(58.dp))
        Spacer(Modifier.width(10.dp))
        Text(d.headsign, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
        if (d.live) {
            val (p, c) = punctuality(d.delay)
            if (p != "On time") Text(p, color = c, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(end = 6.dp))
        }
        Text(if (d.cancelled) "Cancelled" else countdown(d.expected - now), fontWeight = FontWeight.Black,
             color = if (d.cancelled) Pal.Late else if (d.live) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
    }
}

/** A stop's platforms to ask AT about: a station's children, or just itself. */
fun platformMap(idOrCode: String): Map<String, String> {
    val net = Timetable.today ?: return mapOf(idOrCode to "")
    val s = net.stopIndex(idOrCode)
    if (s < 0) return mapOf(idOrCode to "")
    val kids = (0 until net.nStops).filter { net.stopParent[it] == s }
    return if (kids.isEmpty()) mapOf(net.stopId[s] to net.stopPlat[s]) else kids.associate { net.stopId[it] to net.stopPlat[it] }
}

@Composable
private fun NearMe(vm: AppViewModel) {
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var near by remember { mutableStateOf<List<StopHit>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    val find: () -> Unit = {
        busy = true
        msg = null
        scope.launch {
            val here = vm.here()
            val net = Timetable.today
            when {
                here == null -> msg = "Couldn't get your location. Is it on?"
                net == null -> msg = "The timetable is still loading"
                else -> near = net.nearby(here.latitude, here.longitude, 700, 8).also { if (it.isEmpty()) msg = "No stops within 700 m" }
            }
            busy = false
        }
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.any { it }) find() else msg = "Location is off for AKL Live"
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.MyLocation, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text("Stops near me", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                else OutlinedButton(onClick = {
                    ask.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }) { Text(if (near == null) "Find" else "Again") }
            }
            msg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp)) }
            near?.forEachIndexed { i, h ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                StopRow(h) { nav.go("stop/${android.net.Uri.encode(h.stop.id)}") }
            }
        }
    }
}

/** A stop in a list: its name, code, modes and the routes that call there. */
@Composable
fun StopRow(h: StopHit, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
            Icon(modesIcons(h.modes).first(), null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(h.stop.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1,
                 overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(if (h.station) "Station" else h.stop.code.takeIf { it.isNotEmpty() }?.let { "Stop $it" },
                               h.dist.takeIf { it > 0 }?.let { "$it m" }, h.routes.take(6).joinToString(", ").takeIf { it.isNotEmpty() })
                     .joinToString(" · "),
                 style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                 overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun DexTeaser(spotted: Int, onClick: () -> Unit) {
    val total = Fleet.models.size.coerceAtLeast(1)
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.CatchingPokemon, null, tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Bus spotting", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
                Text(if (spotted == 0) "Every model that pulls up at your stops goes in your fleet dex. Gotta catch 'em all."
                     else "$spotted of $total models spotted at your stops",
                     style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { spotted / total.toFloat() }, modifier = Modifier.fillMaxWidth(),
                                        color = MaterialTheme.colorScheme.tertiary,
                                        trackColor = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.15f))
            }
        }
    }
}

/** Runs [then] once notifications are allowed (asking on Android 13+ if needed). */
@Composable
fun rememberNotifyPermission(): (() -> Unit) -> Unit {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        pending?.invoke()
        pending = null
    }
    return { then ->
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pending = then
            ask.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else then()
    }
}

@Suppress("unused")
private val HomeKinds = PlaceKind.Home
