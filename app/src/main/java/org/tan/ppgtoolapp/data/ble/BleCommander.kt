package org.tan.ppgtoolapp.data.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.coroutines.resume

/**
 * BLE command module - handles writing commands, reading characteristics, notifications
 */
class BleCommander {
    companion object {
        private const val TAG = "BleCommander"
        private const val READ_TIMEOUT_MS = 3000L
        private const val FRAME_HEADER: Byte = 0xAA.toByte()
    }

    private val _liveData = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val liveData: SharedFlow<ByteArray> = _liveData.asSharedFlow()

    private val _statusData = MutableSharedFlow<ByteArray>(extraBufferCapacity = 16)
    val statusData: SharedFlow<ByteArray> = _statusData.asSharedFlow()

    private val _cmdResponse = MutableSharedFlow<ByteArray>(extraBufferCapacity = 16)
    val cmdResponse: SharedFlow<ByteArray> = _cmdResponse.asSharedFlow()

    private val pendingReadLock = Any()
    @Volatile private var pendingReadUuid: UUID? = null
    @Volatile private var pendingReadContinuation: kotlin.coroutines.Continuation<ByteArray?>? = null

    /**
     * Enable notifications for a characteristic
     */
    @SuppressLint("MissingPermission")
    fun enableNotifications(gatt: BluetoothGatt) {
        enableCharNotification(gatt, PpgGattProfile.CHAR_LIVE_DATA, "Live Data")
        enableCharNotification(gatt, PpgGattProfile.CHAR_STATUS, "Status")
        enableCharNotification(gatt, PpgGattProfile.CHAR_COMMAND, "Command")
    }

    @SuppressLint("MissingPermission")
    private fun enableCharNotification(gatt: BluetoothGatt, charUuid: UUID, label: String) {
        val service = gatt.getService(PpgGattProfile.SERVICE_UUID) ?: return
        val char = service.getCharacteristic(charUuid) ?: return
        gatt.setCharacteristicNotification(char, true)
        char.getDescriptor(PpgGattProfile.DESCRIPTOR_CCC)?.let { desc ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(desc, android.bluetooth.BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                desc.value = android.bluetooth.BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(desc)
            }
        }
        Log.d(TAG, "Enabled notification: $label")
    }

    /**
     * Write a command to the device
     */
    @SuppressLint("MissingPermission")
    suspend fun writeCommand(gatt: BluetoothGatt?, command: ByteArray): Boolean {
        if (gatt == null) return false
        val service = gatt.getService(PpgGattProfile.SERVICE_UUID) ?: return false
        val char = service.getCharacteristic(PpgGattProfile.CHAR_COMMAND) ?: return false

        // Build frame: [0xAA][CMD][LEN][DATA...][CHECKSUM]
        val frame = ByteArray(3 + command.size)
        frame[0] = FRAME_HEADER
        frame[1] = command[0]
        frame[2] = command.size.toByte()
        if (command.size > 1) {
            System.arraycopy(command, 1, frame, 3, command.size - 1)
        }
        // Calculate SUM checksum
        var checksum = 0
        for (i in 1 until frame.size - 1) {
            checksum = (checksum + (frame[i].toInt() and 0xFF)) and 0xFF
        }
        frame[frame.size - 1] = checksum.toByte()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(char, frame, android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            @Suppress("DEPRECATION")
            char.value = frame
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(char)
        }
    }

    /**
     * Read a characteristic value
     */
    @SuppressLint("MissingPermission")
    suspend fun readCharacteristic(gatt: BluetoothGatt?, uuid: UUID): ByteArray? {
        if (gatt == null) return null
        val service = gatt.getService(PpgGattProfile.SERVICE_UUID) ?: return null
        val char = service.getCharacteristic(uuid) ?: return null

        return withTimeoutOrNull(READ_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                synchronized(pendingReadLock) {
                    pendingReadUuid = uuid
                    pendingReadContinuation = continuation
                }
                gatt.readCharacteristic(char)

                continuation.invokeOnCancellation {
                    synchronized(pendingReadLock) {
                        pendingReadContinuation = null
                        pendingReadUuid = null
                    }
                }
            }
        }
    }

    /**
     * Handle characteristic read callback
     */
    fun handleCharacteristicRead(uuid: UUID, value: ByteArray?) {
        synchronized(pendingReadLock) {
            if (uuid == pendingReadUuid && pendingReadContinuation != null) {
                val cont = pendingReadContinuation
                pendingReadContinuation = null
                pendingReadUuid = null
                cont?.resume(value)
            }
        }
    }

    /**
     * Handle characteristic changed callback
     */
    fun handleCharacteristicChanged(uuid: UUID, value: ByteArray?) {
        when (uuid) {
            PpgGattProfile.CHAR_LIVE_DATA -> {
                value?.let { _liveData.tryEmit(it) }
            }
            PpgGattProfile.CHAR_STATUS -> {
                value?.let { _statusData.tryEmit(it) }
            }
            PpgGattProfile.CHAR_COMMAND -> {
                value?.let { _cmdResponse.tryEmit(it) }
            }
        }
    }
}
