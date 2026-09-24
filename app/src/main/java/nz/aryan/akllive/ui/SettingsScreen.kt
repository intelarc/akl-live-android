package nz.aryan.akllive.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import nz.aryan.akllive.AppViewModel
import nz.aryan.akllive.BuildConfig

@Composable
fun SettingsScreen(vm: AppViewModel, modifier: Modifier, keepOn: Boolean, setKeepOn: (Boolean) -> Unit) {
    val p = vm.prefs
    var key by remember { mutableStateOf("") }
    var stops by remember { mutableStateOf(p.stops.joinToString(", ")) }
    var route by remember { mutableStateOf(p.route) }
    var place by remember { mutableStateOf(p.place) }
    var linz by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
           verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black,
             modifier = Modifier.padding(start = 4.dp, top = 4.dp))
        Section("Your stops") {
            OutlinedTextField(stops, { stops = it; saved = false }, Modifier.fillMaxWidth(),
                              label = { Text("Bus stop numbers") },
                              supportingText = { Text("The number on the stop sign, comma-separated. 8669 = Aldersgate Rd to the city, 8664 = the other way.") })
            OutlinedTextField(route, { route = it; saved = false }, Modifier.fillMaxWidth(),
                              label = { Text("Route") }, supportingText = { Text("Leave empty to show every route at those stops.") })
            OutlinedTextField(place, { place = it; saved = false }, Modifier.fillMaxWidth(),
                              label = { Text("Place name") })
        }
        Section("Keys") {
            OutlinedTextField(key, { key = it; saved = false }, Modifier.fillMaxWidth(),
                              label = { Text("AT API key") },
                              visualTransformation = PasswordVisualTransformation(),
                              supportingText = {
                                  Text(if (p.keyIsBuiltIn) "Using the key built into this app. Paste one here to override it."
                                       else if (p.apiKey.isNotBlank()) "A key is set (ends …${p.apiKey.takeLast(4)})."
                                       else "Get a free key at dev-portal.at.govt.nz (GTFS product).")
                              })
            Text("The satellite map uses Esri's world imagery. With a free LINZ Basemaps key it switches to " +
                 "Toitū Te Whenua LINZ's aerial photos, down to 7.5 cm across Auckland. Request one at basemaps.linz.govt.nz.",
                 style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                 modifier = Modifier.padding(top = 6.dp))
            OutlinedTextField(linz, { linz = it; saved = false }, Modifier.fillMaxWidth(),
                              label = { Text("LINZ Basemaps key (optional)") },
                              visualTransformation = PasswordVisualTransformation(),
                              supportingText = {
                                  Text(if (p.linzIsBuiltIn) "Using the key built into this app."
                                       else if (p.linzKey.isNotBlank()) "A key is set (ends …${p.linzKey.takeLast(4)}): LINZ aerials."
                                       else "No key: Esri imagery.")
                              })
        }
        Button(onClick = { vm.saveSettings(key, stops, route, place, linz); saved = true; key = ""; linz = "" },
               modifier = Modifier.fillMaxWidth()) {
            Text(if (saved) "Saved" else "Save")
        }
        Section("Display") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Keep the screen on", fontWeight = FontWeight.Bold)
                    Text("Handy when the phone is standing in as a departure board.",
                         style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(keepOn, setKeepOn)
            }
        }
        Column(Modifier.padding(horizontal = 4.dp)) {
            Text("AKL Live ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Live data from the Auckland Transport developer API. Not affiliated with Auckland Transport. " +
                 "The train map follows AT's post-CRL network map; the bus art is inspired by MSMGreen/at-departure-board. " +
                 "Maps by MapLibre, with imagery from Esri or LINZ and streets from OpenFreeMap / OpenStreetMap. " +
                 "Weather by Open-Meteo.com (CC BY 4.0). " +
                 "Bus models and fleet numbers come from the AT Metro Wiki (CC BY-SA) and may be incomplete.",
                 style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(shape = RoundedCornerShape(22.dp),
         colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}
