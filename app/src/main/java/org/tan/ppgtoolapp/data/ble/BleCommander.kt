package org.tan.ppgtoolapp.data.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothStatusCodes
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

    private val _statusData = MutableSharedFlow<ByteArray>(extraBufferCapacity = 16, replay = 1)
    val statusData: SharedFlow<ByteArray> = _statusData.asSharedFlow()

    private val _cmdResponse = MutableSharedFlow<ByteArray>(extraBufferCapacity = 16)
    val cmdResponse: SharedFlow<ByteArray> = _cmdResponse.asSharedFlow()

    private val _fileListData = MutableSharedFlow<ByteArray>(extraBufferCapacity = 16, replay = 1)
    val fileListData: SharedFlow<ByteArray> = _fileListData.asSharedFlow()

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
        enableCharNotification(gatt, PpgGattProfile.CHAR_FILE_LIST, "File List")
    }

    @SuppressLint("MissingPermission")
    private fun enableCharNotification(gatt: BluetoothGatt, charUuid: UUID, label: String) {
        val service = gatt.getService(PpgGattProfile.SERVICE_UUID) ?: return
        val char = service.getCharacteristic(charUuid) ?: return
        gatt.setCharacteristicNotification(char, true)
        char.getDescriptor(PpgGattProfile.DESCRIPTOR_CCC)?.let { desc ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                // writeDescriptor returns int on API 33+, but we don't need to check it here
            } else {
                @Suppress("DEPRECATION")
                desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
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
    fun writeCommand(gatt: BluetoothGatt?, command: ByteArray): Boolean {
        if (gatt == null) {
            Log.e(TAG, "writeCommand: gatt is null")
            return false
        }
        val service = gatt.getService(PpgGattProfile.SERVICE_UUID) ?: run {
            Log.e(TAG, "writeCommand: service not found")
            return false
        }
        val char = service.getCharacteristic(PpgGattProfile.CHAR_COMMAND) ?: run {
            Log.e(TAG, "writeCommand: characteristic not found")
            return false
        }

        // Build frame: [0xAA][CMD][LEN][DATA...][CHECKSUM]
        // command = [CMD][DATA...], LEN = data length only (excluding CMD)
        val dataLen = command.size - 1
        val frame = ByteArray(3 + command.size)
        frame[0] = FRAME_HEADER
        frame[1] = command[0]
        frame[2] = dataLen.toByte()
        if (command.size > 1) {
            System.arraycopy(command, 1, frame, 3, command.size - 1)
        }
        // Calculate SUM checksum
        var checksum = 0
        for (i in 1 until frame.size - 1) {
            checksum = (checksum + (frame[i].toInt() and 0xFF)) and 0xFF
        }
        frame[frame.size - 1] = checksum.toByte()

        Log.d(TAG, "TX Command: cmd=0x${String.format("%02X", command[0])} dataLen=$dataLen frame=${frame.joinToString(" ") { String.format("%02X", it) }}")

        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(char, frame, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            @Suppress("DEPRECATION")
            char.value = frame
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(char)
        }

        Log.d(TAG, "TX writeCharacteristic result: $result")

        // Handle error - trigger disconnect if connection is stale
        if (result != BluetoothStatusCodes.SUCCESS && result != 0) {
            Log.e(TAG, "TX write failed with code: $result, connection may be stale")
            // Don't trigger disconnect here to avoid recursion, let the caller handle it
        }

        return result == BluetoothStatusCodes.SUCCESS || result == 0
    }

    /**
     * Read a characteristic value
     */
    @SuppressLint("MissingPermission")
    suspend fun readCharacteristic(gatt: BluetoothGatt?, uuid: UUID): ByteArray? {
        if (gatt == null) {
            Log.e(TAG, "readCharacteristic: gatt is null")
            return null
        }
        val service = gatt.getService(PpgGattProfile.SERVICE_UUID) ?: run {
            Log.e(TAG, "readCharacteristic: service not found for uuid=$uuid")
            return null
        }
        val char = service.getCharacteristic(uuid) ?: run {
            Log.e(TAG, "readCharacteristic: characteristic not found for uuid=$uuid")
            return null
        }

        Log.d(TAG, "RX Read request: uuid=$uuid")
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
        Log.d(TAG, "RX Read response: uuid=$uuid value=${value?.joinToString(" ") { String.format("%02X", it) }}")
        synchronized(pendingReadLock) {
            if (uuid == pendingReadUuid && pendingReadContinuation != null) {
                val cont = pendingReadContinuation
                pendingReadContinuation = null
                pendingReadUuid = null
                cont?.resume(value)
            } else {
                Log.w(TAG, "RX Read: no pending continuation for uuid=$uuid")
            }
        }
    }

    /**
     * Handle characteristic changed callback
     */
    fun handleCharacteristicChanged(uuid: UUID, value: ByteArray?) {
        Log.d(TAG, "RX Notification: uuid=$uuid len=${value?.size} value=${value?.joinToString(" ") { String.format("%02X", it) }}")
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
            PpgGattProfile.CHAR_FILE_LIST -> {
                value?.let { _fileListData.tryEmit(it) }
            }
        }
    }
}
