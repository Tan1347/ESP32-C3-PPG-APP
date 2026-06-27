package org.tan.ppgtoolapp.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.tan.ppgtoolapp.data.ble.BleManager
import org.tan.ppgtoolapp.data.ble.PpgGattProfile
import org.tan.ppgtoolapp.data.network.HttpRepository
import org.tan.ppgtoolapp.data.wifi.WifiNetwork
import org.tan.ppgtoolapp.data.wifi.WifiScanner
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
    val error: String? = null
)

sealed class ConnectionResult {
    data class Success(val message: String) : ConnectionResult()
    data class Error(val message: String) : ConnectionResult()
}

@HiltViewModel
class WifiProvisionViewModel @Inject constructor(
    private val wifiScanner: WifiScanner,
    private val bleManager: BleManager,
    private val httpRepository: HttpRepository
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
     * Note: Frame checksum is added by BleManager.buildFrame()
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
                val success = bleManager.writeCommand(command)

                Log.i(TAG, "BLE 写入结果: $success")

                if (success) {
                    _state.update {
                        it.copy(connectionResult = ConnectionResult.Success("WiFi 凭据已发送，等待连接..."))
                    }

                    // Poll Status characteristic to check WiFi connected (byte[4])
                    val connected = withTimeoutOrNull(30000L) {
                        for (i in 1..15) {
                            delay(2000)
                            val data = bleManager.readCharacteristic(PpgGattProfile.CHAR_STATUS)
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
                val success = bleManager.writeCommand(command)

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
}
