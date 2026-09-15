package com.kemi.mypad;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Debug;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.net.Uri;
import android.system.Os;
import android.system.OsConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Low-frequency, in-process performance sampler used only while the D2 overlay is enabled. */
final class SystemPerformanceSampler {
    static final long SAMPLE_INTERVAL_MS = 3000L;

    static final class Core {
        final int index;
        final double percent;
        final long maxFrequencyKhz;
        final long currentFrequencyKhz;

        Core(int index, double percent, long maxFrequencyKhz, long currentFrequencyKhz) {
            this.index = index;
            this.percent = percent;
            this.maxFrequencyKhz = maxFrequencyKhz;
            this.currentFrequencyKhz = currentFrequencyKhz;
        }
    }

    static final class Row {
        final String name;
        final int id;
        final boolean uidAggregate;
        final boolean inUse;
        final double cpuPercent;
        final long memoryKb;

        Row(String name, int id, boolean uidAggregate, boolean inUse, double cpuPercent, long memoryKb) {
            this.name = name;
            this.id = id;
            this.uidAggregate = uidAggregate;
            this.inUse = inUse;
            this.cpuPercent = cpuPercent;
            this.memoryKb = memoryKb;
        }
    }

    static final class Snapshot {
        final long capturedAt;
        final long intervalMs;
        final double totalCpuPercent;
        final List<Core> cores;
        final long totalMemory;
        final long availableMemory;
        final long totalSwapKb;
        final long freeSwapKb;
        final List<Row> rows;
        final long sampleCostMs;

        Snapshot(long capturedAt, long intervalMs, double totalCpuPercent, List<Core> cores,
                 long totalMemory, long availableMemory, long totalSwapKb, long freeSwapKb,
                 List<Row> rows, long sampleCostMs) {
            this.capturedAt = capturedAt;
            this.intervalMs = intervalMs;
            this.totalCpuPercent = totalCpuPercent;
            this.cores = Collections.unmodifiableList(cores);
            this.totalMemory = totalMemory;
            this.availableMemory = availableMemory;
            this.totalSwapKb = totalSwapKb;
            this.freeSwapKb = freeSwapKb;
            this.rows = Collections.unmodifiableList(rows);
            this.sampleCostMs = sampleCostMs;
        }
    }

    private static final class CpuRow {
        final int index;
        final long total;
        final long idle;

        CpuRow(int index, long total, long idle) {
            this.index = index;
            this.total = total;
            this.idle = idle;
        }
    }

    private static final class CpuState {
        final CpuRow aggregate;
        final List<CpuRow> cores;

        CpuState(CpuRow aggregate, List<CpuRow> cores) {
            this.aggregate = aggregate;
            this.cores = cores;
        }
    }

    private static final class PidCounter {
        final int pid;
        final int uid;
        final String name;
        final long ticks;
        final long startTicks;
        final long rssKb;

        PidCounter(int pid, int uid, String name, long ticks, long startTicks, long rssKb) {
            this.pid = pid;
            this.uid = uid;
            this.name = name;
            this.ticks = ticks;
            this.startTicks = startTicks;
            this.rssKb = rssKb;
        }
    }

    private final ActivityManager activityManager;
    private final PackageManager packageManager;
    private final Context context;
    private final long clockTicksPerSecond;
    private CpuState previousCpu;
    private long previousAt;
    private Map<Integer, Long> previousUidMicros = new HashMap<>();
    private Map<Integer, PidCounter> previousPids = new HashMap<>();

    SystemPerformanceSampler(Context context) {
        this.context = context.getApplicationContext();
        activityManager = context.getSystemService(ActivityManager.class);
        packageManager = context.getPackageManager();
        long ticks = 100L;
        try { ticks = Os.sysconf(OsConstants._SC_CLK_TCK); } catch (Exception ignored) { }
        clockTicksPerSecond = Math.max(1L, ticks);
    }

