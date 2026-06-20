package org.tan.ppgtoolapp.data.network

import android.content.Context
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 全局 HTTP 客户端单例。
 * 使用自定义 [GitHubDns] 对 GitHub 域名走优选 IP，其他域名走系统 DNS。
 */
object AppHttpClient {

    @Volatile
    private var client: OkHttpClient? = null

    fun get(context: Context): OkHttpClient {
        return client ?: synchronized(this) {
            client ?: buildClient(context.applicationContext).also { client = it }
        }
    }

    /**
     * 当优选 IP 结果更新后（用户测速完成），调用此方法重建客户端使新 IP 生效。
     */
    fun invalidate() {
        synchronized(this) {
            client = null
        }
    }

    private fun buildClient(context: Context): OkHttpClient {
        return OkHttpClient.Builder()
            .dns(GitHubDns(context))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
