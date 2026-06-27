package org.tan.ppgtoolapp.ui.navigation

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
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import org.tan.ppgtoolapp.ui.screens.*

sealed class Screen(val route: String, val title: String) {
    data object Device : Screen("device", "设备")
    data object Monitor : Screen("monitor", "监控")
    data object Data : Screen("data", "数据")
    data object Settings : Screen("settings", "设置")
    data object WifiProvision : Screen("wifi_provision", "WiFi配网")
    data object OtaUpgrade : Screen("ota_upgrade", "OTA升级")
    data object Analysis : Screen("analysis/{encodedPath}/{encodedName}", "数据分析")
    data object RemoteFiles : Screen("remote_files", "远端文件")
    data object UartRecord : Screen("uart_record", "串口记录")
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
    val isAnalysisScreen = currentDestination?.route?.startsWith("analysis") == true

    Scaffold(
        topBar = {
            if (!isAnalysisScreen) {
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
            }
        },
        bottomBar = {
            if (isMainScreen && !isAnalysisScreen) {
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
            composable(Screen.Data.route) {
                DataScreen(
                    onNavigateToAnalysis = { filePath, fileName ->
                        val encodedPath = java.net.URLEncoder.encode(filePath, "UTF-8")
                        val encodedName = java.net.URLEncoder.encode(fileName, "UTF-8")
                        navController.navigate("analysis/$encodedPath/$encodedName")
                    },
                    onNavigateToRemoteFiles = { navController.navigate(Screen.RemoteFiles.route) },
                    onNavigateToUartRecord = { navController.navigate(Screen.UartRecord.route) }
                )
            }
            composable(Screen.RemoteFiles.route) {
                RemoteFileScreen(
                    onNavigateToAnalysis = { filePath, fileName ->
                        val encodedPath = java.net.URLEncoder.encode(filePath, "UTF-8")
                        val encodedName = java.net.URLEncoder.encode(fileName, "UTF-8")
                        navController.navigate("analysis/$encodedPath/$encodedName")
                    }
                )
            }
            composable(Screen.UartRecord.route) { UartRecordScreen() }
            composable(Screen.Settings.route) { SettingsScreen(navController) }
            composable(Screen.WifiProvision.route) { WifiProvisionScreen(navController) }
            composable(Screen.OtaUpgrade.route) { OtaScreen(navController) }
            composable(
                route = Screen.Analysis.route,
                arguments = listOf(
                    navArgument("encodedPath") { type = NavType.StringType },
                    navArgument("encodedName") { type = NavType.StringType }
                )
            ) { backStackEntry ->
                val encodedPath = backStackEntry.arguments?.getString("encodedPath") ?: ""
                val encodedName = backStackEntry.arguments?.getString("encodedName") ?: ""
                val filePath = java.net.URLDecoder.decode(encodedPath, "UTF-8")
                val fileName = java.net.URLDecoder.decode(encodedName, "UTF-8")
                AnalysisScreen(
                    filePath = filePath,
                    fileName = fileName,
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}
