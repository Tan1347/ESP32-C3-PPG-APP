package com.ppgtool.app.data.network

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

object GitHubHostsHelper {

    // 远程 hosts 文件地址（社区维护，定时更新 GitHub 优选 IP）
    private const val REMOTE_HOSTS_URL = "https://raw.hellogithub.com/hosts"

    // 代理镜像域名列表
    private val MIRROR_DOMAINS = listOf(
        "ghfast.top",
        "ghproxy.net",
        "github.moeyy.xyz"
    )

    data class IpResult(
        val ip: String,
        val latency: Long  // ms, Long.MAX_VALUE = unreachable
    )

    data class DomainResult(
        val domain: String,
        val bestIp: String,
        val bestLatency: Long,
        val allResults: List<IpResult>
    )

    private const val PREF_NAME = "github_hosts"
    private const val KEY_CACHE = "hosts_cache"
    private const val KEY_TIMESTAMP = "hosts_timestamp"
    private const val KEY_REMOTE_CACHE = "remote_hosts_cache"
    private const val KEY_REMOTE_TIMESTAMP = "remote_hosts_timestamp"
    private const val CACHE_DURATION_MS = 24 * 60 * 60 * 1000L  // 24 hours

    // GitHub 相关域名列表，用于从远程 hosts 文件中筛选
    private val GITHUB_DOMAINS = listOf(
        "github.com",
        "api.github.com",
        "github.global.ssl.fastly.net",
        "objects.githubusercontent.com",
        "raw.githubusercontent.com",
        "gist.github.com",
        "gist.githubusercontent.com",
        "codeload.github.com",
        "collector.github.com"
    )

    // GitHub 域名对应的已知 CDN IP 池
    val GITHUB_IPS = mapOf(
        "github.com" to listOf(
            "20.205.243.166",
            "140.82.121.3", "140.82.121.4",
            "140.82.114.3", "140.82.114.4",
            "20.200.245.247", "20.201.211.129",
            "20.207.73.82", "20.175.191.89"
        ),
        "github.global.ssl.fastly.net" to listOf(
            "151.101.1.69", "151.101.65.69",
            "151.101.129.69", "151.101.193.69"
        ),
        "objects.githubusercontent.com" to listOf(
            "185.199.108.133", "185.199.109.133",
            "185.199.110.133", "185.199.111.133"
        ),
        "raw.githubusercontent.com" to listOf(
            "185.199.108.133", "185.199.109.133",
            "185.199.110.133", "185.199.111.133"
        ),
        "gist.github.com" to listOf(
            "140.82.114.3", "140.82.114.4",
            "140.82.121.3", "140.82.121.4"
        )
    )

    /**
     * 测试单个 IP 的 TCP 连接延迟
     */
    fun testIp(ip: String, port: Int = 443, timeout: Int = 3000): Long {
        val start = System.currentTimeMillis()
        return try {
            val socket = Socket()
            socket.connect(InetSocketAddress(ip, port), timeout)
            val latency = System.currentTimeMillis() - start
            socket.close()
            latency
        } catch (e: Exception) {
            Long.MAX_VALUE
        }
    }

    /**
     * 测试所有域名的所有 IP，返回每个域名的最佳 IP
     */
    fun testAll(onProgress: (domain: String, ip: String, latency: Long) -> Unit = { _, _, _ -> }): List<DomainResult> {
        val results = mutableListOf<DomainResult>()

        for ((domain, ips) in GITHUB_IPS) {
            val ipResults = mutableListOf<IpResult>()
            var bestIp = ips[0]
            var bestLatency = Long.MAX_VALUE

            for (ip in ips) {
                val lat1 = testIp(ip)
                val lat2 = if (lat1 < Long.MAX_VALUE) testIp(ip) else Long.MAX_VALUE
                val avgLatency = if (lat1 < Long.MAX_VALUE && lat2 < Long.MAX_VALUE) {
                    (lat1 + lat2) / 2
                } else {
                    minOf(lat1, lat2)
                }

                ipResults.add(IpResult(ip, avgLatency))
                onProgress(domain, ip, avgLatency)

                if (avgLatency < bestLatency) {
                    bestLatency = avgLatency
                    bestIp = ip
                }
            }

            results.add(DomainResult(domain, bestIp, bestLatency, ipResults.sortedBy { it.latency }))
        }

        return results
    }

