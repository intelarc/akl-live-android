package nz.aryan.akllive.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import nz.aryan.akllive.data.Weather
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** The fixed scenery for one lane: built once, drawn every frame. */
class Scenery(val city: Boolean, seed: Int) {
    class Building(val x: Float, val w: Float, val h: Float, val windows: List<Pair<Float, Float>>, val litMask: Long)
    class House(val x: Float, val w: Float, val h: Float, val roof: Float, val lit: Boolean)
    class Tree(val x: Float, val r: Float, val blossoms: List<Pair<Float, Float>>)

    val buildings = ArrayList<Building>()
    val houses = ArrayList<House>()
    val trees = ArrayList<Tree>()
    val stars: List<Triple<Float, Float, Float>>
    val clouds: List<FloatArray>              // offset, y, scale, speed
    val drops: List<FloatArray>               // x, phase, speed

    init {
        val r = Random(seed * 7919 + 17)
        stars = List(70) { Triple(r.nextFloat(), r.nextFloat() * 0.62f, r.nextFloat() * 6.28f) }
        clouds = List(5) { floatArrayOf(r.nextFloat(), 0.08f + r.nextFloat() * 0.28f,
                                        0.7f + r.nextFloat() * 0.6f, 6f + r.nextFloat() * 10f) }
        var x = 0.01f
        if (city) {
            while (x < 0.80f) {
                val w = 0.035f + r.nextFloat() * 0.05f
                val h = 0.18f + r.nextFloat() * 0.34f
                val win = ArrayList<Pair<Float, Float>>()
                var wy = 0.06f
                while (wy < 0.92f) {
                    var wx = 0.18f
                    while (wx < 0.82f) { win += wx to wy; wx += 0.28f }
                    wy += 0.12f
                }
                buildings += Building(x, w, h, win, r.nextLong())
                x += w + 0.004f + r.nextFloat() * 0.012f
            }
        } else {
            while (x < 0.80f) {
                if (r.nextFloat() < 0.58f) {
                    val w = 0.045f + r.nextFloat() * 0.03f
                    houses += House(x, w, 0.09f + r.nextFloat() * 0.05f, 0.05f + r.nextFloat() * 0.03f,
                                    r.nextFloat() < 0.75f)
                    x += w + 0.01f + r.nextFloat() * 0.025f
                } else {
                    val rad = 0.018f + r.nextFloat() * 0.012f
                    trees += Tree(x + rad, rad, List(7) { r.nextFloat() * 6.28f to r.nextFloat() })
                    x += rad * 2 + 0.008f + r.nextFloat() * 0.02f
                }
            }
        }
        drops = List(120) { floatArrayOf(r.nextFloat(), r.nextFloat(), r.nextFloat()) }
    }
}

// (hour, sky top, sky bottom, far hills, near scenery)
private class SkyKey(val h: Float, val top: Long, val bottom: Long, val hills: Long, val near: Long)

private val SKY = listOf(
    SkyKey(0f, 0xFF0A122C, 0xFF1E2C54, 0xFF1A2440, 0xFF243050),
    SkyKey(5.3f, 0xFF141E46, 0xFF464C78, 0xFF283050, 0xFF323C5C),
    SkyKey(6.6f, 0xFF6E96D2, 0xFFFFC496, 0xFF9696AA, 0xFF78809C),
    SkyKey(8.5f, 0xFF5FA4EC, 0xFFD2EAFC, 0xFF9CC2A4, 0xFFA0B0C8),
    SkyKey(16.8f, 0xFF5FA4EC, 0xFFD6ECFC, 0xFF9CC2A4, 0xFFA0B0C8),
    SkyKey(18.4f, 0xFF5A6EBE, 0xFFFFAA78, 0xFF8C8296, 0xFF6E6E8C),
    SkyKey(19.6f, 0xFF1E285A, 0xFF6E5078, 0xFF323454, 0xFF3C3E60),
    SkyKey(21f, 0xFF0A122C, 0xFF1E2C54, 0xFF1A2440, 0xFF243050),
    SkyKey(24f, 0xFF0A122C, 0xFF1E2C54, 0xFF1A2440, 0xFF243050),
)

