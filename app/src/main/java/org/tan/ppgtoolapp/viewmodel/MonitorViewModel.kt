package org.tan.ppgtoolapp.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.tan.ppgtoolapp.data.ble.BleManager
import org.tan.ppgtoolapp.data.ble.ConnectionState
import org.tan.ppgtoolapp.data.network.BatteryInfo
import org.tan.ppgtoolapp.data.network.HttpRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

private const val TAG = "MonitorViewModel"

/** BLE 数据字节偏移量 */
private object PpgDataOffset {
    const val HR_HIGH = 0
    const val HR_LOW = 1
    const val SPO2 = 2
    const val PI = 3
    const val QUALITY = 4
    const val MIN_DATA_SIZE = 5
}

/** 波形缓冲区最大长度 */
private const val WAVEFORM_BUFFER_SIZE = 300

data class PpgData(
    val hr: Int = 0,
    val spo2: Int = 0,
    val pi: Int = 0,
    val quality: Int = 0,
    val redValues: List<Float> = emptyList(),
    val irValues: List<Float> = emptyList()
)

data class DeviceStatus(
    val battery: BatteryInfo? = null,
    val firmwareVersion: String = "",
    val sdFreeMb: Int = 0,
    val isOnline: Boolean = false
)

@HiltViewModel
class MonitorViewModel @Inject constructor(
    private val bleManager: BleManager,
    private val httpRepository: HttpRepository
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = bleManager.connectionState

    val isConnected: Boolean
        get() = bleManager.connectionState.value is ConnectionState.Connected

    private val _ppgData = MutableStateFlow(PpgData())
    val ppgData: StateFlow<PpgData> = _ppgData.asStateFlow()

    private val _isMeasuring = MutableStateFlow(false)
    val isMeasuring: StateFlow<Boolean> = _isMeasuring.asStateFlow()

    private val _deviceStatus = MutableStateFlow(DeviceStatus())
    val deviceStatus: StateFlow<DeviceStatus> = _deviceStatus.asStateFlow()

    // 使用 Mutex 保证线程安全
    private val bufferMutex = Mutex()
    private val redBuffer = mutableListOf<Float>()
    private val irBuffer = mutableListOf<Float>()

    // 标记是否已查询过状态（防止重复查询）
    private var hasFetchedStatus = false

    init {
        // 监听 Live Data
        viewModelScope.launch {
            bleManager.liveData.collect { data ->
                if (data.size >= PpgDataOffset.MIN_DATA_SIZE) {
                    val hr = (data[PpgDataOffset.HR_HIGH].toInt() and 0xFF shl 8) or
                            (data[PpgDataOffset.HR_LOW].toInt() and 0xFF)
                    val spo2 = data[PpgDataOffset.SPO2].toInt() and 0xFF
                    val pi = data[PpgDataOffset.PI].toInt() and 0xFF
                    val quality = data[PpgDataOffset.QUALITY].toInt() and 0xFF

                    bufferMutex.withLock {
                        redBuffer.add(hr.toFloat())
                        irBuffer.add(spo2.toFloat())
                        if (redBuffer.size > WAVEFORM_BUFFER_SIZE) redBuffer.removeAt(0)
                        if (irBuffer.size > WAVEFORM_BUFFER_SIZE) irBuffer.removeAt(0)

                        _ppgData.value = PpgData(
                            hr = hr,
                            spo2 = spo2,
                            pi = pi,
                            quality = quality,
                            redValues = redBuffer.toList(),
                            irValues = irBuffer.toList()
                        )
                    }
                }
            }
        }

        // 监听连接状态，连接成功后自动查询一次
        viewModelScope.launch {
            bleManager.connectionState.collect { state ->
                if (state is ConnectionState.Connected && !hasFetchedStatus) {
                    hasFetchedStatus = true
                    Log.d(TAG, "BLE 已连接，自动查询设备状态")
                    fetchDeviceStatus()
                } else if (state is ConnectionState.Disconnected) {
                    hasFetchedStatus = false
                }
            }
        }
    }

    fun startMeasuring() {
        viewModelScope.launch {
            bleManager.writeCommand(byteArrayOf(0x01))
            _isMeasuring.value = true
        }
    }

    fun stopMeasuring() {
        viewModelScope.launch {
            bleManager.writeCommand(byteArrayOf(0x02))
            _isMeasuring.value = false
        }
    }

    fun clearWaveform() {
        viewModelScope.launch {
            bufferMutex.withLock {
                redBuffer.clear()
                irBuffer.clear()
            }
            _ppgData.value = PpgData()
        }
    }

    fun fetchDeviceStatus() {
        viewModelScope.launch {
            try {
                // 优先通过 BLE 查询
                if (bleManager.isConnected()) {
                    val success = bleManager.queryDeviceStatus()
                    if (success) {
                        Log.d(TAG, "BLE 查询设备状态已发送")
                        // BLE 查询结果会通过 Status Notify 返回
                        // 延迟一小段时间等待响应
                        delay(500)
                        // 读取 Status 特征值
                        val data = bleManager.readCharacteristic(org.tan.ppgtoolapp.data.ble.PpgGattProfile.CHAR_STATUS)
                        if (data != null && data.size >= 20) {
                            val battery = org.tan.ppgtoolapp.data.network.BatteryInfo(
                                soc = data[0].toInt() and 0xFF,
                                voltage = ((data[1].toInt() and 0xFF) shl 8) or (data[2].toInt() and 0xFF)
                            )
                            val versionBytes = data.copyOfRange(5, 20)
                            val version = String(versionBytes, Charsets.UTF_8).trim()
                            _deviceStatus.value = DeviceStatus(
                                battery = battery,
                                firmwareVersion = version,
                                sdFreeMb = 0,  // Will be updated by querySdCardStatus()
                                isOnline = true
                            )
                            Log.d(TAG, "BLE 获取状态成功: battery=${battery.soc}%, version=$version")
                            // Query SD card separately
                            querySdCardStatus()
                            return@launch
                        }
                    }
                }

                // 回退到 HTTP
                val status = httpRepository.getDeviceStatus()
                if (status != null) {
                    _deviceStatus.value = DeviceStatus(
                        battery = status.battery,
                        firmwareVersion = status.version,
                        sdFreeMb = status.sd_free_mb,
                        isOnline = true
                    )
                } else {
                    _deviceStatus.update { it.copy(isOnline = false) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "获取设备状态异常: ${e.message}")
                _deviceStatus.update { it.copy(isOnline = false) }
            }
        }
    }

    /**
     * Query SD card capacity via BLE command 0x23
     * Response frame: [0xAA][0x23][0x04][free_h][free_l][total_h][total_l][CHECKSUM]
     */
    fun querySdCardStatus() {
        viewModelScope.launch {
            if (bleManager.isConnected()) {
                val success = bleManager.querySdCardStatus()
                if (success) {
                    Log.d(TAG, "BLE 查询 SD 卡状态已发送")
                    // Wait for response with timeout
                    val resp = withTimeoutOrNull(2000L) {
                        bleManager.cmdResponse.first { it.size >= 7 && it[1] == 0x23.toByte() }
                    }
                    if (resp != null) {
                        val freeMb = ((resp[3].toInt() and 0xFF) shl 8) or (resp[4].toInt() and 0xFF)
                        val totalMb = ((resp[5].toInt() and 0xFF) shl 8) or (resp[6].toInt() and 0xFF)
                        Log.d(TAG, "SD 卡响应: free=${freeMb}MB, total=${totalMb}MB")
                        _deviceStatus.update { it.copy(sdFreeMb = freeMb) }
                    } else {
                        Log.w(TAG, "SD 卡查询超时")
                    }
                }
            }
        }
    }

    /**
     * Query battery details via BLE command 0x24
     * Response frame: [0xAA][0x24][0x03][soc][voltage_h][voltage_l][CHECKSUM]
     */
    fun queryBatteryStatus() {
        viewModelScope.launch {
            if (bleManager.isConnected()) {
                val success = bleManager.queryBatteryStatus()
                if (success) {
                    Log.d(TAG, "BLE 查询电池状态已发送")
                    // Wait for response with timeout
                    val resp = withTimeoutOrNull(2000L) {
                        bleManager.cmdResponse.first { it.size >= 6 && it[1] == 0x24.toByte() }
                    }
                    if (resp != null) {
                        val soc = resp[3].toInt() and 0xFF
                        val voltage = ((resp[4].toInt() and 0xFF) shl 8) or (resp[5].toInt() and 0xFF)
                        Log.d(TAG, "电池响应: SOC=${soc}%, voltage=${voltage}mV")
                        _deviceStatus.update {
                            it.copy(
                                battery = BatteryInfo(soc = soc, voltage = voltage)
                            )
                        }
                    } else {
                        Log.w(TAG, "电池查询超时")
                    }
                }
            }
        }
    }

    /**
     * 刷新设备状态（点击时调用）
     */
    fun refreshDeviceStatus() {
        hasFetchedStatus = false
        fetchDeviceStatus()
    }

    override fun onCleared() {
        super.onCleared()
    }
}
