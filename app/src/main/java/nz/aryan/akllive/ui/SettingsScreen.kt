package nz.aryan.akllive.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
    var saved by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(stops, { stops = it; saved = false }, Modifier.fillMaxWidth(),
                          label = { Text("Bus stop numbers") },
                          supportingText = { Text("The number on the stop sign, comma-separated. 8669 = Aldersgate Rd to the city, 8664 = the other way.") })
        OutlinedTextField(route, { route = it; saved = false }, Modifier.fillMaxWidth(),
                          label = { Text("Route") }, supportingText = { Text("Leave empty to show every route at those stops.") })
        OutlinedTextField(place, { place = it; saved = false }, Modifier.fillMaxWidth(),
                          label = { Text("Place name") })
        OutlinedTextField(key, { key = it; saved = false }, Modifier.fillMaxWidth(),
                          label = { Text("AT API key") },
                          visualTransformation = PasswordVisualTransformation(),
                          supportingText = {
                              Text(if (p.keyIsBuiltIn) "Using the key built into this app. Paste one here to override it."
                                   else if (p.apiKey.isNotBlank()) "A key is set (ends …${p.apiKey.takeLast(4)})."
                                   else "Get a free key at dev-portal.at.govt.nz (GTFS product).")
                          })
        Spacer(Modifier.height(8.dp))
        Button(onClick = { vm.saveSettings(key, stops, route, place); saved = true; key = "" }) {
            Text(if (saved) "Saved" else "Save")
        }
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Keep the screen on", fontWeight = FontWeight.Bold)
                Text("Handy when the phone is standing in as a departure board.",
                     style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(keepOn, setKeepOn)
        }
        Spacer(Modifier.height(28.dp))
        Text("AKL Live ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Live data from the Auckland Transport developer API. Not affiliated with Auckland Transport. " +
             "The train map follows AT's post-CRL network map; the bus art is inspired by MSMGreen/at-departure-board.",
             style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
