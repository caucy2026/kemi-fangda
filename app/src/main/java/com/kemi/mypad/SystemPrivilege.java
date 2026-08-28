package com.kemi.mypad;

import android.app.ActivityManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.PackageInstaller;
import android.os.Debug;
import android.view.Display;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Platform-certificate system APIs used by the KEMI PAD firmware. */
final class SystemPrivilege {
    static final class ForegroundTask {
        final int displayId;
        final String packageName;
        final String activityName;

        ForegroundTask(int displayId, String packageName, String activityName) {
            this.displayId = displayId;
            this.packageName = packageName;
            this.activityName = activityName.startsWith(".") ? packageName + activityName : activityName;
        }

        String componentName() { return packageName + "/" + activityName; }
    }

    static final class ProcessDetails {
        int pid;
        int uid;
        int importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE;
        int importanceReasonCode;
        int lru;
        String processName = "";
        String state = "";
        String threads = "";
        String ppid = "";
        String vmSize = "";
        String vmRss = "";
        String vmSwap = "";
        String fdCount = "";
        long cpuTicks = -1;
        int totalPssKb;
        int totalPrivateDirtyKb;
        int totalSharedDirtyKb;
        String javaHeapKb = "";
        String nativeHeapKb = "";
        String codeKb = "";
        String stackKb = "";
        String graphicsKb = "";

        boolean running() { return pid > 0; }
    }

    private SystemPrivilege() { }

    static Map<Integer, String> foregroundPackagesByDisplay(Context context) {
        Map<Integer, String> packages = new LinkedHashMap<>();
        for (Map.Entry<Integer, ForegroundTask> entry : foregroundTasksByDisplay(context).entrySet()) {
            packages.put(entry.getKey(), entry.getValue().packageName);
        }
        return packages;
    }

    static Map<Integer, ForegroundTask> foregroundTasksByDisplay(Context context) {
        Map<Integer, ForegroundTask> tasks = new LinkedHashMap<>();
        if (context.checkSelfPermission("android.permission.REAL_GET_TASKS") != PackageManager.PERMISSION_GRANTED) return tasks;
        ActivityManager manager = context.getSystemService(ActivityManager.class);
        if (manager == null) return tasks;
        try {
            for (ActivityManager.RunningTaskInfo task : manager.getRunningTasks(100)) {
                if (task.topActivity == null) continue;
                int displayId = Display.DEFAULT_DISPLAY;
                try {
                    java.lang.reflect.Field field = task.getClass().getField("displayId");
                    field.setAccessible(true);
                    displayId = field.getInt(task);
                } catch (Exception ignored) {
                    String text = task.toString();
                    int marker = text.indexOf("displayId=");
                    if (marker >= 0) {
                        int start = marker + 10, end = start;
                        while (end < text.length() && Character.isDigit(text.charAt(end))) end++;
                        if (end > start) displayId = Integer.parseInt(text.substring(start, end));
                    }
                }
                if (!tasks.containsKey(displayId)) tasks.put(displayId, new ForegroundTask(displayId,
                        task.topActivity.getPackageName(), task.topActivity.getClassName()));
            }
        } catch (Exception ignored) { }
        return tasks;
    }

