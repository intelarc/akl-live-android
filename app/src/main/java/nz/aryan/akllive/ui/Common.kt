package nz.aryan.akllive.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.DirectionsBoat
import androidx.compose.material.icons.rounded.DirectionsBus
import androidx.compose.material.icons.rounded.Train
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nz.aryan.akllive.data.MapData
import nz.aryan.akllive.gtfs.Mode

/** Where to go next: every screen navigates through this. */
fun interface Nav {
    fun go(route: String)
}

val LocalNav = staticCompositionLocalOf { Nav { } }
val LocalBack = staticCompositionLocalOf<() -> Unit> { {} }

/** A little buzz, when the user wants them. */
class Haptics(private val on: Boolean) {
    fun tick(v: View) { if (on) v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
    fun confirm(v: View) {
        if (!on) return
        v.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS)
    }
    fun heavy(v: View) { if (on) v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
}

val LocalHaptics = staticCompositionLocalOf { Haptics(true) }

/** A route's colour: train lines in AT's line colours, ferries teal, buses the theme's. */
@Composable
fun routeColor(short: String, mode: Mode): Color {
    val li = MapData.LINE_IDS.indexOf(short)
    return when {
        li >= 0 -> Pal.line(li)
        mode == Mode.Train -> Color(0xFF8D6E63)
        mode == Mode.Ferry -> Pal.Ferry
        else -> MaterialTheme.colorScheme.primary
    }
}

fun modeOf(routeType: Int) = Mode.of(routeType)

fun modeIcon(m: Mode): ImageVector = when (m) {
    Mode.Train -> Icons.Rounded.Train
    Mode.Ferry -> Icons.Rounded.DirectionsBoat
    Mode.Walk -> Icons.AutoMirrored.Rounded.DirectionsWalk
    Mode.Bus -> Icons.Rounded.DirectionsBus
}

/** The icons for a stop's modes (1 bus, 2 train, 4 ferry). */
fun modesIcons(modes: Int): List<ImageVector> = buildList {
    if (modes and 2 != 0) add(Icons.Rounded.Train)
    if (modes and 4 != 0) add(Icons.Rounded.DirectionsBoat)
    if (modes and 1 != 0 || isEmpty()) add(Icons.Rounded.DirectionsBus)
}

/** A route's name on its colour, like AT's signs. */
@Composable
fun RouteBadge(short: String, mode: Mode = Mode.Bus, modifier: Modifier = Modifier, big: Boolean = false, icon: Boolean = false) {
    val c = routeColor(short, mode)
    val fg = if (c == MaterialTheme.colorScheme.primary) MaterialTheme.colorScheme.onPrimary else Color.White
    Row(modifier.background(c, RoundedCornerShape(if (mode == Mode.Train) 50 else 8))
            .padding(horizontal = if (big) 10.dp else 7.dp, vertical = if (big) 3.dp else 1.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (icon) {
            Icon(modeIcon(mode), null, tint = fg, modifier = Modifier.size(if (big) 18.dp else 14.dp))
            Spacer(Modifier.width(3.dp))
        }
        Text(short, color = fg, fontWeight = FontWeight.Black, fontSize = if (big) 17.sp else 13.sp, maxLines = 1)
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: () -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(start = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, text: String, modifier: Modifier = Modifier, action: @Composable () -> Unit = {}) {
    Column(modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(72.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(36.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
             textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        action()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackBar(title: String, subtitle: String? = null, scroll: TopAppBarScrollBehavior? = null,
            actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {}) {
    val back = LocalBack.current
    TopAppBar(
        title = {
            Column {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall,
                                           color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                                           overflow = TextOverflow.Ellipsis)
            }
        },
        navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
        actions = actions,
        scrollBehavior = scroll,
        colors = TopAppBarDefaults.topAppBarColors(scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer),
    )
}

/** A small round tag: "Live", "2 min late". */
@Composable
fun Tag(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.background(color.copy(alpha = 0.15f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp)) {
        Text(text, color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

/** "12 min", "1 h 5 min" */
fun durationText(secs: Long): String {
    val m = Math.round(secs / 60.0)
    return if (m < 60) "$m min" else "${m / 60} h ${m % 60} min"
}

/** "350 m", "1.2 km" */
fun distText(m: Double): String = if (m >= 1000) "%.1f km".format(m / 1000) else "${(Math.round(m / 10) * 10)} m"

val Gap = Arrangement.spacedBy(12.dp)
fun gap(d: Dp) = Arrangement.spacedBy(d)

/** "just now", "40 s ago", "3 min ago" */
fun ago(t: Long, now: Long): String {
    val s = (now - t).coerceAtLeast(0)
    return when {
        s < 5 -> "just now"
        s < 90 -> "$s s ago"
        s < 5400 -> "${s / 60} min ago"
        else -> "${s / 3600} h ago"
    }
}
