package com.ppgtool.app.data.network

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.asRequestBody
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HttpRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
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

    suspend fun downloadFile(filename: String, onProgress: ((Int) -> Unit)? = null): File? = withContext(Dispatchers.IO) {
        try {
            val response = api?.downloadFile(filename) ?: return@withContext null
            val dir = File(context.getExternalFilesDir(null), "PPG")
            dir.mkdirs()
            val file = File(dir, filename.substringAfterLast("/"))

            response.byteStream().use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalBytes = 0L
                    val totalSize = response.contentLength()

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytes += bytesRead
                        if (totalSize > 0) {
                            onProgress?.invoke((totalBytes * 100 / totalSize).toInt())
                        }
                    }
                }
            }
            file
        } catch (e: Exception) {
            null
        }
    }

    suspend fun getDeviceStatus(): DeviceStatusResponse? = withContext(Dispatchers.IO) {
        try { api?.getStatus() } catch (e: Exception) { null }
    }

    suspend fun uploadFirmware(file: File, onProgress: ((Int) -> Unit)? = null): Boolean = withContext(Dispatchers.IO) {
        try {
            val requestBody = file.asRequestBody("application/octet-stream".toMediaTypeOrNull())
            api?.uploadFirmware(requestBody as retrofit2.Response<okhttp3.ResponseBody>)
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun shutdown(): Boolean = withContext(Dispatchers.IO) {
        try { api?.shutdown(); true } catch (e: Exception) { false }
    }
}
