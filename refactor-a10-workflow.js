export const meta = {
  name: 'refactor-a10-interfaces',
  description: 'A10 重构：为 BleManager/HttpRepository/WifiScanner 添加接口抽象',
  phases: [
    { title: '创建接口', detail: '创建 5 个接口文件 + Hilt AppModule' },
    { title: '实现接口', detail: '修改 BleManager/HttpRepository/WifiScanner 实现接口' },
    { title: '更新ViewModel', detail: '修改 6 个 ViewModel 使用接口注入' },
    { title: '验证编译', detail: '检查所有文件编译正确性' },
  ],
}

const BASE = 'e:/project/ESP32-C3-PPG-APP/app/src/main/java/org/tan/ppgtoolapp'

// ===== Phase 1: 创建接口文件（并行） =====
phase('创建接口')

await parallel([
  () => agent(`在 ${BASE}/data/ble/ 目录下创建 BleScannerProvider.kt 文件，内容如下：

package org.tan.ppgtoolapp.data.ble

import android.bluetooth.BluetoothDevice
import kotlinx.coroutines.flow.Flow

/**
 * BLE 设备扫描能力接口
 */
interface BleScannerProvider {
    fun scan(useUuidFilter: Boolean = false): Flow<BleDevice>
    fun stopScan()
    fun getBluetoothDevice(address: String): BluetoothDevice?
}
`, { label: '创建 BleScannerProvider', phase: '创建接口' }),

  () => agent(`在 ${BASE}/data/ble/ 目录下创建 BleConnectionProvider.kt 文件，内容如下：

package org.tan.ppgtoolapp.data.ble

import android.bluetooth.BluetoothDevice
import kotlinx.coroutines.flow.StateFlow

/**
 * BLE 连接生命周期管理接口
 */
interface BleConnectionProvider {
    val connectionState: StateFlow<ConnectionState>
    suspend fun connect(device: BluetoothDevice, deviceName: String = ""): Boolean
    fun disconnect()
    fun isConnected(): Boolean
    fun getConnectedDeviceMac(): String?
}
`, { label: '创建 BleConnectionProvider', phase: '创建接口' }),

  () => agent(`在 ${BASE}/data/ble/ 目录下创建 BleCommandProvider.kt 文件，内容如下：

package org.tan.ppgtoolapp.data.ble

import kotlinx.coroutines.flow.SharedFlow
import java.util.UUID

/**
 * BLE 命令写入、特征读取、数据流接收接口
 */
interface BleCommandProvider {
    val liveData: SharedFlow<ByteArray>
    val cmdResponse: SharedFlow<ByteArray>
    suspend fun writeCommand(command: ByteArray): Boolean
    suspend fun readCharacteristic(uuid: UUID): ByteArray?
    suspend fun queryDeviceStatus(): Boolean
    suspend fun querySdCardStatus(): Boolean
    suspend fun queryBatteryStatus(): Boolean
    suspend fun startUartRecord(baudRate: Int, dataBits: Int, parity: Int, stopBits: Int): Boolean
    suspend fun stopUartRecord(): Boolean
    suspend fun syncTime(timestamp: Long): Boolean
    suspend fun triggerFileDownload(): String?
}
`, { label: '创建 BleCommandProvider', phase: '创建接口' }),

  () => agent(`在 ${BASE}/data/network/ 目录下创建 DeviceHttpApi.kt 文件。先读取 ${BASE}/data/network/HttpRepository.kt 和 ${BASE}/data/network/DeviceApi.kt 了解现有的返回类型（如 DownloadResult、ApiResult、DeviceStatusResponse、OperationResult、OtaInfoResponse 等），然后创建接口：

package org.tan.ppgtoolapp.data.network

import java.io.File

/**
 * 与 ESP32 设备的 HTTP 通信接口
 */
interface DeviceHttpApi {
    fun setDeviceIp(ip: String)
    suspend fun getFileList(): List<String>
    suspend fun downloadFile(filename: String, onProgress: ((Int, Long, Long) -> Unit)? = null): DownloadResult?
    suspend fun getDeviceStatus(): DeviceStatusResponse?
    suspend fun uploadFirmware(file: File, onProgress: ((Int) -> Unit)? = null): Boolean
    suspend fun downloadFromGitHub(url: String, outputFile: File, onProgress: ((Int) -> Unit)? = null): Boolean
}

注意：需要根据 HttpRepository.kt 中的实际返回类型来确认接口中的类型签名。如果 HttpRepository.getDeviceStatus() 返回的是 DeviceStatusResponse? 而不是 ApiResult，那就用 DeviceStatusResponse?。如果 uploadFirmware 返回 Boolean 就用 Boolean。
`, { label: '创建 DeviceHttpApi', phase: '创建接口' }),

  () => agent(`在 ${BASE}/data/wifi/ 目录下创建 WifiScanProvider.kt 文件。先读取 ${BASE}/data/wifi/WifiScanner.kt 了解 WifiNetwork 类的包路径，然后创建接口：

package org.tan.ppgtoolapp.data.wifi

import kotlinx.coroutines.flow.Flow

/**
 * WiFi 网络扫描接口
 */
interface WifiScanProvider {
    fun scan24GHz(): Flow<List<WifiNetwork>>
    fun isWifiEnabled(): Boolean
    fun requestEnableWifi(): Boolean
}
`, { label: '创建 WifiScanProvider', phase: '创建接口' }),

  () => agent(`在 ${BASE}/di/ 目录下创建 AppModule.kt 文件。先读取 ${BASE}/di/DatabaseModule.kt 了解现有 Hilt 模块的风格，然后创建：

package org.tan.ppgtoolapp.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.tan.ppgtoolapp.data.ble.BleCommandProvider
import org.tan.ppgtoolapp.data.ble.BleConnectionProvider
import org.tan.ppgtoolapp.data.ble.BleManager
import org.tan.ppgtoolapp.data.ble.BleScannerProvider
import org.tan.ppgtoolapp.data.network.DeviceHttpApi
import org.tan.ppgtoolapp.data.network.HttpRepository
import org.tan.ppgtoolapp.data.wifi.WifiScanProvider
import org.tan.ppgtoolapp.data.wifi.WifiScanner
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds @Singleton
    abstract fun bindBleScannerProvider(impl: BleManager): BleScannerProvider

    @Binds @Singleton
    abstract fun bindBleConnectionProvider(impl: BleManager): BleConnectionProvider

    @Binds @Singleton
    abstract fun bindBleCommandProvider(impl: BleManager): BleCommandProvider

    @Binds @Singleton
    abstract fun bindDeviceHttpApi(impl: HttpRepository): DeviceHttpApi

    @Binds @Singleton
    abstract fun bindWifiScanProvider(impl: WifiScanner): WifiScanProvider
}
`, { label: '创建 AppModule', phase: '创建接口' }),
])

