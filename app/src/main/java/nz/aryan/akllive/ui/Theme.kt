package nz.aryan.akllive.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
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
    val Ferry = Color(0xFF00A3A6)
    fun line(i: Int) = Color(MapData.LINE_COLORS[i])
}

enum class ThemeMode(val label: String) { System("System"), Light("Light"), Dark("Dark") }

/** The app's colour themes, each after something Auckland. */
enum class Palette(val label: String, val blurb: String, val primary: Long, val secondary: Long, val tertiary: Long) {
    Waitemata("Waitematā", "Harbour blue, AT's own", 0xFF235EA8, 0xFF3E6A8A, 0xFF00897F),
    Pohutukawa("Pōhutukawa", "Summer crimson", 0xFFB3261E, 0xFF4E7D3A, 0xFFB07A00),
    Kawakawa("Kawakawa", "Bush green", 0xFF2E7D4F, 0xFF52655A, 0xFF3B6B8F),
    Kowhai("Kōwhai", "Spring gold", 0xFF8C6200, 0xFF6F5D3A, 0xFF4B6E3C),
    Rangitoto("Rangitoto", "Volcanic slate", 0xFF4F5B73, 0xFF6B5E57, 0xFFB5543B),
    Tui("Tūī", "Iridescent teal", 0xFF1E5E6E, 0xFF5A4E8C, 0xFF2E8B57),
}

/** Is the app dark right now (it can differ from the system). Maps follow this. */
val LocalDark = staticCompositionLocalOf { false }

private val White = Color.White
private val Black = Color.Black

/** A full Material 3 scheme from a palette's three colours. */
fun schemeFor(p: Palette, dark: Boolean, pureBlack: Boolean): ColorScheme {
    val pri = Color(p.primary)
    val sec = Color(p.secondary)
    val ter = Color(p.tertiary)
    return if (!dark) lightColorScheme(
        primary = pri, onPrimary = White,
        primaryContainer = lerp(pri, White, 0.83f), onPrimaryContainer = lerp(pri, Black, 0.62f),
        inversePrimary = lerp(pri, White, 0.5f),
        secondary = sec, onSecondary = White,
        secondaryContainer = lerp(sec, White, 0.82f), onSecondaryContainer = lerp(sec, Black, 0.62f),
        tertiary = ter, onTertiary = White,
        tertiaryContainer = lerp(ter, White, 0.82f), onTertiaryContainer = lerp(ter, Black, 0.62f),
        background = lerp(White, pri, 0.035f), onBackground = lerp(Color(0xFF1A1C22), pri, 0.18f),
        surface = lerp(White, pri, 0.035f), onSurface = lerp(Color(0xFF1A1C22), pri, 0.18f),
        surfaceVariant = lerp(White, pri, 0.11f), onSurfaceVariant = lerp(Color(0xFF45474F), pri, 0.22f),
        surfaceTint = pri,
        inverseSurface = lerp(Color(0xFF2E3036), pri, 0.15f), inverseOnSurface = lerp(White, pri, 0.06f),
        outline = lerp(Color(0xFF767880), pri, 0.18f), outlineVariant = lerp(Color(0xFFC6C7CF), pri, 0.12f),
        surfaceBright = lerp(White, pri, 0.02f), surfaceDim = lerp(White, pri, 0.13f),
        surfaceContainerLowest = White,
        surfaceContainerLow = lerp(White, pri, 0.045f),
        surfaceContainer = lerp(White, pri, 0.07f),
        surfaceContainerHigh = lerp(White, pri, 0.095f),
        surfaceContainerHighest = lerp(White, pri, 0.125f),
    ) else {
        val base = if (pureBlack) Black else lerp(Color(0xFF0C0E13), pri, 0.07f)
        darkColorScheme(
            primary = lerp(pri, White, 0.48f), onPrimary = lerp(pri, Black, 0.66f),
            primaryContainer = lerp(pri, Black, 0.3f), onPrimaryContainer = lerp(pri, White, 0.82f),
            inversePrimary = pri,
            secondary = lerp(sec, White, 0.5f), onSecondary = lerp(sec, Black, 0.66f),
            secondaryContainer = lerp(sec, Black, 0.4f), onSecondaryContainer = lerp(sec, White, 0.82f),
            tertiary = lerp(ter, White, 0.5f), onTertiary = lerp(ter, Black, 0.66f),
            tertiaryContainer = lerp(ter, Black, 0.4f), onTertiaryContainer = lerp(ter, White, 0.82f),
            background = base, onBackground = lerp(Color(0xFFE3E4EA), pri, 0.05f),
            surface = base, onSurface = lerp(Color(0xFFE3E4EA), pri, 0.05f),
            surfaceVariant = lerp(Color(0xFF2B2E37), pri, 0.14f), onSurfaceVariant = lerp(Color(0xFFC4C6D0), pri, 0.1f),
            surfaceTint = lerp(pri, White, 0.48f),
            inverseSurface = lerp(Color(0xFFE3E4EA), pri, 0.05f), inverseOnSurface = lerp(Color(0xFF2E3036), pri, 0.1f),
            outline = lerp(Color(0xFF8E9099), pri, 0.12f), outlineVariant = lerp(Color(0xFF44474F), pri, 0.12f),
            surfaceBright = lerp(Color(0xFF363A44), pri, 0.1f), surfaceDim = base,
            surfaceContainerLowest = if (pureBlack) Black else lerp(Color(0xFF07090D), pri, 0.04f),
            surfaceContainerLow = lerp(if (pureBlack) Color(0xFF0B0C0F) else Color(0xFF13161D), pri, 0.07f),
            surfaceContainer = lerp(if (pureBlack) Color(0xFF111217) else Color(0xFF181B23), pri, 0.09f),
            surfaceContainerHigh = lerp(if (pureBlack) Color(0xFF1A1C22) else Color(0xFF20242D), pri, 0.1f),
            surfaceContainerHighest = lerp(if (pureBlack) Color(0xFF23262D) else Color(0xFF2A2E38), pri, 0.1f),
        )
    }
}

