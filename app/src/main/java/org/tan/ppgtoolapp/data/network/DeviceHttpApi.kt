package org.tan.ppgtoolapp.data.network

import java.io.File

/**
 * 与 ESP32 设备的 HTTP 通信接口
 * 对应 HttpRepository 中的实际方法签名
 */
interface DeviceHttpApi {
    fun setDeviceIp(ip: String)
    fun getDeviceIp(): String?
    suspend fun getFileList(): List<String>
    suspend fun downloadFile(filename: String, onProgress: ((Int, Long, Long) -> Unit)? = null): DownloadResult?
    suspend fun getDeviceStatus(): ApiResult<DeviceStatusResponse>
    suspend fun uploadFirmware(file: File, onProgress: ((Int) -> Unit)? = null): OperationResult
    suspend fun downloadFromGitHub(url: String, outputFile: File, onProgress: ((Int) -> Unit)? = null): Boolean
    suspend fun getOtaInfo(): ApiResult<OtaInfoResponse>
    suspend fun shutdown(): OperationResult
}
