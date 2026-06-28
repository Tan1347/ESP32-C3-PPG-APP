package org.tan.ppgtoolapp.data.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * BLE connection module - handles connect, disconnect, auto-reconnect
 */
class BleConnection(
    private val context: Context,
    private val onConnected: (BluetoothGatt) -> Unit,
    private val onDisconnected: () -> Unit,
    private val onCharacteristicChanged: (java.util.UUID, ByteArray?) -> Unit = { _, _ -> }
) {
    companion object {
        private const val TAG = "BleConnection"
        private const val MAX_RECONNECT_ATTEMPTS = 10
        private const val BACKOFF_BASE_MS = 2000L
        private const val BACKOFF_MAX_MS = 30000L
    }

    @Volatile var bluetoothGatt: BluetoothGatt? = null
        private set

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private var lastConnectedDevice: BluetoothDevice? = null
    private var lastConnectedName: String = ""
    private var isAutoReconnecting = false
    @Volatile private var intentionalDisconnect = false
    private var autoReconnectRunnable: Runnable? = null
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private var reconnectAttempt = 0

    /**
     * Connect to a BLE device
     */
    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice, deviceName: String = "") {
        _connectionState.value = ConnectionState.Connecting
        lastConnectedDevice = device
        lastConnectedName = deviceName
        intentionalDisconnect = false

        device.connectGatt(context, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.i(TAG, "Connected: $deviceName (${device.address})")
                        bluetoothGatt = gatt
                        reconnectAttempt = 0
                        // Don't set Connected yet - wait for services discovered
                        _connectionState.value = ConnectionState.Connecting
                        gatt.discoverServices()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.w(TAG, "Disconnected: $deviceName (${device.address})")
                        bluetoothGatt = null
                        _connectionState.value = ConnectionState.Disconnected
                        onDisconnected()
                        if (!intentionalDisconnect) {
                            startAutoReconnect()
                        }
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "Services discovered")
                    // Request larger MTU for bigger data transfers
                    gatt.requestMtu(247)  // BLE 5.0 max is 512, 247 is safe for most devices
                } else {
                    Log.e(TAG, "Services discovery failed: $status")
                    gatt.disconnect()
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "MTU negotiated: $mtu bytes")
                    // Now set Connected - services and MTU are ready
                    val device = lastConnectedDevice
                    if (device != null) {
                        _connectionState.value = ConnectionState.Connected(device, lastConnectedName)
                    }
                    onConnected(gatt)
                } else {
                    Log.e(TAG, "MTU negotiation failed: $status")
                    // Still connect even if MTU negotiation fails
                    val device = lastConnectedDevice
                    if (device != null) {
                        _connectionState.value = ConnectionState.Connected(device, lastConnectedName)
                    }
                    onConnected(gatt)
                }
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: android.bluetooth.BluetoothGattCharacteristic, value: ByteArray) {
                Log.d(TAG, "onCharacteristicChanged: uuid=${characteristic.uuid}")
                onCharacteristicChanged(characteristic.uuid, value)
            }
        })
    }

    /**
     * Disconnect from current device
     */
    @SuppressLint("MissingPermission")
    fun disconnect() {
        intentionalDisconnect = true
        stopAutoReconnect()
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        _connectionState.value = ConnectionState.Disconnected
    }

    /**
     * Check if connected
     */
    fun isConnected(): Boolean = _connectionState.value is ConnectionState.Connected

    /**
     * Get connected device MAC
     */
    fun getConnectedDeviceMac(): String? {
        val state = _connectionState.value
        return if (state is ConnectionState.Connected) state.device.address else null
    }

    /**
     * Start auto-reconnect with exponential backoff
     */
    @SuppressLint("MissingPermission")
    private fun startAutoReconnect() {
        val device = lastConnectedDevice ?: return

        if (reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "Max reconnect attempts reached")
            isAutoReconnecting = false
            reconnectAttempt = 0
            _connectionState.value = ConnectionState.Disconnected
            return
        }

        isAutoReconnecting = true
        reconnectAttempt++
        val delayMs = minOf(BACKOFF_BASE_MS * (1L shl (reconnectAttempt - 1)), BACKOFF_MAX_MS)
        Log.i(TAG, "Reconnect attempt $reconnectAttempt/$MAX_RECONNECT_ATTEMPTS, delay=${delayMs}ms")

        autoReconnectRunnable = Runnable {
            if (isAutoReconnecting && bluetoothGatt == null) {
                try {
                    device.connectGatt(context, false, object : BluetoothGattCallback() {
                        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                            when (newState) {
                                BluetoothProfile.STATE_CONNECTED -> {
                                    Log.i(TAG, "Reconnected!")
                                    bluetoothGatt = gatt
                                    isAutoReconnecting = false
                                    reconnectAttempt = 0
                                    // Don't set Connected yet - wait for services discovered
                                    _connectionState.value = ConnectionState.Connecting
                                    gatt.discoverServices()
                                }
                                BluetoothProfile.STATE_DISCONNECTED -> {
                                    Log.w(TAG, "Reconnect failed")
                                    gatt.close()
                                    startAutoReconnect()
                                }
                            }
                        }

                        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                            if (status == BluetoothGatt.GATT_SUCCESS) {
                                Log.i(TAG, "Reconnect services discovered")
                                gatt.requestMtu(247)
                            } else {
                                Log.e(TAG, "Reconnect services discovery failed: $status")
                                gatt.disconnect()
                            }
                        }

                        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                            Log.i(TAG, "Reconnect MTU: $mtu")
                            _connectionState.value = ConnectionState.Connected(device, lastConnectedName)
                            onConnected(gatt)
                        }

                        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: android.bluetooth.BluetoothGattCharacteristic, value: ByteArray) {
                            onCharacteristicChanged(characteristic.uuid, value)
                        }
                    })
                } catch (e: Exception) {
                    Log.e(TAG, "Reconnect error: ${e.message}")
                    startAutoReconnect()
                }
            }
        }
        reconnectHandler.postDelayed(autoReconnectRunnable!!, delayMs)
    }

    /**
     * Stop auto-reconnect
     */
    private fun stopAutoReconnect() {
        autoReconnectRunnable?.let { reconnectHandler.removeCallbacks(it) }
        autoReconnectRunnable = null
        isAutoReconnecting = false
        reconnectAttempt = 0
    }
}
