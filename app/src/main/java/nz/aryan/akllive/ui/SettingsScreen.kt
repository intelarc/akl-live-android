package nz.aryan.akllive.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Business
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import nz.aryan.akllive.AppViewModel
import nz.aryan.akllive.BuildConfig
import nz.aryan.akllive.Place
import nz.aryan.akllive.PlaceKind
import nz.aryan.akllive.gtfs.Timetable

/** Everything you can set, in sections. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val tt by vm.timetable.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val view = LocalView.current
    val haptics = LocalHaptics.current
    var picking by remember { mutableStateOf<PlaceKind?>(null) }
    val locate = rememberLocate(vm)
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()

    Box(Modifier.fillMaxSize()) {
        Scaffold(Modifier.nestedScroll(scroll.nestedScrollConnection),
                 contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
                 topBar = { BackBar("Settings", scroll = scroll) }) { pad ->
            Column(Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(14.dp),
                   verticalArrangement = Arrangement.spacedBy(14.dp)) {

                // ---- keys first when there isn't one: nothing live works without it
                val keySection: @Composable () -> Unit = {
                    Section("Auckland Transport key", if (s.apiKey.isBlank()) "Needed for everything live. It's free." else null) {
                        var key by rememberSaveable { mutableStateOf("") }
                        OutlinedTextField(key, { key = it.trim() }, Modifier.fillMaxWidth(), singleLine = true,
                                          label = { Text("AT API key") }, visualTransformation = PasswordVisualTransformation(),
                                          supportingText = {
                                              Text(if (s.apiKey.isNotBlank()) "A key is set (ends …${s.apiKey.takeLast(4)}). Paste a new one to replace it."
                                                   else "Sign up at dev-portal.at.govt.nz, subscribe to the GTFS product, and paste the primary key here.")
                                          })
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { vm.update { it.copy(apiKey = key) }; key = ""; vm.toast.tryEmit("Key saved") },
                                   enabled = key.length >= 16) { Text("Save key") }
                            OutlinedButton(onClick = {
                                try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://dev-portal.at.govt.nz/"))) } catch (_: Exception) { }
                            }) { Text("Get a key") }
                        }
                    }
                }
                if (s.apiKey.isBlank()) keySection()

                // ---- home screen
                Section("Your stops", "The live bus scenes on Home") {
                    var stops by rememberSaveable(s.stops) { mutableStateOf(s.stops.joinToString(", ")) }
                    var route by rememberSaveable(s.route) { mutableStateOf(s.route) }
                    var place by rememberSaveable(s.place) { mutableStateOf(s.place) }
                    OutlinedTextField(stops, { stops = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Stop numbers") },
                                      supportingText = { Text("From the stop sign, comma-separated: one each way works best.") })
                    OutlinedTextField(route, { route = it.trim().uppercase() }, Modifier.fillMaxWidth(), singleLine = true,
                                      label = { Text("Route") }, supportingText = { Text("Empty shows every route at those stops.") })
                    OutlinedTextField(place, { place = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Your neighbourhood") })
                    val parsed = stops.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    val changed = parsed != s.stops || route != s.route || place.trim() != s.place
                    Button(onClick = { vm.update { it.copy(stops = parsed, route = route, place = place.trim().ifEmpty { "Auckland" }) } },
                           enabled = changed && parsed.isNotEmpty()) { Text(if (changed) "Save" else "Saved") }
                }

                Section("Places", "One tap to get home or to work") {
                    PlaceRow(Icons.Rounded.Home, "Home", s.home, onSet = { picking = PlaceKind.Home }) { vm.update { it.copy(home = null) } }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    PlaceRow(Icons.Rounded.Business, "Work", s.work, onSet = { picking = PlaceKind.Work }) { vm.update { it.copy(work = null) } }
                    Text("How long's the walk to your stop?", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
                    var walk by remember(s.walkMin) { mutableFloatStateOf(s.walkMin.toFloat()) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Slider(walk, { walk = it }, Modifier.weight(1f), valueRange = 1f..20f, steps = 18,
                               onValueChangeFinished = { vm.update { it.copy(walkMin = walk.toInt()) } })
                        Text("${walk.toInt()} min", Modifier.width(56.dp), style = MaterialTheme.typography.labelLarge)
                    }
                }

                Section("Appearance") {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        ThemeMode.entries.forEachIndexed { i, m ->
                            SegmentedButton(s.themeMode == m, onClick = { vm.update { it.copy(themeMode = m) } },
                                            shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size)) { Text(m.label) }
                        }
                    }
                    val dynamicOk = Build.VERSION.SDK_INT >= 31
                    if (dynamicOk) Toggle("Colours from your wallpaper", "Material You", s.dynamicColor) { v -> vm.update { it.copy(dynamicColor = v) } }
                    if (!s.dynamicColor || !dynamicOk) {
                        Text("Theme", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Palette.entries.forEach { p -> Swatch(p, s.palette == p) { haptics.tick(view); vm.update { it.copy(palette = p) } } }
                        }
                        Text(s.palette.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Toggle("Pure black", "For OLED screens, when it's dark", s.pureBlack) { v -> vm.update { it.copy(pureBlack = v) } }
                    Text("Maps", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        listOf(Basemap.Satellite to "Satellite", Basemap.Streets to "Streets").forEachIndexed { i, (b, label) ->
                            SegmentedButton(s.basemap == b, onClick = { vm.setBasemap(b) }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(label) }
                        }
                    }
                }

                Section("Tracking a bus") {
                    Text("Buzz me this long before I need to leave", style = MaterialTheme.typography.bodyMedium)
                    var lead by remember(s.alertMin) { mutableFloatStateOf(s.alertMin.toFloat()) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Slider(lead, { lead = it }, Modifier.weight(1f), valueRange = 0f..15f, steps = 14,
                               onValueChangeFinished = { vm.update { it.copy(alertMin = lead.toInt()) } })
                        Text("${lead.toInt()} min", Modifier.width(56.dp), style = MaterialTheme.typography.labelLarge)
                    }
                    Text("That's on top of your ${s.walkMin} min walk.", style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Section("Feel") {
                    Toggle("Haptics", "Little buzzes on taps and toggles", s.haptics) { v -> vm.update { it.copy(haptics = v) } }
                    Toggle("Shake to refresh", "Give the phone a good shake", s.shake) { v -> vm.update { it.copy(shake = v) } }
                    Toggle("Kiwi mode", "Mōrena, chur, sweet as and the like", s.kiwi) { v -> vm.update { it.copy(kiwi = v) } }
                    Toggle("Keep the screen on", "When the phone's a departure board on the bench", s.keepOn) { v -> vm.update { it.copy(keepOn = v) } }
                }

                Section("Timetable", "AT's full timetable, on your phone for Directions, stops and routes") {
                    Text(when {
                        tt.ready -> "Loaded for ${tt.date?.let { "${it.substring(6)}/${it.substring(4, 6)}" } ?: "today"}: " +
                                    "%,d stops, %,d trips, %d routes".format(tt.stops, tt.trips, tt.routes)
                        tt.loading -> tt.text
                        else -> tt.error ?: "Not loaded yet"
                    }, style = MaterialTheme.typography.bodyMedium)
                    if (tt.loading) LinearProgressIndicator(progress = { tt.pct / 100f }, modifier = Modifier.fillMaxWidth())
                    if (tt.zipBytes > 0) Text("%.1f MB downloaded".format(tt.zipBytes / 1e6), style = MaterialTheme.typography.bodySmall,
                                              color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Toggle("Download on Wi-Fi only", "It's about 29 MB, checked every six hours", s.wifiOnly) { v -> vm.update { it.copy(wifiOnly = v) } }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { scope.launch { Timetable.ready(s.wifiOnly) } }, enabled = !tt.loading && s.apiKey.isNotBlank()) {
                            Text(if (tt.ready) "Check now" else "Load now")
                        }
                        TextButton(onClick = { scope.launch { Timetable.clear(); vm.toast.tryEmit("Timetable cleared") } }, enabled = !tt.loading) {
                            Text("Clear it")
                        }
                    }
                }

                if (s.apiKey.isNotBlank()) keySection()

                Section("Aerial photos (optional)") {
                    Text("Satellite maps use Esri's imagery. With a free LINZ Basemaps key they switch to Toitū Te Whenua LINZ's " +
                         "aerials, down to 7.5 cm across Auckland.", style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                    var linz by rememberSaveable { mutableStateOf("") }
                    OutlinedTextField(linz, { linz = it.trim() }, Modifier.fillMaxWidth(), singleLine = true,
                                      label = { Text("LINZ Basemaps key") }, visualTransformation = PasswordVisualTransformation(),
                                      supportingText = { Text(if (s.linzKey.isNotBlank()) "Set: LINZ aerials are on" else "Not set: Esri imagery") })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.update { it.copy(linzKey = linz) }; linz = "" }, enabled = linz.length >= 8) { Text("Save") }
                        if (s.linzKey.isNotBlank()) TextButton(onClick = { vm.update { it.copy(linzKey = "") } }) { Text("Remove") }
                    }
                }

                About(vm)
                Spacer(Modifier.height(24.dp))
            }
        }

        AnimatedVisibility(picking != null, enter = slideInVertically { it / 3 } + fadeIn(), exit = slideOutVertically { it / 3 } + fadeOut()) {
            val kind = picking ?: PlaceKind.Home
            PlacePicker(vm, if (kind == PlaceKind.Home) "Home" else "Work", false,
                        onPick = { p ->
                            val saved = p.copy(kind = kind, sub = p.sub.ifEmpty { p.name })
                            vm.update { if (kind == PlaceKind.Home) it.copy(home = saved) else it.copy(work = saved) }
                            picking = null
                            vm.toast.tryEmit(if (s.kiwi && kind == PlaceKind.Home) "Home sweet home 🏡" else "${kind.name} saved")
                        },
                        onHere = {
                            picking = null
                            locate { p ->
                                if (p == null) vm.toast.tryEmit("Couldn't get your location")
                                else {
                                    val saved = p.copy(name = if (kind == PlaceKind.Home) "Home" else "Work", kind = kind)
                                    vm.update { if (kind == PlaceKind.Home) it.copy(home = saved) else it.copy(work = saved) }
                                }
                            }
                        },
                        onClose = { picking = null })
        }
    }
}

@Composable
private fun Section(title: String, sub: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
    }
}

@Composable
private fun Toggle(title: String, sub: String?, on: Boolean, set: (Boolean) -> Unit) {
    val view = LocalView.current
    val haptics = LocalHaptics.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { haptics.tick(view); set(!on) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(on, { haptics.tick(view); set(it) })
    }
}

@Composable
private fun PlaceRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, p: Place?, onSet: () -> Unit, onClear: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onSet).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(p?.let { if (it.sub.isNotEmpty() && it.sub != it.name) "${it.name} · ${it.sub}" else it.name } ?: "Not set",
                 style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        if (p != null) TextButton(onClick = onClear) { Text("Clear") } else TextButton(onClick = onSet) { Text("Set") }
    }
}

@Composable
private fun Swatch(p: Palette, on: Boolean, pick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = pick).padding(4.dp)) {
        Box(Modifier.size(48.dp).clip(CircleShape)
                .border(if (on) 3.dp else 1.dp, if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant, CircleShape),
            contentAlignment = Alignment.Center) {
            // the palette's three colours, as a little pie
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(2f).fillMaxSize().background(Color(p.primary)))
                Column(Modifier.weight(1f).fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxWidth().background(Color(p.secondary)))
                    Box(Modifier.weight(1f).fillMaxWidth().background(Color(p.tertiary)))
                }
            }
            if (on) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(p.label, style = MaterialTheme.typography.labelSmall, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun About(vm: AppViewModel) {
    var taps by remember { mutableIntStateOf(0) }
    val view = LocalView.current
    val haptics = LocalHaptics.current
    val ctx = LocalContext.current
    Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("AKL Live ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleSmall,
             modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                 taps++
                 haptics.tick(view)
                 when (taps) {
                     3 -> vm.toast.tryEmit("Keep going…")
                     5 -> vm.toast.tryEmit("Nearly at the depot…")
                     7 -> { haptics.heavy(view); vm.toast.tryEmit("🚌 Beep beep! You found the secret depot. You're officially a bus nerd.") }
                 }
             }.padding(vertical = 4.dp))
        Text("Live data from the Auckland Transport developer API; not affiliated with Auckland Transport. " +
             "Directions run on AT's GTFS timetable, right on your phone. " +
             "Maps by MapLibre, with imagery from Esri or Toitū Te Whenua LINZ and streets from OpenFreeMap / © OpenStreetMap contributors. " +
             "Places by Photon (komoot), walking directions by FOSSGIS OSRM, weather by Open-Meteo.com (CC BY 4.0). " +
             "Bus models and fleet numbers from the AT Metro Wiki (CC BY-SA), and may be incomplete. " +
             "The bus art is inspired by MSMGreen/at-departure-board.",
             style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = {
            try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/intelarc/akl-live-android"))) } catch (_: Exception) { }
        }) { Text("Source on GitHub") }
    }
}
