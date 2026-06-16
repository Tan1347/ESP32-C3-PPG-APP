package com.ppgtool.app.data.network

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 时间同步工具
 * 优先从网络获取 UTC+8 时间戳，失败时使用本地时间
 */
@Singleton
class TimeSyncHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        // 网络时间接口（返回 Unix 13 位毫秒时间戳，已含 UTC+8 偏移）
        private const val TIME_API_URL = "https://ip.ddnspod.com/timestamp"

        // 超时时间
        private const val CONNECT_TIMEOUT = 5000
        private const val READ_TIMEOUT = 5000
    }

    /**
     * 获取当前 Unix 10 位时间戳（秒）
     * 优先从网络获取，失败时使用本地时间（带 UTC+8 偏移）
     *
     * @return Unix 10 位时间戳（秒），UTC+8 时区
     */
    suspend fun getTimestamp(): Long = withContext(Dispatchers.IO) {
        try {
            val networkTimestamp = fetchNetworkTimestamp()
            if (networkTimestamp != null) {
                return@withContext networkTimestamp
            }
        } catch (_: Exception) {
            // 网络请求失败，回退到本地时间
        }

        // 使用本地时间 + UTC+8 偏移
        getLocalTimestampWithUTC8()
    }

    /**
     * 从网络获取时间戳
     * @return Unix 10 位时间戳（秒），失败返回 null
     */
    private suspend fun fetchNetworkTimestamp(): Long? = withContext(Dispatchers.IO) {
        try {
            val url = URL(TIME_API_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = READ_TIMEOUT
            conn.requestMethod = "GET"

            if (conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader().readText().trim()
                conn.disconnect()

                // 接口返回 Unix 13 位毫秒时间戳（已含 UTC+8）
                // 去除最后 3 位得到 10 位秒级时间戳
                val timestamp13 = response.toLongOrNull()
                if (timestamp13 != null && timestamp13 > 1_000_000_000_000L) {
                    return@withContext timestamp13 / 1000
                }
            }
            conn.disconnect()
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 获取本地时间戳（带 UTC+8 偏移）
     * @return Unix 10 位时间戳（秒），UTC+8 时区
     */
    private fun getLocalTimestampWithUTC8(): Long {
        val utc8Zone = ZoneId.of("Asia/Shanghai")
        val now = ZonedDateTime.now(utc8Zone)
        return now.toEpochSecond()
    }

    /**
     * 格式化时间戳为可读字符串
     * @param timestamp Unix 10 位时间戳（秒）
     * @return 格式化的时间字符串
     */
    fun formatTimestamp(timestamp: Long): String {
        val instant = Instant.ofEpochSecond(timestamp)
        val utc8Zone = ZoneId.of("Asia/Shanghai")
        val zonedDateTime = instant.atZone(utc8Zone)
        return "${zonedDateTime.year}-${zonedDateTime.monthValue.toString().padStart(2, '0')}-${zonedDateTime.dayOfMonth.toString().padStart(2, '0')} " +
                "${zonedDateTime.hour.toString().padStart(2, '0')}:${zonedDateTime.minute.toString().padStart(2, '0')}:${zonedDateTime.second.toString().padStart(2, '0')}"
    }
}
