package com.ppgtool.app.data.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

data class BleDevice(
    val name: String,
    val address: String,
    val rssi: Int
)

sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Connecting : ConnectionState()
    data class Connected(val device: BluetoothDevice) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

@Singleton
class BleManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var bluetoothGatt: BluetoothGatt? = null

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _liveData = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val liveData: SharedFlow<ByteArray> = _liveData.asSharedFlow()

    private val _statusData = MutableSharedFlow<ByteArray>(extraBufferCapacity = 16)
    val statusData: SharedFlow<ByteArray> = _statusData.asSharedFlow()

    @SuppressLint("MissingPermission")
    fun scan(): Flow<BleDevice> = callbackFlow {
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            close()
            return@callbackFlow
        }

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val name = result.scanRecord?.deviceName ?: device.name ?: "Unknown"
                if (name.startsWith(PpgGattProfile.DEVICE_NAME_PREFIX)) {
                    trySend(BleDevice(name, device.address, result.rssi))
                }
            }
        }

        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(PpgGattProfile.SERVICE_UUID))
                .build()
        )

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(filters, settings, callback)

        awaitClose {
            scanner.stopScan(callback)
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun connect(device: BluetoothDevice): Boolean {
        _connectionState.value = ConnectionState.Connecting

        return suspendCancellableCoroutine { continuation ->
            val gattCallback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    when (newState) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            bluetoothGatt = gatt
                            _connectionState.value = ConnectionState.Connected(device)
                            gatt.discoverServices()
                            if (continuation.isActive) continuation.resume(true)
                        }
                        BluetoothProfile.STATE_DISCONNECTED -> {
                            _connectionState.value = ConnectionState.Disconnected
                            bluetoothGatt = null
                            if (continuation.isActive) continuation.resume(false)
                        }
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        enableNotifications(gatt)
                    }
                }

                override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                    when (characteristic.uuid) {
                        PpgGattProfile.CHAR_LIVE_DATA -> {
                            characteristic.value?.let { _liveData.tryEmit(it) }
                        }
                        PpgGattProfile.CHAR_STATUS -> {
                            characteristic.value?.let { _statusData.tryEmit(it) }
                        }
                    }
                }

                override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        when (characteristic.uuid) {
                            PpgGattProfile.CHAR_STATUS -> characteristic.value?.let { _statusData.tryEmit(it) }
                        }
                    }
                }
            }

            device.connectGatt(context, false, gattCallback)
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotifications(gatt: BluetoothGatt) {
        val service = gatt.getService(PpgGattProfile.SERVICE_UUID) ?: return

        // 启用 Live Data Notify
        service.getCharacteristic(PpgGattProfile.CHAR_LIVE_DATA)?.let { char ->
            gatt.setCharacteristicNotification(char, true)
            char.getDescriptor(PpgGattProfile.DESCRIPTOR_CCC)?.let { desc ->
                desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(desc)
            }
        }

        // 启用 Status Notify
        service.getCharacteristic(PpgGattProfile.CHAR_STATUS)?.let { char ->
            gatt.setCharacteristicNotification(char, true)
            char.getDescriptor(PpgGattProfile.DESCRIPTOR_CCC)?.let { desc ->
                desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(desc)
            }
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun writeCommand(command: ByteArray): Boolean {
        val gatt = bluetoothGatt ?: return false
        val service = gatt.getService(PpgGattProfile.SERVICE_UUID) ?: return false
        val char = service.getCharacteristic(PpgGattProfile.CHAR_COMMAND) ?: return false

        char.value = command
        return gatt.writeCharacteristic(char)
    }

    @SuppressLint("MissingPermission")
    suspend fun readCharacteristic(uuid: UUID): ByteArray? {
        val gatt = bluetoothGatt ?: return null
        val service = gatt.getService(PpgGattProfile.SERVICE_UUID) ?: return null
        val char = service.getCharacteristic(uuid) ?: return null

        gatt.readCharacteristic(char)
        return char.value
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        _connectionState.value = ConnectionState.Disconnected
    }

    fun isConnected(): Boolean {
        return _connectionState.value is ConnectionState.Connected
    }
}