// ===== Phase 2: 修改实现类（并行） =====
phase('实现接口')

await parallel([
  () => agent(`修改 ${BASE}/data/ble/BleManager.kt，让它实现三个接口。

步骤：
1. 读取文件
2. 在 class BleManager 的声明上添加接口实现：
   class BleManager @Inject constructor(...) : BleScannerProvider, BleConnectionProvider, BleCommandProvider
3. 为以下方法添加 override 关键字（这些方法签名必须与接口完全匹配）：
   - scan() -> override
   - stopScan() -> override
   - getBluetoothDevice() -> override
   - connectionState 属性 -> 如果接口声明了 val connectionState，确保 BleManager 中的声明匹配
   - connect() -> override
   - disconnect() -> override
   - isConnected() -> override
   - getConnectedDeviceMac() -> override
   - liveData -> 确认匹配
   - cmdResponse -> 确认匹配
   - writeCommand() -> override
   - readCharacteristic() -> override
   - queryDeviceStatus() -> override
   - querySdCardStatus() -> override
   - queryBatteryStatus() -> override
   - startUartRecord() -> override
   - stopUartRecord() -> override
   - syncTime() -> override
   - triggerFileDownload() -> override

4. 添加必要的 import：
   - import org.tan.ppgtoolapp.data.ble.BleScannerProvider
   - import org.tan.ppgtoolapp.data.ble.BleConnectionProvider
   - import org.tan.ppgtoolapp.data.ble.BleCommandProvider

注意：不要修改任何方法的实现逻辑，只添加 override 关键字和接口声明。
`, { label: 'BleManager 实现接口', phase: '实现接口' }),

  () => agent(`修改 ${BASE}/data/network/HttpRepository.kt，让它实现 DeviceHttpApi 接口。

步骤：
1. 读取文件和接口文件 ${BASE}/data/network/DeviceHttpApi.kt
2. 在 class HttpRepository 的声明上添加：
   class HttpRepository @Inject constructor(...) : DeviceHttpApi
3. 为接口中声明的方法添加 override 关键字
4. 添加 import org.tan.ppgtoolapp.data.network.DeviceHttpApi

注意：只添加 override 和接口声明，不修改实现逻辑。如果接口中的方法签名与实际方法不匹配，请以实际方法为准调整接口（通过修改接口文件）。
`, { label: 'HttpRepository 实现接口', phase: '实现接口' }),

  () => agent(`修改 ${BASE}/data/wifi/WifiScanner.kt，让它实现 WifiScanProvider 接口。

步骤：
1. 读取文件和接口文件 ${BASE}/data/wifi/WifiScanProvider.kt
2. 在 class WifiScanner 的声明上添加：
   class WifiScanner @Inject constructor(...) : WifiScanProvider
3. 为 scan24GHz()、isWifiEnabled()、requestEnableWifi() 添加 override 关键字
4. 添加 import org.tan.ppgtoolapp.data.wifi.WifiScanProvider

注意：只添加 override 和接口声明，不修改实现逻辑。
`, { label: 'WifiScanner 实现接口', phase: '实现接口' }),
])

