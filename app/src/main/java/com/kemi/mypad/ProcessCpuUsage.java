package com.kemi.mypad;

/** Pure calculation helpers for machine-wide per-process CPU percentages. */
final class ProcessCpuUsage {
    private ProcessCpuUsage() { }

    static double percent(long processDeltaTicks, long aggregateDeltaTicks) {
        if (processDeltaTicks < 0 || aggregateDeltaTicks <= 0) return -1d;
        return Math.min(100d, Math.max(0d, processDeltaTicks * 100d / aggregateDeltaTicks));
    }
}
