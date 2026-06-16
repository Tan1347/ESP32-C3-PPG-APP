package com.ppgtool.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.ppgtool.app.data.network.ReleaseInfo
import com.ppgtool.app.ui.components.InstallConfirmDialog
import com.ppgtool.app.ui.components.DownloadProgressDialog
import com.ppgtool.app.ui.components.UpdateAvailableDialog
import com.ppgtool.app.ui.navigation.Screen
import com.ppgtool.app.viewmodel.SettingsViewModel
import com.ppgtool.app.viewmodel.TimeSyncResult

@Composable
fun SettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val updateState by viewModel.updateState.collectAsState()
    val timeSyncState by viewModel.timeSyncState.collectAsState()

    // 显示更新对话框
    if (updateState.showDialog && updateState.releaseInfo != null) {
        UpdateAvailableDialog(
            releaseInfo = updateState.releaseInfo!!,
            currentVersion = viewModel.getCurrentVersion(),
            onUpdate = { viewModel.startUpdate() },
            onDismiss = { viewModel.dismissUpdateDialog() }
        )
    }

    // 显示下载进度对话框
    if (updateState.isDownloading) {
        DownloadProgressDialog(
            progress = updateState.progress,
            progressText = updateState.progressText,
            onDismiss = { viewModel.dismissProgressDialog() }
        )
    }

    // 显示安装确认对话框
    if (updateState.showInstallDialog) {
        InstallConfirmDialog(
            onInstall = { viewModel.installUpdate() },
            onDismiss = { viewModel.dismissInstallDialog() }
        )
    }

    // 显示错误提示
    LaunchedEffect(updateState.error) {
        updateState.error?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.clearError()
        }
    }

    // 显示时间同步结果
    LaunchedEffect(timeSyncState.result) {
        timeSyncState.result?.let {
            when (it) {
                is TimeSyncResult.Success -> Toast.makeText(context, "时间同步成功: ${it.timeStr}", Toast.LENGTH_LONG).show()
                is TimeSyncResult.Error -> Toast.makeText(context, it.message, Toast.LENGTH_LONG).show()
            }
            viewModel.clearTimeSyncResult()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("设备设置", style = MaterialTheme.typography.titleMedium)

        SettingsItem(
            icon = Icons.Filled.Wifi,
            title = "WiFi 配网",
            subtitle = "管理设备 WiFi 连接",
            onClick = { navController.navigate(Screen.WifiProvision.route) }
        )

        SettingsItem(
            icon = Icons.Filled.SystemUpdate,
            title = "固件升级 (OTA)",
            subtitle = "上传新固件到设备",
            onClick = { navController.navigate(Screen.OtaUpgrade.route) }
        )

        SettingsItem(
            icon = Icons.Filled.Schedule,
            title = "同步时间",
            subtitle = if (timeSyncState.isSyncing) "正在同步..." else {
                if (timeSyncState.lastSyncTime.isNotEmpty()) "上次同步: ${timeSyncState.lastSyncTime}" else "将手机时间同步到设备"
            },
            onClick = { viewModel.syncTime() }
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Text("关于", style = MaterialTheme.typography.titleMedium)

        SettingsItem(
            icon = Icons.Filled.Update,
            title = "检查更新",
            subtitle = if (updateState.isChecking) "正在检查..." else "当前版本: ${viewModel.getCurrentVersion()}",
            onClick = { viewModel.checkForUpdate() }
        )
    }
}

@Composable
fun SettingsItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = null)
        }
    }
}
