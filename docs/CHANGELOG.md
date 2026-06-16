# 更新日志

## [1.0.0] - 2026-06-16

### ✨ 新增功能

#### 设备监控
- 实时 PPG 波形显示（红光/红外）
- 心率、血氧、灌注指数、信号质量显示
- 设备状态栏（电量、固件版本、SD 卡容量）
- 30 秒自动刷新设备状态

#### WiFi 配网
- 2.4GHz WiFi 网络扫描
- 信号强度显示（0-4 级）
- 通过 BLE 发送 WiFi 凭据到设备
- 支持开放网络和加密网络

#### OTA 升级
- 从 GitHub Release 下载 7z 固件包
- 自动解压 7z 文件，查找 .bin 固件
- 支持选择本地 7z/bin 文件
- 可配置固件仓库地址（默认：`Tan1347/ESP32-C3_PPG_Data_Collector`）
- 上传进度显示

#### 应用自更新
- 从 GitHub Release 检查新版本
- 镜像优先下载策略
- 下载进度显示
- 多厂商安装器适配（MIUI、Samsung、Huawei、ColorOS、Vivo）

#### GitHub 加速
- 优选 DNS 解析
- 镜像代理支持（ghfast.top、ghproxy.net、github.moeyy.xyz）
- 远程 Hosts 文件获取
- 本地缓存（24 小时 TTL）

#### 数据管理
- TF 卡文件列表显示
- 文件下载到本地
- 文件类型图标区分

#### 时间同步
- 网络时间获取（https://ip.ddnspod.com/timestamp）
- Unix 13 位转 10 位时间戳
- UTC+8 时区处理
- 无网络时使用本地时间 + 时区偏移
- BLE 命令同步到设备（CMD_TIME_SYNC 0x40）

### 🔒 安全修复

- **TLS 证书验证**：修复证书验证绕过漏洞，使用系统默认 TrustManager + 主机名校验
- **Zip Slip 防护**：添加路径校验，防止 7z 解压目录穿越
- **BLE 连接竞态**：修复 `connect()` 在服务发现完成前返回的问题
- **线程安全**：使用 Mutex 保护 BLE 数据缓冲区，防止并发访问
- **HTTP 明文通信**：添加 `usesCleartextTraffic="true"` 配置

### 🐛 问题修复

- 修复 `isSecure` 运算符优先级错误
- 修复 `readCharacteristic` 异步返回问题
- 修复 LazyColumn 缺少 key 导致的重组性能问题
- 修复状态轮询重复启动问题
- 修复魔法数字，提取为命名常量

### 📦 依赖更新

- AGP: 8.7.3 → 8.8.2
- Gradle: 8.9 → 8.11.1
- 添加 Apache Commons Compress 1.27.1（7z 解压）
- 添加 XZ 1.9（7z 压缩支持）

### 🏗️ 架构改进

- 引入 Version Catalog（`libs.versions.toml`）统一管理依赖
- 阿里云 Maven 镜像加速
- 腾讯云 Gradle Wrapper 镜像
- GitHub Actions 自动构建发布
- 三层缓存策略（Gradle、SDK、构建产物）

### 📝 文档

- 新增架构设计文档（`docs/ARCHITECTURE.md`）
- 新增 API 参考文档（`docs/API.md`）
- 更新 README.md，添加安全特性说明
- 更新项目结构说明

---

## 版本说明

### 版本号规则

采用语义化版本：`主版本号.次版本号.修订号`

- **主版本号**：不兼容的 API 修改
- **次版本号**：向下兼容的功能性新增
- **修订号**：向下兼容的问题修正

### 标签格式

Git 标签格式：`v{版本号}-{短提交哈希}`

示例：`v1.0.0-abc1234`
