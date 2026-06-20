package org.tan.ppgtoolapp.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.tan.ppgtoolapp.data.ble.BleManager
import org.tan.ppgtoolapp.data.ble.ConnectionState
import org.tan.ppgtoolapp.data.network.BatteryInfo
import org.tan.ppgtoolapp.data.network.HttpRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

    // 状态轮询 Job，防止重复启动
    private var pollingJob: Job? = null

    init {
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
                                sdFreeMb = 0,  // SD 卡信息需要单独查询
                                isOnline = true
                            )
                            Log.d(TAG, "BLE 获取状态成功: battery=${battery.soc}%, version=$version")
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
     * 查询 SD 卡容量
     */
    fun querySdCardStatus() {
        viewModelScope.launch {
            if (bleManager.isConnected()) {
                val success = bleManager.querySdCardStatus()
                if (success) {
                    Log.d(TAG, "BLE 查询 SD 卡状态已发送")
                }
            }
        }
    }

    /**
     * 查询电池详情
     */
    fun queryBatteryStatus() {
        viewModelScope.launch {
            if (bleManager.isConnected()) {
                val success = bleManager.queryBatteryStatus()
                if (success) {
                    Log.d(TAG, "BLE 查询电池状态已发送")
                }
            }
        }
    }

    /**
     * 定时刷新设备状态（每 30 秒）
     * 使用 Job 引用防止重复启动
     */
    fun startStatusPolling() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (true) {
                fetchDeviceStatus()
                delay(30_000)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        pollingJob?.cancel()
    }
}
