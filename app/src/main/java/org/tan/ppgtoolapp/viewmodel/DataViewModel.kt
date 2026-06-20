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
     * Download a file from device
     */
    fun downloadFile(fileName: String) {
        viewModelScope.launch {
            // Check if already downloaded
            if (fileMetadataDao.exists(fileName)) {
                _state.update { it.copy(error = "File already downloaded: $fileName") }
                return@launch
            }

            _state.update { it.copy(isDownloading = true, downloadProgress = 0, downloadFileName = fileName, error = null) }
            NotificationHelper.showProgress(context, fileName.substringAfterLast("/"), 0)

            try {
                val file = httpRepository.downloadFile(fileName) { progress ->
                    _state.update { it.copy(downloadProgress = progress) }
                    NotificationHelper.showProgress(context, fileName.substringAfterLast("/"), progress)
                }

                if (file != null) {
                    val metadata = FileMetadata(
                        fileName = fileName,
                        fileSize = file.length(),
                        downloadTime = System.currentTimeMillis(),
                        deviceMac = bleManager.getConnectedDeviceMac() ?: "unknown",
                        localPath = file.absolutePath,
                        fileType = detectFileType(fileName)
                    )
                    fileMetadataDao.insert(metadata)
                    loadDownloadedFiles()
                    NotificationHelper.showComplete(context, fileName.substringAfterLast("/"))
                    Log.d(TAG, "Downloaded: $fileName (${file.length()} bytes)")
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
     * Download multiple files sequentially
     */
    fun downloadFiles(fileNames: List<String>) {
        viewModelScope.launch {
            val pending = fileNames.filter { !fileMetadataDao.exists(it) }
            if (pending.isEmpty()) {
                _state.update { it.copy(error = "All files already downloaded") }
                return@launch
            }

            _state.update { it.copy(isDownloading = true, downloadProgress = 0, downloadFileName = "0/${pending.size}", error = null) }

            for ((index, fileName) in pending.withIndex()) {
                _state.update { it.copy(downloadFileName = "${index + 1}/${pending.size}: ${fileName.substringAfterLast("/")}")}
                NotificationHelper.showProgress(context, "${index + 1}/${pending.size}", (index * 100) / pending.size)

                try {
                    val file = httpRepository.downloadFile(fileName) { progress ->
                        _state.update { it.copy(downloadProgress = progress) }
                    }
                    if (file != null) {
                        val metadata = FileMetadata(
                            fileName = fileName, fileSize = file.length(),
                            downloadTime = System.currentTimeMillis(),
                            deviceMac = bleManager.getConnectedDeviceMac() ?: "unknown",
                            localPath = file.absolutePath, fileType = detectFileType(fileName)
                        )
                        fileMetadataDao.insert(metadata)
                        Log.d(TAG, "Batch downloaded: $fileName")
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
