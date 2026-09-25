package nz.aryan.akllive

import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import nz.aryan.akllive.system.Glance
import nz.aryan.akllive.system.Shortcuts
import nz.aryan.akllive.ui.AklApp
import kotlin.math.sqrt

class MainActivity : ComponentActivity(), SensorEventListener {
    private val vm: AppViewModel by viewModels()
    /** a link to open (a shortcut, the widget, a notification): "akllive://stop/..." */
    private val link = MutableStateFlow<String?>(null)
    private var sensors: SensorManager? = null
    private var lastShake = 0L
    private var shakes = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        link.value = intent?.dataString
        setContent { AklApp(vm, link) { link.value = null } }
        // keep the screen on when it's standing in as a departure board
        lifecycleScope.launch {
            vm.settings.collect { s ->
                if (s.keepOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        Glance.schedule(this)
        Shortcuts.syncFavs(this, vm.settings.value.favs)
        sensors = getSystemService(SensorManager::class.java)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.dataString?.let { link.value = it }
    }

    override fun onStart() {
        super.onStart()
        vm.setVisible(true)
    }

    override fun onResume() {
        super.onResume()
        sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensors?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        sensors?.unregisterListener(this)
        super.onPause()
    }

    override fun onStop() {
        vm.setVisible(false)
        super.onStop()
    }

    // shake the phone to refresh
    override fun onSensorChanged(e: SensorEvent) {
        if (!vm.settings.value.shake) return
        val g = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]) / SensorManager.GRAVITY_EARTH
        val now = SystemClock.elapsedRealtime()
        if (g > 2.7f && now - lastShake > 2500) {
            shakes = if (now - lastShake < 12_000) shakes + 1 else 1
            lastShake = now
            if (vm.settings.value.haptics) window.decorView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            vm.pullRefresh()
            vm.toast.tryEmit(when {
                shakes >= 4 -> "Alright, alright! I'm refreshing 😵‍💫"
                vm.settings.value.kiwi -> "Shaken, not stirred: refreshing"
                else -> "Refreshing"
            })
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
