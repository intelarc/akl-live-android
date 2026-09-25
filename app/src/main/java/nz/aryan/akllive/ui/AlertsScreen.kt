package nz.aryan.akllive.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoveDown
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Timelapse
import androidx.compose.material.icons.rounded.TurnRight
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nz.aryan.akllive.AppViewModel
import nz.aryan.akllive.data.Alert
import nz.aryan.akllive.data.AlertKind

fun alertIcon(k: AlertKind): ImageVector = when (k) {
    AlertKind.NoService -> Icons.Rounded.Block
    AlertKind.Reduced -> Icons.Rounded.Timelapse
    AlertKind.Delays -> Icons.Rounded.Schedule
    AlertKind.Detour -> Icons.Rounded.TurnRight
    AlertKind.Extra -> Icons.Rounded.Add
    AlertKind.Changed -> Icons.Rounded.Info
    AlertKind.StopMoved -> Icons.Rounded.MoveDown
    AlertKind.Other -> Icons.Rounded.Warning
}

/** Background and text colours for an alert, by how bad it is. */
@Composable
fun alertColors(k: AlertKind): Pair<Color, Color> = when (k) {
    AlertKind.NoService, AlertKind.Delays -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    AlertKind.Detour, AlertKind.StopMoved, AlertKind.Reduced ->
        MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
    else -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
}

/** AT's disruptions: yours first, then everything, searchable. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertsScreen(vm: AppViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val alerts by vm.alerts.collectAsStateWithLifecycle()
    val error by vm.alertsError.collectAsStateWithLifecycle()
    val mine = remember(alerts, s) { vm.myAlerts(alerts, s).map { it.id }.toSet() }
    var filter by rememberSaveable { mutableStateOf(if (mine.isNotEmpty()) "mine" else "now") }
    var q by rememberSaveable { mutableStateOf("") }
    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(alerts, error) { refreshing = false }
    val shown = remember(alerts, filter, q, mine) {
        alerts.filter { a ->
            when (filter) {
                "mine" -> a.id in mine
                "now" -> a.active
                "later" -> !a.active
                else -> true
            } && (q.isBlank() || a.routes.any { it.equals(q.trim(), true) } || a.header.contains(q.trim(), true) ||
                  a.description.contains(q.trim(), true))
        }
    }
    Scaffold(contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
             topBar = { BackBar("Service alerts", if (alerts.isEmpty()) null else "${alerts.count { it.active }} happening now") }) { pad ->
        PullToRefreshBox(refreshing, onRefresh = { refreshing = true; vm.refreshAlerts() }, modifier = Modifier.fillMaxSize().padding(pad)) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 28.dp),
                       verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item("filters") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), singleLine = true,
                                          shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
                                          placeholder = { Text("A route (27H) or a word") },
                                          leadingIcon = { Icon(Icons.Rounded.Search, null) })
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for ((k, label) in listOf("mine" to "Yours (${mine.size})", "now" to "Now", "later" to "Upcoming", "all" to "All")) {
                                FilterChip(filter == k, { filter = k }, label = { Text(label) })
                            }
                        }
                    }
                }
                when {
                    s.apiKey.isBlank() -> item("nokey") {
                        EmptyState(Icons.Rounded.Warning, "No AT key yet", "Alerts come from AT's API: add your free key in Settings.")
                    }
                    error != null && alerts.isEmpty() -> item("err") { EmptyState(Icons.Rounded.Warning, "Couldn't get alerts", error ?: "") }
                    shown.isEmpty() -> item("none") {
                        EmptyState(Icons.Rounded.CheckCircle, if (filter == "mine") "All clear on your routes" else "Nothing here",
                                   if (filter == "mine") (if (s.kiwi) "Sweet as: nothing on your route, your stops or your favourites."
                                                          else "Nothing affects your route, stops or favourites.")
                                   else "No alerts match")
                    }
                    else -> items(shown, key = { it.id }) { a -> AlertCard(a, a.id in mine) }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AlertCard(a: Alert, yours: Boolean) {
    var open by rememberSaveable(a.id) { mutableStateOf(false) }
    val (bg, fg) = alertColors(a.kind)
    val ctx = LocalContext.current
    val nav = LocalNav.current
    Card(onClick = { open = !open }, modifier = Modifier.animateContentSize(),
         colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).background(bg, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(alertIcon(a.kind), null, tint = fg, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(a.kind.label + if (yours) " · yours" else "", style = MaterialTheme.typography.labelMedium,
                         fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text(a.whenText(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.padding(top = 8.dp))
            Text(a.header, style = MaterialTheme.typography.titleSmall, maxLines = if (open) 10 else 3, overflow = TextOverflow.Ellipsis)
            if (a.routes.isNotEmpty()) {
                FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    a.routes.take(if (open) 60 else 10).forEach { r ->
                        RouteBadge(r, modeOfRoute("", r))
                    }
                    if (!open && a.routes.size > 10) Text("+${a.routes.size - 10}", style = MaterialTheme.typography.labelMedium)
                }
            }
            if (open) {
                if (a.description.isNotBlank() && a.description != a.header) {
                    Text(a.description, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 10.dp))
                }
                Row(Modifier.padding(top = 6.dp)) {
                    if (a.routes.size == 1) TextButton(onClick = { nav.go("route/${Uri.encode(a.routes[0])}") }) { Text("Route ${a.routes[0]}") }
                    if (a.url.startsWith("http")) TextButton(onClick = {
                        try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(a.url))) } catch (_: Exception) { }
                    }) {
                        Text("More on AT's site")
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}