// ===== Phase 3: 更新 ViewModel（并行） =====
phase('更新ViewModel')

await parallel([
  () => agent(`修改 ${BASE}/viewmodel/DeviceViewModel.kt。

步骤：
1. 读取文件
2. 将构造参数中的 BleManager 替换为 BleScannerProvider 和 BleConnectionProvider：
   @Inject constructor(
       private val bleScanner: BleScannerProvider,
       private val bleConnection: BleConnectionProvider
   )
3. 更新 import：
   - 移除 import org.tan.ppgtoolapp.data.ble.BleManager
   - 添加 import org.tan.ppgtoolapp.data.ble.BleScannerProvider
   - 添加 import org.tan.ppgtoolapp.data.ble.BleConnectionProvider
4. 将文件中所有 bleManager. 调用替换为对应的接口调用：
   - 扫描相关（scan, stopScan, getBluetoothDevice）-> bleScanner.
   - 连接相关（connectionState, connect, disconnect）-> bleConnection.
5. 确保所有引用都正确更新
`, { label: '更新 DeviceVM', phase: '更新ViewModel' }),

  () => agent(`修改 ${BASE}/viewmodel/SettingsViewModel.kt。

步骤：
1. 读取文件
2. 将构造参数中的 BleManager 替换为 BleCommandProvider：
   @Inject constructor(
       private val updateChecker: UpdateChecker,
       private val timeSyncHelper: TimeSyncHelper,
       private val bleCommander: BleCommandProvider
   )
3. 更新 import：
   - 移除 import org.tan.ppgtoolapp.data.ble.BleManager
   - 添加 import org.tan.ppgtoolapp.data.ble.BleCommandProvider
4. 将文件中所有 bleManager. 调用替换为 bleCommander.
`, { label: '更新 SettingsVM', phase: '更新ViewModel' }),

  () => agent(`修改 ${BASE}/viewmodel/MonitorViewModel.kt。

步骤：
1. 读取文件
2. 将构造参数替换为接口：
   @Inject constructor(
       private val bleConnection: BleConnectionProvider,
       private val bleCommander: BleCommandProvider,
       private val httpRepository: DeviceHttpApi
   )
3. 更新 import：
   - 移除 BleManager 和 HttpRepository 的 import
   - 添加 BleConnectionProvider、BleCommandProvider、DeviceHttpApi 的 import
4. 替换所有 bleManager. 调用：
   - 连接相关（connectionState, isConnected）-> bleConnection.
   - 命令相关（liveData, cmdResponse, writeCommand, readCharacteristic, queryDeviceStatus, querySdCardStatus, queryBatteryStatus）-> bleCommander.
`, { label: '更新 MonitorVM', phase: '更新ViewModel' }),

  () => agent(`修改 ${BASE}/viewmodel/DataViewModel.kt。

步骤：
1. 读取文件
2. 将构造参数替换为接口：
   @Inject constructor(
       @ApplicationContext private val context: Context,
       private val bleConnection: BleConnectionProvider,
       private val bleCommander: BleCommandProvider,
       private val httpRepository: DeviceHttpApi,
       private val fileMetadataDao: FileMetadataDao
   )
3. 更新 import
4. 替换所有 bleManager. 调用：
   - getConnectedDeviceMac() -> bleConnection.
   - triggerFileDownload() -> bleCommander.
`, { label: '更新 DataVM', phase: '更新ViewModel' }),

  () => agent(`修改 ${BASE}/viewmodel/OtaViewModel.kt。

步骤：
1. 读取文件
2. 将构造参数替换为接口：
   @Inject constructor(
       @ApplicationContext private val context: Context,
       private val httpRepository: DeviceHttpApi,
       private val updateChecker: UpdateChecker,
       private val bleConnection: BleConnectionProvider,
       private val bleCommander: BleCommandProvider
   )
3. 更新 import
4. 替换所有 bleManager. 调用：
   - isConnected() -> bleConnection.
   - readCharacteristic() -> bleCommander.
`, { label: '更新 OtaVM', phase: '更新ViewModel' }),

  () => agent(`修改 ${BASE}/viewmodel/WifiProvisionViewModel.kt。

步骤：
1. 读取文件
2. 将构造参数替换为接口：
   @Inject constructor(
       private val wifiScanner: WifiScanProvider,
       private val bleCommander: BleCommandProvider,
       private val httpRepository: DeviceHttpApi
   )
3. 更新 import：
   - 移除 BleManager、HttpRepository、WifiScanner 的 import
   - 添加 BleCommandProvider、DeviceHttpApi、WifiScanProvider 的 import
4. 替换所有 bleManager. 调用为 bleCommander.
`, { label: '更新 WifiProvisionVM', phase: '更新ViewModel' }),
])

