# KEMI Pads CPU 监控架构

## 目标

活动监控页面以统一口径显示整机、8 个核心和各进程 CPU 占用。采样只在页面可见时运行；离开页面、Activity 进入后台或点击“退出 KEMI Pads”后立即停止，不形成常驻耗电服务。

## 当前 APK 层

`ProcessCpuMonitorService` 是不允许外部调用的绑定式服务：

- 页面进入时通过 `BIND_AUTO_CREATE` 建立唯一绑定；
- 服务在线程中读取两次 `/proc/stat` 和可访问的 `/proc/<pid>/stat`，固定间隔 3 秒；
- PID 基线同时保存 `starttime`，PID 被复用时丢弃旧样本；
- 进程百分比采用整机口径，列表合计可以与 CPU 总占用比较；
- Android 12 隔离的其他 UID 使用 `dumpsys cpuinfo` 回退，并保留系统返回的真实统计窗口；
- UI 明确区分“3 秒实测”和“系统近期平均”，第一轮没有增量时显示“采样中”，不显示伪造的 0%；
- 最后一个绑定解除后调用 `stopSelf()`，清空 Handler、进程基线和快照。

行为记录服务和文件分发服务有各自的用户开关及后台语义，不由 CPU 页面误关。

## 固件精确采样层

要让所有 UID 都成为严格的同一 3 秒窗口，需要在 KEMI Android 固件中提供只读 Binder 服务 `kemi_process_monitor`，由 `system_server` 或受控 vendor 特权进程托管。

建议接口：

```text
openSession(clientToken, intervalMs = 3000)
getLatestSnapshot(sessionId) -> CpuSnapshot
closeSession(sessionId)
```

`CpuSnapshot` 至少包含：

```text
capturedAtElapsedRealtime
intervalMillis
aggregateTotalDelta / aggregateIdleAndIoWaitDelta
perCoreTotalDelta[] / perCoreIdleAndIoWaitDelta[]
processes[] {
  pid, startTimeTicks, uid, processName,
  userTicksDelta, systemTicksDelta,
  minorFaultsDelta, majorFaultsDelta,
  threadCount, state
}
```

系统服务只返回一次批量快照，不允许 APK 为每个 PID 发起一次 Binder 请求。服务以客户端 Binder token 做引用计数并注册死亡通知；最后一个客户端退出或异常死亡时取消采样任务并清空基线。

访问权限使用仅授予 KEMI 平台签名应用的 signature 权限，例如：

```xml
<permission
    android:name="com.kemi.permission.READ_PROCESS_MONITOR"
    android:protectionLevel="signature" />
```

## 统计口径

```text
进程整机占用 = Δ(utime + stime) / Δ(全部核心总 ticks) × 100%
整机占用     = (Δtotal - Δidle - Δiowait) / Δtotal × 100%
单核心占用   = (ΔcoreTotal - ΔcoreIdle - ΔcoreIowait) / ΔcoreTotal × 100%
```

进程整机口径范围为 `0–100%`；如后续增加类似 Linux `top` 的单核口径，应作为另一列明确标注，不能混用。

## 验收

1. 首次进入显示“采样中”，3 秒后出现整机、8 核和进程数据。
2. 同一快照内所有直接采样进程使用完全相同的起止时间。
3. 进程合计与整机忙碌占用偏差可解释为内核线程、采样边界或不可见进程，不得混入不同时间窗口。
4. 快速切换页面 20 次不出现重复采样线程或 Binder 泄漏。
5. 离开页面后 1 秒内 `dumpsys activity services com.kemi.mypad` 不存在 CPU 监控绑定。
6. 点击退出后 KEMI Pads 任务和 CPU 监控服务均不存在。
7. 行为记录或文件分发已明确开启时，不因离开 CPU 页面被关闭。
