package com.ppgtool.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ppgtool.app.data.ble.BleManager
import com.ppgtool.app.data.ble.ConnectionState
import com.ppgtool.app.data.network.BatteryInfo
import com.ppgtool.app.data.network.HttpRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = bleManager.connectionState

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
                _deviceStatus.update { it.copy(isOnline = false) }
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
