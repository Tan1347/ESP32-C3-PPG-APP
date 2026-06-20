# PPG Monitor Android App

ESP32-C3 PPG companion Android application for real-time monitoring, data management, and device configuration.

> **This document also serves as firmware development reference**, containing complete BLE protocol, data formats, and communication specifications.

## Features

### Core Features
- **Device Connection**: BLE scan, connect, pair ESP32-C3 PPG devices
- **Real-time Monitoring**: PPG waveform display, heart rate/SpO2/perfusion index real-time data
- **Device Status**: Battery percentage, firmware version, SD card free space
- **Data Management**: Download collection data from TF card via WiFi HTTP, Room database for file tracking
- **Offline Analysis**: Parse binary PPG records, calculate HR/SpO2 statistics, detect SpO2 desaturation events
- **Batch Download**: Download all files from device with progress notification
- **Export & Share**: Export analysis results as CSV or PDF, share via Android Share Sheet

### Device Configuration
- **WiFi Provisioning**: Scan 2.4GHz WiFi + manual hidden network, send credentials to device via BLE
- **OTA Upgrade**: Download 7z firmware from GitHub Release, extract and upload to device
- **Local OTA**: Support selecting local 7z/bin firmware files
- **Time Sync**: Network time sync to device (UTC+8), fallback to local time

### Application Features
- **Self Update**: Check and download new versions from GitHub Release
- **GitHub Acceleration**: Built-in DNS optimization and mirror proxies

## Documentation

- [Quick Start](docs/QUICKSTART.md) - Environment setup, build, run, debug tips
- [Architecture](docs/ARCHITECTURE.md) - System architecture, module design, security
- [API Reference](docs/API.md) - BLE/HTTP/WiFi API documentation
- [Changelog](docs/CHANGELOG.md) - Version update history

---

## Firmware Development Reference

> This section provides complete protocol specifications for ESP32-C3 firmware developers.

### 1. BLE GATT Service Definition

Firmware must implement the following GATT service:

```
Service UUID: 0000fff0-0000-1000-8000-00805f9b34fb
├── Characteristic: 0000fff1-0000-1000-8000-00805f9b34fb (Status)      - Read, Notify
├── Characteristic: 0000fff2-0000-1000-8000-00805f9b34fb (Live Data)   - Notify
├── Characteristic: 0000fff3-0000-1000-8000-00805f9b34fb (Command)     - Write, Notify
└── Characteristic: 0000fff4-0000-1000-8000-00805f9b34fb (File List)   - Read
```

**CCC Descriptor**: `00002902-0000-1000-8000-00805f9b34fb` (for enabling Notify)

### 2. BLE Advertising

```
Device name prefix: "PPG-Monitor" (also supports "ESP32", "PPG" prefixes)
Service UUID: 0000fff0-0000-1000-8000-00805f9b34fb (optional, for fast filtering)
Advertising interval: 200-220ms
```

APP scans and filters devices with names starting with `PPG-Monitor`, `ESP32`, or `PPG`.

### 3. Data Formats

#### 3.1 Status Characteristic (0xFFF1) - Read/Notify

Device status information, **20 bytes fixed length**:

```
Offset  Length  Field       Type    Description
0       1       batt_pct    uint8   Battery percentage (0-100)
1-3     3       reserved    uint8   Reserved (0x00)
4       1       connected   uint8   WiFi status (0=disconnected, 1=connected)
5-19    15      version     char[]  Firmware version (UTF-8, null-padded)
```

**Example data**:
```
37 00 00 00 01 31 2E 30 2E 30 00 00 00 00 00 00 00 00 00 00
│           │  └──────────────────────────────────────────────┘
│           │  version: "1.0.0" (UTF-8, null-padded)
│           connected: 1 (WiFi connected)
│           reserved: 0
batt_pct: 37 = 55%
```

#### 3.2 Live Data Characteristic (0xFFF2) - Notify

Real-time PPG data, **5 bytes fixed length**:

```
Offset  Length  Field    Type    Description
0-1     2       hr       uint16  Heart rate (BPM, big-endian)
2       1       spo2     uint8   Blood oxygen (0-100%)
3       1       pi       uint8   Perfusion index (reserved, 0x00)
4       1       quality  uint8   Signal quality (80=valid, 20=invalid)
```

**Example data**:
```
00 4B 61 00 50
│     │     └─ quality: 80 (valid)
│     │     pi: 0 (reserved)
│     └─ spo2: 97
└─ hr: 75 BPM
```

#### 3.3 Command Characteristic (0xFFF3) - Write/Notify

