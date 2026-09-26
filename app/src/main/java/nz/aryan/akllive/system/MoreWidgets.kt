package nz.aryan.akllive.system

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import nz.aryan.akllive.MainActivity
import nz.aryan.akllive.Prefs
import nz.aryan.akllive.R
import nz.aryan.akllive.data.AtApi
import nz.aryan.akllive.data.MapData
import nz.aryan.akllive.data.Nz
import nz.aryan.akllive.data.StopDeparture
import nz.aryan.akllive.data.StopRepo
import nz.aryan.akllive.data.Train
import nz.aryan.akllive.data.TrainRepo
import nz.aryan.akllive.ui.TrainArt

private fun open(ctx: Context, link: String) =
    actionStartActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link), ctx, MainActivity::class.java))

// ======================= search =======================

/** A search bar on the home screen: straight into search, or plan a trip. */
class SearchWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { GlanceTheme { Body(context) } }
    }

    @Composable
    private fun Body(ctx: Context) {
        val wide = LocalSize.current.width > 250.dp
        Row(GlanceModifier.fillMaxSize().cornerRadius(28.dp).background(GlanceTheme.colors.widgetBackground).padding(6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Row(GlanceModifier.defaultWeight().height(44.dp).cornerRadius(22.dp).background(GlanceTheme.colors.secondaryContainer)
                    .padding(horizontal = 14.dp).clickable(open(ctx, "akllive://search")),
                verticalAlignment = Alignment.CenterVertically) {
                Image(ImageProvider(R.drawable.ic_sc_search), null, GlanceModifier.size(22.dp))
                Spacer(GlanceModifier.width(10.dp))
                Text(if (wide) "Stops, places, routes, buses…" else "Search", maxLines = 1,
                     style = TextStyle(color = GlanceTheme.colors.onSecondaryContainer, fontSize = 14.sp))
            }
            Spacer(GlanceModifier.width(6.dp))
            Box(GlanceModifier.size(44.dp).cornerRadius(22.dp).background(GlanceTheme.colors.primary).clickable(open(ctx, "akllive://plan")),
                contentAlignment = Alignment.Center) {
                Image(ImageProvider(R.drawable.ic_sc_plan), "Plan a trip", GlanceModifier.size(26.dp))
            }
        }
    }
}

class SearchWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SearchWidget()
}

// ======================= favourite stops =======================

/** Your favourite stops, each with its next departures. */
class FavouritesWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val s = Prefs(context).load()
        val favs = s.favs.take(3)
        val repo = StopRepo(AtApi { s.apiKey })
        val deps = if (s.apiKey.isBlank()) emptyMap() else favs.associate { f ->
            f.id to (try { repo.departures(mapOf(f.id to "")) } catch (_: Exception) { null })
        }
        provideContent { GlanceTheme { Body(context, favs.map { it.id to it.title }, deps, s.apiKey.isBlank()) } }
    }

    @Composable
    private fun Body(ctx: Context, favs: List<Pair<String, String>>, deps: Map<String, List<StopDeparture>?>, noKey: Boolean) {
        val now = Nz.nowSec()
        val rows = if (LocalSize.current.height < 170.dp) 1 else 2
        Column(GlanceModifier.fillMaxSize().cornerRadius(24.dp).background(GlanceTheme.colors.widgetBackground).padding(14.dp)
                   .clickable(open(ctx, "akllive://home"))) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("★  Favourite stops", GlanceModifier.defaultWeight(),
                     style = TextStyle(color = GlanceTheme.colors.onSurface, fontWeight = FontWeight.Bold, fontSize = 14.sp))
                Text("↻", GlanceModifier.clickable(actionRunCallback<RefreshMore>()),
                     style = TextStyle(color = GlanceTheme.colors.primary, fontWeight = FontWeight.Bold, fontSize = 18.sp))
            }
            when {
                noKey -> Text("Open AKL Live and add your AT key", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp))
                favs.isEmpty() -> Text("Star a stop in the app and it shows up here", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp))
                else -> for ((id, title) in favs) {
                    Spacer(GlanceModifier.height(8.dp))
                    Column(GlanceModifier.fillMaxWidth().clickable(open(ctx, "akllive://stop/" + Uri.encode(id)))) {
                        Text(title, maxLines = 1, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Medium))
                        val list = deps[id]
                        if (list.isNullOrEmpty()) Text(if (list == null) "Couldn't load" else "Nothing for a while",
                                                       style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp))
                        else list.filter { !it.cancelled }.take(rows).forEach { d ->
                            Row(GlanceModifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(GlanceModifier.cornerRadius(8.dp).background(GlanceTheme.colors.primary).padding(horizontal = 6.dp, vertical = 1.dp)) {
                                    Text(d.route, style = TextStyle(color = GlanceTheme.colors.onPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp))
                                }
                                Spacer(GlanceModifier.width(6.dp))
                                Text(d.headsign, GlanceModifier.defaultWeight(), maxLines = 1,
                                     style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 12.sp))
                                Text(mins(d.expected - now), style = TextStyle(color = GlanceTheme.colors.primary, fontWeight = FontWeight.Bold, fontSize = 13.sp))
                            }
                        }
                    }
                }
            }
        }
    }
}

class FavouritesWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = FavouritesWidget()
    override fun onEnabled(context: Context) { super.onEnabled(context); Glance.schedule(context) }
}

// ======================= the train map =======================

/** The train map, as the app draws it, with every train on it, live. */
class TrainWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val trains = TrainsNow.get(context)
        val (w, h) = widgetSize(context, id)
        // the app's own Diagram view; the plain diagram only if the map can't be drawn
        val map = try { TrainArt.render(context, trains ?: emptyList(), w, h, top = HEADER) } catch (e: Exception) { null }
        provideContent { GlanceTheme { Body(context, map ?: drawTrains(trains ?: emptyList()), map != null, trains) } }
    }

    /** The widget's size on the home screen, in dp (portrait: its narrowest width and tallest height). */
    private fun widgetSize(ctx: Context, id: GlanceId): Pair<Int, Int> = try {
        val wid = GlanceAppWidgetManager(ctx).getAppWidgetId(id)
        val o = AppWidgetManager.getInstance(ctx).getAppWidgetOptions(wid)
        val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
        val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
        if (w > 80 && h > 80) w to h else 320 to 260
    } catch (e: Exception) {
        320 to 260
    }

    @Composable
    private fun Body(ctx: Context, map: Bitmap, fullBleed: Boolean, trains: List<Train>?) {
        Box(GlanceModifier.fillMaxSize().cornerRadius(24.dp).background(ColorProvider(Color(0xFF172234)))
                .clickable(open(ctx, "akllive://trains"))) {
            if (fullBleed) Image(ImageProvider(map), "Train map", GlanceModifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Column(GlanceModifier.fillMaxSize().padding(start = 10.dp, end = 10.dp, bottom = 10.dp, top = HEADER.dp)) {
                Image(ImageProvider(map), "Train map", GlanceModifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
            Row(GlanceModifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Ngā Tereina", GlanceModifier.defaultWeight(),
                     style = TextStyle(color = ColorProvider(Color.White), fontWeight = FontWeight.Bold, fontSize = 15.sp))
                Text(when {
                    trains == null -> "Open the app first"
                    else -> "${trains.size} running · ${Nz.time(TrainsNow.at)}"
                }, style = TextStyle(color = ColorProvider(Color(0xFFE6ECF5)), fontSize = 11.sp))
                Spacer(GlanceModifier.width(8.dp))
                Text("↻", GlanceModifier.clickable(actionRunCallback<RefreshMore>()),
                     style = TextStyle(color = ColorProvider(Color.White), fontWeight = FontWeight.Bold, fontSize = 18.sp))
            }
        }
    }

    private companion object {
        /** room for the header over the top of the map, dp */
        const val HEADER = 40
    }
}

class TrainWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TrainWidget()
    override fun onEnabled(context: Context) { super.onEnabled(context); Glance.schedule(context) }
}