class Sky(val top: Color, val bottom: Color, val hills: Color, val near: Color)

fun skyAt(hour: Float): Sky {
    val h = ((hour % 24f) + 24f) % 24f
    for (i in 0 until SKY.size - 1) {
        val a = SKY[i]
        val b = SKY[i + 1]
        if (h >= a.h && h <= b.h) {
            val f = (h - a.h) / (b.h - a.h)
            return Sky(lerp(Color(a.top), Color(b.top), f), lerp(Color(a.bottom), Color(b.bottom), f),
                       lerp(Color(a.hills), Color(b.hills), f), lerp(Color(a.near), Color(b.near), f))
        }
    }
    val a = SKY[0]
    return Sky(Color(a.top), Color(a.bottom), Color(a.hills), Color(a.near))
}

fun isNight(hour: Float) = hour < 6.3f || hour > 19.7f

private val Lit = Color(0xFFFFD98A)

/**
 * One lane's live scene. [progress] 0..1 is how far along the bus is (0 = 20
 * minutes away, 1 = at the stop); null means no bus is coming soon.
 */
fun DrawScope.drawBusScene(
    s: Scenery, hour: Float, t: Float, progress: Float?, moving: Boolean,
    cancelled: Boolean, dest: TextLayoutResult?, dp: Float, look: BusLook = BusLook(), wx: Weather? = null,
) {
    val w = size.width
    val h = size.height
    val ground = h * 0.74f
    val night = isNight(hour)
    // the real weather: cloud cover greys the sky, rain darkens it further
    val cover = (wx?.cloudCover ?: 30) / 100f
    val rain = wx?.rain ?: 0
    val gloom = (((cover - 0.5f) / 0.5f).coerceIn(0f, 1f) * 0.5f + rain * 0.08f).coerceAtMost(0.7f)
    val grey = if (night) Color(0xFF141922) else Color(0xFF8C95A5)
    val clear = skyAt(hour)
    val sky = Sky(lerp(clear.top, grey, gloom), lerp(clear.bottom, lerp(grey, Color.White, 0.25f), gloom),
                  lerp(clear.hills, grey, gloom * 0.5f), lerp(clear.near, grey, gloom * 0.4f))
    val sunShow = (1f - gloom * 1.7f).coerceIn(0f, 1f)

    // sky
    drawRect(Brush.verticalGradient(listOf(sky.top, sky.bottom), 0f, ground), size = Size(w, ground))

    // sun on its arc across the day, or moon and stars
    if (hour in 6.4f..19.6f) {
        val p = (hour - 6.4f) / 13.2f
        val sx = w * (0.08f + 0.84f * p)
        val sy = ground - sin(p * PI).toFloat() * ground * 0.78f
        val low = 1f - sin(p * PI).toFloat()
        val sun = lerp(Color(0xFFFFF4D6), Color(0xFFFFB066), low)
        drawCircle(Brush.radialGradient(listOf(sun.copy(alpha = 0.55f * sunShow), Color.Transparent),
                                        Offset(sx, sy), 46 * dp), 46 * dp, Offset(sx, sy))
        drawCircle(sun.copy(alpha = sunShow), 13 * dp, Offset(sx, sy))
    }
    if (night && sunShow > 0f) {
        s.stars.forEachIndexed { i, (x, y, ph) ->
            val a = (0.45f + 0.45f * sin(t * (0.8f + (i % 5) * 0.3f) + ph)) * (1f - cover).coerceIn(0f, 1f)
            drawCircle(Color.White.copy(alpha = a), (if (i % 7 == 0) 1.4f else 0.9f) * dp,
                       Offset(x * w, y * ground))
        }
        val mc = Offset(w * 0.82f, h * 0.2f)
        drawCircle(Brush.radialGradient(listOf(Color(0x55FFF3D0).copy(alpha = 0.33f * sunShow), Color.Transparent),
                                        mc, 30 * dp), 30 * dp, mc)
        drawCircle(Color(0xFFFBF3DA).copy(alpha = sunShow), 10 * dp, mc)
        drawCircle(sky.top, 9 * dp, mc + Offset(4.5f * dp, -2.5f * dp))      // crescent
    }

    // clouds drift by: as many as the real cover, grey when it's raining, dark at night
    val clouds = if (wx == null) (if (night) 0 else 5) else Math.round(cover * 5.4f).coerceIn(0, 5)
    val cloudCol = when {
        night -> Color(0xFF2A3246).copy(alpha = 0.85f)
        rain > 0 || gloom > 0.3f -> lerp(Color.White, Color(0xFF8E98A8), 0.35f + gloom * 0.5f).copy(alpha = 0.95f)
        else -> Color.White.copy(alpha = if (hour < 7.5f || hour > 18f) 0.7f else 0.92f)
    }
    run {
        for (c in s.clouds.take(clouds)) {
            val span = w + 160 * dp
            val cx = ((c[0] * span + t * c[3] * dp) % span) - 80 * dp
            val cy = c[1] * ground
            val k = c[2] * dp * (1f + gloom * 0.6f)
            val col = cloudCol
            drawCircle(col, 11 * k, Offset(cx, cy + 3 * k))
            drawCircle(col, 15 * k, Offset(cx + 14 * k, cy - 2 * k))
            drawCircle(col, 11 * k, Offset(cx + 30 * k, cy + 3 * k))
            drawRoundRect(col, Offset(cx - 4 * k, cy + 2 * k), Size(40 * k, 12 * k), CornerRadius(6 * k))
        }
    }

    // far hills (the Waitakeres, more or less)
    val hills = Path().apply {
        moveTo(0f, ground)
        var x = 0f
        while (x <= w + 8) {
            val f = x / w
            lineTo(x, ground - h * (0.16f + 0.06f * sin(f * 9.3f + 1.3f) + 0.03f * sin(f * 23f)))
            x += 6f
        }
        lineTo(w, ground); close()
    }
    drawPath(hills, sky.hills)
    // a landmark on the horizon: Rangitoto out past the city, Maungakiekie over the suburbs
    val haze = lerp(sky.hills, sky.bottom, 0.18f)
    if (s.city) drawRangitoto(haze, w, h, ground) else drawMaungakiekie(haze, night, w, h, ground, dp)

    if (s.city) drawCity(s, sky, night, t, w, h, ground, dp) else drawSuburb(s, sky, night, w, h, ground, dp)

    if (wx?.foggy == true) {
        drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = if (night) 0.08f else 0.2f),
                                               Color.White.copy(alpha = if (night) 0.22f else 0.5f)), 0f, ground),
                 size = Size(w, ground))
    }

    // road
    val road = if (night) Color(0xFF22262F) else Color(0xFF3B414D)
    drawRect(Color(0xFF8A919C).copy(alpha = if (night) 0.5f else 1f), Offset(0f, ground), Size(w, 3 * dp))
    drawRect(road, Offset(0f, ground + 3 * dp), Size(w, h - ground - 3 * dp))
    val laneY = ground + (h - ground) * 0.62f
    var dx = 0f
    while (dx < w) {
        drawRect(if (night) Color(0xFFB8AC7A) else Color(0xFFF0E3A8), Offset(dx, laneY), Size(18 * dp, 2.2f * dp))
        dx += 34 * dp
    }

    // the AT bus stop
    val stopX = w - 40 * dp
    drawStop(stopX, ground, dp)

    // the bus
    if (progress != null) {
        // small enough to have room to travel: 20 minutes out it's half off
        // the left edge, at 0 it's pulled up at the stop
        val bh = h * 0.25f
        val bl = bh * 2.75f
        val x0 = -bl * 0.5f
        val x1 = stopX - bl - 8 * dp
        val bx = x0 + (x1 - x0) * progress.coerceIn(0f, 1f)
        drawBus(bx, ground + 2 * dp + (h - ground) * 0.12f, bl, bh, t, night, moving, cancelled, dest, dp, look)
    }

    // rain, falling on a slant
    if (rain > 0) {
        val n = intArrayOf(0, 40, 80, 120)[rain]
        val len = (if (rain == 1) 6f else 11f) * dp
        val col = Color(0xFFD6E6FF).copy(alpha = if (rain == 1) 0.35f else 0.5f)
        for (i in 0 until n) {
            val d = s.drops[i]
            val y = ((d[1] + t * (1.1f + d[2] * 0.8f)) % 1f) * (h + len) - len
            val x = d[0] * (w + 30 * dp) - y * 0.2f
            drawLine(col, Offset(x, y), Offset(x - len * 0.2f, y + len), strokeWidth = 1.1f * dp, cap = StrokeCap.Round)
        }
    }
    if (wx?.thunder == true) {
        val ph = t % 9f
        if (ph < 0.1f || ph in 0.22f..0.3f) drawRect(Color.White.copy(alpha = 0.35f))
    }
}

