# Android App 重构清单

> 待完成的重构项（建议功能稳定后执行）

---

## 代码级重构

### 1. `BleManager` 类 495 行，违反单一职责原则

- **文件**: `app/src/main/java/org/tan/ppgtoolapp/data/ble/BleManager.kt` 第 37-532 行
- **问题**: 扫描、连接、重连、读写、帧构建、通知、查询全在一个类
- **方案**: 拆分为 BleScanner、BleConnection、BleCommander 三个子模块，BleManager 作为门面

### 2. `connect()` 函数 122 行，内嵌匿名回调类

- **文件**: `BleManager.kt` 第 187-309 行
- **问题**: `BluetoothGattCallback` 内嵌在函数中，5 层嵌套
- **方案**: 提取为独立的 `BleGattCallback` 类，通过 lambda 回调

### 3. 重复的 `BluetoothGattCallback` 定义

- **文件**: `BleManager.kt` 第 193-215 行 (`connect()`)、第 436-454 行 (`startAutoReconnect()`)
- **问题**: 两处定义几乎相同的 `onConnectionStateChange` 逻辑
- **方案**: 使用共享的回调类（与问题 2 联动）

### 4. `OtaViewModel` 类 507 行

- **文件**: `OtaViewModel.kt`
- **问题**: 处理版本查询、Release 下载、本地文件、OTA 上传、仓库配置
- **方案**: 提取 `FirmwareDownloader` 类处理下载/解压逻辑（与 A1 联动）

---

## 架构级重构

### A1. OtaViewModel 严重违反 MVVM

- **文件**: `viewmodel/OtaViewModel.kt`
- **问题**: ViewModel 中包含 SharedPreferences 访问、JSON 解析、文件 I/O、7z 解压
- **方案**: 提取 `OtaRepository` 处理数据层逻辑

```kotlin
@Singleton
class OtaRepository @Inject constructor(@ApplicationContext context: Context) {
    fun getFirmwareRepo(): String { ... }
    fun saveFirmwareRepo(repo: String) { ... }
    suspend fun fetchReleases(repo: String): List<ReleaseInfo> { ... }
    suspend fun extractFirmware(source: File, dest: File): File? { ... }
}
```

### A2. HttpRepository 错误处理吞异常

- **文件**: `data/network/HttpRepository.kt`
- **问题**: 所有方法 catch Exception 返回 null，调用方无法区分失败原因
- **方案**: 使用 sealed Result 类型

```kotlin
sealed class DownloadResult {
    data class Success(val file: File, val crcMatch: Boolean) : DownloadResult()
    data class Error(val message: String, val cause: Exception? = null) : DownloadResult()
}
```

### A8. 通知逻辑在 ViewModel 中

- **文件**: `viewmodel/DataViewModel.kt` 第 81-124 行
- **问题**: NotificationHelper 调用散落在下载逻辑中
- **方案**: 提取 DownloadUseCase 或 DownloadService

### A9. 导航路由嵌入文件路径

- **文件**: `ui/navigation/AppNavigation.kt` 第 121-124 行
- **问题**: `"analysis/$filePath/$fileName"` 中文件路径可能包含破坏路由的字符
- **方案**: 使用数据库 ID 或共享 ViewModel 传递数据

### A10. 无接口抽象，不可测试

- **问题**: BleManager、HttpRepository、WifiScanner 都是具体类注入，无接口
- **方案**: 为 Repository 层定义接口，支持 mock 测试

---

## 优先级

| 优先级 | 问题 | 预估工作量 |
|--------|------|-----------|
| P0 | OtaViewModel 提取 OtaRepository | 3h |
| P0 | HttpRepository 错误处理 | 1h |
| P0 | BleManager 拆分 + connect 回调提取 | 4h |
| P2 | 通知逻辑移出 ViewModel | 1h |
| P2 | 导航路由类型安全 | 1h |
| P3 | 接口抽象 + 测试基础设施 | 4h |

**总计 8 项待做，预估约 14 小时**
