# Android App 重构清单

> 代码坏味道分析与重构方案

---

## 🔴 高优先级

### 1. `BleManager` 类 495 行，违反单一职责原则

- **文件**: `app/src/main/java/org/tan/ppgtoolapp/data/ble/BleManager.kt` 第 37-532 行
- **问题**: 扫描、连接、重连、读写、帧构建、通知、查询全在一个类
- **方案**: 拆分为职责单一的子模块

```kotlin
// Before: BleManager 做所有事 (495 行)
class BleManager { /* 扫描 + 连接 + 重连 + 读写 + 帧构建 + 通知 + 查询 */ }

// After: 拆分为
class BleScanner(context: Context) {           // 扫描 (~90行)
    fun scan(useUuidFilter: Boolean): Flow<BleDevice>
}

class BleConnection(context: Context) {        // 连接 + 重连 (~200行)
    fun connect(device: BluetoothDevice): Flow<ConnectionState>
    fun disconnect()
    private fun startAutoReconnect()
}

class BleCommander(gatt: BluetoothGatt) {      // 命令收发 (~150行)
    suspend fun writeCommand(command: ByteArray): Boolean
    suspend fun readCharacteristic(uuid: UUID): ByteArray?
    fun buildFrame(cmdData: ByteArray): ByteArray
}

// BleManager 作为门面，委托给子模块
class BleManager @Inject constructor(...) {
    private val scanner = BleScanner(context)
    private var connection: BleConnection? = null
    private var commander: BleCommander? = null
    // 委托方法...
}
```

---

### 2. `connect()` 函数 122 行，内嵌匿名回调类

- **文件**: `BleManager.kt` 第 187-309 行
- **问题**: `BluetoothGattCallback` 内嵌在函数中，5 层嵌套
- **方案**: 提取为独立的回调类

```kotlin
// Before: connect() 内嵌 122 行回调
suspend fun connect(device: BluetoothDevice, deviceName: String): Boolean {
    return suspendCancellableCoroutine { continuation ->
        device.connectGatt(context, false, object : BluetoothGattCallback() {
            // 100+ 行回调逻辑...
        })
    }
}

// After: 提取回调类
class BleGattCallback(
    private val onConnected: (BluetoothGatt) -> Unit,
    private val onDisconnected: () -> Unit,
    private val onDataReceived: (UUID, ByteArray) -> Unit
) : BluetoothGattCallback() {

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        when (newState) {
            BluetoothProfile.STATE_CONNECTED -> onConnected(gatt)
            BluetoothProfile.STATE_DISCONNECTED -> onDisconnected()
        }
    }

    override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        onDataReceived(characteristic.uuid, characteristic.value ?: return)
    }
    // ...
}

// connect() 简化为
suspend fun connect(device: BluetoothDevice, deviceName: String): Boolean {
    return suspendCancellableCoroutine { continuation ->
        val callback = BleGattCallback(
            onConnected = { gatt -> /* ... */ continuation.resume(true) },
            onDisconnected = { /* ... */ continuation.resume(false) },
            onDataReceived = { uuid, data -> routeData(uuid, data) }
        )
        device.connectGatt(context, false, callback)
    }
}
```

---

### 3. 重复的 `BluetoothGattCallback` 定义

- **文件**: `BleManager.kt` 第 193-215 行 (`connect()`)、第 436-454 行 (`startAutoReconnect()`)
- **问题**: 两处定义几乎相同的 `onConnectionStateChange` 逻辑
- **方案**: 使用共享的回调类（见问题 2 的方案）

---

## 🟡 中优先级

### 4. ✅ 重复的通知启用模式 — 已完成

- **文件**: `BleManager.kt` 第 321-348 行
- **问题**: 三个特征值的通知启用代码完全相同
- **方案**: 提取 helper 函数

