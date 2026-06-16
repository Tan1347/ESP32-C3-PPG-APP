package com.ppgtool.app.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
    val context = LocalContext.current
    val connectionState by viewModel.connectionState.collectAsState()
    val devices by viewModel.devices.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()

    // 权限请求
    val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    } else {
        arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            viewModel.startScan()
        }
    }

    // 设备筛选状态
    var selectedFilter by remember { mutableStateOf("全部") }
    val filterOptions = listOf("全部", "PPG-Monitor", "ESP32", "PPG")

    // 根据筛选条件过滤设备
    val filteredDevices = if (selectedFilter == "全部") {
        devices
    } else {
        devices.filter { it.name.startsWith(selectedFilter) }
    }

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
                            Text(
                                state.deviceName.ifEmpty { state.device.name ?: "未知设备" },
                                style = MaterialTheme.typography.bodyMedium
                            )
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
            onClick = {
                if (isScanning) {
                    viewModel.stopScan()
                } else {
                    // 请求权限后再扫描
                    permissionLauncher.launch(permissions)
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(if (isScanning) Icons.Filled.Stop else Icons.Filled.Search, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (isScanning) "停止扫描" else "扫描设备")
        }

        Spacer(Modifier.height(16.dp))

        // 设备筛选
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("设备类型:", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(8.dp))
            filterOptions.forEach { filter ->
                FilterChip(
                    selected = selectedFilter == filter,
                    onClick = { selectedFilter = filter },
                    label = { Text(filter) },
                    modifier = Modifier.padding(end = 4.dp)
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // 设备列表
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("发现的设备", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(8.dp))
            Text("(${filteredDevices.size})", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))

        if (filteredDevices.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.BluetoothSearching,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (isScanning) "正在扫描..." else "点击上方按钮扫描设备",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(
                    items = filteredDevices,
                    key = { it.address }
                ) { device ->
                    DeviceListItem(device = device) {
                        viewModel.connect(device)
                    }
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
