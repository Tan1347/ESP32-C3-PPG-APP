# PPG Monitor Android App

ESP32-C3 PPG 血氧仪配套 Android 应用，用于实时监控、数据管理和设备配置。

> **本文档同时作为固件开发参考**，包含完整的 BLE 协议、数据格式和通信规范。

## 功能概述

### 核心功能
- **设备连接**：BLE 扫描、连接、配对 ESP32-C3 PPG 设备
- **实时监控**：PPG 波形显示、心率/血氧/灌注指数实时数据
- **设备状态**：电量百分比、固件版本、SD 卡剩余容量
- **数据管理**：通过 WiFi HTTP 下载 TF 卡中的采集数据

### 设备配置
- **WiFi 配网**：扫描 2.4GHz WiFi + 手动添加隐藏网络，通过 BLE 发送凭据到设备
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

---

## 🔧 固件开发参考

> 本章节为 ESP32-C3 固件开发者提供完整的协议规范。

### 1. BLE GATT 服务定义

固件需要实现以下 GATT 服务：

```
Service UUID: 0000fff0-0000-1000-8000-00805f9b34fb
├── Characteristic: 0000fff1-0000-1000-8000-00805f9b34fb (Status)      - Read, Notify
├── Characteristic: 0000fff2-0000-1000-8000-00805f9b34fb (Live Data)   - Notify
├── Characteristic: 0000fff3-0000-1000-8000-00805f9b34fb (Command)     - Write
└── Characteristic: 0000fff4-0000-1000-8000-00805f9b34fb (File List)   - Read
```

**CCC Descriptor**: `00002902-0000-1000-8000-00805f9b34fb`（用于启用 Notify）

**固件初始化示例**：
```c
// ESP-IDF 示例
static const uint16_t GATT_SERVICE_UUID = 0xFFF0;
static const uint16_t STATUS_CHAR_UUID = 0xFFF1;
static const uint16_t LIVE_DATA_CHAR_UUID = 0xFFF2;
static const uint16_t COMMAND_CHAR_UUID = 0xFFF3;
static const uint16_t FILE_LIST_CHAR_UUID = 0xFFF4;

// Status 特征值初始数据
static uint8_t status_data[20] = {
    0,            // battery_soc: 待更新
    0, 0,         // voltage: 待更新
    0,            // reserved
    0,            // connected: 未连接
    '0', '.', '0', '.', '0',  // version
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0  // 填充
};
```

### 2. BLE 广播配置

```
设备名前缀: "PPG-Monitor" (也支持 "ESP32", "PPG" 开头)
Service UUID: 0000fff0-0000-1000-8000-00805f9b34fb (可选，用于快速过滤)
```

APP 扫描时会过滤名称以 `PPG-Monitor`、`ESP32`、`PPG` 开头的设备。

### 3. 数据格式详解

#### 3.1 Status 特征值 (0xFFF1) - Read/Notify

设备状态信息，**20 字节固定长度**：

```
偏移量  长度  字段名        类型      说明
0       1     battery_soc   uint8     电量百分比 (0-100)
1-2     2     voltage       uint16    电压 (mV, big-endian)
3       1     reserved      uint8     保留字段
4       1     connected     uint8     WiFi 连接状态 (0=未连接, 1=已连接)
5-19    15    version       char[]    固件版本字符串 (UTF-8, 空格填充)
```

**示例数据**：
```
55 0E A0 00 01 31 2E 30 2E 30 00 00 00 00 00 00 00 00 00 00
│  │      │  │  └─────────────────────────────────────────────┘
│  │      │  │  version: "1.0.0" (UTF-8, 剩余填充 0x00)
│  │      │  connected: 1 (WiFi 已连接)
│  │      reserved: 0
│  voltage: 0x0EA0 = 3744 mV
battery_soc: 55 = 85%
```

#### 3.2 Live Data 特征值 (0xFFF2) - Notify

