# PPG Monitor API 参考文档

## BLE API

### BleManager

蓝牙低功耗设备管理器，负责扫描、连接、数据通信。

#### 属性

| 属性 | 类型 | 说明 |
|------|------|------|
| `connectionState` | `StateFlow<ConnectionState>` | 连接状态流 |
| `liveData` | `SharedFlow<ByteArray>` | 实时 PPG 数据流 |
| `statusData` | `SharedFlow<ByteArray>` | 设备状态数据流 |

#### 方法

##### `scan(): Flow<BleDevice>`

扫描附近的 PPG 设备。

```kotlin
bleManager.scan().collect { device ->
    println("发现设备: ${device.name} (${device.address})")
}
```

**返回值**：设备信息流

| 字段 | 类型 | 说明 |
|------|------|------|
| `name` | `String` | 设备名称 |
| `address` | `String` | MAC 地址 |
| `rssi` | `Int` | 信号强度 |

---

##### `suspend connect(device: BluetoothDevice): Boolean`

连接到指定设备，等待服务发现完成。

```kotlin
val success = bleManager.connect(device)
if (success) {
    println("连接成功，服务已就绪")
}
```

**参数**：
- `device`: BluetoothDevice 实例

**返回值**：是否连接成功

**线程安全**：使用 `suspendCancellableCoroutine`，在 `onServicesDiscovered` 回调后返回

---

##### `suspend writeCommand(command: ByteArray): Boolean`

向设备发送控制命令。

```kotlin
// 开始测量
bleManager.writeCommand(byteArrayOf(0x01))

// 停止测量
bleManager.writeCommand(byteArrayOf(0x02))

// 发送 WiFi 凭据
val ssid = "MyWiFi".toByteArray()
val password = "12345678".toByteArray()
val cmd = ByteArray(1 + 2 + ssid.size + 2 + password.size)
cmd[0] = 0x10  // CMD_WIFI_ADD
// ... 填充长度和数据
bleManager.writeCommand(cmd)
```

**参数**：
- `command`: 命令字节数组

**返回值**：是否发送成功

---

##### `suspend readCharacteristic(uuid: UUID): ByteArray?`

读取指定特征值。

```kotlin
val data = bleManager.readCharacteristic(PpgGattProfile.CHAR_STATUS)
data?.let {
    val battery = it[0].toInt() and 0xFF
    println("电量: $battery%")
}
```

**参数**：
- `uuid`: 特征值 UUID

**返回值**：特征值数据，失败返回 null

**线程安全**：使用 `suspendCancellableCoroutine` 等待异步回调

---

##### `suspend syncTime(timestamp: Long): Boolean`

同步时间到设备。

```kotlin
// 获取当前时间戳（UTC+8）
val timestamp = timeSyncHelper.getTimestamp()

// 发送到设备
val success = bleManager.syncTime(timestamp)
if (success) {
    println("时间同步成功")
}
```

**参数**：
- `timestamp`: Unix 10 位时间戳（秒），UTC+8 时区

**返回值**：是否发送成功

**命令格式**：
```
[0x40][timestamp_byte3][timestamp_byte2][timestamp_byte1][timestamp_byte0]
```

---

### PpgGattProfile

GATT 服务定义常量。

#### UUID 常量

| 常量 | UUID | 说明 |
|------|------|------|
| `SERVICE_UUID` | `0000fff0-...` | 主服务 |
| `CHAR_STATUS` | `0000fff1-...` | 状态特征 (Read/Notify) |
| `CHAR_LIVE_DATA` | `0000fff2-...` | 实时数据 (Notify) |
| `CHAR_COMMAND` | `0000fff3-...` | 命令特征 (Write) |
| `CHAR_FILE_LIST` | `0000fff4-...` | 文件列表 (Read) |

#### 命令常量

