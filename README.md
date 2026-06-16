# PPG Monitor Android App

ESP32-C3 PPG 血氧仪配套 Android 应用，用于实时监控、数据管理和设备配置。

## 功能概述

### 核心功能
- **设备连接**：BLE 扫描、连接、配对 ESP32-C3 PPG 设备
- **实时监控**：PPG 波形显示、心率/血氧/灌注指数实时数据
- **设备状态**：电量百分比、固件版本、SD 卡剩余容量
- **数据管理**：通过 WiFi HTTP 下载 TF 卡中的采集数据

### 设备配置
- **WiFi 配网**：扫描 2.4GHz WiFi，通过 BLE 发送凭据到设备
- **OTA 升级**：从 GitHub Release 下载 7z 固件包，解压后上传到设备
- **本地 OTA**：支持选择本地 7z/bin 固件文件进行升级
- **时间同步**：网络时间同步到设备（UTC+8），无网络时使用本地时间

### 应用功能
- **应用自更新**：从 GitHub Release 检查并下载新版本
- **GitHub 加速**：内置优选 DNS 和镜像代理，优化国内下载速度

## 📚 文档

- [快速开始](docs/QUICKSTART.md) - 环境配置、构建运行、调试技巧
- [架构设计](docs/ARCHITECTURE.md) - 系统架构、模块设计、安全机制
- [API 参考](docs/API.md) - BLE/HTTP/WiFi API 详细文档
- [更新日志](docs/CHANGELOG.md) - 版本更新记录

## 技术栈

| 技术 | 说明 |
|------|------|
| Kotlin | 开发语言 |
| Jetpack Compose | UI 框架 (Material 3) |
| MVVM | 架构模式 |
| Hilt | 依赖注入 |
| Android BLE API | 蓝牙通信 |
| Retrofit + OkHttp | HTTP 通信 |
| Room | 本地数据存储 |
| Kotlin Coroutines | 异步编程 |
| Apache Commons Compress | 7z 文件解压 |

## 🔒 安全特性

本项目实施了多层安全防护：

- **TLS 证书验证**：使用系统默认 TrustManager + 主机名校验，防止 MITM 攻击
- **Zip Slip 防护**：7z 解压时校验路径，防止目录穿越漏洞
- **BLE 连接安全**：等待服务发现完成后再返回，避免竞态条件
- **线程安全**：使用 Mutex 保护共享数据，防止并发访问问题
- **权限管理**：版本感知权限请求，适配 Android 11-14

## 环境要求

- **Android Studio**：Ladybug (2024.2) 或更新版本
- **JDK**：17
- **Android SDK**：API 30 ~ 35
- **Gradle**：8.11.1
- **AGP**：8.8.2
- **操作系统**：Windows 11 / macOS / Linux

## 编译步骤

### 1. 导入项目

1. 打开 Android Studio
2. 选择 `File → Open`
3. 选择项目根目录
4. 等待 Gradle 同步完成

### 2. 配置 SDK

1. 确保已安装 Android SDK 35
2. `File → Project Structure → SDK Location` 确认 SDK 路径

### 3. 编译 APK

**Debug 版本**：
```
Build → Build Bundle(s) / APK(s) → Build APK(s)
```

**Release 版本**：
```
Build → Generate Signed Bundle / APK → APK
```

**命令行编译**：
```bash
./gradlew assembleDebug    # Debug 版本
./gradlew assembleRelease  # Release 版本
```

APK 输出路径：`app/build/outputs/apk/`

### 4. 安装到手机

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## 项目结构

