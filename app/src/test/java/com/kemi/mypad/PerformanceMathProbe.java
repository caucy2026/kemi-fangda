package com.kemi.mypad;

/** Dependency-free checks for the same 3-second formulas used by the D2 overlay. */
public final class PerformanceMathProbe {
    public static void main(String[] args) {
        require(close(PerformanceMath.busyPercent(2400, 1440), 40d), "single CPU average");
        require(close(PerformanceMath.busyPercent(2400, 1440) * 8d, 320d), "eight-core top scale");
        require(close(PerformanceMath.counterPercent(4_710_000L, 3_000_000L, 3000L, 1_000_000d), 57d), "UID microseconds");
        require(close(PerformanceMath.counterPercent(271L, 100L, 3000L, 100d), 57d), "PID clock ticks");
        require(Double.isNaN(PerformanceMath.counterPercent(10L, null, 3000L, 100d)), "first sample unavailable");
        require(Double.isNaN(PerformanceMath.counterPercent(9L, 10L, 3000L, 100d)), "counter rollback discarded");
        System.out.println("PerformanceMathProbe PASS");
    }

    private static boolean close(double actual, double expected) {
        return Math.abs(actual - expected) < 0.0001d;
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