    synchronized Snapshot sample(boolean includeDetails) {
        long started = SystemClock.elapsedRealtime();
        CpuState cpu = readCpuState();
        long now = SystemClock.elapsedRealtime();
        long intervalMs = previousAt <= 0 ? 0 : now - previousAt;
        double totalPercent = Double.NaN;
        List<Core> cores = new ArrayList<>();

        if (cpu != null) {
            int online = cpu.cores.size();
            if (previousCpu != null && intervalMs > 0) {
                double average = percent(cpu.aggregate, previousCpu.aggregate);
                if (!Double.isNaN(average)) totalPercent = average * Math.max(1, online);
            }
            for (int i = 0; i < cpu.cores.size(); i++) {
                CpuRow current = cpu.cores.get(i);
                CpuRow before = findCore(previousCpu, current.index);
                cores.add(new Core(current.index, before == null ? Double.NaN : percent(current, before),
                        readFrequency(current.index, "cpuinfo_max_freq", "scaling_max_freq"),
                        readFrequency(current.index, "scaling_cur_freq")));
            }
        }

        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        if (activityManager != null) activityManager.getMemoryInfo(memory);
        long[] swap = readSwap();
        List<Row> rows = includeDetails ? readDetailRows(intervalMs, totalPercent) : new ArrayList<>();

        previousCpu = cpu;
        previousAt = now;
        if (!includeDetails) {
            previousUidMicros.clear();
            previousPids.clear();
        }
        return new Snapshot(now, intervalMs, totalPercent, cores, memory.totalMem, memory.availMem,
                swap[0], swap[1], rows, SystemClock.elapsedRealtime() - started);
    }

    synchronized void reset() {
        previousCpu = null;
        previousAt = 0;
        previousUidMicros.clear();
        previousPids.clear();
    }

