package org.tan.ppgtoolapp.data.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Handler
import android.os.Looper
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
    companion object {
        private const val TAG = "BleManager"
        private const val READ_TIMEOUT_MS = 5000L
        private const val AUTO_RECONNECT_DELAY_MS = 3000L
    }
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var bluetoothGatt: BluetoothGatt? = null

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _liveData = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val liveData: SharedFlow<ByteArray> = _liveData.asSharedFlow()

    private val _statusData = MutableSharedFlow<ByteArray>(extraBufferCapacity = 16)
    val statusData: SharedFlow<ByteArray> = _statusData.asSharedFlow()

    // Command response data (SD card, battery query, etc.)
    private val _cmdResponse = MutableSharedFlow<ByteArray>(extraBufferCapacity = 16)
    val cmdResponse: SharedFlow<ByteArray> = _cmdResponse.asSharedFlow()

    // 用于 readCharacteristic 的异步等待
    @Volatile
    private var pendingReadUuid: UUID? = null
    private var pendingReadContinuation: kotlin.coroutines.Continuation<ByteArray?>? = null

    // 扫描相关
    private var currentScanner: BluetoothLeScanner? = null
    private var currentScanCallback: ScanCallback? = null

    // 自动重连相关
    private var lastConnectedDevice: BluetoothDevice? = null
    private var lastConnectedName: String = ""
    private var isAutoReconnecting = false
    private var autoReconnectRunnable: Runnable? = null
    private val reconnectHandler = Handler(Looper.getMainLooper())

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
        lastConnectedDevice = device
        lastConnectedName = deviceName

        return suspendCancellableCoroutine { continuation ->
            val gattCallback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    when (newState) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            bluetoothGatt = gatt
                            isAutoReconnecting = false
                            _connectionState.value = ConnectionState.Connected(device, deviceName)
                            Log.i(TAG, "设备已连接: $deviceName (${device.address})")
                            gatt.discoverServices()
                        }
                        BluetoothProfile.STATE_DISCONNECTED -> {
                            Log.w(TAG, "设备断开连接: $deviceName (${device.address})")
                            bluetoothGatt = null
                            _connectionState.value = ConnectionState.Disconnected

                            // 如果不是主动断开，尝试自动重连
                            if (!isAutoReconnecting) {
                                startAutoReconnect()
                            }

                            if (continuation.isActive) continuation.resume(false)
                        }
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        Log.i(TAG, "=== GATT 服务发现 ===")
                        for (service in gatt.services) {
                            Log.i(TAG, "Service: ${service.uuid}")
                            for (char in service.characteristics) {
                                val props = mutableListOf<String>()
                                if (char.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) props.add("Read")
                                if (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) props.add("Write")
                                if (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) props.add("WriteNoResp")
                                if (char.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) props.add("Notify")
                                if (char.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) props.add("Indicate")
                                Log.i(TAG, "  Char: ${char.uuid} [${props.joinToString(", ")}]")
                            }
                        }
                        Log.i(TAG, "====================")

                        enableNotifications(gatt)
                        if (continuation.isActive) continuation.resume(true)
                    } else {
                        Log.e(TAG, "服务发现失败: $status")
                        if (continuation.isActive) continuation.resume(false)
                    }
                }

                override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                    when (characteristic.uuid) {
                        PpgGattProfile.CHAR_LIVE_DATA -> {
                            characteristic.value?.let { data ->
                                Log.v(TAG, "Live Data: ${data.joinToString(" ") { "%02X".format(it) }} (${data.size} bytes)")
                                _liveData.tryEmit(data)
                            }
                        }
                        PpgGattProfile.CHAR_STATUS -> {
                            characteristic.value?.let { data ->
                                Log.d(TAG, "Status Notify: ${data.joinToString(" ") { "%02X".format(it) }}")
                                _statusData.tryEmit(data)
                            }
                        }
                        PpgGattProfile.CHAR_COMMAND -> {
                            characteristic.value?.let { data ->
                                Log.d(TAG, "CMD Response: ${data.joinToString(" ") { "%02X".format(it) }} (${data.size} bytes)")
                                _cmdResponse.tryEmit(data)
                            }
                        }
                        else -> {
                            Log.d(TAG, "Unknown Notify [${characteristic.uuid}]: ${characteristic.value?.joinToString(" ") { "%02X".format(it) }}")
                        }
                    }
                }

                // Android 13+ (API 33) new callback signature
                override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
                    Log.d(TAG, "onCharacteristicRead(API33): uuid=${characteristic.uuid}, status=$status, value.size=${value.size}")
                    handleCharacteristicRead(characteristic.uuid, value, status)
                }

                // Android 12 and below legacy callback
                override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                    Log.d(TAG, "onCharacteristicRead(Legacy): uuid=${characteristic.uuid}, status=$status, value.size=${characteristic.value?.size}")
                    handleCharacteristicRead(characteristic.uuid, characteristic.value, status)
                }

                private fun handleCharacteristicRead(uuid: UUID, value: ByteArray?, status: Int) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        when (uuid) {
                            PpgGattProfile.CHAR_STATUS -> {
                                value?.let { data ->
                                    Log.d(TAG, "Status Read: ${data.joinToString(" ") { "%02X".format(it) }}")
                                    _statusData.tryEmit(data)
                                }
                            }
                        }
                    }

                    // 处理 readCharacteristic 的异步结果
                    if (uuid == pendingReadUuid) {
                        val result = if (status == BluetoothGatt.GATT_SUCCESS) {
                            value?.also { data ->
                                Log.d(TAG, "Read $uuid: ${data.joinToString(" ") { "%02X".format(it) }}")
                            }
                        } else null
                        pendingReadContinuation?.resume(result)
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
        val service = gatt.getService(PpgGattProfile.SERVICE_UUID)
        if (service == null) {
            Log.e(TAG, "未找到目标 Service: ${PpgGattProfile.SERVICE_UUID}")
            return
        }
        Log.i(TAG, "找到 Service: ${service.uuid}")

        enableCharNotification(gatt, PpgGattProfile.CHAR_LIVE_DATA, "Live Data")
        enableCharNotification(gatt, PpgGattProfile.CHAR_STATUS, "Status")
        enableCharNotification(gatt, PpgGattProfile.CHAR_COMMAND, "Command")
    }

    @SuppressLint("MissingPermission")
    private fun enableCharNotification(gatt: BluetoothGatt, charUuid: UUID, label: String) {
        val service = gatt.getService(PpgGattProfile.SERVICE_UUID) ?: return
        service.getCharacteristic(charUuid)?.let { char ->
            gatt.setCharacteristicNotification(char, true)
            char.getDescriptor(PpgGattProfile.DESCRIPTOR_CCC)?.let { desc ->
                desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(desc)
                Log.i(TAG, "已启用 $label Notify: ${char.uuid}")
            } ?: Log.w(TAG, "$label 无 CCC Descriptor")
        } ?: Log.w(TAG, "未找到 $label 特征值: $charUuid")
    }

    @SuppressLint("MissingPermission")
    suspend fun writeCommand(command: ByteArray): Boolean {
        val gatt = bluetoothGatt ?: return false
        val service = gatt.getService(PpgGattProfile.SERVICE_UUID) ?: return false
        val char = service.getCharacteristic(PpgGattProfile.CHAR_COMMAND) ?: return false

        // Build frame: [0xAA][CMD][LEN][DATA...][CHECKSUM]
        val frame = buildFrame(command)
        char.value = frame
        return gatt.writeCharacteristic(char)
    }

    /**
     * Build BLE frame according to protocol
     * Format: [0xAA][CMD][LEN][DATA...][CHECKSUM]
     * CHECKSUM = SUM of CMD, LEN, and all DATA bytes (mod 256)
     */
    private fun buildFrame(cmdData: ByteArray): ByteArray {
        val cmd = cmdData[0]
        val data = if (cmdData.size > 1) cmdData.copyOfRange(1, cmdData.size) else ByteArray(0)
        val len = data.size

        // Calculate checksum: SUM of CMD + LEN + DATA (mod 256)
        var checksum = (cmd.toInt() and 0xFF) + len
        for (b in data) {
            checksum += (b.toInt() and 0xFF)
        }
        checksum = checksum and 0xFF

        // Build frame
        val frame = ByteArray(4 + data.size)
        frame[0] = 0xAA.toByte()  // Header
        frame[1] = cmd             // Command
        frame[2] = len.toByte()    // Data length
        System.arraycopy(data, 0, frame, 3, data.size)
        frame[3 + data.size] = checksum.toByte()

        return frame
    }

    @SuppressLint("MissingPermission")
    suspend fun readCharacteristic(uuid: UUID): ByteArray? {
        val gatt = bluetoothGatt ?: return null
        val service = gatt.getService(PpgGattProfile.SERVICE_UUID) ?: return null
        val char = service.getCharacteristic(uuid) ?: return null

        // 设置临时特征读取标记
        pendingReadUuid = uuid

        return kotlinx.coroutines.withTimeoutOrNull(READ_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                pendingReadContinuation = continuation
                gatt.readCharacteristic(char)

                // 超时处理
                continuation.invokeOnCancellation {
                    pendingReadContinuation = null
                    pendingReadUuid = null
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        isAutoReconnecting = true  // 标记为主动断开，不自动重连
        stopAutoReconnect()
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        _connectionState.value = ConnectionState.Disconnected
    }

    /**
     * 开始自动重连
     */
    private fun startAutoReconnect() {
        val device = lastConnectedDevice ?: return
        isAutoReconnecting = true
        Log.i(TAG, "开始自动重连: $lastConnectedName (${device.address})")

        autoReconnectRunnable = Runnable {
            if (isAutoReconnecting && bluetoothGatt == null) {
                Log.d(TAG, "尝试重连...")
                try {
                    device.connectGatt(context, false, object : BluetoothGattCallback() {
                        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                            when (newState) {
                                BluetoothProfile.STATE_CONNECTED -> {
                                    Log.i(TAG, "重连成功!")
                                    bluetoothGatt = gatt
                                    isAutoReconnecting = false
                                    _connectionState.value = ConnectionState.Connected(device, lastConnectedName)
                                    gatt.discoverServices()
                                }
                                BluetoothProfile.STATE_DISCONNECTED -> {
                                    Log.w(TAG, "重连失败，继续尝试...")
                                    gatt.close()
                                    // 继续重连
                                    startAutoReconnect()
                                }
                            }
                        }
                    })
                } catch (e: Exception) {
                    Log.e(TAG, "重连异常: ${e.message}")
                    startAutoReconnect()
                }
            }
        }
        reconnectHandler.postDelayed(autoReconnectRunnable!!, AUTO_RECONNECT_DELAY_MS)
    }

    /**
     * 停止自动重连
     */
    private fun stopAutoReconnect() {
        autoReconnectRunnable?.let { reconnectHandler.removeCallbacks(it) }
        autoReconnectRunnable = null
        isAutoReconnecting = false
    }

    fun isConnected(): Boolean {
        return _connectionState.value is ConnectionState.Connected
    }

    fun getConnectedDeviceMac(): String? {
        val state = _connectionState.value
        return if (state is ConnectionState.Connected) state.device.address else null
    }

    /**
     * 获取 BluetoothDevice 实例
     * @param address MAC 地址
     * @return BluetoothDevice 实例，失败返回 null
     */
    @SuppressLint("MissingPermission")
    fun getBluetoothDevice(address: String): BluetoothDevice? {
        return bluetoothAdapter?.getRemoteDevice(address)
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

    /**
     * 查询设备完整状态
     * 固件收到后应更新 Status 特征值
     * @return 是否发送成功
     */
    suspend fun queryDeviceStatus(): Boolean {
        Log.d(TAG, "查询设备状态: CMD=0x%02X".format(PpgGattProfile.CMD_QUERY_STATUS))
        return writeCommand(byteArrayOf(PpgGattProfile.CMD_QUERY_STATUS))
    }

    /**
     * 查询 SD 卡容量
     * 固件收到后应更新 Status 特征值中的 SD 卡信息
     * @return 是否发送成功
     */
    suspend fun querySdCardStatus(): Boolean {
        Log.d(TAG, "查询 SD 卡状态: CMD=0x%02X".format(PpgGattProfile.CMD_QUERY_SD_CARD))
        return writeCommand(byteArrayOf(PpgGattProfile.CMD_QUERY_SD_CARD))
    }

    /**
     * 查询电池详情
     * 固件收到后应更新 Status 特征值中的电池信息
     * @return 是否发送成功
     */
    suspend fun queryBatteryStatus(): Boolean {
        Log.d(TAG, "查询电池状态: CMD=0x%02X".format(PpgGattProfile.CMD_QUERY_BATTERY))
        return writeCommand(byteArrayOf(PpgGattProfile.CMD_QUERY_BATTERY))
    }
}
