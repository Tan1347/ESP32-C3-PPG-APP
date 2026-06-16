package com.ppgtool.app.data.network

import okhttp3.ResponseBody
import retrofit2.http.*

/**
 * ESP32-C3 HTTP API 接口
 * 与固件 components/wifi_transfer/wifi_transfer.c 中的端点一致
 */
interface DeviceApi {

    @GET("/api/files")
    suspend fun getFileList(): FileListResponse

    @GET("/api/download")
    suspend fun downloadFile(@Query("file") filename: String): ResponseBody

    @GET("/api/status")
    suspend fun getStatus(): DeviceStatusResponse

    @Streaming
    @POST("/api/ota")
    suspend fun uploadFirmware(@Body body: ResponseBody): ResponseBody

    @GET("/api/logs")
    suspend fun getLogList(): LogListResponse

    @GET("/api/logs/download")
    suspend fun downloadLogFile(@Query("file") filename: String): ResponseBody

    @POST("/api/shutdown")
    suspend fun shutdown(): ResponseBody
}

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
    val soc: Int,
    val voltage: Int
)

data class LogListResponse(
    val logs: List<String>
)
