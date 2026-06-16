package com.ppgtool.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ppgtool.app.data.ble.BleManager
import com.ppgtool.app.data.ble.PpgGattProfile
import com.ppgtool.app.data.wifi.WifiNetwork
import com.ppgtool.app.data.wifi.WifiScanner
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets
import javax.inject.Inject

data class WifiProvisionState(
    val isScanning: Boolean = false,
    val networks: List<WifiNetwork> = emptyList(),
    val selectedNetwork: WifiNetwork? = null,
    val password: String = "",
    val isConnecting: Boolean = false,
    val connectionResult: ConnectionResult? = null,
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
            _state.update { it.copy(isScanning = false, error = "请先开启 WiFi") }
            return
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
                // 格式: [CMD_WIFI_ADD][ssid_len_h][ssid_len_l][ssid...][pwd_len_h][pwd_len_l][pwd...]
                val ssidBytes = network.ssid.toByteArray(StandardCharsets.UTF_8)
                val pwdBytes = password.toByteArray(StandardCharsets.UTF_8)

                val command = ByteArray(1 + 2 + ssidBytes.size + 2 + pwdBytes.size)
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

                val success = bleManager.writeCommand(command)

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
