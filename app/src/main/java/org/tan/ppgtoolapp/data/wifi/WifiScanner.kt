package org.tan.ppgtoolapp.data.wifi

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

data class WifiNetwork(
    val ssid: String,
    val rssi: Int,
    val frequency: Int,
    val capabilities: String,
    val is24GHz: Boolean = frequency in 2400..2500
) {
    val signalLevel: Int
        get() = when {
            rssi >= -50 -> 4  // 强
            rssi >= -60 -> 3  // 较强
            rssi >= -70 -> 2  // 中等
            rssi >= -80 -> 1  // 较弱
            else -> 0          // 弱
        }

    val isSecure: Boolean
        get() = capabilities.isNotEmpty() &&
                (!capabilities.contains("OWE-only") || capabilities.contains("WPA") || capabilities.contains("WEP"))
}

@Singleton
class WifiScanner @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager

    @SuppressLint("MissingPermission")
    fun scan24GHz(): Flow<List<WifiNetwork>> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                    val results = getScannedNetworks()
                    trySend(results)
                }
            }
        }

        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }

        // 触发扫描
        @Suppress("DEPRECATION")
        wifiManager.startScan()

        // 也发送已有的缓存结果
        val cachedResults = getScannedNetworks()
        if (cachedResults.isNotEmpty()) {
            trySend(cachedResults)
        }

        awaitClose {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {}
        }
    }

    @SuppressLint("MissingPermission", "Deprecation")
    private fun getScannedNetworks(): List<WifiNetwork> {
        return try {
            wifiManager.scanResults
                .filter { it.frequency in 2400..2500 }  // 只要 2.4GHz
                .filter { getSsid(it).isNotBlank() }     // 过滤隐藏网络
                .distinctBy { getSsid(it) }              // 去重
                .map { result ->
                    WifiNetwork(
                        ssid = getSsid(result),
                        rssi = result.level,
                        frequency = result.frequency,
                        capabilities = result.capabilities
                    )
                }
                .sortedByDescending { it.rssi }  // 按信号强度排序
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Suppress("Deprecation")
    private fun getSsid(result: ScanResult): String {
        val ssid = if (android.os.Build.VERSION.SDK_INT >= 33) {
            result.wifiSsid?.toString() ?: result.SSID ?: ""
        } else {
            result.SSID ?: ""
        }
        // 移除可能存在的引号
        return ssid.removeSurrounding("\"")
    }

    fun isWifiEnabled(): Boolean = wifiManager.isWifiEnabled

    /**
     * 请求开启 WiFi
     * Android 10+ 使用系统面板，以下版本直接开启
     * @return true 如果可以直接开启，false 需要用户手动操作
     */
    @Suppress("DEPRECATION")
    fun requestEnableWifi(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ 无法直接开启，返回 false 让调用方打开系统设置面板
            false
        } else {
            // Android 10 以下可以直接开启
            wifiManager.isWifiEnabled = true
            true
        }
    }
}