Commands sent from APP to device use frame protocol.

**Frame format**: `[0xAA][CMD][LEN][DATA...][CHECKSUM]`

**Checksum**: SUM of CMD + LEN + all DATA bytes, mod 256.

**Response frame**: `[0xAA][CMD][0x01][STATUS][CHECKSUM]`

Status codes: 0=OK, 1=cancelled, 2=checksum error, 3=unknown command, 4=low battery

### 4. Command Protocol

#### 4.1 Measurement Control

| Command | Bytes | Description |
|---------|-------|-------------|
| Start measurement | `[0x01]` | Start PPG sampling, begin sending Live Data |
| Stop measurement | `[0x02]` | Stop sampling |

#### 4.2 WiFi Management

| Command | Bytes | Description |
|---------|-------|-------------|
| Start WiFi | `[0x03]` | Connect to saved router |
| Add WiFi | `[0x10] + data` | Send WiFi credentials |
| WiFi status | `[0x11]` | Query WiFi connection status |
| Clear WiFi | `[0x12]` | Clear all saved WiFi |
| Delete WiFi | `[0x13] + index` | Delete WiFi by index |
| WiFi list | `[0x14]` | Get saved WiFi list |
| Modify WiFi | `[0x15] + data` | Modify saved WiFi |
| WiFi priority | `[0x16] + data` | Set WiFi connection priority |

**Add WiFi command format (0x10)**:

```
Frame: [CMD] [SSID_LEN_H] [SSID_LEN_L] [SSID...] [PWD_LEN_H] [PWD_LEN_L] [PWD...]
Bytes:  1      1            1            N          1            1            M
```

| Field | Offset | Length | Type | Description |
|-------|--------|--------|------|-------------|
| CMD | 0 | 1 | uint8 | Command ID: 0x10 |
| SSID_LEN | 1-2 | 2 | uint16 | SSID length (big-endian) |
| SSID | 3~3+N-1 | N | bytes | SSID string (UTF-8) |
| PWD_LEN | 3+N~4+N | 2 | uint16 | Password length (big-endian) |
| PWD | 5+N~5+N+M-1 | M | bytes | Password string (UTF-8) |

**Example: SSID="MyWiFi", Password="12345678"**:
```
Field      Value                Hex
CMD        CMD_WIFI_ADD         10
SSID_LEN   6                    00 06
SSID       "MyWiFi"             4D 79 57 69 46 69
PWD_LEN    8                    00 08
PWD        "12345678"           31 32 33 34 35 36 37 38

Payload (20 bytes):
10 00 06 4D 79 57 69 46 69 00 08 31 32 33 34 35 36 37 38

Note: The outer writeCommand() wraps with [0xAA][LEN][...][CHECKSUM]
```

**WiFi provisioning modes**:

| Mode | Description | Use case |
|------|-------------|----------|
| Scan mode | Scan nearby 2.4GHz WiFi, tap to connect | Normal WiFi networks |
| Manual mode | Manually enter SSID and password | Hidden WiFi networks |

Both modes produce identical BLE frames. Firmware does not need to distinguish.

**Note**: After adding WiFi credentials, firmware automatically enters WiFi mode to connect.

#### 4.3 Device Status Query

| Command | CMD | Description |
|---------|-----|-------------|
| Query full status | 0x22 | Get battery, version, WiFi status |
| Query SD card | 0x23 | Get SD card free/total space |
| Query battery | 0x24 | Get battery percentage |

**Query SD card response (0x23)**:

```
Frame: [0xAA][0x23][0x04][FREE_H][FREE_L][TOTAL_H][TOTAL_L][CHECKSUM]
```

- FREE: uint16 big-endian, free space in MB
- TOTAL: uint16 big-endian, total space in MB

**Query battery response (0x24)**:

```
Frame: [0xAA][0x24][0x01][BATT_PCT][CHECKSUM]
```

- BATT_PCT: uint8, battery percentage (0-100)

#### 4.4 Time Sync

| Command | Bytes | Description |
|---------|-------|-------------|
| Time sync | `[0x40] + timestamp` | Sync Unix timestamp |

**Time sync command format (0x40)**:

```
Frame: [CMD] [TIMESTAMP] [CHECKSUM]
Bytes:  1      4           1
```

| Field | Offset | Length | Type | Description |
|-------|--------|--------|------|-------------|
| CMD | 0 | 1 | uint8 | Command ID: 0x40 |
| TIMESTAMP | 1-4 | 4 | uint32 | Unix timestamp (big-endian, UTC+8) |
| CHECKSUM | 5 | 1 | uint8 | Checksum (SUM) |