/** Rangitoto's low, even shield, with its summit bump, as seen from the city. */
private fun DrawScope.drawRangitoto(col: Color, w: Float, h: Float, ground: Float) {
    val base = ground - h * 0.11f
    val rise = h * 0.2f
    val prof = floatArrayOf(0.56f, 0f, 0.66f, 0.3f, 0.73f, 0.66f, 0.77f, 0.9f, 0.795f, 1f, 0.81f, 0.96f,
                            0.825f, 1f, 0.85f, 0.9f, 0.89f, 0.66f, 0.96f, 0.3f, 1.04f, 0f)
    val p = Path().apply {
        moveTo(prof[0] * w, ground)
        for (i in prof.indices step 2) lineTo(prof[i] * w, base - prof[i + 1] * rise)
        lineTo(prof[prof.size - 2] * w, ground); close()
    }
    drawPath(p, col)
}

/** Maungakiekie / One Tree Hill, with the obelisk on top. */
private fun DrawScope.drawMaungakiekie(col: Color, night: Boolean, w: Float, h: Float, ground: Float, dp: Float) {
    val cx = w * 0.36f
    val top = ground - h * 0.33f
    val p = Path().apply {
        moveTo(cx - w * 0.24f, ground)
        cubicTo(cx - w * 0.14f, ground - h * 0.12f, cx - w * 0.09f, top, cx, top)
        cubicTo(cx + w * 0.07f, top, cx + w * 0.12f, ground - h * 0.16f, cx + w * 0.26f, ground)
        close()
    }
    drawPath(p, col)
    val ob = Path().apply {
        moveTo(cx - 2.6f * dp, top + 1 * dp); lineTo(cx - 1f * dp, top - 22 * dp)
        lineTo(cx, top - 25 * dp); lineTo(cx + 1f * dp, top - 22 * dp); lineTo(cx + 2.6f * dp, top + 1 * dp); close()
    }
    drawPath(ob, lerp(col, if (night) Color.Black else Color.White, 0.25f))
}

