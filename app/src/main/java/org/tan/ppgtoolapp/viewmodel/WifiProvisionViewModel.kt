package org.tan.ppgtoolapp.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.util.Log
import org.tan.ppgtoolapp.data.ble.BleManager
import org.tan.ppgtoolapp.data.ble.PpgGattProfile
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
    private val bleManager: BleManager
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

    fun sendWifiCredentials() {
        val network = _state.value.selectedNetwork ?: return
        val password = _state.value.password

        _state.update { it.copy(isConnecting = true, connectionResult = null) }

        viewModelScope.launch {
            try {
                // 构造 WiFi 凭据命令
                // 格式: [CMD_WIFI_ADD][ssid_len_h][ssid_len_l][ssid...][pwd_len_h][pwd_len_l][pwd...][checksum]
                val ssidBytes = network.ssid.toByteArray(StandardCharsets.UTF_8)
                val pwdBytes = password.toByteArray(StandardCharsets.UTF_8)

                // 计算帧长度：命令(1) + SSID长度(2) + SSID + 密码长度(2) + 密码 + 校验码(1)
                val frameLength = 1 + 2 + ssidBytes.size + 2 + pwdBytes.size + 1
                val command = ByteArray(frameLength)
                var offset = 0

                // 命令 ID
                command[offset++] = PpgGattProfile.CMD_WIFI_ADD

                // SSID 长度 (big-endian)
                command[offset++] = (ssidBytes.size shr 8).toByte()
                command[offset++] = (ssidBytes.size and 0xFF).toByte()

                // SSID
                ssidBytes.copyInto(command, offset)
                offset += ssidBytes.size

                // 密码长度 (big-endian)
                command[offset++] = (pwdBytes.size shr 8).toByte()
                command[offset++] = (pwdBytes.size and 0xFF).toByte()

                // 密码
                pwdBytes.copyInto(command, offset)
                offset += pwdBytes.size

                // Note: Frame checksum is handled by BleManager.buildFrame() using SUM

                // ====== BLE frame debug log ======
                Log.i(TAG, "========================================")
                Log.i(TAG, "BLE WiFi Credential Frame")
                Log.i(TAG, "========================================")
                Log.i(TAG, "SSID: \"${network.ssid}\"")
                Log.i(TAG, "Password: \"${password}\"")
                Log.i(TAG, "----------------------------------------")
                Log.i(TAG, "Frame structure:")
                Log.i(TAG, "  CMD:        0x%02X (CMD_WIFI_ADD)".format(command[0]))
                Log.i(TAG, "  SSID_LEN:   ${ssidBytes.size} (0x%02X 0x%02X)".format(command[1], command[2]))
                Log.i(TAG, "  SSID_DATA:  ${ssidBytes.joinToString(" ") { "%02X".format(it) }} (\"${network.ssid}\")")
                val pwdLenOffset = 3 + ssidBytes.size
                Log.i(TAG, "  PWD_LEN:    ${pwdBytes.size} (0x%02X 0x%02X)".format(command[pwdLenOffset], command[pwdLenOffset + 1]))
                Log.i(TAG, "  PWD_DATA:   ${pwdBytes.joinToString(" ") { "%02X".format(it) }}")
                Log.i(TAG, "----------------------------------------")
                Log.i(TAG, "Raw data(${command.size} bytes): ${command.joinToString(" ") { "%02X".format(it) }}")
                Log.i(TAG, "========================================")

                val success = bleManager.writeCommand(command)

                Log.i(TAG, "BLE 写入结果: $success")

                if (success) {
                    _state.update {
                        it.copy(
                            isConnecting = false,
                            connectionResult = ConnectionResult.Success("WiFi 凭据已发送，设备将尝试连接"),
                            selectedNetwork = null,
                            password = ""
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
                // 构造 WiFi 凭据命令
                val ssidBytes = ssid.toByteArray(StandardCharsets.UTF_8)
                val pwdBytes = password.toByteArray(StandardCharsets.UTF_8)

                // 计算帧长度：命令(1) + SSID长度(2) + SSID + 密码长度(2) + 密码 + 校验码(1)
                val frameLength = 1 + 2 + ssidBytes.size + 2 + pwdBytes.size + 1
                val command = ByteArray(frameLength)
                var offset = 0

                // 命令 ID
                command[offset++] = PpgGattProfile.CMD_WIFI_ADD

                // SSID 长度 (big-endian)
                command[offset++] = (ssidBytes.size shr 8).toByte()
                command[offset++] = (ssidBytes.size and 0xFF).toByte()

                // SSID
                ssidBytes.copyInto(command, offset)
                offset += ssidBytes.size

                // 密码长度 (big-endian)
                command[offset++] = (pwdBytes.size shr 8).toByte()
                command[offset++] = (pwdBytes.size and 0xFF).toByte()

                // 密码
                pwdBytes.copyInto(command, offset)
                offset += pwdBytes.size

                // Note: Frame checksum is handled by BleManager.buildFrame() using SUM

                // ====== BLE frame debug log ======
                Log.i(TAG, "========================================")
                Log.i(TAG, "BLE WiFi Credential Frame (Manual)")
                Log.i(TAG, "========================================")
                Log.i(TAG, "SSID: \"$ssid\"")
                Log.i(TAG, "Password: \"$password\"")
                Log.i(TAG, "----------------------------------------")
                Log.i(TAG, "Frame structure:")
                Log.i(TAG, "  CMD:        0x%02X (CMD_WIFI_ADD)".format(command[0]))
                Log.i(TAG, "  SSID_LEN:   ${ssidBytes.size} (0x%02X 0x%02X)".format(command[1], command[2]))
                Log.i(TAG, "  SSID_DATA:  ${ssidBytes.joinToString(" ") { "%02X".format(it) }} (\"$ssid\")")
                val pwdLenOffset = 3 + ssidBytes.size
                Log.i(TAG, "  PWD_LEN:    ${pwdBytes.size} (0x%02X 0x%02X)".format(command[pwdLenOffset], command[pwdLenOffset + 1]))
                Log.i(TAG, "  PWD_DATA:   ${pwdBytes.joinToString(" ") { "%02X".format(it) }}")
                Log.i(TAG, "----------------------------------------")
                Log.i(TAG, "Raw data(${command.size} bytes): ${command.joinToString(" ") { "%02X".format(it) }}")
                Log.i(TAG, "========================================")

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
