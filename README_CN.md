# PPG Monitor Android App

中文 | [English](README.md)

ESP32-C3 PPG 血氧仪配套 Android 应用，用于实时监控、数据管理和设备配置。

> **本文档同时作为固件开发参考**，包含完整的 BLE 协议、数据格式和通信规范。

## 功能概述

### 核心功能
- **设备连接**：BLE 扫描、连接、配对 ESP32-C3 PPG 设备
- **实时监控**：PPG 波形显示、心率/血氧/灌注指数实时数据
- **设备状态**：电池电量百分比、固件版本、SD 卡剩余容量
- **数据管理**：通过 WiFi HTTP 下载 TF 卡中的采集数据，Room 数据库管理已下载文件
- **离线分析**：解析二进制 PPG 记录，计算 HR/SpO2 统计（平均/最小/最大/标准差），检测 SpO2 去饱和事件（SpO2 < 90% 持续 > 10 秒）
- **批量下载**：一键下载设备所有文件，带进度通知
- **导出分享**：导出分析结果为 CSV 或 PDF，通过 Android 分享面板发送

### 设备配置
- **WiFi 配网**：扫描 2.4GHz WiFi + 手动添加隐藏网络，通过 BLE 发送凭据到设备
- **OTA 升级**：从 GitHub Release 下载 7z 固件包，解压后上传到设备
- **本地 OTA**：支持选择本地 7z/bin 固件文件
- **时间同步**：网络时间同步到设备（UTC+8），无网络时使用本地时间

### 应用功能
- **应用自更新**：从 GitHub Release 检查并下载新版本
- **GitHub 加速**：内置优选 DNS 和镜像代理

---

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

---

## UI 页面流程

```
App 启动
    │
    ▼
┌─────────────┐     未连接      ┌──────────────┐
│  设备连接页  │ ──────────────> │  扫描设备列表  │
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

**导航路由**：
- `device_scan` — 扫描连接
- `monitor` — 实时监控（主页面）
- `data_list` — 文件列表
- `settings` — 设置
- `wifi_provision` — WiFi 配网
- `ota_upgrade` — OTA 升级

---

## 固件开发参考

> 本章节为 ESP32-C3 固件开发者提供完整的协议规范。

### 1. BLE GATT 服务定义

固件需要实现以下 GATT 服务：

```
Service UUID: 0000fff0-0000-1000-8000-00805f9b34fb
├── Characteristic: 0000fff1 (Status)      - Read, Notify
├── Characteristic: 0000fff2 (Live Data)   - Notify
├── Characteristic: 0000fff3 (Command)     - Write, Notify
└── Characteristic: 0000fff4 (File List)   - Read
```

**CCC Descriptor**：`00002902`（用于启用 Notify）

### 2. BLE 广播配置

```
设备名前缀: "PPG-Monitor"（也支持 "ESP32"、"PPG" 前缀）
广播间隔: 200-220ms
```

### 3. 数据格式

#### 3.1 Status 特征值 (0xFFF1) - 20 字节

```
偏移  长度  字段       类型    说明
0     1     batt_pct   uint8   电池电量百分比 (0-100)
1-3   3     reserved   uint8   保留 (0x00)
4     1     connected  uint8   WiFi 状态 (0=未连接, 1=已连接)
5-19  15    version    char[]  固件版本 (UTF-8, 空字符填充)
```

#### 3.2 Live Data 特征值 (0xFFF2) - 5 字节

```
偏移  长度  字段     类型    说明
0-1   2     hr       uint16  心率 (BPM, 大端序)
2     1     spo2     uint8   血氧 (0-100%)
3     1     pi       uint8   灌注指数 (保留, 0x00)
4     1     quality  uint8   信号质量 (80=有效, 20=无效)
```

#### 3.3 帧协议

```
请求帧: [0xAA][CMD][LEN][DATA...][CHECKSUM]
响应帧: [0xAA][CMD][0x01][STATUS][CHECKSUM]
数据帧: [0xAA][CMD][LEN][DATA...][CHECKSUM]

