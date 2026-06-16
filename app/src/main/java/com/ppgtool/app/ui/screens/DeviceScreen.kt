package com.ppgtool.app.ui.screens

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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.ppgtool.app.data.ble.BleDevice
import com.ppgtool.app.data.ble.ConnectionState
import com.ppgtool.app.viewmodel.DeviceViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceScreen(
    navController: NavController,
    viewModel: DeviceViewModel = hiltViewModel()
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val devices by viewModel.devices.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // 连接状态
        when (val state = connectionState) {
            is ConnectionState.Connected -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Bluetooth, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("已连接", style = MaterialTheme.typography.titleMedium)
                            Text(state.device.address, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { viewModel.disconnect() }) {
                            Text("断开")
                        }
                    }
                }
            }
            is ConnectionState.Connecting -> {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("正在连接...")
                    }
                }
            }
            else -> {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.BluetoothDisabled, contentDescription = null)
                        Spacer(Modifier.width(12.dp))
                        Text("未连接设备")
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // 扫描按钮
        Button(
            onClick = { if (isScanning) viewModel.stopScan() else viewModel.startScan() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(if (isScanning) Icons.Filled.Stop else Icons.Filled.Search, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (isScanning) "停止扫描" else "扫描设备")
        }

        Spacer(Modifier.height(16.dp))

        // 设备列表
        Text("发现的设备", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(devices) { device ->
                DeviceListItem(device = device) {
                    viewModel.connect(device)
                }
            }
        }
    }
}

@Composable
fun DeviceListItem(device: BleDevice, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Bluetooth, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(device.name, style = MaterialTheme.typography.bodyLarge)
                Text(device.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("${device.rssi} dBm", style = MaterialTheme.typography.bodySmall)
        }
    }
}