**Example: timestamp=1718688000 (2024-06-18 16:00:00 UTC+8)**:
```
Field       Value              Hex
CMD         CMD_TIME_SYNC      0x40
TIMESTAMP   1718688000         66 72 8C 00
CHECKSUM    SUM                42

Complete frame (6 bytes):
40 66 72 8C 00 42

Checksum calculation:
SUM = 0x40 + 0x66 + 0x72 + 0x8C + 0x00 = 0x04 (mod 256)
Wait, let me recalculate:
0x40 + 0x66 = 0xA6
0xA6 + 0x72 = 0x118 -> 0x18 (mod 256)
0x18 + 0x8C = 0xA4
0xA4 + 0x00 = 0xA4
CHECKSUM = 0xA4

Hmm, the example above was wrong. Let me fix:

Actually let me just show the correct calculation:
40 + 66 + 72 + 8C + 00 = 40+66=A6, A6+72=118->18, 18+8C=A4, A4+00=A4
CHECKSUM = 0xA4

Complete frame: 40 66 72 8C 00 A4
```

**Note**:
- Timestamp is UTC+8 timezone
- 10-digit second-level Unix timestamp
- APP prioritizes network time, falls back to local time +8h offset

#### 4.5 OTA Upgrade

| Command | Bytes | Description |
|---------|-------|-------------|
| Enter OTA | `[0x20]` | Device enters OTA mode |
| Query version | `[0x21]` | Get firmware version (via 0xF1 Notify) |

#### 4.6 Log Management

| Command | Bytes | Description |
|---------|-------|-------------|
| Log level | `[0x30] + level` | Set log level |
| Log status | `[0x31]` | Query log status |

#### 4.7 Standalone Mode

| Command | Bytes | Description |
|---------|-------|-------------|
| Standalone | `[0x41]` | Enter standalone collection (BLE/WiFi off) |

### 5. HTTP API Specification

Device accessible via LAN IP after WiFi connection.

#### 5.1 Get Device Status

```
GET /api/status
```

**Response example**:
```json
{
  "version": "1.0.0",
  "battery": {
    "batt_pct": 55,
    "voltage": 3700
  },
  "ip": "192.168.1.100",
  "sd_free_mb": 1024
}
```

#### 5.2 Get File List

```
GET /api/files
```

#### 5.3 Download File

```
GET /api/download?file=data_20240618_120000.csv
```

#### 5.4 OTA Firmware Upload

```
POST /api/ota
Content-Type: application/octet-stream

[firmware binary data]
```

#### 5.5 Get Log List

```
GET /api/logs
```

#### 5.6 Download Log

```
GET /api/logs/download?file=log_20240618.txt
```

#### 5.7 Shutdown WiFi

```
POST /api/shutdown
```

### 6. Auto-Reconnect

APP implements BLE auto-reconnect:

1. Device disconnects, APP waits 3 seconds
2. Auto-attempts reconnect to last device
3. If reconnect fails, retries every 3 seconds
4. User-initiated disconnect does not trigger auto-reconnect

**Firmware recommendation**:
- Continue advertising after disconnect
- Keep GATT service configuration unchanged

### 7. Device Name Filter

APP filters devices by name prefix (case-insensitive):

```kotlin
val DEVICE_NAME_PREFIXES = listOf(
    "PPG-Monitor",
    "ESP32",
    "PPG"
)
```

**Recommended firmware name**: `PPG-Monitor-XXXX` (XXXX = device identifier)

### 8. BLE Communication Flow

