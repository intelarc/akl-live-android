package nz.aryan.akllive.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.Business
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Directions
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nz.aryan.akllive.AppViewModel
import nz.aryan.akllive.Place
import nz.aryan.akllive.PlaceKind
import nz.aryan.akllive.PlanOpts
import nz.aryan.akllive.PlanState
import nz.aryan.akllive.data.Nz
import nz.aryan.akllive.data.Places
import nz.aryan.akllive.data.TripLive
import nz.aryan.akllive.gtfs.Itinerary
import nz.aryan.akllive.gtfs.RideLeg
import nz.aryan.akllive.gtfs.StopHit
import nz.aryan.akllive.gtfs.Timetable
import nz.aryan.akllive.gtfs.WalkLeg
import nz.aryan.akllive.gtfs.searchStops
import java.time.LocalDate
import java.time.LocalTime

/**
 * Where the phone is, as a Place, asking for location first if needed.
 * Calls back with null when it's off or not allowed.
 */
@Composable
fun rememberLocate(vm: AppViewModel): ((Place?) -> Unit) -> Unit {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<((Place?) -> Unit)?>(null) }
    val run: ((Place?) -> Unit) -> Unit = { cb ->
        scope.launch {
            val l = vm.here()
            cb(l?.let { Place("Your location", "Near %.4f, %.4f".format(it.latitude, it.longitude), it.latitude, it.longitude, PlaceKind.Here) })
        }
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        val cb = pending
        pending = null
        if (cb != null) { if (r.values.any { it }) run(cb) else cb(null) }
    }
    return { cb ->
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) run(cb)
        else {
            pending = cb
            ask.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }
}

/** Walking time before the first ride, and when you'd need to leave, with the live delay. */
fun leaveAt(it: Itinerary, live: Map<String, TripLive>): Long {
    val first = it.rides.firstOrNull() ?: return it.start
    val walk = (it.legs.firstOrNull() as? WalkLeg)?.let { w -> w.end - w.start } ?: 0
    return first.from.dep + (live[first.tripId]?.delay ?: 0) - walk
}

