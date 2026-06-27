package org.tan.ppgtoolapp.data.ble

import android.bluetooth.BluetoothDevice
import kotlinx.coroutines.flow.Flow

/**
 * BLE 设备扫描能力接口
 */
interface BleScannerProvider {
    fun scan(useUuidFilter: Boolean = false): Flow<BleDevice>
    fun stopScan()
    fun getBluetoothDevice(address: String): BluetoothDevice?
}