```
┌─────────────────────────────────────────────────────────────────┐
│                        APP Communication Flow                    │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  1. Scan devices                                                │
│     APP: startScan() -> filter by name -> show device list      │
│                                                                 │
│  2. Connect device                                              │
│     APP: connect(device) -> wait STATE_CONNECTED                │
│     APP: discoverServices() -> wait onServicesDiscovered        │
│     APP: enableNotifications() -> enable Notify on 0xFFF1/2/3  │
│                                                                 │
│  3. Data exchange                                               │
│     ┌──────────────┐                    ┌──────────────┐        │
│     │     APP      │                    │    ESP32     │        │
│     ├──────────────┤                    ├──────────────┤        │
│     │              │ ── Write CMD ───>  │              │        │
│     │              │ <-- Notify Data ── │              │        │
│     │              │ ── Read Status ──> │              │        │
│     │              │ <-- Status Data ── │              │        │
│     └──────────────┘                    └──────────────┘        │
│                                                                 │
│  4. Disconnect & Reconnect                                      │
│     Device disconnects -> wait 3s -> auto-reconnect -> step 2   │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 9. Firmware Implementation Checklist

| Feature | Characteristic | Firmware Implementation |
|---------|---------------|------------------------|
| Device advertising | - | Broadcast name starting with "PPG-Monitor" |
| Status report | 0xFFF1 | Initialize 20-byte status data, update periodically |
| Real-time data | 0xFFF2 | Send 5-byte PPG data during measurement |
| Command receive | 0xFFF3 | Parse commands, execute corresponding operations |
| File list | 0xFFF4 | Return TF card file list JSON |
| WiFi credentials | CMD 0x10 | Parse frame (2-byte SSID_LEN), verify checksum, connect WiFi |
| Query status | CMD 0x22 | Update Status characteristic (battery, version, WiFi) |
| Query SD card | CMD 0x23 | Send 4-byte response [free_h][free_l][total_h][total_l] |
| Query battery | CMD 0x24 | Send 1-byte response [batt_pct] |
| Time sync | CMD 0x40 | Parse frame, set system time |
| OTA mode | CMD 0x20 | Enter OTA, wait for firmware upload |
| Standalone | CMD 0x41 | Enter standalone collection mode |

### 10. Common Firmware Issues

| Issue | Possible Cause | Solution |
|-------|---------------|----------|
| APP can't find device | Device name doesn't match | Use "PPG-Monitor" prefix |
| Status all zeros | Status not initialized | Write initial status data at startup |
| WiFi connect failed | SSID contains quotes | Check SSID is correct |
| Checksum error | Algorithm mismatch | Use SUM checksum (not XOR) |
| WiFi Add empty SSID | Wrong SSID_LEN format | Use 2-byte big-endian SSID_LEN |
| OTA failed | Not in OTA mode | Enter OTA mode on 0x20 command |
| BLE read timeout | Old callback API | Support both Android 13+ and legacy callbacks |

---

## Tech Stack

| Technology | Description |
|------------|-------------|
| Kotlin | Development language |
| Jetpack Compose | UI framework (Material 3) |
| MVVM | Architecture pattern |
| Hilt | Dependency injection |
| Android BLE API | Bluetooth communication |
| Retrofit + OkHttp | HTTP communication |
| Room | Local data storage |
| Kotlin Coroutines | Async programming |
| Apache Commons Compress | 7z file extraction |

## UI Page Flow

```
App Launch
    │
    ▼
┌─────────────┐     Not connected    ┌──────────────┐
│  Device Page │ ──────────────────>  │  Scan List    │
│  (Home)      │                      │  Tap to connect│
└──────┬──────┘                      └──────────────┘
       │ Connected
       ▼
┌─────────────────────────────────────────────┐
│              Bottom Navigation                │
│  ┌──────────┐ ┌──────────┐ ┌──────────────┐ │
│  │ Monitor  │ │   Data   │ │   Settings   │ │
│  │ (default)│ │          │ │              │ │
│  └──────────┘ └──────────┘ └──────────────┘ │
│                                               │
│  ┌─────────────────────────────────────────┐ │
│  │           Page Content Area              │ │
│  └─────────────────────────────────────────┘ │
└─────────────────────────────────────────────┘
```

**Navigation Routes**:
- `device_scan` — Scan & connect
- `monitor` — Real-time monitoring (main page)
- `data_list` — File list
- `settings` — Settings
- `wifi_provision` — WiFi provisioning
- `ota_upgrade` — OTA upgrade

## Android Version Compatibility

| Version | Handling |
|---------|----------|
| Android 11 (API 30) | Requires `ACCESS_FINE_LOCATION` for BLE scan |
| Android 12 (API 31) | New `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` permissions |
| Android 13 (API 33) | Notifications require `POST_NOTIFICATIONS` permission, new BLE read callback signature |
| Android 14 (API 34) | Foreground service type declaration (`foregroundServiceType`) |
| Android 15 (API 35) | Edge-to-edge enforcement, predictive back gestures |

## Security

- **TLS Certificate Verification**: System default TrustManager + hostname verification
- **Zip Slip Protection**: Path validation during 7z extraction
- **BLE Connection Safety**: Wait for service discovery before returning
- **Thread Safety**: Mutex protection for shared data
- **Permission Management**: Version-aware permission requests (Android 11-14)

## Environment Requirements

- **Android Studio**: Ladybug (2024.2) or newer
- **JDK**: 17
- **Android SDK**: API 30 ~ 35
- **Gradle**: 8.11.1
- **AGP**: 8.8.2

## Build

```bash
# Debug version
./gradlew assembleDebug

