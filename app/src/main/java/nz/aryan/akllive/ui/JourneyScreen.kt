package nz.aryan.akllive.ui

import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.TextButton
import nz.aryan.akllive.system.Phase
import nz.aryan.akllive.system.TripProgress
import nz.aryan.akllive.system.TripTracker
import nz.aryan.akllive.AppViewModel
import nz.aryan.akllive.Place
import nz.aryan.akllive.data.BusDeparture
import nz.aryan.akllive.data.Nz
import nz.aryan.akllive.data.Places
import nz.aryan.akllive.data.StopBoard
import nz.aryan.akllive.data.TripLive
import nz.aryan.akllive.data.WalkRoute
import nz.aryan.akllive.gtfs.Itinerary
import nz.aryan.akllive.gtfs.Mode
import nz.aryan.akllive.gtfs.RideLeg
import nz.aryan.akllive.gtfs.WalkLeg

private val WalkGrey = Color(0xFF8A94A6)

/** The journey in words, for sharing or a calendar. */
private fun describe(it: Itinerary, from: Place?, to: Place?, live: Map<String, TripLive>): String = buildString {
    appendLine("Getting to ${to?.name ?: "there"}" + (from?.let { " from ${it.name}" } ?: ""))
    for (l in it.legs) when (l) {
        is WalkLeg -> appendLine("• Walk ${durationText(l.end - l.start)} (${distText(l.dist.toDouble())}) to ${l.toName}")
        is RideLeg -> {
            val d = live[l.tripId]?.delay ?: 0
            appendLine("• ${Nz.time(l.from.dep + d)}: the ${l.route} to ${l.headsign} from ${l.from.stop.name}" +
                       (l.from.stop.platform.takeIf { p -> p.isNotEmpty() }?.let { p -> " (platform $p)" } ?: "") +
                       ", off at ${l.to.stop.name} (${l.stops.size - 1} stops)")
        }
    }
    appendLine("Arrive about ${Nz.time(it.end + (it.rides.lastOrNull()?.let { r -> live[r.tripId]?.delay } ?: 0))}")
    append("Planned with AKL Live")
}

/** Little asides about a trip. */
private fun tripNotes(it: Itinerary): List<String> = buildList {
    if (it.rides.any { r -> r.mode == Mode.Ferry }) add("⛴️ Harbour views included")
    if (it.rides.any { r -> r.mode == Mode.Train && r.stops.any { s -> s.stop.name.contains("Te Waihorotiu") || s.stop.name.contains("Karanga-a-Hape") } })
        add("🚇 Through the City Rail Link")
    if (it.walk >= 1500) add("👟 Good for the step count")
    if (it.end - it.start >= 2 * 3600) add("🥪 Pack a snack")
    if (it.transfers >= 3) add("🔁 Change champion")
}

private fun RideLeg.board() = StopBoard(from.stop.code.ifEmpty { from.stop.id }, from.stop.name, from.stop.lat, from.stop.lon, route, headsign)
private fun RideLeg.departure(l: TripLive?) = BusDeparture(tripId, route, headsign, from.seq, from.dep, l?.delay ?: 0,
                                                           l?.delay != null, l?.cancelled == true, false, l?.seq, l?.vehicle)

/** One way there, step by step over a map of it, with every ride live. */
@Composable
fun JourneyScreen(vm: AppViewModel, index: Int) {
    val st by vm.plan.collectAsStateWithLifecycle()
    val it = st.result?.itineraries?.getOrNull(index)
    if (it == null) {
        Column(Modifier.fillMaxSize()) {
            BackBar("Journey")
            EmptyState(Icons.Rounded.Warning, "That journey's gone", "Plan again from Directions.")
        }
        return
    }
    JourneyView(vm, it, st.from, st.to, st.live)
}