/** Directions: from, to, when; then the ways there, live. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanScreen(vm: AppViewModel) {
    val st by vm.plan.collectAsStateWithLifecycle()
    val s by vm.settings.collectAsStateWithLifecycle()
    val tt by vm.timetable.collectAsStateWithLifecycle()
    val now by rememberNow()
    val nav = LocalNav.current
    val view = LocalView.current
    val haptics = LocalHaptics.current
    var picking by remember { mutableStateOf<Boolean?>(null) }          // true: the start, false: the end
    var timeDialog by remember { mutableStateOf(false) }
    var options by remember { mutableStateOf(false) }
    var locating by remember { mutableStateOf(false) }
    val locate = rememberLocate(vm)
    val useHere: () -> Unit = {
        locating = true
        locate { p ->
            locating = false
            if (p != null) vm.setPlan({ it.copy(from = p) }) else vm.toast.tryEmit("Couldn't get your location. Pick a start instead")
        }
    }
    // somewhere to go but nowhere to start: start from here
    LaunchedEffect(st.from == null && st.to != null) { if (st.from == null && st.to != null) useHere() }
    // a stale "leave now" plan refreshes itself; live delays every 30 s
    LaunchedEffect(st.result != null) {
        if (st.result != null && st.time == null && Nz.nowSec() - st.stamp > 180) vm.runPlan()
        while (st.result != null) {
            delay(30_000)
            vm.refreshPlanLive()
        }
    }
    // the timetable finishing loading runs a plan that was waiting on it
    LaunchedEffect(tt.ready) { if (tt.ready && st.result == null && st.from != null && st.to != null && !st.loading) vm.runPlan() }

  Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp)) {
            Column(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Directions", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black,
                         modifier = Modifier.weight(1f).padding(start = 4.dp))
                    if (st.from != null || st.to != null) IconButton(onClick = { vm.setPlan({ PlanState() }, run = false) }) {
                        Icon(Icons.Rounded.Close, "Clear")
                    }
                    IconButton(onClick = { options = true }) { Icon(Icons.Rounded.Tune, "Options") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        PlaceField(st.from, "Choose a start", true, locating) { picking = true }
                        PlaceField(st.to, "Where to?", false, false) { picking = false }
                    }
                    var spun by remember { mutableIntStateOf(0) }
                    val angle by animateFloatAsState(spun * 180f, label = "swap")
                    IconButton(onClick = {
                        haptics.tick(view)
                        spun++
                        vm.setPlan({ it.copy(from = it.to, to = it.from) })
                    }) { Icon(Icons.Rounded.SwapVert, "Swap", Modifier.rotate(angle)) }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(st.time == null, onClick = { vm.setPlan({ it.copy(time = null, arriveBy = false) }) },
                               label = { Text("Leave now") })
                    FilterChip(st.time != null && !st.arriveBy, onClick = {
                        vm.setPlan({ it.copy(arriveBy = false) }, run = false); timeDialog = true
                    }, label = { Text(if (st.time != null && !st.arriveBy) "Leave ${whenText(st.time!!)}" else "Leave at…") },
                               leadingIcon = { Icon(Icons.Rounded.AccessTime, null, Modifier.size(18.dp)) })
                    FilterChip(st.arriveBy, onClick = {
                        vm.setPlan({ it.copy(arriveBy = true) }, run = false); timeDialog = true
                    }, label = { Text(if (st.arriveBy && st.time != null) "Arrive by ${whenText(st.time!!)}" else "Arrive by…") })
                }
            }
        }
        Box(Modifier.weight(1f)) {
            PlanBody(vm, st, s, tt.ready, tt.loading, tt.text, tt.pct, tt.error, now) { i -> nav.go("journey/$i") }
        }
    }

    // overlays: choosing a place, the time, the options
    // remembered so the picker keeps its title while it slides away
    var lastStart by remember { mutableStateOf(true) }
    picking?.let { lastStart = it }
    AnimatedVisibility(picking != null, enter = slideInVertically { it / 3 } + fadeIn(), exit = slideOutVertically { it / 3 } + fadeOut()) {
        val start = picking ?: lastStart
        PlacePicker(vm, if (start) "Start from" else "Going to", start,
                    onPick = { p ->
                        vm.rememberPlace(p)
                        vm.setPlan({ if (start) it.copy(from = p) else it.copy(to = p) })
                        picking = null
                    },
                    onHere = { picking = null; if (start) useHere() else locate { p -> if (p != null) vm.setPlan({ it.copy(to = p) }) } },
                    onClose = { picking = null })
    }
    if (timeDialog) TimeDialog(st, onDismiss = {
        timeDialog = false
        // "arrive by" with no time picked means nothing: back to leaving now
        if (vm.plan.value.time == null) vm.setPlan({ it.copy(arriveBy = false) }, run = false)
    }) { t ->
        timeDialog = false
        vm.setPlan({ it.copy(time = t) })
    }
    if (options) {
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { options = false; vm.runPlan() }, sheetState = sheet) {
            OptionsSheet(s.planOpts) { o -> vm.update { it.copy(planOpts = o) } }
        }
    }
}
}

/** "5:30 pm", "tomorrow 8:10 am", "Fri 7:00 am" */
fun whenText(t: Long): String {
    val d = Nz.at(t).toLocalDate()
    val today = Nz.now().toLocalDate()
    return when (d) {
        today -> Nz.time(t)
        today.plusDays(1) -> "tomorrow ${Nz.time(t)}"
        else -> d.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() } + " " + Nz.time(t)
    }
}

