package org.tan.ppgtoolapp.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.tan.ppgtoolapp.data.ble.BleCommandProvider
import org.tan.ppgtoolapp.data.ble.BleConnectionProvider
import org.tan.ppgtoolapp.data.ble.ConnectionState
import org.tan.ppgtoolapp.data.network.ApiResult
import org.tan.ppgtoolapp.data.network.BatteryInfo
import org.tan.ppgtoolapp.data.network.DeviceHttpApi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
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
    val sdFreeMb: Int = -1,  // -1 表示未加载，0 表示无 SD 卡或容量为 0
    val isOnline: Boolean = false
)

@HiltViewModel
class MonitorViewModel @Inject constructor(
    private val bleConnection: BleConnectionProvider,
    private val bleCommander: BleCommandProvider,
    private val httpRepository: DeviceHttpApi
) : ViewModel() {
    companion object {
        private const val TAG = "MonitorViewModel"
        private const val BLE_QUERY_TIMEOUT_MS = 2000L
        private const val BLE_RESPONSE_DELAY_MS = 500L
        private const val STATUS_DATA_SIZE = 20
        private const val SD_CARD_RESPONSE_SIZE = 7
        private const val BATTERY_RESPONSE_SIZE = 5
    }

    val connectionState: StateFlow<ConnectionState> = bleConnection.connectionState

    val isConnected: Boolean
        get() = bleConnection.connectionState.value is ConnectionState.Connected

    private val _ppgData = MutableStateFlow(PpgData())
    val ppgData: StateFlow<PpgData> = _ppgData.asStateFlow()

    private val _isMeasuring = MutableStateFlow(false)
    val isMeasuring: StateFlow<Boolean> = _isMeasuring.asStateFlow()

    private val _deviceStatus = MutableStateFlow(DeviceStatus())
    val deviceStatus: StateFlow<DeviceStatus> = _deviceStatus.asStateFlow()

    // Waveform buffers (HR and SpO2 trend data for chart display)
    private val hrBuffer = ArrayDeque<Float>(WAVEFORM_BUFFER_SIZE + 1)
    private val spo2Buffer = ArrayDeque<Float>(WAVEFORM_BUFFER_SIZE + 1)

    // 标记是否已查询过状态（防止重复查询）
    private var hasFetchedStatus = false

    init {
        // 监听 Live Data
        viewModelScope.launch {
            bleCommander.liveData.collect { data ->
                if (data.size >= PpgDataOffset.MIN_DATA_SIZE) {
                    val hr = (data[PpgDataOffset.HR_HIGH].toInt() and 0xFF shl 8) or
                            (data[PpgDataOffset.HR_LOW].toInt() and 0xFF)
                    val spo2 = data[PpgDataOffset.SPO2].toInt() and 0xFF
                    val pi = data[PpgDataOffset.PI].toInt() and 0xFF
                    val quality = data[PpgDataOffset.QUALITY].toInt() and 0xFF

                    hrBuffer.addLast(hr.toFloat())
                    spo2Buffer.addLast(spo2.toFloat())
                    if (hrBuffer.size > WAVEFORM_BUFFER_SIZE) hrBuffer.removeFirst()
                    if (spo2Buffer.size > WAVEFORM_BUFFER_SIZE) spo2Buffer.removeFirst()

                    _ppgData.value = PpgData(
                        hr = hr,
                        spo2 = spo2,
                        pi = pi,
                        quality = quality,
                        redValues = hrBuffer.toList(),
                        irValues = spo2Buffer.toList()
                    )
                }
            }
        }

        // 监听连接状态，连接成功后自动查询一次
        viewModelScope.launch {
            bleConnection.connectionState.collect { state ->
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
            if (bleCommander.writeCommand(byteArrayOf(0x01))) {
                _isMeasuring.value = true
            } else {
                Log.e(TAG, "Failed to send start measuring command")
            }
        }
    }

    fun stopMeasuring() {
        viewModelScope.launch {
            if (bleCommander.writeCommand(byteArrayOf(0x02))) {
                _isMeasuring.value = false
            } else {
                Log.e(TAG, "Failed to send stop measuring command")
            }
        }
    }

    fun clearWaveform() {
        viewModelScope.launch {
            hrBuffer.clear()
            spo2Buffer.clear()
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
        if (!bleConnection.isConnected()) return false
        if (!bleCommander.queryDeviceStatus()) return false

        Log.d(TAG, "BLE device status query sent")

        // Use timeout instead of fixed delay
        val data = withTimeoutOrNull(BLE_QUERY_TIMEOUT_MS) {
            delay(100)  // Small initial delay for response
            bleCommander.readCharacteristic(org.tan.ppgtoolapp.data.ble.PpgGattProfile.CHAR_STATUS)
        }
        if (data == null || data.size < STATUS_DATA_SIZE) return false

        val battery = org.tan.ppgtoolapp.data.network.BatteryInfo(batt_pct = data[0].toInt() and 0xFF)
        val version = String(data.copyOfRange(5, 20), Charsets.UTF_8).trim()
        _deviceStatus.update { it.copy(battery = battery, firmwareVersion = version, isOnline = true) }
        Log.d(TAG, "BLE status OK: battery=${battery.batt_pct}%, version=$version")
        return true
    }

    private suspend fun fetchDeviceStatusHttp() {
        when (val result = httpRepository.getDeviceStatus()) {
            is ApiResult.Success -> {
                _deviceStatus.value = DeviceStatus(
                    battery = result.data.battery, firmwareVersion = result.data.version,
                    sdFreeMb = result.data.sd_free_mb, isOnline = true
                )
            }
            is ApiResult.Error -> {
                Log.w(TAG, "HTTP status failed: ${result.message}")
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
            if (!bleConnection.isConnected()) return@launch
            if (!bleCommander.querySdCardStatus()) return@launch
            Log.d(TAG, "BLE 查询 SD 卡状态已发送")
            val resp = withTimeoutOrNull(BLE_QUERY_TIMEOUT_MS) {
                bleCommander.cmdResponse.first { it.size >= SD_CARD_RESPONSE_SIZE && it[1] == 0x23.toByte() }
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

    /**
     * Query battery via BLE command 0x24
     * Response frame: [0xAA][0x24][0x01][BATT_PCT][CHECKSUM]
     */
    fun queryBatteryStatus() {
        viewModelScope.launch {
            if (!bleConnection.isConnected()) return@launch
            if (!bleCommander.queryBatteryStatus()) return@launch
            Log.d(TAG, "BLE 查询电池状态已发送")
            val resp = withTimeoutOrNull(BLE_QUERY_TIMEOUT_MS) {
                bleCommander.cmdResponse.first { it.size >= BATTERY_RESPONSE_SIZE && it[1] == 0x24.toByte() }
            }
            if (resp != null) {
                val pct = resp[3].toInt() and 0xFF
                Log.d(TAG, "电池响应: ${pct}%")
                _deviceStatus.update { it.copy(battery = BatteryInfo(batt_pct = pct)) }
            } else {
                Log.w(TAG, "电池查询超时")
            }
        }
    }

    /**
     * 刷新全部设备状态（点击时调用）
     */
    fun refreshDeviceStatus() {
        hasFetchedStatus = false
        fetchDeviceStatus()
        querySdCardStatus()
    }

    /**
     * 仅刷新版本号（点击版本时调用）
     */
    fun refreshVersionOnly() {
        hasFetchedStatus = false
        fetchDeviceStatus()
    }

}
