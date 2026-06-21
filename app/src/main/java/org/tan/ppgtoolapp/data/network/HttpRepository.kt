package org.tan.ppgtoolapp.data.network

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import android.util.Log
import javax.inject.Singleton

@Singleton
class HttpRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "HttpRepository"
    }

    private var api: DeviceApi? = null
    private var currentIp: String? = null

    fun setDeviceIp(ip: String) {
        currentIp = ip
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        api = Retrofit.Builder()
            .baseUrl("http://$ip/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(DeviceApi::class.java)
    }

    suspend fun getFileList(): List<String> = withContext(Dispatchers.IO) {
        api?.getFileList()?.files ?: emptyList()
    }

    /**
     * Download file with CRC32 verification and byte-level progress
     * @param onProgress callback: (percent, downloadedBytes, totalBytes)
     * @return DownloadResult with file and verification status
     */
    suspend fun downloadFile(filename: String, onProgress: ((Int, Long, Long) -> Unit)? = null): DownloadResult? = withContext(Dispatchers.IO) {
        try {
            val response = api?.downloadFile(filename) ?: return@withContext null
            if (!response.isSuccessful) return@withContext null

            val body = response.body() ?: return@withContext null
            val serverCrc = response.headers()["X-File-CRC32"]
            val totalSize = body.contentLength()

            val dir = File(context.getExternalFilesDir(null), "PPG")
            dir.mkdirs()
            val file = File(dir, filename.substringAfterLast("/"))

            val crc = java.util.zip.CRC32()

            body.byteStream().use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalBytes = 0L

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        crc.update(buffer, 0, bytesRead)
                        totalBytes += bytesRead
                        if (totalSize > 0) {
                            val percent = (totalBytes * 100 / totalSize).toInt()
                            onProgress?.invoke(percent, totalBytes, totalSize)
                        }
                    }
                }
            }

            // Verify CRC32
            val localCrc = String.format("%08X", crc.value)
            val crcMatch = serverCrc == null || serverCrc.equals(localCrc, ignoreCase = true)

            if (!crcMatch) {
                Log.e(TAG, "CRC32 mismatch: server=$serverCrc local=$localCrc")
            }

            DownloadResult(file = file, md5Match = crcMatch, serverMd5 = serverCrc, localMd5 = localCrc)
        } catch (e: Exception) {
            Log.e(TAG, "Download error: ${e.message}")
            null
        }
    }

    suspend fun getDeviceStatus(): DeviceStatusResponse? = withContext(Dispatchers.IO) {
        try { api?.getStatus() } catch (e: Exception) { null }
    }
    suspend fun uploadFirmware(file: File, onProgress: ((Int) -> Unit)? = null): Boolean = withContext(Dispatchers.IO) {
        try {
            val requestBody = ProgressRequestBody(file, onProgress)
            api?.uploadFirmware(requestBody)
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun shutdown(): Boolean = withContext(Dispatchers.IO) {
        try { api?.shutdown(); true } catch (e: Exception) { false }
    }

    /**
     * 从 GitHub 下载文件（使用优选 DNS）
     * @param url 完整的 GitHub 下载地址
     * @param outputFile 保存路径
     * @param onProgress 进度回调 (0-100)
     */
    suspend fun downloadFromGitHub(
        url: String,
        outputFile: File,
        onProgress: ((Int) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val client = AppHttpClient.get(context)
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                response.close()
                return@withContext false
            }

            response.body?.byteStream()?.use { input ->
                outputFile.parentFile?.mkdirs()
                outputFile.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalBytes = 0L
                    val totalSize = response.body?.contentLength() ?: -1

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytes += bytesRead
                        if (totalSize > 0) {
                            onProgress?.invoke((totalBytes * 100 / totalSize).toInt())
                        }
                    }
                }
            }
            response.close()
            true
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * 支持进度回调的 RequestBody
 */
private class ProgressRequestBody(
    private val file: File,
    private val onProgress: ((Int) -> Unit)?
) : RequestBody() {

    override fun contentType(): MediaType? = "application/octet-stream".toMediaTypeOrNull()

    override fun contentLength(): Long = file.length()

    override fun writeTo(sink: BufferedSink) {
        val totalBytes = file.length()
        var uploadedBytes = 0L

        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int

            while (input.read(buffer).also { bytesRead = it } != -1) {
                sink.write(buffer, 0, bytesRead)
                uploadedBytes += bytesRead
                if (totalBytes > 0) {
                    onProgress?.invoke((uploadedBytes * 100 / totalBytes).toInt())
                }
            }
        }
    }
}
