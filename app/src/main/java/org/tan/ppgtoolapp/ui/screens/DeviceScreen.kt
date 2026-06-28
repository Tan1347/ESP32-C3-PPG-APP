package org.tan.ppgtoolapp.ui.screens

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
import org.tan.ppgtoolapp.data.ble.BleDevice
import org.tan.ppgtoolapp.data.ble.ConnectionState
import org.tan.ppgtoolapp.data.ble.PpgGattProfile
import org.tan.ppgtoolapp.viewmodel.DeviceViewModel

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

    // Track previous connection state to detect disconnect
    var wasConnected by remember { mutableStateOf(false) }
    LaunchedEffect(connectionState) {
        val currentlyConnected = connectionState is ConnectionState.Connected
        if (wasConnected && !currentlyConnected) {
            android.widget.Toast.makeText(context, "设备连接已断开", android.widget.Toast.LENGTH_SHORT).show()
        }
        wasConnected = currentlyConnected
    }

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

    // 判断设备是否匹配前缀
    fun isMatchingDevice(device: BleDevice): Boolean {
        return PpgGattProfile.DEVICE_NAME_PREFIXES.any { prefix ->
            device.name.startsWith(prefix, ignoreCase = true)
        }
    }

    // Sort: matching devices first, then by signal strength
    val sortedDevices = remember(devices) {
        devices.sortedWith(
            compareByDescending<BleDevice> { isMatchingDevice(it) }
                .thenByDescending { it.rssi }
        )
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

        // 设备列表
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("发现的设备", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(8.dp))
            Text("(${sortedDevices.size})", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text("匹配设备置顶", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(8.dp))

        if (sortedDevices.isEmpty()) {
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
            val matchedDevices = sortedDevices.filter { isMatchingDevice(it) }
            val otherDevices = sortedDevices.filter { !isMatchingDevice(it) }

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // 匹配设备
                if (matchedDevices.isNotEmpty()) {
                    item {
                        Text(
                            "可连接设备",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                    items(
                        items = matchedDevices,
                        key = { it.address }
                    ) { device ->
                        DeviceListItem(
                            device = device,
                            isMatch = true
                        ) {
                            viewModel.connect(device)
                        }
                    }
                }

                // 其他设备
                if (otherDevices.isNotEmpty()) {
                    item {
                        Text(
                            "其他设备",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                    items(
                        items = otherDevices,
                        key = { it.address }
                    ) { device ->
                        DeviceListItem(
                            device = device,
                            isMatch = false
                        ) {
                            viewModel.connect(device)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DeviceListItem(device: BleDevice, isMatch: Boolean = false, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = if (isMatch) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.cardColors()
        }
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (isMatch) Icons.Filled.CheckCircle else Icons.Filled.Bluetooth,
                contentDescription = null,
                tint = if (isMatch) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(device.name, style = MaterialTheme.typography.bodyLarge)
                Text(device.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (isMatch) {
                Text("可连接", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
            }
            Text("${device.rssi} dBm", style = MaterialTheme.typography.bodySmall)
        }
    }
}
