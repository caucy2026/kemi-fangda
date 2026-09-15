package com.kemi.mypad.perfhelper;

import android.app.ActivityManager;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** System-UID, read-only CPU counter bridge for KEMI Pads. */
public final class PerformanceProvider extends ContentProvider {
    public static final String METHOD_SAMPLE = "sample";
    private final Map<Integer, Long> previousUidMicros = new HashMap<>();
    private final Map<Integer, PidCounter> previousPids = new HashMap<>();
    private long previousAt;
    private long clockTicksPerSecond = 100L;

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

    @Override public boolean onCreate() {
        try { clockTicksPerSecond = Math.max(1L, Os.sysconf(OsConstants._SC_CLK_TCK)); }
        catch (Exception ignored) { }
        return true;
    }

    @Override public synchronized Bundle call(String method, String arg, Bundle extras) {
        if (!METHOD_SAMPLE.equals(method)) return Bundle.EMPTY;
        long now = SystemClock.elapsedRealtime();
        long intervalMs = previousAt <= 0 ? 0 : now - previousAt;
        Map<Integer, Long> uidNow = readUidCpuMicros();
        Map<Integer, PidCounter> pidNow = readPidCounters();
        ArrayList<Integer> ids = new ArrayList<>();
        ArrayList<String> names = new ArrayList<>();
        ArrayList<Double> cpu = new ArrayList<>();
        ArrayList<Long> memory = new ArrayList<>();
        ArrayList<Boolean> uidAggregate = new ArrayList<>();

        if (intervalMs > 0) {
            for (Map.Entry<Integer, Long> entry : uidNow.entrySet()) {
                int uid = entry.getKey();
                if (uid < 10000) continue;
                Long before = previousUidMicros.get(uid);
                if (before == null || entry.getValue() < before) continue;
                add(ids, names, cpu, memory, uidAggregate, uid, labelForUid(uid),
                        (entry.getValue() - before) * 100d / (intervalMs * 1000d),
                        0L, true);
            }
            for (PidCounter current : pidNow.values()) {
                if (current.uid >= 10000) continue;
                PidCounter before = previousPids.get(current.pid);
                if (before == null || before.startTicks != current.startTicks || current.ticks < before.ticks) continue;
                add(ids, names, cpu, memory, uidAggregate, current.pid,
                        labelForPid(current),
                        (current.ticks - before.ticks) * 100000d / (clockTicksPerSecond * intervalMs),
                        current.rssKb, false);
            }
        }

        previousUidMicros.clear();
        previousUidMicros.putAll(uidNow);
        previousPids.clear();
        previousPids.putAll(pidNow);
        previousAt = now;

        Bundle result = new Bundle();
        result.putLong("capturedAt", now);
        result.putLong("intervalMs", intervalMs);
        int count = ids.size();
        int[] idArray = new int[count];
        String[] nameArray = new String[count];
        double[] cpuArray = new double[count];
        long[] memoryArray = new long[count];
        boolean[] uidArray = new boolean[count];
        for (int i = 0; i < count; i++) {
            idArray[i] = ids.get(i);
            nameArray[i] = names.get(i);
            cpuArray[i] = cpu.get(i);
            memoryArray[i] = memory.get(i);
            uidArray[i] = uidAggregate.get(i);
        }
        result.putIntArray("ids", idArray);
        result.putStringArray("names", nameArray);
        result.putDoubleArray("cpu", cpuArray);
        result.putLongArray("memoryKb", memoryArray);
        result.putBooleanArray("uidAggregate", uidArray);
        result.putInt("visiblePidCount", pidNow.size());
        return result;
    }

    private static void add(List<Integer> ids, List<String> names, List<Double> cpu,
                            List<Long> memory, List<Boolean> uidAggregate, int id,
                            String name, double percent, long memoryKb, boolean isUid) {
        ids.add(id); names.add(name); cpu.add(Math.max(0d, percent));
        memory.add(memoryKb); uidAggregate.add(isUid);
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
        File[] directories = new File("/proc").listFiles(file -> file.isDirectory() && digits(file.getName()));
        if (directories == null) return result;
        for (File directory : directories) {
            PidCounter row = readPid(directory);
            if (row != null) result.put(row.pid, row);
        }
        return result;
    }

    private PidCounter readPid(File directory) {
        try {
            int pid = Integer.parseInt(directory.getName());
            String stat = firstLine(new File(directory, "stat"));
            int close = stat.lastIndexOf(") ");
            if (close <= 0) return null;
            String comm = stat.substring(stat.indexOf('(') + 1, close);
            String[] fields = stat.substring(close + 2).split("\\s+");
            if (fields.length <= 19) return null;
            int uid = -1;
            long rss = 0;
            String name = comm;
            try (BufferedReader reader = new BufferedReader(new FileReader(new File(directory, "status")))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("Uid:")) uid = Integer.parseInt(line.substring(4).trim().split("\\s+")[0]);
                    else if (line.startsWith("VmRSS:")) rss = parseLong(line.replaceAll("[^0-9]", ""));
                }
            }
            String cmdline = readCmdline(new File(directory, "cmdline"));
            if (!cmdline.isEmpty()) name = cmdline.split("\\s+")[0];
            return new PidCounter(pid, uid, name, parseLong(fields[11]) + parseLong(fields[12]),
                    parseLong(fields[19]), rss);
        } catch (Exception ignored) { return null; }
    }

    private String labelForUid(int uid) {
        PackageManager pm = getContext().getPackageManager();
        String[] packages = pm.getPackagesForUid(uid);
        if (packages == null || packages.length == 0) return "UID " + uid;
        if (packages.length > 1) return packages[0] + " +" + (packages.length - 1);
        return labelForPackage(packages[0]);
    }

    private String labelForPid(PidCounter row) {
        String packageName = row.name;
        int colon = packageName.indexOf(':');
        if (colon > 0) packageName = packageName.substring(0, colon);
        try { getContext().getPackageManager().getApplicationInfo(packageName, 0); }
        catch (Exception ignored) { return row.name; }
        return labelForPackage(packageName);
    }

    private String labelForPackage(String packageName) {
        try {
            PackageManager pm = getContext().getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
            return pm.getApplicationLabel(info).toString();
        } catch (Exception ignored) { return packageName; }
    }

    private static String firstLine(File file) {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line = reader.readLine();
            return line == null ? "" : line.trim();
        } catch (Exception ignored) { return ""; }
    }

    private static String readCmdline(File file) {
        try (java.io.FileInputStream input = new java.io.FileInputStream(file)) {
            byte[] bytes = new byte[512];
            int count = input.read(bytes);
            return count <= 0 ? "" : new String(bytes, 0, count).replace('\0', ' ').trim();
        } catch (Exception ignored) { return ""; }
    }

    private static boolean digits(String value) {
        if (value.isEmpty()) return false;
        for (int i = 0; i < value.length(); i++) if (!Character.isDigit(value.charAt(i))) return false;
        return true;
    }

    private static long parseLong(String value) {
        try { return Long.parseLong(value.trim()); }
        catch (Exception ignored) { return 0L; }
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
}
