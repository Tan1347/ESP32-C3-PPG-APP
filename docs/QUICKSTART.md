# 快速开始指南

## 环境准备

### 1. 安装 Android Studio

下载并安装 [Android Studio](https://developer.android.com/studio) Ladybug (2024.2) 或更新版本。

### 2. 配置 JDK

确保 JDK 17 已安装并配置：

```bash
java -version
# 应显示 openjdk version "17.x.x"
```

### 3. 配置 Android SDK

在 Android Studio 中：
1. 打开 `File → Settings → Appearance & Behavior → System Settings → Android SDK`
2. 确保安装 Android SDK 35
3. 记录 SDK 路径（后续配置需要）

## 项目配置

### 1. 克隆项目

```bash
git clone https://github.com/kelven/PPGTool.git
cd PPGTool
```

### 2. 配置 local.properties

创建 `local.properties` 文件：

```properties
sdk.dir=/path/to/your/Android/Sdk
```

### 3. 配置签名（可选）

如需构建 Release 版本，配置签名信息：

**方式一：环境变量**
```bash
export KEYSTORE_STORE_PASSWORD=your_password
export KEYSTORE_KEY_ALIAS=your_alias
export KEYSTORE_KEY_PASSWORD=your_key_password
```

**方式二：创建 keystore.properties**
```properties
storePassword=your_password
keyAlias=your_alias
keyPassword=your_key_password
```

然后生成 keystore：
```bash
keytool -genkey -v -keystore release.keystore -alias your_alias -keyalg RSA -keysize 2048 -validity 10000
```

## 构建运行

### 1. 导入项目

1. 打开 Android Studio
2. 选择 `File → Open`
3. 选择项目根目录
4. 等待 Gradle 同步完成（首次可能需要 5-10 分钟）

### 2. 连接设备

**真机调试**：
1. 启用开发者选项（设置 → 关于手机 → 连续点击版本号 7 次）
2. 启用 USB 调试
3. 连接手机到电脑
4. 授权 USB 调试

**模拟器**：
1. 打开 `Tools → Device Manager`
2. 创建新设备（API 30+）
3. 启动模拟器

### 3. 运行应用

1. 选择目标设备
2. 点击 ▶️ 运行按钮
3. 等待构建和安装

**命令行运行**：
```bash
./gradlew installDebug
```

## 调试技巧

### 1. 查看日志

```bash
# 查看所有 PPG 相关日志
adb logcat | grep -i ppg

# 查看 BLE 通信日志
adb logcat | grep -i ble

# 查看崩溃日志
adb logcat *:E
```

### 2. 使用 Layout Inspector

1. 运行应用
2. 打开 `Tools → Layout Inspector`
3. 查看 Compose UI 层级

### 3. 使用 Network Inspector

1. 运行应用
2. 打开 `Tools → App Inspection → Network Inspector`
3. 查看 HTTP 请求

## 常见问题

### Q: Gradle 同步失败？

**A**: 检查网络连接，尝试以下方案：

1. 使用阿里云镜像（已配置）
2. 使用腾讯云 Gradle 镜像（已配置）
3. 清除 Gradle 缓存：
   ```bash
   rm -rf ~/.gradle/caches
   ./gradlew clean
   ```

### Q: 找不到设备？

**A**: 检查以下配置：

1. 确保设备已开启蓝牙
2. 确保位置权限已授予（Android 11-12）
3. 确保蓝牙权限已授予（Android 12+）
4. 检查设备是否在广播（名称以 `PPG-Monitor` 开头）

### Q: 构建速度慢？

**A**: 优化建议：

1. 使用 Gradle 构建缓存：
   ```bash
   ./gradlew assembleDebug --build-cache
   ```
2. 增加 Gradle 内存（`gradle.properties`）：
   ```properties
   org.gradle.jvmargs=-Xmx4096m
   ```
3. 启用并行构建：
   ```properties
   org.gradle.parallel=true
   ```

### Q: WiFi 扫描无结果？

**A**: 检查以下权限：

1. `ACCESS_FINE_LOCATION`（Android 11-12）
2. `NEARBY_WIFI_DEVICES`（Android 13+）
3. 确保位置服务已开启
4. 注意扫描频率限制（Android 9+ 每 2 分钟最多 4 次）

### Q: OTA 升级失败？

**A**: 检查以下问题：

1. 确保 7z 包中包含 `.bin` 文件
2. 确保设备电量充足（>20%）
3. 确保网络连接稳定
4. 检查设备是否在 OTA 模式

## 开发流程

### 1. 创建功能分支

```bash
git checkout -b feature/your-feature
```

### 2. 开发并测试

1. 编写代码
2. 本地测试
3. 确保无编译错误

### 3. 提交代码

```bash
git add .
git commit -m "feat: 添加你的功能"
```

### 4. 创建 Pull Request

1. 推送分支到远程
2. 创建 Pull Request
3. 等待代码审查
4. 合并到主分支

## 下一步

- 阅读 [架构设计](ARCHITECTURE.md) 了解系统设计
- 查看 [API 参考](API.md) 了解详细接口
- 查看 [更新日志](CHANGELOG.md) 了解最新变化
