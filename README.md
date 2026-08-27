# KEMI Pads

面向 KEMI Android 12 双屏 PAD 的 Finder 风格文件与系统管理器。

## v1.0.0 功能

- 下载、最近使用、收藏、本机文件和 USB 移动盘管理
- 文件直接打开、系统分享、复制到、剪切到、递归删除和刷新
- 复制/剪切目标目录在副屏以浮动窗口显示，并记忆上一次目录
- 左侧提供退出入口，可同时关闭 KEMI Pads 的主屏与副屏任务
- USB 插拔实时检测；连接后入口高亮，断开后自动禁用
- 双屏应用启动和主屏/副屏前台进程保护
- CPU、内存、交换分区、存储、电池、温度、网络和活动进程监控
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
./gradlew assembleRelease
```

Windows：

```bat
gradlew.bat assembleDebug
gradlew.bat assembleRelease
```

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

## 正式版本

`bin/KEMI-Pads-v1.0.0.apk` 为 v1.0.0 内部正式版，SHA-256 校验值位于同目录的 `.sha256` 文件。

> v1 面向固定的 Android 12 双屏系统镜像，`targetSdkVersion` 保持为 31，以维持该设备上的存储及系统管理权限行为。
