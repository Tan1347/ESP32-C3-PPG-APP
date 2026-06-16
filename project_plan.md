# Android PPG 数据管理 App — 项目计划书

## 1. 项目概述

Android 手机端应用程序，用于与 ESP32-C3 PPG 设备通信，实现实时数据查看、历史数据下载、波形分析和数据导出。

- **运行平台**：Android 11 (API 30) ~ Android 16 (API 36)
- **目标 SDK**：Android 16 (API 36)
- **开发语言**：Kotlin
- **UI 框架**：Jetpack Compose (Material 3)
- **通信方式**：BLE（实时数据）+ WiFi HTTP（批量下载）

## 2. 技术栈选型

| 层级 | 方案 | 说明 |
|------|------|------|
| **语言** | Kotlin | Android 官方推荐 |
| **UI** | Jetpack Compose + Material 3 | 声明式 UI，原生 Android |
| **架构** | MVVM + Clean Architecture | ViewModel + UseCase + Repository |
| **BLE** | Android 原生 BLE API (BluetoothLeScanner) | 扫描、连接、GATT 读写、Notify |
| **HTTP** | Retrofit + OkHttp | WiFi AP 文件下载 |
| **数据处理** | Kotlin Coroutines + Flow | 异步数据流、实时 UI 更新 |
| **本地存储** | Room (SQLite) | 下载文件元数据、用户配置 |
| **图表绑图** | Vico / MPAndroidChart / Canvas 自绘 | PPG 波形实时绘制 |
| **DI** | Hilt | 依赖注入 |
| **序列化** | Kotlinx Serialization | JSON 解析 |

## 3. 功能架构

```
┌─────────────────────────────────────────────────────┐
│                   PPG Android App                     │
│                                                       │
│  ┌─────────────────────────────────────────────────┐ │
│  │              Compose UI 层                       │ │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────────┐  │ │
│  │  │ 设备连接 │  │ 实时监控 │  │  数据管理    │  │ │
│  │  │ Screen   │  │ Screen   │  │  Screen      │  │ │
│  │  └──────────┘  └──────────┘  └──────────────┘  │ │
│  └─────────────────────────────────────────────────┘ │
│                                                       │
│  ┌─────────────────────────────────────────────────┐ │
│  │              ViewModel 层                        │ │
│  │  DeviceVM  │  MonitorVM  │  DataVM  │  ConfigVM │ │
│  └─────────────────────────────────────────────────┘ │
│                                                       │
│  ┌─────────────────────────────────────────────────┐ │
│  │              Domain 层 (UseCases)                 │ │
│  │  ScanDevices │ StreamPPG │ DownloadFiles │ ...   │ │
│  └─────────────────────────────────────────────────┘ │
│                                                       │
│  ┌─────────────────────────────────────────────────┐ │
│  │              Data 层 (Repositories)               │ │
│  │  BleRepo │ HttpRepo │ FileRepo │ ConfigRepo      │ │
│  └─────────────────────────────────────────────────┘ │
│                                                       │
│  ┌──────────┐  ┌──────────┐  ┌───────────────────┐  │
│  │ Android  │  │ Retrofit │  │ Room DB           │  │
│  │ BLE API  │  │ OkHttp   │  │ (配置/元数据)     │  │
│  └──────────┘  └──────────┘  └───────────────────┘  │
└─────────────────────────────────────────────────────┘
```

## 4. 功能模块详细设计

### 4.1 设备连接 Screen

**BLE 扫描与连接**：
- 使用 `BluetoothLeScanner` 扫描，按设备名 `PPG-*` 过滤
- 显示设备列表：名称、MAC 地址、RSSI 信号强度
- 点击连接，自动配对（BLE Bond）
- 连接后读取设备信息（固件版本、电池电量）
- 支持自动重连（后台 Service）

**权限处理**：
- `BLUETOOTH_SCAN`、`BLUETOOTH_CONNECT`（Android 12+）
- `ACCESS_FINE_LOCATION`（Android 11-12 扫描需要）
- 运行时权限请求 + 引导用户开启位置服务

### 4.2 实时监控 Screen

| 显示项 | 更新频率 | 数据来源 |
|--------|----------|----------|
| PPG 波形（红光 + 红外） | 实时滚动 | BLE Notify |
| 心率 (BPM) | 1Hz | BLE Notify |
| 血氧 (SpO2 %) | 1Hz | BLE Notify |
| 灌注指数 (PI %) | 1Hz | BLE Notify |
| 信号质量 | 1Hz | BLE Notify |
| 电池电量 | 30s | BLE Read |

