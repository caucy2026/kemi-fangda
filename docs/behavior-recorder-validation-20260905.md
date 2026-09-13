# 行为记录真机验证：2026-09-05

## 范围与授权

- 目标：用户明确授权的 KEMI 双屏 PAD `192.168.3.75:5555`。
- 允许变更：覆盖安装平台签名 KEMI Pads，开启行为记录并执行无破坏的应用切换测试。
- 禁止内容：未记录输入文字、密码、剪贴板、截图、音视频或网络正文。
- 导出边界：记录 ZIP 已在设备本机生成，但尚未复制到开发机；复制真实行为数据需要单独授权。

## 目标身份

- Android：12 / API 31。
- Build fingerprint：`huanglong/hi3781v730_tablet/hi3781v730:12/SP1A.210812.016/eng.yance.20260901.221805:userdebug/dev-keys`。
- 屏幕：内置屏幕 Display 0，HDMI 屏幕 Display 2，均为 1920×1280。
- 应用包：`com.kemi.mypad`，运行 UID 10084。
- 测试 APK SHA-256：`d5dbbd02172f4cfb5d8ce9626e03a2a4d395f82900cc19fec6792a892501daa8`。

## 权限实测

安装后 `dumpsys package com.kemi.mypad` 显示以下能力均为 `granted=true`：

- `REAL_GET_TASKS`
- `DUMP`
- `PACKAGE_USAGE_STATS`
- `WRITE_SECURE_SETTINGS`
- `READ_LOGS`
- `RECEIVE_BOOT_COMPLETED`

因此本功能使用平台签名系统权限，不依赖 `su` 或安装到 `/system/priv-app`。

## 刺激与观测

测试动作：

1. 保持副屏 KEMI 传书运行。
2. 主屏从桌面打开系统设置。
3. 主屏切换到 KEMI Pads。
4. 执行返回，回到系统设置。
5. 等待超过 10 秒，写入一次问题标记。

动态记录按顺序观察到：

- Display 2：`org.kemi.send/org.kemi.send.MainActivity` 持续保持前台。
- Display 0：Launcher → Settings → KEMI Pads → Settings。
- `UsageEvents` 的 Activity resumed/paused 与 Display 0 的任务变化一致。
- Accessibility 记录到 `TYPE_WINDOW_STATE_CHANGED`、`TYPE_WINDOWS_CHANGED` 和带有 `com.android.settings:id/recycler_view` 的 `TYPE_VIEW_SCROLLED`。
- 健康采样记录 CPU 约 16%、可用内存约 3.52 GB、温度状态 0、电量 100%，并附带两块屏幕的前台组件。
- 问题标记成功进入同一会话。

状态探针结果：

```text
enabled=true
running=true
accessibility=true
session=20260905-231846-boot6
bytes=9917
```

设备端导出结果：

```text
KEMI-Pads-行为记录-20260905-231846-boot6.zip
bytes=4788
```

## 发现并修正的问题

第一次从自定义后台广播调用 `startForegroundService()` 时，Android 12 正确抛出 `ForegroundServiceStartNotAllowedException`。这证明设置开关成功不等于记录服务运行。

修正后采用三条合法启动路径：

- 用户在 KEMI Pads 前台打开开关；
- `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` 的系统临时白名单；
- 持有 `DUMP` 权限的 ADB shell 显式启动导出的诊断服务。

覆盖安装后，`MY_PACKAGE_REPLACED` 自动恢复服务，实测 `startForegroundCount=1`，Accessibility 服务也由 system 绑定。

## 证据等级与剩余项

- S0：源码、Manifest、签名和 Lint 已验证。
- D1：真实设备的服务状态、双屏任务、UI 语义事件和系统采样已验证。
- R1：尚未完成自动重放。当前记录足以生成引导式复现步骤，但不保证恢复服务器响应、随机状态或无可访问性节点的 Canvas 内容。
- E1：需获得记录 ZIP 拉取授权后，核对 `manifest.json.eventsSha256`、读取 `timeline.md` 并在隔离实验机执行一次生成的复现脚本。

测试结束后已把主屏恢复到桌面；行为记录按用户授权保持开启。
