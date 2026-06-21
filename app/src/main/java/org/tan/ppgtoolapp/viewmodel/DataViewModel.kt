package org.tan.ppgtoolapp.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.tan.ppgtoolapp.data.ble.BleManager
import org.tan.ppgtoolapp.data.local.FileMetadata
import org.tan.ppgtoolapp.data.local.FileMetadataDao
import org.tan.ppgtoolapp.data.local.FileType
import org.tan.ppgtoolapp.data.network.HttpRepository
import org.tan.ppgtoolapp.util.NotificationHelper
import javax.inject.Inject

data class DataState(
    val fileList: List<String> = emptyList(),
    val downloadedFiles: List<FileMetadata> = emptyList(),
    val isLoading: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadProgress: Int = 0,
    val downloadBytes: Long = 0,
    val downloadTotal: Long = 0,
    val downloadFileName: String = "",
    val error: String? = null
)

@HiltViewModel
class DataViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bleManager: BleManager,
    private val httpRepository: HttpRepository,
    private val fileMetadataDao: FileMetadataDao
) : ViewModel() {

    companion object {
        private const val TAG = "DataViewModel"
    }

    private val _state = MutableStateFlow(DataState())
    val state: StateFlow<DataState> = _state.asStateFlow()

    init {
        loadDownloadedFiles()
    }

    /**
     * Load file list from device via HTTP
     */
    fun loadFileList() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val files = httpRepository.getFileList()
                _state.update { it.copy(fileList = files, isLoading = false) }
                Log.d(TAG, "File list loaded: ${files.size} files")
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = "Load failed: ${e.message}") }
            }
        }
    }

    /**
     * Trigger BLE download + HTTP transfer for a single file
     * Flow: BLE 0x32 -> get IP -> HTTP download
     */
    fun downloadFile(fileName: String) {
        viewModelScope.launch {
            if (fileMetadataDao.exists(fileName)) {
                _state.update { it.copy(error = "File already downloaded: $fileName") }
                return@launch
            }

            _state.update { it.copy(isDownloading = true, downloadProgress = 0, downloadBytes = 0, downloadTotal = 0, downloadFileName = fileName, error = null) }
            NotificationHelper.showProgress(context, fileName.substringAfterLast("/"), 0)

            try {
                // Step 1: BLE trigger - ensure WiFi is ready and get IP
                val ip = bleManager.triggerFileDownload()
                if (ip == null) {
                    _state.update { it.copy(isDownloading = false, error = "WiFi connection failed") }
                    NotificationHelper.showFailed(context, fileName.substringAfterLast("/"), "WiFi connection failed")
                    return@launch
                }
                httpRepository.setDeviceIp(ip)

                // Step 2: HTTP download with MD5 verification
                val result = httpRepository.downloadFile(fileName) { percent, downloaded, total ->
                    _state.update { it.copy(downloadProgress = percent, downloadBytes = downloaded, downloadTotal = total) }
                    NotificationHelper.showProgress(context, fileName.substringAfterLast("/"), percent)
                }

                if (result != null) {
                    if (!result.md5Match) {
                        Log.w(TAG, "MD5 mismatch for $fileName: server=${result.serverMd5} local=${result.localMd5}")
                        _state.update { it.copy(error = "File integrity check failed: $fileName") }
                        result.file.delete()
                        NotificationHelper.showFailed(context, fileName.substringAfterLast("/"), "MD5 mismatch")
                        return@launch
                    }

                    val metadata = FileMetadata(
                        fileName = fileName, fileSize = result.file.length(),
                        downloadTime = System.currentTimeMillis(),
                        deviceMac = bleManager.getConnectedDeviceMac() ?: "unknown",
                        localPath = result.file.absolutePath, fileType = detectFileType(fileName)
                    )
                    fileMetadataDao.insert(metadata)
                    loadDownloadedFiles()
                    NotificationHelper.showComplete(context, fileName.substringAfterLast("/"))
                    Log.d(TAG, "Downloaded: $fileName (${result.file.length()} bytes) MD5 OK")
                } else {
                    _state.update { it.copy(error = "Download failed: $fileName") }
                    NotificationHelper.showFailed(context, fileName.substringAfterLast("/"), "Download failed")
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = "Download error: ${e.message}") }
                NotificationHelper.showFailed(context, fileName.substringAfterLast("/"), e.message ?: "Unknown error")
            }

            _state.update { it.copy(isDownloading = false, downloadProgress = 0, downloadFileName = "") }
        }
    }

    /**
     * Delete a downloaded file
     */
    fun deleteFile(metadata: FileMetadata) {
        viewModelScope.launch {
            try {
                java.io.File(metadata.localPath).delete()
                fileMetadataDao.deleteByFileName(metadata.fileName)
                loadDownloadedFiles()
                Log.d(TAG, "Deleted: ${metadata.fileName}")
            } catch (e: Exception) {
                _state.update { it.copy(error = "Delete failed: ${e.message}") }
            }
        }
    }

    /**
     * Download multiple files sequentially with BLE trigger
     * Flow: for each file: BLE 0x32 -> HTTP download -> next
     */
    fun downloadFiles(fileNames: List<String>) {
        viewModelScope.launch {
            val pending = fileNames.filter { !fileMetadataDao.exists(it) }
            if (pending.isEmpty()) {
                _state.update { it.copy(error = "All files already downloaded") }
                return@launch
            }

            _state.update { it.copy(isDownloading = true, downloadProgress = 0, downloadFileName = "0/${pending.size}", error = null) }

            // BLE trigger once to ensure WiFi is ready
            val ip = bleManager.triggerFileDownload()
            if (ip == null) {
                _state.update { it.copy(isDownloading = false, error = "WiFi connection failed") }
                return@launch
            }
            httpRepository.setDeviceIp(ip)

            for ((index, fileName) in pending.withIndex()) {
                _state.update { it.copy(downloadFileName = "${index + 1}/${pending.size}: ${fileName.substringAfterLast("/")}")}
                NotificationHelper.showProgress(context, "${index + 1}/${pending.size}", (index * 100) / pending.size)

                try {
                    val result = httpRepository.downloadFile(fileName) { percent, downloaded, total ->
                        _state.update { it.copy(downloadProgress = percent, downloadBytes = downloaded, downloadTotal = total) }
                    }
                    if (result != null) {
                        if (!result.md5Match) {
                            Log.w(TAG, "Batch MD5 mismatch: $fileName")
                            result.file.delete()
                            continue
                        }
                        val metadata = FileMetadata(
                            fileName = fileName, fileSize = result.file.length(),
                            downloadTime = System.currentTimeMillis(),
                            deviceMac = bleManager.getConnectedDeviceMac() ?: "unknown",
                            localPath = result.file.absolutePath, fileType = detectFileType(fileName)
                        )
                        fileMetadataDao.insert(metadata)
                        Log.d(TAG, "Batch downloaded: $fileName (${result.file.length()} bytes) MD5 OK")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Batch download failed: $fileName - ${e.message}")
                }
            }

            loadDownloadedFiles()
            _state.update { it.copy(isDownloading = false, downloadProgress = 100, downloadFileName = "") }
            NotificationHelper.showComplete(context, "${pending.size} files downloaded")
        }
    }

    /**
     * Load downloaded files from Room database
     */
    private fun loadDownloadedFiles() {
        viewModelScope.launch {
            val files = fileMetadataDao.getAll()
            _state.update { it.copy(downloadedFiles = files) }
        }
    }

    /**
     * Detect file type from name
     */
    private fun detectFileType(fileName: String): FileType {
        return when {
            fileName.startsWith("raw/") || fileName.endsWith(".bin") && fileName.contains("raw") -> FileType.PPG_RAW
            fileName.startsWith("csv/") || fileName.endsWith(".csv") -> FileType.PPG_RESULT
            fileName.startsWith("env/") -> FileType.DHT11
            fileName.startsWith("log/") || fileName.endsWith(".log") -> FileType.LOG
            else -> FileType.UNKNOWN
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }
}
