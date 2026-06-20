package org.tan.ppgtoolapp.ui.screens

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import org.tan.ppgtoolapp.data.wifi.WifiNetwork
import org.tan.ppgtoolapp.viewmodel.ConnectionResult
import org.tan.ppgtoolapp.viewmodel.WifiProvisionViewModel

@Composable
fun WifiProvisionScreen(
    navController: NavController,
    viewModel: WifiProvisionViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()

    // 显示错误提示
    LaunchedEffect(state.error) {
        state.error?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.clearError()
        }
    }

    // 显示连接结果
    LaunchedEffect(state.connectionResult) {
        state.connectionResult?.let {
            when (it) {
                is ConnectionResult.Success -> Toast.makeText(context, it.message, Toast.LENGTH_LONG).show()
                is ConnectionResult.Error -> Toast.makeText(context, it.message, Toast.LENGTH_LONG).show()
            }
            viewModel.clearResult()
        }
    }

    // WiFi 密码输入对话框
    if (state.selectedNetwork != null) {
        WifiPasswordDialog(
            network = state.selectedNetwork!!,
            password = state.password,
            onPasswordChange = { viewModel.updatePassword(it) },
            onConfirm = { viewModel.sendWifiCredentials() },
            onDismiss = { viewModel.dismissSelection() },
            isConnecting = state.isConnecting
        )
    }

    // 开启 WiFi 提示对话框
    if (state.showEnableWifiDialog) {
        EnableWifiDialog(
            onConfirm = {
                viewModel.dismissEnableWifiDialog()
                // 打开系统 WiFi 设置
                val intent = Intent(Settings.ACTION_WIFI_SETTINGS)
                context.startActivity(intent)
            },
            onDismiss = { viewModel.dismissEnableWifiDialog() }
        )
    }

    // 手动添加 WiFi 对话框
    if (state.showManualAddDialog) {
        ManualWifiDialog(
            ssid = state.manualSsid,
            password = state.manualPassword,
            onSsidChange = { viewModel.updateManualSsid(it) },
            onPasswordChange = { viewModel.updateManualPassword(it) },
            onConfirm = { viewModel.sendManualWifiCredentials() },
            onDismiss = { viewModel.dismissManualAddDialog() },
            isConnecting = state.isConnecting
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 扫描和手动添加按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { viewModel.scanNetworks() },
                modifier = Modifier.weight(1f),
                enabled = !state.isScanning
            ) {
                if (state.isScanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("扫描中...")
                } else {
                    Icon(Icons.Filled.WifiFind, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("扫描")
                }
            }

            OutlinedButton(
                onClick = { viewModel.showManualAddDialog() },
                modifier = Modifier.weight(1f),
                enabled = !state.isScanning
            ) {
                Icon(Icons.Filled.Edit, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("手动添加")
            }
        }

        // 网络列表
        if (state.networks.isNotEmpty()) {
            Text(
                "发现 ${state.networks.size} 个网络",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(
                    items = state.networks,
                    key = { it.ssid }
                ) { network ->
                    WifiNetworkItem(
                        network = network,
                        onClick = { viewModel.selectNetwork(network) }
                    )
                }
            }
        } else if (!state.isScanning) {
            // 空状态
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Filled.WifiOff,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("点击上方按钮扫描附近 WiFi")
                }
            }
        }
    }
}

@Composable
private fun WifiNetworkItem(
    network: WifiNetwork,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 信号强度图标
            Icon(
                when (network.signalLevel) {
                    4 -> Icons.Filled.Wifi
                    3 -> Icons.Filled.Wifi
                    2 -> Icons.Filled.Wifi
                    1 -> Icons.Filled.WifiFind
                    else -> Icons.Filled.WifiOff
                },
                contentDescription = null,
                tint = when {
                    network.signalLevel >= 3 -> MaterialTheme.colorScheme.primary
                    network.signalLevel >= 2 -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.error
                },
                modifier = Modifier.size(28.dp)
            )

            Spacer(Modifier.width(12.dp))

            // 网络信息
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    network.ssid,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    buildString {
                        append("${network.frequency / 1000.0} GHz")
                        if (network.isSecure) append(" · 已加密")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 信号强度数值
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "${network.rssi} dBm",
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        network.rssi >= -50 -> MaterialTheme.colorScheme.primary
                        network.rssi >= -70 -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.error
                    }
                )
                Text(
                    when (network.signalLevel) {
                        4 -> "强"
                        3 -> "较强"
                        2 -> "中等"
                        1 -> "较弱"
                        else -> "弱"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.width(8.dp))

            // 安全状态
            if (network.isSecure) {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun WifiPasswordDialog(
    network: WifiNetwork,
    password: String,
    onPasswordChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    isConnecting: Boolean
) {
    var showPassword by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!isConnecting) onDismiss() },
        title = { Text("连接到 ${network.ssid}") },
        text = {
            Column {
                Text(
                    "信号强度: ${
                        when (network.signalLevel) {
                            4 -> "强"
                            3 -> "较强"
                            2 -> "中等"
                            1 -> "较弱"
                            else -> "弱"
                        }
                    }",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(12.dp))

                if (network.isSecure) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = onPasswordChange,
                        label = { Text("密码") },
                        singleLine = true,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    if (showPassword) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                    contentDescription = null
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isConnecting
                    )
                } else {
                    Text("这是一个开放网络，无需密码")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !isConnecting && (!network.isSecure || password.isNotEmpty())
            ) {
                if (isConnecting) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(4.dp))
                    Text("发送中...")
                } else {
                    Text("连接")
                }
            }
        },
        dismissButton = {
            if (!isConnecting) {
                TextButton(onClick = onDismiss) {
                    Text("取消")
                }
            }
        }
    )
}

@Composable
private fun EnableWifiDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.WifiOff, contentDescription = null) },
        title = { Text("WiFi 未开启") },
        text = { Text("扫描 WiFi 需要开启无线网络，是否前往系统设置开启 WiFi？") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("去开启")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun ManualWifiDialog(
    ssid: String,
    password: String,
    onSsidChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    isConnecting: Boolean
) {
    var showPassword by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!isConnecting) onDismiss() },
        icon = { Icon(Icons.Filled.Wifi, contentDescription = null) },
        title = { Text("手动添加 WiFi") },
        text = {
            Column {
                Text(
                    "适用于隐藏网络或扫描不到的 WiFi",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = ssid,
                    onValueChange = onSsidChange,
                    label = { Text("WiFi 名称 (SSID)") },
                    placeholder = { Text("输入 WiFi 名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isConnecting
                )

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = password,
                    onValueChange = onPasswordChange,
                    label = { Text("密码") },
                    placeholder = { Text("输入密码（开放网络留空）") },
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                contentDescription = null
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isConnecting
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !isConnecting && ssid.isNotBlank()
            ) {
                if (isConnecting) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(4.dp))
                    Text("发送中...")
                } else {
                    Text("连接")
                }
            }
        },
        dismissButton = {
            if (!isConnecting) {
                TextButton(onClick = onDismiss) {
                    Text("取消")
                }
            }
        }
    )
}
