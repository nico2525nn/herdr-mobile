package dev.herdr.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import kotlinx.serialization.Serializable

@Serializable
private data object HomeRoute

@Serializable
private data object SettingsRoute

@Serializable
private data class TerminalRoute(val workspaceId: String? = null, val tabId: String? = null)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as HerdrApp).container
        // Notification deep link: open the target terminal after a foreground refresh.
        val targetWorkspace = intent.getStringExtra(HerdrNotifications.EXTRA_WORKSPACE_ID)
        val targetTab = intent.getStringExtra(HerdrNotifications.EXTRA_TAB_ID)
        setContent {
            val settings by container.settingsRepository.settings.collectAsStateWithLifecycle()
            HerdrMobileTheme(themeMode = settings.themeMode, dynamicColor = settings.dynamicColor) {
                AppNav(
                    container = container,
                    startTerminal = if (targetWorkspace != null) {
                        TerminalRoute(targetWorkspace, targetTab) to true
                    } else {
                        null
                    },
                )
            }
        }
    }
}

@Composable
private fun AppNav(
    container: AppContainer,
    startTerminal: Pair<TerminalRoute, Boolean>?,
) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val destination = backStack?.destination

    // Terminal is a working context, not a permanent bottom-nav destination; the bar only
    // shows Home and Settings, and Terminal hides it to give the grid every pixel.
    val showBar = destination?.hierarchy?.any { it.hasRoute(HomeRoute::class) || it.hasRoute(SettingsRoute::class) } == true

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            if (showBar) {
                ShortNavigationBar {
                    ShortNavigationBarItem(
                        selected = destination?.hierarchy?.any { it.hasRoute(HomeRoute::class) } == true,
                        onClick = {
                            nav.navigate(HomeRoute) {
                                popUpTo(HomeRoute) { inclusive = false }
                                launchSingleTop = true
                            }
                        },
                        icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                        label = { Text("Home") },
                    )
                    ShortNavigationBarItem(
                        selected = destination?.hierarchy?.any { it.hasRoute(SettingsRoute::class) } == true,
                        onClick = {
                            nav.navigate(SettingsRoute) {
                                popUpTo(HomeRoute) { inclusive = false }
                                launchSingleTop = true
                            }
                        },
                        icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                        label = { Text("Settings") },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = startTerminal?.first ?: HomeRoute,
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
                val key = "${route.workspaceId}/${route.tabId}"
                val vm: TerminalViewModel = viewModel(
                    key = key,
                    factory = remember(route.workspaceId, route.tabId) {
                        TerminalViewModel.Factory(
                            container.client,
                            route.workspaceId,
                            route.tabId,
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
