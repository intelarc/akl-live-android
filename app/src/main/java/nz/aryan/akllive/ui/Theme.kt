package nz.aryan.akllive.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nz.aryan.akllive.data.MapData

/** AT's colours, from their network map and signage. */
object Pal {
    val AtBlue = Color(0xFF235EA8)
    val Navy = Color(0xFF1A2744)
    val Ink = Color(0xFF56647E)
    val Page = Color(0xFFE2ECF7)
    val Live = Color(0xFF2FA85A)
    val Warn = Color(0xFFE08A12)
    val Late = Color(0xFFD64545)
    val Bus = Color(0xFF0096D6)
    fun line(i: Int) = Color(MapData.LINE_COLORS[i])
}

@Composable
fun AklTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) darkColorScheme(
        primary = Color(0xFF8DB8F2),
        onPrimary = Color(0xFF0B1B33),
        background = Color(0xFF0D1522),
        onBackground = Color(0xFFE3EAF5),
        surface = Color(0xFF16213A),
        onSurface = Color(0xFFE3EAF5),
        surfaceVariant = Color(0xFF1E2B47),
        onSurfaceVariant = Color(0xFFA9B6CC),
        secondaryContainer = Color(0xFF26406B),
        onSecondaryContainer = Color(0xFFE3EAF5),
    ) else lightColorScheme(
        primary = Pal.AtBlue,
        onPrimary = Color.White,
        background = Pal.Page,
        onBackground = Pal.Navy,
        surface = Color.White,
        onSurface = Pal.Navy,
        surfaceVariant = Color(0xFFEEF3FA),
        onSurfaceVariant = Pal.Ink,
        secondaryContainer = Color(0xFFD3E2F7),
        onSecondaryContainer = Pal.Navy,
    )
    MaterialTheme(colorScheme = scheme, content = content)
}

/** A line's code on its colour, like AT's signage ("E-W"). */
@Composable
fun LinePill(line: Int, modifier: Modifier = Modifier, big: Boolean = false) {
    Box(
        modifier
            .background(Pal.line(line), RoundedCornerShape(50))
            .padding(horizontal = if (big) 10.dp else 7.dp, vertical = if (big) 3.dp else 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(MapData.LINE_IDS[line], color = Color.White, fontWeight = FontWeight.Bold,
             fontSize = if (big) 15.sp else 12.sp)
    }
}

/** "3 min", "Due", "1 h 5 min" */
fun countdown(secs: Long): String {
    val m = secs / 60
    return when {
        secs < 45 -> "Due"
        m < 60 -> "$m min"
        else -> "${m / 60} h ${m % 60} min"
    }
}

/** "On time", "2 min late", "1 min early" -- and the colour to show it in. */
fun punctuality(delay: Int?): Pair<String, Color> {
    if (delay == null) return "Scheduled" to Pal.Ink
    val m = Math.round(delay / 60f)
    return when {
        m >= 5 -> "$m min late" to Pal.Late
        m >= 2 -> "$m min late" to Pal.Warn
        m <= -2 -> "${-m} min early" to Pal.Warn
        else -> "On time" to Pal.Live
    }
}

object AklIcons {
    val Settings = Icons.Filled.Settings
    val Live = Icons.Filled.Place

    /** A bus side-on, for the fleet list. */
    val Fleet: ImageVector = ImageVector.Builder("fleet", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
            moveTo(3f, 5f); lineTo(18.5f, 5f)
            curveTo(19.9f, 5f, 20.9f, 5.9f, 21.2f, 7.2f); lineTo(22f, 11f); lineTo(22f, 16f)
            curveTo(22f, 16.6f, 21.6f, 17f, 21f, 17f); lineTo(3f, 17f)
            curveTo(2.4f, 17f, 2f, 16.6f, 2f, 16f); lineTo(2f, 6f)
            curveTo(2f, 5.4f, 2.4f, 5f, 3f, 5f); close()
            moveTo(4f, 7.5f); lineTo(8f, 7.5f); lineTo(8f, 11f); lineTo(4f, 11f); close()
            moveTo(9.5f, 7.5f); lineTo(13.5f, 7.5f); lineTo(13.5f, 11f); lineTo(9.5f, 11f); close()
            moveTo(15f, 7.5f); lineTo(19.4f, 7.5f); lineTo(20.2f, 11f); lineTo(15f, 11f); close()
        }
        path(fill = SolidColor(Color.Black)) {
            moveTo(4.5f, 18f); arcToRelative(2.2f, 2.2f, 0f, true, true, 4.4f, 0f)
            arcToRelative(2.2f, 2.2f, 0f, true, true, -4.4f, 0f); close()
            moveTo(15.1f, 18f); arcToRelative(2.2f, 2.2f, 0f, true, true, 4.4f, 0f)
            arcToRelative(2.2f, 2.2f, 0f, true, true, -4.4f, 0f); close()
        }
    }.build()

    // single even-odd paths: windows are holes, so Icon's tint keeps them see-through
    val Bus: ImageVector = ImageVector.Builder("bus", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
            moveTo(6f, 3f); lineTo(18f, 3f)
            curveTo(19.7f, 3f, 21f, 4.3f, 21f, 6f); lineTo(21f, 17f)
            curveTo(21f, 17.6f, 20.6f, 18f, 20f, 18f); lineTo(4f, 18f)
            curveTo(3.4f, 18f, 3f, 17.6f, 3f, 17f); lineTo(3f, 6f)
            curveTo(3f, 4.3f, 4.3f, 3f, 6f, 3f); close()
            moveTo(5f, 6f); lineTo(19f, 6f); lineTo(19f, 11f); lineTo(5f, 11f); close()
            moveTo(6f, 14f); lineTo(8f, 14f); lineTo(8f, 16f); lineTo(6f, 16f); close()
            moveTo(16f, 14f); lineTo(18f, 14f); lineTo(18f, 16f); lineTo(16f, 16f); close()
        }
        path(fill = SolidColor(Color.Black)) {
            moveTo(5f, 18.5f); lineTo(9f, 18.5f); lineTo(9f, 21f); lineTo(5f, 21f); close()
            moveTo(15f, 18.5f); lineTo(19f, 18.5f); lineTo(19f, 21f); lineTo(15f, 21f); close()
        }
    }.build()

    val Train: ImageVector = ImageVector.Builder("train", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
            moveTo(8f, 2f); lineTo(16f, 2f)
            curveTo(18.2f, 2f, 20f, 3.8f, 20f, 6f); lineTo(20f, 15f)
            curveTo(20f, 16.7f, 18.7f, 18f, 17f, 18f); lineTo(7f, 18f)
            curveTo(5.3f, 18f, 4f, 16.7f, 4f, 15f); lineTo(4f, 6f)
            curveTo(4f, 3.8f, 5.8f, 2f, 8f, 2f); close()
            moveTo(6.5f, 5.5f); lineTo(17.5f, 5.5f); lineTo(17.5f, 10.5f); lineTo(6.5f, 10.5f); close()
            moveTo(7f, 13f); lineTo(9f, 13f); lineTo(9f, 15f); lineTo(7f, 15f); close()
            moveTo(15f, 13f); lineTo(17f, 13f); lineTo(17f, 15f); lineTo(15f, 15f); close()
        }
        path(fill = SolidColor(Color.Black)) {
            moveTo(7f, 19f); lineTo(9.5f, 19f); lineTo(7.5f, 22f); lineTo(5f, 22f); close()
            moveTo(14.5f, 19f); lineTo(17f, 19f); lineTo(19f, 22f); lineTo(16.5f, 22f); close()
        }
    }.build()
}
