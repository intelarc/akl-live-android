package nz.aryan.akllive.system

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
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
import nz.aryan.akllive.data.Nz
import nz.aryan.akllive.data.liveFor

/**
 * A journey in progress: follows you with GPS (TripTracker works out where you
 * are in it), keeps the rides' live data fresh, shows it all in a notification
 * that updates as you go, and buzzes when your bus is close, when you're on,
 * the stop before yours, and when you're there. Works with the phone locked.
 */
class TripService : Service(), LocationListener {
    companion object {
        const val ACTION_STOP = "nz.aryan.akllive.STOP_TRIP"
        private const val CH_TRIP = "trip"
        private const val CH_ALERT = "trip-alerts"
        private const val NID = 2101
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var poll: Job? = null
    private var lastGps = 0L
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || !TripTracker.state.value.active) {
            finish()
            return START_NOT_STICKY
        }
        channels()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        try {
            ServiceCompat.startForeground(this, NID, build(), type)
        } catch (e: Exception) {
            // location isn't allowed: nothing to follow you with
            TripTracker.clear()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!started) {
            started = true
            listen()
            poll = scope.launch { pollLive() }
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun listen() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val lm = getSystemService(LocationManager::class.java) ?: return
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= 31 && lm.isProviderEnabled(LocationManager.FUSED_PROVIDER)) add(LocationManager.FUSED_PROVIDER)
            else {
                if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) add(LocationManager.GPS_PROVIDER)
                if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) add(LocationManager.NETWORK_PROVIDER)
            }
        }
        for (p in providers) {
            try { lm.requestLocationUpdates(p, 3000L, 0f, this, Looper.getMainLooper()) } catch (_: Exception) { }
            lm.getLastKnownLocation(p)?.takeIf { System.currentTimeMillis() - it.time < 60_000 }?.let { onLocationChanged(it) }
        }
    }

    override fun onLocationChanged(loc: Location) {
        // a GPS fix beats a rougher network one for 15 seconds
        val gps = loc.provider == LocationManager.GPS_PROVIDER || loc.provider == LocationManager.FUSED_PROVIDER
        val t = SystemClock.elapsedRealtime()
        if (gps) lastGps = t else if (t - lastGps < 15_000) return
        val events = TripTracker.onFix(loc.latitude, loc.longitude, if (loc.hasSpeed()) loc.speed else null, loc.accuracy)
        post(build(), NID)
        events.forEach { alert(it) }
        if (events.any { it is TripEvent.Arrived }) scope.launch { delay(90_000); TripTracker.clear(); finish() }
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    private suspend fun pollLive() {
        val s = Prefs(this).load()
        val api = AtApi { s.apiKey }
        while (true) {
            val st = TripTracker.state.value
            val it = st.itinerary ?: break
            // done with it well after it should have ended
            if (Nz.nowSec() > it.end + 45 * 60) { TripTracker.clear(); finish(); break }
            val ids = TripTracker.watching()
            if (ids.isNotEmpty()) try { TripTracker.setLive(liveFor(api, ids)) } catch (_: Exception) { }
            post(build(), NID)
            delay(15_000)
        }
    }

    private fun build(): Notification {
        val s = TripTracker.state.value
        val open = PendingIntent.getActivity(this, 10, Intent(Intent.ACTION_VIEW, Uri.parse("akllive://trip"), this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 11, Intent(this, TripService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CH_TRIP)
            .setSmallIcon(R.drawable.ic_stat_bus)
            .setContentTitle(s.headline.ifEmpty { "Your trip" })
            .setContentText(s.detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(s.detail))
            .setSubText(s.to?.name?.let { "to $it" })
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setColor(0xFF235EA8.toInt())
            .setProgress(s.stopsTotal, s.stopsDone, false)
            .setContentIntent(open)
            .addAction(0, "End trip", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun alert(e: TripEvent) {
        val kiwi = Prefs(this).load().kiwi
        val (title, text) = when (e) {
            is TripEvent.BusSoon -> "The ${e.ride.route} is almost here" to
                "At ${e.ride.from.stop.name}" + if (e.secs > 45) ", about ${e.secs / 60} min away" else ""
            is TripEvent.Boarded -> "You're on the ${e.ride.route}" to "Get off at ${e.ride.to.stop.name}. I'll tell you when it's next."
            is TripEvent.GetOffNext -> (if (kiwi) "Next stop's yours!" else "Get off at the next stop") to
                "${e.ride.to.stop.name}: press the bell"
            is TripEvent.Missed -> "You've passed ${e.ride.to.stop.name}" to "Get off at the next stop and we'll sort it from there"
            TripEvent.Arrived -> (if (kiwi) "You made it, e hoa!" else "You're there") to "Trip finished"
        }
        val n = NotificationCompat.Builder(this, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat_bus)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setTimeoutAfter(10 * 60_000)
            .setContentIntent(PendingIntent.getActivity(this, 12, Intent(Intent.ACTION_VIEW, Uri.parse("akllive://trip"), this,
                                                                          MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()
        post(n, NID + 1)
    }

    private fun post(n: Notification, id: Int) {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        NotificationManagerCompat.from(this).notify(id, n)
    }

    private fun channels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_TRIP, "Your trip", NotificationManager.IMPORTANCE_LOW)
            .apply { description = "Where you're up to on a journey, updated as you go" })
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, "Trip alerts", NotificationManager.IMPORTANCE_HIGH)
            .apply { description = "Your bus is close, you're on, and when to get off"; enableVibration(true)
                     vibrationPattern = longArrayOf(0, 300, 150, 300, 150, 600) })
    }

    private fun finish() {
        poll?.cancel()
        try { getSystemService(LocationManager::class.java)?.removeUpdates(this) } catch (_: Exception) { }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        try { getSystemService(LocationManager::class.java)?.removeUpdates(this) } catch (_: Exception) { }
        scope.cancel()
        super.onDestroy()
    }
}
