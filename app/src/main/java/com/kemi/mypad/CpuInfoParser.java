package com.kemi.mypad;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure parser for the stable, human-readable output of dumpsys cpuinfo. */
final class CpuInfoParser {
    private static final Pattern PROCESS = Pattern.compile(
            "^\\s*\\+?([0-9.]+)%\\s+(\\d+)/(.+?):\\s.*$");
    private static final Pattern WINDOW = Pattern.compile(
            "CPU usage from (\\d+)ms to (\\d+)ms ago.*");

    private CpuInfoParser() { }

    static Result parse(List<String> lines, int availableCores) {
        Result result = new Result();
        int cores = Math.max(1, availableCores);
        for (String line : lines) {
            Matcher window = WINDOW.matcher(line);
            if (window.find()) {
                result.windowMillis = Math.abs(Long.parseLong(window.group(1))
                        - Long.parseLong(window.group(2)));
            }
            Matcher process = PROCESS.matcher(line);
            if (!process.matches()) continue;
            double oneCorePercent = Double.parseDouble(process.group(1));
            int pid = Integer.parseInt(process.group(2));
            result.machinePercentByPid.put(pid, Math.min(100d, oneCorePercent / cores));
            result.processNameByPid.put(pid, process.group(3).trim());
        }
        return result;
    }

    static final class Result {
        final Map<Integer, Double> machinePercentByPid = new HashMap<>();
        final Map<Integer, String> processNameByPid = new HashMap<>();
        long windowMillis = -1;
    }
}