实时 PPG 数据，**5 字节固定长度**：

```
偏移量  长度  字段名    类型      说明
0-1     2     hr        uint16    心率 (BPM, big-endian)
2       1     spo2      uint8     血氧饱和度 (0-100%)
3       1     pi        uint8     灌注指数 (0-100%)
4       1     quality   uint8     信号质量 (0-100)
```

**示例数据**：
```
00 4B 61 0A 50
│     │  │  └─ quality: 80
│     │  └─ pi: 10
│     └─ spo2: 97
└─ hr: 75 BPM
```

#### 3.3 Command 特征值 (0xFFF3) - Write

APP 发送给设备的控制命令。

**固件接收命令处理示例**：
```c
// ESP-IDF GATT 写入回调
static void gatts_write_handler(esp_gatts_cb_event_t event, 
                                 esp_gatt_if_t gatts_if, 
                                 esp_ble_gatts_cb_param_t *param) {
    if (param->write.handle == command_char_handle) {
        uint8_t *data = param->write.value;
        uint16_t len = param->write.len;
        
        uint8_t cmd = data[0];
        
        switch (cmd) {
            case 0x01:  // CMD_START_MEASURE
                start_ppg_measurement();
                break;
                
            case 0x02:  // CMD_STOP_MEASURE
                stop_ppg_measurement();
                break;
                
            case 0x10:  // CMD_WIFI_ADD
                process_wifi_credentials(data, len);
                break;
                
            case 0x40:  // CMD_TIME_SYNC
                process_time_sync(data, len);
                break;
                
            // ... 其他命令
        }
    }
}

// WiFi 凭据处理
void process_wifi_credentials(uint8_t *data, uint16_t len) {
    // 1. 校验帧长度
    if (len < 7) return;  // 最小: CMD(1) + SSID_LEN(2) + PWD_LEN(2) + CHECKSUM(1)
    
    // 2. 解析 SSID
    uint16_t ssid_len = (data[1] << 8) | data[2];
    char ssid[33] = {0};
    memcpy(ssid, &data[3], ssid_len);
    
    // 3. 解析密码
    uint16_t pwd_offset = 3 + ssid_len;
    uint16_t pwd_len = (data[pwd_offset] << 8) | data[pwd_offset + 1];
    char password[65] = {0};
    memcpy(password, &data[pwd_offset + 2], pwd_len);
    
    // 4. 校验 checksum
    uint8_t checksum = 0;
    for (int i = 0; i < len - 1; i++) {
        checksum ^= data[i];
    }
    if (checksum != data[len - 1]) {
        ESP_LOGE(TAG, "WiFi 凭据校验码错误: 期望 0x%02X, 实际 0x%02X", 
                 data[len - 1], checksum);
        return;
    }
    
    // 5. 连接 WiFi
    ESP_LOGI(TAG, "WiFi SSID: %s, 密码: %s", ssid, password);
    wifi_connect(ssid, password);
}
```

### 4. 命令协议详解

#### 4.1 测量控制

| 命令 | 字节 | 说明 |
|------|------|------|
| 开始测量 | `[0x01]` | 启动 PPG 采样，开始发送 Live Data |
| 停止测量 | `[0x02]` | 停止采样 |

#### 4.2 WiFi 管理

| 命令 | 字节 | 说明 |
|------|------|------|
| 开启 WiFi | `[0x03]` | 连接已保存的路由器 |
| 添加 WiFi | `[0x10] + 数据` | 发送 WiFi 凭据 |
| WiFi 状态 | `[0x11]` | 查询 WiFi 连接状态 |
| 清除 WiFi | `[0x12]` | 清除所有已保存 WiFi |
| 删除 WiFi | `[0x13] + index` | 删除指定 WiFi |
| WiFi 列表 | `[0x14]` | 获取已保存 WiFi 列表 |
| 修改 WiFi | `[0x15] + data` | 修改已保存 WiFi |
| WiFi 优先级 | `[0x16] + data` | 设置 WiFi 连接优先级 |

