# Android App 性能分析报告

生成日期: 2026-06-26

---

## 🔴 关键瓶颈（影响 >50%）

### ~~1. [MonitorScreen:342-385] 每次重组都重绘 Canvas~~ ✅ 已优化

`PpgWaveform` composable 每次发射都接收新的 `List<Float>` 实例（不同对象引用），Compose 无法跳过重组。`Canvas` 的 `onDraw` lambda 每次绘制都重建 `Path` 对象。`MetricCard` composable 即使数值未变也会在每个包时重组。

**修复方案：** 将波形渲染与指标显示分离。使用 `remember` 配合稳定键。考虑使用 `AndroidView` 自定义 View 进行高频波形渲染。

### ~~2. [CsvParser:24] 整个文件加载到内存~~ ✅ 已优化（流式解析）

`file.readBytes()` 将整个二进制文件加载到单个 `ByteArray`。10MB 文件需要 10MB 字节数组 + ~34MB 的 `List<PpgRecord>`（714K 条记录 × ~48 字节/条）。在内存受限设备上有 `OutOfMemoryError` 风险。

**修复方案：** 使用流式 `FileInputStream` 缓冲读取，或增量处理记录。

---

## 🟡 次要问题

### ~~3. [PpgAnalyzer:19-20, 37] 遍历记录列表 5 次~~ ✅ 已优化（单遍统计）

`analyze()` 方法遍历 `records` 5 次，产生中间列表分配：
1. `records.filter { hrValid }.map { heartRate }` — 2 次遍历
2. `records.filter { spo2Valid }.map { spo2 }` — 2 次遍历
3. `detectSpo2Events(records)` — 1 次遍历

对于 714K 条记录，意味着 5 次迭代和 4 次中间 `List` 分配。

**修复方案：** 单次遍历统计，同时累积所有指标。

### ~~4. [DataScreen:76, 123] 每个文件项 O(n×m) 线性扫描~~ ✅ 已优化（Set 查找）

`LazyColumn` 中每个 item 调用 `state.downloadedFiles.any { it.fileName == file }`。50 个设备文件和 20 个已下载文件，每次重组需要 1000 次比较。

**修复方案：** 预计算 `Set<String>` 的已下载文件名，每项 O(1) 查找。

### ~~5. [DeviceScreen:70-73] 排序未记忆化~~ ✅ 已优化（remember）

`sortedDevices` 在 composable 函数体内计算，未使用 `remember`。每次重组都重新排序设备列表，调用 `isMatchingDevice()` 遍历前缀。

**修复方案：** 用 `remember(devices)` 包裹。

### ~~6. [HttpRepository/DataViewModel] 进度更新过于频繁~~ ✅ 已优化（节流到每秒 10 次）

下载进度回调在每个 8KB 块上触发。10MB 文件约 1,250 次状态更新。每次更新触发 `_state.update { }` 和 `NotificationHelper.showProgress()`。

**修复方案：** 节流至每秒最多 10-20 次更新。

### ~~7. [OtaViewModel:294-296] JSON 双重序列化/解析~~ ✅ 已优化（直接传递 JSONObject）

`parseReleases()` 先调用 `arr.getJSONObject(i).toString()` 序列化，再传给 `parseRelease()` 用 `JSONObject(json)` 重新解析。

**修复方案：** 直接传递 `JSONObject` 给 `parseRelease()`。

### ~~8. [BleManager:251-266] 热路径无条件格式化 Hex~~ ✅ 已优化（日志级别检查）

每个 BLE 通知都通过 `joinToString("%02X".format(it))` 格式化 hex 字符串。格式化工作在日志级别检查之前无条件执行。

**修复方案：** 用 `if (Log.isLoggable(TAG, Log.VERBOSE))` 守护。

### ~~9. [DataViewModel:153] N+1 数据库查询~~ ✅ 已优化（批量查询）

`fileMetadataDao.exists(it)` 对每个文件名单独查询。50 个文件发出 50 条 SQL。

**修复方案：** 使用单条 `SELECT fileName FROM downloaded_files WHERE fileName IN (...)` 查询。

### ~~10. [MonitorViewModel:173] BLE 响应固定 500ms 延迟~~ ✅ 已优化（动态超时）

`delay(BLE_RESPONSE_DELAY_MS)` 固定等待 500ms，无论实际响应时间。如果 50ms 就收到响应，用户多等 450ms。

**修复方案：** 使用 `first()` 配合超时的响应 Flow。