// ===== Phase 4: 验证 =====
phase('验证编译')

await agent(`检查以下文件的编译正确性：

1. 读取所有修改过的文件，检查：
   - 接口声明是否正确添加
   - override 关键字是否遗漏
   - import 语句是否完整
   - ViewModel 中的变量名是否与注入参数一致

2. 检查接口文件与实现类的方法签名是否完全匹配（参数类型、返回类型）

3. 列出发现的所有问题

文件列表：
- ${BASE}/data/ble/BleScannerProvider.kt
- ${BASE}/data/ble/BleConnectionProvider.kt
- ${BASE}/data/ble/BleCommandProvider.kt
- ${BASE}/data/network/DeviceHttpApi.kt
- ${BASE}/data/wifi/WifiScanProvider.kt
- ${BASE}/di/AppModule.kt
- ${BASE}/data/ble/BleManager.kt
- ${BASE}/data/network/HttpRepository.kt
- ${BASE}/data/wifi/WifiScanner.kt
- ${BASE}/viewmodel/DeviceViewModel.kt
- ${BASE}/viewmodel/SettingsViewModel.kt
- ${BASE}/viewmodel/MonitorViewModel.kt
- ${BASE}/viewmodel/DataViewModel.kt
- ${BASE}/viewmodel/OtaViewModel.kt
- ${BASE}/viewmodel/WifiProvisionViewModel.kt
`, { label: '验证编译', phase: '验证编译' })