@Composable
private fun PlaceField(p: Place?, hint: String, start: Boolean, busy: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (start) Box(Modifier.size(12.dp).background(MaterialTheme.colorScheme.primary, CircleShape).padding(3.dp)
                           .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape))
        else Icon(Icons.Rounded.Place, null, Modifier.size(16.dp), tint = Pal.Late)
        Spacer(Modifier.width(12.dp))
        Text(p?.name ?: hint, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
             color = if (p == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
             fontWeight = if (p != null) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f))
        if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        else if (p?.kind == PlaceKind.Here) Icon(Icons.Rounded.MyLocation, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun PlanBody(vm: AppViewModel, st: PlanState, s: nz.aryan.akllive.Settings, ready: Boolean, loading: Boolean,
                     text: String, pct: Int, ttError: String?, now: Long, open: (Int) -> Unit) {
    val nav = LocalNav.current
    val r = st.result
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (s.apiKey.isBlank() && !ready) {
            item("nokey") {
                EmptyState(Icons.Rounded.Warning, "Add your AT key first",
                           "Directions use AT's full timetable, which downloads once you've added your free key.") {
                    FilledTonalButton(onClick = { nav.go("settings") }) { Text("Open Settings") }
                }
            }
            return@LazyColumn
        }
        if (st.from == null || st.to == null) {
            item("sugg") { Suggestions(vm, s) }
            return@LazyColumn
        }
        if (!ready && loading) item("tt") { TimetableCard(text, pct) }
        if (!ready && !loading && ttError != null) item("tterr") {
            EmptyState(Icons.Rounded.Warning, "The timetable didn't load", ttError) {
                FilledTonalButton(onClick = { vm.runPlan() }) { Text("Try again") }
            }
        }
        when {
            st.loading -> items(3) { SkeletonCard() }
            st.error != null && ready -> item("err") {
                EmptyState(Icons.Rounded.Warning, "Couldn't plan that", st.error) {
                    FilledTonalButton(onClick = { vm.runPlan() }) { Text("Try again") }
                }
            }
            r != null -> {
                r.walkOnly?.let { secs ->
                    if (secs <= 25 * 60) item("walk") { WalkOnlyCard(secs, r.direct, s.kiwi) }
                }
                if (r.itineraries.isEmpty()) item("none") {
                    EmptyState(Icons.Rounded.Directions, "No way there found",
                               r.note ?: "Nothing within your walking limit and ride count at that time. Try leaving later, or allow more walking in options.")
                } else {
                    val fastest = r.itineraries.minOf { it.end - leaveAt(it, st.live) }
                    val fewest = r.itineraries.minOf { it.transfers }
                    val leastWalk = r.itineraries.minOf { it.walk }
                    itemsIndexed(r.itineraries, key = { i, it -> "it-$i-" + it.rides.joinToString { r -> r.tripId } }) { i, it ->
                        val tags = buildList {
                            if (it.end - leaveAt(it, st.live) == fastest) add("Fastest")
                            if (r.itineraries.size > 1 && it.transfers == fewest && r.itineraries.any { o -> o.transfers > fewest }) add("Fewest changes")
                            if (r.itineraries.size > 1 && it.walk == leastWalk && r.itineraries.any { o -> o.walk > leastWalk + 100 }) add("Least walking")
                        }
                        ItineraryCard(it, st.live, now, st.time == null, tags) { open(i) }
                    }
                    item("foot") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Planned ${if (st.stamp > 0) ago(st.stamp, now) else ""} from AT's timetable, with live delays.",
                                 style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                 modifier = Modifier.weight(1f).padding(start = 4.dp))
                            IconButton(onClick = { vm.runPlan() }) { Icon(Icons.Rounded.Refresh, "Plan again") }
                        }
                    }
                }
            }
        }
    }
}

/** Nowhere picked yet: the usual places, one tap away. */
@Composable
private fun Suggestions(vm: AppViewModel, s: nz.aryan.akllive.Settings) {
    val nav = LocalNav.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for ((p, label, icon) in listOf(Triple(s.home, "Home", Icons.Rounded.Home), Triple(s.work, "Work", Icons.Rounded.Business))) {
                ElevatedCard(onClick = {
                    if (p != null) vm.setPlan({ it.copy(to = p) }) else nav.go("settings")
                }, modifier = Modifier.weight(1f)) {
                    Column(Modifier.padding(14.dp)) {
                        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(6.dp))
                        Text(label, style = MaterialTheme.typography.titleMedium)
                        Text(p?.name ?: "Set it in Settings", style = MaterialTheme.typography.bodySmall, maxLines = 1,
                             overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (s.recents.isNotEmpty()) {
            SectionHeader("Recent")
            s.recents.take(6).forEach { p -> HitRow(Icons.Rounded.History, p.name, p.sub) { vm.setPlan({ it.copy(to = p) }) } }
        }
        if (s.favs.isNotEmpty()) {
            SectionHeader("Favourite stops")
            s.favs.take(5).forEach { f ->
                HitRow(Icons.Rounded.Star, f.title, "Stop ${f.code}") {
                    vm.setPlan({ it.copy(to = Place(f.name, "Stop ${f.code}", f.lat, f.lon, PlaceKind.Stop, f.id, f.code, f.modes)) })
                }
            }
        }
        Text("Tip: long-press any map in the app to drop a pin and get directions there.",
             style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
             modifier = Modifier.padding(4.dp))
    }
}

@Composable
private fun WalkOnlyCard(secs: Int, dist: Int, kiwi: Boolean) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Rounded.DirectionsWalk, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Walk it: ${durationText(secs.toLong())}", style = MaterialTheme.typography.titleSmall,
                     color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text("About ${distText(dist * 1.25)} on foot" + if (kiwi && secs < 600) ". Good day for it, eh?" else "",
                     style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
}

@Composable
private fun SkeletonCard() {
    val a by androidx.compose.animation.core.rememberInfiniteTransition(label = "sk").animateFloat(
        0.35f, 0.7f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(700),
                                                                         androidx.compose.animation.core.RepeatMode.Reverse), label = "a")
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val c = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = a * 0.3f)
            Box(Modifier.fillMaxWidth(0.55f).height(18.dp).background(c, RoundedCornerShape(8.dp)))
            Box(Modifier.fillMaxWidth(0.8f).height(24.dp).background(c, RoundedCornerShape(8.dp)))
            Box(Modifier.fillMaxWidth(0.4f).height(14.dp).background(c, RoundedCornerShape(8.dp)))
        }
    }
}

