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
    cancelled: Boolean, dest: TextLayoutResult?, dp: Float,
) {
    val w = size.width
    val h = size.height
    val ground = h * 0.74f
    val sky = skyAt(hour)
    val night = isNight(hour)

    // sky
    drawRect(Brush.verticalGradient(listOf(sky.top, sky.bottom), 0f, ground), size = Size(w, ground))

    // sun on its arc across the day, or moon and stars
    if (hour in 6.4f..19.6f) {
        val p = (hour - 6.4f) / 13.2f
        val sx = w * (0.08f + 0.84f * p)
        val sy = ground - sin(p * PI).toFloat() * ground * 0.78f
        val low = 1f - sin(p * PI).toFloat()
        val sun = lerp(Color(0xFFFFF4D6), Color(0xFFFFB066), low)
        drawCircle(Brush.radialGradient(listOf(sun.copy(alpha = 0.55f), Color.Transparent),
                                        Offset(sx, sy), 46 * dp), 46 * dp, Offset(sx, sy))
        drawCircle(sun, 13 * dp, Offset(sx, sy))
    }
    if (night) {
        s.stars.forEachIndexed { i, (x, y, ph) ->
            val a = 0.45f + 0.45f * sin(t * (0.8f + (i % 5) * 0.3f) + ph)
            drawCircle(Color.White.copy(alpha = a), (if (i % 7 == 0) 1.4f else 0.9f) * dp,
                       Offset(x * w, y * ground))
        }
        val mc = Offset(w * 0.82f, h * 0.2f)
        drawCircle(Brush.radialGradient(listOf(Color(0x55FFF3D0), Color.Transparent), mc, 30 * dp), 30 * dp, mc)
        drawCircle(Color(0xFFFBF3DA), 10 * dp, mc)
        drawCircle(sky.top, 9 * dp, mc + Offset(4.5f * dp, -2.5f * dp))      // crescent
    }

    // clouds drift by (daytime and dusk)
    if (!night) {
        val alpha = if (hour < 7.5f || hour > 18f) 0.7f else 0.92f
        for (c in s.clouds) {
            val span = w + 160 * dp
            val cx = ((c[0] * span + t * c[3] * dp) % span) - 80 * dp
            val cy = c[1] * ground
            val k = c[2] * dp
            val col = Color.White.copy(alpha = alpha)
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

    if (s.city) drawCity(s, sky, night, t, w, h, ground, dp) else drawSuburb(s, sky, night, w, h, ground, dp)

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
        val bh = h * 0.36f
        val bl = bh * 2.75f
        val x0 = 10 * dp
        val x1 = stopX - bl - 10 * dp
        val bx = x0 + (x1 - x0) * progress.coerceIn(0f, 1f)
        drawBus(bx, ground + 2 * dp + (h - ground) * 0.12f, bl, bh, t, night, moving, cancelled, dest, dp)
    }
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
    drawRect(Color(0xFF7E8794), Offset(x - 1.2f * dp, ground - 46 * dp), Size(2.4f * dp, 48 * dp))
    // timetable case
    drawRoundRect(Color(0xFF2A3346), Offset(x + 3 * dp, ground - 30 * dp), Size(9 * dp, 13 * dp), CornerRadius(1.5f * dp))
    drawRect(Color(0xFFE9EEF5), Offset(x + 4.2f * dp, ground - 28.5f * dp), Size(6.6f * dp, 10 * dp))
    // roundel
    val c = Offset(x, ground - 54 * dp)
    drawCircle(Color.White, 11 * dp, c)
    drawCircle(Pal.AtBlue, 9.2f * dp, c)
    drawRoundRect(Color.White, c + Offset(-5 * dp, -4.5f * dp), Size(10 * dp, 7 * dp), CornerRadius(1.5f * dp))
    drawRect(Pal.AtBlue, c + Offset(-3.8f * dp, -3.3f * dp), Size(7.6f * dp, 2.6f * dp))
    drawCircle(Color.White, 1.3f * dp, c + Offset(-2.8f * dp, 3.6f * dp))
    drawCircle(Color.White, 1.3f * dp, c + Offset(2.8f * dp, 3.6f * dp))
}

/** An AT Metro bus, facing right. [baseY] is where the tyres meet the road. */
private fun DrawScope.drawBus(
    x: Float, baseY: Float, bl: Float, bh: Float, t: Float, night: Boolean,
    moving: Boolean, cancelled: Boolean, dest: TextLayoutResult?, dp: Float,
) {
    val paint = if (cancelled) Color(0xFF8C96A5) else Pal.Bus
    val dark = lerp(paint, Color.Black, 0.35f)
    val r = bh * 0.15f
    val bob = if (moving) sin(t * 7f) * 0.5f * dp else 0f
    val top = baseY - r - bh * 0.84f + bob

    // shadow
    drawOval(Color.Black.copy(alpha = 0.28f), Offset(x + bl * 0.03f, baseY - r * 0.35f), Size(bl * 0.96f, r * 0.8f))
    // headlight beam at night
    if (night && !cancelled) {
        drawPath(Path().apply {
            moveTo(x + bl, top + bh * 0.66f); lineTo(x + bl + 70 * dp, baseY - 2 * dp)
            lineTo(x + bl + 70 * dp, top + bh * 0.4f); close()
        }, Brush.horizontalGradient(listOf(Color(0x66FFF1C2), Color.Transparent), x + bl, x + bl + 70 * dp))
    }
    run {
        // body
        drawRoundRect(paint, Offset(x, top), Size(bl, bh * 0.84f), CornerRadius(bh * 0.12f))
        // white roof band and skirt
        drawRoundRect(Color.White.copy(alpha = 0.9f), Offset(x + bh * 0.05f, top), Size(bl - bh * 0.1f, bh * 0.08f),
                      CornerRadius(bh * 0.06f))
        drawRect(dark, Offset(x, top + bh * 0.66f), Size(bl, bh * 0.14f))
        // windows (lit at night)
        val glass = if (night) Color(0xFFFFE6A6) else Color(0xFFCFEFFF)
        val wy = top + bh * 0.15f
        val wh = bh * 0.34f
        val paneW = bl * 0.12f
        for (i in 0 until 5) {
            val px = x + bl * 0.05f + i * (paneW + bl * 0.02f)
            if (i == 4) continue                    // the rear door goes here
            drawRoundRect(glass, Offset(px, wy), Size(paneW, wh), CornerRadius(2.5f * dp))
            drawRect(Color.White.copy(alpha = if (night) 0.1f else 0.45f), Offset(px + paneW * 0.12f, wy + 2 * dp),
                     Size(paneW * 0.18f, wh - 4 * dp))
        }
        // doors
        for (dxf in floatArrayOf(0.05f + 4 * 0.14f, 0.74f)) {
            val dxp = x + bl * dxf
            drawRoundRect(dark, Offset(dxp, wy - 1 * dp), Size(bl * 0.085f, bh * 0.62f), CornerRadius(2 * dp))
            drawRect(glass.copy(alpha = 0.8f), Offset(dxp + 2 * dp, wy + 1 * dp), Size(bl * 0.085f - 4 * dp, bh * 0.28f))
        }
        // windscreen and destination sign
        drawRoundRect(glass, Offset(x + bl * 0.855f, top + bh * 0.13f), Size(bl * 0.13f, bh * 0.46f),
                      CornerRadius(4 * dp))
        drawRoundRect(Color(0xFF111418), Offset(x + bl * 0.84f, top + bh * 0.02f), Size(bl * 0.15f, bh * 0.1f),
                      CornerRadius(1.5f * dp))
        dest?.let {
            drawText(it, topLeft = Offset(x + bl * 0.915f - it.size.width / 2f,
                                          top + bh * 0.07f - it.size.height / 2f))
        }
        // lights
        drawRoundRect(Color(0xFFFFF4C8), Offset(x + bl - 6 * dp, top + bh * 0.62f), Size(5 * dp, 3 * dp), CornerRadius(1 * dp))
        drawRoundRect(Color(0xFFE23B3B), Offset(x + 1 * dp, top + bh * 0.6f), Size(3.5f * dp, 5 * dp), CornerRadius(1 * dp))
        // AT stripe
        drawRect(Color.White.copy(alpha = 0.85f), Offset(x + bh * 0.1f, top + bh * 0.53f), Size(bl * 0.72f, 1.6f * dp))
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
