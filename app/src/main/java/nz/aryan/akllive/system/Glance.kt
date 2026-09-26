package nz.aryan.akllive.system

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import nz.aryan.akllive.Prefs
import nz.aryan.akllive.data.AtApi
import nz.aryan.akllive.data.BusRepo
import nz.aryan.akllive.data.Fleet
import nz.aryan.akllive.data.Nz
import nz.aryan.akllive.data.StopBoard
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** A bus, as the widget and the tile show it. */
data class GlanceBus(val route: String, val headsign: String, val expected: Long, val live: Boolean, val delay: Int,
                     val model: String?, val electric: Boolean, val cancelled: Boolean)

/** The next buses at each of your stops, as of [updated]. */
data class GlanceData(val stops: List<Pair<String, List<GlanceBus>>>, val updated: Long) {
    /** the soonest bus still to come, anywhere */
    fun next(now: Long = Nz.nowSec()): GlanceBus? =
        stops.flatMap { it.second }.filter { !it.cancelled && it.expected >= now - 30 }.minByOrNull { it.expected }
}

/** Keeps the widget and the quick settings tile fed: a small cache, refreshed by the app or in the background. */
object Glance {
    private var lastWidget = 0L

    fun save(ctx: Context, boards: List<StopBoard>) {
        val stops = JSONArray()
        for (b in boards) {
            if (b.departures.isEmpty() && b.name.isEmpty()) continue
            val buses = JSONArray()
            b.departures.take(4).forEach { d ->
                val m = d.vehicle?.label?.let { Fleet.info(it)?.model }
                buses.put(JSONObject().put("r", d.route).put("h", d.headsign).put("e", d.expected).put("l", d.live)
                              .put("d", d.delay).put("m", m?.short ?: "").put("el", m?.electric == true).put("c", d.cancelled))
            }
            stops.put(JSONObject().put("n", b.name.ifEmpty { "Stop ${b.code}" }).put("b", buses))
        }
        Prefs(ctx).glance = JSONObject().put("t", Nz.nowSec()).put("s", stops).toString()
        // the widget redraws at most once a minute from here
        val now = System.currentTimeMillis()
        if (now - lastWidget > 60_000) {
            lastWidget = now
            val app = ctx.applicationContext
            CoroutineScope(Dispatchers.Default).launch { try { NextBusWidget().updateAll(app) } catch (_: Exception) { } }
        }
    }

    fun load(ctx: Context): GlanceData? {
        val raw = Prefs(ctx).glance
        if (raw.isEmpty()) return null
        return try {
            val j = JSONObject(raw)
            val s = j.getJSONArray("s")
            GlanceData((0 until s.length()).map { i ->
                val o = s.getJSONObject(i)
                val b = o.getJSONArray("b")
                o.optString("n") to (0 until b.length()).map { k ->
                    val x = b.getJSONObject(k)
                    GlanceBus(x.optString("r"), x.optString("h"), x.optLong("e"), x.optBoolean("l"), x.optInt("d"),
                              x.optString("m").ifEmpty { null }, x.optBoolean("el"), x.optBoolean("c"))
                }
            }, j.optLong("t"))
        } catch (e: Exception) {
            null
        }
    }

    /** Fetch fresh from AT (the widget's worker, the tile). */
    suspend fun fetch(ctx: Context): GlanceData? {
        val s = Prefs(ctx).load()
        if (s.apiKey.isBlank()) return null
        Fleet.load(ctx)
        val repo = BusRepo(AtApi { s.apiKey })
        val boards = s.stops.mapNotNull { code -> try { repo.board(code, s.route) } catch (_: Exception) { null } }
        if (boards.isEmpty()) return load(ctx)
        save(ctx, boards)
        return load(ctx)
    }

    /** Refresh the widget every 15 minutes in the background (Android's shortest interval). */
    fun schedule(ctx: Context) {
        // run with the internet up: a job that needs it is let online even while the app's in the background
        val req = PeriodicWorkRequestBuilder<WidgetWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("next-bus-widget", ExistingPeriodicWorkPolicy.UPDATE, req)
    }
}

class WidgetWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Glance.fetch(applicationContext)
        NextBusWidget().updateAll(applicationContext)
        return Result.success()
    }
}
