package nz.aryan.akllive.ui

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AddToHomeScreen
import androidx.compose.material.icons.rounded.Directions
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nz.aryan.akllive.AppViewModel
import nz.aryan.akllive.FavStop
import nz.aryan.akllive.Place
import nz.aryan.akllive.PlaceKind
import nz.aryan.akllive.data.Alert
import nz.aryan.akllive.data.AlertKind
import nz.aryan.akllive.data.Nz
import nz.aryan.akllive.data.StopBoard
import nz.aryan.akllive.data.StopDeparture
import nz.aryan.akllive.gtfs.Mode
import nz.aryan.akllive.gtfs.RouteSummary
import nz.aryan.akllive.gtfs.Timetable
import nz.aryan.akllive.gtfs.routeInfo
import nz.aryan.akllive.gtfs.routesList
import nz.aryan.akllive.gtfs.stopDetail
import nz.aryan.akllive.gtfs.tripInfo
import nz.aryan.akllive.system.Shortcuts

/** A route's mode from its GTFS route id ("27H-203"), by the timetable. */
fun modeOfRoute(routeId: String, short: String = ""): Mode {
    val net = Timetable.today ?: return Mode.Bus
    var i = net.routeId.indexOf(routeId)
    if (i < 0 && short.isNotEmpty()) i = net.routeShort.indexOf(short)
    return if (i >= 0) Mode.of(net.routeType[i]) else Mode.Bus
}