**添加 WiFi 命令格式 (0x10)**：

```
帧结构:  [CMD] [SSID_LEN] [SSID_DATA] [PWD_LEN] [PWD_DATA] [CHECKSUM]
字节数:   1      2          N           2          M          1
```

| 字段 | 偏移 | 长度 | 类型 | 说明 |
|------|------|------|------|------|
| CMD | 0 | 1 | uint8 | 命令 ID: 0x10 |
| SSID_LEN | 1-2 | 2 | uint16 | SSID 长度 (big-endian) |
| SSID_DATA | 3~3+N-1 | N | bytes | SSID 字符串 (UTF-8) |
| PWD_LEN | 3+N ~ 4+N | 2 | uint16 | 密码长度 (big-endian) |
| PWD_DATA | 5+N ~ 5+N+M-1 | M | bytes | 密码字符串 (UTF-8) |
| CHECKSUM | 5+N+M | 1 | uint8 | 校验码 (SUM) |

**校验码计算**：从 CMD 字节开始，到 PWD_DATA 结束，所有字节求和（SUM，mod 256）

**示例：SSID="MyWiFi", Password="12345678"**：
```
字段      值                十六进制
CMD       CMD_WIFI_ADD      10
SSID_LEN  6                 00 06
SSID      "MyWiFi"          4D 79 57 69 46 69
PWD_LEN   8                 00 08
PWD       "12345678"        31 32 33 34 35 36 37 38
CHECKSUM  SUM               79

完整帧 (21字节):
10 00 06 4D 79 57 69 46 69 00 08 31 32 33 34 35 36 37 38 5E
```

**校验码计算过程**：
```
checksum = 0x00
checksum += 0x10 = 0x10
checksum += 0x00 = 0x10
checksum += 0x06 = 0x16
checksum += 0x4D = 0x63
checksum += 0x79 = 0xDC
checksum += 0x57 = 0x33 (overflow)
checksum += 0x69 = 0x9C
checksum += 0x46 = 0xE2
checksum += 0x69 = 0x4B (overflow)
checksum += 0x00 = 0x4B
checksum += 0x08 = 0x53
checksum += 0x31 = 0x84
checksum += 0x32 = 0xB6
checksum += 0x33 = 0xE9
checksum ^= 0x34 = 0x3F
checksum ^= 0x35 = 0x0A
checksum ^= 0x36 = 0x3C
checksum ^= 0x37 = 0x0B
checksum ^= 0x38 = 0x33
最终校验码: 0x33
```

**注意**：SSID 不应包含引号字符，APP 已自动移除。

**WiFi 配网模式**：

APP 支持两种 WiFi 配网方式：

| 模式 | 说明 | 适用场景 |
|------|------|---------|
| 扫描模式 | 扫描附近 2.4GHz WiFi，点击连接 | 正常 WiFi 网络 |
| 手动模式 | 手动输入 SSID 和密码 | 隐藏 WiFi 网络 |

两种模式发送的 BLE 帧格式完全相同，固件无需区分来源。

**隐藏 WiFi 注意事项**：
- SSID 可以包含空格和特殊字符
- 密码可以为空（开放网络）
- APP 会自动移除 SSID 中的引号字符

#### 4.3 设备状态查询

**查询命令格式**：

```
帧结构:  [CMD]
字节数:   1
```

| 命令 | CMD 字节 | 说明 |
|------|----------|------|
| 查询完整状态 | 0x22 | 获取电量、版本、WiFi 状态 |
| 查询 SD 卡 | 0x23 | 获取 SD 卡剩余容量 |
| 查询电池详情 | 0x24 | 获取电池电量和电压 |

**响应方式**：