```
ESP32-C3-PPG-APP/
├── build.gradle.kts              # 项目级构建配置
├── settings.gradle.kts           # 项目设置（含阿里云镜像）
├── gradle.properties             # Gradle 属性
├── gradle/
│   ├── libs.versions.toml        # Version Catalog（依赖版本管理）
│   └── wrapper/                  # Gradle Wrapper
├── .github/workflows/            # GitHub Actions CI/CD
├── release.keystore              # 签名文件（不提交到 Git）
├── README.md                     # 本文件
└── app/
    ├── build.gradle.kts          # 应用级构建配置
    ├── proguard-rules.pro        # 混淆规则
    └── src/main/
        ├── AndroidManifest.xml   # 权限和组件声明
        ├── java/com/ppgtool/app/
        │   ├── PpgApplication.kt        # Application (Hilt)
        │   ├── MainActivity.kt          # 主 Activity
        │   ├── ui/
        │   │   ├── theme/               # Material 3 主题
        │   │   ├── navigation/          # Compose Navigation
        │   │   ├── screens/             # 页面组件
        │   │   │   ├── DeviceScreen.kt      # 设备扫描/连接
        │   │   │   ├── MonitorScreen.kt     # 实时监控（含电量显示）
        │   │   │   ├── DataScreen.kt        # 数据管理
        │   │   │   ├── SettingsScreen.kt    # 设置
        │   │   │   ├── WifiProvisionScreen.kt # WiFi 配网（2.4GHz 扫描）
        │   │   │   └── OtaScreen.kt         # OTA 升级（支持 7z）
        │   │   └── components/          # 通用组件
        │   ├── viewmodel/               # ViewModel 层
        │   ├── data/
        │   │   ├── ble/                 # BLE 通信
        │   │   │   ├── BleManager.kt        # BLE 管理器
        │   │   │   └── PpgGattProfile.kt    # GATT UUID 定义
        │   │   ├── network/             # HTTP 通信
        │   │   │   ├── DeviceApi.kt         # Retrofit 接口
        │   │   │   ├── HttpRepository.kt    # HTTP 仓库（含 GitHub DNS）
        │   │   │   ├── UpdateChecker.kt     # 应用自更新
        │   │   │   ├── DownloadHelper.kt    # APK 安装辅助
        │   │   │   ├── SevenZipHelper.kt    # 7z 解压
        │   │   │   └── GitHubHostsHelper.kt # GitHub 优选 IP
        │   │   └── wifi/                # WiFi 扫描
        │   │       └── WifiScanner.kt       # 2.4GHz WiFi 扫描器
        │   ├── service/                 # 前台服务
        │   └── util/                    # 工具类
        └── res/                         # 资源文件
```

## BLE 协议

与 ESP32-C3 固件通信的 GATT 服务定义：

| 特征值 | UUID | 属性 | 说明 |
|--------|------|------|------|
| Status | 0xFFF1 | Read/Notify | 电量/状态/固件版本 |
| Live Data | 0xFFF2 | Notify | 实时 HR/SpO2/PI |
| Command | 0xFFF3 | Write | 控制命令 |
| File List | 0xFFF4 | Read | TF 卡文件列表 |

**命令定义**：

| 命令 | 字节 | 说明 |
|------|------|------|
| 开始测量 | 0x01 | 启动 PPG 采样 |
| 停止测量 | 0x02 | 停止采样 |
| 开启 WiFi | 0x03 | 连接路由器 |
| 添加 WiFi | 0x10 | 发送 WiFi 凭据 |
| 查询列表 | 0x14 | 获取已保存 WiFi |
| 时间同步 | 0x40 | 同步时间到设备 |
| 进入 OTA | 0x20 | 开启 OTA 模式 |
| 查询版本 | 0x21 | 获取固件版本 |

**WiFi 凭据格式**：
```
[0x10][ssid_len_h][ssid_len_l][ssid...][pwd_len_h][pwd_len_l][pwd...]
```

**时间同步格式**：
```
[0x40][timestamp_byte3][timestamp_byte2][timestamp_byte1][timestamp_byte0]
```

## HTTP API

设备连接 WiFi 后，通过局域网 IP 访问：

| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/files` | GET | TF 卡文件列表 |
| `/api/download?file=x` | GET | 下载文件 |
| `/api/status` | GET | 设备状态（电量/版本/SD卡） |
| `/api/ota` | POST | 上传固件 |
| `/api/logs` | GET | 日志列表 |
| `/api/logs/download?file=x` | GET | 下载日志 |
| `/api/shutdown` | POST | 关闭 WiFi |

## GitHub Actions CI/CD

项目配置了自动化构建发布流程：

- **触发方式**：每周二自动构建 / 手动触发
- **缓存策略**：Gradle 依赖 + 构建产物 + Android SDK 分层缓存
- **自动发布**：检测到新提交时自动创建 Release 并上传 APK
- **版本标签**：`v{version}-{short_sha}` 格式

**配置 Secrets**：

| Secret | 说明 |
|--------|------|
| `KEYSTORE_BASE64` | 签名文件 Base64 编码 |
| `KEYSTORE_STORE_PASSWORD` | Store 密码 |
| `KEYSTORE_KEY_ALIAS` | Key 别名 |
| `KEYSTORE_KEY_PASSWORD` | Key 密码 |

## 国内开发加速

### 镜像配置

项目已配置阿里云 Maven 镜像，加速依赖下载：

```kotlin
// settings.gradle.kts
maven { url = uri("https://maven.aliyun.com/repository/google") }
maven { url = uri("https://maven.aliyun.com/repository/central") }
maven { url = uri("https://maven.aliyun.com/repository/public") }
```

### Gradle Wrapper 镜像

```properties
# gradle-wrapper.properties
distributionUrl=https\://mirrors.cloud.tencent.com/gradle/gradle-8.11.1-bin.zip
```

## 权限说明

| 权限 | 用途 | Android 版本 |
|------|------|-------------|
| BLUETOOTH_SCAN | BLE 扫描 | 12+ |
| BLUETOOTH_CONNECT | BLE 连接 | 12+ |
| ACCESS_FINE_LOCATION | BLE 扫描（位置服务） | 11-12 |
| NEARBY_WIFI_DEVICES | WiFi 扫描 | 13+ |
| ACCESS_WIFI_STATE | WiFi 状态 | 全版本 |
| CHANGE_WIFI_STATE | WiFi 控制 | 全版本 |
| POST_NOTIFICATIONS | 通知权限 | 13+ |
| INTERNET | HTTP 通信 | 全版本 |
| FOREGROUND_SERVICE | 后台 BLE 服务 | 全版本 |

## 兼容性

- **最低版本**：Android 11 (API 30)
- **目标版本**：Android 15 (API 35)
- **BLE 芯片兼容**：高通、联发科、三星（需实际测试）

## 固件仓库

- **APP 仓库**：`https://github.com/kelven/PPGTool`
- **ESP32-C3 固件**：`https://github.com/Tan1347/ESP32-C3_PPG_Data_Collector`

## 与 ESP32-C3 固件联调

1. 烧录 ESP32-C3 固件（参考固件仓库）
2. 设备开机，BLE 广播 `PPG-Monitor`
3. 手机 App 扫描并连接
4. 实时监控页面查看 PPG 数据和电量
5. 通过设置页面配置 WiFi（扫描 2.4GHz 网络）
6. WiFi 连接后可下载 TF 卡数据
7. 通过 OTA 页面升级固件（支持从 GitHub 下载 7z 包）

## 常见问题

**Q: 扫描不到设备？**
- 确认设备已开机并广播
- 检查手机蓝牙是否开启
- 检查位置服务是否开启（Android 11-12）
- 检查蓝牙权限是否授予

**Q: 连接后收不到数据？**
- 确认 Notify 已启用
- 检查 GATT UUID 是否与固件一致
- 查看 Android Logcat BLE 相关日志

**Q: 无法下载文件？**
- 确认手机与设备在同一 WiFi 网络
- 检查设备 IP 地址是否正确
- 确认设备 WiFi 已开启

**Q: OTA 升级失败？**
- 确认 7z 包中包含 `.bin` 固件文件
- 检查设备电量是否充足
- 升级过程中不要断开连接

**Q: GitHub 下载慢？**
- App 内置了优选 DNS 和镜像代理
- 可在设置中手动触发 GitHub IP 测速

## 开发者

本项目是 PPG 信号采集工具的 Android 客户端，与 ESP32-C3 固件配合使用。

详细设计文档请参考 `项目计划书.md`。
