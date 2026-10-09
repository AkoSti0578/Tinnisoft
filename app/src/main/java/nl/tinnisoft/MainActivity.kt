package nl.tinnisoft

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import nl.tinnisoft.playback.PlaybackService
import nl.tinnisoft.ui.AppViewModel
import nl.tinnisoft.ui.screens.BatteryScreen
import nl.tinnisoft.ui.screens.DisclaimerScreen
import nl.tinnisoft.ui.screens.LogScreen
import nl.tinnisoft.ui.screens.MatcherScreen
import nl.tinnisoft.ui.screens.MixerScreen
import nl.tinnisoft.ui.screens.MoreScreen
import nl.tinnisoft.ui.screens.PlayerScreen
import nl.tinnisoft.ui.screens.PresetsScreen
import nl.tinnisoft.ui.screens.ScheduleScreen
import nl.tinnisoft.ui.screens.SettingsScreen
import nl.tinnisoft.ui.theme.TinnisoftTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val appVm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleAutoplay(intent)
        setContent {
            val settings by appVm.state.collectAsStateWithLifecycle()
            TinnisoftTheme(light = settings?.lightTheme == true) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    val s = settings
                    when {
                        s == null -> Unit
                        !s.disclaimerAccepted -> DisclaimerScreen(onAccept = { appVm.acceptDisclaimer() })
                        !s.batteryPromptShown -> {
                            RequestRuntimePermissions()
                            BatteryScreen(onDone = { appVm.batteryPromptDone() })
                        }
                        else -> AppScaffold()
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAutoplay(intent)
    }

    private fun handleAutoplay(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_AUTOPLAY, false) == true) {
            intent.removeExtra(EXTRA_AUTOPLAY)
            PlaybackService.play(this)
        }
    }

    companion object {
        const val EXTRA_AUTOPLAY = "autoplay"
    }
}

/** Notifications (Android 13+) and the Bluetooth device name (Android 12+). */
@Composable
private fun RequestRuntimePermissions() {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    LaunchedEffect(Unit) {
        val perms = buildList {
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (perms.isNotEmpty()) launcher.launch(perms.toTypedArray())
    }
}

private data class Tab(val route: String, val label: Int, val icon: ImageVector)

private val tabs = listOf(
    Tab("player", R.string.nav_player, Icons.Filled.PlayCircle),
    Tab("mixer", R.string.nav_mixer, Icons.Filled.Tune),
    Tab("presets", R.string.nav_presets, Icons.Filled.LibraryMusic),
    Tab("more", R.string.nav_more, Icons.Filled.MoreHoriz),
)

private val subTitles = mapOf(
    "matcher" to R.string.nav_matcher,
    "log" to R.string.nav_log,
    "schedule" to R.string.nav_schedule,
    "settings" to R.string.nav_settings,
    "battery" to R.string.battery_title,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScaffold() {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route ?: "player"
    val isTab = tabs.any { it.route == route }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(subTitles[route] ?: tabs.firstOrNull { it.route == route }?.label ?: R.string.app_name)) },
                navigationIcon = {
                    if (!isTab) {
                        IconButton(onClick = { nav.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (isTab) {
                NavigationBar {
                    tabs.forEach { t ->
                        NavigationBarItem(
                            selected = route == t.route,
                            onClick = {
                                nav.navigate(t.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(t.icon, null) },
                            label = { Text(stringResource(t.label)) },
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        NavHost(nav, startDestination = "player", modifier = Modifier.padding(padding)) {
            composable("player") { PlayerScreen(snackbar) }
            composable("mixer") { MixerScreen() }
            composable("presets") { PresetsScreen() }
            composable("more") { MoreScreen { nav.navigate(it) } }
            composable("matcher") { MatcherScreen(onDone = { nav.popBackStack() }) }
            composable("log") { LogScreen() }
            composable("schedule") { ScheduleScreen() }
            composable("settings") { SettingsScreen(onBatteryHelp = { nav.navigate("battery") }) }
            composable("battery") { BatteryScreen(onDone = { nav.popBackStack() }, showSkip = false) }
        }
    }
}