CHECKSUM = SUM(CMD + LEN + DATA 各字节) & 0xFF
```

状态码：0=OK, 1=取消, 2=校验错误, 3=未知命令, 4=电量不足

### 4. 命令协议

#### 4.1 测量控制

| 命令 | 字节 | 说明 |
|------|------|------|
| 开始测量 | `[0x01]` | 启动 PPG 采样 |
| 停止测量 | `[0x02]` | 停止采样 |

#### 4.2 WiFi 管理

| 命令 | 字节 | 说明 |
|------|------|------|
| 开启 WiFi | `[0x03]` | 连接已保存路由器 |
| 添加 WiFi | `[0x10] + 数据` | 发送 WiFi 凭据 |
| WiFi 状态 | `[0x11]` | 查询连接状态 |
| 清除 WiFi | `[0x12]` | 清除所有已保存 WiFi |
| 删除 WiFi | `[0x13] + index` | 按索引删除 |
| WiFi 列表 | `[0x14]` | 获取已保存列表 |

**添加 WiFi 帧格式 (0x10)**：

```
[CMD][SSID_LEN_H][SSID_LEN_L][SSID...][PWD_LEN_H][PWD_LEN_L][PWD...]
```

- SSID_LEN：uint16 大端序
- PWD_LEN：uint16 大端序

**注意**：添加 WiFi 后固件自动进入 WiFi 模式连接。

#### 4.3 设备状态查询

| 命令 | CMD | 说明 |
|------|-----|------|
| 查询状态 | 0x22 | 获取电量、版本、WiFi 状态 |
| 查询 SD 卡 | 0x23 | 获取 SD 卡剩余/总空间 |
| 查询电池 | 0x24 | 获取电池电量百分比 |

**SD 卡查询响应 (0x23)**：
```
[0xAA][0x23][0x04][FREE_H][FREE_L][TOTAL_H][TOTAL_L][CHECKSUM]
```

**电池查询响应 (0x24)**：
```
[0xAA][0x24][0x01][BATT_PCT][CHECKSUM]
```

#### 4.4 文件下载触发

| 命令 | CMD | 说明 |
|------|-----|------|
| 文件下载 | 0x32 | BLE 触发 WiFi，返回设备 IP，App 通过 HTTP 下载 |

**响应帧**：`[0xAA][0x32][LEN][IP_LEN][IP_STR...][CHECKSUM]`

#### 4.5 时间同步

| 命令 | 字节 | 说明 |
|------|------|------|
| 时间同步 | `[0x40] + timestamp(4B)` | 同步 Unix 时间戳 (大端序, UTC+8) |

#### 4.6 OTA 升级

| 命令 | 字节 | 说明 |
|------|------|------|
| 进入 OTA | `[0x20]` | 设备进入 OTA 模式 |
| 查询版本 | `[0x21]` | 获取固件版本 (通过 0xF1 Notify) |

#### 4.7 日志管理

| 命令 | 字节 | 说明 |
|------|------|------|
| 日志级别 | `[0x30] + level` | 设置日志级别 |
| 日志状态 | `[0x31]` | 查询日志状态 |

#### 4.8 独立模式

| 命令 | 字节 | 说明 |
|------|------|------|
| 独立模式 | `[0x41]` | 进入独立采集 (关闭 BLE/WiFi) |

### 5. HTTP API

设备连接 WiFi 后，通过局域网 IP 访问。

| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/status` | GET | 设备状态 JSON |
| `/api/files` | GET | TF 卡文件列表 JSON |
| `/api/download?file=xxx` | GET | 下载文件（带 CRC32 校验头） |
| `/api/ota` | POST | 上传固件执行 OTA |
| `/api/logs` | GET | 日志文件列表 |
| `/api/logs/download?file=xxx` | GET | 下载日志 |
| `/api/shutdown` | POST | 关闭 WiFi |

**文件下载响应头**：
- `Content-Length`：文件大小
- `X-File-CRC32`：文件 CRC32 校验值

### 6. 自动重连

App 实现 BLE 自动重连：
1. 设备断开后等待 3 秒
2. 自动尝试重连上次设备
3. 失败后每 3 秒重试
4. 用户主动断开不触发自动重连

### 7. 设备名过滤

App 扫描时过滤以下前缀（不区分大小写）：
- `PPG-Monitor`
- `ESP32`
- `PPG`

---

## BLE 通信时序

| 参数 | 值 | 说明 |
|------|-----|------|
| 查询超时 | 2000ms | App 等待命令响应最大 2 秒 |
| 响应延迟 | 500ms | App 读取 Status 前的延迟 |
| 读取超时 | 5000ms | 特征值读取超时 |
| 自动重连延迟 | 3000ms | 重连等待时间 |
| 波形缓冲 | 300 采样点 | 100Hz 下约 3 秒 |

---

## TF 卡二进制格式

PPG 结果文件使用二进制格式存储（非 CSV）：

```
每条记录 14 字节：
  timestamp(4) + heart_rate(2) + spo2(2) + hr_valid(1) + spo2_valid(1) + reserved(3) + checksum(1)

字节序：小端序
校验码：前 13 字节 XOR
```

**TF 卡目录结构**：
```
/raw/xxx.bin      - PPG 原始数据 (红光 + 红外)
/csv/xxx.csv      - PPG 算法结果 (HR/SpO2)
/env/xxx.bin      - DHT11 温湿度数据
/log/xxx.log      - 运行日志
```

---

## Android 版本兼容性

