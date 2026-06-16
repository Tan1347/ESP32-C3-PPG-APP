# PPG Monitor Android App

ESP32-C3 PPG 血氧仪配套 Android 应用，用于实时监控、数据管理和设备配置。

## 功能概述

- **设备连接**：BLE 扫描、连接、配对 ESP32-C3 PPG 设备
- **实时监控**：PPG 波形显示、心率/血氧/灌注指数实时数据
- **数据管理**：通过 WiFi HTTP 下载 TF 卡中的采集数据
- **WiFi 配网**：通过 BLE 为设备配置 WiFi 连接
- **OTA 升级**：通过 WiFi 上传新固件到设备
- **设备设置**：同步时间、调整参数、查看状态

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

## 环境要求

- **Android Studio**：Ladybug (2024.2) 或更新版本
- **JDK**：17
- **Android SDK**：API 30 ~ 35
- **Gradle**：8.9
- **操作系统**：Windows 11 / macOS / Linux

## 编译步骤

### 1. 导入项目

1. 打开 Android Studio
2. 选择 `File → Open`
3. 选择 `app/` 目录
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
cd app
./gradlew assembleDebug    # Debug 版本
./gradlew assembleRelease  # Release 版本
```

APK 输出路径：`app/app/build/outputs/apk/`

### 4. 安装到手机

```bash
adb install app/app/build/outputs/apk/debug/app-debug.apk
```

## 项目结构

```
app/
├── build.gradle.kts              # 项目级构建配置
├── settings.gradle.kts           # 项目设置
├── gradle.properties             # Gradle 属性
├── gradle/wrapper/               # Gradle Wrapper
├── 项目计划书.md                   # 详细设计文档
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
        │   │   │   ├── MonitorScreen.kt     # 实时监控
        │   │   │   ├── DataScreen.kt        # 数据管理
        │   │   │   ├── SettingsScreen.kt    # 设置
        │   │   │   ├── WifiProvisionScreen.kt # WiFi 配网
        │   │   │   └── OtaScreen.kt         # OTA 升级
        │   │   └── components/          # 通用组件
        │   ├── viewmodel/               # ViewModel 层
        │   ├── data/
        │   │   ├── ble/                 # BLE 通信
        │   │   │   ├── BleManager.kt        # BLE 管理器
        │   │   │   └── PpgGattProfile.kt    # GATT UUID 定义
        │   │   ├── network/             # HTTP 通信
        │   │   │   ├── DeviceApi.kt         # Retrofit 接口
        │   │   │   └── HttpRepository.kt    # HTTP 仓库
        │   │   └── local/               # 本地存储
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
| 进入 OTA | 0x20 | 开启 OTA 模式 |
| 查询版本 | 0x21 | 获取固件版本 |

## HTTP API

设备连接 WiFi 后，通过局域网 IP 访问：

| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/files` | GET | TF 卡文件列表 |
| `/api/download?file=x` | GET | 下载文件 |
| `/api/status` | GET | 设备状态 |
| `/api/ota` | GET | OTA 页面 |
| `/api/ota` | POST | 上传固件 |
| `/api/logs` | GET | 日志列表 |
| `/api/shutdown` | POST | 关闭 WiFi |

## 权限说明

| 权限 | 用途 | Android 版本 |
|------|------|-------------|
| BLUETOOTH_SCAN | BLE 扫描 | 12+ |
| BLUETOOTH_CONNECT | BLE 连接 | 12+ |
| ACCESS_FINE_LOCATION | BLE 扫描（位置服务） | 11-12 |
| POST_NOTIFICATIONS | 通知权限 | 13+ |
| INTERNET | HTTP 通信 | 全版本 |
| FOREGROUND_SERVICE | 后台 BLE 服务 | 全版本 |

## 兼容性

- **最低版本**：Android 11 (API 30)
- **目标版本**：Android 15 (API 35)
- **BLE 芯片兼容**：高通、联发科、三星（需实际测试）

## 待完成功能

- [ ] Room 数据库实现（文件元数据存储）
- [ ] CSV 文件解析和波形回放
- [ ] 数据统计分析（均值、极值、SpO2 事件检测）
- [ ] 数据导出（CSV/PDF）
- [ ] 截图保存功能
- [ ] 自动重连机制
- [ ] 深色/浅色主题切换
- [ ] 多语言支持

## 与 ESP32-C3 固件联调

1. 烧录 ESP32-C3 固件（参考 `esp32c3/` 目录）
2. 设备开机，BLE 广播 `PPG-Monitor`
3. 手机 App 扫描并连接
4. 实时监控页面查看 PPG 数据
5. 通过设置页面配置 WiFi
6. WiFi 连接后可下载 TF 卡数据

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

## 开发者

本项目是 PPG 信号采集工具的 Android 客户端，与 ESP32-C3 固件配合使用。

详细设计文档请参考 `项目计划书.md`。
