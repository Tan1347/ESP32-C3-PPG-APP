package org.tan.ppgtoolapp.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.tan.ppgtoolapp.data.ble.BleCommandProvider
import org.tan.ppgtoolapp.data.ble.BleConnectionProvider
import org.tan.ppgtoolapp.data.ble.PpgGattProfile
import org.tan.ppgtoolapp.data.network.ApiResult
import org.tan.ppgtoolapp.data.network.DeviceHttpApi
import org.tan.ppgtoolapp.data.wifi.WifiNetwork
import org.tan.ppgtoolapp.data.wifi.WifiScanProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets
import javax.inject.Inject

private const val TAG = "WifiProvisionVM"

data class WifiProvisionState(
    val isScanning: Boolean = false,
    val networks: List<WifiNetwork> = emptyList(),
    val selectedNetwork: WifiNetwork? = null,
    val password: String = "",
    val isConnecting: Boolean = false,
    val connectionResult: ConnectionResult? = null,
    val showEnableWifiDialog: Boolean = false,
    val showManualAddDialog: Boolean = false,
    val manualSsid: String = "",
    val manualPassword: String = "",
    val error: String? = null,
    // Device WiFi status
    val deviceWifiConnected: Boolean = false,
    val deviceWifiIp: String = "",
    val deviceSavedNetworks: List<DeviceWifiNetwork> = emptyList(),
    val isQueryingDeviceWifi: Boolean = false
)

data class DeviceWifiNetwork(
    val ssid: String,
    val isConnected: Boolean = false,
    val hasPassword: Boolean = true,
    val priority: Int = 0,
    val ip: String = ""  // Only populated for connected WiFi
)

sealed class ConnectionResult {
    data class Success(val message: String) : ConnectionResult()
    data class Error(val message: String) : ConnectionResult()
}

