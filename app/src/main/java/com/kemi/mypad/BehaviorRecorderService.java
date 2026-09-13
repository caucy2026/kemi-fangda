package com.kemi.mypad;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Low-frequency foreground service that correlates app/display changes with system health. */
public final class BehaviorRecorderService extends Service {
    static final String ACTION_MARK = "com.kemi.mypad.behavior.MARK";
    private static final String CHANNEL = "behavior_recorder";
    private static final int NOTIFICATION_ID = 1910;
    private static volatile boolean running;

    private HandlerThread workerThread;
    private Handler worker;
    private final Map<Integer, String> lastDisplayTasks = new LinkedHashMap<>();
    private final Map<String, Long> lastExitTimes = new HashMap<>();
    private final Set<String> observedPackages = new HashSet<>();
    private long lastUsageWallTime;
    private long previousCpuTotal = -1;
    private long previousCpuIdle = -1;
    private long recordingStartedWallTime;
    private int tick;

    private final Runnable sampler = new Runnable() {
        @Override public void run() {
            if (!running || !BehaviorRecordStore.isEnabled(BehaviorRecorderService.this)) return;
            sampleUsageEvents();
            sampleDisplayTasks();
            if (tick++ % 10 == 0) sampleHealth();
            if (tick % 60 == 0) sampleProcessExits();
            worker.postDelayed(this, 1000);
        }
    };

