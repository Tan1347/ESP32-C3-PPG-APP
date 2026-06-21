package org.tan.ppgtoolapp.data.network

import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

/**
 * ESP32-C3 HTTP API 接口
 * 与固件 components/wifi_transfer/wifi_transfer.c 中的端点一致
 */
interface DeviceApi {

    @GET("/api/files")
    suspend fun getFileList(): FileListResponse

    @Streaming
    @GET("/api/download")
    suspend fun downloadFile(@Query("file") filename: String): Response<ResponseBody>

    @GET("/api/status")
    suspend fun getStatus(): DeviceStatusResponse

    @Streaming
    @POST("/api/ota")
    suspend fun uploadFirmware(@Body body: RequestBody): ResponseBody

    @GET("/api/logs")
    suspend fun getLogList(): LogListResponse

    @GET("/api/logs/download")
    suspend fun downloadLogFile(@Query("file") filename: String): ResponseBody

    @POST("/api/shutdown")
    suspend fun shutdown(): ResponseBody

    @GET("/api/ota/info")
    suspend fun getOtaInfo(): OtaInfoResponse
}

data class OtaInfoResponse(
    val current_version: String = "",
    val build_time: String = "",
    val current_size: Long = 0,
    val partition_label: String = "",
    val partition_offset: String = "",
    val partition_size: Long = 0,
    val next_partition: String = ""
)

data class FileListResponse(
    val files: List<String>
)

data class DeviceStatusResponse(
    val version: String,
    val battery: BatteryInfo,
    val ip: String,
    val sd_free_mb: Int
)

data class BatteryInfo(
    val batt_pct: Int
)

data class LogListResponse(
    val logs: List<String>
)

data class DownloadResult(
    val file: java.io.File,
    val crcMatch: Boolean,
    val serverCrc: String?,
    val localCrc: String
)