    private List<Row> readDetailRows(long intervalMs, double totalCpuPercent) {
        Map<Integer, String> packageByPid = runningPackageByPid();
        Map<Integer, Integer> uidByPid = runningUidByPid();
        Map<Integer, Long> memoryByUid = readAppPssByUid();
        Map<Integer, Long> memoryByPid = readAppPssByPid();
        Set<String> inUsePackages = inUsePackages();
        List<Row> privileged = readPrivilegedRows(packageByPid, uidByPid, memoryByUid,
                memoryByPid, inUsePackages);
        if (privileged != null) {
            privileged.removeIf(row -> !row.inUse && row.cpuPercent < 0.05d);
            addUnattributedCpu(privileged, totalCpuPercent);
            privileged.sort(Comparator.comparingDouble((Row row) -> row.cpuPercent).reversed()
                    .thenComparingLong(row -> -row.memoryKb).thenComparing(row -> row.name));
            return privileged;
        }

        Map<Integer, Long> uidNow = readUidCpuMicros();
        Map<Integer, PidCounter> pidNow = readPidCounters();
        List<Row> rows = new ArrayList<>();
        double assigned = 0d;

        // Ordinary apps are sampled from cumulative UID CPU microseconds. This
        // is a real elapsedRealtime three-second delta and naturally aggregates
        // the app's main process and all of its child processes.
        for (Map.Entry<Integer, Long> entry : uidNow.entrySet()) {
            int uid = entry.getKey();
            if (uid < 10000) continue;
            Long before = previousUidMicros.get(uid);
            double cpu = PerformanceMath.counterPercent(entry.getValue(), before,
                    intervalMs, 1_000_000d);
            boolean inUse = containsUidPackage(uid, inUsePackages);
            rows.add(new Row(labelForUid(uid), uid, true, inUse, cpu,
                    memoryByUid.getOrDefault(uid, 0L)));
            if (!Double.isNaN(cpu)) assigned += cpu;
        }

        // hidepid=invisible can leave a platform-signed app seeing only its own
        // PID.  In that case use ActivityManager's privileged cpuinfo service;
        // this is the same authoritative process list Android exposes through
        // dumpsys cpuinfo and includes shared-UID system apps such as the IME.
        SystemPrivilege.CpuInfoSnapshot systemCpu = pidNow.size() < 10
                ? SystemPrivilege.processCpuInfo() : null;
        if (systemCpu != null && !systemCpu.machinePercentByPid.isEmpty()) {
            int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
            Set<Integer> includedPids = new HashSet<>();
            for (Map.Entry<Integer, Double> entry : systemCpu.machinePercentByPid.entrySet()) {
                int pid = entry.getKey();
                String processName = systemCpu.processNameByPid.getOrDefault(pid, "PID " + pid);
                String packageName = packageByPid.get(pid);
                int uid = uidByPid.getOrDefault(pid, uidForProcessName(processName));
                if (uid >= 10000 && uidNow.containsKey(uid)) continue;
                boolean inUse = packageName != null && inUsePackages.contains(packageName);
                String label = packageName == null ? processName : labelForPackage(packageName);
                double cpu = entry.getValue() * cores; // restore adb/top multi-core scale
                rows.add(new Row(label, pid, false, inUse, cpu,
                        memoryByPid.getOrDefault(pid, 0L)));
                includedPids.add(pid);
            }
            // cpuinfo omits processes that consumed exactly 0 CPU in its current
            // window. Keep foreground apps and the selected IME visible anyway;
            // 0.0% is meaningful here because the process is confirmed running.
            for (Map.Entry<Integer, String> entry : packageByPid.entrySet()) {
                if (includedPids.contains(entry.getKey()) || !inUsePackages.contains(entry.getValue())) continue;
                int uid = uidByPid.getOrDefault(entry.getKey(), -1);
                if (uid >= 10000 && uidNow.containsKey(uid)) continue;
                rows.add(new Row(labelForPackage(entry.getValue()), entry.getKey(), false,
                        true, 0d, memoryByPid.getOrDefault(entry.getKey(), 0L)));
            }
        } else {
            for (PidCounter current : pidNow.values()) {
                PidCounter before = previousPids.get(current.pid);
                double cpu = Double.NaN;
                if (before != null && before.startTicks == current.startTicks) {
                    cpu = PerformanceMath.counterPercent(current.ticks, before.ticks, intervalMs, clockTicksPerSecond);
                }
                String packageName = packageByPid.get(current.pid);
                boolean inUse = packageName != null && inUsePackages.contains(packageName);
                String label = packageName == null ? current.name : labelForPackage(packageName);
                rows.add(new Row(label, current.pid, false, inUse, cpu, current.rssKb));
                if (!Double.isNaN(cpu)) assigned += cpu;
            }
        }
        // A live CPU monitor should show what ran in this sampling window, not
        // paginate through dozens of sleeping UIDs whose valid delta is zero.
        rows.removeIf(row -> row.id != -1 && !row.inUse
                && (Double.isNaN(row.cpuPercent) || row.cpuPercent < 0.05d));
        addUnattributedCpu(rows, totalCpuPercent);
        rows.sort(Comparator.comparingDouble((Row row) -> Double.isNaN(row.cpuPercent) ? -1d : row.cpuPercent)
                .reversed().thenComparingLong(row -> -row.memoryKb).thenComparing(row -> row.name));
        previousUidMicros = uidNow;
        previousPids = pidNow;
        return rows;
    }

    /**
     * The aggregate /proc/stat value also contains kernel threads, interrupts,
     * drivers and vendor/root processes that Android's hidepid policy does not
     * expose by name. Keep that real work visible instead of making the detail
     * list appear to contradict the aggregate meter.
     */
    private static void addUnattributedCpu(List<Row> rows, double totalCpuPercent) {
        if (Double.isNaN(totalCpuPercent)) return;
        double attributed = 0d;
        for (Row row : rows) {
            if (row.id != -1 && !Double.isNaN(row.cpuPercent) && row.cpuPercent > 0d) {
                attributed += row.cpuPercent;
            }
        }
        double remainder = totalCpuPercent - attributed;
        if (remainder >= 0.05d) {
            rows.add(new Row("系统内核 / 其他", -1, false, false, remainder, 0));
        }
    }