**波形显示要求**：
- Canvas 自绘或 Vico 图表库
- 滚动波形，时间窗口 5~30 秒可调（Slider）
- 双通道：红光（红色）+ 红外光（蓝色）
- 支持双指缩放、暂停/恢复
- 截图保存到相册

**数据卡片**：
- Material 3 Card 展示 HR / SpO2 / PI
- 颜色编码：SpO2 < 90% 红色警告，90-94% 黄色，≥ 95% 绿色
- 信号质量差时显示提示

### 4.3 数据管理 Screen

**文件浏览**：
- 通过 BLE 命令获取 TF 卡文件列表（JSON）
- LazyColumn 显示：文件名、大小、日期、时长
- 支持下拉刷新

**数据下载**：
- BLE 获取设备 IP 地址（设备已连接路由器 WiFi）
- 确认手机与设备在同一局域网
- Retrofit HTTP 下载文件（通过设备 IP）
- 下载进度通知（NotificationManager）
- 保存到 `Downloads/PPG/` 目录
- 支持批量选择下载

**数据查看**：
- 加载已下载的 CSV 文件
- 列表展示 + 统计摘要
- 点击进入波形回放

### 4.4 数据分析

**离线分析功能**：
- 解析 CSV 文件（kotlinx.serialization 或手动解析）
- 统计指标：平均 HR、平均 SpO2、最大/最小值、标准差
- SpO2 事件检测：< 90% 持续 > 10 秒
- 基础 HRV 分析（RR 间期标准差）

**分享与导出**：
- 导出统计摘要为 CSV / PDF
- Android Share Sheet 分享文件
- 发送到邮件 / 微信 / 云盘

### 4.5 设置 Screen

- 设备配置（通过 BLE 写入）：
  - 采样率（50/100/200 Hz）
  - LED 电流
  - 测量模式
  - 设备时间同步（手机 → 设备 RTC）
  - WiFi 配网（扫描附近路由器，BLE 发送凭据给设备）
  - 已保存 WiFi 列表管理（查看/删除/修改密码/调整优先级）
  - WiFi 连接状态显示（当前 SSID、IP、信号强度）
  - 固件版本显示 + OTA 升级入口
- App 设置：
  - 波形显示偏好
  - 通知开关
  - 自动连接开关
  - 存储路径
  - 深色/浅色主题

## 5. UI 页面流程

```
App 启动
    │
    ▼
┌─────────────┐     未连接      ┌──────────────┐
│  设备连接页  │ ──────────────► │  扫描设备列表  │
│  (首页)      │                 │  点击连接      │
└──────┬──────┘                 └──────────────┘
       │ 已连接
       ▼
┌─────────────────────────────────────────────┐
│              Bottom Navigation                │
│  ┌──────────┐ ┌──────────┐ ┌──────────────┐ │
│  │ 实时监控 │ │ 数据管理 │ │    设置      │ │
│  │ (默认)   │ │          │ │              │ │
│  └──────────┘ └──────────┘ └──────────────┘ │
│                                               │
│  ┌─────────────────────────────────────────┐ │
│  │           页面内容区域                    │ │
│  └─────────────────────────────────────────┘ │
└─────────────────────────────────────────────┘
```

**Compose Navigation 路由**：
- `device_scan` — 扫描连接
- `monitor` — 实时监控（主页面）
- `data_list` — 文件列表
- `data_detail` — 单文件分析
- `settings` — 设置
- `wifi_provision` — WiFi 配网（扫描路由器、发送凭据、管理已保存列表）
- `ota_upgrade` — 固件 OTA 升级（上传 .bin）
- `permission` — 权限引导

## 6. Android 关键实现

### 6.1 BLE 通信封装

```kotlin
// 核心类设计
class BleManager(context: Context) {
    // 扫描
    fun scan(): Flow<BluetoothDevice>
    // 连接
    fun connect(device: BluetoothDevice): Flow<ConnectionState>
    // 读特征值
    suspend fun readCharacteristic(uuid: UUID): ByteArray
    // 写特征值
    suspend fun writeCharacteristic(uuid: UUID, data: ByteArray)
    // 订阅 Notify
    fun subscribeNotify(uuid: UUID): Flow<ByteArray>
}

// GATT UUID 常量
object PpgGatt {
    val SERVICE_DEVICE_INFO = UUID.fromString("0000180a-...")
    val SERVICE_REALTIME    = UUID.fromString("0000ffe0-...")
    val SERVICE_CONTROL     = UUID.fromString("0000ffe1-...")
    val CHAR_HR             = UUID.fromString("0000ffe2-...")
    val CHAR_SPO2           = UUID.fromString("0000ffe3-...")
    // ... 与 ESP32-C3 固件协商定义
}
```

