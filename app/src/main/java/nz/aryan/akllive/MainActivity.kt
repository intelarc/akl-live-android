package nz.aryan.akllive

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import nz.aryan.akllive.ui.AklIcons
import nz.aryan.akllive.ui.AklTheme
import nz.aryan.akllive.ui.BusScreen
import nz.aryan.akllive.ui.FleetScreen
import nz.aryan.akllive.ui.LiveScreen
import nz.aryan.akllive.ui.LocalOpenModel
import nz.aryan.akllive.ui.SettingsScreen
import nz.aryan.akllive.ui.TrainScreen

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AklTheme { AppRoot(vm, ::keepScreenOn) }
        }
    }

    override fun onStart() {
        super.onStart()
        vm.setVisible(true)
    }

    override fun onStop() {
        vm.setVisible(false)
        super.onStop()
    }

    private fun keepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

@Composable
private fun AppRoot(vm: AppViewModel, keepScreenOn: (Boolean) -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var keepOn by rememberSaveable { mutableIntStateOf(if (vm.prefs.keepOn) 1 else 0) }
    LaunchedEffect(keepOn) { keepScreenOn(keepOn == 1) }
    // any bus's model row opens that model's page on the Fleet tab
    val openModel: (String) -> Unit = { id ->
        vm.fleetModel.value = id
        tab = 3
    }
    val tabs = listOf("Buses" to AklIcons.Bus, "Trains" to AklIcons.Train, "Live" to AklIcons.Live,
                      "Fleet" to AklIcons.Fleet, "Settings" to AklIcons.Settings)
    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, (label, icon) ->
                    NavigationBarItem(selected = tab == i, onClick = {
                            // tapping Fleet again goes back to the list
                            if (i == 3 && tab == 3) vm.fleetModel.value = null
                            tab = i
                        },
                        icon = { Icon(icon, null) }, label = { Text(label) })
                }
            }
        },
    ) { pad ->
        val m = Modifier.padding(pad)
        CompositionLocalProvider(LocalOpenModel provides openModel) {
            when (tab) {
                0 -> BusScreen(vm, m)
                1 -> TrainScreen(vm, m)
                2 -> LiveScreen(vm, m)
                3 -> FleetScreen(vm, m)
                else -> SettingsScreen(vm, m, keepOn == 1) { on ->
                    vm.prefs.keepOn = on
                    keepOn = if (on) 1 else 0
                }
            }
        }
    }
}
