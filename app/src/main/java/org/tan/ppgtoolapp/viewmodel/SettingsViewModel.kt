package org.tan.ppgtoolapp.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.tan.ppgtoolapp.data.ble.BleManager
import org.tan.ppgtoolapp.data.network.ReleaseInfo
import org.tan.ppgtoolapp.data.network.TimeSyncHelper
import org.tan.ppgtoolapp.data.network.UpdateChecker
import org.tan.ppgtoolapp.data.network.UpdateState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class TimeSyncState(
    val isSyncing: Boolean = false,
    val lastSyncTime: String = "",
    val result: TimeSyncResult? = null
)

sealed class TimeSyncResult {
    data class Success(val timestamp: Long, val timeStr: String) : TimeSyncResult()
    data class Error(val message: String) : TimeSyncResult()
}

data class UartRecordState(
    val isRecording: Boolean = false,
    val selectedBaudRate: Int = 115200,
    val selectedDataBits: Int = 8,
    val selectedParity: Int = 0,      // 0=none, 1=even, 2=odd
    val selectedStopBits: Int = 1,
    val result: String? = null
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val updateChecker: UpdateChecker,
    private val timeSyncHelper: TimeSyncHelper,
    private val bleManager: BleManager
) : ViewModel() {

    companion object {
        val BAUD_RATES = listOf(9600, 19200, 38400, 57600, 115200, 230400, 460800, 921600, 1000000, 2000000, 5000000)
    }

    private val _timeSyncState = MutableStateFlow(TimeSyncState())
    val timeSyncState: StateFlow<TimeSyncState> = _timeSyncState.asStateFlow()

    private val _updateState = MutableStateFlow(UpdateState())
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    private val _uartState = MutableStateFlow(UartRecordState())
    val uartState: StateFlow<UartRecordState> = _uartState.asStateFlow()

    private var downloadedFile: File? = null

    fun getCurrentVersion(): String = updateChecker.getCurrentVersion()

    fun checkForUpdate() {
        if (_updateState.value.isChecking) return

        _updateState.update { it.copy(isChecking = true, error = null) }

        updateChecker.checkForUpdate { releaseInfo ->
            _updateState.update {
                if (releaseInfo != null) {
                    it.copy(
                        isChecking = false,
                        releaseInfo = releaseInfo,
                        showDialog = true
                    )
                } else {
                    it.copy(
                        isChecking = false,
                        error = "已是最新版本"
                    )
                }
            }
        }
    }

    fun startUpdate() {
        val release = _updateState.value.releaseInfo ?: return

        _updateState.update {
            it.copy(
                showDialog = false,
                isDownloading = true,
                progress = 0,
                progressText = "准备下载..."
            )
        }

        updateChecker.startDownload(
            apkUrl = release.apkUrl,
            totalSize = release.apkSize,
            onProgress = { progress, text ->
                _updateState.update {
                    it.copy(progress = progress, progressText = text)
                }
            },
            onComplete = { file ->
                downloadedFile = file
                _updateState.update {
                    it.copy(
                        isDownloading = false,
                        showInstallDialog = true
                    )
                }
            },
            onError = { error ->
                _updateState.update {
                    it.copy(
                        isDownloading = false,
                        error = error
                    )
                }
            }
        )
    }

    fun installUpdate() {
        _updateState.update { it.copy(showInstallDialog = false) }
        updateChecker.installDownloadedApk(downloadedFile)
    }

    fun dismissUpdateDialog() {
        _updateState.update { it.copy(showDialog = false) }
    }

    fun dismissProgressDialog() {
        // 后台下载，只关闭对话框
        _updateState.update { it.copy(isDownloading = false) }
    }

    fun dismissInstallDialog() {
        _updateState.update { it.copy(showInstallDialog = false) }
    }

    fun clearError() {
        _updateState.update { it.copy(error = null) }
    }

    /**
     * 同步时间到设备
     */
    fun syncTime() {
        if (_timeSyncState.value.isSyncing) return

        _timeSyncState.update { it.copy(isSyncing = true, result = null) }

        viewModelScope.launch {
            try {
                // 获取时间戳（优先网络，回退本地）
                val timestamp = timeSyncHelper.getTimestamp()
                val timeStr = timeSyncHelper.formatTimestamp(timestamp)

                // 发送到设备
                val success = bleManager.syncTime(timestamp)

                if (success) {
                    _timeSyncState.update {
                        it.copy(
                            isSyncing = false,
                            lastSyncTime = timeStr,
                            result = TimeSyncResult.Success(timestamp, timeStr)
                        )
                    }
                } else {
                    _timeSyncState.update {
                        it.copy(
                            isSyncing = false,
                            result = TimeSyncResult.Error("发送失败，请检查蓝牙连接")
                        )
                    }
                }
            } catch (e: Exception) {
                _timeSyncState.update {
                    it.copy(
                        isSyncing = false,
                        result = TimeSyncResult.Error("同步失败: ${e.message}")
                    )
                }
            }
        }
    }

    fun clearTimeSyncResult() {
        _timeSyncState.update { it.copy(result = null) }
    }

    /**
     * Update selected baud rate for UART recording
     */
    fun setBaudRate(baudRate: Int) {
        _uartState.update { it.copy(selectedBaudRate = baudRate) }
    }

    fun setDataBits(dataBits: Int) {
        _uartState.update { it.copy(selectedDataBits = dataBits) }
    }

    fun setParity(parity: Int) {
        _uartState.update { it.copy(selectedParity = parity) }
    }

    fun setStopBits(stopBits: Int) {
        _uartState.update { it.copy(selectedStopBits = stopBits) }
    }

    /**
     * Start UART data recording
     */
    fun startUartRecord() {
        if (_uartState.value.isRecording) return

        viewModelScope.launch {
            val state = _uartState.value
            Log.d(TAG, "Start UART record: baud=${state.selectedBaudRate} data=${state.selectedDataBits} parity=${state.selectedParity} stop=${state.selectedStopBits}")

            val success = bleManager.startUartRecord(
                baudRate = state.selectedBaudRate,
                dataBits = state.selectedDataBits,
                parity = state.selectedParity,
                stopBits = state.selectedStopBits
            )
            if (success) {
                _uartState.update {
                    it.copy(isRecording = true, result = "Recording started at ${state.selectedBaudRate} baud")
                }
            } else {
                _uartState.update {
                    it.copy(result = "Failed to start recording, check BLE connection")
                }
            }
        }
    }

    /**
     * Stop UART data recording
     */
    fun stopUartRecord() {
        if (!_uartState.value.isRecording) return

        viewModelScope.launch {
            Log.d(TAG, "Stop UART record")

            val success = bleManager.stopUartRecord()
            if (success) {
                _uartState.update {
                    it.copy(isRecording = false, result = "Recording stopped")
                }
            } else {
                _uartState.update {
                    it.copy(result = "Failed to stop recording, check BLE connection")
                }
            }
        }
    }

    fun clearUartResult() {
        _uartState.update { it.copy(result = null) }
    }
}
