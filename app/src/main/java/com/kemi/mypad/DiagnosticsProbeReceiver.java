package com.kemi.mypad;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Process;
import android.util.Log;

import java.util.List;
import java.util.Map;

/** Shell-only background probe used to verify system privileges and display mapping without opening UI. */
public final class DiagnosticsProbeReceiver extends BroadcastReceiver {
    private static final String TAG = "KEMI_DIAGNOSTICS";

    @Override public void onReceive(Context context, Intent intent) {
        PendingResult pending = goAsync();
        new Thread(() -> {
            try {
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
            } finally {
                pending.finish();
            }
        }, "kemi-diagnostics-probe").start();
    }
}
