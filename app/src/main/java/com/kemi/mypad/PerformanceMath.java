package com.kemi.mypad;

/** Pure CPU math shared by the overlay and dependency-free regression probes. */
final class PerformanceMath {
    private PerformanceMath() { }

    static double busyPercent(long totalDelta, long idleDelta) {
        if (totalDelta <= 0 || idleDelta < 0) return Double.NaN;
        return Math.max(0d, Math.min(100d, (totalDelta - idleDelta) * 100d / totalDelta));
    }

    static double counterPercent(long current, Long previous, long intervalMs, double unitsPerSecond) {
        if (previous == null || current < previous || intervalMs <= 0 || unitsPerSecond <= 0) return Double.NaN;
        return Math.max(0d, (current - previous) * 100000d / (unitsPerSecond * intervalMs));
    }
}