- 0x22 (查询状态)：固件更新 Status 特征值（0xFFF1），APP 通过 Read 获取
- 0x23 (查询 SD 卡)：固件通过 Command 特征值（0xFFF3）Notify 返回数据帧
- 0x24 (查询电池)：固件通过 Command 特征值（0xFFF3）Notify 返回数据帧

**SD 卡查询响应帧格式 (0x23)**：

```
帧结构:  [0xAA][0x23][0x04][FREE_H][FREE_L][TOTAL_H][TOTAL_L][CHECKSUM]
字节数:   1     1     1     1       1       1        1        1
```

| 字段 | 偏移 | 长度 | 类型 | 说明 |
|------|------|------|------|------|
| HEADER | 0 | 1 | uint8 | 帧头 0xAA |
| CMD | 1 | 1 | uint8 | 命令 ID: 0x23 |
| LEN | 2 | 1 | uint8 | 数据长度: 0x04 |
| FREE_H | 3 | 1 | uint8 | 剩余空间高字节 |
| FREE_L | 4 | 1 | uint8 | 剩余空间低字节 |
| TOTAL_H | 5 | 1 | uint8 | 总空间高字节 |
| TOTAL_L | 6 | 1 | uint8 | 总空间低字节 |
| CHECKSUM | 7 | 1 | uint8 | 校验码 (SUM) |

FREE 和 TOTAL 均为 uint16 big-endian，单位 MB。

**示例：剩余 1024MB，总容量 3768MB**：
```
AA 23 04 04 00 0E B8 XX
```

**电池查询响应帧格式 (0x24)**：

```
帧结构:  [0xAA][0x24][0x03][SOC][VOLTAGE_H][VOLTAGE_L][CHECKSUM]
字节数:   1     1     1     1     1          1          1
```

**Status 特征值数据格式（20 字节）**：

```
偏移量  长度  字段名        类型      说明
0       1     battery_soc   uint8     电量百分比 (0-100)
1-2     2     voltage       uint16    电压 (mV, big-endian)
3       1     reserved      uint8     保留字段
4       1     connected     uint8     WiFi 连接状态 (0=未连接, 1=已连接)
5-19    15    version       char[]    固件版本字符串 (UTF-8, 空格填充)
```

**固件实现示例**：
```c
// 收到查询命令后更新 Status 特征值
void handle_query_command(uint8_t cmd) {
    switch (cmd) {
        case 0x22:  // 查询完整状态
            status_data[0] = get_battery_soc();
            uint16_t voltage = get_battery_voltage();
            status_data[1] = (voltage >> 8) & 0xFF;
            status_data[2] = voltage & 0xFF;
            status_data[4] = is_wifi_connected() ? 1 : 0;
            // version 已在初始化时填充
            update_status_characteristic(status_data, 20);
            break;

        case 0x23:  // 查询 SD 卡
            // SD 卡信息可通过扩展字段或自定义特征值返回
            break;

        case 0x24:  // 查询电池
            status_data[0] = get_battery_soc();
            uint16_t v = get_battery_voltage();
            status_data[1] = (v >> 8) & 0xFF;
            status_data[2] = v & 0xFF;
            update_status_characteristic(status_data, 20);
            break;
    }
}
```

#### 4.4 时间同步

| 命令 | 字节 | 说明 |
|------|------|------|
| 时间同步 | `[0x40] + timestamp` | 同步 Unix 时间戳 |

**时间同步命令格式 (0x40)**：

```
帧结构:  [CMD] [TIMESTAMP] [CHECKSUM]
字节数:   1      4           1
```

| 字段 | 偏移 | 长度 | 类型 | 说明 |
|------|------|------|------|------|
| CMD | 0 | 1 | uint8 | 命令 ID: 0x40 |
| TIMESTAMP | 1-4 | 4 | uint32 | Unix 时间戳 (big-endian, UTC+8) |
| CHECKSUM | 5 | 1 | uint8 | 校验码 (SUM) |

