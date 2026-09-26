package nz.aryan.akllive.system

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
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
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
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
import nz.aryan.akllive.R
import nz.aryan.akllive.data.Nz
import nz.aryan.akllive.gtfs.Mode
import nz.aryan.akllive.gtfs.RideLeg

private fun open(ctx: Context, link: String) =
    actionStartActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link), ctx, MainActivity::class.java))

/**
 * Your trip on the home screen: once you start one, where you're up to (walk to
 * the stop, the bus is 2 stops away, 4 stops to go…), the rides ahead, when
 * you'll get there, and a button to end it. With no trip on, a way to plan one.
 */
class TripWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val s = TripTracker.state.value
        provideContent { GlanceTheme { if (s.active) Trip(context, s) else Idle(context) } }
    }

    @Composable
    private fun Idle(ctx: Context) {
        Column(GlanceModifier.fillMaxSize().cornerRadius(24.dp).background(GlanceTheme.colors.widgetBackground).padding(14.dp)
                   .clickable(open(ctx, "akllive://plan"))) {
            Header("Your trip", null)
            Spacer(GlanceModifier.defaultWeight())
            Text("No trip on right now", style = TextStyle(color = GlanceTheme.colors.onSurface, fontWeight = FontWeight.Bold, fontSize = 16.sp))
            Text("Plan a journey and tap Start trip. It follows along here.", maxLines = 2,
                 style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp))
            Spacer(GlanceModifier.height(10.dp))
            Box(GlanceModifier.cornerRadius(18.dp).background(GlanceTheme.colors.primary).padding(horizontal = 16.dp, vertical = 8.dp)
                    .clickable(open(ctx, "akllive://plan"))) {
                Text("Plan a trip", style = TextStyle(color = GlanceTheme.colors.onPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp))
            }
        }
    }

    @Composable
    private fun Trip(ctx: Context, s: TripProgress) {
        val it = s.itinerary ?: return
        val size = LocalSize.current
        val roomy = size.height >= 150.dp
        val last = it.legs.lastOrNull { l -> l is RideLeg } as? RideLeg
        val arrive = it.end + (last?.let { r -> s.live[r.tripId]?.delay } ?: 0)
        Column(GlanceModifier.fillMaxSize().cornerRadius(24.dp).background(GlanceTheme.colors.widgetBackground).padding(14.dp)
                   .clickable(open(ctx, "akllive://trip"))) {
            Header(s.to?.name?.let { n -> "To $n" } ?: "Your trip",
                   if (s.phase == Phase.Arrived) "Done" else "Arrive ${Nz.time(arrive)}")
            Spacer(GlanceModifier.height(8.dp))
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val ride = s.ride
                if (ride != null && s.phase != Phase.Walking && s.phase != Phase.Arrived) {
                    Box(GlanceModifier.cornerRadius(10.dp).background(GlanceTheme.colors.primary).padding(horizontal = 8.dp, vertical = 3.dp)) {
                        Text(ride.route, style = TextStyle(color = GlanceTheme.colors.onPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp))
                    }
                    Spacer(GlanceModifier.width(8.dp))
                }
                Text(s.headline.ifEmpty { "Finding you…" }, GlanceModifier.defaultWeight(), maxLines = 2,
                     style = TextStyle(color = GlanceTheme.colors.onSurface, fontWeight = FontWeight.Bold, fontSize = 15.sp))
            }
            if (s.detail.isNotEmpty()) {
                Spacer(GlanceModifier.height(2.dp))
                Text(s.detail, maxLines = if (roomy) 2 else 1,
                     style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp))
            }
            if (s.phase == Phase.OnBoard && s.stopsTotal > 0) {
                Spacer(GlanceModifier.height(8.dp))
                LinearProgressIndicator(s.stopsDone.toFloat() / s.stopsTotal, GlanceModifier.fillMaxWidth().height(6.dp),
                                        color = GlanceTheme.colors.primary, backgroundColor = GlanceTheme.colors.secondaryContainer)
            }
            Spacer(GlanceModifier.defaultWeight())
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // the journey, leg by leg, with the one you're on picked out
                if (roomy || size.width >= 300.dp) Row(GlanceModifier.defaultWeight(), verticalAlignment = Alignment.CenterVertically) {
                    it.legs.take(6).forEachIndexed { i, l ->
                        if (i > 0) Text(" › ", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp))
                        val now = i == s.leg && s.phase != Phase.Arrived
                        val done = i < s.leg || s.phase == Phase.Arrived
                        val label = when (l) {
                            is RideLeg -> l.route.ifEmpty { if (l.mode == Mode.Train) "Train" else if (l.mode == Mode.Ferry) "Ferry" else "Bus" }
                            else -> "Walk"
                        }
                        Box(GlanceModifier.cornerRadius(8.dp)
                                .background(if (now) GlanceTheme.colors.primary else GlanceTheme.colors.secondaryContainer)
                                .padding(horizontal = 6.dp, vertical = 2.dp)) {
                            Text(label, maxLines = 1, style = TextStyle(
                                color = if (now) GlanceTheme.colors.onPrimary else GlanceTheme.colors.onSecondaryContainer,
                                fontWeight = if (now) FontWeight.Bold else FontWeight.Medium, fontSize = 11.sp,
                                textDecoration = if (done) androidx.glance.text.TextDecoration.LineThrough else null))
                        }
                    }
                } else Spacer(GlanceModifier.defaultWeight())
                Box(GlanceModifier.cornerRadius(16.dp).background(GlanceTheme.colors.errorContainer).padding(horizontal = 12.dp, vertical = 6.dp)
                        .clickable(actionRunCallback<EndTrip>())) {
                    Text(if (s.phase == Phase.Arrived) "Close" else "End", style = TextStyle(
                        color = GlanceTheme.colors.onErrorContainer, fontWeight = FontWeight.Bold, fontSize = 12.sp))
                }
            }
        }
    }

    @Composable
    private fun Header(title: String, right: String?) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Image(ImageProvider(R.drawable.ic_widget_logo), null, GlanceModifier.size(20.dp))
            Spacer(GlanceModifier.width(8.dp))
            Text(title, GlanceModifier.defaultWeight(), maxLines = 1,
                 style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontWeight = FontWeight.Medium, fontSize = 12.sp))
            if (right != null) Text(right, style = TextStyle(color = GlanceTheme.colors.primary, fontWeight = FontWeight.Bold, fontSize = 12.sp))
        }
    }

    companion object {
        private var last = 0L
        private var lastKey = ""

        /** Redraw after the trip moves on: straight away when something changes, else every 15 seconds at most. */
        fun nudge(ctx: Context, force: Boolean = false) {
            val s = TripTracker.state.value
            val key = "${s.active}|${s.leg}|${s.phase}|${s.stopsDone}|${s.headline}"
            val now = System.currentTimeMillis()
            if (!force && key == lastKey && now - last < 15_000) return
            last = now
            lastKey = key
            val app = ctx.applicationContext
            CoroutineScope(Dispatchers.Default).launch { try { TripWidget().updateAll(app) } catch (_: Exception) { } }
        }
    }
}

/** End on the trip widget. */
class EndTrip : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        TripTracker.stop(context)
        TripWidget().updateAll(context)
    }
}

/**
 * The trip widget. It took the favourite stops widget's place, and keeps its
 * receiver's name so one already on the home screen turns into it.
 */
class FavouritesWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TripWidget()
}