private fun DrawScope.drawCity(s: Scenery, sky: Sky, night: Boolean, t: Float, w: Float, h: Float,
                               ground: Float, dp: Float) {
    val body = sky.near
    val face = lerp(body, Color.White, 0.08f)
    for (b in s.buildings) {
        val bx = b.x * w
        val bw = b.w * w
        val bh = b.h * h
        drawRect(body, Offset(bx, ground - bh), Size(bw, bh))
        drawRect(face, Offset(bx, ground - bh), Size(bw * 0.35f, bh))
        b.windows.forEachIndexed { i, (fx, fy) ->
            val on = night && (b.litMask shr (i % 63)) and 1L == 1L
            val c = if (on) Lit else lerp(body, sky.bottom, 0.35f).copy(alpha = if (night) 0.6f else 0.7f)
            drawRect(c, Offset(bx + fx * bw - 1.1f * dp, ground - bh + fy * bh), Size(2.2f * dp, 2.6f * dp))
        }
    }
    // the Sky Tower
    val tx = w * 0.43f
    val top = ground - h * 0.66f
    val shaft = Path().apply {
        moveTo(tx - 5 * dp, ground); lineTo(tx - 2.2f * dp, top + 18 * dp)
        lineTo(tx + 2.2f * dp, top + 18 * dp); lineTo(tx + 5 * dp, ground); close()
    }
    drawPath(shaft, body)
    drawRoundRect(body, Offset(tx - 9 * dp, top + 10 * dp), Size(18 * dp, 9 * dp), CornerRadius(4 * dp))
    drawRoundRect(body, Offset(tx - 5.5f * dp, top + 4 * dp), Size(11 * dp, 6 * dp), CornerRadius(3 * dp))
    drawRect(body, Offset(tx - 0.9f * dp, top - 16 * dp), Size(1.8f * dp, 22 * dp))
    if (night) {
        drawRect(Lit.copy(alpha = 0.9f), Offset(tx - 8 * dp, top + 13.5f * dp), Size(16 * dp, 1.5f * dp))
        val blink = (sin(t * 3.0) > 0).let { if (it) 1f else 0.25f }
        drawCircle(Color(0xFFFF4A4A).copy(alpha = blink), 1.8f * dp, Offset(tx, top - 16 * dp))
    }
}

