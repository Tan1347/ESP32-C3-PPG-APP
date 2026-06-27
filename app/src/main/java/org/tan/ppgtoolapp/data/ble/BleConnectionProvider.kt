package org.tan.ppgtoolapp.data.ble

import android.bluetooth.BluetoothDevice
import kotlinx.coroutines.flow.StateFlow

/**
 * BLE 连接生命周期管理接口
 */
interface BleConnectionProvider {
    val connectionState: StateFlow<ConnectionState>
    suspend fun connect(device: BluetoothDevice, deviceName: String = ""): Boolean
    fun disconnect()
    fun isConnected(): Boolean
    fun getConnectedDeviceMac(): String?
}