```kotlin
// Before: 重复 3 次
service.getCharacteristic(PpgGattProfile.CHAR_LIVE_DATA)?.let { char ->
    gatt.setCharacteristicNotification(char, true)
    char.getDescriptor(PpgGattProfile.DESCRIPTOR_CCC)?.let { desc ->
        desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        gatt.writeDescriptor(desc)
    }
}
// ... 再来两次

// After: 提取为一个函数
private fun enableCharNotification(gatt: BluetoothGatt, charUuid: UUID, label: String) {
    val service = gatt.getService(PpgGattProfile.SERVICE_UUID) ?: return
    service.getCharacteristic(charUuid)?.let { char ->
        gatt.setCharacteristicNotification(char, true)
        char.getDescriptor(PpgGattProfile.DESCRIPTOR_CCC)?.let { desc ->
            desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(desc)
            Log.i(TAG, "已启用 $label Notify: ${char.uuid}")
        } ?: Log.w(TAG, "$label 无 CCC Descriptor")
    } ?: Log.w(TAG, "未找到 $label 特征值")
}

// 使用
enableCharNotification(gatt, PpgGattProfile.CHAR_LIVE_DATA, "Live Data")
enableCharNotification(gatt, PpgGattProfile.CHAR_STATUS, "Status")
enableCharNotification(gatt, PpgGattProfile.CHAR_COMMAND, "Command")
```

---

### 5. ✅ 重复的 BLE 查询模式 — 已完成

- **文件**: `MonitorViewModel.kt` 第 202-254 行
- **问题**: `querySdCardStatus()` 和 `queryBatteryStatus()` 结构几乎相同
- **方案**: 提取通用查询函数

```kotlin
// Before: 两个函数结构相同
fun querySdCardStatus() {
    viewModelScope.launch {
        if (bleManager.isConnected()) {
            val success = bleManager.querySdCardStatus()
            if (success) {
                val resp = withTimeoutOrNull(2000L) {
                    bleManager.cmdResponse.first { it.size >= 7 && it[1] == 0x23.toByte() }
                }
                // parse...
            }
        }
    }
}

// After: 通用查询
private suspend fun <T> queryBleCommand(
    cmd: Byte,
    timeoutMs: Long = 2000L,
    minResponseSize: Int = 5,
    parser: (ByteArray) -> T
): T? {
    if (!bleManager.isConnected()) return null
    if (!bleManager.writeCommand(byteArrayOf(cmd))) return null
    val resp = withTimeoutOrNull(timeoutMs) {
        bleManager.cmdResponse.first { it.size >= minResponseSize && it[1] == cmd }
    }
    return resp?.let { parser(it) }
}

fun querySdCardStatus() {
    viewModelScope.launch {
        val result = queryBleCommand(0x23.toByte(), minResponseSize = 7) { resp ->
            val freeMb = ((resp[3].toInt() and 0xFF) shl 8) or (resp[4].toInt() and 0xFF)
            val totalMb = ((resp[5].toInt() and 0xFF) shl 8) or (resp[6].toInt() and 0xFF)
            Pair(freeMb, totalMb)
        }
        result?.let { (free, _) -> _deviceStatus.update { it.copy(sdFreeMb = free) } }
    }
}

fun queryBatteryStatus() {
    viewModelScope.launch {
        val result = queryBleCommand(0x24.toByte(), minResponseSize = 5) { resp ->
            resp[3].toInt() and 0xFF
        }
        result?.let { pct -> _deviceStatus.update { it.copy(battery = BatteryInfo(batt_pct = pct)) } }
    }
}
```

---

### 6. 重复的 HTTP 请求模式

- **文件**: `OtaViewModel.kt` 第 199-243 行
- **问题**: 两个 for 循环结构几乎相同，只差 URL 后缀和解析函数
- **方案**: 提取 HTTP 请求 helper

```kotlin
// Before: 两个几乎相同的循环
for (mirror in apiMirrors) {
    val url = java.net.URL("${mirror}${githubApi}/latest")
    val conn = url.openConnection() as java.net.HttpURLConnection
    conn.setRequestProperty("Accept", "...")
    conn.setRequestProperty("User-Agent", "...")
    conn.connectTimeout = 8000
    conn.readTimeout = 8000
    // ... 处理响应
}

// After: 提取 helper
private suspend fun fetchFromMirror(path: String): String? = withContext(Dispatchers.IO) {
    val apiMirrors = GitHubHostsHelper.getSortedMirrors(context)
    val repo = _state.value.firmwareRepo.ifBlank { DEFAULT_FIRMWARE_REPO }
    for (mirror in apiMirrors) {
        try {
            val url = java.net.URL("${mirror}${GITHUB_API_BASE}/${repo}$path")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
            conn.setRequestProperty("User-Agent", "PPGTool-Android")
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            if (conn.responseCode == 200) {
                val json = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                return@withContext json
            }
            conn.disconnect()
        } catch (_: Exception) { }
    }
    null
}

// 使用
suspend fun fetchLatestRelease(): ReleaseInfo? {
    val json = fetchFromMirror("/latest") ?: return null
    return parseRelease(json)
}

suspend fun fetchReleases(): List<ReleaseInfo> {
    val json = fetchFromMirror("?per_page=5") ?: return emptyList()
    return parseReleases(json)
}
```