/** The journey you're on right now (from its notification, or Home). */
@Composable
fun TripRoute(vm: AppViewModel) {
    val trip by TripTracker.state.collectAsStateWithLifecycle()
    val st by vm.plan.collectAsStateWithLifecycle()
    val it = trip.itinerary
    if (it == null) {
        Column(Modifier.fillMaxSize()) {
            BackBar("Your trip")
            EmptyState(Icons.Rounded.Warning, "No trip on the go", "Plan a journey, then tap Start trip and I'll follow you along it.")
        }
        return
    }
    JourneyView(vm, it, trip.from, trip.to, st.live + trip.live)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JourneyView(vm: AppViewModel, it: Itinerary, from: Place?, to: Place?, live: Map<String, TripLive>) {
    val basemap by vm.basemap.collectAsStateWithLifecycle()
    val linzKey by vm.linzKey.collectAsStateWithLifecycle()
    val back = LocalBack.current
    val frameT = rememberFrameTime()
    val now by rememberNow()
    val ctx = LocalContext.current
    val nav = LocalNav.current
    val notify = rememberNotifyPermission()
    // following you along it
    val trip by TripTracker.state.collectAsStateWithLifecycle()
    val tracking = trip.itinerary === it
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.ACCESS_FINE_LOCATION] == true || r[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            TripTracker.start(ctx, it, from, to)
            vm.toast.tryEmit("Following your trip: it's in your notifications")
        } else vm.toast.tryEmit("Trip tracking needs your location")
    }
    val startTrip: () -> Unit = {
        askLocation.launch(buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION); add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray())
    }
    // every ride's delay and position, every 20 seconds
    LaunchedEffect(it) {
        while (true) {
            vm.refreshPlanLive(it.rides.map { r -> r.tripId })
            delay(20_000)
        }
    }
    // real walking paths and turn-by-turn for the walks
    val walks = remember(it) { mutableStateMapOf<Int, WalkRoute>() }
    LaunchedEffect(it) {
        it.legs.forEachIndexed { k, l ->
            if (l is WalkLeg && l.dist > 40) try { walks[k] = Places.walk(l.fromLat, l.fromLon, l.toLat, l.toLon) } catch (_: Exception) { }
        }
    }
    var focus by remember { mutableStateOf<MapFocus?>(null) }
    val lines = it.legs.mapIndexed { k, l ->
        when (l) {
            is RideLeg -> MapLine(listOf(l.shape), routeColor(l.route, l.mode), 5f)
            is WalkLeg -> MapLine(listOf(walks[k]?.path ?: listOf(l.fromLat to l.fromLon, l.toLat to l.toLon)), WalkGrey, 3f)
        }
    }
    val dots = buildList {
        for (r in it.rides) {
            val c = routeColor(r.route, r.mode)
            add(MapStop(r.from.stop.lat, r.from.stop.lon, c, big = true, label = r.from.stop.name, id = r.from.stop.id))
            add(MapStop(r.to.stop.lat, r.to.stop.lon, c, big = true, label = r.to.stop.name, id = r.to.stop.id))
        }
        (it.legs.firstOrNull() as? WalkLeg)?.let { w -> add(MapStop(w.fromLat, w.fromLon, MaterialTheme.colorScheme.primary, big = true, label = from?.name)) }
        (it.legs.lastOrNull() as? WalkLeg)?.let { w -> add(MapStop(w.toLat, w.toLon, Pal.Late, big = true, label = to?.name)) }
    }
    val markers = it.rides.mapNotNull { r ->
        live[r.tripId]?.vehicle?.let { v ->
            MapMarker(r.tripId, v.lat, v.lon, v.bearing, routeColor(r.route, r.mode), train = r.mode == Mode.Train, tag = r.route)
        }
    } + listOfNotNull(if (tracking && trip.lat != null) MapMarker("me", trip.lat!!, trip.lon!!, null, Color(0xFF1A73E8), train = false, tag = "You")
                      else null)
    // the first fix of a trip brings the map to you
    var centred by remember(tracking) { mutableStateOf(false) }
    LaunchedEffect(tracking, trip.lat != null) {
        if (tracking && !centred && trip.lat != null) { centred = true; focus = MapFocus(trip.lat!!, trip.lon!!, 15.5) }
    }
    val fit = remember(it) {
        val pts = it.legs.flatMap { l -> if (l is RideLeg) l.shape else listOf((l as WalkLeg).fromLat to l.fromLon, l.toLat to l.toLon) }
        if (pts.size > 300) pts.filterIndexed { i, _ -> i % 5 == 0 } + listOf(pts.last()) else pts
    }
    val sheet = rememberBottomSheetScaffoldState()

    BottomSheetScaffold(
        scaffoldState = sheet,
        sheetPeekHeight = 360.dp,
        sheetContent = {
            Steps(it, from, to, live, walks, now, if (tracking) trip else null,
                  onStart = startTrip, onEnd = { TripTracker.stop(ctx) },
                  onLocate = { trip.lat?.let { la -> focus = MapFocus(la, trip.lon!!, 16.0) } },
                  onFocus = { lat, lon -> focus = MapFocus(lat, lon, 16.0) },
                  onStop = { id -> nav.go("stop/${Uri.encode(id)}") },
                  onTrack = { r -> notify { vm.track(r.board(), r.departure(live[r.tripId])) } },
                  onShare = {
                      val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                          .putExtra(Intent.EXTRA_TEXT, describe(it, from, to, live))
                      try { ctx.startActivity(Intent.createChooser(send, "Share the journey")) } catch (_: Exception) { }
                  },
                  onCalendar = {
                      val i = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
                          .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, leaveAt(it, live) * 1000)
                          .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, it.end * 1000)
                          .putExtra(CalendarContract.Events.TITLE, "Trip to ${to?.name ?: "…"}")
                          .putExtra(CalendarContract.Events.DESCRIPTION, describe(it, from, to, live))
                      try { ctx.startActivity(i) } catch (_: Exception) { vm.toast.tryEmit("No calendar app to add it to") }
                  })
        },
    ) { _ ->
        Box(Modifier.fillMaxSize()) {
            LiveMap(basemap, linzKey, lines, dots, markers, fit, frameT, Modifier.fillMaxSize(), topInset = 60f, bottomInset = 340f,
                    focus = focus, onStop = { id -> nav.go("stop/${Uri.encode(id)}") })
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(onClick = back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                Spacer(Modifier.weight(1f))
                BasemapToggle(basemap, vm::setBasemap)
            }
        }
    }
}