private fun DrawScope.drawSuburb(s: Scenery, sky: Sky, night: Boolean, w: Float, h: Float,
                                 ground: Float, dp: Float) {
    val wall = lerp(sky.near, Color.White, if (night) 0.02f else 0.25f)
    val roof = lerp(sky.near, Color(0xFF7A2E2A), if (night) 0.2f else 0.45f)
    for (hs in s.houses) {
        val x = hs.x * w
        val hw = hs.w * w
        val hh = hs.h * h
        drawRect(wall, Offset(x, ground - hh), Size(hw, hh))
        drawPath(Path().apply {
            moveTo(x - 2 * dp, ground - hh); lineTo(x + hw / 2, ground - hh - hs.roof * h)
            lineTo(x + hw + 2 * dp, ground - hh); close()
        }, roof)
        val win = if (night && hs.lit) Lit else lerp(sky.bottom, Color.White, 0.3f).copy(alpha = 0.8f)
        drawRect(win, Offset(x + hw * 0.18f, ground - hh * 0.72f), Size(hw * 0.22f, hh * 0.3f))
        drawRect(lerp(wall, Color.Black, 0.35f), Offset(x + hw * 0.6f, ground - hh * 0.6f), Size(hw * 0.2f, hh * 0.6f))
    }
    val leaf = lerp(Color(0xFF3F7A48), sky.near, if (night) 0.75f else 0.3f)
    val trunk = lerp(Color(0xFF5A4030), sky.near, if (night) 0.7f else 0.2f)
    for (tr in s.trees) {        // pōhutukawa, in flower
        val x = tr.x * w
        val r = tr.r * w
        drawRect(trunk, Offset(x - 1.4f * dp, ground - r * 1.2f), Size(2.8f * dp, r * 1.2f))
        drawCircle(leaf, r, Offset(x, ground - r * 1.5f))
        drawCircle(leaf, r * 0.75f, Offset(x - r * 0.7f, ground - r * 1.1f))
        drawCircle(leaf, r * 0.75f, Offset(x + r * 0.7f, ground - r * 1.1f))
        if (!night) for ((a, d) in tr.blossoms) {
            drawCircle(Color(0xFFD7263D), 1.3f * dp,
                       Offset(x + cos(a) * r * d * 0.9f, ground - r * 1.4f + sin(a) * r * d * 0.8f))
        }
    }
}

private fun DrawScope.drawStop(x: Float, ground: Float, dp: Float) {
    drawRect(Color(0xFF7E8794), Offset(x - 1.2f * dp, ground - 36 * dp), Size(2.4f * dp, 38 * dp))
    // timetable case
    drawRoundRect(Color(0xFF2A3346), Offset(x + 3 * dp, ground - 30 * dp), Size(9 * dp, 13 * dp), CornerRadius(1.5f * dp))
    drawRect(Color(0xFFE9EEF5), Offset(x + 4.2f * dp, ground - 28.5f * dp), Size(6.6f * dp, 10 * dp))
    // roundel
    val c = Offset(x, ground - 44 * dp)      // low enough to clear the countdown box
    drawCircle(Color.White, 11 * dp, c)
    drawCircle(Pal.AtBlue, 9.2f * dp, c)
    drawRoundRect(Color.White, c + Offset(-5 * dp, -4.5f * dp), Size(10 * dp, 7 * dp), CornerRadius(1.5f * dp))
    drawRect(Pal.AtBlue, c + Offset(-3.8f * dp, -3.3f * dp), Size(7.6f * dp, 2.6f * dp))
    drawCircle(Color.White, 1.3f * dp, c + Offset(-2.8f * dp, 3.6f * dp))
    drawCircle(Color.White, 1.3f * dp, c + Offset(2.8f * dp, 3.6f * dp))
}

