package com.kemi.mypad;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Bound-only CPU sampler whose lifetime follows the Activity Monitor UI. */
public final class ProcessCpuMonitorService extends Service {
    static final long SAMPLE_INTERVAL_MS = 3000L;

    private final LocalBinder binder = new LocalBinder();
    private final Map<Integer, ProcessCounter> previousProcesses = new HashMap<>();
    private HandlerThread workerThread;
    private Handler worker;
    private CpuTimes previousCpu;
    private volatile Sample latest = Sample.waiting();
    private volatile boolean sampling;

    public final class LocalBinder extends Binder {
        Sample latest() { return ProcessCpuMonitorService.this.latest; }
    }

    private final Runnable sampler = new Runnable() {
        @Override public void run() {
            if (!sampling) return;
            sampleOnce();
            if (sampling) worker.postDelayed(this, SAMPLE_INTERVAL_MS);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        workerThread = new HandlerThread("kemi-process-cpu-monitor");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
    }

    @Override public IBinder onBind(Intent intent) {
        sampling = true;
        worker.removeCallbacks(sampler);
        worker.post(sampler);
        return binder;
    }

    @Override public boolean onUnbind(Intent intent) {
        sampling = false;
        if (worker != null) worker.removeCallbacksAndMessages(null);
        stopSelf();
        return false;
    }

    @Override public void onDestroy() {
        sampling = false;
        if (worker != null) worker.removeCallbacksAndMessages(null);
        previousProcesses.clear();
        previousCpu = null;
        latest = Sample.waiting();
        if (workerThread != null) workerThread.quitSafely();
        super.onDestroy();
    }

    private void sampleOnce() {
        long capturedAt = SystemClock.elapsedRealtime();
        CpuTimes cpu = readCpuTimes();
        Map<Integer, ProcessCounter> current = readAccessibleProcesses();
        Map<Integer, ProcessValue> values = new HashMap<>();
        float totalPercent = -1f;
        float[] corePercents = new float[0];

        CpuTimes beforeCpu = previousCpu;
        if (cpu != null && beforeCpu != null && cpu.total > beforeCpu.total) {
            totalPercent = busyPercent(cpu.total - beforeCpu.total, cpu.idle - beforeCpu.idle);
            int coreCount = Math.min(cpu.cores.length, beforeCpu.cores.length);
            corePercents = new float[coreCount];
            for (int i = 0; i < coreCount; i++) {
                long totalDelta = cpu.cores[i][0] - beforeCpu.cores[i][0];
                long idleDelta = cpu.cores[i][1] - beforeCpu.cores[i][1];
                corePercents[i] = busyPercent(totalDelta, idleDelta);
            }
            long aggregateDelta = cpu.total - beforeCpu.total;
            for (Map.Entry<Integer, ProcessCounter> entry : current.entrySet()) {
                ProcessCounter before = previousProcesses.get(entry.getKey());
                ProcessCounter now = entry.getValue();
                if (before == null || before.startTimeTicks != now.startTimeTicks) continue;
                double percent = ProcessCpuUsage.percent(now.cpuTicks - before.cpuTicks, aggregateDelta);
                if (percent >= 0) values.put(entry.getKey(), new ProcessValue(percent, false, now.name));
            }
        }

        // Cross-UID procfs is normally isolated on Android 12. Keep the exact
        // system_server sampling window when DUMP is used as the fallback.
        SystemPrivilege.CpuInfoSnapshot system = SystemPrivilege.processCpuInfo();
        for (Map.Entry<Integer, Double> entry : system.machinePercentByPid.entrySet()) {
            if (values.containsKey(entry.getKey())) continue;
            String name = system.processNameByPid.getOrDefault(entry.getKey(), "PID " + entry.getKey());
            values.put(entry.getKey(), new ProcessValue(entry.getValue(), true, name));
        }

        previousCpu = cpu;
        previousProcesses.clear();
        previousProcesses.putAll(current);
        latest = new Sample(capturedAt, totalPercent, corePercents, values,
                system.windowMillis, system.readMillis, system.error);
    }

    private CpuTimes readCpuTimes() {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/stat"))) {
            long total = -1;
            long idle = -1;
            java.util.ArrayList<long[]> cores = new java.util.ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null && line.startsWith("cpu")) {
                String[] fields = line.trim().split("\\s+");
                long rowTotal = 0;
                for (int i = 1; i < fields.length; i++) rowTotal += Long.parseLong(fields[i]);
                long rowIdle = Long.parseLong(fields[4])
                        + (fields.length > 5 ? Long.parseLong(fields[5]) : 0L);
                if ("cpu".equals(fields[0])) {
                    total = rowTotal;
                    idle = rowIdle;
                } else {
                    cores.add(new long[]{rowTotal, rowIdle});
                }
            }
            if (total < 0) return null;
            return new CpuTimes(total, idle, cores.toArray(new long[0][]));
        } catch (Exception ignored) {
            return null;
        }
    }

    private Map<Integer, ProcessCounter> readAccessibleProcesses() {
        Map<Integer, ProcessCounter> values = new HashMap<>();
        File[] entries = new File("/proc").listFiles();
        if (entries == null) return values;
        for (File entry : entries) {
            int pid;
            try { pid = Integer.parseInt(entry.getName()); }
            catch (NumberFormatException ignored) { continue; }
            ProcessCounter counter = readProcessCounter(pid);
            if (counter != null) values.put(pid, counter);
        }
        return values;
    }

    private ProcessCounter readProcessCounter(int pid) {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/" + pid + "/stat"))) {
            String line = reader.readLine();
            int close = line == null ? -1 : line.lastIndexOf(')');
            int open = line == null ? -1 : line.indexOf('(');
            if (open < 0 || close <= open) return null;
            String[] fields = line.substring(close + 2).trim().split("\\s+");
            if (fields.length <= 19) return null;
            long cpuTicks = Long.parseLong(fields[11]) + Long.parseLong(fields[12]);
            long startTimeTicks = Long.parseLong(fields[19]);
            return new ProcessCounter(cpuTicks, startTimeTicks, line.substring(open + 1, close));
        } catch (Exception ignored) {
            return null;
        }
    }

    private float busyPercent(long totalDelta, long idleDelta) {
        if (totalDelta <= 0) return 0f;
        return Math.max(0f, Math.min(100f, (totalDelta - idleDelta) * 100f / totalDelta));
    }

    static final class Sample {
        final long capturedAtElapsed;
        final float totalPercent;
        final float[] corePercents;
        final Map<Integer, ProcessValue> processes;
        final long systemWindowMillis;
        final long systemReadMillis;
        final String systemError;

        Sample(long capturedAtElapsed, float totalPercent, float[] corePercents,
               Map<Integer, ProcessValue> processes, long systemWindowMillis,
               long systemReadMillis, String systemError) {
            this.capturedAtElapsed = capturedAtElapsed;
            this.totalPercent = totalPercent;
            this.corePercents = corePercents.clone();
            this.processes = Collections.unmodifiableMap(new HashMap<>(processes));
            this.systemWindowMillis = systemWindowMillis;
            this.systemReadMillis = systemReadMillis;
            this.systemError = systemError == null ? "" : systemError;
        }

        static Sample waiting() {
            return new Sample(0, -1f, new float[0], Collections.emptyMap(), -1, 0, "采样中");
        }

        boolean ready() { return capturedAtElapsed > 0 && (!processes.isEmpty() || totalPercent >= 0); }
    }

    static final class ProcessValue {
        final double percent;
        final boolean systemAverage;
        final String name;

        ProcessValue(double percent, boolean systemAverage, String name) {
            this.percent = percent;
            this.systemAverage = systemAverage;
            this.name = name == null ? "" : name;
        }
    }

    private static final class CpuTimes {
        final long total;
        final long idle;
        final long[][] cores;

        CpuTimes(long total, long idle, long[][] cores) {
            this.total = total;
            this.idle = idle;
            this.cores = cores;
        }
    }

    private static final class ProcessCounter {
        final long cpuTicks;
        final long startTimeTicks;
        final String name;

        ProcessCounter(long cpuTicks, long startTimeTicks, String name) {
            this.cpuTicks = cpuTicks;
            this.startTimeTicks = startTimeTicks;
            this.name = name;
        }
    }
}
