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

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val updateChecker: UpdateChecker,
    private val timeSyncHelper: TimeSyncHelper,
    private val bleManager: BleManager
) : ViewModel() {

    private val _timeSyncState = MutableStateFlow(TimeSyncState())
    val timeSyncState: StateFlow<TimeSyncState> = _timeSyncState.asStateFlow()

    private val _updateState = MutableStateFlow(UpdateState())
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

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
}
