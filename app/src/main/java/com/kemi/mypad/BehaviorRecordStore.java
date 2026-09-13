package com.kemi.mypad;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Append-only, device-protected evidence store for behavior-reproduction sessions. */
final class BehaviorRecordStore {
    private static final String PREFS = "behavior_recorder";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_ACTIVE_SESSION = "active_session";
    private static final String KEY_ACTIVE_BOOT = "active_boot";
    private static final int MAX_SESSIONS = 10;
    private static final long MAX_SESSION_BYTES = 8L * 1024 * 1024;

    private BehaviorRecordStore() { }

    static Context deviceContext(Context context) {
        return context.createDeviceProtectedStorageContext();
    }

    static boolean isEnabled(Context context) {
        return preferences(context).getBoolean(KEY_ENABLED, false);
    }

    static void setEnabled(Context context, boolean enabled) {
        preferences(context).edit().putBoolean(KEY_ENABLED, enabled).commit();
    }

    static synchronized File beginSession(Context context, String reason) {
        Context device = deviceContext(context);
        SharedPreferences prefs = preferences(context);
        int boot = Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        String activePath = prefs.getString(KEY_ACTIVE_SESSION, "");
        File active = activePath.isEmpty() ? null : new File(activePath);
        if (active != null && active.isDirectory() && prefs.getInt(KEY_ACTIVE_BOOT, -2) == boot) {
            append(context, "service_resume", json("reason", reason));
            return active;
        }

        File sessions = new File(device.getFilesDir(), "behavior_records/sessions");
        sessions.mkdirs();
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.CHINA).format(new Date());
        String runId = stamp + "-boot" + boot;
        File session = new File(sessions, runId);
        int suffix = 1;
        while (session.exists()) session = new File(sessions, runId + "-" + suffix++);
        session.mkdirs();
        prefs.edit().putString(KEY_ACTIVE_SESSION, session.getAbsolutePath()).putInt(KEY_ACTIVE_BOOT, boot).commit();
        writeManifest(context, session, runId, boot, reason, null);
        append(context, "session_start", json("reason", reason));
        pruneOldSessions(sessions, session);
        return session;
    }

    static synchronized void append(Context context, String type, JSONObject detail) {
        if (!isEnabled(context)) return;
        File session = activeSession(context);
        if (session == null) session = beginSession(context, "automatic");
        try {
            File eventsFile = new File(session, "events.jsonl");
            if (eventsFile.length() >= MAX_SESSION_BYTES) {
                preferences(context).edit().remove(KEY_ACTIVE_SESSION).remove(KEY_ACTIVE_BOOT).commit();
                session = beginSession(context, "size_rotation");
                eventsFile = new File(session, "events.jsonl");
            }
            JSONObject event = new JSONObject();
            event.put("schema", 1);
            event.put("seq", SystemClock.elapsedRealtimeNanos());
            event.put("wallTimeMs", System.currentTimeMillis());
            event.put("elapsedMs", SystemClock.elapsedRealtime());
            event.put("type", type);
            event.put("detail", detail == null ? new JSONObject() : detail);
            try (FileOutputStream output = new FileOutputStream(eventsFile, true)) {
                output.write((event.toString() + "\n").getBytes(StandardCharsets.UTF_8));
                output.flush();
            }
            session.setLastModified(System.currentTimeMillis());
        } catch (Exception ignored) { }
    }

    static synchronized File exportLatest(Context context) throws Exception {
        File session = activeSession(context);
        if (session == null || !session.isDirectory()) throw new IllegalStateException("没有可导出的记录");
        File events = new File(session, "events.jsonl");
        writeDerivedTimeline(context, session);
        writeManifest(context, session, session.getName(),
                Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1),
                "export", sha256(events));
        File external = context.getExternalCacheDir();
        if (external == null) throw new IllegalStateException("共享存储不可用");
        File exports = new File(external, "diagnostics");
        exports.mkdirs();
        File output = new File(exports, "KEMI-Pads-行为记录-" + session.getName() + ".zip");
        FolderArchive.zip(session, output);
        return output;
    }

    private static void writeDerivedTimeline(Context context, File session) throws Exception {
        List<JSONObject> newest = recentEvents(context, 1200);
        Collections.reverse(newest);
        StringBuilder markdown = new StringBuilder();
        markdown.append("# KEMI Pads 问题复现时间线\n\n")
                .append("会话：").append(session.getName()).append("\n\n")
                .append("> 自动摘要不包含输入文字、密码、剪贴板、屏幕图像或网络内容。")
                .append("精确分析请同时读取 `events.jsonl` 与 `manifest.json`。\n\n")
                .append("| 时间 | 类型 | 现场 |\n|---|---|---|\n");
        long previousHealth = 0;
        for (JSONObject event : newest) {
            String type = event.optString("type");
            long wall = event.optLong("wallTimeMs");
            if ("health_sample".equals(type) && wall - previousHealth < 30000) continue;
            if ("health_sample".equals(type)) previousHealth = wall;
            JSONObject detail = event.optJSONObject("detail");
            if (detail == null) detail = new JSONObject();
            String summary;
            if ("display_foreground".equals(type)) summary = "Display " + detail.optInt("displayId") + " → "
                    + detail.optString("package") + "/" + detail.optString("activity");
            else if ("ui_action".equals(type)) summary = detail.optString("package") + " · "
                    + detail.optString("event") + " · view=" + detail.optString("viewId") + " · bounds=" + detail.optString("bounds");
            else if ("navigation_key".equals(type)) summary = "keyCode=" + detail.optInt("keyCode");
            else if ("usage_event".equals(type)) summary = detail.optString("package") + "/" + detail.optString("class")
                    + " · usageType=" + detail.optInt("usageType");
            else if ("health_sample".equals(type)) summary = "CPU=" + Math.round(detail.optDouble("cpuPercent")) + "% · availMem="
                    + detail.optLong("memoryAvailableBytes") + " · thermal=" + detail.optInt("thermalStatus");
            else if ("system_failure".equals(type)) summary = "异常标签=" + detail.optString("tag");
            else if ("process_exit".equals(type)) summary = detail.optString("package") + "/" + detail.optString("process")
                    + " · reason=" + detail.optInt("reason") + " · status=" + detail.optInt("status")
                    + " · " + detail.optString("description");
            else if ("incident_marker".equals(type)) summary = "用户标记：请重点检查此前的操作和资源曲线";
            else summary = detail.toString();
            String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.CHINA).format(new Date(wall));
            markdown.append('|').append(time).append('|').append(escapeMarkdown(type)).append('|')
                    .append(escapeMarkdown(summary)).append("|\n");
        }
        try (FileOutputStream output = new FileOutputStream(new File(session, "timeline.md"), false)) {
            output.write(markdown.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    static synchronized List<JSONObject> recentEvents(Context context, int limit) {
        File session = activeSession(context);
        if (session == null) return Collections.emptyList();
        File events = new File(session, "events.jsonl");
        if (!events.isFile()) return Collections.emptyList();
        ArrayList<JSONObject> ring = new ArrayList<>();
        try (RandomAccessFile reader = new RandomAccessFile(events, "r")) {
            long position = reader.length() - 1;
            StringBuilder reversed = new StringBuilder();
            while (position >= 0 && ring.size() < limit) {
                reader.seek(position--);
                int value = reader.read();
                if (value == '\n' && reversed.length() == 0) continue;
                if (value == '\n') {
                    addReverseLine(ring, reversed);
                    reversed.setLength(0);
                } else {
                    reversed.append((char) value);
                }
            }
            if (reversed.length() > 0 && ring.size() < limit) addReverseLine(ring, reversed);
        } catch (Exception ignored) { }
        return ring;
    }

    static synchronized long activeBytes(Context context) {
        File session = activeSession(context);
        return session == null ? 0 : treeBytes(session);
    }

    static synchronized String activeSessionName(Context context) {
        File session = activeSession(context);
        return session == null ? "尚未建立" : session.getName();
    }

    private static SharedPreferences preferences(Context context) {
        return deviceContext(context).getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static File activeSession(Context context) {
        String path = preferences(context).getString(KEY_ACTIVE_SESSION, "");
        File value = path.isEmpty() ? null : new File(path);
        return value != null && value.isDirectory() ? value : null;
    }

    private static void writeManifest(Context context, File session, String runId, int boot,
                                      String reason, String eventsHash) {
        try {
            JSONObject manifest = new JSONObject();
            manifest.put("schema", 1);
            manifest.put("runId", runId);
            manifest.put("purpose", "KEMI PAD user-action and system-state reproduction");
            manifest.put("capturePolicy", "no text input, password, clipboard, screenshot, audio or network payload");
            manifest.put("device", Build.MANUFACTURER + " " + Build.MODEL);
            manifest.put("buildFingerprint", Build.FINGERPRINT);
            manifest.put("androidSdk", Build.VERSION.SDK_INT);
            manifest.put("bootCount", boot);
            manifest.put("timezone", java.util.TimeZone.getDefault().getID());
            manifest.put("updatedWallTimeMs", System.currentTimeMillis());
            manifest.put("reason", reason);
            if (eventsHash != null) manifest.put("eventsSha256", eventsHash);
            try {
                android.content.pm.PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
                manifest.put("recorderPackage", context.getPackageName());
                manifest.put("recorderVersion", info.versionName);
                manifest.put("recorderVersionCode", info.getLongVersionCode());
            } catch (Exception ignored) { }
            try (FileOutputStream output = new FileOutputStream(new File(session, "manifest.json"), false)) {
                output.write(manifest.toString(2).getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) { }
    }

    private static void addReverseLine(List<JSONObject> output, StringBuilder reversed) {
        try {
            byte[] bytes = new byte[reversed.length()];
            for (int i = 0; i < reversed.length(); i++) bytes[i] = (byte) reversed.charAt(reversed.length() - 1 - i);
            output.add(new JSONObject(new String(bytes, StandardCharsets.UTF_8)));
        } catch (Exception ignored) { }
    }

    private static void pruneOldSessions(File sessions, File active) {
        File[] values = sessions.listFiles(File::isDirectory);
        if (values == null || values.length <= MAX_SESSIONS) return;
        List<File> ordered = new ArrayList<>();
        Collections.addAll(ordered, values);
        ordered.sort(Comparator.comparingLong(File::lastModified));
        int remaining = ordered.size();
        for (File value : ordered) {
            if (remaining <= MAX_SESSIONS) break;
            if (!value.equals(active)) { deleteTree(value); remaining--; }
        }
    }

    private static long treeBytes(File file) {
        if (file == null || !file.exists()) return 0;
        if (file.isFile()) return file.length();
        long total = 0;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) total += treeBytes(child);
        return total;
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }

    private static String sha256(File file) throws Exception {
        if (file == null || !file.isFile()) return "";
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder value = new StringBuilder();
        for (byte item : digest.digest()) value.append(String.format(Locale.US, "%02x", item & 0xff));
        return value.toString();
    }

    private static String escapeMarkdown(String value) {
        return value == null ? "" : value.replace("|", "\\|").replace("\r", " ").replace("\n", " ");
    }

    static JSONObject json(String key, Object value) {
        JSONObject result = new JSONObject();
        try { result.put(key, value); } catch (Exception ignored) { }
        return result;
    }
}
