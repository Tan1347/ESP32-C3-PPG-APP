package org.tan.ppgtoolapp.ui.screens

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
import org.tan.ppgtoolapp.data.network.ReleaseInfo
import org.tan.ppgtoolapp.ui.components.InstallConfirmDialog
import org.tan.ppgtoolapp.ui.components.DownloadProgressDialog
import org.tan.ppgtoolapp.ui.components.UpdateAvailableDialog
import org.tan.ppgtoolapp.ui.navigation.Screen
import org.tan.ppgtoolapp.viewmodel.SettingsViewModel
import org.tan.ppgtoolapp.viewmodel.TimeSyncResult

@Composable
fun SettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val updateState by viewModel.updateState.collectAsState()
    val timeSyncState by viewModel.timeSyncState.collectAsState()
    val uartState by viewModel.uartState.collectAsState()

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

    // 显示串口记录结果
    LaunchedEffect(uartState.result) {
        uartState.result?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearUartResult()
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

        // 串口数据记录
        Text("串口数据记录", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))

        // 波特率选择
        var baudExpanded by remember { mutableStateOf(false) }
        Text("波特率", style = MaterialTheme.typography.bodySmall)
        Box {
            OutlinedButton(
                onClick = { baudExpanded = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("${uartState.selectedBaudRate}")
                Spacer(Modifier.weight(1f))
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = baudExpanded, onDismissRequest = { baudExpanded = false }) {
                SettingsViewModel.BAUD_RATES.forEach { rate ->
                    DropdownMenuItem(
                        text = { Text("$rate") },
                        onClick = { viewModel.setBaudRate(rate); baudExpanded = false }
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        // 数据位、校验、停止位 (一行三列)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // 数据位
            var dbExpanded by remember { mutableStateOf(false) }
            Column(modifier = Modifier.weight(1f)) {
                Text("数据位", style = MaterialTheme.typography.bodySmall)
                Box {
                    OutlinedButton(onClick = { dbExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("${uartState.selectedDataBits}")
                    }
                    DropdownMenu(expanded = dbExpanded, onDismissRequest = { dbExpanded = false }) {
                        listOf(5, 6, 7, 8).forEach { bits ->
                            DropdownMenuItem(text = { Text("$bits") },
                                onClick = { viewModel.setDataBits(bits); dbExpanded = false })
                        }
                    }
                }
            }

            // 校验位
            var parExpanded by remember { mutableStateOf(false) }
            Column(modifier = Modifier.weight(1f)) {
                Text("校验", style = MaterialTheme.typography.bodySmall)
                Box {
                    OutlinedButton(onClick = { parExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(when (uartState.selectedParity) { 0 -> "无"; 1 -> "偶"; 2 -> "奇"; else -> "无" })
                    }
                    DropdownMenu(expanded = parExpanded, onDismissRequest = { parExpanded = false }) {
                        listOf(0 to "无", 1 to "偶", 2 to "奇").forEach { (value, label) ->
                            DropdownMenuItem(text = { Text(label) },
                                onClick = { viewModel.setParity(value); parExpanded = false })
                        }
                    }
                }
            }

            // 停止位
            var stopExpanded by remember { mutableStateOf(false) }
            Column(modifier = Modifier.weight(1f)) {
                Text("停止位", style = MaterialTheme.typography.bodySmall)
                Box {
                    OutlinedButton(onClick = { stopExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("${uartState.selectedStopBits}")
                    }
                    DropdownMenu(expanded = stopExpanded, onDismissRequest = { stopExpanded = false }) {
                        listOf(1, 2).forEach { bits ->
                            DropdownMenuItem(text = { Text("$bits") },
                                onClick = { viewModel.setStopBits(bits); stopExpanded = false })
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // 开始/停止按钮
        val configStr = "${uartState.selectedBaudRate} ${uartState.selectedDataBits}${when(uartState.selectedParity){0->"N";1->"E";2->"O";else->"N"}}${uartState.selectedStopBits}"
        if (uartState.isRecording) {
            Button(
                onClick = { viewModel.stopUartRecord() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Filled.Stop, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("停止记录 ($configStr)")
            }
        } else {
            Button(
                onClick = { viewModel.startUartRecord() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.FiberManualRecord, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("开始记录 ($configStr)")
            }
        }

        Text(
            "数据存储到 TF 卡 /uart0/ 目录，单文件 10MB 限制",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
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