# Release version
./gradlew assembleRelease
```

APK output path: `app/build/outputs/apk/`

## Project Structure

```
ESP32-C3-PPG-APP/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle/libs.versions.toml        # Version Catalog
├── .github/workflows/build.yml      # CI/CD
└── app/src/main/java/org/tan/ppgtoolapp/
    ├── PpgApplication.kt            # Application (Hilt)
    ├── MainActivity.kt              # Main Activity
    ├── di/
    │   └── DatabaseModule.kt        # Hilt Room database module
    ├── data/
    │   ├── ble/
    │   │   ├── BleManager.kt        # BLE connection, auto-reconnect
    │   │   └── PpgGattProfile.kt    # GATT UUID and command definitions
    │   ├── network/
    │   │   ├── DeviceApi.kt         # HTTP API interface
    │   │   ├── HttpRepository.kt    # HTTP repository
    │   │   └── ...
    │   ├── local/
    │   │   ├── AppDatabase.kt       # Room database
    │   │   ├── FileMetadata.kt      # Downloaded file entity
    │   │   ├── FileMetadataDao.kt   # File metadata DAO
    │   │   ├── PpgRecord.kt         # PPG data record model
    │   │   ├── CsvParser.kt         # Binary PPG file parser
    │   │   └── PpgAnalyzer.kt       # Statistical analysis (HR/SpO2/HRV)
    │   └── wifi/
    │       └── WifiScanner.kt       # WiFi scanner
    ├── util/
    │   ├── ExportHelper.kt          # CSV/PDF export + Share Sheet
    │   ├── NotificationHelper.kt    # Download progress notifications
    │   └── PermissionHelper.kt      # Permission handling
    ├── viewmodel/
    │   ├── MonitorViewModel.kt      # Monitor page logic
    │   ├── DataViewModel.kt         # Data download & file management
    │   ├── OtaViewModel.kt          # OTA upgrade logic
    │   ├── WifiProvisionViewModel.kt # WiFi provisioning (scan + manual)
    │   └── SettingsViewModel.kt     # Settings page logic
    └── ui/screens/
        ├── MonitorScreen.kt         # Real-time monitoring UI
        ├── DeviceScreen.kt          # Device scan/connect
        ├── DataScreen.kt            # File download & management
        ├── AnalysisScreen.kt        # Offline data analysis
        ├── OtaScreen.kt             # OTA upgrade
        ├── WifiProvisionScreen.kt   # WiFi provisioning
        └── SettingsScreen.kt        # Settings
```

## Firmware Repositories

- **APP Repository**: `https://github.com/Tan1347/ESP32-C3-PPG-APP`
- **ESP32-C3 Firmware**: `https://github.com/Tan1347/ESP32-C3_PPG_Data_Collector`

## Quick Reference for Firmware Developers

### Key Implementation Points

| Point | Description |
|-------|-------------|
| Device name | Start with "PPG-Monitor" |
| GATT Service | UUID: 0000fff0-... |
| Status characteristic | Must initialize and update periodically |
| Command receive | Support 0x01-0x41 commands |
| Checksum | All command frames use SUM (not XOR) |
| WiFi SSID | 2-byte big-endian length, no quotes |
| Timestamp | UTC+8, 10-digit seconds |
| Battery | Only send percentage (batt_pct), not voltage |

### Debug Tips

1. **Use logs**: Both APP and firmware print hex data
2. **Compare frames**: Ensure send/receive data matches
3. **Verify checksum**: Both sides use same algorithm (SUM)
4. **Status initialization**: Avoid reading all-zero data
5. **WiFi Add format**: Use 2-byte big-endian SSID_LEN

## FAQ

**Q: Can't find device?**
- Confirm device is powered on and advertising
- Device name starts with `PPG-Monitor`, `ESP32`, or `PPG`
- Check Bluetooth permissions granted

**Q: No data after connection?**
- Confirm Notify is enabled (need to write CCC Descriptor)
- Check GATT UUID matches this document
- Check Android Logcat for BLE logs

**Q: Auto-reconnect not working?**
- Confirm device continues advertising after disconnect
- Check device name matches filter rules

**Q: WiFi provisioning failed?**
- Check BLE frame hex data in logs
- Confirm firmware checksum algorithm matches APP (SUM)
- Confirm SSID doesn't contain extra quotes
- Hidden WiFi uses "Manual Add" function

**Q: OTA upgrade failed?**
- Confirm 7z package contains `.bin` firmware file
- Check device battery is sufficient
- Don't disconnect during upgrade
