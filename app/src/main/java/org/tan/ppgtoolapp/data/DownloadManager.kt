package org.tan.ppgtoolapp.data

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import org.tan.ppgtoolapp.data.local.FileMetadata
import org.tan.ppgtoolapp.data.local.FileMetadataDao
import org.tan.ppgtoolapp.data.local.FileType
import org.tan.ppgtoolapp.data.network.HttpRepository
import org.tan.ppgtoolapp.util.NotificationHelper
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Download manager - handles file download with notifications
 * Extracted from DataViewModel for separation of concerns
 */
@Singleton
class DownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpRepository: HttpRepository,
    private val fileMetadataDao: FileMetadataDao
) {
    companion object {
        private const val TAG = "DownloadManager"
    }

    /**
     * Download result
     */
    sealed class DownloadResult {
        data class Success(val metadata: FileMetadata) : DownloadResult()
        data class Error(val message: String) : DownloadResult()
    }

    /**
     * Download a single file with BLE trigger and HTTP transfer
     * @param fileName name of file to download
     * @param deviceMac connected device MAC address
     * @param onProgress progress callback (percent, downloadedBytes, totalBytes)
     */
    suspend fun downloadFile(
        fileName: String,
        deviceMac: String,
        onProgress: ((Int, Long, Long) -> Unit)? = null
    ): DownloadResult {
        val displayName = fileName.substringAfterLast("/")

        // Check if already downloaded
        if (fileMetadataDao.exists(fileName)) {
            return DownloadResult.Error("File already downloaded: $fileName")
        }

        // Show start notification
        NotificationHelper.showProgress(context, displayName, 0)

        try {
            // Step 1: BLE trigger - ensure WiFi is ready and get IP
            val ip = httpRepository.getDeviceIp()
            if (ip == null) {
                NotificationHelper.showFailed(context, displayName, "WiFi connection failed")
                return DownloadResult.Error("WiFi connection failed")
            }

            // Step 2: HTTP download with CRC verification
            val result = httpRepository.downloadFile(fileName) { percent, downloaded, total ->
                onProgress?.invoke(percent, downloaded, total)
                NotificationHelper.showProgress(context, displayName, percent)
            }

            if (result == null) {
                NotificationHelper.showFailed(context, displayName, "Download failed")
                return DownloadResult.Error("Download failed: $fileName")
            }

            // Step 3: Verify CRC
            if (!result.crcMatch) {
                Log.w(TAG, "CRC mismatch for $fileName: server=${result.serverCrc} local=${result.localCrc}")
                NotificationHelper.showFailed(context, displayName, "File integrity check failed")
                result.file.delete()
                return DownloadResult.Error("File integrity check failed: $fileName")
            }

            // Step 4: Save metadata
            val metadata = FileMetadata(
                fileName = fileName,
                fileSize = result.file.length(),
                downloadTime = System.currentTimeMillis(),
                deviceMac = deviceMac,
                localPath = result.file.absolutePath,
                fileType = detectFileType(fileName)
            )
            fileMetadataDao.insert(metadata)

            NotificationHelper.showComplete(context, displayName)
            Log.d(TAG, "Downloaded: $fileName (${result.file.length()} bytes) CRC OK")

            return DownloadResult.Success(metadata)

        } catch (e: Exception) {
            Log.e(TAG, "Download error: ${e.message}")
            NotificationHelper.showFailed(context, displayName, e.message ?: "Unknown error")
            return DownloadResult.Error("Download error: ${e.message}")
        }
    }

    /**
     * Detect file type from extension
     */
    private fun detectFileType(fileName: String): FileType {
        return when {
            fileName.endsWith(".bin", ignoreCase = true) -> FileType.PPG_RAW
            fileName.endsWith(".log", ignoreCase = true) -> FileType.LOG
            fileName.endsWith(".csv", ignoreCase = true) -> FileType.PPG_RESULT
            else -> FileType.UNKNOWN
        }
    }
}