    /** Reads another process through APIs granted by the platform certificate; no su binary is involved. */
    static ProcessDetails inspectProcess(Context context, String packageName) {
        ProcessDetails result = new ProcessDetails();
        if (!safePackage(packageName)) return result;
        try { result.uid = context.getPackageManager().getApplicationInfo(packageName, 0).uid; }
        catch (Exception ignored) { }
        ActivityManager manager = context.getSystemService(ActivityManager.class);
        if (manager == null) return result;
        try {
            java.util.List<ActivityManager.RunningAppProcessInfo> running = manager.getRunningAppProcesses();
            if (running != null) for (ActivityManager.RunningAppProcessInfo process : running) {
                boolean ownsPackage = false;
                if (process.pkgList != null) for (String value : process.pkgList) {
                    if (packageName.equals(value)) { ownsPackage = true; break; }
                }
                if (!ownsPackage && !packageName.equals(process.processName)
                        && !process.processName.startsWith(packageName + ":")) continue;
                if (result.pid == 0 || process.importance < result.importance) {
                    result.pid = process.pid;
                    result.uid = process.uid;
                    result.processName = process.processName;
                    result.importance = process.importance;
                    result.importanceReasonCode = process.importanceReasonCode;
                    result.lru = process.lru;
                }
            }
            if (result.pid > 0) {
                Debug.MemoryInfo[] memory = manager.getProcessMemoryInfo(new int[]{result.pid});
                if (memory.length > 0) {
                    Debug.MemoryInfo info = memory[0];
                    result.totalPssKb = info.getTotalPss();
                    result.totalPrivateDirtyKb = info.getTotalPrivateDirty();
                    result.totalSharedDirtyKb = info.getTotalSharedDirty();
                    result.javaHeapKb = info.getMemoryStat("summary.java-heap");
                    result.nativeHeapKb = info.getMemoryStat("summary.native-heap");
                    result.codeKb = info.getMemoryStat("summary.code");
                    result.stackKb = info.getMemoryStat("summary.stack");
                    result.graphicsKb = info.getMemoryStat("summary.graphics");
                }
                readProcDetails(result);
            }
        } catch (Exception ignored) { }
        return result;
    }

    private static void readProcDetails(ProcessDetails result) {
        File directory = new File("/proc/" + result.pid);
        try (BufferedReader reader = new BufferedReader(new FileReader(new File(directory, "status")))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("State:")) result.state = afterColon(line);
                else if (line.startsWith("Threads:")) result.threads = afterColon(line);
                else if (line.startsWith("PPid:")) result.ppid = afterColon(line);
                else if (line.startsWith("VmSize:")) result.vmSize = afterColon(line);
                else if (line.startsWith("VmRSS:")) result.vmRss = afterColon(line);
                else if (line.startsWith("VmSwap:")) result.vmSwap = afterColon(line);
            }
        } catch (Exception ignored) { }
        try {
            File[] descriptors = new File(directory, "fd").listFiles();
            if (descriptors != null) result.fdCount = String.valueOf(descriptors.length);
        } catch (Exception ignored) { }
        try (BufferedReader reader = new BufferedReader(new FileReader(new File(directory, "stat")))) {
            String line = reader.readLine();
            int close = line == null ? -1 : line.lastIndexOf(')');
            if (close > 0) {
                String[] fields = line.substring(close + 2).split("\\s+");
                if (fields.length > 12) result.cpuTicks = Long.parseLong(fields[11]) + Long.parseLong(fields[12]);
            }
        } catch (Exception ignored) { }
    }

    private static String afterColon(String value) {
        int colon = value.indexOf(':');
        return colon < 0 ? "" : value.substring(colon + 1).trim();
    }

    static boolean clearApplicationCache(Context context, String packageName) {
        if (!safePackage(packageName)) return false;
        try {
            Class<?> observer = Class.forName("android.content.pm.IPackageDataObserver");
            java.lang.reflect.Method method = context.getPackageManager().getClass()
                    .getMethod("deleteApplicationCacheFiles", String.class, observer);
            method.setAccessible(true);
            method.invoke(context.getPackageManager(), packageName, null);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    static boolean uninstallUserPackage(Context context, String packageName) {
        if (!safePackage(packageName)) return false;
        String action = context.getPackageName() + ".UNINSTALL_RESULT." + System.nanoTime();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger status = new AtomicInteger(Integer.MIN_VALUE);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) {
                status.set(intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE));
                latch.countDown();
            }
        };
        try {
            context.registerReceiver(receiver, new IntentFilter(action));
            Intent callback = new Intent(action).setPackage(context.getPackageName());
            PendingIntent pending = PendingIntent.getBroadcast(context, (int) System.nanoTime(), callback,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            context.getPackageManager().getPackageInstaller().uninstall(packageName, pending.getIntentSender());
            return latch.await(12, TimeUnit.SECONDS) && status.get() == PackageInstaller.STATUS_SUCCESS;
        } catch (Exception ignored) {
            return false;
        } finally {
            try { context.unregisterReceiver(receiver); } catch (Exception ignored) { }
        }
    }

    private static boolean safePackage(String value) {
        return value != null && value.matches("[A-Za-z][A-Za-z0-9_.]*");
    }

}