/** A stop or station: every departure, live, and what's going on there. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun StopScreen(vm: AppViewModel, id: String) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val tt by vm.timetable.collectAsStateWithLifecycle()
    val alerts by vm.alerts.collectAsStateWithLifecycle()
    val basemap by vm.basemap.collectAsStateWithLifecycle()
    val linzKey by vm.linzKey.collectAsStateWithLifecycle()
    val detail = remember(tt.ready, id) { Timetable.today?.stopDetail(id) }
    val now by rememberNow()
    val frameT = rememberFrameTime()
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val view = LocalView.current
    val haptics = LocalHaptics.current
    val notify = rememberNotifyPermission()
    var tick by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    val platforms = remember(detail, id) { platformMap(detail?.stop?.id ?: id) }
    var err by remember { mutableStateOf<String?>(null) }
    val deps by rememberPolled(platforms to tick, 30_000) {
        try { vm.stopDepartures(platforms).also { err = null } } catch (e: Exception) { err = e.message ?: "Couldn't reach AT"; throw e }
        finally { refreshing = false }
    }
    var only by rememberSaveable(id) { mutableStateOf<String?>(null) }
    var open by rememberSaveable(id) { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf(false) }
    val stopId = detail?.stop?.id ?: id
    val code = detail?.stop?.code ?: id
    val name = detail?.stop?.name ?: "Stop $id"
    val fav = s.favs.firstOrNull { it.id == stopId || it.code == id }
    val shown = deps?.filter { only == null || it.route == only }
    val mine = remember(alerts, stopId, platforms) {
        alerts.filter { a -> a.stopIds.any { it == stopId || it in platforms } || (code.isNotEmpty() && code in a.stopCodes) }
    }
    val routeAlerts = remember(alerts, detail) {
        val here = detail?.routes?.map { it.short }?.toSet() ?: emptySet()
        alerts.filter { a -> a.active && a !in mine && a.routes.any { it in here } &&
                        a.kind in setOf(AlertKind.NoService, AlertKind.Detour, AlertKind.StopMoved, AlertKind.Delays) }
    }
    // the map shows the stop, the buses heading for it, and the path of the one you opened
    val openDep = shown?.firstOrNull { it.tripId == open }
    val openShape = remember(open, tt.ready) { open?.let { Timetable.today?.tripInfo(it)?.shape } }
    val fit = remember(detail?.stop?.id) { detail?.let { listOf(it.stop.lat to it.stop.lon) } ?: emptyList() }
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        Modifier.nestedScroll(scroll.nestedScrollConnection),
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = {
            BackBar(fav?.title ?: name,
                    listOfNotNull(if (detail?.station == true) "Station" else code.takeIf { it.isNotEmpty() }?.let { "Stop $it" },
                                  fav?.let { name.takeIf { n -> n != it.title } }).joinToString(" · "),
                    scroll) {
                IconButton(onClick = {
                    haptics.confirm(view)
                    vm.toggleFav(FavStop(stopId, code, name, fav?.nickname ?: "", detail?.stop?.lat ?: 0.0,
                                         detail?.stop?.lon ?: 0.0, detail?.modes ?: 1))
                }) {
                    Icon(if (fav != null) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                         if (fav != null) "Unfavourite" else "Favourite",
                         tint = if (fav != null) Color(0xFFF2B01E) else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (fav != null) IconButton(onClick = { renaming = true }) { Icon(Icons.Rounded.Edit, "Nickname") }
                IconButton(onClick = {
                    if (!Shortcuts.pin(ctx, stopId, fav?.title ?: name)) vm.toast.tryEmit("Your launcher can't pin shortcuts")
                }) { Icon(Icons.Rounded.AddToHomeScreen, "Add to home screen") }
            }
        },
    ) { pad ->
        PullToRefreshBox(refreshing, onRefresh = { refreshing = true; tick++ }, modifier = Modifier.fillMaxSize().padding(pad)) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 28.dp),
                       verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (detail != null) item("map") {
                    val markers = shown.orEmpty().filter { it.vehicle != null && !it.cancelled && it.expected - now < 1800 }.take(8).map { d ->
                        val v = d.vehicle!!
                        MapMarker(d.tripId, v.lat, v.lon, v.bearing, routeColor(d.route, modeOfRoute(d.routeId, d.route)),
                                  train = modeOfRoute(d.routeId, d.route) == Mode.Train, tag = "${d.route} · ${countdown(d.expected - now)}")
                    }
                    val lines = openShape?.let { listOf(MapLine(listOf(it), routeColor(openDep?.route ?: "", Mode.Bus), 4f)) } ?: emptyList()
                    val dots = listOf(MapStop(detail.stop.lat, detail.stop.lon, MaterialTheme.colorScheme.primary, big = true,
                                              label = detail.stop.name)) +
                        detail.platforms.map { MapStop(it.stop.lat, it.stop.lon, MaterialTheme.colorScheme.primary) }
                    Box(Modifier.fillMaxWidth().height(210.dp).clip(RoundedCornerShape(22.dp))) {
                        LiveMap(basemap, linzKey, lines, dots, markers, fit, frameT, Modifier.fillMaxSize(),
                                selected = open, onMarker = { open = if (open == it) null else it }, onBackground = { open = null })
                        Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) { BasemapToggle(basemap, vm::setBasemap) }
                    }
                }
                item("actions") {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val here = detail?.let { Place(it.stop.name, if (it.station) "Station" else "Stop ${it.stop.code}",
                                                       it.stop.lat, it.stop.lon, PlaceKind.Stop, it.stop.id, it.stop.code, it.modes) }
                        FilledTonalButton(enabled = here != null, onClick = {
                            vm.setPlan({ st -> st.copy(to = here, from = st.from?.takeIf { f -> here == null || !f.same(here) }) })
                            nav.go("plan")
                        }) {
                            Icon(Icons.Rounded.Directions, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Directions here")
                        }
                        OutlinedButton(enabled = here != null, onClick = {
                            vm.setPlan({ st -> st.copy(from = here, to = st.to?.takeIf { t -> here == null || !t.same(here) }) })
                            nav.go("plan")
                        }) { Text("From here") }
                    }
                }
                if (detail != null && detail.routes.isNotEmpty()) item("routes") {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(only == null, onClick = { only = null }, label = { Text("All") })
                        for (r in detail.routes) {
                            FilterChip(only == r.short, onClick = { only = if (only == r.short) null else r.short },
                                       label = { Text(r.short, fontWeight = FontWeight.Bold) },
                                       leadingIcon = { Box(Modifier.size(10.dp).background(routeColor(r.short, modeOf(r.type)), CircleShape)) })
                        }
                    }
                }
                if (mine.isNotEmpty() || routeAlerts.isNotEmpty()) item("alerts") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        (mine + routeAlerts).take(3).forEach { a -> AlertMini(a) { nav.go("alerts") } }
                    }
                }
                if (tt.loading && detail == null) item("tt") { TimetableCard(tt.text, tt.pct) }
                item("head") { SectionHeader(if (only != null) "Departures on the $only" else "Departures") }
                val list = shown
                when {
                    s.apiKey.isBlank() -> item("nokey") {
                        EmptyState(Icons.Rounded.Warning, "No AT key yet", "Add your free key in Settings to see live departures.") {
                            FilledTonalButton(onClick = { nav.go("settings") }) { Text("Open Settings") }
                        }
                    }
                    list == null && err != null -> item("err") {
                        EmptyState(Icons.Rounded.Warning, "Couldn't load departures", err ?: "") {
                            FilledTonalButton(onClick = { tick++ }) { Text("Try again") }
                        }
                    }
                    list == null -> item("loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    list.isEmpty() -> item("empty") {
                        EmptyState(Icons.Rounded.Search, "Nothing for a while",
                                   if (only != null) "No ${only} in the next three hours" else "No departures in the next three hours")
                    }
                    else -> items(list, key = { it.tripId + it.stopId }) { d ->
                        StopDepartureRow(d, now, detail?.station == true, open == d.tripId,
                                         onClick = { haptics.tick(view); open = if (open == d.tripId) null else d.tripId },
                                         onTrack = {
                                             notify {
                                                 vm.track(StopBoard(code.ifEmpty { stopId }, name, detail?.stop?.lat ?: 0.0,
                                                                    detail?.stop?.lon ?: 0.0), d.asBus())
                                             }
                                         },
                                         onRoute = { nav.go("route/${Uri.encode(d.route)}") })
                    }
                }
                if (detail != null && detail.platforms.size > 1) {
                    item("plats") { SectionHeader("Platforms") }
                    items(detail.platforms, key = { "p-" + it.stop.id }) { h ->
                        StopRow(h.copy(stop = h.stop.copy(name = h.stop.platform.takeIf { it.isNotEmpty() }?.let { "Platform $it" } ?: h.stop.name))) {
                            nav.go("stop/${Uri.encode(h.stop.id)}")
                        }
                    }
                }
                item("foot") {
                    Text("Live from Auckland Transport; times without \"live\" are from the timetable. Tap a departure for the bus and to track it.",
                         style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                         modifier = Modifier.padding(horizontal = 4.dp))
                }
            }
        }
    }

    if (renaming && fav != null) {
        var text by remember { mutableStateOf(fav.nickname) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Nickname") },
            text = {
                OutlinedTextField(text, { text = it.take(30) }, singleLine = true, label = { Text("e.g. Work stop") },
                                  supportingText = { Text(fav.name) })
            },
            confirmButton = { TextButton(onClick = { vm.renameFav(fav.id, text.trim()); renaming = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
}

/** One departure: route, where it's going, when; tap for the bus itself and to track it. */
@Composable
fun StopDepartureRow(d: StopDeparture, now: Long, station: Boolean, expanded: Boolean,
                     onClick: () -> Unit, onTrack: () -> Unit, onRoute: () -> Unit) {
    val mode = modeOfRoute(d.routeId, d.route)
    Card(onClick = onClick, modifier = Modifier.animateContentSize(),
         colors = CardDefaults.cardColors(containerColor = if (expanded) MaterialTheme.colorScheme.surfaceContainerHigh
                                                           else MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RouteBadge(d.route, mode, Modifier.width(62.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(d.headsign, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1,
                         overflow = TextOverflow.Ellipsis,
                         textDecoration = if (d.cancelled) TextDecoration.LineThrough else null)
                    val (p, c) = when {
                        d.cancelled -> "Cancelled" to Pal.Late
                        d.live -> punctuality(d.delay)
                        else -> "Scheduled" to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (d.live && !d.cancelled) {
                            Box(Modifier.size(7.dp).background(Pal.Live, CircleShape))
                            Spacer(Modifier.width(5.dp))
                        }
                        Text(listOfNotNull(
                            if (station && d.platform.isNotEmpty()) "Platform ${d.platform}" else null,
                            p,
                            d.stopsAway?.let { if (it == 0) "at the stop" else if (it == 1) "1 stop away" else "$it stops away" },
                            d.vehicle?.busModel()?.let { m -> m.short + if (m.electric) " ⚡" else "" },
                        ).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = c, maxLines = 1,
                             overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(if (d.cancelled) "—" else countdown(d.expected - now), fontWeight = FontWeight.Black,
                         style = MaterialTheme.typography.titleMedium,
                         color = if (d.live && !d.cancelled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    Text(Nz.time(d.expected), style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            AnimatedVisibility(expanded) {
                Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (d.live && d.delay / 60 != 0) {
                        Text("Timetabled for ${Nz.time(d.scheduled)}", style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    d.vehicle?.let { VehicleInfo(it) }
                    if (d.vehicle == null && !d.cancelled) {
                        Text(if (d.live) "Running, but not sharing its GPS right now" else "Not on the road yet",
                             style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!d.cancelled) FilledTonalButton(onClick = onTrack) {
                            Icon(Icons.Rounded.NotificationsActive, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Track")
                        }
                        OutlinedButton(onClick = onRoute) {
                            Icon(Icons.Rounded.Route, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Route ${d.route}")
                        }
                    }
                }
            }
        }
    }
}

/** A disruption in one line, for the top of a stop or route. */
@Composable
fun AlertMini(a: Alert, onClick: () -> Unit) {
    val (bg, fg) = alertColors(a.kind)
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(bg).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(alertIcon(a.kind), null, tint = fg, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(a.kind.label + if (!a.active) " · upcoming" else "", style = MaterialTheme.typography.labelMedium, color = fg,
                 fontWeight = FontWeight.Bold)
            Text(a.header, style = MaterialTheme.typography.bodySmall, color = fg, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = fg, modifier = Modifier.size(18.dp))
    }
}

// ======================= a route =======================

/** A route: its map, its stops each way, and every bus on it right now. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteScreen(vm: AppViewModel, short: String) {
    DisposableEffect(Unit) {
        vm.watchLive(true)
        onDispose { vm.watchLive(false) }
    }
    val tt by vm.timetable.collectAsStateWithLifecycle()
    val fleet by vm.fleet.collectAsStateWithLifecycle()
    val alerts by vm.alerts.collectAsStateWithLifecycle()
    val basemap by vm.basemap.collectAsStateWithLifecycle()
    val linzKey by vm.linzKey.collectAsStateWithLifecycle()
    val info = remember(tt.ready, short) { Timetable.today?.routeInfo(short) }
    val frameT = rememberFrameTime()
    val now by rememberNow()
    val nav = LocalNav.current
    var dir by rememberSaveable(short) { mutableIntStateOf(0) }
    var selected by rememberSaveable(short) { mutableStateOf<String?>(null) }
    val mode = info?.let { modeOf(it.type) } ?: Mode.Bus
    val color = routeColor(short, mode)
    val live = remember(fleet.buses, short) { fleet.buses.filter { it.route.equals(short, true) } }
    val sel = live.firstOrNull { it.v.id == selected }
    val routeAlerts = remember(alerts, short) { alerts.filter { a -> a.routes.any { it.equals(short, true) } } }
    val d = info?.dirs?.getOrNull(dir)
    val fit = remember(info) { info?.lines?.flatten()?.let { pts -> if (pts.size > 400) pts.filterIndexed { i, _ -> i % 8 == 0 } else pts } ?: emptyList() }

    Scaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = {
            BackBar("Route $short", info?.long?.takeIf { it.isNotEmpty() } ?: when (mode) {
                Mode.Train -> "Train line"; Mode.Ferry -> "Ferry"; else -> "Bus route"
            })
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 28.dp),
                   verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (info == null) {
                item {
                    if (tt.loading) TimetableCard(tt.text, tt.pct)
                    else EmptyState(Icons.Rounded.Route, "Route $short", tt.error ?: "Not running today, or the timetable isn't loaded")
                }
            }
            if (info != null) item("map") {
                val lines = info.lines.map { MapLine(listOf(it), color, 4.5f) }
                val dots = d?.stops?.mapIndexed { i, st ->
                    MapStop(st.lat, st.lon, color, big = i == 0 || i == d.stops.lastIndex,
                            label = if (i == 0 || i == d.stops.lastIndex) st.name else null, id = st.id)
                } ?: emptyList()
                val markers = live.map { b ->
                    MapMarker(b.v.id, b.v.lat, b.v.lon, b.v.bearing, color, train = mode == Mode.Train,
                              tag = if (b.v.id == selected) b.info.model?.short ?: b.info.fleetNo else null)
                }
                Box(Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(22.dp))) {
                    LiveMap(basemap, linzKey, lines, dots, markers, fit, frameT, Modifier.fillMaxSize(), selected = selected,
                            onMarker = { selected = if (selected == it) null else it },
                            onStop = { nav.go("stop/${Uri.encode(it)}") }, onBackground = { selected = null })
                    Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) { BasemapToggle(basemap, vm::setBasemap) }
                }
            }
            if (sel != null) item("sel") {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    Column(Modifier.padding(12.dp)) {
                        VehicleInfo(sel.v, big = true)
                        Text(listOfNotNull(sel.v.speedKmh?.takeIf { it > 1 }?.let { "${it.toInt()} km/h" },
                                           nz.aryan.akllive.data.occupancyText(sel.v.occupancy),
                                           "seen ${ago(sel.v.timestamp, now)}").joinToString(" · "),
                             style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                             modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
            if (info != null) item("facts") {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FactTile("On the road", if (fleet.loading) "…" else "${live.size}")
                    FactTile("Trips today", "${info.trips}")
                    FactTile("First", Nz.time(Timetable.dayStart(Timetable.todayYmd()) + info.first))
                    FactTile("Last", Nz.time(Timetable.dayStart(Timetable.todayYmd()) + info.last))
                    if (info.agency.isNotEmpty()) FactTile("Run by", agencyName(info.agency))
                }
            }
            if (routeAlerts.isNotEmpty()) item("alerts") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { routeAlerts.take(4).forEach { AlertMini(it) { nav.go("alerts") } } }
            }
            if (info != null && info.dirs.size > 1) item("dirs") {
                SecondaryTabRow(selectedTabIndex = dir.coerceIn(0, info.dirs.lastIndex)) {
                    info.dirs.forEachIndexed { i, rd ->
                        Tab(dir == i, onClick = { dir = i }, text = { Text("to ${rd.headsign}", maxLines = 1, overflow = TextOverflow.Ellipsis) })
                    }
                }
            }
            if (d != null) {
                item("dirhead") {
                    Text("${d.stops.size} stops · ${d.trips} trips this way today", style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
                }
                items(d.stops.size, key = { "s-$dir-$it" }) { i ->
                    val st = d.stops[i]
                    StopLine(st.name, if (st.platform.isNotEmpty()) "Platform ${st.platform}" else st.code.takeIf { it.isNotEmpty() }?.let { "Stop $it" },
                             color, first = i == 0, last = i == d.stops.lastIndex) { nav.go("stop/${Uri.encode(st.id)}") }
                }
            }
        }
    }
}

/** A stop on a line diagram: a dot on the route's colour, joined to the next. */
@Composable
fun StopLine(name: String, sub: String?, color: Color, first: Boolean, last: Boolean, big: Boolean = first || last,
             trailing: String? = null, faded: Boolean = false, onClick: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().height(if (sub != null) 52.dp else 40.dp)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(34.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Column(Modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.width(5.dp).weight(1f).background(if (first) Color.Transparent else color.copy(alpha = if (faded) 0.35f else 1f)))
                Box(Modifier.width(5.dp).weight(1f).background(if (last) Color.Transparent else color.copy(alpha = if (faded) 0.35f else 1f)))
            }
            Box(Modifier.size(if (big) 16.dp else 11.dp).background(MaterialTheme.colorScheme.surface, CircleShape)
                    .padding(if (big) 3.dp else 2.dp).background(color, CircleShape))
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = if (big) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                 fontWeight = if (big) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis,
                 color = if (faded) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            if (sub != null) Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 6.dp),
                                   color = if (faded) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
fun FactTile(label: String, value: String) {
    Column(Modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
               .padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** AT's agency codes, in words. */
fun agencyName(code: String): String = when (code.uppercase()) {
    "NZB" -> "NZ Bus"
    "RTH" -> "Ritchies"
    "GBT" -> "Go Bus"
    "HE" -> "Howick & Eastern"
    "TZG" -> "Tranzurban"
    "WBC" -> "Waiheke Bus Co"
    "BAYES" -> "Bayes"
    "PC" -> "Pavlovich"
    "AM" -> "Auckland One Rail"
    "FGL" -> "Fullers360"
    "SBL" -> "Sealink"
    "BFL" -> "Belaire Ferries"
    "ATMB" -> "AT Metro"
    else -> code
}

// ======================= every route =======================

/** Every route running today, to browse. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutesScreen(vm: AppViewModel) {
    val tt by vm.timetable.collectAsStateWithLifecycle()
    val all = remember(tt.ready) { Timetable.today?.routesList() ?: emptyList() }
    var kind by rememberSaveable { mutableStateOf(-1) }
    var q by rememberSaveable { mutableStateOf("") }
    val nav = LocalNav.current
    val shown = remember(all, kind, q) {
        all.filter { (kind < 0 || modeOf(it.type).ordinal == kind) &&
                     (q.isBlank() || it.short.contains(q.trim(), true) || it.long.contains(q.trim(), true)) }
    }
    Scaffold(contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
             topBar = { BackBar("Routes", if (all.isEmpty()) null else "${all.size} running today") }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 28.dp),
                   verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item("filter") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(50),
                                      placeholder = { Text("Route number or where it goes") },
                                      leadingIcon = { Icon(Icons.Rounded.Search, null) })
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(kind < 0, { kind = -1 }, label = { Text("All") })
                        for (m in listOf(Mode.Bus, Mode.Train, Mode.Ferry)) {
                            FilterChip(kind == m.ordinal, { kind = if (kind == m.ordinal) -1 else m.ordinal }, label = { Text(m.name) },
                                       leadingIcon = { Icon(modeIcon(m), null, Modifier.size(18.dp)) })
                        }
                    }
                }
            }
            if (all.isEmpty()) item("tt") {
                if (tt.loading) TimetableCard(tt.text, tt.pct)
                else EmptyState(Icons.Rounded.Route, "No routes yet", tt.error ?: "The timetable loads once you've added your AT key")
            }
            items(shown, key = { it.short }) { r -> RouteRow(r) { nav.go("route/${Uri.encode(r.short)}") } }
        }
    }
}

@Composable
private fun RouteRow(r: RouteSummary, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        RouteBadge(r.short, modeOf(r.type), Modifier.width(70.dp), icon = modeOf(r.type) != Mode.Bus)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(r.long.ifEmpty { r.short }, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${r.trips} trips today", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
