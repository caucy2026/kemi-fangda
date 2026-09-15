package com.kemi.mypad;

import android.app.ActivityManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.PackageInstaller;
import android.content.pm.ApplicationInfo;
import android.app.usage.StorageStats;
import android.app.usage.StorageStatsManager;
import android.os.Debug;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.view.Display;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.HashMap;
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
        String oomScoreAdj = "";
        String voluntaryContextSwitches = "";
        String involuntaryContextSwitches = "";
        String commandLine = "";
        long cpuTicks = -1;
        long startTimeTicks = -1;
        long ioReadBytes = -1;
        long ioWriteBytes = -1;
        int priority;
        int nice;
        int processor = -1;
        boolean visibleToActivityManager;
        boolean procReadable;
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

    static final class CpuInfoSnapshot {
        final Map<Integer, Double> machinePercentByPid = new HashMap<>();
        final Map<Integer, String> processNameByPid = new HashMap<>();
        long windowMillis = -1;
        long readMillis;
        String error = "";
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
                    result.visibleToActivityManager = true;
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

    /** Reads one exact PID so a selected row never changes to a sibling process. */
    static ProcessDetails inspectProcess(Context context, int pid) {
        ProcessDetails result = new ProcessDetails();
        if (pid <= 0) return result;
        result.pid = pid;
        ActivityManager manager = context.getSystemService(ActivityManager.class);
        if (manager == null) return result;
        try {
            java.util.List<ActivityManager.RunningAppProcessInfo> running = manager.getRunningAppProcesses();
            if (running != null) for (ActivityManager.RunningAppProcessInfo process : running) {
                if (process.pid != pid) continue;
                result.uid = process.uid;
                result.processName = process.processName;
                result.importance = process.importance;
                result.importanceReasonCode = process.importanceReasonCode;
                result.lru = process.lru;
                result.visibleToActivityManager = true;
                break;
            }
            Debug.MemoryInfo[] memory = manager.getProcessMemoryInfo(new int[]{pid});
            if (memory.length > 0) fillMemoryDetails(result, memory[0]);
            readProcDetails(result);
        } catch (Exception ignored) { }
        return result;
    }

    private static void fillMemoryDetails(ProcessDetails result, Debug.MemoryInfo info) {
        result.totalPssKb = info.getTotalPss();
        result.totalPrivateDirtyKb = info.getTotalPrivateDirty();
        result.totalSharedDirtyKb = info.getTotalSharedDirty();
        result.javaHeapKb = info.getMemoryStat("summary.java-heap");
        result.nativeHeapKb = info.getMemoryStat("summary.native-heap");
        result.codeKb = info.getMemoryStat("summary.code");
        result.stackKb = info.getMemoryStat("summary.stack");
        result.graphicsKb = info.getMemoryStat("summary.graphics");
    }

    private static void readProcDetails(ProcessDetails result) {
        File directory = new File("/proc/" + result.pid);
        try (BufferedReader reader = new BufferedReader(new FileReader(new File(directory, "status")))) {
            result.procReadable = true;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("Name:") && result.processName.isEmpty()) result.processName = afterColon(line);
                else if (line.startsWith("Uid:") && result.uid <= 0) result.uid = firstNumberAfterColon(line);
                else if (line.startsWith("State:")) result.state = afterColon(line);
                else if (line.startsWith("Threads:")) result.threads = afterColon(line);
                else if (line.startsWith("PPid:")) result.ppid = afterColon(line);
                else if (line.startsWith("VmSize:")) result.vmSize = afterColon(line);
                else if (line.startsWith("VmRSS:")) result.vmRss = afterColon(line);
                else if (line.startsWith("VmSwap:")) result.vmSwap = afterColon(line);
                else if (line.startsWith("voluntary_ctxt_switches:")) result.voluntaryContextSwitches = afterColon(line);
                else if (line.startsWith("nonvoluntary_ctxt_switches:")) result.involuntaryContextSwitches = afterColon(line);
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
                if (fields.length > 19) {
                    result.cpuTicks = Long.parseLong(fields[11]) + Long.parseLong(fields[12]);
                    result.priority = Integer.parseInt(fields[15]);
                    result.nice = Integer.parseInt(fields[16]);
                    result.startTimeTicks = Long.parseLong(fields[19]);
                    if (fields.length > 36) result.processor = Integer.parseInt(fields[36]);
                }
            }
        } catch (Exception ignored) { }
        result.oomScoreAdj = readFirstLine(new File(directory, "oom_score_adj"));
        result.commandLine = readCommandLine(new File(directory, "cmdline"));
        try (BufferedReader reader = new BufferedReader(new FileReader(new File(directory, "io")))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("read_bytes:")) result.ioReadBytes = parseLongAfterColon(line);
                else if (line.startsWith("write_bytes:")) result.ioWriteBytes = parseLongAfterColon(line);
            }
        } catch (Exception ignored) { }
    }

    static long aggregateCpuTicks() {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/stat"))) {
            String line = reader.readLine();
            if (line == null || !line.startsWith("cpu ")) return -1;
            String[] fields = line.trim().split("\\s+");
            long total = 0;
            for (int i = 1; i < fields.length; i++) total += Long.parseLong(fields[i]);
            return total;
        } catch (Exception ignored) { return -1; }
    }

    /** Uses Android's privileged cpuinfo service when per-PID /proc is isolated by SELinux. */
    static CpuInfoSnapshot processCpuInfo() {
        CpuInfoSnapshot snapshot = new CpuInfoSnapshot();
        long started = android.os.SystemClock.elapsedRealtime();
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        try {
            Process process = new ProcessBuilder("/system/bin/dumpsys", "cpuinfo")
                    .redirectErrorStream(true).start();
            java.util.ArrayList<String> lines = new java.util.ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) lines.add(line);
            }
            CpuInfoParser.Result parsed = CpuInfoParser.parse(lines, cores);
            snapshot.windowMillis = parsed.windowMillis;
            snapshot.machinePercentByPid.putAll(parsed.machinePercentByPid);
            snapshot.processNameByPid.putAll(parsed.processNameByPid);
            if (!process.waitFor(2, TimeUnit.SECONDS) || process.exitValue() != 0) {
                process.destroy();
                snapshot.error = "cpuinfo 服务未响应";
            } else if (snapshot.machinePercentByPid.isEmpty()) {
                snapshot.error = "cpuinfo 未返回进程数据";
            }
        } catch (Exception error) {
            snapshot.error = error.getClass().getSimpleName();
        }
        snapshot.readMillis = android.os.SystemClock.elapsedRealtime() - started;
        return snapshot;
    }

    private static String readFirstLine(File file) {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String value = reader.readLine();
            return value == null ? "" : value.trim();
        } catch (Exception ignored) { return ""; }
    }

    private static String readCommandLine(File file) {
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[1024];
            int count = input.read(buffer);
            return count <= 0 ? "" : new String(buffer, 0, count, StandardCharsets.UTF_8)
                    .replace('\u0000', ' ').trim();
        } catch (Exception ignored) { return ""; }
    }

    private static long parseLongAfterColon(String value) {
        try { return Long.parseLong(afterColon(value)); }
        catch (Exception ignored) { return -1; }
    }

    private static int firstNumberAfterColon(String value) {
        try { return Integer.parseInt(afterColon(value).split("\\s+")[0]); }
        catch (Exception ignored) { return 0; }
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

    static long applicationCacheBytes(Context context, ApplicationInfo info) {
        if (info == null || !safePackage(info.packageName)) return 0;
        try {
            StorageStatsManager manager = context.getSystemService(StorageStatsManager.class);
            if (manager == null) return 0;
            StorageStats stats = manager.queryStatsForPackage(
                    info.storageUuid == null ? StorageManager.UUID_DEFAULT : info.storageUuid,
                    info.packageName, UserHandle.getUserHandleForUid(info.uid));
            return Math.max(0, stats.getCacheBytes());
        } catch (Exception ignored) {
            return 0;
        }
    }

    /** Requests Android's package manager to trim every cache it is allowed to reclaim. */
    static boolean trimAllApplicationCaches(Context context) {
        try {
            Process process = new ProcessBuilder("/system/bin/cmd", "package", "trim-caches", "9223372036854775807")
                    .redirectErrorStream(true).start();
            if (process.waitFor(20, TimeUnit.SECONDS) && process.exitValue() == 0) return true;
            process.destroy();
        } catch (Exception ignored) { }
        PackageManager manager = context.getPackageManager();
        for (java.lang.reflect.Method method : manager.getClass().getMethods()) {
            if (!"freeStorageAndNotify".equals(method.getName())) continue;
            try {
                Class<?>[] types = method.getParameterTypes();
                Object[] args = new Object[types.length];
                for (int i = 0; i < types.length; i++) {
                    if (types[i] == long.class || types[i] == Long.class) args[i] = Long.MAX_VALUE;
                    else if (types[i] == int.class || types[i] == Integer.class) args[i] = 0;
                    else args[i] = null;
                }
                method.setAccessible(true);
                method.invoke(manager, args);
                return true;
            } catch (Exception ignored) { }
        }
        return false;
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