private val Base = Typography()

/** Material's type scale, heavier at the top: countdowns and titles read at a glance. */
val AklType = Typography(
    displayLarge = Base.displayLarge.copy(fontWeight = FontWeight.Black, letterSpacing = (-1.5).sp),
    displayMedium = Base.displayMedium.copy(fontWeight = FontWeight.Black, letterSpacing = (-1).sp),
    displaySmall = Base.displaySmall.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp),
    headlineLarge = Base.headlineLarge.copy(fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp),
    headlineMedium = Base.headlineMedium.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.25).sp),
    headlineSmall = Base.headlineSmall.copy(fontWeight = FontWeight.Bold),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.Bold),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.Bold),
    titleSmall = Base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = Base.bodyLarge,
    bodyMedium = Base.bodyMedium,
    bodySmall = Base.bodySmall,
    labelLarge = Base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    labelMedium = Base.labelMedium.copy(fontWeight = FontWeight.SemiBold),
    labelSmall = Base.labelSmall.copy(fontWeight = FontWeight.SemiBold),
)

val AklShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun AklTheme(
    mode: ThemeMode = ThemeMode.System,
    palette: Palette = Palette.Waitemata,
    dynamic: Boolean = false,
    pureBlack: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val ctx = LocalContext.current
    val scheme = if (dynamic && Build.VERSION.SDK_INT >= 31) {
        val d = if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        if (dark && pureBlack) d.copy(background = Black, surface = Black, surfaceDim = Black,
                                      surfaceContainerLowest = Black) else d
    } else schemeFor(palette, dark, pureBlack)
    CompositionLocalProvider(LocalDark provides dark) {
        MaterialTheme(colorScheme = scheme, typography = AklType, shapes = AklShapes, content = content)
    }
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
