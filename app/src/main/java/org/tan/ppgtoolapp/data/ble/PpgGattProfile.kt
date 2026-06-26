package org.tan.ppgtoolapp.data.ble

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
    const val CMD_TIME_SYNC: Byte = 0x40      // 时间同步
    const val CMD_OTA_ENTER: Byte = 0x20
    const val CMD_FW_VERSION: Byte = 0x21
    const val CMD_QUERY_STATUS: Byte = 0x22   // 查询完整状态
    const val CMD_QUERY_SD_CARD: Byte = 0x23  // 查询 SD 卡容量
    const val CMD_QUERY_BATTERY: Byte = 0x24  // 查询电池详情
    const val CMD_LOG_LEVEL: Byte = 0x30
    const val CMD_LOG_STATUS: Byte = 0x31
    const val CMD_FILE_DOWNLOAD: Byte = 0x32  /* BLE trigger + HTTP download */
    const val CMD_UART_RECORD: Byte = 0x50    /* UART recording control */
    const val CMD_UART_RECORD: Byte = 0x50    /* UART recording control */

    // 设备名前缀
    const val DEVICE_NAME_PREFIX = "PPG-Monitor"

    // 支持的设备名前缀列表（用于扫描过滤）
    val DEVICE_NAME_PREFIXES = listOf(
        "PPG-Monitor",
        "ESP32",
        "PPG"
    )
}
