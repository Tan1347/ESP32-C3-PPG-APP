# PPG Monitor 架构设计文档

## 架构概览

```
┌─────────────────────────────────────────────────────────────┐
│                      UI Layer (Compose)                      │
├─────────────────────────────────────────────────────────────┤
│  MonitorScreen  │  DataScreen  │  SettingsScreen  │  OtaScreen  │
└────────┬────────┴──────┬───────┴────────┬─────────┴──────┬──────┘
         │               │                │                │
         ▼               ▼                ▼                ▼
┌─────────────────────────────────────────────────────────────┐
│                    ViewModel Layer (Hilt)                     │
├─────────────────────────────────────────────────────────────┤
│ MonitorVM │ OtaVM │ WifiVM │ SettingsVM │ DeviceVM          │
└────────┬───┴───┬───┴───┬────┴─────┬──────┴──────┬──────────┘
         │       │       │          │             │
         ▼       ▼       ▼          ▼             ▼
┌─────────────────────────────────────────────────────────────┐
│                    Data Layer (Repository)                    │
├─────────────────────────────────────────────────────────────┤
│  BleManager  │  HttpRepository  │  WifiScanner  │  UpdateChecker  │
└──────┬───────┴────────┬─────────┴───────┬───────┴────────┬────────┘
       │                │                 │                │
       ▼                ▼                 ▼                ▼
┌──────────┐    ┌──────────────┐   ┌──────────────┐  ┌──────────────┐
│ BLE GATT │    │ Retrofit/    │   │ Android      │  │ GitHub       │
│ Service  │    │ OkHttp       │   │ WifiManager  │  │ API          │
└──────────┘    └──────────────┘   └──────────────┘  └──────────────┘
```

## 核心模块

### 1. BLE 通信模块 (`data/ble/`)

**职责**：与 ESP32-C3 设备建立 BLE 连接，收发实时数据和控制命令。

```kotlin
// BleManager.kt - 核心类
@Singleton
class BleManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    // 连接状态
    val connectionState: StateFlow<ConnectionState>
    
    // 实时数据流 (HR, SpO2, PI, Quality)
    val liveData: SharedFlow<ByteArray>
    
    // 设备状态流 (电量, 固件版本)
    val statusData: SharedFlow<ByteArray>
    
    // 扫描设备
    fun scan(): Flow<BleDevice>
    
    // 连接设备 (等待 services 发现完成)
    suspend fun connect(device: BluetoothDevice): Boolean
    
    // 发送命令
    suspend fun writeCommand(command: ByteArray): Boolean
    
    // 读取特征值 (异步等待回调)
    suspend fun readCharacteristic(uuid: UUID): ByteArray?
}
```

**线程安全设计**：
- `connect()` 使用 `suspendCancellableCoroutine`，在 `onServicesDiscovered` 后才返回
- `readCharacteristic()` 使用 `@Volatile` 标记的 pending continuation 等待异步回调

### 2. HTTP 通信模块 (`data/network/`)

**职责**：通过 WiFi 与设备通信，处理文件传输、OTA 升级、GitHub 下载。

```kotlin
// HttpRepository.kt - 核心类
@Singleton
class HttpRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    // 设备 API 客户端 (HTTP 明文)
    private var api: DeviceApi? = null
    
    // GitHub 客户端 (带自定义 DNS)
    private val githubClient: OkHttpClient
    
    // 设备通信
    suspend fun getFileList(): List<String>
    suspend fun downloadFile(filename: String, onProgress: ((Int) -> Unit)?): File?
    suspend fun getDeviceStatus(): DeviceStatusResponse?
    suspend fun uploadFirmware(file: File, onProgress: ((Int) -> Unit)?): Boolean
    
    // GitHub 下载 (使用优选 IP)
    suspend fun downloadFromGitHub(url: String, outputFile: File, onProgress: ((Int) -> Unit)?): Boolean
}
```

**GitHub 加速策略**：
```
1. 镜像优先: ghfast.top → ghproxy.net → github.moeyy.xyz
2. 远程 Hosts: raw.hellogithub.com/hosts (社区维护)
3. 优选 DNS: OkHttp 自定义 Dns 解析器
4. 本地缓存: SharedPreferences (24小时 TTL)
```

