package com.ppgtool.app.data.network

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

data class ReleaseInfo(
    val tagName: String,
    val body: String,
    val apkUrl: String,
    val apkSize: Long
)

data class UpdateState(
    val isChecking: Boolean = false,
    val isDownloading: Boolean = false,
    val progress: Int = 0,
    val progressText: String = "",
    val releaseInfo: ReleaseInfo? = null,
    val showDialog: Boolean = false,
    val showInstallDialog: Boolean = false,
    val error: String? = null
)

@Singleton
class UpdateChecker @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val REPO = "kelven/PPGTool"  // TODO: 替换为实际仓库
        private const val GITHUB_API = "https://api.github.com/repos/$REPO/releases/latest"
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var downloadId: Long = -1
    private var downloadReceiver: BroadcastReceiver? = null
    private var destFile: File? = null
    private var originalDownloadUrl: String = ""
    private var mirrorIndex: Int = 0
    private var downloadTotalSize: Long = 0

    fun getCurrentVersion(): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (e: PackageManager.NameNotFoundException) {
            "unknown"
        }
    }

    fun checkForUpdate(onResult: (ReleaseInfo?) -> Unit) {
        executor.execute {
            // 使用优选排序后的镜像列表
            val apiMirrors = GitHubHostsHelper.getSortedMirrors(context)

            for ((index, mirror) in apiMirrors.withIndex()) {
                try {
                    val apiUrl = "${mirror}${GITHUB_API}"
                    val url = URL(apiUrl)
                    val conn = url.openConnection() as HttpURLConnection
                    conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
                    conn.setRequestProperty("User-Agent", "PPGTool-Android")
                    conn.connectTimeout = 8000
                    conn.readTimeout = 8000

                    if (conn.responseCode == 200) {
                        val json = conn.inputStream.bufferedReader().readText()
                        conn.disconnect()
                        val release = parseReleaseJson(json)
                        if (release != null) {
                            val currentVersion = getCurrentVersion()
                            val remoteVersion = extractVersion(release.tagName)
                            if (isNewerVersion(remoteVersion, currentVersion)) {
                                val optimized = optimizeDownloadUrl(release)
                                mainHandler.post { onResult(optimized) }
                            } else {
                                mainHandler.post { onResult(null) }
                            }
                            return@execute
                        }
                    }
                    conn.disconnect()
                } catch (e: Exception) {
                    // 继续尝试下一个镜像
                }
            }

            // 所有镜像失败，尝试从远程 hosts 获取 GitHub IP 直连
            val release = tryDirectWithRemoteHosts()
            if (release != null) {
                val currentVersion = getCurrentVersion()
                val remoteVersion = extractVersion(release.tagName)
                if (isNewerVersion(remoteVersion, currentVersion)) {
                    val optimized = optimizeDownloadUrl(release)
                    mainHandler.post { onResult(optimized) }
                } else {
                    mainHandler.post { onResult(null) }
                }
                return@execute
            }

            mainHandler.post { onResult(null) }
        }
    }

    private fun parseReleaseJson(json: String): ReleaseInfo? {
        return try {
            val obj = JSONObject(json)
            val tagName = obj.getString("tag_name")
            val body = obj.optString("body", "")

            val assets = obj.getJSONArray("assets")
            var apkUrl = ""
            var apkSize = 0L
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.getString("name").endsWith(".apk")) {
                    apkUrl = asset.getString("browser_download_url")
                    apkSize = asset.optLong("size", 0L)
                    break
                }
            }

            if (apkUrl.isNotEmpty()) ReleaseInfo(tagName, body, apkUrl, apkSize)
            else null
        } catch (e: Exception) {
            null
        }
    }

    private fun extractVersion(tagName: String): String {
        val noV = if (tagName.startsWith("v")) tagName.substring(1) else tagName
        val dashIndex = noV.indexOf('-')
        return if (dashIndex > 0) noV.substring(0, dashIndex) else noV
    }

    private fun isNewerVersion(remote: String, current: String): Boolean {
        val rParts = remote.split(".").mapNotNull { it.toIntOrNull() }
        val cParts = current.split(".").mapNotNull { it.toIntOrNull() }
        val maxLen = maxOf(rParts.size, cParts.size)
        for (i in 0 until maxLen) {
            val r = rParts.getOrElse(i) { 0 }
            val c = cParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }

    private fun optimizeDownloadUrl(release: ReleaseInfo): ReleaseInfo {
        val originalUrl = release.apkUrl
        if (!originalUrl.contains("github.com") && !originalUrl.contains("githubusercontent.com")) {
            return release
        }
        val downloadMirrors = GitHubHostsHelper.getSortedMirrors(context).filter { it.isNotEmpty() }
        val mirrorPrefix = downloadMirrors.firstOrNull() ?: "https://ghfast.top/"
        val proxiedUrl = "$mirrorPrefix$originalUrl"
        return release.copy(apkUrl = proxiedUrl)
    }

    private fun tryDirectWithRemoteHosts(): ReleaseInfo? {
        return try {
            val remoteHosts = GitHubHostsHelper.fetchRemoteHosts(context)
            if (remoteHosts.isEmpty()) return null

            val apiIp = remoteHosts["api.github.com"]
                ?: remoteHosts["github.com"]
                ?: return null

            val latency = GitHubHostsHelper.testIp(apiIp)
            if (latency == Long.MAX_VALUE) return null

            val apiPath = "/repos/$REPO/releases/latest"
            val response = httpsGetWithIp(apiIp, "api.github.com", apiPath)
            if (response != null) parseReleaseJson(response) else null
        } catch (e: Exception) {
            null
        }
    }

    private fun httpsGetWithIp(ip: String, host: String, path: String): String? {
        var socket: javax.net.ssl.SSLSocket? = null
        try {
            // 使用系统默认信任管理器，保留证书验证
            val trustManagerFactory = javax.net.ssl.TrustManagerFactory.getInstance(
                javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()
            )
            trustManagerFactory.init(null as java.security.KeyStore?)

            val sslContext = javax.net.ssl.SSLContext.getInstance("TLS")
            sslContext.init(null, trustManagerFactory.trustManagers, java.security.SecureRandom())
            val factory = sslContext.socketFactory

            socket = factory.createSocket(ip, 443) as javax.net.ssl.SSLSocket
            socket.soTimeout = 8000

            // 启用主机名校验
            val sslParameters = socket.sslParameters
            sslParameters.endpointIdentificationAlgorithm = "HTTPS"
            socket.sslParameters = sslParameters

            socket.startHandshake()

            val request = buildString {
                append("GET $path HTTP/1.1\r\n")
                append("Host: $host\r\n")
                append("Accept: application/vnd.github.v3+json\r\n")
                append("User-Agent: PPGTool-Android\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }

            socket.outputStream.write(request.toByteArray())
            socket.outputStream.flush()

            val reader = socket.inputStream.bufferedReader()
            val statusLine = reader.readLine() ?: return null
            if (!statusLine.contains("200")) return null

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line.isNullOrEmpty()) break
            }

            val body = StringBuilder()
            while (reader.readLine().also { line = it } != null) {
                body.append(line)
            }
            return body.toString().ifEmpty { null }
        } catch (e: Exception) {
            return null
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    fun startDownload(
        apkUrl: String,
        totalSize: Long,
        onProgress: (Int, String) -> Unit,
        onComplete: (File?) -> Unit,
        onError: (String) -> Unit
    ) {
        originalDownloadUrl = apkUrl
        mirrorIndex = 0
        downloadTotalSize = totalSize
        enqueueDownload(apkUrl, totalSize, onProgress, onComplete, onError)
    }

    private fun enqueueDownload(
        apkUrl: String,
        totalSize: Long,
        onProgress: (Int, String) -> Unit,
        onComplete: (File?) -> Unit,
        onError: (String) -> Unit
    ) {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val fileName = "ppgtool-update-${System.currentTimeMillis()}.apk"
        val downloadDir = DownloadHelper.getUpdateDir(context)
        destFile = File(downloadDir, fileName)

        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle("PPGTool 更新")
            .setDescription("正在下载新版本...")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(destFile))
            .setMimeType("application/vnd.android.package-archive")

        downloadId = dm.enqueue(request)

        pollProgress(dm, totalSize, onProgress, onComplete, onError)
        registerDownloadComplete(onComplete)
    }

    private fun retryWithNextMirror(
        onProgress: (Int, String) -> Unit,
        onComplete: (File?) -> Unit,
        onError: (String) -> Unit
    ) {
        mirrorIndex++
        val downloadMirrors = GitHubHostsHelper.getSortedMirrors(context).filter { it.isNotEmpty() }
        if (mirrorIndex < downloadMirrors.size) {
            val nextMirror = downloadMirrors[mirrorIndex]
            val githubPath = originalDownloadUrl
                .replace("https://ghfast.top/", "")
                .replace("https://ghproxy.net/", "")
                .replace("https://github.moeyy.xyz/", "")
            val nextUrl = "$nextMirror$githubPath"
            mainHandler.post { onProgress(0, "下载失败，正在切换镜像源...") }
            mainHandler.postDelayed({
                enqueueDownload(nextUrl, downloadTotalSize, onProgress, onComplete, onError)
            }, 1500)
        } else {
            mainHandler.post { onError("所有下载镜像均失败") }
        }
    }

    private fun pollProgress(
        dm: DownloadManager,
        totalSize: Long,
        onProgress: (Int, String) -> Unit,
        onComplete: (File?) -> Unit,
        onError: (String) -> Unit
    ) {
        val query = DownloadManager.Query().setFilterById(downloadId)
        val runnable = object : Runnable {
            override fun run() {
                val cursor = dm.query(query)
                if (cursor != null && cursor.moveToFirst()) {
                    val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val bytesDownloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val bytesTotal = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    cursor.close()

                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        mainHandler.post { onComplete(destFile) }
                        return
                    }

                    if (status == DownloadManager.STATUS_FAILED) {
                        retryWithNextMirror(onProgress, onComplete, onError)
                        return
                    }

                    val total = if (bytesTotal > 0) bytesTotal else totalSize
                    val progress = if (total > 0) (bytesDownloaded * 100 / total).toInt() else 0
                    val downloadedMB = bytesDownloaded / 1024.0 / 1024.0
                    val totalMB = total / 1024.0 / 1024.0
                    val text = String.format("%.1f MB / %.1f MB (%d%%)", downloadedMB, totalMB, progress)

                    mainHandler.post { onProgress(progress, text) }
                    mainHandler.postDelayed(this, 500)
                } else {
                    cursor?.close()
                    mainHandler.postDelayed(this, 500)
                }
            }
        }
        mainHandler.postDelayed(runnable, 500)
    }

    private fun registerDownloadComplete(onComplete: (File?) -> Unit) {
        downloadReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    try {
                        context.unregisterReceiver(this)
                    } catch (_: Exception) {}
                    downloadReceiver = null
                    mainHandler.post { onComplete(destFile) }
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(
                downloadReceiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            context.registerReceiver(
                downloadReceiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            )
        }
    }

    fun installDownloadedApk(file: File?) {
        if (file != null) {
            DownloadHelper.installApk(context, file)
        }
    }
}