| 版本 | 处理方式 |
|------|----------|
| Android 11 (API 30) | BLE 扫描需要 `ACCESS_FINE_LOCATION` |
| Android 12 (API 31) | 新增 `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` 权限 |
| Android 13 (API 33) | 通知需要 `POST_NOTIFICATIONS`，新增 BLE 读取回调签名 |
| Android 14 (API 34) | 前台服务类型声明 (`foregroundServiceType`) |
| Android 15 (API 35) | 边对边强制、预测性返回手势 |

---

## 安全特性

- **TLS 证书验证**：系统默认 TrustManager + 主机名校验
- **Zip Slip 防护**：7z 解压时校验路径
- **BLE 连接安全**：等待服务发现完成后再返回
- **线程安全**：Mutex 保护共享数据
- **权限管理**：版本感知权限请求 (Android 11-14)
- **文件完整性**：下载文件 CRC32 校验
- **密码安全**：WiFi 密码不写入串口日志

---

## 环境要求

- **Android Studio**：Ladybug (2024.2) 或更新版本
- **JDK**：17
- **Android SDK**：API 30 ~ 35
- **Gradle**：8.11.1
- **AGP**：8.8.2

## 编译

```bash
# Debug 版本
./gradlew assembleDebug

# Release 版本
./gradlew assembleRelease
```

APK 输出路径：`app/build/outputs/apk/`

---

## 项目结构

```
ESP32-C3-PPG-APP/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle/libs.versions.toml        # Version Catalog
├── .github/workflows/build.yml      # CI/CD
└── app/src/main/java/org/tan/ppgtoolapp/
    ├── PpgApplication.kt            # Application (Hilt)
    ├── MainActivity.kt              # 主 Activity
    ├── di/
    │   └── DatabaseModule.kt        # Hilt Room 数据库模块
    ├── data/
    │   ├── ble/
    │   │   ├── BleManager.kt        # BLE 连接、自动重连
    │   │   └── PpgGattProfile.kt    # GATT UUID 和命令定义
    │   ├── network/
    │   │   ├── DeviceApi.kt         # HTTP API 接口
    │   │   ├── HttpRepository.kt    # HTTP 仓库 (含 CRC32 校验)
    │   │   └── ...
    │   ├── local/
    │   │   ├── AppDatabase.kt       # Room 数据库
    │   │   ├── FileMetadata.kt      # 已下载文件实体
    │   │   ├── FileMetadataDao.kt   # 文件元数据 DAO
    │   │   ├── PpgRecord.kt         # PPG 数据记录模型
    │   │   ├── CsvParser.kt         # 二进制 PPG 文件解析器
    │   │   └── PpgAnalyzer.kt       # 统计分析 (HR/SpO2 + SpO2 事件)
    │   └── wifi/
    │       └── WifiScanner.kt       # WiFi 扫描
    ├── util/
    │   ├── ExportHelper.kt          # CSV/PDF 导出 + 分享
    │   ├── NotificationHelper.kt    # 下载进度通知
    │   └── PermissionHelper.kt      # 权限处理
    ├── viewmodel/
    │   ├── MonitorViewModel.kt      # 监控页面逻辑
    │   ├── DataViewModel.kt         # 数据下载与文件管理
    │   ├── OtaViewModel.kt          # OTA 升级逻辑
    │   ├── WifiProvisionViewModel.kt # WiFi 配网
    │   └── SettingsViewModel.kt     # 设置页面逻辑
    └── ui/screens/
        ├── MonitorScreen.kt         # 实时监控界面
        ├── DeviceScreen.kt          # 设备扫描/连接
        ├── DataScreen.kt            # 文件下载与管理
        ├── AnalysisScreen.kt        # 离线数据分析
        ├── OtaScreen.kt             # OTA 升级
        ├── WifiProvisionScreen.kt   # WiFi 配网
        └── SettingsScreen.kt        # 设置
```

---

## 常见问题

**Q: 扫描不到设备？**
- 确认设备已开机并广播
- 设备名以 `PPG-Monitor`、`ESP32` 或 `PPG` 开头
- 检查蓝牙权限是否授予

**Q: 连接后收不到数据？**
- 确认 Notify 已启用（需要写入 CCC Descriptor）
- 检查 GATT UUID 是否与本文档一致
- 查看 Android Logcat BLE 相关日志

**Q: WiFi 配网失败？**
- 检查日志中的 BLE 帧十六进制数据
- 确认固件校验码算法与 App 一致（SUM）
- 确认 SSID 不包含多余引号
- 隐藏 WiFi 使用「手动添加」功能

**Q: OTA 升级失败？**
- 确认 7z 包中包含 `.bin` 固件文件
- 检查设备电量是否充足
- 升级过程中不要断开连接

**Q: 文件下载校验失败？**
- 检查 WiFi 信号强度
- 重新下载文件
- 查看 Logcat 中的 CRC32 日志

---

## 固件仓库

- **App 仓库**：`https://github.com/Tan1347/ESP32-C3-PPG-APP`
- **ESP32-C3 固件**：`https://github.com/Tan1347/ESP32-C3_PPG_Data_Collector`