### 3. WiFi 扫描模块 (`data/wifi/`)

**职责**：扫描 2.4GHz WiFi 网络，用于设备配网。

```kotlin
// WifiScanner.kt
@Singleton
class WifiScanner @Inject constructor(
    @ApplicationContext private val context: Context
) {
    // 扫描 2.4GHz 网络
    fun scan24GHz(): Flow<List<WifiNetwork>>
    
    // 检查 WiFi 状态
    fun isWifiEnabled(): Boolean
}

// WifiNetwork 数据模型
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

### 4. OTA 升级模块 (`data/network/SevenZipHelper.kt`)

**职责**：从 GitHub Release 下载 7z 固件包，解压后上传到设备。

### 5. 时间同步模块 (`data/network/TimeSyncHelper.kt`)

**职责**：从网络获取时间并同步到设备。

```kotlin
// TimeSyncHelper.kt
@Singleton
class TimeSyncHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    // 获取当前 Unix 10 位时间戳（秒），UTC+8
    suspend fun getTimestamp(): Long
    
    // 格式化时间戳
    fun formatTimestamp(timestamp: Long): String
}
```

**获取策略**：
```
1. 网络时间: https://ip.ddnspod.com/timestamp (Unix 13 位，去除末 3 位)
2. 本地时间: System.currentTimeMillis() + UTC+8 偏移
```

```kotlin
// SevenZipHelper.kt - 7z 解压
object SevenZipHelper {
    // 解压固件 (带 Zip Slip 防护)
    fun extractFirmware(archiveFile: File, outputDir: File): ExtractResult
    
    // 验证 7z 文件
    fun is7zFile(file: File): Boolean
}
```

**安全防护**：
```kotlin
// Zip Slip 防护
val canonicalOutputPath = outputFile.canonicalPath
val canonicalOutputDir = outputDir.canonicalPath + File.separator
if (!canonicalOutputPath.startsWith(canonicalOutputDir)) {
    throw SecurityException("Zip Slip 检测: ${entry.name}")
}
```

## BLE 协议规范

### GATT 服务定义

| UUID | 名称 | 属性 | 说明 |
|------|------|------|------|
| `0000fff0-0000-1000-8000-00805f9b34fb` | Service | - | 主服务 |
| `0000fff1-0000-1000-8000-00805f9b34fb` | Status | Read/Notify | 设备状态 |
| `0000fff2-0000-1000-8000-00805f9b34fb` | Live Data | Notify | 实时数据 |
| `0000fff3-0000-1000-8000-00805f9b34fb` | Command | Write | 控制命令 |
| `0000fff4-0000-1000-8000-00805f9b34fb` | File List | Read | 文件列表 |

### 数据格式

**实时数据 (Live Data)**：
```
Byte 0-1: HR (16-bit big-endian)
Byte 2:   SpO2 (0-100%)
Byte 3:   PI (灌注指数)
Byte 4:   Quality (信号质量 0-100)
```

**WiFi 凭据命令**：
```
[0x10][ssid_len_h][ssid_len_l][ssid...][pwd_len_h][pwd_len_l][pwd...]
```

**时间同步命令**：
```
[0x40][timestamp_byte3][timestamp_byte2][timestamp_byte1][timestamp_byte0]
```

## 状态管理

### ViewModel 状态流

```kotlin
// MonitorViewModel.kt
data class PpgData(
    val hr: Int = 0,
    val spo2: Int = 0,
    val pi: Int = 0,
    val quality: Int = 0,
    val redValues: List<Float> = emptyList(),
    val irValues: List<Float> = emptyList()
)

data class DeviceStatus(
    val battery: BatteryInfo? = null,
    val firmwareVersion: String = "",
    val sdFreeMb: Int = 0,
    val isOnline: Boolean = false
)
```

### 线程安全设计

```kotlin
// 使用 Mutex 保护共享状态
private val bufferMutex = Mutex()
private val redBuffer = mutableListOf<Float>()

