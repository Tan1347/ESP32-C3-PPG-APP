package com.ppgtool.app.data.ble

import java.util.UUID

/**
 * ESP32-C3 PPG 设备 GATT 服务定义
 * 与固件 components/ble_svc/ble_svc.c 中的定义一致
 */
object PpgGattProfile {
    // 主服务
    val SERVICE_UUID: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")

    // 特征值
    val CHAR_STATUS: UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")    // Read/Notify
    val CHAR_LIVE_DATA: UUID = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb") // Notify
    val CHAR_COMMAND: UUID = UUID.fromString("0000fff3-0000-1000-8000-00805f9b34fb")   // Write
    val CHAR_FILE_LIST: UUID = UUID.fromString("0000fff4-0000-1000-8000-00805f9b34fb") // Read

    // 描述符（Notify 启用）
    val DESCRIPTOR_CCC: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // 命令定义
    const val CMD_START_MEASURE: Byte = 0x01
    const val CMD_STOP_MEASURE: Byte = 0x02
    const val CMD_START_WIFI: Byte = 0x03
    const val CMD_WIFI_ADD: Byte = 0x10
    const val CMD_WIFI_STATUS: Byte = 0x11
    const val CMD_WIFI_CLEAR: Byte = 0x12
    const val CMD_WIFI_DELETE: Byte = 0x13
    const val CMD_WIFI_LIST: Byte = 0x14
    const val CMD_WIFI_MODIFY: Byte = 0x15
    const val CMD_WIFI_PRIORITY: Byte = 0x16
    const val CMD_OTA_ENTER: Byte = 0x20
    const val CMD_FW_VERSION: Byte = 0x21
    const val CMD_LOG_LEVEL: Byte = 0x30
    const val CMD_LOG_STATUS: Byte = 0x31
    const val CMD_LOG_EXPORT: Byte = 0x32

    // 设备名前缀
    const val DEVICE_NAME_PREFIX = "PPG-Monitor"
}