@HiltViewModel
class WifiProvisionViewModel @Inject constructor(
    private val wifiScanner: WifiScanProvider,
    private val bleCommander: BleCommandProvider,
    private val bleConnection: BleConnectionProvider,
    private val httpRepository: DeviceHttpApi
) : ViewModel() {

    private val _state = MutableStateFlow(WifiProvisionState())
    val state: StateFlow<WifiProvisionState> = _state.asStateFlow()

    fun scanNetworks() {
        if (_state.value.isScanning) return

        _state.update { it.copy(isScanning = true, error = null) }

        if (!wifiScanner.isWifiEnabled()) {
            // 尝试直接开启 WiFi
            if (wifiScanner.requestEnableWifi()) {
                // 成功开启，继续扫描
            } else {
                // Android 10+ 需要用户手动开启，显示提示
                _state.update { it.copy(isScanning = false, showEnableWifiDialog = true) }
                return
            }
        }

        viewModelScope.launch {
            try {
                wifiScanner.scan24GHz().collect { networks ->
                    _state.update {
                        it.copy(
                            isScanning = false,
                            networks = networks
                        )
                    }
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(isScanning = false, error = "扫描失败: ${e.message}")
                }
            }
        }
    }

    fun dismissEnableWifiDialog() {
        _state.update { it.copy(showEnableWifiDialog = false) }
    }

    fun selectNetwork(network: WifiNetwork) {
        _state.update {
            it.copy(
                selectedNetwork = network,
                password = "",
                connectionResult = null
            )
        }
    }

    fun updatePassword(password: String) {
        _state.update { it.copy(password = password) }
    }

    fun dismissSelection() {
        _state.update { it.copy(selectedNetwork = null, password = "") }
    }

    /**
     * Build BLE command frame for Wi-Fi credentials
     * Format: [CMD_WIFI_ADD][ssid_len_h][ssid_len_l][ssid...][pwd_len_h][pwd_len_l][pwd]
     * Note: Frame header, length, and checksum are added by BleCommander.writeCommand()
     */
    private fun buildWifiCommand(ssid: String, password: String): ByteArray {
        val ssidBytes = ssid.toByteArray(StandardCharsets.UTF_8)
        val pwdBytes = password.toByteArray(StandardCharsets.UTF_8)
        val command = ByteArray(1 + 2 + ssidBytes.size + 2 + pwdBytes.size)
        var offset = 0

        command[offset++] = PpgGattProfile.CMD_WIFI_ADD
        command[offset++] = (ssidBytes.size shr 8).toByte()
        command[offset++] = (ssidBytes.size and 0xFF).toByte()
        ssidBytes.copyInto(command, offset)
        offset += ssidBytes.size
        command[offset++] = (pwdBytes.size shr 8).toByte()
        command[offset++] = (pwdBytes.size and 0xFF).toByte()
        pwdBytes.copyInto(command, offset)

        Log.i(TAG, "WiFi CMD: SSID=\"$ssid\" pwd_len=${password.length} frame=${command.size}B")
        return command
    }

    fun sendWifiCredentials() {
        val network = _state.value.selectedNetwork ?: return
        val password = _state.value.password

        _state.update { it.copy(isConnecting = true, connectionResult = null) }

        viewModelScope.launch {
            try {
                val command = buildWifiCommand(network.ssid, password)
                val success = bleCommander.writeCommand(command)

                Log.i(TAG, "BLE 写入结果: $success")

                if (success) {
                    _state.update {
                        it.copy(connectionResult = ConnectionResult.Success("WiFi 凭据已发送，等待连接..."))
                    }

                    // Poll Status characteristic to check WiFi connected (byte[4])
                    val connected = withTimeoutOrNull(30000L) {
                        for (i in 1..15) {
                            delay(2000)
                            val data = bleCommander.readCharacteristic(PpgGattProfile.CHAR_STATUS)
                            if (data != null && data.size >= 5 && data[4].toInt() and 0xFF == 1) {
                                return@withTimeoutOrNull true
                            }
                            Log.d(TAG, "WiFi poll #$i: connected=${data?.get(4)?.toInt()?.and(0xFF)}")
                        }
                        false
                    }

                    if (connected == true) {
                        // WiFi connected, try to get IP via HTTP
                        val ip = when (val result = httpRepository.getDeviceStatus()) {
                            is ApiResult.Success -> result.data.ip
                            is ApiResult.Error -> null
                        }

                        _state.update {
                            it.copy(
                                isConnecting = false,
                                connectionResult = ConnectionResult.Success(
                                    if (ip != null) "WiFi 已连接\nIP: $ip"
                                    else "WiFi 已连接"
                                ),
                                selectedNetwork = null,
                                password = ""
                            )
                        }
                    } else {
                        _state.update {
                            it.copy(
                                isConnecting = false,
                                connectionResult = ConnectionResult.Error("WiFi 连接超时，请检查密码"),
                                selectedNetwork = null,
                                password = ""
                            )
                        }
                    }
                } else {
                    _state.update {
                        it.copy(
                            isConnecting = false,
                            connectionResult = ConnectionResult.Error("发送失败，请检查蓝牙连接")
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "发送异常: ${e.message}", e)
                _state.update {
                    it.copy(
                        isConnecting = false,
                        connectionResult = ConnectionResult.Error("发送异常: ${e.message}")
                    )
                }
            }
        }
    }

    // 手动添加 WiFi 相关方法
    fun showManualAddDialog() {
        _state.update { it.copy(showManualAddDialog = true, manualSsid = "", manualPassword = "") }
    }

    fun dismissManualAddDialog() {
        _state.update { it.copy(showManualAddDialog = false) }
    }

    fun updateManualSsid(ssid: String) {
        _state.update { it.copy(manualSsid = ssid) }
    }

    fun updateManualPassword(password: String) {
        _state.update { it.copy(manualPassword = password) }
    }

    fun sendManualWifiCredentials() {
        val ssid = _state.value.manualSsid
        val password = _state.value.manualPassword

        if (ssid.isBlank()) return

        _state.update { it.copy(isConnecting = true, connectionResult = null) }

        viewModelScope.launch {
            try {
                val command = buildWifiCommand(ssid, password)
                val success = bleCommander.writeCommand(command)

                Log.i(TAG, "BLE 写入结果: $success")

                if (success) {
                    _state.update {
                        it.copy(
                            isConnecting = false,
                            showManualAddDialog = false,
                            connectionResult = ConnectionResult.Success("WiFi 凭据已发送，设备将尝试连接"),
                            manualSsid = "",
                            manualPassword = ""
                        )
                    }
                } else {
                    _state.update {
                        it.copy(
                            isConnecting = false,
                            connectionResult = ConnectionResult.Error("发送失败，请检查蓝牙连接")
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "发送异常: ${e.message}", e)
                _state.update {
                    it.copy(
                        isConnecting = false,
                        connectionResult = ConnectionResult.Error("发送异常: ${e.message}")
                    )
                }
            }
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun clearResult() {
        _state.update { it.copy(connectionResult = null) }
    }

    /**
     * Query device WiFi status - one by one
     * Firmware sends data via Command characteristic (0xFFF3) notifications
     * ACK frames start with 0xAA, data frames are JSON (start with '{')
     */
    fun queryDeviceWifiStatus() {
        if (!bleConnection.isConnected()) return

        _state.update { it.copy(isQueryingDeviceWifi = true, deviceSavedNetworks = emptyList()) }

        viewModelScope.launch {
            try {
                // Step 1: Get WiFi count from list query
                val listSuccess = bleCommander.writeCommand(byteArrayOf(PpgGattProfile.CMD_WIFI_LIST))
                if (!listSuccess) {
                    _state.update { it.copy(isQueryingDeviceWifi = false, error = "查询失败") }
                    return@launch
                }

                // Wait for WiFi list data via Command notification (0xFFF3)
                // Skip ACK frames (start with 0xAA) and wait for JSON data
                val listData = withTimeoutOrNull(3000L) {
                    bleCommander.cmdResponse.first { it[0] != 0xAA.toByte() }
                }

                if (listData == null) {
                    _state.update { it.copy(isQueryingDeviceWifi = false, error = "读取超时") }
                    return@launch
                }

                val listJson = String(listData, Charsets.UTF_8)
                Log.d(TAG, "WiFi list: $listJson")

                val listObj = org.json.JSONObject(listJson)
                val count = listObj.optInt("count", 0)

                if (count == 0) {
                    _state.update { it.copy(isQueryingDeviceWifi = false) }
                    return@launch
                }

                // Step 2: Query each WiFi details one by one
                val networks = mutableListOf<DeviceWifiNetwork>()
                for (i in 0 until count) {
                    val detailSuccess = bleCommander.writeCommand(
                        byteArrayOf(PpgGattProfile.CMD_WIFI_DETAIL, i.toByte())
                    )
                    if (!detailSuccess) break

                    delay(200)

                    val detailData = withTimeoutOrNull(2000L) {
                        bleCommander.cmdResponse.first { it[0] != 0xAA.toByte() }
                    }

                    if (detailData != null) {
                        val detailJson = String(detailData, Charsets.UTF_8)
                        Log.d(TAG, "WiFi[$i]: $detailJson")
                        val detailObj = org.json.JSONObject(detailJson)

                        val ssid = detailObj.optString("ssid", "")
                        val connected = detailObj.optBoolean("connected", false)
                        val ip = detailObj.optString("ip", "")

                        networks.add(
                            DeviceWifiNetwork(
                                ssid = ssid,
                                isConnected = connected,
                                hasPassword = detailObj.optBoolean("has_pass", false),
                                priority = detailObj.optInt("priority", 0),
                                ip = if (connected) ip else ""
                            )
                        )
                        _state.update { it.copy(deviceSavedNetworks = networks.toList()) }
                    }

                    delay(100)
                }

                // Set global connection status from the last connected WiFi
                val connectedNetwork = networks.firstOrNull { it.isConnected }
                _state.update {
                    it.copy(
                        deviceWifiConnected = connectedNetwork != null,
                        deviceWifiIp = connectedNetwork?.ip ?: ""
                    )
                }

            } catch (e: Exception) {
                Log.e(TAG, "Query device WiFi failed: ${e.message}")
            } finally {
                _state.update { it.copy(isQueryingDeviceWifi = false) }
            }
        }
    }

    private fun parseDeviceWifiStatus(json: String) {
        try {
            val obj = org.json.JSONObject(json)
            val connected = obj.optBoolean("connected", false)
            val ip = obj.optString("ip", "")
            val list = obj.optJSONArray("list")

            val networks = mutableListOf<DeviceWifiNetwork>()
            if (list != null) {
                for (i in 0 until list.length()) {
                    val item = list.getJSONObject(i)
                    networks.add(
                        DeviceWifiNetwork(
                            ssid = item.optString("ssid", ""),
                            hasPassword = item.optBoolean("has_pass", false),
                            priority = item.optInt("priority", 0)
                        )
                    )
                }
            }

            _state.update {
                it.copy(
                    deviceWifiConnected = connected,
                    deviceWifiIp = ip,
                    deviceSavedNetworks = networks
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Parse WiFi status failed: ${e.message}")
        }
    }

    /**
     * Trigger device to connect to WiFi using saved credentials
     */
    fun triggerDeviceWifiConnect() {
        if (!bleConnection.isConnected()) return

        viewModelScope.launch {
            try {
                val success = bleCommander.writeCommand(byteArrayOf(PpgGattProfile.CMD_START_WIFI))
                if (success) {
                    _state.update {
                        it.copy(connectionResult = ConnectionResult.Success("已触发设备连接 WiFi..."))
                    }
                    // Wait a bit then query status
                    delay(3000)
                    queryDeviceWifiStatus()
                } else {
                    _state.update {
                        it.copy(connectionResult = ConnectionResult.Error("发送失败"))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Trigger WiFi connect failed: ${e.message}")
            }
        }
    }
}
