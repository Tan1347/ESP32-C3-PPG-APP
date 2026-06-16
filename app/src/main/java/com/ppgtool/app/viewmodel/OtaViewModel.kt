package com.ppgtool.app.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ppgtool.app.data.network.*
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class OtaState(
    val deviceVersion: String = "未知",
    val firmwareRepo: String = "",
    val isDownloading: Boolean = false,
    val isExtracting: Boolean = false,
    val isUploading: Boolean = false,
    val progress: Int = 0,
    val progressText: String = "",
    val firmwareFile: File? = null,
    val selectedFileName: String? = null,
    val releaseList: List<ReleaseInfo> = emptyList(),
    val isLoadingReleases: Boolean = false,
    val showReleaseDialog: Boolean = false,
    val showRepoDialog: Boolean = false,
    val result: OtaResult? = null,
    val error: String? = null
)

sealed class OtaResult {
    data class Success(val message: String) : OtaResult()
    data class Error(val message: String) : OtaResult()
}

@HiltViewModel
class OtaViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpRepository: HttpRepository,
    private val updateChecker: UpdateChecker
) : ViewModel() {

    companion object {
        const val DEFAULT_FIRMWARE_REPO = "Tan1347/ESP32-C3_PPG_Data_Collector"
        private const val PREF_NAME = "ota_settings"
        private const val KEY_FIRMWARE_REPO = "firmware_repo"
        private const val GITHUB_API_BASE = "https://api.github.com/repos"
    }

    private val _state = MutableStateFlow(OtaState())
    val state: StateFlow<OtaState> = _state.asStateFlow()

    private var extractedDir: File? = null

    init {
        val repo = getFirmwareRepo()
        _state.update { it.copy(firmwareRepo = repo) }
    }

    fun getFirmwareRepo(): String {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_FIRMWARE_REPO, DEFAULT_FIRMWARE_REPO) ?: DEFAULT_FIRMWARE_REPO
    }

    fun saveFirmwareRepo(repo: String) {
        val trimmed = repo.trim()
        if (trimmed.isBlank()) return
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_FIRMWARE_REPO, trimmed)
            .apply()
        _state.update { it.copy(firmwareRepo = trimmed) }
    }

    fun showRepoDialog() {
        _state.update { it.copy(showRepoDialog = true) }
    }

    fun dismissRepoDialog() {
        _state.update { it.copy(showRepoDialog = false) }
    }

    fun loadDeviceStatus() {
        viewModelScope.launch {
            val status = httpRepository.getDeviceStatus()
            if (status != null) {
                _state.update { it.copy(deviceVersion = status.version) }
            }
        }
    }

    fun loadReleases() {
        if (_state.value.isLoadingReleases) return

        _state.update { it.copy(isLoadingReleases = true, error = null) }

        viewModelScope.launch {
            try {
                val releases = fetchReleases()
                _state.update {
                    it.copy(
                        isLoadingReleases = false,
                        releaseList = releases,
                        showReleaseDialog = releases.isNotEmpty()
                    )
                }
                if (releases.isEmpty()) {
                    _state.update { it.copy(error = "未找到包含固件的 Release") }
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(isLoadingReleases = false, error = "获取 Release 失败: ${e.message}")
                }
            }
        }
    }

    private suspend fun fetchReleases(): List<ReleaseInfo> = withContext(Dispatchers.IO) {
        val repo = _state.value.firmwareRepo.ifBlank { DEFAULT_FIRMWARE_REPO }
        val githubApi = "$GITHUB_API_BASE/$repo/releases"
        val apiMirrors = GitHubHostsHelper.getSortedMirrors(context)

        for (mirror in apiMirrors) {
            try {
                val apiUrl = "${mirror}${githubApi}/latest"
                val url = java.net.URL(apiUrl)
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
                conn.setRequestProperty("User-Agent", "PPGTool-Android")
                conn.connectTimeout = 8000
                conn.readTimeout = 8000

                if (conn.responseCode == 200) {
                    val json = conn.inputStream.bufferedReader().readText()
                    conn.disconnect()
                    val release = parseRelease(json)
                    if (release != null) return@withContext listOf(release)
                }
                conn.disconnect()
            } catch (e: Exception) {
                // 继续尝试下一个镜像
            }
        }

        // 尝试获取多个 releases
        for (mirror in apiMirrors) {
            try {
                val apiUrl = "${mirror}${githubApi}?per_page=5"
                val url = java.net.URL(apiUrl)
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
                conn.setRequestProperty("User-Agent", "PPGTool-Android")
                conn.connectTimeout = 8000
                conn.readTimeout = 8000

                if (conn.responseCode == 200) {
                    val json = conn.inputStream.bufferedReader().readText()
                    conn.disconnect()
                    val releases = parseReleases(json)
                    if (releases.isNotEmpty()) return@withContext releases
                }
                conn.disconnect()
            } catch (e: Exception) {
                // 继续尝试
            }
        }

        emptyList()
    }

    private fun parseRelease(json: String): ReleaseInfo? {
        return try {
            val obj = org.json.JSONObject(json)
            val tagName = obj.getString("tag_name")
            val body = obj.optString("body", "")
            val assets = obj.getJSONArray("assets")

            var sevenZipUrl = ""
            var sevenZipSize = 0L
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.getString("name")
                if (name.endsWith(".7z", ignoreCase = true)) {
                    sevenZipUrl = asset.getString("browser_download_url")
                    sevenZipSize = asset.optLong("size", 0L)
                    break
                }
            }

            if (sevenZipUrl.isNotEmpty()) {
                ReleaseInfo(tagName, body, sevenZipUrl, sevenZipSize)
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private fun parseReleases(json: String): List<ReleaseInfo> {
        return try {
            val arr = org.json.JSONArray(json)
            val releases = mutableListOf<ReleaseInfo>()
            for (i in 0 until arr.length()) {
                val release = parseRelease(arr.getJSONObject(i).toString())
                if (release != null) releases.add(release)
            }
            releases
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun downloadAndExtract(release: ReleaseInfo) {
        _state.update {
            it.copy(
                showReleaseDialog = false,
                isDownloading = true,
                progress = 0,
                progressText = "准备下载...",
                error = null,
                firmwareFile = null
            )
        }

        viewModelScope.launch {
            try {
                // 优化下载链接
                val downloadMirrors = GitHubHostsHelper.getSortedMirrors(context).filter { it.isNotEmpty() }
                val mirrorPrefix = downloadMirrors.firstOrNull() ?: "https://ghfast.top/"
                val downloadUrl = "$mirrorPrefix${release.apkUrl}"

                // 下载 7z 文件
                val downloadDir = DownloadHelper.getUpdateDir(context)
                val sevenZipFile = File(downloadDir, "firmware-${release.tagName}.7z")

                val success = httpRepository.downloadFromGitHub(
                    url = downloadUrl,
                    outputFile = sevenZipFile,
                    onProgress = { percent ->
                        _state.update { it.copy(progress = percent, progressText = "下载中... $percent%") }
                    }
                )

                if (!success) {
                    _state.update { it.copy(isDownloading = false, error = "下载失败") }
                    return@launch
                }

                // 解压 7z
                _state.update { it.copy(isDownloading = false, isExtracting = true, progressText = "正在解压...") }

                val extractDir = File(downloadDir, "extract-${System.currentTimeMillis()}")
                val result = withContext(Dispatchers.IO) {
                    SevenZipHelper.extractFirmware(sevenZipFile, extractDir)
                }

                extractedDir = extractDir

                if (result.firmwareFile != null) {
                    _state.update {
                        it.copy(
                            isExtracting = false,
                            firmwareFile = result.firmwareFile,
                            selectedFileName = result.firmwareFile.name,
                            progressText = "解压完成，找到固件: ${result.firmwareFile.name}"
                        )
                    }
                } else {
                    _state.update {
                        it.copy(
                            isExtracting = false,
                            error = "7z 包中未找到 .bin 固件文件，解压内容: ${result.extractedFiles.joinToString(", ")}"
                        )
                    }
                }

                // 清理 7z 文件
                sevenZipFile.delete()

            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isDownloading = false,
                        isExtracting = false,
                        error = "处理失败: ${e.message}"
                    )
                }
            }
        }
    }

    fun selectLocalFile(uri: Uri) {
        viewModelScope.launch {
            try {
                _state.update { it.copy(isExtracting = true, progressText = "正在处理文件...", error = null) }

                // 复制到临时目录
                val tempDir = DownloadHelper.getUpdateDir(context)
                val tempFile = File(tempDir, "local-firmware.7z")

                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }

                // 检查是否为 7z 文件
                if (!SevenZipHelper.is7zFile(tempFile)) {
                    // 可能是 bin 文件
                    val fileName = getFileNameFromUri(uri)
                    if (fileName != null && fileName.endsWith(".bin", ignoreCase = true)) {
                        val binFile = File(tempDir, fileName)
                        tempFile.renameTo(binFile)
                        _state.update {
                            it.copy(
                                isExtracting = false,
                                firmwareFile = binFile,
                                selectedFileName = fileName
                            )
                        }
                        return@launch
                    }

                    tempFile.delete()
                    _state.update { it.copy(isExtracting = false, error = "不支持的文件格式，请选择 .7z 或 .bin 文件") }
                    return@launch
                }

                // 解压 7z
                val extractDir = File(tempDir, "extract-local-${System.currentTimeMillis()}")
                val result = withContext(Dispatchers.IO) {
                    SevenZipHelper.extractFirmware(tempFile, extractDir)
                }

                extractedDir = extractDir
                tempFile.delete()

                if (result.firmwareFile != null) {
                    _state.update {
                        it.copy(
                            isExtracting = false,
                            firmwareFile = result.firmwareFile,
                            selectedFileName = result.firmwareFile.name
                        )
                    }
                } else {
                    _state.update {
                        it.copy(
                            isExtracting = false,
                            error = "7z 包中未找到 .bin 固件文件"
                        )
                    }
                }

            } catch (e: Exception) {
                _state.update {
                    it.copy(isExtracting = false, error = "文件处理失败: ${e.message}")
                }
            }
        }
    }

    private fun getFileNameFromUri(uri: Uri): String? {
        var name: String? = null
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) {
                name = cursor.getString(nameIndex)
            }
        }
        return name
    }

    fun startOta() {
        val firmwareFile = _state.value.firmwareFile ?: return

        _state.update {
            it.copy(isUploading = true, progress = 0, progressText = "准备上传...", error = null, result = null)
        }

        viewModelScope.launch {
            try {
                val success = httpRepository.uploadFirmware(firmwareFile) { progress ->
                    _state.update { it.copy(progress = progress, progressText = "上传中... $progress%") }
                }

                if (success) {
                    _state.update {
                        it.copy(
                            isUploading = false,
                            result = OtaResult.Success("固件上传成功，设备将自动重启"),
                            progressText = "上传完成"
                        )
                    }
                } else {
                    _state.update {
                        it.copy(
                            isUploading = false,
                            result = OtaResult.Error("固件上传失败")
                        )
                    }
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isUploading = false,
                        result = OtaResult.Error("上传异常: ${e.message}")
                    )
                }
            }
        }
    }

    fun dismissReleaseDialog() {
        _state.update { it.copy(showReleaseDialog = false) }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun clearResult() {
        _state.update { it.copy(result = null) }
    }

    fun cleanup() {
        extractedDir?.deleteRecursively()
        extractedDir = null
    }
}