**示例：时间戳=1718688000 (2024-06-18 16:00:00 UTC+8)**：
```
字段       值              十六进制
CMD        CMD_TIME_SYNC   0x40
TIMESTAMP  1718688000      66 72 8C 00
CHECKSUM   SUM             1A

完整帧 (6字节):
40 66 72 8C 00 1A
```

**校验码计算**：
```
checksum = 0x00
checksum ^= 0x40 = 0x40
checksum ^= 0x66 = 0x26
checksum ^= 0x72 = 0x54
checksum ^= 0x8C = 0xD8
checksum ^= 0x00 = 0xD8
最终校验码: 0xD8
```

**注意**：
- 时间戳为 UTC+8 时区
- 10 位秒级 Unix 时间戳
- APP 优先从网络获取时间，失败时使用本地时间 +8 小时偏移

**固件接收时间同步示例**：
```c
void process_time_sync(uint8_t *data, uint16_t len) {
    // 1. 校验帧长度
    if (len != 6) return;  // CMD(1) + TIMESTAMP(4) + CHECKSUM(1)
    
    // 2. 校验 checksum
    uint8_t checksum = 0;
    for (int i = 0; i < len - 1; i++) {
        checksum ^= data[i];
    }
    if (checksum != data[len - 1]) {
        ESP_LOGE(TAG, "时间同步校验码错误");
        return;
    }
    
    // 3. 解析时间戳 (big-endian)
    uint32_t timestamp = (data[1] << 24) | (data[2] << 16) | 
                         (data[3] << 8) | data[4];
    
    // 4. 设置系统时间
    struct timeval tv = { .tv_sec = timestamp, .tv_usec = 0 };
    settimeofday(&tv, NULL);
    
    ESP_LOGI(TAG, "时间同步成功: %lu", timestamp);
}
```

#### 4.5 OTA 升级

| 命令 | 字节 | 说明 |
|------|------|------|
| 进入 OTA | `[0x20]` | 设备进入 OTA 模式 |
| 查询版本 | `[0x21]` | 获取固件版本 |

#### 4.6 日志管理

| 命令 | 字节 | 说明 |
|------|------|------|
| 日志级别 | `[0x30] + level` | 设置日志级别 |
| 日志状态 | `[0x31]` | 查询日志状态 |
| 导出日志 | `[0x32]` | 导出日志到 TF 卡 |

### 5. HTTP API 详细规范

设备连接 WiFi 后，通过局域网 IP 访问。

#### 5.1 获取设备状态

```
GET /api/status
```

**响应示例**：
```json
{
  "version": "1.0.0",
  "battery": {
    "soc": 85,
    "voltage": 3744
  },
  "ip": "192.168.1.100",
  "sd_free_mb": 1024
}
```

#### 5.2 获取文件列表

```
GET /api/files
```

**响应示例**：
```json
{
  "files": [
    "data_20240618_120000.csv",
    "data_20240618_130000.csv",
    "log_001.bin"
  ]
}
```

#### 5.3 下载文件

```
GET /api/download?file=data_20240618_120000.csv
```

**响应**：文件二进制流

#### 5.4 OTA 固件上传

```
POST /api/ota
Content-Type: application/octet-stream

[固件二进制数据]
```

**响应**：设备自动重启进入 OTA 模式

#### 5.5 获取日志列表

```
GET /api/logs
```

**响应示例**：
```json
{
  "logs": [
    "log_20240618.txt",
    "log_20240617.txt"
  ]
}
```

#### 5.6 下载日志

```
GET /api/logs/download?file=log_20240618.txt
```

#### 5.7 关闭 WiFi

```
POST /api/shutdown
```

### 6. 自动重连机制

APP 实现了 BLE 断线自动重连：

1. 设备断开连接后，APP 等待 3 秒
2. 自动尝试重新连接上次连接的设备
3. 如果重连失败，每 3 秒重试一次
4. 用户主动断开时不会触发自动重连

**固件建议**：
- 设备断开后继续广播，方便 APP 重新发现
- 保持 GATT 服务配置不变

