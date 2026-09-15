package com.kemi.mypad;

import java.util.Arrays;

/** Dependency-free regression probe, matching the project's existing test style. */
public final class CpuInfoParserProbe {
    public static void main(String[] args) {
        CpuInfoParser.Result result = CpuInfoParser.parse(Arrays.asList(
                "CPU usage from 3200ms to 200ms ago:",
                "  80% 123/com.example.player: 60% user + 20% kernel",
                " +40% 456/com.example.player:remote: 30% user + 10% kernel",
                "  22% TOTAL: 12% user + 10% kernel"), 8);

        require(result.windowMillis == 3000L, "sampling window");
        require(close(result.machinePercentByPid.get(123), 10d), "whole-device scale");
        require(close(result.machinePercentByPid.get(456), 5d), "remote process scale");
        require("com.example.player".equals(result.processNameByPid.get(123)), "main process name");
        require("com.example.player:remote".equals(result.processNameByPid.get(456)), "colon process name");
        require(result.machinePercentByPid.size() == 2, "TOTAL row excluded");
        System.out.println("CpuInfoParserProbe PASS");
    }

    private static boolean close(Double actual, double expected) {
        return actual != null && Math.abs(actual - expected) < 0.001d;
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