---

### 7. 重复的 7z 解压模式

- **文件**: `OtaViewModel.kt` 第 288-365 行 (`downloadAndExtract()`)、第 367-438 行 (`selectLocalFile()`)
- **问题**: 两处共享相同的 7z 解压 + 状态更新模式
- **方案**: 提取解压 helper

```kotlin
// Before: 两处重复的解压逻辑
_state.update { it.copy(isExtracting = true, progressText = "正在解压...") }
val extractDir = File(downloadDir, "extract-${System.currentTimeMillis()}")
val result = withContext(Dispatchers.IO) { SevenZipHelper.extractFirmware(file, extractDir) }
if (result.firmwareFile != null) {
    _state.update { it.copy(isExtracting = false, firmwareFile = result.firmwareFile, ...) }
} else {
    _state.update { it.copy(isExtracting = false, error = "...") }
}

// After: 提取为通用方法
private suspend fun extractFirmware(sourceFile: File, extractDir: File): File? {
    _state.update { it.copy(isExtracting = true, progressText = "正在解压...", error = null) }
    val result = withContext(Dispatchers.IO) {
        SevenZipHelper.extractFirmware(sourceFile, extractDir)
    }
    return if (result.firmwareFile != null) {
        _state.update {
            it.copy(isExtracting = false, firmwareFile = result.firmwareFile,
                selectedFileName = result.firmwareFile.name,
                progressText = "解压完成: ${result.firmwareFile.name}")
        }
        result.firmwareFile
    } else {
        _state.update {
            it.copy(isExtracting = false,
                error = "未找到 .bin 固件: ${result.extractedFiles.joinToString(", ")}")
        }
        null
    }
}
```

---

### 8. ✅ `fetchDeviceStatus()` 嵌套 4 层 — 已完成

- **文件**: `MonitorViewModel.kt` 第 146-196 行
- **问题**: `launch` > `if (connected)` > `if (success)` > `if (data != null)` — 4 层嵌套
- **方案**: 使用卫语句 (Guard Clause)

```kotlin
// Before: 4 层嵌套
fun fetchDeviceStatus() {
    viewModelScope.launch {
        if (bleManager.isConnected()) {
            val success = bleManager.queryDeviceStatus()
            if (success) {
                val data = bleManager.readCharacteristic(...)
                if (data != null && data.size >= 20) {
                    // 解析逻辑...
                }
            }
        }
        // fallback...
    }
}

// After: 卫语句提前返回
fun fetchDeviceStatus() {
    viewModelScope.launch {
        try {
            if (!bleManager.isConnected()) {
                fetchDeviceStatusHttp()
                return@launch
            }
            if (!bleManager.queryDeviceStatus()) {
                fetchDeviceStatusHttp()
                return@launch
            }
            delay(500)
            val data = bleManager.readCharacteristic(PpgGattProfile.CHAR_STATUS)
            if (data == null || data.size < 20) {
                fetchDeviceStatusHttp()
                return@launch
            }
            // 解析逻辑 (扁平结构)
            val battery = BatteryInfo(batt_pct = data[0].toInt() and 0xFF)
            val version = String(data.copyOfRange(5, 20), Charsets.UTF_8).trim()
            _deviceStatus.value = DeviceStatus(battery = battery, firmwareVersion = version, isOnline = true)
            querySdCardStatus()
        } catch (e: Exception) {
            Log.e(TAG, "获取设备状态异常: ${e.message}")
            _deviceStatus.update { it.copy(isOnline = false) }
        }
    }
}
```

---

## 🟢 低优先级

### 9. ✅ 魔法数字 — 已完成

- **文件**: `MonitorViewModel.kt`、`OtaViewModel.kt`、`BleManager.kt`
- **问题**: 超时值、命令字节、数据长度等硬编码
- **方案**: 提取为常量

