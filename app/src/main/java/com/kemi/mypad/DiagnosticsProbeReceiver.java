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
            } finally {
                pending.finish();
            }
        }, "kemi-diagnostics-probe").start();
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
}