init {
    viewModelScope.launch {
        bleManager.liveData.collect { data ->
            bufferMutex.withLock {
                redBuffer.add(hr.toFloat())
                if (redBuffer.size > 300) redBuffer.removeAt(0)
            }
        }
    }
}
```

## 安全设计

### 1. TLS 证书验证

```kotlin
// UpdateChecker.kt - 使用系统默认信任管理器
val trustManagerFactory = TrustManagerFactory.getInstance(
    TrustManagerFactory.getDefaultAlgorithm()
)
trustManagerFactory.init(null as KeyStore?)

val sslContext = SSLContext.getInstance("TLS")
sslContext.init(null, trustManagerFactory.trustManagers, SecureRandom())

// 启用主机名校验
val sslParameters = socket.sslParameters
sslParameters.endpointIdentificationAlgorithm = "HTTPS"
socket.sslParameters = sslParameters
```

### 2. Zip Slip 防护

```kotlin
// SevenZipHelper.kt
val canonicalOutputPath = outputFile.canonicalPath
val canonicalOutputDir = outputDir.canonicalPath + File.separator
if (!canonicalOutputPath.startsWith(canonicalOutputDir)) {
    throw SecurityException("Zip Slip 检测")
}
```

### 3. 权限管理

```kotlin
// PermissionHelper.kt - 版本感知权限检查
object PermissionHelper {
    fun getRequiredPermissions(): List<String> {
        return when {
            Build.VERSION.SDK_INT >= 33 -> listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.NEARBY_WIFI_DEVICES,
                Manifest.permission.POST_NOTIFICATIONS
            )
            Build.VERSION.SDK_INT >= 31 -> listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
            else -> listOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
    }
}
```

## 依赖注入 (Hilt)

```kotlin
// 模块绑定示例
@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {
    @Binds
    abstract fun bindBleManager(impl: BleManager): BleManager
}

// ViewModel 注入
@HiltViewModel
class MonitorViewModel @Inject constructor(
    private val bleManager: BleManager,
    private val httpRepository: HttpRepository
) : ViewModel()
```

## 缓存策略

### GitHub IP 缓存

```kotlin
// SharedPreferences 缓存 (24小时 TTL)
private const val CACHE_DURATION_MS = 24 * 60 * 60 * 1000L

// 缓存键
private const val KEY_CACHE = "hosts_cache"           // 本地测速结果
private const val KEY_REMOTE_CACHE = "remote_hosts_cache"  // 远程 hosts
private const val KEY_MIRROR_CACHE = "mirror_latency_cache" // 镜像延迟
```

### Gradle 构建缓存

```yaml
# GitHub Actions 缓存策略
key: gradle-${{ runner.os }}-${{ hashFiles('gradle/wrapper/gradle-wrapper.properties', 'gradle/libs.versions.toml') }}
restore-keys: |
  gradle-${{ runner.os }}-${{ hashFiles('gradle/wrapper/gradle-wrapper.properties') }}
  gradle-${{ runner.os }}-
```

## 性能优化

### 1. LazyColumn 优化

```kotlin
// 添加 key 提升重组效率
items(
    items = state.networks,
    key = { it.ssid }
) { network ->
    WifiNetworkItem(network = network)
}
```

### 2. 波形缓冲区

```kotlin
// 固定大小缓冲区，避免内存增长
private const val WAVEFORM_BUFFER_SIZE = 300

if (redBuffer.size > WAVEFORM_BUFFER_SIZE) {
    redBuffer.removeAt(0)
}
```

### 3. 状态轮询防重复

```kotlin
// 使用 Job 引用防止重复启动
private var pollingJob: Job? = null

fun startStatusPolling() {
    pollingJob?.cancel()
    pollingJob = viewModelScope.launch {
        while (true) {
            fetchDeviceStatus()
            delay(30_000)
        }
    }
}
```

## 错误处理

### 统一错误模式

```kotlin
// Sealed class 表示操作结果
sealed class OtaResult {
    data class Success(val message: String) : OtaResult()
    data class Error(val message: String) : OtaResult()
}

// ViewModel 中的错误处理
viewModelScope.launch {
    try {
        val result = repository.uploadFirmware(file)
        _state.update { it.copy(result = OtaResult.Success("上传成功")) }
    } catch (e: Exception) {
        _state.update { it.copy(result = OtaResult.Error(e.message ?: "未知错误")) }
    }
}
```
