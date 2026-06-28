package org.tan.ppgtoolapp.data.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import org.tan.ppgtoolapp.data.ble.BleScannerProvider
import org.tan.ppgtoolapp.data.ble.BleConnectionProvider
import org.tan.ppgtoolapp.data.ble.BleCommandProvider

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
) : BleScannerProvider, BleConnectionProvider, BleCommandProvider {
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
        onDisconnected = { /* handled by connection module */ },
        onCharacteristicChanged = { uuid, value -> commander.handleCharacteristicChanged(uuid, value) }
    )

    // Public state flows (delegated to sub-modules)
    override val connectionState: StateFlow<ConnectionState> = connection.connectionState
    override val liveData: SharedFlow<ByteArray> = commander.liveData
    override val statusData: SharedFlow<ByteArray> = commander.statusData
    override val cmdResponse: SharedFlow<ByteArray> = commander.cmdResponse

    /**
     * Scan for BLE devices
     */
    override fun scan(useUuidFilter: Boolean): Flow<BleDevice> = scanner.scan(useUuidFilter)

    /**
     * Stop scanning
     */
    override fun stopScan() = scanner.stopScan()

    /**
     * Connect to a device
     */
    @SuppressLint("MissingPermission")
    override suspend fun connect(device: BluetoothDevice, deviceName: String): Boolean {
        connection.connect(device, deviceName)
        // Wait for connection state to become Connected or Error
        return try {
            val state = connection.connectionState.first { it is ConnectionState.Connected || it is ConnectionState.Error }
            state is ConnectionState.Connected
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Disconnect
     */
    override fun disconnect() = connection.disconnect()

    /**
     * Check if connected
     */
    override fun isConnected(): Boolean = connection.isConnected()

    /**
     * Get connected device MAC
     */
    override fun getConnectedDeviceMac(): String? = connection.getConnectedDeviceMac()

    /**
     * Get BluetoothDevice by address
     */
    @SuppressLint("MissingPermission")
    override fun getBluetoothDevice(address: String): BluetoothDevice? {
        return bluetoothAdapter?.getRemoteDevice(address)
    }

    /**
     * Write a command
     */
    override suspend fun writeCommand(command: ByteArray): Boolean {
        val gatt = connection.bluetoothGatt
        if (gatt == null || !connection.isConnected()) {
            Log.w(TAG, "writeCommand: not connected")
            return false
        }
        return commander.writeCommand(gatt, command)
    }

    /**
     * Read a characteristic
     */
    override suspend fun readCharacteristic(uuid: UUID): ByteArray? {
        val gatt = connection.bluetoothGatt
        if (gatt == null || !connection.isConnected()) {
            Log.w(TAG, "readCharacteristic: not connected")
            return null
        }
        return commander.readCharacteristic(gatt, uuid)
    }

    /**
     * Query device status
     */
    override suspend fun queryDeviceStatus(): Boolean {
        Log.d(TAG, "queryDeviceStatus: isConnected=${connection.isConnected()}, gatt=${connection.bluetoothGatt != null}")
        return writeCommand(byteArrayOf(0x22))
    }

    /**
     * Query SD card status
     */
    override suspend fun querySdCardStatus(): Boolean {
        return writeCommand(byteArrayOf(0x23))
    }

    /**
     * Query battery status
     */
    override suspend fun queryBatteryStatus(): Boolean {
        return writeCommand(byteArrayOf(0x24))
    }

    /**
     * Start UART recording
     */
    override suspend fun startUartRecord(baudRate: Int, dataBits: Int, parity: Int, stopBits: Int): Boolean {
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
    override suspend fun stopUartRecord(): Boolean {
        return writeCommand(byteArrayOf(0x51))
    }

    /**
     * Sync time to device
     * @param timestamp Unix timestamp (10 digits, seconds)
     */
    override suspend fun syncTime(timestamp: Long): Boolean {
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
    override suspend fun triggerFileDownload(): String? {
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