```kotlin
// BleManager.kt 伴生对象
companion object {
    const val READ_TIMEOUT_MS = 5000L
    const val AUTO_RECONNECT_DELAY_MS = 3000L
}

// MonitorViewModel.kt 伴生对象
companion object {
    const val BLE_QUERY_TIMEOUT_MS = 2000L
    const val BLE_RESPONSE_DELAY_MS = 500L
    const val STATUS_DATA_SIZE = 20
    const val SD_CARD_RESPONSE_SIZE = 7
    const val BATTERY_RESPONSE_SIZE = 5
}

// OtaViewModel.kt 伴生对象
companion object {
    const val HTTP_CONNECT_TIMEOUT_MS = 8000
    const val HTTP_READ_TIMEOUT_MS = 8000
    const val GITHUB_API_BASE = "https://api.github.com/repos"
    const val DEFAULT_FIRMWARE_REPO = "Tan1347/ESP32-C3_PPG_Data_Collector"
}
```

---

### 10. ✅ 空的 `onCleared()` 覆写 — 已完成

- **文件**: `MonitorViewModel.kt` 第 264-266 行
- **问题**: 只调用 `super.onCleared()`，无意义
- **方案**: 直接删除

```kotlin
// Before
override fun onCleared() {
    super.onCleared()
}

// After: 删除，Kotlin 会自动调用 super
```

---

### 11. `OtaState` 数据类 14 个字段

- **文件**: `OtaViewModel.kt` 第 25-41 行
- **问题**: 字段过多，暗示类承担过多 UI 状态
- **方案**: 拆分为子状态

```kotlin
// Before: 14 个字段
data class OtaState(
    val deviceVersion: String = "未知",
    val firmwareRepo: String = "",
    val isDownloading: Boolean = false,
    val isExtracting: Boolean = false,
    val isUploading: Boolean = false,
    val progress: Int = 0,
    val progressText: String = "",
    val firmwareFile: File? = null,
    val selectedFileName: String? = null,
    val releaseList: List<ReleaseInfo> = emptyList(),
    val isLoadingReleases: Boolean = false,
    val showReleaseDialog: Boolean = false,
    val showRepoDialog: Boolean = false,
    val result: OtaResult? = null,
    val error: String? = null
)

// After: 拆分为子状态
data class OtaState(
    val deviceVersion: String = "未知",
    val firmwareRepo: String = "",
    val operation: OperationState = OperationState.Idle,
    val releaseList: ReleaseListState = ReleaseListState(),
    val dialog: DialogState = DialogState(),
    val error: String? = null
)

sealed class OperationState {
    data object Idle : OperationState()
    data class Downloading(val progress: Int, val text: String) : OperationState()
    data class Extracting(val text: String) : OperationState()
    data class Uploading(val progress: Int, val text: String) : OperationState()
}

data class ReleaseListState(
    val releases: List<ReleaseInfo> = emptyList(),
    val isLoading: Boolean = false
)

data class DialogState(
    val showRelease: Boolean = false,
    val showRepo: Boolean = false
)
```

---

### 12. `OtaViewModel` 类 507 行

- **文件**: `OtaViewModel.kt`
- **问题**: 处理版本查询、Release 下载、本地文件、OTA 上传、仓库配置
- **方案**: 可考虑提取 `FirmwareDownloader` 类处理下载/解压逻辑

---

## 重构优先级

| 优先级 | 问题 | 预估工作量 | 收益 |
|--------|------|-----------|------|
| P0 | BleManager 拆分 | 4h | SRP 原则，可维护性大幅提升 |
| P0 | connect() 回调提取 | 1h | 消除嵌套，可测试性提升 |
| P1 | enableNotifications helper | 15min | 消除重复 |
| P1 | BLE 查询通用函数 | 30min | 消除重复 |
| P1 | HTTP 请求 helper | 30min | 消除重复 |
| P1 | 7z 解压 helper | 20min | 消除重复 |
| P2 | fetchDeviceStatus 卫语句 | 15min | 可读性提升 |
| P2 | 魔法数字提取 | 30min | 可读性提升 |
| P3 | 删除空 onCleared | 1min | 清理 |
| P3 | OtaState 拆分 | 30min | 可维护性 |