### 6.2 BLE 前台服务

```kotlin
// 保持后台 BLE 连接
@AndroidEntryPoint
class BleForegroundService : LifecycleService() {
    // Foreground Notification (Android 12+ 必须)
    // 管理 BLE 连接生命周期
    // 推送实时数据到 ViewModel (SharedFlow)
}
```

### 6.3 Android 版本兼容

| 版本 | 差异处理 |
|------|----------|
| Android 11 (API 30) | 需要 `ACCESS_FINE_LOCATION` 才能 BLE 扫描 |
| Android 12 (API 31) | 新增 `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` 权限 |
| Android 13 (API 33) | 通知需要 `POST_NOTIFICATIONS` 权限 |
| Android 14 (API 34) | 前台服务类型声明 (`foregroundServiceType`) |
| Android 15 (API 35) | 边对边强制、预测性返回手势 |
| Android 16 (API 36) | 目标 SDK，最新行为变更适配 |

## 7. 项目结构

```
app/
├── 项目计划书.md
├── build.gradle.kts
├── settings.gradle.kts
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/ppgtool/app/
│       │   ├── PpgApplication.kt          # Application (Hilt)
│       │   ├── MainActivity.kt
│       │   │
│       │   ├── ui/                         # Compose UI
│       │   │   ├── theme/
│       │   │   │   ├── Theme.kt
│       │   │   │   ├── Color.kt
│       │   │   │   └── Type.kt
│       │   │   ├── navigation/
│       │   │   │   └── AppNavigation.kt
│       │   │   ├── screens/
│       │   │   │   ├── DeviceScreen.kt     # 扫描/连接
│       │   │   │   ├── MonitorScreen.kt    # 实时监控
│       │   │   │   ├── DataScreen.kt       # 数据管理
│       │   │   │   ├── AnalysisScreen.kt   # 数据分析
│       │   │   │   ├── SettingsScreen.kt   # 设置
│       │   │   │   ├── WifiProvisionScreen.kt  # WiFi 配网
│       │   │   │   └── OtaScreen.kt        # OTA 升级
│       │   │   └── components/
│       │   │       ├── PpgWaveform.kt      # 波形 Canvas 组件
│       │   │       ├── MetricCard.kt       # HR/SpO2 卡片
│       │   │       ├── QualityIndicator.kt # 信号质量指示
│       │   │       └── FileListItem.kt     # 文件列表项
│       │   │
│       │   ├── viewmodel/                  # ViewModels
│       │   │   ├── DeviceViewModel.kt
│       │   │   ├── MonitorViewModel.kt
│       │   │   ├── DataViewModel.kt
│       │   │   ├── SettingsViewModel.kt
│       │   │   ├── WifiProvisionViewModel.kt
│       │   │   └── OtaViewModel.kt
│       │   │
│       │   ├── domain/                     # UseCases
│       │   │   ├── ScanDevicesUseCase.kt
│       │   │   ├── StreamPpgDataUseCase.kt
│       │   │   ├── DownloadFileUseCase.kt
│       │   │   ├── AnalyzeDataUseCase.kt
│       │   │   ├── ProvisionWifiUseCase.kt  # WiFi 配网
│       │   │   └── OtaUpgradeUseCase.kt     # OTA 升级
│       │   │
│       │   ├── data/                       # Repositories
│       │   │   ├── ble/
│       │   │   │   ├── BleManager.kt
│       │   │   │   ├── BleRepository.kt
│       │   │   │   └── PpgGattProfile.kt
│       │   │   ├── network/
│       │   │   │   ├── HttpRepository.kt
│       │   │   │   ├── DeviceApi.kt        # Retrofit 接口
│       │   │   │   └── OtaUploader.kt      # OTA 固件上传
│       │   │   ├── local/
│       │   │   │   ├── AppDatabase.kt      # Room
│       │   │   │   ├── FileMetadataDao.kt
│       │   │   │   └── CsvParser.kt
│       │   │   ├── wifi/
│       │   │   │   └── WifiScanner.kt      # 扫描路由器
│       │   │   └── ConfigRepository.kt
│       │   │
│       │   ├── service/
│       │   │   └── BleForegroundService.kt  # 后台 BLE 服务
│       │   │
│       │   └── util/
│       │       ├── PermissionHelper.kt      # 权限工具
│       │       ├── PpgAnalyzer.kt           # 统计分析
│       │       └── Extensions.kt
│       │
│       └── res/
│           ├── values/
│           └── drawable/
└── gradle/
```

