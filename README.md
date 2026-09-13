# KEMI Pads

面向 KEMI Android 12 双屏 PAD 的 Finder 风格文件与系统管理器。

## 项目简介

KEMI Pads 是为 KEMI 双屏 Android PAD 定制的一体化文件与系统管理入口。它借鉴 macOS Finder 的信息结构和操作习惯，并针对无鼠标、以触控为主的 PAD 场景重新设计，让用户通过一个应用即可管理下载文件、U 盘、局域网资源、全部应用、双屏任务及设备运行状态。

项目的重点不是堆叠功能，而是缩短常用操作路径：点击文件直接交给系统中合适的应用打开，常用分享和文件操作保持在当前列表；复制或剪切时，目标文件夹选择窗口出现在副屏，并记住上一次有效目录，不打断副屏原有应用；一键清理同时释放普通后台进程、应用缓存和系统可回收缓存，并保护主屏与副屏前台进程。

### 核心定位

- **一个入口连接整台设备**：文件、应用、双屏、USB、局域网和系统监控统一管理。
- **为触控操作设计**：减少层级、减少确认步骤，使用清晰的大触控目标和直接操作。
- **发挥双屏价值**：主屏保持当前工作，副屏承担目标目录选择和应用启动等辅助任务。
- **保护系统安全**：删除范围限制在下载目录和外接 U 盘；系统、服务及双屏前台进程默认保护。
- **保持 Finder 体验**：统一的文件图标、列表结构、路径导航和 macOS 风格上下文菜单。

## v1.0.0 功能

- 下载、最近使用、收藏、本机文件和 USB 移动盘管理
- 文件直接打开、系统分享、复制到、剪切到、递归删除和刷新；文件夹可自动打包为 ZIP 后分享
- 返回上一级后可连续前进，目录历史不会在两个位置之间反复跳转
- 复制/剪切目标目录在副屏以浮动窗口显示，并记忆上一次目录
- 左侧提供退出入口，可同时关闭 KEMI Pads 的主屏与副屏任务
- USB 插拔实时检测；连接后入口高亮，断开后自动禁用
- 双屏应用启动和主屏/副屏前台进程保护
- 基于设备系统权限通道读取每个 Display 的真实前台任务，不依赖安装目录
- 全部应用支持主屏/副屏打开、清理缓存和卸载；系统应用与自身受到保护
- 工具集提供百分之一秒秒表（数字与圆形表盘同步、计次）和双模式计算器（普通模式采用 Windows 计算器的上下文百分比规则；程序员模式支持 HEX、DEC、OCT、BIN、位宽与位运算）
- CPU、内存、交换分区、存储、电池、温度、网络和活动进程监控；进程 CPU 占用按整机口径每 3 秒刷新；清理结果分别核算内存释放与磁盘缓存删除，不混算容量
- 行为记录可在用户明确开启后开机常驻，关联双屏 App/Activity、控件操作、系统健康与崩溃/ANR 标记；记录包包含机器可读 JSONL、清单、校验值和供项目部/大模型阅读的时间线
- 局域网 HTTP 文件浏览、下载和本机下载目录分发
- KEMI Send 自动发现与收发入口

## 构建环境

- JDK 17
- Android SDK Platform 36
- Android SDK Build Tools 36.0.0
- Git

项目包含 Gradle Wrapper，不需要单独安装 Gradle。`local.properties` 不进入版本库；Android Studio 会自动生成，也可以手动配置：

```properties
sdk.dir=/你的/Android/Sdk/路径
```

确保 `JAVA_HOME` 指向 JDK 17，且 `ANDROID_HOME` 或 `ANDROID_SDK_ROOT` 指向 Android SDK。

## 编译

macOS / Linux：

```bash
./gradlew assembleDebug
```

Windows：

```bat
gradlew.bat assembleDebug
```

Debug 包只用于普通功能开发。正式 Release 必须使用 KEMI 固件对应的平台证书，构建脚本不会生成未签名或错误签名的 Release：

```bash
KEMI_PLATFORM_KEYSTORE=/安全位置/debug.keystore \
KEMI_PLATFORM_STORE_PASSWORD=你的密码 \
KEMI_PLATFORM_KEY_ALIAS=androiddebugkey \
KEMI_PLATFORM_KEY_PASSWORD=你的密码 \
./gradlew assembleRelease
```

平台私钥和密码不得提交到 Git。构建后应使用 Android SDK 的 `apksigner verify --print-certs` 核对证书摘要，再安装到目标设备。

输出位置：

```text
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release.apk
```

## 安装

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

应用包名：`com.kemi.mypad`

### KEMI PAD 系统权限

系统能力来自与目标固件一致的平台证书，而不是 APK 文件名、安装目录或 `/system/xbin/su`。平台签名版即使安装在 `/data/app`，也能取得固件授予的 `REAL_GET_TASKS`、`DUMP`、`FORCE_STOP_PACKAGES` 等签名权限；应用通过 Android 系统 API 读取每个 Display 的真实前台任务和活动进程。目标设备上的 `su` 仅允许 root/shell 组执行，普通应用 UID 不具备该能力，因此业务代码不得依赖它。

平台签名版在安装时即取得固件授予的系统权限，不要求用户进入设置页二次授权。清理前通过权威任务栈识别主屏和副屏前台进程；清理时仅终止普通后台进程，但会通过 Package Manager 清理所有应用的可删除缓存，并以清理后的 StorageStats 快照计算真实释放量。

## 正式版本

`bin/KEMI-Pads-v1.0.0.apk` 为 v1.0.0 内部正式版，SHA-256 校验值位于同目录的 `.sha256` 文件。

> v1 面向固定的 Android 12 双屏系统镜像，`targetSdkVersion` 保持为 31，以维持该设备上的存储及系统管理权限行为。
