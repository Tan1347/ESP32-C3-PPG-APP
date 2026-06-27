package org.tan.ppgtoolapp.data.ble

import android.bluetooth.BluetoothDevice

/**
 * BLE device info from scanning
 */
data class BleDevice(
    val name: String,
    val address: String,
    val rssi: Int
)

/**
 * BLE connection state
 */
sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Connecting : ConnectionState()
    data class Connected(val device: BluetoothDevice, val deviceName: String = "") : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}
