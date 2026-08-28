package com.kemi.mypad;

import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Map;

/** CLI probe: validates the parser against a real dumpsys capture without launching UI. */
public final class ActivityTaskParserProbe {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("usage: <dump-file> <displayId=package> [displayId=package ...]");
        }
        Map<Integer, ActivityTaskSnapshotParser.Task> tasks =
                ActivityTaskSnapshotParser.parse(new String(
                        Files.readAllBytes(Paths.get(args[0])), StandardCharsets.UTF_8));
        for (int i = 1; i < args.length; i++) {
            String[] expected = args[i].split("=", 2);
            int displayId = Integer.parseInt(expected[0]);
            ActivityTaskSnapshotParser.Task actual = tasks.get(displayId);
            if (actual == null || !expected[1].equals(actual.packageName)) {
                throw new AssertionError("Display " + displayId + " expected " + expected[1]
                        + " but was " + (actual == null ? "<missing>" : actual.packageName));
            }
        }
        for (ActivityTaskSnapshotParser.Task task : tasks.values()) {
            System.out.println("PASS Display " + task.displayId + " -> " + task.packageName + "/" + task.activityName);
        }
    }
}