@Composable
private fun Steps(it: Itinerary, from: Place?, to: Place?, live: Map<String, TripLive>, walks: Map<Int, WalkRoute>, now: Long,
                  trip: TripProgress?, onStart: () -> Unit, onEnd: () -> Unit, onLocate: () -> Unit,
                  onFocus: (Double, Double) -> Unit, onStop: (String) -> Unit, onTrack: (RideLeg) -> Unit,
                  onShare: () -> Unit, onCalendar: () -> Unit) {
    val leave = leaveAt(it, live)
    val end = it.end + (it.rides.lastOrNull()?.let { r -> live[r.tripId]?.delay } ?: 0)
    LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 24.dp)) {
        item("head") {
            Column {
                Row(verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Text("${Nz.time(leave)} – ${Nz.time(end)}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                        Text("to ${to?.name ?: "…"}", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(durationText(end - leave), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black,
                         color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(8.dp))
                val secs = leave - now
                when {
                    secs > 60 -> Text("Leave in ${countdown(secs)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    now < end -> {
                        Text(if (secs > -60) "Time to go!" else "On your way", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        LinearProgressIndicator(progress = { ((now - leave).toFloat() / (end - leave).coerceAtLeast(1)).coerceIn(0f, 1f) },
                                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                    }
                    else -> Text("This one's been and gone", style = MaterialTheme.typography.titleMedium,
                                 color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(10.dp))
                if (trip != null) TripStatus(trip, onEnd, onLocate)
                else if (now < end) Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Navigation, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Start trip")
                }
                val notes = tripNotes(it)
                if (notes.isNotEmpty()) {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        notes.forEach { n -> Tag(n, MaterialTheme.colorScheme.tertiary) }
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    it.rides.firstOrNull()?.let { r ->
                        AssistChip({ onTrack(r) }, { Text("Track the ${r.route}") },
                                   leadingIcon = { Icon(Icons.Rounded.NotificationsActive, null, Modifier.size(AssistChipDefaults.IconSize)) })
                    }
                    AssistChip(onShare, { Text("Share") }, leadingIcon = { Icon(Icons.Rounded.Share, null, Modifier.size(AssistChipDefaults.IconSize)) })
                    AssistChip(onCalendar, { Text("Calendar") },
                               leadingIcon = { Icon(Icons.Rounded.CalendarMonth, null, Modifier.size(AssistChipDefaults.IconSize)) })
                }
            }
        }
        it.legs.forEachIndexed { k, l ->
            item("leg-$k") {
                when (l) {
                    is WalkLeg -> WalkStep(l, walks[k], if (k == 0) from?.name else null, onFocus)
                    is RideLeg -> RideStep(l, live[l.tripId], now, onFocus, onStop) { onTrack(l) }
                }
            }
        }
        item("end") {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).background(Pal.Late.copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Flag, null, tint = Pal.Late, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(to?.name ?: "You're there", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Arrive about ${Nz.time(end)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun WalkStep(w: WalkLeg, route: WalkRoute?, fromName: String?, onFocus: (Double, Double) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val mins = maxOf(1, Math.round((w.end - w.start) / 60.0))
    val dist = route?.dist ?: w.dist.toDouble()
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).animateContentSize()) {
        // a dotted trail
        androidx.compose.foundation.Canvas(Modifier.width(34.dp).fillMaxHeight()) {
            val step = 9.dp.toPx()
            var y = 6.dp.toPx()
            while (y < size.height - 2.dp.toPx()) {
                drawCircle(WalkGrey, 2.dp.toPx(), androidx.compose.ui.geometry.Offset(size.width / 2, y))
                y += step
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { open = !open; onFocus(w.fromLat, w.fromLon) }
                   .padding(vertical = 10.dp)) {
            if (fromName != null) Text("From $fromName", style = MaterialTheme.typography.labelMedium,
                                       color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Rounded.DirectionsWalk, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text("Walk $mins min · ${distText(dist)}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                     modifier = Modifier.weight(1f))
                if (route != null && route.steps.isNotEmpty()) Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
            }
            Text("to ${w.toName}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                 maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (open && route != null) {
                Column(Modifier.padding(top = 6.dp)) {
                    route?.steps?.forEach { (t, d) ->
                        Row(Modifier.padding(vertical = 3.dp)) {
                            Text(t, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            if (d > 0) Text(distText(d), style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (route?.straight == true) Text("Walking directions aren't available, so this is as the crow flies.",
                                                      style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun RideStep(r: RideLeg, l: TripLive?, now: Long, onFocus: (Double, Double) -> Unit, onStop: (String) -> Unit, onTrack: () -> Unit) {
    var open by rememberSaveable(r.tripId) { mutableStateOf(false) }
    val c = routeColor(r.route, r.mode)
    val delay = l?.delay ?: 0
    val dep = r.from.dep + delay
    val arr = r.to.arr + delay
    val away = l?.seq?.let { r.from.seq - it }
    val toGo = l?.seq?.let { r.to.seq - it }
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        StopLine(r.from.stop.name, listOfNotNull(r.from.stop.platform.takeIf { it.isNotEmpty() }?.let { "Platform $it" },
                                                 r.from.stop.code.takeIf { it.isNotEmpty() && r.mode == Mode.Bus }?.let { "Stop $it" })
                     .joinToString(" · ").ifEmpty { null },
                 c, first = true, last = false, big = true, trailing = Nz.time(dep)) { onStop(r.from.stop.id) }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(Modifier.width(34.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                Box(Modifier.width(5.dp).fillMaxHeight().background(c))
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RouteBadge(r.route, r.mode, big = true, icon = true)
                    Spacer(Modifier.width(8.dp))
                    Text("to ${r.headsign}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1,
                         overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                }
                val (p, pc) = when {
                    l?.cancelled == true -> "Cancelled" to Pal.Late
                    l?.delay != null -> punctuality(delay)
                    else -> "Scheduled" to MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(listOfNotNull(
                    p,
                    when {
                        away != null && away > 0 -> if (away == 1) "1 stop away" else "$away stops away"
                        away == 0 -> "at the stop"
                        toGo != null && toGo >= 0 -> "you're on it: $toGo stops to go"
                        else -> null
                    },
                    if (dep - now > 45) "in ${countdown(dep - now)}" else null,
                ).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = pc, modifier = Modifier.padding(top = 4.dp))
                if (l?.delay != null && Math.abs(delay) >= 60) {
                    Text("Timetabled ${Nz.time(r.from.dep)}", style = MaterialTheme.typography.labelSmall,
                         textDecoration = TextDecoration.LineThrough, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                l?.vehicle?.let { v ->
                    Spacer(Modifier.height(8.dp))
                    if (r.mode == Mode.Bus) VehicleInfo(v) else Text("${r.mode.name} ${v.label}".trim(), style = MaterialTheme.typography.bodySmall)
                    Text("Show it on the map", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                         modifier = Modifier.padding(top = 6.dp).clickable { onFocus(v.lat, v.lon) })
                }
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${r.stops.size - 1} stops · ${durationText(r.to.arr - r.from.dep)}", style = MaterialTheme.typography.labelLarge,
                         modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { open = !open }.padding(vertical = 4.dp))
                    Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, Modifier.size(20.dp).clickable { open = !open })
                    Spacer(Modifier.weight(1f))
                    if (l?.cancelled != true && dep > now - 60) Text("Track", style = MaterialTheme.typography.labelLarge,
                                                                     color = MaterialTheme.colorScheme.primary,
                                                                     modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onTrack)
                                                                         .padding(horizontal = 8.dp, vertical = 4.dp))
                }
            }
        }
        if (open) {
            r.stops.drop(1).dropLast(1).forEach { s ->
                val passed = l?.seq?.let { s.seq <= it } == true
                StopLine(s.stop.name, null, c, first = false, last = false, big = false, trailing = Nz.time(s.dep + delay),
                         faded = passed) { onStop(s.stop.id) }
            }
        }
        StopLine(r.to.stop.name, r.to.stop.platform.takeIf { it.isNotEmpty() }?.let { "Platform $it" } ?: "Get off here",
                 c, first = false, last = true, big = true, trailing = Nz.time(arr)) { onStop(r.to.stop.id) }
    }
}

/** Where you're up to, live: walking, waiting, on board with the stops left, or there. */
@Composable
private fun TripStatus(t: TripProgress, onEnd: () -> Unit, onLocate: () -> Unit) {
    val (icon, tint) = when (t.phase) {
        Phase.Walking -> Icons.AutoMirrored.Rounded.DirectionsWalk to MaterialTheme.colorScheme.primary
        Phase.Waiting -> Icons.Rounded.HourglassTop to Pal.Warn
        Phase.OnBoard -> modeIcon(t.ride?.mode ?: Mode.Bus) to Pal.Live
        Phase.Arrived -> Icons.Rounded.Flag to Pal.Live
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(38.dp).background(MaterialTheme.colorScheme.surface, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.headline, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                         color = MaterialTheme.colorScheme.onPrimaryContainer, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(t.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            if (t.phase == Phase.OnBoard && t.stopsTotal > 0) {
                LinearProgressIndicator(progress = { t.stopsDone / t.stopsTotal.toFloat() },
                                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
            }
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (t.lat == null) "Waiting for GPS…" else "GPS ±${t.accuracy.toInt()} m", style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f), modifier = Modifier.weight(1f))
                TextButton(onClick = onLocate, enabled = t.lat != null) { Text("Where am I") }
                TextButton(onClick = onEnd) { Text("End trip") }
            }
        }
    }
}
