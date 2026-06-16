package com.ppgtool.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WifiProvisionScreen(navController: NavController) {
    var ssid by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isConnected by remember { mutableStateOf(false) }
    var connectedSsid by remember { mutableStateOf("") }
    var deviceIp by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 当前状态
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (isConnected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (isConnected) Icons.Filled.Wifi else Icons.Filled.WifiOff,
                    contentDescription = null
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(if (isConnected) "已连接" else "未连接", style = MaterialTheme.typography.titleMedium)
                    if (isConnected) {
                        Text("SSID: $connectedSsid", style = MaterialTheme.typography.bodySmall)
                        Text("IP: $deviceIp", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        HorizontalDivider()

        Text("添加 WiFi 网络", style = MaterialTheme.typography.titleMedium)

        OutlinedTextField(
            value = ssid,
            onValueChange = { ssid = it },
            label = { Text("WiFi 名称 (SSID)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("密码") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Button(
            onClick = { /* BLE 发送 WiFi 凭据 */ },
            modifier = Modifier.fillMaxWidth(),
            enabled = ssid.isNotEmpty()
        ) {
            Icon(Icons.Filled.Send, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("发送到设备")
        }

        HorizontalDivider()

        Text("已保存的 WiFi", style = MaterialTheme.typography.titleMedium)

        // TODO: 显示设备已保存的 WiFi 列表
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.padding(16.dp)) {
                Icon(Icons.Filled.Info, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Text("连接设备后可查看已保存的 WiFi 列表")
            }
        }
    }
}
