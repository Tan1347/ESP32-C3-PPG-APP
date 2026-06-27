package org.tan.ppgtoolapp.data.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.*
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * BLE scanner module - handles device discovery
 */
class BleScanner(
    private val bluetoothAdapter: BluetoothAdapter?
) {
    companion object {
        private const val TAG = "BleScanner"
    }

    private var currentScanner: BluetoothLeScanner? = null
    private var currentScanCallback: ScanCallback? = null

    /**
     * Scan for BLE devices
     * @param useUuidFilter whether to filter by PPG service UUID
     */
    @SuppressLint("MissingPermission")
    fun scan(useUuidFilter: Boolean = false): Flow<BleDevice> = callbackFlow {
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            Log.e(TAG, "BLE Scanner not available")
            close()
            return@callbackFlow
        }

        currentScanner = scanner
        var totalResults = 0
        val uniqueDevices = mutableSetOf<String>()
        val matchedDevices = mutableSetOf<String>()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val name = result.scanRecord?.deviceName ?: device.name ?: "Unknown"
                val address = device.address
                totalResults++

                val isNewDevice = uniqueDevices.add(address)
                val isMatch = PpgGattProfile.DEVICE_NAME_PREFIXES.any { prefix ->
                    name.startsWith(prefix, ignoreCase = true)
                }
                if (isMatch) {
                    matchedDevices.add(address)
                    if (isNewDevice) {
                        Log.i(TAG, "Matched device: $name ($address)")
                    }
                }
                trySend(BleDevice(name, address, result.rssi))
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "Scan failed: $errorCode")
                currentScanner = null
                currentScanCallback = null
                close()
            }
        }

        currentScanCallback = callback

        val filters = if (useUuidFilter) {
            listOf(ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(PpgGattProfile.SERVICE_UUID))
                .build())
        } else {
            emptyList()
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .build()

        scanner.startScan(filters, settings, callback)

        awaitClose {
            Log.i(TAG, "Scan stopped: ${uniqueDevices.size} devices, ${matchedDevices.size} matched")
            try { scanner.stopScan(callback) } catch (e: Exception) { }
            currentScanner = null
            currentScanCallback = null
        }
    }

    /**
     * Stop scanning
     */
    @SuppressLint("MissingPermission")
    fun stopScan() {
        val scanner = currentScanner
        val callback = currentScanCallback
        if (scanner != null && callback != null) {
            try { scanner.stopScan(callback) } catch (e: Exception) { }
        }
        currentScanner = null
        currentScanCallback = null
    }
}
