# Android App 重构清单

> 所有重构项已完成

---

## 已完成

| 优先级 | 问题 | 状态 |
|--------|------|------|
| P0 | BleManager 拆分 | ✅ |
| P0 | OtaViewModel 提取 OtaRepository | ✅ |
| P0 | HttpRepository 错误处理 | ✅ |
| P2 | 通知逻辑移出 ViewModel | ✅ |
| P2 | 导航路由类型安全 | ✅ |
| P3 | 无接口抽象，不可测试 (A10) | ✅ |

---

## A10 重构详情

为 BleManager、HttpRepository、WifiScanner 添加接口抽象，支持 mock 测试。

### 新增接口

| 接口 | 包路径 | 实现类 |
|------|--------|--------|
| `BleScannerProvider` | `data.ble` | BleManager |
| `BleConnectionProvider` | `data.ble` | BleManager |
| `BleCommandProvider` | `data.ble` | BleManager |
| `DeviceHttpApi` | `data.network` | HttpRepository |
| `WifiScanProvider` | `data.wifi` | WifiScanner |

### Hilt 绑定

`di/AppModule.kt` 通过 `@Binds` 将接口绑定到实现类。

### ViewModel 依赖变更

| ViewModel | 重构前 | 重构后 |
|-----------|--------|--------|
| DeviceVM | BleManager | BleScannerProvider + BleConnectionProvider |
| SettingsVM | BleManager | BleCommandProvider |
| MonitorVM | BleManager + HttpRepository | BleConnectionProvider + BleCommandProvider + DeviceHttpApi |
| DataVM | BleManager + HttpRepository | BleConnectionProvider + BleCommandProvider + DeviceHttpApi |
| OtaVM | BleManager + HttpRepository | BleConnectionProvider + BleCommandProvider + DeviceHttpApi |
| WifiProvisionVM | WifiScanner + BleManager + HttpRepository | WifiScanProvider + BleCommandProvider + DeviceHttpApi |
