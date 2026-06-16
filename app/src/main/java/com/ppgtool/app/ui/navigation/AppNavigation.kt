package com.ppgtool.app.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.ppgtool.app.ui.screens.*

sealed class Screen(val route: String, val title: String) {
    data object Device : Screen("device", "设备")
    data object Monitor : Screen("monitor", "监控")
    data object Data : Screen("data", "数据")
    data object Settings : Screen("settings", "设置")
    data object WifiProvision : Screen("wifi_provision", "WiFi配网")
    data object OtaUpgrade : Screen("ota_upgrade", "OTA升级")
}

// 主页路由（显示底部导航栏）
private val mainRoutes = setOf(
    Screen.Monitor.route,
    Screen.Data.route,
    Screen.Settings.route
)

data class BottomNavItem(
    val screen: Screen,
    val icon: @Composable () -> Unit,
    val label: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation() {
    val navController = rememberNavController()

    val bottomNavItems = listOf(
        BottomNavItem(Screen.Monitor, { Icon(Icons.Filled.Monitor, contentDescription = null) }, "监控"),
        BottomNavItem(Screen.Data, { Icon(Icons.Filled.Storage, contentDescription = null) }, "数据"),
        BottomNavItem(Screen.Settings, { Icon(Icons.Filled.Settings, contentDescription = null) }, "设置"),
    )

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val isMainScreen = currentDestination?.route in mainRoutes

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (currentDestination?.route) {
                            Screen.WifiProvision.route -> Screen.WifiProvision.title
                            Screen.OtaUpgrade.route -> Screen.OtaUpgrade.title
                            else -> "PPG Monitor"
                        }
                    )
                },
                navigationIcon = {
                    if (!isMainScreen) {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        },
        bottomBar = {
            if (isMainScreen) {
                NavigationBar {
                    bottomNavItems.forEach { item ->
                        NavigationBarItem(
                            icon = item.icon,
                            label = { Text(item.label) },
                            selected = currentDestination?.hierarchy?.any { it.route == item.screen.route } == true,
                            onClick = {
                                navController.navigate(item.screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Monitor.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Device.route) { DeviceScreen(navController) }
            composable(Screen.Monitor.route) {
                MonitorScreen(
                    onNavigateToDevice = { navController.navigate(Screen.Device.route) }
                )
            }
            composable(Screen.Data.route) { DataScreen() }
            composable(Screen.Settings.route) { SettingsScreen(navController) }
            composable(Screen.WifiProvision.route) { WifiProvisionScreen(navController) }
            composable(Screen.OtaUpgrade.route) { OtaScreen(navController) }
        }
    }
}