/** Which bus to draw: single or double deck, diesel or electric. */
class BusLook(val doubleDeck: Boolean = false, val electric: Boolean = false)

/** An AT Metro bus, facing right. [baseY] is where the tyres meet the road; [bh] is a single deck's height. */
internal fun DrawScope.drawBus(
    x: Float, baseY: Float, bl: Float, bh: Float, t: Float, night: Boolean,
    moving: Boolean, cancelled: Boolean, dest: TextLayoutResult?, dp: Float, look: BusLook = BusLook(),
) {
    val paint = if (cancelled) Color(0xFF8C96A5) else Pal.Bus
    val dark = lerp(paint, Color.Black, 0.35f)
    val r = bh * 0.15f
    val bob = if (moving) sin(t * 7f) * 0.5f * dp else 0f
    val deck = bh * 0.84f
    val bodyH = if (look.doubleDeck) deck * 1.72f else deck
    val top = baseY - r - bodyH + bob
    val low = top + bodyH - deck                      // top of the lower deck
    val glass = if (night) Color(0xFFFFE6A6) else Color(0xFFCFEFFF)

    // shadow
    drawOval(Color.Black.copy(alpha = 0.28f), Offset(x + bl * 0.03f, baseY - r * 0.35f), Size(bl * 0.96f, r * 0.8f))
    // headlight beam at night
    if (night && !cancelled) {
        drawPath(Path().apply {
            moveTo(x + bl, low + deck * 0.78f); lineTo(x + bl + 70 * dp, baseY - 2 * dp)
            lineTo(x + bl + 70 * dp, low + deck * 0.47f); close()
        }, Brush.horizontalGradient(listOf(Color(0x66FFF1C2), Color.Transparent), x + bl, x + bl + 70 * dp))
    }
    // diesel exhaust, puffing out the back while it drives
    if (!look.electric && moving && !cancelled) {
        for (k in 0 until 3) {
            val ph = (t * 0.9f + k / 3f) % 1f
            drawCircle(Color(0xFF9AA3AE).copy(alpha = 0.32f * (1 - ph)), (2 + ph * 4.5f) * dp,
                       Offset(x - 2 * dp - ph * 20 * dp, baseY - r * 0.9f - ph * 7 * dp))
        }
    }
    // body, lit from above
    drawRoundRect(Brush.verticalGradient(listOf(lerp(paint, Color.White, 0.16f), paint, lerp(paint, Color.Black, 0.1f)),
                                         top, top + bodyH),
                  Offset(x, top), Size(bl, bodyH), CornerRadius(bh * 0.12f))
    // white roof band and skirt
    drawRoundRect(Color.White.copy(alpha = 0.9f), Offset(x + bh * 0.05f, top), Size(bl - bh * 0.1f, bh * 0.08f),
                  CornerRadius(bh * 0.06f))
    drawRect(dark, Offset(x, low + deck * 0.786f), Size(bl, deck * 0.167f))
    // windows (lit at night): the upper deck runs right to the front
    val paneW = bl * 0.12f
    val decks = if (look.doubleDeck) listOf(top + deck * 0.06f to true, low to false) else listOf(low to false)
    for ((dy, upper) in decks) {
        val wy = dy + deck * 0.18f
        val wh = deck * (if (upper) 0.46f else 0.405f)
        for (i in 0 until (if (upper) 6 else 5)) {
            if (!upper && i == 4) continue                // the rear door goes here
            val px = x + bl * 0.05f + i * (paneW + bl * 0.02f)
            val pw = if (upper && i == 5) bl * 0.2f else paneW
            drawRoundRect(glass, Offset(px, wy), Size(pw, wh), CornerRadius(2.5f * dp))
            drawRect(Color.White.copy(alpha = if (night) 0.1f else 0.45f), Offset(px + paneW * 0.12f, wy + 2 * dp),
                     Size(paneW * 0.18f, wh - 4 * dp))
        }
    }
    // doors
    val wy = low + deck * 0.18f
    for (dxf in floatArrayOf(0.05f + 4 * 0.14f, 0.74f)) {
        val dxp = x + bl * dxf
        drawRoundRect(dark, Offset(dxp, wy - 1 * dp), Size(bl * 0.085f, deck * 0.74f), CornerRadius(2 * dp))
        drawRect(glass.copy(alpha = 0.8f), Offset(dxp + 2 * dp, wy + 1 * dp), Size(bl * 0.085f - 4 * dp, deck * 0.33f))
    }
    // windscreen and destination sign (between the decks on a double-decker)
    drawRoundRect(glass, Offset(x + bl * 0.855f, low + deck * 0.155f), Size(bl * 0.13f, deck * 0.55f),
                  CornerRadius(4 * dp))
    val signY = if (look.doubleDeck) low - deck * 0.02f else top + bh * 0.02f
    drawRoundRect(Color(0xFF111418), Offset(x + bl * 0.84f, signY), Size(bl * 0.15f, bh * 0.1f),
                  CornerRadius(1.5f * dp))
    dest?.let {
        drawText(it, topLeft = Offset(x + bl * 0.915f - it.size.width / 2f, signY + bh * 0.05f - it.size.height / 2f))
    }
    // lights
    drawRoundRect(Color(0xFFFFF4C8), Offset(x + bl - 6 * dp, low + deck * 0.738f), Size(5 * dp, 3 * dp), CornerRadius(1 * dp))
    drawRoundRect(Color(0xFFE23B3B), Offset(x + 1 * dp, low + deck * 0.714f), Size(3.5f * dp, 5 * dp), CornerRadius(1 * dp))
    // AT stripe, and a lightning bolt on the electrics
    drawRect(Color.White.copy(alpha = 0.85f), Offset(x + bh * 0.1f, low + deck * 0.63f), Size(bl * 0.72f, 1.6f * dp))
    if (look.electric && !cancelled) {
        val bx = x + bl * 0.32f
        val by = low + deck * 0.635f
        val u = deck * 0.075f
        drawPath(Path().apply {
            moveTo(bx + 1.2f * u, by - 1.6f * u); lineTo(bx - 0.4f * u, by + 0.2f * u); lineTo(bx + 0.5f * u, by + 0.2f * u)
            lineTo(bx - 0.6f * u, by + 2f * u); lineTo(bx + 1.3f * u, by - 0.3f * u); lineTo(bx + 0.4f * u, by - 0.3f * u); close()
        }, Color(0xFF7CFFB2))
    }
    // wheels
    for (fx in floatArrayOf(0.2f, 0.8f)) {
        val c = Offset(x + bl * fx, baseY - r)
        drawCircle(dark, r * 1.25f, c + Offset(0f, -r * 0.1f))
        drawCircle(Color(0xFF1B1F27), r, c)
        drawCircle(Color(0xFF9AA3AE), r * 0.52f, c)
        val spin = if (moving) t * 360f * 0.9f else 0f
        rotate(spin, c) {
            for (k in 0 until 5) {
                val a = k * 2 * PI / 5
                drawLine(Color(0xFF5B636E), c, c + Offset(cos(a).toFloat() * r * 0.5f, sin(a).toFloat() * r * 0.5f),
                         strokeWidth = 1.2f * dp, cap = StrokeCap.Round)
            }
        }
        drawCircle(Color(0xFF5B636E), r * 0.14f, c)
    }
    if (cancelled) {
        drawLine(Pal.Late, Offset(x, top), Offset(x + bl, baseY - r), strokeWidth = 3 * dp, cap = StrokeCap.Round)
    }
}
