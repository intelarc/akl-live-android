package nz.aryan.akllive.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Business
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Train
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import nz.aryan.akllive.AppViewModel
import nz.aryan.akllive.Place
import nz.aryan.akllive.PlaceKind
import nz.aryan.akllive.SearchHit
import nz.aryan.akllive.data.Fleet

/** A few things people type that deserve an answer of their own. */
private fun quirk(q: String, kiwi: Boolean): String? = when (q.trim().lowercase().trimEnd('!', '?', '.')) {
    "kia ora", "hello", "hi", "hey" -> if (kiwi) "Kia ora! 👋 Where are we off to?" else "Hello! 👋 Where are we off to?"
    "beep", "beep beep", "honk" -> "📯 BEEEEP. (Tap a bus on the home screen and it'll honk back.)"
    "sheep", "🐑" -> "🐑 No sheep on the network. Usually."
    "chur", "sweet as", "sweet" -> "Chur 🤙"
    "bus", "buses" -> "Plenty of those. Try a route number like 27H, or a stop number off the sign."
    "hobbit", "hobbiton" -> "That's a bit far for an AT HOP card. 🧙"
    "home" -> null
    else -> null
}

/** One search for everything: stops, places, routes, bus models, fleet numbers. */
@Composable
fun SearchScreen(vm: AppViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val tt by vm.timetable.collectAsStateWithLifecycle()
    var q by rememberSaveable { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<SearchHit>?>(null) }
    var busy by remember { mutableStateOf(false) }
    val nav = LocalNav.current
    val back = LocalBack.current
    val focus = remember { FocusRequester() }
    val fm = LocalFocusManager.current
    val view = LocalView.current
    val haptics = LocalHaptics.current
    val openModel = LocalOpenModel.current
    LaunchedEffect(Unit) { delay(120); try { focus.requestFocus() } catch (_: Exception) { } }
    LaunchedEffect(q, tt.ready) {
        if (q.isBlank()) { hits = null; busy = false; return@LaunchedEffect }
        busy = true
        delay(260)
        hits = try { vm.search(q) } catch (_: Exception) { emptyList() }
        busy = false
    }
    val goPlace: (Place) -> Unit = { p ->
        haptics.tick(view)
        vm.rememberPlace(p)
        vm.setPlan({ st -> st.copy(to = p, from = st.from?.takeIf { !it.same(p) }) })
        nav.go("plan")
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        Surface(Modifier.fillMaxWidth().padding(12.dp).height(56.dp), shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                Box(Modifier.weight(1f)) {
                    if (q.isEmpty()) Text("Stops, places, routes, buses…", color = MaterialTheme.colorScheme.onSurfaceVariant,
                                          style = MaterialTheme.typography.bodyLarge)
                    BasicTextField(q, { q = it }, Modifier.fillMaxWidth().focusRequester(focus), singleLine = true,
                                   textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                                   cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                   keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                   keyboardActions = KeyboardActions(onSearch = { fm.clearFocus() }))
                }
                if (q.isNotEmpty()) IconButton(onClick = { q = "" }) { Icon(Icons.Rounded.Close, "Clear") }
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 24.dp)) {
            val list = hits
            if (q.isBlank()) {
                item("browse") {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip({ nav.go("routes") }, { Text("All routes") }, leadingIcon = { Icon(Icons.Rounded.Route, null, Modifier.size(18.dp)) })
                        AssistChip({ nav.go("live") }, { Text("Every bus") }, leadingIcon = { Icon(Icons.Rounded.Map, null, Modifier.size(18.dp)) })
                        AssistChip({ nav.go("trains") }, { Text("Trains") }, leadingIcon = { Icon(Icons.Rounded.Train, null, Modifier.size(18.dp)) })
                        AssistChip({ nav.go("alerts") }, { Text("Alerts") }, leadingIcon = { Icon(Icons.Rounded.Warning, null, Modifier.size(18.dp)) })
                    }
                }
                val saved = listOfNotNull(s.home, s.work)
                if (saved.isNotEmpty()) {
                    item("savedh") { SectionHeader("Saved") }
                    items(saved, key = { "saved-" + it.kind }) { p -> HitRow(if (p.kind == PlaceKind.Work) Icons.Rounded.Business else Icons.Rounded.Home,
                                                                              p.kind.name, p.name) { goPlace(p) } }
                }
                if (s.favs.isNotEmpty()) {
                    item("favh") { SectionHeader("Favourite stops") }
                    items(s.favs, key = { "fav-" + it.id }) { f ->
                        HitRow(Icons.Rounded.Star, f.title, if (f.nickname.isNotBlank()) f.name else "Stop ${f.code}") {
                            nav.go("stop/${Uri.encode(f.id)}")
                        }
                    }
                }
                if (s.recents.isNotEmpty()) {
                    item("recenth") { SectionHeader("Recent", action = "Clear") { vm.update { it.copy(recents = emptyList()) } } }
                    items(s.recents, key = { "r-" + it.lat + it.lon }) { p ->
                        HitRow(Icons.Rounded.History, p.name, p.sub) {
                            if (p.kind == PlaceKind.Stop && p.stopId != null) nav.go("stop/${Uri.encode(p.stopId)}") else goPlace(p)
                        }
                    }
                }
                if (!tt.ready) item("tt") {
                    Text(if (tt.loading) "Stop and route search is getting ready: ${tt.text}" else "Stops and routes search once the timetable has loaded.",
                         style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                         modifier = Modifier.padding(16.dp))
                }
                return@LazyColumn
            }
            quirk(q, s.kiwi)?.let { msg ->
                item("quirk") {
                    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.tertiaryContainer,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text(msg, Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onTertiaryContainer,
                             style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            if (list == null) return@LazyColumn
            if (list.isEmpty() && !busy) {
                item("none") {
                    EmptyState(Icons.Rounded.SearchOff, "Nothing for \"${q.trim()}\"",
                               if (tt.ready) "Try a stop number off the sign, a street or a route" else "Stops and routes show once the timetable loads")
                }
                return@LazyColumn
            }
            val routes = list.filterIsInstance<SearchHit.RouteHit>()
            val stops = list.filterIsInstance<SearchHit.StopHit>()
            val places = list.filterIsInstance<SearchHit.PlaceHit>()
            val buses = list.filter { it is SearchHit.ModelHit || it is SearchHit.FleetNoHit }
            if (routes.isNotEmpty()) {
                item("rh") { SectionHeader("Routes") }
                items(routes, key = { "route-" + it.short }) { r ->
                    Row(Modifier.fillMaxWidth().clickable { nav.go("route/${Uri.encode(r.short)}") }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RouteBadge(r.short, modeOf(r.type), Modifier.width(64.dp), icon = r.type != 3)
                        Spacer(Modifier.width(12.dp))
                        Text(r.long.ifEmpty { "Route ${r.short}" }, style = MaterialTheme.typography.bodyMedium, maxLines = 2,
                             overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (stops.isNotEmpty()) {
                item("sh") { SectionHeader("Stops and stations") }
                items(stops, key = { "stop-" + it.hit.stop.id }) { h -> StopRow(h.hit) { nav.go("stop/${Uri.encode(h.hit.stop.id)}") } }
            }
            if (buses.isNotEmpty()) {
                item("bh") { SectionHeader("Buses") }
                items(buses, key = { b -> if (b is SearchHit.ModelHit) "m-" + b.id else "f-" + (b as SearchHit.FleetNoHit).fleetNo }) { b ->
                    when (b) {
                        is SearchHit.ModelHit -> HitRow(AklIcons.Fleet, b.name, b.kind) { openModel(b.id) }
                        is SearchHit.FleetNoHit -> HitRow(AklIcons.Bus, b.fleetNo, listOfNotNull(b.model ?: "Model not known yet", b.operator).joinToString(" · ")) {
                            openModel(Fleet.info(b.fleetNo)?.model?.id ?: UNKNOWN_MODEL)
                        }
                        else -> {}
                    }
                }
            }
            if (places.isNotEmpty()) {
                item("ph") { SectionHeader("Places") }
                items(places, key = { "p-" + it.place.lat + "," + it.place.lon }) { p ->
                    HitRow(Icons.Rounded.Place, p.place.name, p.place.sub, "Directions") { goPlace(p.place) }
                }
                item("credit") {
                    Text("Places from Photon · © OpenStreetMap contributors", style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
                }
            }
        }
    }
}

/** A row in a list of results: an icon, a title and a line under it. */
@Composable
fun HitRow(icon: ImageVector, title: String, sub: String, trailing: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1,
                 overflow = TextOverflow.Ellipsis)
            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                       maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}
