package com.kemi.mypad;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Device-management bridge for the rooted KEMI PAD firmware. */
final class SystemPrivilege {
    private static final Pattern DISPLAY = Pattern.compile("^Display #(\\d+)");
    private static final Pattern RESUMED = Pattern.compile(
            "mResumedActivity:.*? u\\d+ ([A-Za-z0-9_.]+)/(?:[A-Za-z0-9_.$]+)");

    static final class Result {
        final int exitCode;
        final String output;

        Result(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }

        boolean ok() { return exitCode == 0; }
    }

    private SystemPrivilege() { }

    static Result run(String command) {
        Process process = null;
        StringBuilder output = new StringBuilder();
        try {
            process = new ProcessBuilder("/system/xbin/su", "0", "sh", "-c", command)
                    .redirectErrorStream(true).start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) output.append(line).append('\n');
            }
            if (!process.waitFor(8, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new Result(124, output.toString());
            }
            return new Result(process.exitValue(), output.toString());
        } catch (Exception error) {
            return new Result(-1, error.getClass().getSimpleName() + ": " + error.getMessage());
        } finally {
            if (process != null) process.destroy();
        }
    }

    static Map<Integer, String> foregroundPackagesByDisplay() {
        Result result = run("dumpsys activity activities");
        Map<Integer, String> packages = new LinkedHashMap<>();
        if (!result.ok()) return packages;
        int displayId = -1;
        for (String line : result.output.split("\\n")) {
            Matcher display = DISPLAY.matcher(line.trim());
            if (display.find()) {
                displayId = Integer.parseInt(display.group(1));
                continue;
            }
            if (displayId < 0 || packages.containsKey(displayId)) continue;
            Matcher resumed = RESUMED.matcher(line.trim());
            if (resumed.find()) packages.put(displayId, resumed.group(1));
        }
        return packages;
    }

    static boolean clearApplicationCache(String dataDir) {
        if (dataDir == null || !(dataDir.startsWith("/data/user/") || dataDir.startsWith("/data/data/"))) return false;
        String cache = dataDir + "/cache";
        String codeCache = dataDir + "/code_cache";
        return run("find " + quote(cache) + " " + quote(codeCache)
                + " -mindepth 1 -maxdepth 1 -exec rm -rf -- {} + 2>/dev/null").ok();
    }

    static boolean uninstallUserPackage(String packageName) {
        if (!safePackage(packageName)) return false;
        Result result = run("pm uninstall --user 0 " + packageName);
        return result.ok() && result.output.contains("Success");
    }

    private static boolean safePackage(String value) {
        return value != null && value.matches("[A-Za-z][A-Za-z0-9_.]*");
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