    /**
     * 保存测试结果到 SharedPreferences
     */
    fun saveResults(context: Context, results: List<DomainResult>) {
        val cache = JSONObject()
        for (result in results) {
            cache.put(result.domain, result.bestIp)
        }
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CACHE, cache.toString())
            .putLong(KEY_TIMESTAMP, System.currentTimeMillis())
            .apply()
    }

    /**
     * 获取缓存的最佳 IP，优先使用本地测速结果，其次使用远程 hosts 数据
     */
    fun getBestIp(context: Context, domain: String): String? {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

        // 1. 优先使用本地测速缓存
        val localTimestamp = prefs.getLong(KEY_TIMESTAMP, 0)
        if (System.currentTimeMillis() - localTimestamp <= CACHE_DURATION_MS) {
            val localJson = prefs.getString(KEY_CACHE, null)
            if (localJson != null) {
                try {
                    val obj = JSONObject(localJson)
                    val ip = obj.optString(domain, "").ifEmpty { null }
                    if (ip != null) return ip
                } catch (_: Exception) {}
            }
        }

        // 2. 回退到远程 hosts 缓存
        val remoteTimestamp = prefs.getLong(KEY_REMOTE_TIMESTAMP, 0)
        if (System.currentTimeMillis() - remoteTimestamp <= CACHE_DURATION_MS) {
            val remoteJson = prefs.getString(KEY_REMOTE_CACHE, null)
            if (remoteJson != null) {
                try {
                    val obj = JSONObject(remoteJson)
                    val ip = obj.optString(domain, "").ifEmpty { null }
                    if (ip != null) return ip
                } catch (_: Exception) {}
            }
        }

        return null
    }

    /**
     * 获取上次测试时间戳
     */
    fun getLastTestTime(context: Context): Long {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_TIMESTAMP, 0)
    }

    /**
     * 缓存是否有效
     */
    fun isCacheValid(context: Context): Boolean {
        val timestamp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_TIMESTAMP, 0)
        return System.currentTimeMillis() - timestamp <= CACHE_DURATION_MS
    }

    /**
     * 清除缓存
     */
    fun clearCache(context: Context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    // --- 远程 Hosts 获取 ---

    /**
     * 从远程获取 GitHub hosts 文件，解析并缓存
     */
    fun fetchRemoteHosts(context: Context): Map<String, String> {
        return try {
            val url = URL(REMOTE_HOSTS_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "PPGTool-Android")
            conn.connectTimeout = 10000
            conn.readTimeout = 10000

            if (conn.responseCode == 200) {
                val text = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                val parsed = parseHostsFile(text)
                if (parsed.isNotEmpty()) {
                    saveRemoteHosts(context, parsed)
                }
                parsed
            } else {
                conn.disconnect()
                emptyMap()
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /**
     * 解析标准 hosts 文件格式，只保留 GitHub 相关域名
     */
    private fun parseHostsFile(text: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

            val parts = trimmed.split(Regex("\\s+"))
            if (parts.size < 2) continue

            val ip = parts[0]
            for (i in 1 until parts.size) {
                val domain = parts[i].lowercase()
                if (GITHUB_DOMAINS.any { domain == it || domain.endsWith(".$it") }) {
                    if (ip.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+"))) {
                        result[domain] = ip
                    }
                }
            }
        }
        return result
    }

    /**
     * 保存远程 hosts 数据到 SharedPreferences
     */
    private fun saveRemoteHosts(context: Context, hosts: Map<String, String>) {
        val json = JSONObject()
        for ((domain, ip) in hosts) {
            json.put(domain, ip)
        }
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_REMOTE_CACHE, json.toString())
            .putLong(KEY_REMOTE_TIMESTAMP, System.currentTimeMillis())
            .apply()
    }

    /**
     * 获取远程 hosts 缓存
     */
    fun getRemoteHostsCache(context: Context): Map<String, String> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_REMOTE_CACHE, null) ?: return emptyMap()
        val timestamp = prefs.getLong(KEY_REMOTE_TIMESTAMP, 0)
        if (System.currentTimeMillis() - timestamp > CACHE_DURATION_MS) return emptyMap()

        return try {
            val obj = JSONObject(json)
            val result = mutableMapOf<String, String>()
            for (key in obj.keys()) {
                result[key] = obj.getString(key)
            }
            result
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /**
     * 获取上次远程 hosts 更新时间
     */
    fun getRemoteHostsTime(context: Context): Long {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_REMOTE_TIMESTAMP, 0)
    }

    // --- 镜像延迟测试 ---

    private const val KEY_MIRROR_CACHE = "mirror_latency_cache"
    private const val KEY_MIRROR_TIMESTAMP = "mirror_latency_timestamp"

    /**
     * 测试镜像域名的延迟
     */
    fun testMirrorLatency(domain: String, timeout: Int = 3000): Long {
        return try {
            val addresses = java.net.InetAddress.getAllByName(domain)
            if (addresses.isEmpty()) return Long.MAX_VALUE
            testIp(addresses[0].hostAddress ?: return Long.MAX_VALUE, 443, timeout)
        } catch (e: Exception) {
            Long.MAX_VALUE
        }
    }

    /**
     * 测试所有镜像延迟并排序
     */
    fun testAndSortMirrors(): List<String> {
        data class MirrorLatency(val prefix: String, val latency: Long)

        val mirrorLatencies = mutableListOf<MirrorLatency>()
        mirrorLatencies.add(MirrorLatency("", 0))

        for (domain in MIRROR_DOMAINS) {
            val latency = testMirrorLatency(domain)
            mirrorLatencies.add(MirrorLatency("https://$domain/", latency))
        }

        val sorted = mutableListOf(mirrorLatencies[0])
        sorted.addAll(mirrorLatencies.drop(1).sortedBy { it.latency })

        return sorted.map { it.prefix }
    }

    /**
     * 获取排序后的镜像列表（带缓存）
     */
    fun getSortedMirrors(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val timestamp = prefs.getLong(KEY_MIRROR_TIMESTAMP, 0)
        val cached = prefs.getString(KEY_MIRROR_CACHE, null)

        if (cached != null && System.currentTimeMillis() - timestamp <= CACHE_DURATION_MS) {
            return try {
                val arr = org.json.JSONArray(cached)
                (0 until arr.length()).map { arr.getString(it) }
            } catch (e: Exception) {
                getDefaultMirrors()
            }
        }
        return getDefaultMirrors()
    }

    /**
     * 保存镜像延迟排序结果
     */
    fun saveMirrorResults(context: Context, sortedMirrors: List<String>) {
        val arr = org.json.JSONArray(sortedMirrors)
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MIRROR_CACHE, arr.toString())
            .putLong(KEY_MIRROR_TIMESTAMP, System.currentTimeMillis())
            .apply()
    }

    private fun getDefaultMirrors(): List<String> = listOf(
        "",
        "https://ghfast.top/",
        "https://ghproxy.net/",
        "https://github.moeyy.xyz/"
    )
}