    /** Reads the strict three-second counters from the system-UID helper. */
    private List<Row> readPrivilegedRows(Map<Integer, String> packageByPid,
                                         Map<Integer, Integer> uidByPid,
                                         Map<Integer, Long> memoryByUid,
                                         Map<Integer, Long> memoryByPid,
                                         Set<String> inUsePackages) {
        try {
            Bundle data = context.getContentResolver().call(
                    Uri.parse("content://com.kemi.mypad.performance"), "sample", null, null);
            if (data == null || data.getLong("intervalMs", 0L) <= 0) return null;
            int[] ids = data.getIntArray("ids");
            String[] names = data.getStringArray("names");
            double[] cpu = data.getDoubleArray("cpu");
            long[] helperMemory = data.getLongArray("memoryKb");
            boolean[] uidRows = data.getBooleanArray("uidAggregate");
            if (ids == null || names == null || cpu == null || helperMemory == null || uidRows == null
                    || names.length != ids.length || cpu.length != ids.length
                    || helperMemory.length != ids.length || uidRows.length != ids.length) return null;
            List<Row> rows = new ArrayList<>();
            Set<Integer> represented = new HashSet<>();
            for (int i = 0; i < ids.length; i++) {
                boolean inUse = uidRows[i] ? containsUidPackage(ids[i], inUsePackages)
                        : inUsePackages.contains(packageByPid.get(ids[i]));
                long memory = uidRows[i] ? memoryByUid.getOrDefault(ids[i], helperMemory[i])
                        : memoryByPid.getOrDefault(ids[i], helperMemory[i]);
                rows.add(new Row(names[i], ids[i], uidRows[i], inUse, cpu[i], memory));
                represented.add(ids[i]);
            }
            // A newly selected IME/foreground process has no previous counter
            // during its first cycle. Show it immediately at 0.0%, then replace
            // that value with the next real delta.
            for (Map.Entry<Integer, String> entry : packageByPid.entrySet()) {
                if (!inUsePackages.contains(entry.getValue())) continue;
                int uid = uidByPid.getOrDefault(entry.getKey(), -1);
                int id = uid >= 10000 ? uid : entry.getKey();
                if (represented.contains(id)) continue;
                long memory = uid >= 10000 ? memoryByUid.getOrDefault(uid, 0L)
                        : memoryByPid.getOrDefault(entry.getKey(), 0L);
                rows.add(new Row(labelForPackage(entry.getValue()), id, uid >= 10000,
                        true, 0d, memory));
                represented.add(id);
            }
            return rows;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * The detail list represents both work done during this sample and apps the
     * user is actively interacting with.  A foreground app or IME must not
     * disappear merely because it used less than one scheduler tick this cycle.
     */
    private Set<String> inUsePackages() {
        Set<String> result = new HashSet<>();
        try {
            for (SystemPrivilege.ForegroundTask task : SystemPrivilege.foregroundTasksByDisplay(context).values()) {
                if (task.packageName != null && !task.packageName.isEmpty()) result.add(task.packageName);
            }
        } catch (Exception ignored) { }
        try {
            String ime = Settings.Secure.getString(context.getContentResolver(),
                    Settings.Secure.DEFAULT_INPUT_METHOD);
            if (ime != null) {
                int slash = ime.indexOf('/');
                result.add(slash > 0 ? ime.substring(0, slash) : ime);
            }
        } catch (Exception ignored) { }
        return result;
    }

    private Map<Integer, String> runningPackageByPid() {
        Map<Integer, String> result = new HashMap<>();
        if (activityManager == null) return result;
        List<ActivityManager.RunningAppProcessInfo> running = activityManager.getRunningAppProcesses();
        if (running == null) return result;
        for (ActivityManager.RunningAppProcessInfo process : running) {
            String packageName = process.processName;
            if (process.pkgList != null && process.pkgList.length > 0) {
                packageName = process.pkgList[0];
                for (String candidate : process.pkgList) {
                    if (process.processName.equals(candidate) || process.processName.startsWith(candidate + ":")) {
                        packageName = candidate;
                        break;
                    }
                }
            }
            if (packageName != null) result.put(process.pid, packageName);
        }
        return result;
    }

    private Map<Integer, Integer> runningUidByPid() {
        Map<Integer, Integer> result = new HashMap<>();
        if (activityManager == null) return result;
        List<ActivityManager.RunningAppProcessInfo> running = activityManager.getRunningAppProcesses();
        if (running == null) return result;
        for (ActivityManager.RunningAppProcessInfo process : running) result.put(process.pid, process.uid);
        return result;
    }

    private boolean containsUidPackage(int uid, Set<String> packages) {
        String[] uidPackages = packageManager.getPackagesForUid(uid);
        if (uidPackages == null) return false;
        for (String packageName : uidPackages) if (packages.contains(packageName)) return true;
        return false;
    }

    private int uidForProcessName(String processName) {
        if (processName == null || processName.isEmpty()) return -1;
        String packageName = processName;
        int colon = packageName.indexOf(':');
        if (colon > 0) packageName = packageName.substring(0, colon);
        try { return packageManager.getApplicationInfo(packageName, 0).uid; }
        catch (Exception ignored) { return -1; }
    }

    private String labelForPackage(String packageName) {
        try {
            ApplicationInfo info = packageManager.getApplicationInfo(packageName, 0);
            return packageManager.getApplicationLabel(info).toString();
        } catch (Exception ignored) { return packageName; }
    }

    private CpuState readCpuState() {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/stat"))) {
            CpuRow aggregate = null;
            List<CpuRow> cores = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null && line.startsWith("cpu")) {
                String[] fields = line.trim().split("\\s+");
                if (fields.length < 5) continue;
                long total = 0;
                for (int i = 1; i < fields.length; i++) total += parseLong(fields[i]);
                long idle = parseLong(fields[4]) + (fields.length > 5 ? parseLong(fields[5]) : 0L);
                if ("cpu".equals(fields[0])) aggregate = new CpuRow(-1, total, idle);
                else cores.add(new CpuRow(Integer.parseInt(fields[0].substring(3)), total, idle));
            }
            return aggregate == null ? null : new CpuState(aggregate, cores);
        } catch (Exception ignored) { return null; }
    }

