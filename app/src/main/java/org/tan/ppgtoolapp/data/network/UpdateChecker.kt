package org.tan.ppgtoolapp.data.network

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
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "UpdateChecker"

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
        private const val REPO = "Tan1347/ESP32-C3-PPG-APP"
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
            Log.i(TAG, "开始检查更新")
            Log.i(TAG, "当前版本: ${getCurrentVersion()}")
            Log.i(TAG, "检查仓库: $REPO")

            val client = AppHttpClient.get(context)

            // 使用优选排序后的镜像列表
            val apiMirrors = GitHubHostsHelper.getSortedMirrors(context)
            Log.d(TAG, "镜像列表: $apiMirrors")

            for ((index, mirror) in apiMirrors.withIndex()) {
                try {
                    val apiUrl = "${mirror}${GITHUB_API}"
                    Log.d(TAG, "尝试镜像 $index: $apiUrl")

                    val request = Request.Builder()
                        .url(apiUrl)
                        .header("Accept", "application/vnd.github.v3+json")
                        .header("User-Agent", "PPGTool-Android")
                        .build()

                    val response = client.newCall(request).execute()
                    if (response.isSuccessful) {
                        val json = response.body?.string() ?: ""
                        response.close()
                        val release = parseReleaseJson(json)
                        if (release != null) {
                            val currentVersion = getCurrentVersion()
                            val remoteVersion = extractVersion(release.tagName)
                            Log.i(TAG, "远程版本: $remoteVersion, 当前版本: $currentVersion")
                            if (isNewerVersion(remoteVersion, currentVersion)) {
                                Log.i(TAG, "发现新版本: ${release.tagName}")
                                val optimized = optimizeDownloadUrl(release)
                                mainHandler.post { onResult(optimized) }
                            } else {
                                Log.i(TAG, "已是最新版本")
                                mainHandler.post { onResult(null) }
                            }
                            return@execute
                        }
                    } else {
                        Log.w(TAG, "镜像 $index 响应: ${response.code}")
                    }
                    response.close()
                } catch (e: Exception) {
                    Log.e(TAG, "镜像 $index 失败: ${e.message}")
                }
            }

            // 所有镜像失败，尝试优选 DNS 直连 GitHub
            Log.w(TAG, "所有镜像失败，尝试优选 DNS 直连 GitHub...")
            val release = tryDirectWithOptimizedDns(client)
            if (release != null) {
                val currentVersion = getCurrentVersion()
                val remoteVersion = extractVersion(release.tagName)
                Log.i(TAG, "优选 DNS 直连成功，远程版本: $remoteVersion")
                if (isNewerVersion(remoteVersion, currentVersion)) {
                    val optimized = optimizeDownloadUrl(release)
                    mainHandler.post { onResult(optimized) }
                } else {
                    mainHandler.post { onResult(null) }
                }
                return@execute
            }

            Log.e(TAG, "所有更新检查方式均失败")
            mainHandler.post { onResult(null) }
        }
    }

    /**
     * 通过 OkHttp 优选 DNS 直连 GitHub API
     */
    private fun tryDirectWithOptimizedDns(client: okhttp3.OkHttpClient): ReleaseInfo? {
        return try {
            // 确保远程 hosts 已缓存
            GitHubHostsHelper.fetchRemoteHosts(context)

            val request = Request.Builder()
                .url(GITHUB_API)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "PPGTool-Android")
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val json = response.body?.string() ?: ""
                response.close()
                Log.i(TAG, "优选 DNS 直连成功")
                parseReleaseJson(json)
            } else {
                Log.w(TAG, "优选 DNS 直连返回: ${response.code}")
                response.close()
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "优选 DNS 直连失败: ${e.message}")
            null
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
        Log.d(TAG, "下载链接代理: $proxiedUrl")
        return release.copy(apkUrl = proxiedUrl)
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
            Log.w(TAG, "下载失败，切换到镜像源 $mirrorIndex: $nextUrl")
            mainHandler.post { onProgress(0, "下载失败，正在切换镜像源...") }
            mainHandler.postDelayed({
                enqueueDownload(nextUrl, downloadTotalSize, onProgress, onComplete, onError)
            }, 1500)
        } else {
            Log.e(TAG, "所有下载镜像均失败")
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
                    Log.i(TAG, "更新下载完成")
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
