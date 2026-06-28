package org.tan.ppgtoolapp.data.ble

import kotlinx.coroutines.flow.SharedFlow
import java.util.UUID

/**
 * BLE 命令写入、特征读取、数据流接收接口
 */
interface BleCommandProvider {
    val liveData: SharedFlow<ByteArray>
    val statusData: SharedFlow<ByteArray>
    val cmdResponse: SharedFlow<ByteArray>
    suspend fun writeCommand(command: ByteArray): Boolean
    suspend fun readCharacteristic(uuid: UUID): ByteArray?
    suspend fun queryDeviceStatus(): Boolean
    suspend fun querySdCardStatus(): Boolean
    suspend fun queryBatteryStatus(): Boolean
    suspend fun startUartRecord(baudRate: Int, dataBits: Int, parity: Int, stopBits: Int): Boolean
    suspend fun stopUartRecord(): Boolean
    suspend fun syncTime(timestamp: Long): Boolean
    suspend fun triggerFileDownload(): String?
}