    private final BroadcastReceiver systemEvents = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            JSONObject detail = BehaviorRecordStore.json("action", intent.getAction());
            try {
                if (android.os.DropBoxManager.ACTION_DROPBOX_ENTRY_ADDED.equals(intent.getAction())) {
                    detail.put("tag", intent.getStringExtra(android.os.DropBoxManager.EXTRA_TAG));
                    detail.put("entryTimeMs", intent.getLongExtra(android.os.DropBoxManager.EXTRA_TIME, 0));
                    BehaviorRecordStore.append(context, "system_failure", detail);
                } else {
                    BehaviorRecordStore.append(context, "system_state", detail);
                }
            } catch (Exception ignored) { }
        }
    };

    static boolean isRunning() { return running; }

    static void start(Context context, String reason) {
        Intent intent = new Intent(context, BehaviorRecorderService.class).putExtra("reason", reason);
        context.startForegroundService(intent);
    }

    static void stop(Context context) {
        context.stopService(new Intent(context, BehaviorRecorderService.class));
    }

    static boolean setAccessibilityEnabled(Context context, boolean enabled) {
        ComponentName component = new ComponentName(context, BehaviorAccessibilityService.class);
        String target = component.flattenToString();
        try {
            String current = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            java.util.LinkedHashSet<String> services = new java.util.LinkedHashSet<>();
            if (current != null) for (String item : current.split(":")) if (!item.trim().isEmpty()) services.add(item.trim());
            if (enabled) services.add(target);
            else services.removeIf(value -> component.equals(ComponentName.unflattenFromString(value)));
            String joined = android.text.TextUtils.join(":", services);
            boolean saved = Settings.Secure.putString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, joined);
            if (services.isEmpty()) Settings.Secure.putInt(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0);
            else Settings.Secure.putInt(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            return saved;
        } catch (Exception ignored) {
            return false;
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        running = true;
        recordingStartedWallTime = System.currentTimeMillis();
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(new NotificationChannel(
                CHANNEL, "行为记录", NotificationManager.IMPORTANCE_LOW));
        workerThread = new HandlerThread("kemi-behavior-recorder");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
        registerSystemEvents();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!BehaviorRecordStore.isEnabled(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION_ID, notification());
        String reason = intent == null ? "sticky_restart" : intent.getStringExtra("reason");
        BehaviorRecordStore.beginSession(this, reason == null ? "start" : reason);
        if (intent != null && ACTION_MARK.equals(intent.getAction())) {
            markIncident("用户手动标记");
        }
        lastUsageWallTime = Math.max(lastUsageWallTime, System.currentTimeMillis() - 2000);
        worker.removeCallbacks(sampler);
        worker.post(sampler);
        return START_STICKY;
    }

    @Override public void onDestroy() {
        running = false;
        if (worker != null) worker.removeCallbacksAndMessages(null);
        try { unregisterReceiver(systemEvents); } catch (Exception ignored) { }
        BehaviorRecordStore.append(this, "service_stop", new JSONObject());
        if (workerThread != null) workerThread.quitSafely();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private Notification notification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent mark = new Intent(this, BehaviorRecorderService.class).setAction(ACTION_MARK);
        PendingIntent markPending = PendingIntent.getService(this, 1, mark,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_recent_history)
                .setContentTitle("KEMI Pads · 行为记录中")
                .setContentText("仅记录操作语义和系统状态，不记录输入内容")
                .setContentIntent(content)
                .addAction(new Notification.Action.Builder(null, "标记问题", markPending).build())
                .setOngoing(true).build();
    }

    private void registerSystemEvents() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        filter.addAction(Intent.ACTION_SHUTDOWN);
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
        filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        filter.addAction(android.os.DropBoxManager.ACTION_DROPBOX_ENTRY_ADDED);
        registerReceiver(systemEvents, filter);
    }

    private void sampleDisplayTasks() {
        Map<Integer, SystemPrivilege.ForegroundTask> tasks = SystemPrivilege.foregroundTasksByDisplay(this);
        for (SystemPrivilege.ForegroundTask task : tasks.values()) {
            String component = task.componentName();
            if (component.equals(lastDisplayTasks.get(task.displayId))) continue;
            lastDisplayTasks.put(task.displayId, component);
            observedPackages.add(task.packageName);
            try {
                JSONObject detail = new JSONObject();
                detail.put("displayId", task.displayId);
                detail.put("package", task.packageName);
                detail.put("activity", task.activityName);
                try {
                    android.content.pm.PackageInfo info = getPackageManager().getPackageInfo(task.packageName, 0);
                    detail.put("appVersion", info.versionName);
                    detail.put("appVersionCode", info.getLongVersionCode());
                } catch (Exception ignored) { }
                BehaviorRecordStore.append(this, "display_foreground", detail);
            } catch (Exception ignored) { }
        }
    }

    private void sampleUsageEvents() {
        long now = System.currentTimeMillis();
        UsageStatsManager manager = getSystemService(UsageStatsManager.class);
        if (manager == null) return;
        try {
            UsageEvents events = manager.queryEvents(lastUsageWallTime, now);
            UsageEvents.Event event = new UsageEvents.Event();
            while (events != null && events.hasNextEvent()) {
                events.getNextEvent(event);
                int type = event.getEventType();
                if (type != UsageEvents.Event.ACTIVITY_RESUMED && type != UsageEvents.Event.ACTIVITY_PAUSED
                        && type != UsageEvents.Event.USER_INTERACTION && type != UsageEvents.Event.DEVICE_STARTUP
                        && type != UsageEvents.Event.DEVICE_SHUTDOWN) continue;
                JSONObject detail = new JSONObject();
                detail.put("usageType", type);
                detail.put("package", event.getPackageName());
                detail.put("class", event.getClassName());
                detail.put("sourceWallTimeMs", event.getTimeStamp());
                BehaviorRecordStore.append(this, "usage_event", detail);
                if (event.getPackageName() != null) observedPackages.add(event.getPackageName());
            }
        } catch (Exception ignored) { }
        lastUsageWallTime = now + 1;
    }

    private void sampleHealth() {
        try {
            JSONObject detail = new JSONObject();
            ActivityManager activity = getSystemService(ActivityManager.class);
            ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
            if (activity != null) activity.getMemoryInfo(memory);
            detail.put("memoryAvailableBytes", memory.availMem);
            detail.put("memoryTotalBytes", memory.totalMem);
            detail.put("lowMemory", memory.lowMemory);
            detail.put("cpuPercent", cpuPercent());
            PowerManager power = getSystemService(PowerManager.class);
            if (power != null) {
                detail.put("interactive", power.isInteractive());
                detail.put("powerSave", power.isPowerSaveMode());
                detail.put("thermalStatus", power.getCurrentThermalStatus());
            }
            Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (battery != null) {
                detail.put("batteryPercent", battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1));
                detail.put("batteryTemperatureTenthsC", battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0));
            }
            JSONArray displays = new JSONArray();
            for (Map.Entry<Integer, String> entry : lastDisplayTasks.entrySet()) {
                JSONObject item = new JSONObject(); item.put("displayId", entry.getKey()); item.put("component", entry.getValue()); displays.put(item);
            }
            detail.put("displays", displays);
            BehaviorRecordStore.append(this, "health_sample", detail);
        } catch (Exception ignored) { }
    }

    private double cpuPercent() {
        try (BufferedReader reader = new BufferedReader(new FileReader(new File("/proc/stat")))) {
            String[] parts = reader.readLine().trim().split("\\s+");
            long total = 0;
            for (int i = 1; i < parts.length; i++) total += Long.parseLong(parts[i]);
            long idle = Long.parseLong(parts[4]) + (parts.length > 5 ? Long.parseLong(parts[5]) : 0);
            double value = 0;
            if (previousCpuTotal > 0 && total > previousCpuTotal) {
                value = 100d * (1d - (idle - previousCpuIdle) / (double) (total - previousCpuTotal));
            }
            previousCpuTotal = total; previousCpuIdle = idle;
            return Math.max(0, Math.min(100, value));
        } catch (Exception ignored) { return -1; }
    }

    private void sampleProcessExits() {
        ActivityManager manager = getSystemService(ActivityManager.class);
        if (manager == null) return;
        for (String packageName : new HashSet<>(observedPackages)) {
            try {
                for (ApplicationExitInfo exit : manager.getHistoricalProcessExitReasons(packageName, 0, 5)) {
                    if (exit.getTimestamp() < recordingStartedWallTime - 5000) continue;
                    long previous = lastExitTimes.getOrDefault(packageName, 0L);
                    if (exit.getTimestamp() <= previous) continue;
                    lastExitTimes.put(packageName, Math.max(previous, exit.getTimestamp()));
                    JSONObject detail = new JSONObject();
                    detail.put("package", packageName);
                    detail.put("process", exit.getProcessName());
                    detail.put("pid", exit.getPid());
                    detail.put("reason", exit.getReason());
                    detail.put("status", exit.getStatus());
                    detail.put("importance", exit.getImportance());
                    detail.put("pssKb", exit.getPss());
                    detail.put("rssKb", exit.getRss());
                    detail.put("exitWallTimeMs", exit.getTimestamp());
                    detail.put("description", exit.getDescription());
                    if (exit.getReason() == ApplicationExitInfo.REASON_ANR) {
                        String trace = boundedTrace(exit.getTraceInputStream(), 16 * 1024);
                        if (!trace.isEmpty()) detail.put("anrTraceExcerpt", trace);
                    }
                    BehaviorRecordStore.append(this, "process_exit", detail);
                }
            } catch (Exception ignored) { }
        }
    }

    private String boundedTrace(InputStream input, int limit) {
        if (input == null) return "";
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[2048];
            int remaining = limit;
            while (remaining > 0) {
                int count = stream.read(buffer, 0, Math.min(buffer.length, remaining));
                if (count < 0) break;
                output.write(buffer, 0, count);
                remaining -= count;
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8).replace("\u0000", "");
        } catch (Exception ignored) { return ""; }
    }

    private void markIncident(String source) {
        try {
            JSONObject detail = new JSONObject();
            detail.put("source", source);
            detail.put("note", "Preserve the preceding timeline for reproduction");
            BehaviorRecordStore.append(this, "incident_marker", detail);
        } catch (Exception ignored) { }
    }
}