### 7. 设备名称过滤

APP 扫描时会过滤以下前缀的设备名（不区分大小写）：

```kotlin
val DEVICE_NAME_PREFIXES = listOf(
    "PPG-Monitor",
    "ESP32",
    "PPG"
)
```

**建议固件使用**：`PPG-Monitor-XXXX`（XXXX 为设备标识）

### 8. BLE 通信流程

```
┌─────────────────────────────────────────────────────────────────┐
│                        APP 通信流程                              │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  1. 扫描设备                                                     │
│     APP: startScan() → 过滤名称前缀 → 显示设备列表               │
│                                                                 │
│  2. 连接设备                                                     │
│     APP: connect(device) → 等待 STATE_CONNECTED                 │
│     APP: discoverServices() → 等待 onServicesDiscovered         │
│     APP: enableNotifications() → 启用 Notify                    │
│                                                                 │
│  3. 数据交互                                                     │
│     ┌──────────────┐                    ┌──────────────┐        │
│     │     APP      │                    │    ESP32     │        │
│     ├──────────────┤                    ├──────────────┤        │
│     │              │ ── Write CMD ───→  │              │        │
│     │              │ ←── Notify Data ── │              │        │
│     │              │ ── Read Status ──→ │              │        │
│     │              │ ←── Status Data ── │              │        │
│     └──────────────┘                    └──────────────┘        │
│                                                                 │
│  4. 断开重连                                                     │
│     设备断开 → 等待 3s → 自动重连 → 重复步骤 2                   │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 9. 固件实现检查清单

| 功能 | 特征值 | 固件需要实现 |
|------|--------|-------------|
| 设备广播 | - | 广播名称以 "PPG-Monitor" 开头 |
| 状态上报 | 0xFFF1 | 初始化 20 字节状态数据，定期更新 |
| 实时数据 | 0xFFF2 | 测量时发送 5 字节 PPG 数据 |
| 命令接收 | 0xFFF3 | 解析命令，执行对应操作 |
| 文件列表 | 0xFFF4 | 返回 TF 卡文件列表 |
| WiFi 凭据 | CMD 0x10 | 解析帧，校验 checksum，连接 WiFi |
| 查询状态 | CMD 0x22 | 更新 Status 特征值（电量、版本、WiFi） |
| 查询 SD 卡 | CMD 0x23 | 通过 Command 特征值 Notify 返回 [free_h][free_l][total_h][total_l] |
| 查询电池 | CMD 0x24 | 通过 Command 特征值 Notify 返回 [soc][voltage_h][voltage_l] |
| 时间同步 | CMD 0x40 | 解析帧，设置系统时间 |
| OTA 模式 | CMD 0x20 | 进入 OTA 等待固件上传 |

### 10. 常见固件问题

| 问题 | 可能原因 | 解决方案 |
|------|---------|---------|
| APP 扫描不到设备 | 设备名不符合规则 | 使用 "PPG-Monitor" 前缀 |
| 读取状态全零 | 未初始化 Status 特征值 | 启动时写入初始状态数据 |
| WiFi 连接失败 | SSID 包含引号 | 检查 SSID 是否正确 |
| 校验码错误 | 校验算法不一致 | 使用 SUM 校验，参考本文档 |
| OTA 失败 | 未进入 OTA 模式 | 收到 0x20 命令后进入 OTA 等待 |

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

## 🔒 安全特性

- **TLS 证书验证**：使用系统默认 TrustManager + 主机名校验
- **Zip Slip 防护**：7z 解压时校验路径
- **BLE 连接安全**：等待服务发现完成后再返回
- **线程安全**：使用 Mutex 保护共享数据
- **权限管理**：版本感知权限请求，适配 Android 11-14

## 环境要求

- **Android Studio**：Ladybug (2024.2) 或更新版本
- **JDK**：17
- **Android SDK**：API 30 ~ 35
- **Gradle**：8.11.1
- **AGP**：8.8.2

## 编译步骤

```bash
# Debug 版本
./gradlew assembleDebug

