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
) {
    companion object {
        private const val TAG = "MonitorViewModel"
        private const val BLE_QUERY_TIMEOUT_MS = 2000L
        private const val BLE_RESPONSE_DELAY_MS = 500L
        private const val STATUS_DATA_SIZE = 20
        private const val SD_CARD_RESPONSE_SIZE = 7
        private const val BATTERY_RESPONSE_SIZE = 5
    }
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
                // Try BLE first
                if (fetchDeviceStatusBle()) return@launch

                // Fallback to HTTP
                fetchDeviceStatusHttp()
            } catch (e: Exception) {
                Log.e(TAG, "获取设备状态异常: ${e.message}")
                _deviceStatus.update { it.copy(isOnline = false) }
            }
        }
    }

    private suspend fun fetchDeviceStatusBle(): Boolean {
        if (!bleManager.isConnected()) return false
        if (!bleManager.queryDeviceStatus()) return false

        Log.d(TAG, "BLE 查询设备状态已发送")
        delay(BLE_RESPONSE_DELAY_MS)

        val data = bleManager.readCharacteristic(org.tan.ppgtoolapp.data.ble.PpgGattProfile.CHAR_STATUS)
        if (data == null || data.size < STATUS_DATA_SIZE) return false

        val battery = org.tan.ppgtoolapp.data.network.BatteryInfo(batt_pct = data[0].toInt() and 0xFF)
        val version = String(data.copyOfRange(5, 20), Charsets.UTF_8).trim()
        _deviceStatus.value = DeviceStatus(battery = battery, firmwareVersion = version, sdFreeMb = 0, isOnline = true)
        Log.d(TAG, "BLE 获取状态成功: battery=${battery.batt_pct}%, version=$version")
        querySdCardStatus()
        return true
    }

    private suspend fun fetchDeviceStatusHttp() {
        val status = httpRepository.getDeviceStatus()
        if (status != null) {
            _deviceStatus.value = DeviceStatus(
                battery = status.battery, firmwareVersion = status.version,
                sdFreeMb = status.sd_free_mb, isOnline = true
            )
        } else {
            _deviceStatus.update { it.copy(isOnline = false) }
        }
    }

    /**
     * Generic BLE command query with timeout and response parsing
     */
    private suspend fun <T> queryBleCommand(
        sendQuery: suspend () -> Boolean,
        cmd: Byte,
        timeoutMs: Long = BLE_QUERY_TIMEOUT_MS,
        minResponseSize: Int = 5,
        parser: (ByteArray) -> T
    ): T? {
        if (!bleManager.isConnected()) return null
        if (!sendQuery()) return null
        val resp = withTimeoutOrNull(timeoutMs) {
            bleManager.cmdResponse.first { it.size >= minResponseSize && it[1] == cmd }
        }
        return resp?.let { parser(it) }
    }

    /**
     * Query SD card capacity via BLE command 0x23
     * Response frame: [0xAA][0x23][0x04][free_h][free_l][total_h][total_l][CHECKSUM]
     */
    fun querySdCardStatus() {
        viewModelScope.launch {
            val result = queryBleCommand(
                sendQuery = { bleManager.querySdCardStatus() },
                cmd = 0x23.toByte(),
                minResponseSize = SD_CARD_RESPONSE_SIZE
            ) { resp ->
                val freeMb = ((resp[3].toInt() and 0xFF) shl 8) or (resp[4].toInt() and 0xFF)
                val totalMb = ((resp[5].toInt() and 0xFF) shl 8) or (resp[6].toInt() and 0xFF)
                Log.d(TAG, "SD 卡响应: free=${freeMb}MB, total=${totalMb}MB")
                freeMb
            }
            if (result != null) {
                _deviceStatus.update { it.copy(sdFreeMb = result) }
            } else {
                Log.w(TAG, "SD 卡查询超时")
            }
        }
    }

    /**
     * Query battery via BLE command 0x24
     * Response frame: [0xAA][0x24][0x01][BATT_PCT][CHECKSUM]
     */
    fun queryBatteryStatus() {
        viewModelScope.launch {
            val result = queryBleCommand(
                sendQuery = { bleManager.queryBatteryStatus() },
                cmd = 0x24.toByte(),
                minResponseSize = BATTERY_RESPONSE_SIZE
            ) { resp ->
                val pct = resp[3].toInt() and 0xFF
                Log.d(TAG, "电池响应: ${pct}%")
                pct
            }
            if (result != null) {
                _deviceStatus.update { it.copy(battery = BatteryInfo(batt_pct = result)) }
            } else {
                Log.w(TAG, "电池查询超时")
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

}
