package nz.aryan.akllive.system

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nz.aryan.akllive.MainActivity
import nz.aryan.akllive.Prefs
import nz.aryan.akllive.R
import nz.aryan.akllive.data.AtApi
import nz.aryan.akllive.data.Fleet
import nz.aryan.akllive.data.Nz
import nz.aryan.akllive.data.distanceKm
import nz.aryan.akllive.data.liveFor
import nz.aryan.akllive.data.occupancyText

/**
 * Tracking a bus: a live notification that counts down to it (the countdown
 * ticks by itself), shows how many stops away it is and what bus it is, and
 * buzzes when it's time to leave. Refreshed from AT every 20 seconds; it ends
 * itself once the bus has been and gone.
 */
class TrackService : Service() {
    companion object {
        const val EX_TRIP = "trip"
        const val EX_ROUTE = "route"
        const val EX_HEAD = "head"
        const val EX_STOP = "stop"
        const val EX_SEQ = "seq"
        const val EX_SCHED = "sched"
        const val EX_LAT = "lat"
        const val EX_LON = "lon"
        const val ACTION_STOP = "nz.aryan.akllive.STOP_TRACKING"
        private const val CH_TRACK = "tracking"
        private const val CH_LEAVE = "leave"
        private const val NID = 2001
    }

    private class Tracked(val trip: String, val route: String, val head: String, val stop: String, val seq: Int,
                          val sched: Long, val lat: Double, val lon: Double)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || intent == null) {
            finish()
            return START_NOT_STICKY
        }
        val t = Tracked(
            intent.getStringExtra(EX_TRIP) ?: return finish().let { START_NOT_STICKY },
            intent.getStringExtra(EX_ROUTE) ?: "", intent.getStringExtra(EX_HEAD) ?: "",
            intent.getStringExtra(EX_STOP) ?: "", intent.getIntExtra(EX_SEQ, 0), intent.getLongExtra(EX_SCHED, 0),
            intent.getDoubleExtra(EX_LAT, 0.0), intent.getDoubleExtra(EX_LON, 0.0),
        )
        channels()
        ServiceCompat.startForeground(this, NID, build(t, t.sched, null, null, null, "Finding your bus…"),
                                      if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        job?.cancel()
        job = scope.launch { follow(t) }
        return START_NOT_STICKY
    }

    private fun finish() {
        job?.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun follow(t: Tracked) {
        val s = Prefs(this).load()
        Fleet.load(this)
        val api = AtApi { s.apiKey }
        val lead = (s.walkMin + s.alertMin) * 60L
        var warned = false
        var firstAway: Int? = null
        val until = Nz.nowSec() + 90 * 60
        while (Nz.nowSec() < until) {
            val live = try { liveFor(api, listOf(t.trip))[t.trip] } catch (_: Exception) { null }
            val now = Nz.nowSec()
            val expected = t.sched + (live?.delay ?: 0)
            val away = live?.seq?.let { t.seq - it }
            if (live?.cancelled == true) {
                done(t, "The ${t.route} to ${t.head} was cancelled", "Check the app for the next one")
                return
            }
            if ((away != null && away < 0) || expected < now - 120) {
                done(t, "The ${t.route} has left ${t.stop}", if (s.kiwi) "Hope you made it, e hoa!" else "Tracking stopped")
                return
            }
            if (away != null && firstAway == null) firstAway = maxOf(away, 1)
            val v = live?.vehicle
            val info = v?.label?.let { Fleet.info(it) }
            val bits = listOfNotNull(
                away?.let { if (it == 0) "At your stop" else if (it == 1) "1 stop away" else "$it stops away" },
                live?.delay?.let { d -> val m = Math.round(d / 60f); if (m >= 2) "$m min late" else if (m <= -2) "${-m} min early" else "on time" }
                    ?: "timetable time",
                v?.let { if (t.lat != 0.0) "%.1f km".format(distanceKm(it.lat, it.lon, t.lat, t.lon)) else null },
                info?.model?.let { m -> m.short + if (m.electric) " ⚡" else "" },
                occupancyText(v?.occupancy),
            )
            val progress = if (away != null && firstAway != null) (firstAway!! - away).coerceAtLeast(0) to firstAway!! else null
            post(build(t, expected, progress, info?.fleetNo, live?.delay, bits.joinToString(" · ")))
            if (!warned && expected - now <= lead) {
                warned = true
                leaveNow(t, expected - now, s.kiwi)
            }
            delay(20_000)
        }
        done(t, "Stopped tracking the ${t.route}", "It was taking a while")
    }

    private fun build(t: Tracked, expected: Long, progress: Pair<Int, Int>?, fleetNo: String?, delay: Int?,
                      text: String): android.app.Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, TrackService::class.java).setAction(ACTION_STOP),
                                            PendingIntent.FLAG_IMMUTABLE)
        val secs = expected - Nz.nowSec()
        val title = "${t.route} to ${t.head} · " + when {
            secs < 45 -> "Due"
            else -> "${secs / 60} min"
        }
        return NotificationCompat.Builder(this, CH_TRACK)
            .setSmallIcon(R.drawable.ic_stat_bus)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(listOfNotNull(t.stop, fleetNo).joinToString(" · "))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setWhen(expected * 1000)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setColor(0xFF235EA8.toInt())
            .setColorized(true)
            .setProgress(progress?.second ?: 0, progress?.first ?: 0, progress == null)
            .setContentIntent(open)
            .addAction(0, "Stop tracking", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun leaveNow(t: Tracked, secs: Long, kiwi: Boolean) {
        val m = maxOf(0, secs / 60)
        val n = NotificationCompat.Builder(this, CH_LEAVE)
            .setSmallIcon(R.drawable.ic_stat_bus)
            .setContentTitle(if (kiwi) "Time to hit the road!" else "Time to leave")
            .setContentText("The ${t.route} to ${t.head} is ${if (m == 0L) "almost here" else "$m min away"} at ${t.stop}")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()
        post(n, NID + 1)
    }

    private fun done(t: Tracked, title: String, text: String) {
        val n = NotificationCompat.Builder(this, CH_LEAVE)
            .setSmallIcon(R.drawable.ic_stat_bus)
            .setContentTitle(title)
            .setContentText(text)
            .setTimeoutAfter(10 * 60_000)
            .setAutoCancel(true)
            .build()
        post(n, NID + 2)
        finish()
    }

    private fun post(n: android.app.Notification, id: Int = NID) {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        NotificationManagerCompat.from(this).notify(id, n)
    }

    private fun channels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_TRACK, "Tracking a bus", NotificationManager.IMPORTANCE_LOW)
            .apply { description = "The live countdown while you track a bus" })
        nm.createNotificationChannel(NotificationChannel(CH_LEAVE, "Time to leave", NotificationManager.IMPORTANCE_HIGH)
            .apply { description = "A heads-up when it's time to head for your stop"; enableVibration(true) })
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
