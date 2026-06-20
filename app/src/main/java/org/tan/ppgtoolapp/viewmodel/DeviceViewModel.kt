package org.tan.ppgtoolapp.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.tan.ppgtoolapp.data.ble.BleDevice
import org.tan.ppgtoolapp.data.ble.BleManager
import org.tan.ppgtoolapp.data.ble.ConnectionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "DeviceViewModel"

@HiltViewModel
class DeviceViewModel @Inject constructor(
    private val bleManager: BleManager
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = bleManager.connectionState

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _devices = MutableStateFlow<List<BleDevice>>(emptyList())
    val devices: StateFlow<List<BleDevice>> = _devices.asStateFlow()

    private val discoveredDevices = mutableListOf<BleDevice>()

    fun startScan() {
        Log.i(TAG, "开始扫描")
        _isScanning.value = true
        discoveredDevices.clear()
        _devices.value = emptyList()

        viewModelScope.launch {
            try {
                var lastUpdateTime = 0L
                val updateInterval = 1000L // 1秒更新一次

                // 不使用 UUID 过滤，扫描所有 BLE 设备
                bleManager.scan(useUuidFilter = false).collect { device ->
                    val existingIndex = discoveredDevices.indexOfFirst { it.address == device.address }
                    if (existingIndex == -1) {
                        // 新设备，添加到列表
                        Log.d(TAG, "添加设备: ${device.name} (${device.address})")
                        discoveredDevices.add(device)
                    } else {
                        // 已存在的设备，更新 RSSI
                        discoveredDevices[existingIndex] = device
                    }

                    // 限制 UI 更新频率：最多每秒更新一次
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastUpdateTime >= updateInterval) {
                        lastUpdateTime = currentTime
                        _devices.value = discoveredDevices.toList()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "扫描异常: ${e.message}", e)
            }
        }
    }

    fun stopScan() {
        Log.i(TAG, "停止扫描")
        _isScanning.value = false
        bleManager.stopScan()
        // 停止扫描时更新一次 UI，确保所有设备都显示
        _devices.value = discoveredDevices.toList()
    }

    fun connect(device: BleDevice) {
        Log.i(TAG, "连接设备: ${device.name} (${device.address})")
        viewModelScope.launch {
            try {
                val btDevice = bleManager.getBluetoothDevice(device.address)
                if (btDevice != null) {
                    val success = bleManager.connect(btDevice, device.name)
                    Log.i(TAG, "连接结果: $success")
                } else {
                    Log.e(TAG, "无法获取 BluetoothDevice")
                }
            } catch (e: Exception) {
                Log.e(TAG, "连接异常: ${e.message}", e)
            }
        }
    }

    fun disconnect() {
        Log.i(TAG, "断开连接")
        bleManager.disconnect()
    }
}
