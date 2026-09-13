# KEMI Pads 行为记录与问题复现

## 目的

当 PAD 出现偶发卡死、ANR、崩溃、双屏应用切换错误或资源异常时，保留故障之前的用户操作语义和系统现场，使项目部或大模型不依赖口头描述即可重建时间线。

行为记录不是连续录屏，也不是键盘记录器。用户必须在 KEMI Pads 的“行为记录”页面明确打开总开关；关闭后立即停止新增采集，已有记录仍保留到自动轮换或用户导出。

## 采集链路

```text
开机 / 应用恢复
  → BehaviorRecorderBootReceiver
  → BehaviorRecorderService（1 秒检查状态变化，10 秒健康采样）
  → BehaviorAccessibilityService（点击、长按、滚动、窗口变化、导航键）
  → BehaviorRecordStore（设备加密区，追加写入）
  → 导出 ZIP（manifest.json + events.jsonl + timeline.md）
```

每个事件包含墙上时钟、开机后单调时钟和单调序号。双屏前台任务通过平台签名授予的 `REAL_GET_TASKS` 获取；Activity 前后台变化同时由 `UsageStatsManager` 补充。两类来源按时间关联，任何一类缺失都保留为缺失，不猜测补齐。

## 记录内容

- 主屏、副屏当前包名、Activity、Display ID 和应用版本。
- 点击、长按、滚动、选中、窗口变化；有可访问性节点时记录 view ID、控件类型和屏幕边界。
- 返回、主页、最近任务、音量等导航键，不记录数字或字符键。
- 屏幕开关、解锁、电源连接、系统关机。
- 每 10 秒一次 CPU、可用内存、低内存、温度、电池和当前双屏任务快照。
- Android DropBox 新增的崩溃、ANR 等异常标签，`ApplicationExitInfo` 提供的退出原因、内存现场及最多 16 KB 的 ANR 诊断栈，以及用户点击“标记问题”的时间点。Android 11 起系统会把近期进程退出原因保存在环形记录中；读取其他应用需要平台签名授予的 `DUMP` 权限。

## 明确排除

- 输入文字、密码、剪贴板。
- 截图、连续录屏、摄像头和麦克风。
- 网络请求正文、账号令牌和用户文件内容。ANR 诊断栈属于例外，但被限制为 16 KB 且只保存在本机诊断包中。
- 自动上传。所有原始记录默认只存在本机设备加密目录。

## 容量和故障保护

- 原始事件仅在状态变化时写入，系统状态每 10 秒采样一次。
- 单会话达到 8 MB 时建立新会话，不覆盖旧的成功证据。
- 最多保留 10 个会话，约 80 MB；建立新会话时从最旧记录开始删除。
- 每条记录独立追加并关闭文件。整机突然断电或内核卡死时，最后一条可能缺失，但之前已落盘的时间线仍可读取。
- 导出时只为最近 1200 条事件生成 `timeline.md`，且健康采样在摘要中降到每 30 秒一条；完整数据仍保留在 `events.jsonl`。

## 重现等级

1. **时间线复盘**：直接读取 `timeline.md`，查看用户操作、双屏变化、资源异常和问题标记。
2. **证据分析**：大模型读取 `manifest.json` 与 `events.jsonl`，按 `wallTimeMs`、`elapsedMs`、`displayId`、包名和 Activity 关联。
3. **引导式复现**：由测试人员根据 view ID 或坐标边界逐步操作，并核对应用版本和初始状态。
4. **受控自动重放**：后续版本可根据语义节点或坐标生成实验室脚本，但必须人工确认，且默认禁止执行删除、卸载、支付、账号或权限相关动作。

当前版本完成 1–3 的数据基础，不宣称仅凭记录就能确定性恢复网络响应、服务器数据、随机数或未保存的应用内部状态。

## 真机验收

1. 安装平台签名 APK，打开“行为记录”，确认总开关由关闭变为运行中。
2. 核对行为记录通知常驻，页面显示“控件事件已接入”。
3. 分别在主屏和副屏打开两个应用，执行点击、滚动、返回和应用切换。
4. 点击“标记问题”，等待 15 秒后导出最新记录包。
5. 检查 ZIP 中三个文件，验证 `manifest.json.eventsSha256` 与 `events.jsonl` 的 SHA-256 一致。
6. 重启设备，确认无需打开 KEMI Pads 即恢复通知和新会话记录。
7. 停止开关，确认通知消失且记录文件不再增长。

后台验收可由持有 `android.permission.DUMP` 的 ADB shell 调用，不需要模拟前台触控：

```bash
adb shell am broadcast -a com.kemi.mypad.BEHAVIOR_RECORDER_CONTROL \
  --ez enabled true -n com.kemi.mypad/.DiagnosticsProbeReceiver
adb shell am start-foreground-service \
  -n com.kemi.mypad/.BehaviorRecorderService --es reason adb_authorized
adb shell am broadcast -a com.kemi.mypad.BEHAVIOR_RECORDER_CONTROL \
  --ez mark true -n com.kemi.mypad/.DiagnosticsProbeReceiver
adb shell am broadcast -a com.kemi.mypad.DIAGNOSTICS_PROBE \
  -n com.kemi.mypad/.DiagnosticsProbeReceiver
adb shell am broadcast -a com.kemi.mypad.BEHAVIOR_RECORDER_CONTROL \
  --ez dump true -n com.kemi.mypad/.DiagnosticsProbeReceiver
adb shell am broadcast -a com.kemi.mypad.BEHAVIOR_RECORDER_CONTROL \
  --ez export true -n com.kemi.mypad/.DiagnosticsProbeReceiver
```

2026-09-05 已在 `192.168.3.75:5555` 完成后台服务、辅助行为事件、双屏任务与健康采样的动态验证（D1）。完整记录包已在设备上生成；将含真实行为的 ZIP 拉取到开发机并读回仍需单独的数据导出授权。详见 `behavior-recorder-validation-20260905.md`。
