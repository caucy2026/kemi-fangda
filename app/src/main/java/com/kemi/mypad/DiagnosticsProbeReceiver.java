package com.kemi.mypad;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Shell-only background probe used to verify system privileges and display mapping without opening UI. */
public final class DiagnosticsProbeReceiver extends BroadcastReceiver {
    private static final String TAG = "KEMI_DIAGNOSTICS";
    public static final String ACTION_BEHAVIOR_CONTROL = "com.kemi.mypad.BEHAVIOR_RECORDER_CONTROL";

    @Override public void onReceive(Context context, Intent intent) {
        PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                if (intent != null && ACTION_BEHAVIOR_CONTROL.equals(intent.getAction())) {
                    controlBehaviorRecorder(context, intent);
                    return;
                }
                boolean realTasks = context.checkSelfPermission("android.permission.REAL_GET_TASKS")
                        == PackageManager.PERMISSION_GRANTED;
                ActivityManager manager = context.getSystemService(ActivityManager.class);
                List<ActivityManager.RunningAppProcessInfo> processes = manager == null ? null : manager.getRunningAppProcesses();
                Map<Integer, SystemPrivilege.ForegroundTask> tasks = SystemPrivilege.foregroundTasksByDisplay(context);
                Log.i(TAG, "PROBE uid=" + Process.myUid() + " realGetTasks=" + realTasks
                        + " visibleProcesses=" + (processes == null ? 0 : processes.size()) + " displays=" + tasks.size());
                for (SystemPrivilege.ForegroundTask task : tasks.values()) {
                    String label = task.packageName;
                    try {
                        label = context.getPackageManager().getApplicationLabel(
                                context.getPackageManager().getApplicationInfo(task.packageName, 0)).toString();
                    } catch (Exception ignored) { }
                    if ("com.huanglong.portui".equals(task.packageName)) label = "Source";
                    SystemPrivilege.ProcessDetails process = SystemPrivilege.inspectProcess(context, task.packageName);
                    Log.i(TAG, "DISPLAY id=" + task.displayId + " package=" + task.packageName
                            + " label=" + label + " activity=" + task.activityName
                            + " pid=" + process.pid + " pssKb=" + process.totalPssKb
                            + " importance=" + process.importance);
                }
                if (tasks.isEmpty()) Log.e(TAG, "FAIL no authoritative display tasks; cleaning must remain disabled");
                else Log.i(TAG, "PASS authoritative display task mapping available");
                Log.i(TAG, "BEHAVIOR enabled=" + BehaviorRecordStore.isEnabled(context)
                        + " running=" + BehaviorRecorderService.isRunning()
                        + " accessibility=" + BehaviorAccessibilityService.isConnected()
                        + " session=" + BehaviorRecordStore.activeSessionName(context)
                        + " bytes=" + BehaviorRecordStore.activeBytes(context));
                if (intent != null && intent.getBooleanExtra("process_cpu", false)) probeProcessCpu(context);
                if (intent != null && intent.getBooleanExtra("performance_cpu", false)) probePerformanceCpu(context);
            } finally {
                pending.finish();
            }
        }, "kemi-diagnostics-probe").start();
    }

    /** ADB-only parity probe using the exact sampler and interval used by the D2 overlay. */
    private void probePerformanceCpu(Context context) {
        SystemPerformanceSampler sampler = new SystemPerformanceSampler(context);
        SystemPerformanceSampler.Snapshot first = sampler.sample(true);
        SystemClock.sleep(Math.max(0L, SystemPerformanceSampler.SAMPLE_INTERVAL_MS - first.sampleCostMs));
        SystemPerformanceSampler.Snapshot second = sampler.sample(true);
        StringBuilder cores = new StringBuilder();
        for (SystemPerformanceSampler.Core core : second.cores) {
            if (cores.length() > 0) cores.append(',');
            cores.append(core.index).append('=')
                    .append(String.format(java.util.Locale.US, "%.2f", core.percent));
        }
        double rowTotal = 0d;
        for (SystemPerformanceSampler.Row row : second.rows) {
            if (!Double.isNaN(row.cpuPercent) && row.cpuPercent > 0d) rowTotal += row.cpuPercent;
        }
        Log.i(TAG, "PERFORMANCE_CPU totalTopScale="
                + String.format(java.util.Locale.US, "%.2f", second.totalCpuPercent)
                + "% rowTotal=" + String.format(java.util.Locale.US, "%.2f", rowTotal)
                + "% difference=" + String.format(java.util.Locale.US, "%.2f",
                second.totalCpuPercent - rowTotal)
                + "% intervalMs=" + second.intervalMs + " sampleCostMs=" + second.sampleCostMs
                + " cores=" + cores + " rows=" + second.rows.size());
        int limit = Math.min(12, second.rows.size());
        for (int i = 0; i < limit; i++) {
            SystemPerformanceSampler.Row row = second.rows.get(i);
            Log.i(TAG, "PERFORMANCE_ROW rank=" + (i + 1) + " kind="
                    + (row.uidAggregate ? "UID" : "PID") + " id=" + row.id + " name=" + row.name
                    + " cpu=" + String.format(java.util.Locale.US, "%.2f", row.cpuPercent)
                    + "% memKb=" + row.memoryKb);
        }
        sampler.reset();
    }

    /** Shell-triggered, background-only acceptance probe for the same exact-PID data used by the UI. */
    private void probeProcessCpu(Context context) {
        ActivityManager manager = context.getSystemService(ActivityManager.class);
        List<ActivityManager.RunningAppProcessInfo> running = manager == null ? null : manager.getRunningAppProcesses();
        if (running == null || running.isEmpty()) {
            Log.e(TAG, "PROCESS_CPU FAIL no visible processes");
            return;
        }
        Map<Integer, SystemPrivilege.ProcessDetails> first = new HashMap<>();
        long totalFirst = SystemPrivilege.aggregateCpuTicks();
        for (ActivityManager.RunningAppProcessInfo process : running) {
            first.put(process.pid, SystemPrivilege.inspectProcess(context, process.pid));
        }
        SystemClock.sleep(3000);
        long readStarted = SystemClock.elapsedRealtime();
        long totalSecond = SystemPrivilege.aggregateCpuTicks();
        SystemPrivilege.CpuInfoSnapshot cpuInfo = SystemPrivilege.processCpuInfo();
        List<ProcessCpuProbeRow> rows = new ArrayList<>();
        long slowestMs = 0;
        for (ActivityManager.RunningAppProcessInfo process : running) {
            long oneStarted = SystemClock.elapsedRealtime();
            SystemPrivilege.ProcessDetails second = SystemPrivilege.inspectProcess(context, process.pid);
            long oneMillis = SystemClock.elapsedRealtime() - oneStarted;
            slowestMs = Math.max(slowestMs, oneMillis);
            SystemPrivilege.ProcessDetails before = first.get(process.pid);
            double cpu = -1;
            if (before != null && before.startTimeTicks >= 0 && second.startTimeTicks == before.startTimeTicks) {
                cpu = ProcessCpuUsage.percent(second.cpuTicks - before.cpuTicks, totalSecond - totalFirst);
            }
            if (cpu < 0) {
                Double fallback = cpuInfo.machinePercentByPid.get(process.pid);
                if (fallback == null && cpuInfo.error.isEmpty() && !cpuInfo.machinePercentByPid.isEmpty()) fallback = 0d;
                if (fallback != null) cpu = fallback;
            }
            rows.add(new ProcessCpuProbeRow(second, cpu, oneMillis));
        }
        rows.sort((left, right) -> Double.compare(right.cpu, left.cpu));
        int limit = Math.min(20, rows.size());
        for (int i = 0; i < limit; i++) {
            ProcessCpuProbeRow row = rows.get(i);
            Log.i(TAG, "PROCESS_CPU rank=" + (i + 1) + " name=" + row.details.processName
                    + " pid=" + row.details.pid + " cpu=" + String.format(java.util.Locale.US, "%.2f", row.cpu)
                    + "% pssKb=" + row.details.totalPssKb + " threads=" + row.details.threads
                    + " state=" + row.details.state + " detailReadMs=" + row.readMillis);
        }
        Log.i(TAG, "PROCESS_CPU PASS sampled=" + rows.size() + " intervalMs=3000 totalDetailReadMs="
                + (SystemClock.elapsedRealtime() - readStarted) + " slowestDetailReadMs=" + slowestMs
                + " cpuInfoRows=" + cpuInfo.machinePercentByPid.size() + " cpuInfoWindowMs=" + cpuInfo.windowMillis
                + " cpuInfoReadMs=" + cpuInfo.readMillis + " cpuInfoError=" + cpuInfo.error);
    }

    private void controlBehaviorRecorder(Context context, Intent intent) {
        if (intent.hasExtra("enabled")) {
            boolean enabled = intent.getBooleanExtra("enabled", false);
            if (enabled) {
                BehaviorRecordStore.setEnabled(context, true);
                BehaviorRecordStore.beginSession(context, "adb_authorized");
                boolean accessibility = BehaviorRecorderService.setAccessibilityEnabled(context, true);
                boolean started = false;
                try {
                    BehaviorRecorderService.start(context, "adb_authorized");
                    started = true;
                } catch (Exception error) {
                    Log.w(TAG, "BEHAVIOR_CONTROL service requires foreground app, boot receiver, or adb start-foreground-service", error);
                }
                Log.i(TAG, "BEHAVIOR_CONTROL enabled=true accessibilitySetting=" + accessibility + " startRequested=" + started);
            } else {
                BehaviorRecordStore.append(context, "recording_disabled", BehaviorRecordStore.json("source", "adb_authorized"));
                BehaviorRecordStore.setEnabled(context, false);
                BehaviorRecorderService.setAccessibilityEnabled(context, false);
                BehaviorRecorderService.stop(context);
                Log.i(TAG, "BEHAVIOR_CONTROL enabled=false");
            }
        }
        if (intent.getBooleanExtra("mark", false)) {
            BehaviorRecordStore.append(context, "incident_marker", BehaviorRecordStore.json("source", "adb_authorized"));
            Log.i(TAG, "BEHAVIOR_CONTROL incident marked");
        }
        if (intent.getBooleanExtra("dump", false)) {
            for (org.json.JSONObject event : BehaviorRecordStore.recentEvents(context, 20)) {
                Log.i(TAG, "BEHAVIOR_EVENT " + event.toString());
            }
        }
        if (intent.getBooleanExtra("export", false)) {
            try {
                java.io.File archive = BehaviorRecordStore.exportLatest(context);
                Log.i(TAG, "BEHAVIOR_EXPORT path=" + archive.getAbsolutePath() + " bytes=" + archive.length());
            } catch (Exception error) {
                Log.e(TAG, "BEHAVIOR_EXPORT failed", error);
            }
        }
        Log.i(TAG, "BEHAVIOR_STATUS enabled=" + BehaviorRecordStore.isEnabled(context)
                + " running=" + BehaviorRecorderService.isRunning()
                + " accessibility=" + BehaviorAccessibilityService.isConnected()
                + " session=" + BehaviorRecordStore.activeSessionName(context)
                + " bytes=" + BehaviorRecordStore.activeBytes(context));
    }

    private static final class ProcessCpuProbeRow {
        final SystemPrivilege.ProcessDetails details;
        final double cpu;
        final long readMillis;

        ProcessCpuProbeRow(SystemPrivilege.ProcessDetails details, double cpu, long readMillis) {
            this.details = details;
            this.cpu = cpu;
            this.readMillis = readMillis;
        }
    }
}
