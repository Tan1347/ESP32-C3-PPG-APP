package org.tan.ppgtoolapp.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.tan.ppgtoolapp.data.ble.BleCommandProvider
import org.tan.ppgtoolapp.data.ble.BleConnectionProvider
import org.tan.ppgtoolapp.data.ble.PpgGattProfile
import org.tan.ppgtoolapp.data.network.*
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

private const val TAG = "OtaViewModel"

data class OtaInfo(
    val currentVersion: String = "",
    val buildTime: String = "",
    val currentSize: Long = 0,
    val partitionLabel: String = "",
    val partitionSize: Long = 0
)

data class OtaState(
    val deviceVersion: String = "未知",
    val firmwareRepo: String = "",
    val operation: OperationState = OperationState.Idle,
    val otaInfo: OtaInfo? = null,
    val releaseList: List<ReleaseInfo> = emptyList(),
    val isLoadingReleases: Boolean = false,
    val showReleaseDialog: Boolean = false,
    val showRepoDialog: Boolean = false,
    val firmwareFile: File? = null,
    val selectedFileName: String? = null,
    val result: OtaResult? = null,
    val error: String? = null
)

sealed class OperationState {
    data object Idle : OperationState()
    data class Downloading(val progress: Int, val text: String) : OperationState()
    data class Extracting(val text: String) : OperationState()
    data class Uploading(val progress: Int, val text: String) : OperationState()
}

sealed class OtaResult {
    data class Success(val message: String) : OtaResult()
    data class Error(val message: String) : OtaResult()
}

