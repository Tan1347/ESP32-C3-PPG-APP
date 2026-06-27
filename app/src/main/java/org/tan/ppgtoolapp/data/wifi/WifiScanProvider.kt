package org.tan.ppgtoolapp.data.wifi

import kotlinx.coroutines.flow.Flow

/**
 * WiFi 网络扫描接口
 */
interface WifiScanProvider {
    fun scan24GHz(): Flow<List<WifiNetwork>>
    fun isWifiEnabled(): Boolean
    fun requestEnableWifi(): Boolean
}
