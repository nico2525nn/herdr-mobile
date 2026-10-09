package dev.herdr.mobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.herdr.mobile.core.designsystem.HerdrMobileTheme
import dev.herdr.mobile.feature.home.HomeScreen
import dev.herdr.mobile.feature.home.HomeViewModel
import dev.herdr.mobile.feature.settings.SettingsScreen
import dev.herdr.mobile.feature.settings.SettingsViewModel
import dev.herdr.mobile.feature.terminal.TerminalScreen
import dev.herdr.mobile.feature.terminal.TerminalViewModel
import dev.herdr.mobile.notifications.HerdrNotifications
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable

@Serializable
private data object HomeRoute

@Serializable
private data object SettingsRoute

@Serializable
private data class TerminalRoute(val workspaceId: String? = null, val tabId: String? = null, val paneId: String? = null)

class MainActivity : ComponentActivity() {

    /**
     * Deep-link target from a notification tap. Mutable so onNewIntent (app
     * already alive, singleTask) can update it — onCreate-only reading drops
     * every notification tapped while the app runs.
     */
    private val deepLinkTarget = MutableStateFlow<TerminalRoute?>(null)

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            // Granted or not, the relay no-ops safely; no retry nag here.
        }

    private fun routeFromIntent(intent: Intent?): TerminalRoute? {
        val workspace = intent?.getStringExtra(HerdrNotifications.EXTRA_WORKSPACE_ID) ?: return null
        val tab = intent.getStringExtra(HerdrNotifications.EXTRA_TAB_ID)
        val pane = intent.getStringExtra(HerdrNotifications.EXTRA_PANE_ID)
        return TerminalRoute(workspace, tab, pane)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // POST_NOTIFICATIONS defaults to denied on API 33+; without this every
        // relay notification is silently dropped while the user believes done /
        // blocked / failed alerts are enabled. Ask once per install.
        // Skipped on notification-tap landings: the prompt would cover the
        // deep-link target the user explicitly asked to see (grant state is
        // also surfaced in Settings with a system link).
        if (routeFromIntent(intent) == null &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val container = (application as HerdrApp).container
        deepLinkTarget.value = routeFromIntent(intent)
        setContent {
            val settings by container.settingsRepository.settings.collectAsStateWithLifecycle()
            val deepLink by deepLinkTarget.collectAsStateWithLifecycle()
            HerdrMobileTheme(themeMode = settings.themeMode, dynamicColor = settings.dynamicColor) {
                AppNav(
                    container = container,
                    startTerminal = deepLink,
                    onDeepLinkConsumed = { deepLinkTarget.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Notification deep link: open the target terminal after a foreground refresh.
        routeFromIntent(intent)?.let { deepLinkTarget.value = it }
    }
}

@Composable
private fun AppNav(
    container: AppContainer,
    startTerminal: TerminalRoute?,
    onDeepLinkConsumed: () -> Unit,
) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val destination = backStack?.destination

    // A deep link arriving while the app runs (notification tap on a live
    // singleTask activity) navigates immediately; the start destination only
    // covers cold starts.
    LaunchedEffect(startTerminal) {
        if (startTerminal != null) {
            nav.navigate(startTerminal) {
                popUpTo(HomeRoute) { inclusive = false }
                launchSingleTop = true
            }
            onDeepLinkConsumed()
        }
    }

    // Terminal is a working context, not a permanent bottom-nav destination; the bar only
    // shows Home and Settings, and Terminal hides it to give the grid every pixel.
    val showBar = destination?.hierarchy?.any { it.hasRoute(HomeRoute::class) || it.hasRoute(SettingsRoute::class) } == true

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            if (showBar) {
                NavigationBar {
                    NavigationBarItem(
                        selected = destination?.hierarchy?.any { it.hasRoute(HomeRoute::class) } == true,
                        onClick = {
                            nav.navigate(HomeRoute) {
                                popUpTo(HomeRoute) { inclusive = false }
                                launchSingleTop = true
                            }
                        },
                        icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                        label = { Text("Home") },
                        alwaysShowLabel = false,
                    )
                    NavigationBarItem(
                        selected = destination?.hierarchy?.any { it.hasRoute(SettingsRoute::class) } == true,
                        onClick = {
                            nav.navigate(SettingsRoute) {
                                popUpTo(HomeRoute) { inclusive = false }
                                launchSingleTop = true
                            }
                        },
                        icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                        label = { Text("Settings") },
                        alwaysShowLabel = false,
                    )
                }
            }
        },
    ) { padding ->
        // Static start: routing ALL links (cold start included) through the
        // LaunchedEffect above. Tying startDestination to the link (startTerminal
        // ?: HomeRoute) resets the NavHost every time the link changes — the
        // navigate-then-snap-back-to-Home bug. Cold start works identically:
        // onCreate sets the value before first composition, the effect fires
        // once and navigates.
        NavHost(
            navController = nav,
            startDestination = HomeRoute,
            modifier = Modifier.padding(padding),
        ) {
            composable<HomeRoute> {
                val vm: HomeViewModel = viewModel(
                    factory = HomeViewModel.Factory(container.client),
                )
                HomeScreen(
                    viewModel = vm,
                    onOpenWorkspace = { workspaceId, tabId ->
                        nav.navigate(TerminalRoute(workspaceId, tabId))
                    },
                    onOpenTab = { workspaceId, tabId ->
                        nav.navigate(TerminalRoute(workspaceId, tabId))
                    },
                )
            }
            composable<SettingsRoute> {
                val vm: SettingsViewModel = viewModel(
                    factory = SettingsViewModel.Factory(container.settingsRepository),
                )
                SettingsScreen(viewModel = vm)
            }
            composable<TerminalRoute> { entry ->
                val route = entry.toRoute<TerminalRoute>()
                // Key the ViewModel on the target so switching panes gets a fresh attachment.
                val key = "${route.workspaceId}/${route.tabId}/${route.paneId}"
                val vm: TerminalViewModel = viewModel(
                    key = key,
                    factory = remember(route.workspaceId, route.tabId, route.paneId) {
                        TerminalViewModel.Factory(
                            container.client,
                            route.workspaceId,
                            route.tabId,
                            route.paneId,
                            container.settingsRepository.settings,
                        )
                    },
                )
                TerminalScreen(
                    viewModel = vm,
                    onNavigateHome = {
                        nav.navigate(HomeRoute) {
                            popUpTo(HomeRoute) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                )
            }
        }
    }
}