| 常量 | 值 | 说明 |
|------|-----|------|
| `CMD_START_MEASURE` | `0x01` | 开始测量 |
| `CMD_STOP_MEASURE` | `0x02` | 停止测量 |
| `CMD_START_WIFI` | `0x03` | 启动 WiFi |
| `CMD_WIFI_ADD` | `0x10` | 添加 WiFi |
| `CMD_WIFI_STATUS` | `0x11` | WiFi 状态 |
| `CMD_WIFI_CLEAR` | `0x12` | 清除 WiFi |
| `CMD_WIFI_DELETE` | `0x13` | 删除 WiFi |
| `CMD_WIFI_LIST` | `0x14` | WiFi 列表 |
| `CMD_WIFI_MODIFY` | `0x15` | 修改 WiFi |
| `CMD_WIFI_PRIORITY` | `0x16` | WiFi 优先级 |
| `CMD_TIME_SYNC` | `0x40` | 时间同步 |
| `CMD_OTA_ENTER` | `0x20` | 进入 OTA |
| `CMD_FW_VERSION` | `0x21` | 查询版本 |
| `CMD_LOG_LEVEL` | `0x30` | 日志级别 |
| `CMD_LOG_STATUS` | `0x31` | 日志状态 |
| `CMD_LOG_EXPORT` | `0x32` | 导出日志 |

---

## HTTP API

### DeviceApi (Retrofit 接口)

设备 HTTP 服务接口。

#### `GET /api/files`

获取 TF 卡文件列表。

**响应**：
```json
{
  "files": ["data_20240101.csv", "log_001.bin"]
}
```

---

#### `GET /api/download?file={filename}`

下载指定文件。

**参数**：
- `file`: 文件名

**响应**：文件二进制流

---

#### `GET /api/status`

获取设备状态。

**响应**：
```json
{
  "version": "1.0.0",
  "battery": {
    "soc": 85,
    "voltage": 3800
  },
  "ip": "192.168.1.100",
  "sd_free_mb": 1024
}
```

**字段说明**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `version` | `String` | 固件版本 |
| `battery.soc` | `Int` | 电量百分比 (0-100) |
| `battery.voltage` | `Int` | 电压 (mV) |
| `ip` | `String` | 设备 IP |
| `sd_free_mb` | `Int` | SD 卡剩余容量 (MB) |

---

#### `POST /api/ota`

上传固件进行 OTA 升级。

**请求体**：`application/octet-stream` (固件二进制)

**响应**：设备自动重启

---

#### `GET /api/logs`

获取日志文件列表。

**响应**：
```json
{
  "logs": ["log_20240101.txt", "log_20240102.txt"]
}
```

---

#### `GET /api/logs/download?file={filename}`

下载指定日志文件。

**参数**：
- `file`: 日志文件名

---

#### `POST /api/shutdown`

关闭设备 WiFi。

---

## GitHub API

### GitHubHostsHelper

GitHub 连接优化工具。

#### 方法

##### `testIp(ip: String, port: Int = 443, timeout: Int = 3000): Long`

测试指定 IP 的 TCP 连接延迟。

**返回值**：延迟毫秒数，不可达返回 `Long.MAX_VALUE`

---

##### `testAll(onProgress: (String, String, Long) -> Unit): List<DomainResult>`

测试所有 GitHub 域名的 IP 延迟。

**返回值**：
```kotlin
data class DomainResult(
    val domain: String,
    val bestIp: String,
    val bestLatency: Long,
    val allResults: List<IpResult>
)
```

---

##### `fetchRemoteHosts(context: Context): Map<String, String>`

从远程获取 GitHub hosts 文件。

**数据源**：`https://raw.hellogithub.com/hosts`

**返回值**：域名到 IP 的映射

---

##### `getBestIp(context: Context, domain: String): String?`

获取缓存的最佳 IP。

**优先级**：
1. 本地测速缓存 (24小时有效)
2. 远程 hosts 缓存 (24小时有效)

---

### UpdateChecker

应用自更新检查器。

#### 方法

##### `checkForUpdate(onResult: (ReleaseInfo?) -> Unit)`

检查是否有新版本。

**流程**：
1. 镜像优先策略
2. 远程 hosts 回退
3. 版本比较

---

##### `startDownload(apkUrl, totalSize, onProgress, onComplete, onError)`

下载 APK 更新。

**特性**：
- DownloadManager 下载
- 镜像失败自动重试
- 进度回调

---

## 时间同步 API

### TimeSyncHelper

时间同步工具，优先从网络获取时间，失败时使用本地时间。

#### 方法

##### `suspend getTimestamp(): Long`

获取当前 Unix 10 位时间戳（秒），UTC+8 时区。

```kotlin
val timestamp = timeSyncHelper.getTimestamp()
println("当前时间戳: $timestamp")
```

**获取策略**：
1. 优先从 `https://ip.ddnspod.com/timestamp` 获取（返回 Unix 13 位，去除末 3 位）
2. 网络失败时使用本地时间 + UTC+8 偏移