/** One way there: times, the rides in a row, and when to leave. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ItineraryCard(it: Itinerary, live: Map<String, TripLive>, now: Long, leavingNow: Boolean, tags: List<String>, onClick: () -> Unit) {
    val first = it.rides.firstOrNull()
    val firstLive = first?.let { f -> live[f.tripId] }
    val delay = firstLive?.delay ?: 0
    val leave = leaveAt(it, live)
    val end = it.end + (it.rides.lastOrNull()?.let { l -> live[l.tripId]?.delay } ?: 0)
    ElevatedCard(onClick = onClick) {
        Column(Modifier.padding(16.dp)) {
            if (tags.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    tags.forEach { t -> Tag(t, MaterialTheme.colorScheme.primary) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${Nz.time(leave)} – ${Nz.time(end)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(listOfNotNull(if (it.transfers == 0) "Direct" else if (it.transfers == 1) "1 change" else "${it.transfers} changes",
                                       if (it.walk > 0) "${distText(it.walk.toDouble())} walk" else null).joinToString(" · "),
                         style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(durationText(end - leave), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.height(10.dp))
            FlowRow(verticalArrangement = Arrangement.spacedBy(6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                it.legs.forEachIndexed { i, leg ->
                    if (i > 0) Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp).align(Alignment.CenterVertically),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    when (leg) {
                        is WalkLeg -> Row(Modifier.align(Alignment.CenterVertically), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.AutoMirrored.Rounded.DirectionsWalk, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${maxOf(1, Math.round((leg.end - leg.start) / 60.0))}", style = MaterialTheme.typography.labelMedium,
                                 color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        is RideLeg -> RouteBadge(leg.route, leg.mode, Modifier.align(Alignment.CenterVertically), icon = true)
                    }
                }
            }
            if (first != null) {
                HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val (p, c) = when {
                        firstLive?.cancelled == true -> "Cancelled" to Pal.Late
                        firstLive?.delay != null -> punctuality(delay)
                        else -> "Scheduled" to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    if (firstLive?.delay != null || firstLive?.vehicle != null) {
                        Box(Modifier.size(7.dp).background(Pal.Live, CircleShape))
                        Spacer(Modifier.width(5.dp))
                    }
                    Text(listOfNotNull("${first.route} from ${first.from.stop.name}", p,
                                       firstLive?.vehicle?.busModel()?.let { m -> m.short + if (m.electric) " ⚡" else "" })
                             .joinToString(" · "),
                         style = MaterialTheme.typography.bodySmall, color = c, maxLines = 1, overflow = TextOverflow.Ellipsis,
                         modifier = Modifier.weight(1f))
                    if (leavingNow) {
                        val secs = leave - now
                        Text(when {
                            firstLive?.cancelled == true -> ""
                            secs > 60 -> "Leave in ${countdown(secs)}"
                            secs > -60 -> "Leave now"
                            else -> "Hurry!"
                        }, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                             color = if (secs < 120) Pal.Late else MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

// ======================= picking a place =======================

/** Choose a start or an end: where you are, saved places, stops and addresses, or a pin on the map. */
@Composable
internal fun PlacePicker(vm: AppViewModel, title: String, start: Boolean, onPick: (Place) -> Unit, onHere: () -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val s by vm.settings.collectAsStateWithLifecycle()
    var q by remember { mutableStateOf("") }
    var stops by remember { mutableStateOf<List<StopHit>>(emptyList()) }
    var places by remember { mutableStateOf<List<Place>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var mapPick by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(150); try { focus.requestFocus() } catch (_: Exception) { } }
    LaunchedEffect(q) {
        if (q.isBlank()) { stops = emptyList(); places = emptyList(); return@LaunchedEffect }
        delay(250)
        busy = true
        stops = Timetable.today?.let { net -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { net.searchStops(q, 6) } } ?: emptyList()
        places = try { Places.search(q) } catch (_: Exception) { emptyList() }
        busy = false
    }
    if (mapPick) {
        MapPicker(vm, onPick = { p -> mapPick = false; onPick(p) }, onClose = { mapPick = false })
        return
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            Surface(Modifier.fillMaxWidth().padding(12.dp).height(56.dp), shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                    Box(Modifier.weight(1f)) {
                        if (q.isEmpty()) Text("$title: a stop, street or place", color = MaterialTheme.colorScheme.onSurfaceVariant,
                                              style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                        BasicTextField(q, { q = it }, Modifier.fillMaxWidth().focusRequester(focus), singleLine = true,
                                       textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                                       cursorBrush = SolidColor(MaterialTheme.colorScheme.primary))
                    }
                    if (q.isNotEmpty()) IconButton(onClick = { q = "" }) { Icon(Icons.Rounded.Close, "Clear") }
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp))
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 24.dp)) {
                if (q.isBlank()) {
                    item("here") { HitRow(Icons.Rounded.MyLocation, "Your location", "Where the phone is now", onClick = onHere) }
                    item("map") { HitRow(Icons.Rounded.Map, "Choose on the map", "Long-press to drop a pin") { mapPick = true } }
                    s.home?.let { h -> item("home") { HitRow(Icons.Rounded.Home, "Home", h.name) { onPick(h) } } }
                    s.work?.let { w -> item("work") { HitRow(Icons.Rounded.Business, "Work", w.name) { onPick(w) } } }
                    if (s.favs.isNotEmpty()) {
                        item("fh") { SectionHeader("Favourite stops") }
                        items(s.favs, key = { "f-" + it.id }) { f ->
                            HitRow(Icons.Rounded.Star, f.title, "Stop ${f.code}") {
                                onPick(Place(f.name, "Stop ${f.code}", f.lat, f.lon, PlaceKind.Stop, f.id, f.code, f.modes))
                            }
                        }
                    }
                    if (s.recents.isNotEmpty()) {
                        item("rh") { SectionHeader("Recent") }
                        items(s.recents, key = { "r-" + it.lat + "," + it.lon }) { p -> HitRow(Icons.Rounded.History, p.name, p.sub) { onPick(p) } }
                    }
                } else {
                    if (stops.isNotEmpty()) {
                        item("sh") { SectionHeader("Stops and stations") }
                        items(stops, key = { "s-" + it.stop.id }) { h ->
                            StopRow(h) {
                                onPick(Place(h.stop.name, if (h.station) "Station" else "Stop ${h.stop.code}", h.stop.lat, h.stop.lon,
                                             PlaceKind.Stop, h.stop.id, h.stop.code, h.modes))
                            }
                        }
                    }
                    if (places.isNotEmpty()) {
                        item("ph") { SectionHeader("Places") }
                        items(places, key = { "p-" + it.lat + "," + it.lon }) { p -> HitRow(Icons.Rounded.Place, p.name, p.sub) { onPick(p) } }
                    }
                    if (!busy && stops.isEmpty() && places.isEmpty()) item("none") {
                        Text("Nothing found for \"$q\"", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** A map to long-press on: the pin becomes the start or the end. */
@Composable
private fun MapPicker(vm: AppViewModel, onPick: (Place) -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val basemap by vm.basemap.collectAsStateWithLifecycle()
    val linzKey by vm.linzKey.collectAsStateWithLifecycle()
    val frameT = rememberFrameTime()
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var place by remember { mutableStateOf<Place?>(null) }
    val view = LocalView.current
    val haptics = LocalHaptics.current
    Box(Modifier.fillMaxSize()) {
        LiveMap(basemap, linzKey, emptyList(),
                listOfNotNull(pin?.let { MapStop(it.first, it.second, Pal.Late, big = true, label = place?.name ?: "…") }),
                emptyList(), AKL_BOUNDS, frameT, Modifier.fillMaxSize(),
                onLongPress = { lat, lon ->
                    haptics.heavy(view)
                    pin = lat to lon
                    place = null
                    scope.launch { place = Places.reverse(lat, lon) }
                })
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Spacer(Modifier.weight(1f))
            BasemapToggle(basemap, vm::setBasemap)
        }
        Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp), shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.PushPin, null, tint = Pal.Late)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(place?.name ?: if (pin == null) "Long-press the map" else "Finding the address…",
                         style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(place?.sub ?: "to drop a pin where you want to go", style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                Button(onClick = { place?.let(onPick) }, enabled = place != null) { Text("Use") }
            }
        }
    }
}

// ======================= when, and how =======================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(st: PlanState, onDismiss: () -> Unit, onSet: (Long) -> Unit) {
    val start = Nz.at(st.time ?: (Nz.nowSec() + 10 * 60))
    val today = Nz.now().toLocalDate()
    var day by remember { mutableIntStateOf(((start.toLocalDate().toEpochDay() - today.toEpochDay()).toInt()).coerceIn(0, 6)) }
    val tp = rememberTimePickerState(start.hour, start.minute, is24Hour = false)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (st.arriveBy) "Arrive by" else "Leave at") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (d in 0..6) {
                        val date = today.plusDays(d.toLong())
                        FilterChip(day == d, { day = d }, label = {
                            Text(when (d) { 0 -> "Today"; 1 -> "Tomorrow"
                                            else -> date.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() } + " ${date.dayOfMonth}" })
                        })
                    }
                }
                Spacer(Modifier.height(12.dp))
                TimePicker(tp)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val date: LocalDate = today.plusDays(day.toLong())
                onSet(date.atTime(LocalTime.of(tp.hour, tp.minute)).atZone(Nz.zone).toEpochSecond())
            }) { Text("Plan") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OptionsSheet(o: PlanOpts, set: (PlanOpts) -> Unit) {
    var walk by remember { mutableFloatStateOf(o.maxWalk.toFloat()) }
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Trip options", style = MaterialTheme.typography.titleLarge)
        Column {
            Text("Walk up to ${walk.toInt()} m to a stop (about ${Math.round(walk * 1.28 / o.walkSpeed / 60)} min)",
                 style = MaterialTheme.typography.bodyMedium)
            Slider(walk, { walk = (Math.round(it / 100f) * 100f) }, valueRange = 200f..1500f, steps = 12,
                   onValueChangeFinished = { set(o.copy(maxWalk = walk.toInt())) })
        }
        Text("Walking pace", style = MaterialTheme.typography.labelLarge)
        val paces = listOf(1.0 to "Strolling", 1.3 to "Normal", 1.6 to "Brisk")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            paces.forEachIndexed { i, (v, label) ->
                SegmentedButton(Math.abs(o.walkSpeed - v) < 0.05, onClick = { set(o.copy(walkSpeed = v)) },
                                shape = SegmentedButtonDefaults.itemShape(i, paces.size)) { Text(label) }
            }
        }
        Text("Ride on", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(o.bus, { if (o.train || o.ferry || !o.bus) set(o.copy(bus = !o.bus)) }, label = { Text("Buses") },
                       leadingIcon = { Icon(modeIcon(nz.aryan.akllive.gtfs.Mode.Bus), null, Modifier.size(18.dp)) })
            FilterChip(o.train, { if (o.bus || o.ferry || !o.train) set(o.copy(train = !o.train)) }, label = { Text("Trains") },
                       leadingIcon = { Icon(modeIcon(nz.aryan.akllive.gtfs.Mode.Train), null, Modifier.size(18.dp)) })
            FilterChip(o.ferry, { if (o.bus || o.train || !o.ferry) set(o.copy(ferry = !o.ferry)) }, label = { Text("Ferries") },
                       leadingIcon = { Icon(modeIcon(nz.aryan.akllive.gtfs.Mode.Ferry), null, Modifier.size(18.dp)) })
        }
        Text("Changes", style = MaterialTheme.typography.labelLarge)
        val rides = listOf(1 to "Direct", 2 to "1", 3 to "2", 4 to "3+")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            rides.forEachIndexed { i, (v, label) ->
                SegmentedButton(o.maxRides == v, onClick = { set(o.copy(maxRides = v)) },
                                shape = SegmentedButtonDefaults.itemShape(i, rides.size)) { Text(label) }
            }
        }
        TextButton(onClick = { walk = 900f; set(PlanOpts()) }) { Text("Reset to defaults") }
    }
}
