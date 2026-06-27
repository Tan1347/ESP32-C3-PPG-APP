package org.tan.ppgtoolapp.data.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BLE Manager - facade that delegates to specialized modules
 *
 * BleScanner: device discovery
 * BleConnection: connect, disconnect, auto-reconnect
 * BleCommander: command writing, characteristic reading, notifications
 */
@Singleton
@Suppress("DEPRECATION")
class BleManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "BleManager"
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    // Sub-modules
    private val scanner = BleScanner(bluetoothAdapter)
    private val commander = BleCommander()
    private val connection = BleConnection(
        context = context,
        onConnected = { gatt -> commander.enableNotifications(gatt) },
        onDisconnected = { /* handled by connection module */ }
    )

    // Public state flows (delegated to sub-modules)
    val connectionState: StateFlow<ConnectionState> = connection.connectionState
    val liveData: SharedFlow<ByteArray> = commander.liveData
    val statusData: SharedFlow<ByteArray> = commander.statusData
    val cmdResponse: SharedFlow<ByteArray> = commander.cmdResponse

    /**
     * Scan for BLE devices
     */
    fun scan(useUuidFilter: Boolean = false): Flow<BleDevice> = scanner.scan(useUuidFilter)

    /**
     * Stop scanning
     */
    fun stopScan() = scanner.stopScan()

    /**
     * Connect to a device
     */
    @SuppressLint("MissingPermission")
    suspend fun connect(device: BluetoothDevice, deviceName: String = ""): Boolean {
        connection.connect(device, deviceName)
        // Wait for connection state to become Connected
        return kotlinx.coroutines.flow.firstNotNullOfOrNull { state ->
            when (state) {
                is ConnectionState.Connected -> true
                is ConnectionState.Error -> false
                else -> null
            }
        } ?: false
    }

    /**
     * Disconnect
     */
    fun disconnect() = connection.disconnect()

    /**
     * Check if connected
     */
    fun isConnected(): Boolean = connection.isConnected()

    /**
     * Get connected device MAC
     */
    fun getConnectedDeviceMac(): String? = connection.getConnectedDeviceMac()

    /**
     * Get BluetoothDevice by address
     */
    @SuppressLint("MissingPermission")
    fun getBluetoothDevice(address: String): BluetoothDevice? {
        return bluetoothAdapter?.getRemoteDevice(address)
    }

    /**
     * Write a command
     */
    suspend fun writeCommand(command: ByteArray): Boolean =
        commander.writeCommand(connection.bluetoothGatt, command)

    /**
     * Read a characteristic
     */
    suspend fun readCharacteristic(uuid: UUID): ByteArray? =
        commander.readCharacteristic(connection.bluetoothGatt, uuid)

    /**
     * Query device status
     */
    suspend fun queryDeviceStatus(): Boolean {
        return writeCommand(byteArrayOf(0x01))
    }

    /**
     * Query SD card status
     */
    suspend fun querySdCardStatus(): Boolean {
        return writeCommand(byteArrayOf(0x23))
    }

    /**
     * Query battery status
     */
    suspend fun queryBatteryStatus(): Boolean {
        return writeCommand(byteArrayOf(0x24))
    }

    /**
     * Start UART recording
     */
    suspend fun startUartRecord(baudRate: Int, dataBits: Int, parity: Int, stopBits: Int): Boolean {
        val cmd = byteArrayOf(
            0x50,
            ((baudRate shr 24) and 0xFF).toByte(),
            ((baudRate shr 16) and 0xFF).toByte(),
            ((baudRate shr 8) and 0xFF).toByte(),
            (baudRate and 0xFF).toByte(),
            dataBits.toByte(),
            parity.toByte(),
            stopBits.toByte()
        )
        return writeCommand(cmd)
    }

    /**
     * Stop UART recording
     */
    suspend fun stopUartRecord(): Boolean {
        return writeCommand(byteArrayOf(0x51))
    }

    /**
     * Sync time to device
     * @param timestamp Unix timestamp (10 digits, seconds)
     */
    suspend fun syncTime(timestamp: Long): Boolean {
        val cmd = byteArrayOf(
            0x06,
            ((timestamp shr 24) and 0xFF).toByte(),
            ((timestamp shr 16) and 0xFF).toByte(),
            ((timestamp shr 8) and 0xFF).toByte(),
            (timestamp and 0xFF).toByte()
        )
        return writeCommand(cmd)
    }

    /**
     * Trigger file download
     */
    suspend fun triggerFileDownload(): String? {
        if (!writeCommand(byteArrayOf(0x32))) return null
        // Wait for response with IP
        val response = kotlinx.coroutines.withTimeoutOrNull(3000L) {
            commander.cmdResponse.first()
        }
        return response?.let { String(it).trim() }
    }

    /**
     * Handle characteristic read callback (called from GATT callback)
     */
    fun onCharacteristicRead(uuid: UUID, value: ByteArray?) {
        commander.handleCharacteristicRead(uuid, value)
    }

    /**
     * Handle characteristic changed callback (called from GATT callback)
     */
    fun onCharacteristicChanged(uuid: UUID, value: ByteArray?) {
        commander.handleCharacteristicChanged(uuid, value)
    }
}
