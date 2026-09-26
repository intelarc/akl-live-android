package nz.aryan.akllive.system

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
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import nz.aryan.akllive.MainActivity
import nz.aryan.akllive.Prefs
import nz.aryan.akllive.R
import nz.aryan.akllive.data.AtApi
import nz.aryan.akllive.data.MapData
import nz.aryan.akllive.data.Nz
import nz.aryan.akllive.data.Train
import nz.aryan.akllive.data.TrainRepo

private fun open(ctx: Context, link: String) =
    actionStartActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link), ctx, MainActivity::class.java))

// ======================= search =======================

/** A search bar on the home screen, like Google's: tap it and you're typing in the app's search. */
class SearchWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { GlanceTheme { Body(context) } }
    }

    @Composable
    private fun Body(ctx: Context) {
        val size = LocalSize.current
        val wide = size.width > 220.dp
        val h = minOf(size.height - 8.dp, 56.dp)
        Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Row(GlanceModifier.fillMaxWidth().height(h).cornerRadius(28.dp).background(GlanceTheme.colors.surface)
                    .padding(start = 10.dp, end = 16.dp).clickable(open(ctx, "akllive://search")),
                verticalAlignment = Alignment.CenterVertically) {
                Image(ImageProvider(R.drawable.ic_widget_logo), "AKL Live", GlanceModifier.size(30.dp))
                Spacer(GlanceModifier.width(12.dp))
                Text(if (wide) "Search stops, places, routes" else "Search", GlanceModifier.defaultWeight(), maxLines = 1,
                     style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 16.sp))
                Image(ImageProvider(R.drawable.ic_widget_glass), null, GlanceModifier.size(22.dp))
            }
        }
    }
}

class SearchWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SearchWidget()
}
