package nz.aryan.akllive.ui

import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Directions
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Train
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Directions
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Train
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.flow.StateFlow
import nz.aryan.akllive.AppViewModel

private class Tab(val route: String, val label: String, val on: ImageVector, val off: ImageVector)

private val TABS = listOf(
    Tab("home", "Home", Icons.Rounded.Home, Icons.Outlined.Home),
    Tab("plan", "Plan", Icons.Rounded.Directions, Icons.Outlined.Directions),
    Tab("live", "Live", Icons.Rounded.Map, Icons.Outlined.Map),
    Tab("trains", "Trains", Icons.Rounded.Train, Icons.Outlined.Train),
    Tab("more", "More", Icons.Rounded.Apps, Icons.Outlined.Apps),
)

/** The whole app: themed, with the bottom tabs and every screen. */
@Composable
fun AklApp(vm: AppViewModel, deepLink: StateFlow<String?>, consumeLink: () -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    AklTheme(s.themeMode, s.palette, s.dynamicColor, s.pureBlack) {
        val nav = rememberNavController()
        val snack = remember { SnackbarHostState() }
        val view = LocalView.current
        val haptics = remember(s.haptics) { Haptics(s.haptics) }
        LaunchedEffect(Unit) { vm.toast.collect { snack.showSnackbar(it) } }
        val link by deepLink.collectAsStateWithLifecycle()
        LaunchedEffect(link) {
            val l = link ?: return@LaunchedEffect
            consumeLink()
            val uri = Uri.parse(l)
            when (uri.host) {
                "stop" -> uri.lastPathSegment?.let { nav.navigate("stop/${Uri.encode(it)}") }
                "plan", "live", "trains", "home", "more" -> nav.tab(uri.host!!)
                "search", "alerts", "fleet", "dex", "settings", "routes" -> nav.navigate(uri.host!!)
                "route" -> uri.lastPathSegment?.let { nav.navigate("route/${Uri.encode(it)}") }
            }
        }
        val go = remember(nav) { Nav { r -> if (TABS.any { it.route == r }) nav.tab(r) else nav.navigate(r) } }
        val back: () -> Unit = remember(nav) { { if (!nav.popBackStack()) nav.tab("home") } }
        val openModel: (String) -> Unit = remember(nav) { { id -> vm.fleetModel.value = id; nav.navigate("fleet") } }
        val entry by nav.currentBackStackEntryAsState()
        val route = entry?.destination?.route
        val alerts by vm.alerts.collectAsStateWithLifecycle()
        val mine = remember(alerts, s) { vm.myAlerts(alerts, s).size }

        CompositionLocalProvider(LocalNav provides go, LocalBack provides back, LocalHaptics provides haptics,
                                 LocalOpenModel provides openModel) {
            Scaffold(
                snackbarHost = { SnackbarHost(snack) },
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                bottomBar = {
                    if (TABS.any { it.route == route }) {
                        NavigationBar {
                            TABS.forEach { t ->
                                val on = route == t.route
                                NavigationBarItem(
                                    selected = on,
                                    onClick = {
                                        haptics.tick(view)
                                        // tapping Directions or Live again starts it fresh; Home scrolls up
                                        nav.tab(t.route)
                                    },
                                    icon = {
                                        if (t.route == "more" && mine > 0) {
                                            BadgedBox(badge = { Badge { Text("$mine") } }) { Icon(if (on) t.on else t.off, null) }
                                        } else Icon(if (on) t.on else t.off, null)
                                    },
                                    label = { Text(t.label, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) },
                                )
                            }
                        }
                    }
                },
            ) { pad ->
                // lists stay clear of the navigation bar; maps and the journey sheet run under it
                val tab = TABS.any { it.route == route }
                val underBar = route == "journey/{i}" || route == "busmap"
                Box(Modifier.fillMaxSize().padding(pad).let { if (tab || underBar) it else it.navigationBarsPadding() }) {
                    Screens(vm, nav)
                }
            }
        }
    }
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.slideInFwd() =
    slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(280)) + fadeIn(tween(200))

private fun AnimatedContentTransitionScope<NavBackStackEntry>.slideOutBack() =
    slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(280)) + fadeOut(tween(200))

private fun NavHostController.tab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

@Composable
private fun Screens(vm: AppViewModel, nav: NavHostController) {
    NavHost(
        nav, startDestination = "home",
        enterTransition = { fadeIn(tween(220)) },
        exitTransition = { fadeOut(tween(160)) },
        popEnterTransition = { fadeIn(tween(220)) },
        popExitTransition = { fadeOut(tween(160)) },
    ) {
        composable("home") { HomeScreen(vm) }
        composable("plan") { PlanScreen(vm) }
        composable("live") { LiveScreen(vm, Modifier) }
        composable("trains") {
            DisposableEffect(Unit) { vm.watchTrains(true); onDispose { vm.watchTrains(false) } }
            TrainScreen(vm, Modifier)
        }
        composable("more") { MoreScreen(vm) }
        composable("search", enterTransition = { fadeIn(tween(180)) }) { SearchScreen(vm) }
        composable("stop/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType }),
                   enterTransition = { slideInFwd() }, popExitTransition = { slideOutBack() }) {
            StopScreen(vm, Uri.decode(it.arguments?.getString("id") ?: ""))
        }
        composable("route/{short}", arguments = listOf(navArgument("short") { type = NavType.StringType }),
                   enterTransition = { slideInFwd() }, popExitTransition = { slideOutBack() }) {
            RouteScreen(vm, Uri.decode(it.arguments?.getString("short") ?: ""))
        }
        composable("journey/{i}", arguments = listOf(navArgument("i") { type = NavType.IntType }),
                   enterTransition = { slideInFwd() }, popExitTransition = { slideOutBack() }) {
            JourneyScreen(vm, it.arguments?.getInt("i") ?: 0)
        }
        composable("routes", enterTransition = { slideInFwd() }, popExitTransition = { slideOutBack() }) { RoutesScreen(vm) }
        composable("busmap", enterTransition = { slideInFwd() }, popExitTransition = { slideOutBack() }) { BusMapRoute(vm) }
        composable("alerts", enterTransition = { slideInFwd() }, popExitTransition = { slideOutBack() }) { AlertsScreen(vm) }
        composable("fleet", enterTransition = { slideInFwd() }, popExitTransition = { slideOutBack() }) { FleetRoute(vm) }
        composable("dex", enterTransition = { slideInFwd() }, popExitTransition = { slideOutBack() }) { DexScreen(vm) }
        composable("settings", enterTransition = { slideInFwd() }, popExitTransition = { slideOutBack() }) { SettingsScreen(vm) }
    }
}
