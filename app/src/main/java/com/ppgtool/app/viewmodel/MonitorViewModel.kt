package com.ppgtool.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ppgtool.app.data.ble.BleManager
import com.ppgtool.app.data.ble.ConnectionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PpgData(
    val hr: Int = 0,
    val spo2: Int = 0,
    val pi: Int = 0,
    val quality: Int = 0,
    val redValues: List<Float> = emptyList(),
    val irValues: List<Float> = emptyList()
)

@HiltViewModel
class MonitorViewModel @Inject constructor(
    private val bleManager: BleManager
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = bleManager.connectionState

    private val _ppgData = MutableStateFlow(PpgData())
    val ppgData: StateFlow<PpgData> = _ppgData.asStateFlow()

    private val _isMeasuring = MutableStateFlow(false)
    val isMeasuring: StateFlow<Boolean> = _isMeasuring.asStateFlow()

    private val redBuffer = mutableListOf<Float>()
    private val irBuffer = mutableListOf<Float>()

    init {
        viewModelScope.launch {
            bleManager.liveData.collect { data ->
                if (data.size >= 5) {
                    val hr = (data[0].toInt() and 0xFF shl 8) or (data[1].toInt() and 0xFF)
                    val spo2 = data[2].toInt() and 0xFF
                    val pi = data[3].toInt() and 0xFF
                    val quality = data[4].toInt() and 0xFF

                    redBuffer.add(hr.toFloat())
                    irBuffer.add(spo2.toFloat())
                    if (redBuffer.size > 300) redBuffer.removeAt(0)
                    if (irBuffer.size > 300) irBuffer.removeAt(0)

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
        redBuffer.clear()
        irBuffer.clear()
        _ppgData.value = PpgData()
    }
}