@HiltViewModel
class OtaViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpRepository: DeviceHttpApi,
    private val updateChecker: UpdateChecker,
    private val otaRepository: OtaRepository,
    private val bleConnection: BleConnectionProvider,
    private val bleCommander: BleCommandProvider
) : ViewModel() {

    companion object {
        const val DEFAULT_FIRMWARE_REPO = OtaRepository.DEFAULT_FIRMWARE_REPO
    }

    private val _state = MutableStateFlow(OtaState())
    val state: StateFlow<OtaState> = _state.asStateFlow()

    private var extractedDir: File? = null

    init {
        val repo = otaRepository.getFirmwareRepo()
        _state.update { it.copy(firmwareRepo = repo) }
    }

    fun getFirmwareRepo(): String = otaRepository.getFirmwareRepo()

    fun saveFirmwareRepo(repo: String) {
        otaRepository.saveFirmwareRepo(repo)
        _state.update { it.copy(firmwareRepo = repo.trim()) }
    }

    fun showRepoDialog() {
        _state.update { it.copy(showRepoDialog = true) }
    }

    fun dismissRepoDialog() {
        _state.update { it.copy(showRepoDialog = false) }
    }

    fun loadDeviceStatus() {
        viewModelScope.launch {
            Log.i(TAG, "Loading device status")

            // Try BLE first
            if (bleConnection.isConnected()) {
                val bleVersion = getVersionFromBle()
                if (bleVersion != null) {
                    _state.update { it.copy(deviceVersion = bleVersion) }
                }
            }

            // Try HTTP for OTA info
            when (val otaResult = httpRepository.getOtaInfo()) {
                is ApiResult.Success -> {
                    val otaInfo = otaResult.data
                    _state.update {
                        it.copy(
                            deviceVersion = otaInfo.current_version.ifEmpty { it.deviceVersion },
                            otaInfo = OtaInfo(
                                currentVersion = otaInfo.current_version,
                                buildTime = otaInfo.build_time,
                                currentSize = otaInfo.current_size,
                                partitionLabel = otaInfo.partition_label,
                                partitionSize = otaInfo.partition_size
                            )
                        )
                    }
                    return@launch
                }
                is ApiResult.Error -> {
                    // Fall through to try getStatus
                }
            }

            when (val statusResult = httpRepository.getDeviceStatus()) {
                is ApiResult.Success -> {
                    _state.update { it.copy(deviceVersion = statusResult.data.version) }
                }
                is ApiResult.Error -> {
                    Log.w(TAG, "HTTP status failed: ${statusResult.message}")
                    _state.update { it.copy(deviceVersion = "Not connected") }
                }
            }
        }
    }

    private suspend fun getVersionFromBle(): String? {
        return try {
            val data = bleCommander.readCharacteristic(PpgGattProfile.CHAR_STATUS)
            if (data != null && data.size >= 20) {
                val version = String(data.copyOfRange(5, 20), Charsets.UTF_8).trim()
                if (version.isNotEmpty()) version else null
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "BLE read error: ${e.message}")
            null
        }
    }

    fun loadReleases() {
        if (_state.value.isLoadingReleases) return

        _state.update { it.copy(isLoadingReleases = true, error = null) }

        viewModelScope.launch {
            try {
                val repo = _state.value.firmwareRepo.ifBlank { DEFAULT_FIRMWARE_REPO }
                val releases = otaRepository.fetchReleases(repo)
                _state.update {
                    it.copy(
                        isLoadingReleases = false,
                        releaseList = releases,
                        showReleaseDialog = releases.isNotEmpty()
                    )
                }
                if (releases.isEmpty()) {
                    _state.update { it.copy(error = "No releases with firmware found") }
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(isLoadingReleases = false, error = "Failed to fetch releases: ${e.message}")
                }
            }
        }
    }

    fun downloadAndExtract(release: ReleaseInfo) {
        _state.update {
            it.copy(
                showReleaseDialog = false,
                operation = OperationState.Downloading(0, "Preparing download..."),
                error = null,
                firmwareFile = null
            )
        }

        viewModelScope.launch {
            try {
                val mirrors = otaRepository.getSortedMirrors().filter { it.isNotEmpty() }
                val mirrorPrefix = mirrors.firstOrNull() ?: "https://ghfast.top/"
                val downloadUrl = "$mirrorPrefix${release.apkUrl}"

                val downloadDir = otaRepository.getDownloadDir()
                val sevenZipFile = File(downloadDir, "firmware-${release.tagName}.7z")

                val success = httpRepository.downloadFromGitHub(
                    url = downloadUrl,
                    outputFile = sevenZipFile,
                    onProgress = { percent ->
                        _state.update { it.copy(operation = OperationState.Downloading(percent, "Downloading... $percent%")) }
                    }
                )

                if (!success) {
                    _state.update { it.copy(operation = OperationState.Idle, error = "Download failed") }
                    return@launch
                }

                _state.update { it.copy(operation = OperationState.Extracting("Extracting...")) }
                val extractDir = File(downloadDir, "extract-${System.currentTimeMillis()}")
                val firmwareFile = otaRepository.extractFirmware(sevenZipFile, extractDir)
                sevenZipFile.delete()

                if (firmwareFile != null) {
                    extractedDir = extractDir
                    _state.update {
                        it.copy(operation = OperationState.Idle, firmwareFile = firmwareFile,
                            selectedFileName = firmwareFile.name)
                    }
                } else {
                    _state.update { it.copy(operation = OperationState.Idle, error = "No .bin found in archive") }
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(operation = OperationState.Idle, error = "Processing failed: ${e.message}")
                }
            }
        }
    }

    fun selectLocalFile(uri: Uri) {
        viewModelScope.launch {
            try {
                _state.update { it.copy(operation = OperationState.Extracting("Processing file..."), error = null) }

                val tempDir = otaRepository.getDownloadDir()
                val tempFile = File(tempDir, "local-firmware.7z")

                val copied = otaRepository.copyFileFromUri(uri, tempFile)
                if (!copied) {
                    _state.update { it.copy(operation = OperationState.Idle, error = "Failed to read file") }
                    return@launch
                }

                if (!otaRepository.is7zFile(tempFile)) {
                    val fileName = otaRepository.getFileNameFromUri(uri)
                    if (fileName != null && fileName.endsWith(".bin", ignoreCase = true)) {
                        val binFile = File(tempDir, fileName)
                        tempFile.renameTo(binFile)
                        _state.update {
                            it.copy(operation = OperationState.Idle, firmwareFile = binFile, selectedFileName = fileName)
                        }
                        return@launch
                    }

                    tempFile.delete()
                    _state.update { it.copy(operation = OperationState.Idle, error = "Unsupported file format") }
                    return@launch
                }

                val extractDir = File(tempDir, "extract-local-${System.currentTimeMillis()}")
                val firmwareFile = otaRepository.extractFirmware(tempFile, extractDir)
                tempFile.delete()

                if (firmwareFile != null) {
                    extractedDir = extractDir
                    _state.update {
                        it.copy(operation = OperationState.Idle, firmwareFile = firmwareFile,
                            selectedFileName = firmwareFile.name)
                    }
                } else {
                    _state.update { it.copy(operation = OperationState.Idle, error = "No .bin found in archive") }
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(operation = OperationState.Idle, error = "File processing failed: ${e.message}")
                }
            }
        }
    }

    fun startOta() {
        val firmwareFile = _state.value.firmwareFile ?: return

        _state.update {
            it.copy(operation = OperationState.Uploading(0, "Preparing upload..."), error = null, result = null)
        }

        viewModelScope.launch {
            try {
                when (val result = httpRepository.uploadFirmware(firmwareFile) { progress ->
                    _state.update { it.copy(operation = OperationState.Uploading(progress, "Uploading... $progress%")) }
                }) {
                    is OperationResult.Success -> {
                        _state.update {
                            it.copy(operation = OperationState.Idle, result = OtaResult.Success("Firmware uploaded, device will restart"))
                        }
                    }
                    is OperationResult.Error -> {
                        _state.update {
                            it.copy(operation = OperationState.Idle, result = OtaResult.Error(result.message))
                        }
                    }
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(operation = OperationState.Idle, result = OtaResult.Error("Upload error: ${e.message}"))
                }
            }
        }
    }

    fun dismissReleaseDialog() {
        _state.update { it.copy(showReleaseDialog = false) }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun clearResult() {
        _state.update { it.copy(result = null) }
    }

    fun cleanup() {
        extractedDir?.deleteRecursively()
        extractedDir = null
    }
}
