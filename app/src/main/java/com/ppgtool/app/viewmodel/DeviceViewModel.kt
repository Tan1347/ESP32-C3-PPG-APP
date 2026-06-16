package com.ppgtool.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ppgtool.app.data.ble.BleDevice
import com.ppgtool.app.data.ble.BleManager
import com.ppgtool.app.data.ble.ConnectionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

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
        _isScanning.value = true
        discoveredDevices.clear()
        _devices.value = emptyList()

        viewModelScope.launch {
            bleManager.scan().collect { device ->
                if (discoveredDevices.none { it.address == device.address }) {
                    discoveredDevices.add(device)
                    _devices.value = discoveredDevices.toList()
                }
            }
        }
    }

    fun stopScan() {
        _isScanning.value = false
    }

    fun connect(device: BleDevice) {
        viewModelScope.launch {
            val btDevice = bleManager.connectionState.value.let {
                android.bluetooth.BluetoothAdapter.getDefaultAdapter()
                    ?.getRemoteDevice(device.address)
            }
            btDevice?.let { bleManager.connect(it) }
        }
    }

    fun disconnect() {
        bleManager.disconnect()
    }
}