# Release 版本
./gradlew assembleRelease
```

APK 输出路径：`app/build/outputs/apk/`

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
    ├── data/
    │   ├── ble/
    │   │   ├── BleManager.kt        # BLE 连接管理、自动重连
    │   │   └── PpgGattProfile.kt    # GATT UUID 和命令定义
    │   ├── network/
    │   │   ├── DeviceApi.kt         # HTTP API 接口
    │   │   ├── HttpRepository.kt    # HTTP 仓库
    │   │   ├── AppHttpClient.kt     # 全局 OkHttpClient（优选 DNS）
    │   │   ├── GitHubDns.kt         # GitHub 域名 DNS 解析器
    │   │   └── ...
    │   └── wifi/
    │       └── WifiScanner.kt       # WiFi 扫描
    ├── viewmodel/
    │   ├── MonitorViewModel.kt      # 监控页面逻辑
    │   ├── OtaViewModel.kt          # OTA 升级逻辑
    │   ├── WifiProvisionViewModel.kt # WiFi 配网逻辑（支持手动添加）
    │   └── SettingsViewModel.kt     # 设置页面逻辑
    └── ui/screens/
        ├── MonitorScreen.kt         # 实时监控界面
        ├── DeviceScreen.kt          # 设备扫描/连接
        ├── OtaScreen.kt             # OTA 升级
        ├── WifiProvisionScreen.kt   # WiFi 配网（扫描+手动）
        └── SettingsScreen.kt        # 设置页面
```

## 固件仓库

- **APP 仓库**：`https://github.com/Tan1347/ESP32-C3-PPG-APP`
- **ESP32-C3 固件**：`https://github.com/Tan1347/ESP32-C3_PPG_Data_Collector`

## 固件开发快速参考

### 关键实现要点

| 要点 | 说明 |
|------|------|
| 设备名 | 以 "PPG-Monitor" 开头 |
| GATT 服务 | UUID: 0000fff0-... |
| Status 特征值 | 必须初始化并定期更新 |
| 命令接收 | 支持 0x01-0x40 命令 |
| 校验码 | 所有命令帧使用 SUM 校验 |
| WiFi SSID | 不包含引号字符 |
| 时间戳 | UTC+8 时区，10位秒级 |

### 调试建议

1. **使用日志**：APP 和固件都打印十六进制数据
2. **对比帧内容**：确保收发数据一致
3. **校验码验证**：两端使用相同算法计算
4. **Status 初始化**：避免读取全零数据

## 常见问题

**Q: 扫描不到设备？**
- 确认设备已开机并广播
- 设备名以 `PPG-Monitor`、`ESP32` 或 `PPG` 开头
- 检查蓝牙权限是否授予

**Q: 连接后收不到数据？**
- 确认 Notify 已启用（需要写入 CCC Descriptor）
- 检查 GATT UUID 是否与本文档一致
- 查看 Android Logcat BLE 相关日志

**Q: 自动重连不工作？**
- 确认设备断开后继续广播
- 检查设备名是否符合过滤规则

**Q: WiFi 配网失败？**
- 检查日志中的 BLE 帧十六进制数据
- 确认固件校验码算法与 APP 一致（SUM）
- 确认 SSID 不包含多余引号
- 隐藏 WiFi 使用「手动添加」功能

**Q: 如何连接隐藏 WiFi？**
- 点击「手动添加」按钮
- 输入正确的 SSID 和密码
- SSID 区分大小写

**Q: OTA 升级失败？**
- 确认 WiFi 凭据格式正确（参考 4.2 节）
- 确认设备支持 2.4GHz WiFi

**Q: OTA 升级失败？**
- 确认 7z 包中包含 `.bin` 固件文件
- 检查设备电量是否充足
- 升级过程中不要断开连接
