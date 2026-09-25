package nz.aryan.akllive.system

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
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
import nz.aryan.akllive.MainActivity
import nz.aryan.akllive.R
import nz.aryan.akllive.data.Nz

/** The home screen widget: your next bus each way. */
class NextBusWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val cached = Glance.load(context)
        val data = if (cached == null || Nz.nowSec() - cached.updated > 240) Glance.fetch(context) ?: cached else cached
        provideContent { GlanceTheme { Body(data) } }
    }

    @Composable
    private fun Body(d: GlanceData?) {
        val small = LocalSize.current.height < 150.dp
        val now = Nz.nowSec()
        Column(
            GlanceModifier.fillMaxSize().cornerRadius(24.dp).background(GlanceTheme.colors.widgetBackground)
                .padding(14.dp).clickable(actionStartActivity<MainActivity>()),
        ) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Image(ImageProvider(R.drawable.ic_stat_bus), "AKL Live", GlanceModifier.size(18.dp),
                      colorFilter = ColorFilter.tint(GlanceTheme.colors.primary))
                Spacer(GlanceModifier.width(6.dp))
                Text("Next bus", GlanceModifier.defaultWeight(),
                     style = TextStyle(color = GlanceTheme.colors.onSurface, fontWeight = FontWeight.Bold, fontSize = 14.sp))
                Box(GlanceModifier.size(28.dp).clickable(actionRunCallback<RefreshWidget>()), contentAlignment = Alignment.Center) {
                    Text("↻", style = TextStyle(color = GlanceTheme.colors.primary, fontWeight = FontWeight.Bold, fontSize = 18.sp))
                }
            }
            if (d == null) {
                Spacer(GlanceModifier.height(8.dp))
                Text("Open AKL Live and add your AT key", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp))
                return@Column
            }
            for ((stop, buses) in d.stops) {
                val soon = buses.filter { !it.cancelled && it.expected >= now - 30 }
                val b = soon.firstOrNull()
                Spacer(GlanceModifier.height(if (small) 6.dp else 10.dp))
                Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(GlanceModifier.cornerRadius(10.dp).background(GlanceTheme.colors.primary).padding(horizontal = 8.dp, vertical = 3.dp)) {
                        Text(b?.route ?: "–", style = TextStyle(color = GlanceTheme.colors.onPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp))
                    }
                    Spacer(GlanceModifier.width(8.dp))
                    Column(GlanceModifier.defaultWeight()) {
                        Text("to " + (b?.headsign ?: stop), maxLines = 1,
                             style = TextStyle(color = GlanceTheme.colors.onSurface, fontWeight = FontWeight.Medium, fontSize = 13.sp))
                        if (!small) {
                            val sub = listOfNotNull(b?.let { Nz.time(it.expected) }, if (b?.live == true) "live" else "timetable",
                                                    b?.model?.let { if (b.electric) "$it ⚡" else it }).joinToString(" · ")
                            Text(sub, maxLines = 1, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp))
                        }
                    }
                    Text(b?.let { mins(it.expected - now) } ?: "—",
                         style = TextStyle(color = GlanceTheme.colors.primary, fontWeight = FontWeight.Bold, fontSize = if (small) 16.sp else 20.sp))
                }
            }
            if (!small) {
                Spacer(GlanceModifier.defaultWeight())
                Text("Updated ${Nz.time(d.updated)}", style = TextStyle(color = ColorProvider(Color(0xFF8A94A6)), fontSize = 10.sp))
            }
        }
    }

    private fun mins(secs: Long) = when {
        secs < 45 -> "Due"
        secs < 3600 -> "${secs / 60} min"
        else -> "${secs / 3600} h"
    }
}

class RefreshWidget : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        Glance.fetch(context)
        NextBusWidget().update(context, glanceId)
    }
}

class NextBusWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NextBusWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        Glance.schedule(context)
    }
}