    private Map<Integer, Long> readUidCpuMicros() {
        Map<Integer, Long> result = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/uid_cputime/show_uid_stat"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] fields = line.trim().split("\\s+");
                if (fields.length < 3) continue;
                int uid = Integer.parseInt(fields[0].replace(":", ""));
                result.put(uid, parseLong(fields[1]) + parseLong(fields[2]));
            }
        } catch (Exception ignored) { }
        return result;
    }

    private Map<Integer, PidCounter> readPidCounters() {
        Map<Integer, PidCounter> result = new HashMap<>();
        File[] directories = new File("/proc").listFiles(file -> file.isDirectory() && isDigits(file.getName()));
        if (directories == null) return result;
        for (File directory : directories) {
            PidCounter counter = readPidCounter(directory);
            if (counter != null) result.put(counter.pid, counter);
        }
        return result;
    }

    private PidCounter readPidCounter(File directory) {
        try {
            int pid = Integer.parseInt(directory.getName());
            String stat = firstLine(new File(directory, "stat"));
            int close = stat.lastIndexOf(") ");
            if (close <= 0) return null;
            String name = stat.substring(stat.indexOf('(') + 1, close);
            String[] fields = stat.substring(close + 2).split("\\s+");
            if (fields.length <= 19) return null;
            long ticks = parseLong(fields[11]) + parseLong(fields[12]);
            long start = parseLong(fields[19]);
            int uid = -1;
            long rss = 0;
            try (BufferedReader reader = new BufferedReader(new FileReader(new File(directory, "status")))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("Uid:")) uid = Integer.parseInt(line.substring(4).trim().split("\\s+")[0]);
                    else if (line.startsWith("VmRSS:")) rss = parseLong(line.replaceAll("[^0-9]", ""));
                }
            }
            return new PidCounter(pid, uid, name, ticks, start, rss);
        } catch (Exception ignored) { return null; }
    }

    private Map<Integer, Long> readAppPssByUid() {
        Map<Integer, Long> result = new HashMap<>();
        if (activityManager == null) return result;
        List<ActivityManager.RunningAppProcessInfo> running = activityManager.getRunningAppProcesses();
        if (running == null) return result;
        for (ActivityManager.RunningAppProcessInfo process : running) {
            try {
                Debug.MemoryInfo[] memory = activityManager.getProcessMemoryInfo(new int[]{process.pid});
                if (memory.length > 0) result.merge(process.uid, (long) memory[0].getTotalPss(), Long::sum);
            } catch (Exception ignored) { }
        }
        return result;
    }

    private Map<Integer, Long> readAppPssByPid() {
        Map<Integer, Long> result = new HashMap<>();
        if (activityManager == null) return result;
        List<ActivityManager.RunningAppProcessInfo> running = activityManager.getRunningAppProcesses();
        if (running == null) return result;
        for (ActivityManager.RunningAppProcessInfo process : running) {
            try {
                Debug.MemoryInfo[] memory = activityManager.getProcessMemoryInfo(new int[]{process.pid});
                if (memory.length > 0) result.put(process.pid, (long) memory[0].getTotalPss());
            } catch (Exception ignored) { }
        }
        return result;
    }

    private String labelForUid(int uid) {
        String[] packages = packageManager.getPackagesForUid(uid);
        if (packages == null || packages.length == 0) return "UID " + uid;
        if (packages.length > 1) return packages[0] + " 等 " + packages.length + " 个包";
        try {
            ApplicationInfo info = packageManager.getApplicationInfo(packages[0], 0);
            return packageManager.getApplicationLabel(info).toString();
        } catch (Exception ignored) { return packages[0]; }
    }

    private long readFrequency(int core, String... names) {
        for (String name : names) {
            long value = parseLong(firstLine(new File("/sys/devices/system/cpu/cpu" + core + "/cpufreq/" + name)));
            if (value > 0) return value;
        }
        return 0;
    }

    private long[] readSwap() {
        long total = 0;
        long free = 0;
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/meminfo"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("SwapTotal:")) total = parseLong(line.replaceAll("[^0-9]", ""));
                else if (line.startsWith("SwapFree:")) free = parseLong(line.replaceAll("[^0-9]", ""));
            }
        } catch (Exception ignored) { }
        return new long[]{total, free};
    }

    private static CpuRow findCore(CpuState state, int index) {
        if (state == null) return null;
        for (CpuRow row : state.cores) if (row.index == index) return row;
        return null;
    }

    private static double percent(CpuRow current, CpuRow previous) {
        long total = current.total - previous.total;
        long idle = current.idle - previous.idle;
        if (total <= 0 || idle < 0) return Double.NaN;
        return PerformanceMath.busyPercent(total, idle);
    }

    private static boolean isDigits(String value) {
        if (value.isEmpty()) return false;
        for (int i = 0; i < value.length(); i++) if (!Character.isDigit(value.charAt(i))) return false;
        return true;
    }

    private static String firstLine(File file) {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line = reader.readLine();
            return line == null ? "" : line.trim();
        } catch (Exception ignored) { return ""; }
    }

    private static long parseLong(String value) {
        try { return Long.parseLong(value.trim()); } catch (Exception ignored) { return 0L; }
    }
}
