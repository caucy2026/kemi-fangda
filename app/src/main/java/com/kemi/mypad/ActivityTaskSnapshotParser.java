package com.kemi.mypad;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure parser kept independent of Android APIs so display/task mapping can be unit-probed. */
final class ActivityTaskSnapshotParser {
    private static final Pattern DISPLAY = Pattern.compile("^Display #(\\d+)");
    private static final Pattern RESUMED = Pattern.compile(
            "mResumedActivity:.*? u\\d+ ([A-Za-z0-9_.]+)/([A-Za-z0-9_.$]+)");

    static final class Task {
        final int displayId;
        final String packageName;
        final String activityName;

        Task(int displayId, String packageName, String activityName) {
            this.displayId = displayId;
            this.packageName = packageName;
            this.activityName = activityName.startsWith(".") ? packageName + activityName : activityName;
        }
    }

    private ActivityTaskSnapshotParser() { }

    static Map<Integer, Task> parse(String dump) {
        Map<Integer, Task> tasks = new LinkedHashMap<>();
        if (dump == null || dump.isEmpty()) return tasks;
        int displayId = -1;
        for (String raw : dump.split("\\n")) {
            String line = raw.trim();
            Matcher display = DISPLAY.matcher(line);
            if (display.find()) {
                displayId = Integer.parseInt(display.group(1));
                continue;
            }
            if (displayId < 0 || tasks.containsKey(displayId)) continue;
            Matcher resumed = RESUMED.matcher(line);
            if (resumed.find()) tasks.put(displayId, new Task(displayId, resumed.group(1), resumed.group(2)));
        }
        return tasks;
    }
}