## 8. 开发阶段划分

### Phase 1：项目基础（1 周）
- [ ] Android 项目初始化（Kotlin, Compose, Hilt, Material 3）
- [ ] Navigation 路由框架
- [ ] Bottom Navigation 三页签布局
- [ ] 主题（深色/浅色）+ 全局样式
- [ ] 权限请求封装（BLE + Location + Notification）

### Phase 2：BLE 通信层（2 周）
- [ ] BleManager 封装（扫描、连接、GATT 读写、Notify）
- [ ] BLE 前台 Service（保持后台连接）
- [ ] PpgGattProfile 常量定义（与 ESP32-C3 固件协商）
- [ ] 自动重连机制
- [ ] 连接状态 Flow 暴露给 UI

### Phase 3：实时监控页面（2 周）
- [ ] MonitorViewModel（接收 BLE 数据流）
- [ ] PPG 波形 Canvas 自绘组件（双通道滚动）
- [ ] HR / SpO2 / PI 数值卡片（Material 3 Card）
- [ ] 信号质量指示器
- [ ] 波形暂停/恢复、时间窗口调节
- [ ] 截图保存功能

### Phase 4：设备连接页面（1 周）
- [ ] DeviceScreen（扫描列表 + 连接状态）
- [ ] 设备信息展示（固件版本、电池电量）
- [ ] 自动重连 UI 反馈
- [ ] 连接引导（未开蓝牙/位置服务提示）

### Phase 5：WiFi 配网与数据管理（1-2 周）
- [ ] WiFi 配网页面（扫描路由器列表、输入密码、BLE 发送给设备）
- [ ] WiFi 状态显示（已连接 SSID、IP 地址、信号强度）
- [ ] BLE 获取 TF 卡文件列表
- [ ] Retrofit HTTP 文件下载（通过设备 IP，同局域网）
- [ ] 下载进度通知
- [ ] 已下载文件列表（Room 存储元数据）
- [ ] CSV 文件解析

### Phase 6：OTA 与分析设置（1-2 周）
- [ ] OTA 升级页面（选择 .bin 固件、上传进度、升级结果）
- [ ] 固件版本查询与显示
- [ ] 离线数据波形查看
- [ ] 统计分析（均值、极值、SpO2 事件检测）
- [ ] 分享/导出（Share Sheet）
- [ ] 设置页面（设备配置 + App 配置）
- [ ] 设备时间同步

### Phase 7：测试与发布（1 周）
- [ ] Android 11 ~ 16 兼容性测试
- [ ] BLE 兼容性测试（不同手机芯片：高通/联发科/三星）
- [ ] 与 ESP32-C3 设备联调
- [ ] 性能优化（波形绘制帧率、内存）
- [ ] ProGuard / R8 混淆配置
- [ ] 签名打包发布

## 9. 关键依赖

```kotlin
// build.gradle.kts (Version Catalog 推荐)
dependencies {
    // Compose BOM
    implementation(platform("androidx.compose:compose-bom:2025.xx"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.10.x")
    implementation("androidx.navigation:navigation-compose:2.8.x")

    // Lifecycle + ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.x")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.x")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.51.x")
    kapt("com.google.dagger:hilt-android-compiler:2.51.x")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.x")

    // Room
    implementation("androidx.room:room-runtime:2.6.x")
    implementation("androidx.room:room-ktx:2.6.x")
    kapt("androidx.room:room-compiler:2.6.x")

    // Retrofit + OkHttp
    implementation("com.squareup.retrofit2:retrofit:2.11.x")
    implementation("com.squareup.okhttp3:okhttp:4.12.x")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.x")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.x")
}
```

## 10. 风险与待确认项

| 风险 | 影响 | 应对 |
|------|------|------|
| BLE 在部分国产手机上被厂商魔改 | 连接不稳定 | 抓 HCI log，针对主流品牌适配 |
| Android 12+ BLE 权限模型变化 | 扫描/连接失败 | 运行时权限 + 引导页 |
| 后台 BLE 被系统杀死 | 断连 | Foreground Service + 电池白名单引导 |
| 手机与设备不在同一 WiFi | HTTP 通信失败 | BLE 检测并提示用户切换网络 |
| OTA 固件上传中断 | 升级失败 | 断点续传 / 重试机制，ESP32 回滚保护 |
| 实时波形 100Hz 绘制性能 | 掉帧 | Canvas 硬件加速 + 降采样显示 |
| ESP32-C3 GATT 协议未定 | 开发阻塞 | 先用 Mock 数据开发 UI |
