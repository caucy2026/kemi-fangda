package com.kemi.mypad;

/** Plain Java regression probe for whole-device per-process CPU percentages. */
public final class ProcessCpuUsageProbe {
    public static void main(String[] args) {
        require(close(ProcessCpuUsage.percent(80, 800), 10d), "whole-device share");
        require(close(ProcessCpuUsage.percent(800, 800), 100d), "full device");
        require(ProcessCpuUsage.percent(-1, 800) < 0, "counter rollback");
        require(ProcessCpuUsage.percent(10, 0) < 0, "missing interval");
        System.out.println("ProcessCpuUsageProbe PASS");
    }

    private static boolean close(double left, double right) {
        return Math.abs(left - right) < 0.0001d;
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