/** The latest trains, shared by the app and the widget. */
object TrainsNow {
    @Volatile var list: List<Train>? = null
        private set
    @Volatile var at = 0L
        private set
    private var lastWidget = 0L
    private var repo: TrainRepo? = null

    /** The app polled the trains: keep them, and redraw the widget now and then. */
    fun save(ctx: Context, trains: List<Train>) {
        list = trains
        at = Nz.nowSec()
        val now = System.currentTimeMillis()
        if (now - lastWidget > 60_000) {
            lastWidget = now
            val app = ctx.applicationContext
            CoroutineScope(Dispatchers.Default).launch { try { TrainWidget().updateAll(app) } catch (_: Exception) { } }
        }
    }

    suspend fun get(ctx: Context): List<Train>? {
        if (list != null && Nz.nowSec() - at < 60) return list
        val s = Prefs(ctx).load()
        if (s.apiKey.isBlank()) return list
        val r = repo ?: TrainRepo(AtApi { Prefs(ctx).load().apiKey }).also { repo = it }
        return try { r.poll().also { list = it; at = Nz.nowSec() } } catch (_: Exception) { list }
    }
}

/** The post-CRL diagram and every train on it, drawn for the widget. */
private fun drawTrains(trains: List<Train>): Bitmap {
    val k = 2.4f
    val w = (MapData.W * k).toInt()
    val h = (MapData.H * k).toInt()
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3.2f * k; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    MapData.LINE_DRAW.forEachIndexed { li, pts ->
        val p = Path()
        for (i in 0 until pts.size / 2) {
            val x = pts[i * 2] * k
            val y = pts[i * 2 + 1] * k
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        line.color = MapData.LINE_COLORS[li].toInt()
        c.drawPath(p, line)
    }
    val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f * k; color = 0xFF1A2744.toInt() }
    val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE6ECF5.toInt(); textSize = 6.5f * k; typeface = Typeface.DEFAULT_BOLD }
    for (st in MapData.STATIONS) {
        for (i in 0 until st.pts.size / 2) {
            dot.color = 0xFFFFFFFF.toInt()
            c.drawCircle(st.pts[i * 2] * k, st.pts[i * 2 + 1] * k, 2.2f * k, dot)
            c.drawCircle(st.pts[i * 2] * k, st.pts[i * 2 + 1] * k, 2.2f * k, ring)
        }
        if (st.priority == 0) {
            val tw = label.measureText(st.name)
            val x = (st.x * k + st.dx * k).let { if (st.anchor == 'r') it - tw else if (st.anchor == 't' || st.anchor == 'b') it - tw / 2 else it }
            c.drawText(st.name, x.coerceIn(2f, w - tw - 2f), st.y * k + st.dy * k + label.textSize / 3, label)
        }
    }
    for (t in trains) {
        val x = t.x * k
        val y = t.y * k
        dot.color = 0xFFFFFFFF.toInt()
        c.drawCircle(x, y, 4.6f * k, dot)
        dot.color = MapData.LINE_COLORS[t.line].toInt()
        c.drawCircle(x, y, 3.4f * k, dot)
        val late = t.delay ?: 0
        if (late >= 120) {
            dot.color = if (late >= 300) 0xFFE0412F.toInt() else 0xFFEFA20E.toInt()
            c.drawCircle(x + 3.4f * k, y - 3.4f * k, 1.8f * k, dot)
        }
    }
    return bmp
}

private fun mins(secs: Long) = when {
    secs < 45 -> "Due"
    secs < 3600 -> "${secs / 60} min"
    else -> "${secs / 3600} h"
}

/** ↻ on the favourites or train widget. */
class RefreshMore : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        FavouritesWidget().updateAll(context)
        TrainWidget().updateAll(context)
    }
}
