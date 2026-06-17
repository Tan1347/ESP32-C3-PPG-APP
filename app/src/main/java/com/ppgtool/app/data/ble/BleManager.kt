package com.ppgtool.app.data.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

private const val TAG = "BleManager"

data class BleDevice(
    val name: String,
    val address: String,
    val rssi: Int
)

sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Connecting : ConnectionState()
    data class Connected(val device: BluetoothDevice, val deviceName: String = "") : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

@Singleton
@Suppress("DEPRECATION")
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

    // 用于 readCharacteristic 的异步等待
    @Volatile
    private var pendingReadUuid: UUID? = null
    private var pendingReadContinuation: kotlin.coroutines.Continuation<ByteArray?>? = null

    // 扫描相关
    private var currentScanner: BluetoothLeScanner? = null
    private var currentScanCallback: ScanCallback? = null

    /**
     * 扫描 BLE 设备
     * @param useUuidFilter 是否使用 UUID 过滤（默认 false，扫描所有设备）
     */
    @SuppressLint("MissingPermission")
    fun scan(useUuidFilter: Boolean = false): Flow<BleDevice> = callbackFlow {
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            Log.e(TAG, "BLE Scanner 不可用，蓝牙是否开启？")
            close()
            return@callbackFlow
        }

        // 保存 scanner 引用用于停止扫描
        currentScanner = scanner

        Log.i(TAG, "开始扫描 BLE 设备，UUID 过滤: $useUuidFilter")
        Log.i(TAG, "支持的设备名前缀: ${PpgGattProfile.DEVICE_NAME_PREFIXES}")

        var totalResults = 0
        var uniqueDevices = mutableSetOf<String>()  // 用地址去重
        var matchedDevices = mutableSetOf<String>()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val name = result.scanRecord?.deviceName ?: device.name ?: "Unknown"
                val address = device.address

                totalResults++

                // 判断是否为新设备
                val isNewDevice = uniqueDevices.add(address)

                // 发送所有设备到 UI
                val isMatch = PpgGattProfile.DEVICE_NAME_PREFIXES.any { prefix ->
                    name.startsWith(prefix, ignoreCase = true)
                }
                if (isMatch) {
                    matchedDevices.add(address)
                    if (isNewDevice) {
                        Log.i(TAG, "✓ 匹配设备: $name ($address)")
                    }
                }
                trySend(BleDevice(name, address, result.rssi))
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "扫描失败，错误码: $errorCode")
                when (errorCode) {
                    SCAN_FAILED_ALREADY_STARTED -> Log.e(TAG, "扫描已在进行中")
                    SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> Log.e(TAG, "应用注册失败")
                    SCAN_FAILED_INTERNAL_ERROR -> Log.e(TAG, "内部错误")
                    SCAN_FAILED_FEATURE_UNSUPPORTED -> Log.e(TAG, "功能不支持")
                }
                currentScanner = null
                currentScanCallback = null
                close()
            }
        }

        currentScanCallback = callback

        // 根据参数决定是否使用 UUID 过滤
        val filters = if (useUuidFilter) {
            listOf(
                ScanFilter.Builder()
                    .setServiceUuid(ParcelUuid(PpgGattProfile.SERVICE_UUID))
                    .build()
            )
        } else {
            emptyList()  // 不使用过滤器，扫描所有设备
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .build()

        Log.i(TAG, "启动扫描，过滤器数量: ${filters.size}")
        scanner.startScan(filters, settings, callback)

        awaitClose {
            Log.i(TAG, "停止扫描，共 ${uniqueDevices.size} 个唯一设备，${matchedDevices.size} 个匹配，$totalResults 次回调")
            try {
                scanner.stopScan(callback)
            } catch (e: Exception) {
                Log.e(TAG, "停止扫描异常: ${e.message}")
            }
            currentScanner = null
            currentScanCallback = null
        }
    }

    /**
     * 停止 BLE 扫描
     */
    @SuppressLint("MissingPermission")
    fun stopScan() {
        Log.i(TAG, "手动停止扫描")
        val scanner = currentScanner
        val callback = currentScanCallback
        if (scanner != null && callback != null) {
            try {
                scanner.stopScan(callback)
                Log.i(TAG, "扫描已停止")
            } catch (e: Exception) {
                Log.e(TAG, "停止扫描异常: ${e.message}")
            }
        }
        currentScanner = null
        currentScanCallback = null
    }

    @SuppressLint("MissingPermission")
    suspend fun connect(device: BluetoothDevice, deviceName: String = ""): Boolean {
        _connectionState.value = ConnectionState.Connecting

        return suspendCancellableCoroutine { continuation ->
            val gattCallback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    when (newState) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            bluetoothGatt = gatt
                            _connectionState.value = ConnectionState.Connected(device, deviceName)
                            gatt.discoverServices()
                            // 不在这里 resume，等待 services 发现完成
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
                        // Services 发现完成，现在才返回 true
                        if (continuation.isActive) continuation.resume(true)
                    } else {
                        // Services 发现失败
                        if (continuation.isActive) continuation.resume(false)
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

                    // 处理 readCharacteristic 的异步结果
                    if (characteristic.uuid == pendingReadUuid) {
                        val value = if (status == BluetoothGatt.GATT_SUCCESS) characteristic.value else null
                        pendingReadContinuation?.resume(value)
                        pendingReadContinuation = null
                        pendingReadUuid = null
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

        // 设置临时特征读取标记
        pendingReadUuid = uuid

        return suspendCancellableCoroutine { continuation ->
            pendingReadContinuation = continuation
            gatt.readCharacteristic(char)

            // 超时处理
            continuation.invokeOnCancellation {
                pendingReadContinuation = null
                pendingReadUuid = null
            }
        }
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

    /**
     * 同步时间到设备
     * @param timestamp Unix 10 位时间戳（秒）
     * @return 是否发送成功
     */
    suspend fun syncTime(timestamp: Long): Boolean {
        // 命令格式: [0x40][timestamp_byte3][timestamp_byte2][timestamp_byte1][timestamp_byte0]
        val cmd = ByteArray(5)
        cmd[0] = PpgGattProfile.CMD_TIME_SYNC
        cmd[1] = (timestamp shr 24).toByte()
        cmd[2] = (timestamp shr 16).toByte()
        cmd[3] = (timestamp shr 8).toByte()
        cmd[4] = timestamp.toByte()
        return writeCommand(cmd)
    }
}