**返回值**：Unix 10 位时间戳（秒），UTC+8 时区

---

##### `formatTimestamp(timestamp: Long): String`

格式化时间戳为可读字符串。

```kotlin
val timeStr = timeSyncHelper.formatTimestamp(1718688000)
println(timeStr) // "2024-06-18 16:00:00"
```

**参数**：
- `timestamp`: Unix 10 位时间戳（秒）

**返回值**：格式化的时间字符串 `yyyy-MM-dd HH:mm:ss`

---

## 7z 解压 API

### SevenZipHelper

7z 文件解压工具。

#### 方法

##### `extractFirmware(archiveFile: File, outputDir: File): ExtractResult`

从 7z 文件中提取固件。

**安全特性**：Zip Slip 防护

**返回值**：
```kotlin
data class ExtractResult(
    val firmwareFile: File?,      // 找到的 .bin 文件
    val extractedFiles: List<String>  // 所有解压的文件名
)
```

**示例**：
```kotlin
val result = SevenZipHelper.extractFirmware(archiveFile, outputDir)
result.firmwareFile?.let {
    println("找到固件: ${it.name}")
}
```

---

##### `is7zFile(file: File): Boolean`

检查文件是否为有效的 7z 文件。

**检测方式**：检查文件头签名 `37 7A BC AF 27 1C`

---

## WiFi API

### WifiScanner

WiFi 网络扫描器。

#### 方法

##### `scan24GHz(): Flow<List<WifiNetwork>>`

扫描 2.4GHz WiFi 网络。

**特性**：
- 过滤隐藏网络
- 按信号强度排序
- 自动去重

**返回值**：
```kotlin
data class WifiNetwork(
    val ssid: String,
    val rssi: Int,
    val frequency: Int,
    val capabilities: String
) {
    val signalLevel: Int  // 0-4
    val isSecure: Boolean
}
```

**信号强度定义**：

| Level | RSSI 范围 | 描述 |
|-------|-----------|------|
| 4 | ≥ -50 dBm | 强 |
| 3 | -50 ~ -60 dBm | 较强 |
| 2 | -60 ~ -70 dBm | 中等 |
| 1 | -70 ~ -80 dBm | 较弱 |
| 0 | < -80 dBm | 弱 |

---

## 权限 API

### PermissionHelper

版本感知权限管理。

#### `getRequiredPermissions(): List<String>`

获取当前 Android 版本所需的权限列表。

**Android 13+ (API 33)**：
- `BLUETOOTH_SCAN`
- `BLUETOOTH_CONNECT`
- `NEARBY_WIFI_DEVICES`
- `POST_NOTIFICATIONS`

**Android 12 (API 31)**：
- `BLUETOOTH_SCAN`
- `BLUETOOTH_CONNECT`
- `ACCESS_FINE_LOCATION`

**Android 11 及以下**：
- `BLUETOOTH`
- `BLUETOOTH_ADMIN`
- `ACCESS_FINE_LOCATION`

---

## 数据模型参考

### BLE 数据

```kotlin
data class BleDevice(
    val name: String,
    val address: String,
    val rssi: Int
)

sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Connecting : ConnectionState()
    data class Connected(val device: BluetoothDevice) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}
```

### PPG 数据

```kotlin
data class PpgData(
    val hr: Int = 0,           // 心率 (BPM)
    val spo2: Int = 0,         // 血氧 (%)
    val pi: Int = 0,           // 灌注指数 (%)
    val quality: Int = 0,      // 信号质量 (0-100)
    val redValues: List<Float> = emptyList(),  // 红光波形
    val irValues: List<Float> = emptyList()    // 红外波形
)
```

### 设备状态

```kotlin
data class DeviceStatus(
    val battery: BatteryInfo? = null,
    val firmwareVersion: String = "",
    val sdFreeMb: Int = 0,
    val isOnline: Boolean = false
)

data class BatteryInfo(
    val soc: Int,      // 电量百分比
    val voltage: Int   // 电压 (mV)
)
```

### OTA 状态

```kotlin
data class OtaState(
    val deviceVersion: String = "",
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
```

### WiFi 状态

```kotlin
data class WifiProvisionState(
    val isScanning: Boolean = false,
    val networks: List<WifiNetwork> = emptyList(),
    val selectedNetwork: WifiNetwork? = null,
    val password: String = "",
    val isConnecting: Boolean = false,
    val connectionResult: ConnectionResult? = null,
    val error: String? = null
)
```
