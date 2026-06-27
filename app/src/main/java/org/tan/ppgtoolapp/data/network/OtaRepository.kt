package org.tan.ppgtoolapp.data.network

import android.content.Context
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for OTA operations - handles data layer logic
 * Extracted from OtaViewModel for proper MVVM separation
 */
@Singleton
class OtaRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpRepository: HttpRepository
) {
    companion object {
        private const val TAG = "OtaRepository"
        private const val PREF_NAME = "ota_settings"
        private const val KEY_FIRMWARE_REPO = "firmware_repo"
        const val DEFAULT_FIRMWARE_REPO = "Tan1347/ESP32-C3_PPG_Data_Collector"
        private const val GITHUB_API_BASE = "https://api.github.com/repos"
        private const val HTTP_CONNECT_TIMEOUT_MS = 15000
        private const val HTTP_READ_TIMEOUT_MS = 30000
    }

    // ========== SharedPreferences ==========

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
    }

    // ========== GitHub Release Fetching ==========

    suspend fun fetchReleases(repo: String): List<ReleaseInfo> = withContext(Dispatchers.IO) {
        val apiMirrors = GitHubHostsHelper.getSortedMirrors(context)

        // Try latest release first
        val latestJson = fetchFromMirror(apiMirrors, "/$GITHUB_API_BASE/$repo/releases/latest")
        if (latestJson != null) {
            val release = parseRelease(latestJson)
            if (release != null) return@withContext listOf(release)
        }

        // Try multiple releases
        val listJson = fetchFromMirror(apiMirrors, "/$GITHUB_API_BASE/$repo/releases?per_page=5")
        if (listJson != null) {
            val releases = parseReleases(listJson)
            if (releases.isNotEmpty()) return@withContext releases
        }

        emptyList()
    }

    private fun fetchFromMirror(mirrors: List<String>, path: String): String? {
        for (mirror in mirrors) {
            try {
                val url = URL("$mirror$path")
                val conn = url.openConnection() as HttpURLConnection
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
                conn.setRequestProperty("User-Agent", "PPGTool-Android")
                conn.connectTimeout = HTTP_CONNECT_TIMEOUT_MS
                conn.readTimeout = HTTP_READ_TIMEOUT_MS
                if (conn.responseCode == 200) {
                    val json = conn.inputStream.bufferedReader().readText()
                    conn.disconnect()
                    return json
                }
                conn.disconnect()
            } catch (_: Exception) { }
        }
        return null
    }

    // ========== JSON Parsing ==========

    fun parseRelease(json: String): ReleaseInfo? {
        return try {
            parseRelease(JSONObject(json))
        } catch (e: Exception) {
            null
        }
    }

    fun parseRelease(obj: JSONObject): ReleaseInfo? {
        return try {
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

    fun parseReleases(json: String): List<ReleaseInfo> {
        return try {
            val arr = JSONArray(json)
            val releases = mutableListOf<ReleaseInfo>()
            for (i in 0 until arr.length()) {
                val release = parseRelease(arr.getJSONObject(i))
                if (release != null) releases.add(release)
            }
            releases
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ========== File Operations ==========

    /**
     * Copy file from URI to local storage
     */
    suspend fun copyFileFromUri(uri: Uri, outputFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                outputFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Copy file failed: ${e.message}")
            false
        }
    }

    /**
     * Get file name from URI
     */
    fun getFileNameFromUri(uri: Uri): String? {
        var name: String? = null
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) {
                name = cursor.getString(nameIndex)
            }
        }
        return name
    }

    /**
     * Extract firmware from 7z file
     */
    suspend fun extractFirmware(sourceFile: File, extractDir: File): File? = withContext(Dispatchers.IO) {
        val result = SevenZipHelper.extractFirmware(sourceFile, extractDir)
        result.firmwareFile
    }

    /**
     * Check if file is a 7z archive
     */
    fun is7zFile(file: File): Boolean = SevenZipHelper.is7zFile(file)

    /**
     * Get download directory
     */
    fun getDownloadDir(): File = DownloadHelper.getUpdateDir(context)

    /**
     * Get GitHub mirrors sorted by speed
     */
    fun getSortedMirrors(): List<String> = GitHubHostsHelper.getSortedMirrors(context)
}
