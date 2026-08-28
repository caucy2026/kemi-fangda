package com.kemi.mypad;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Dialog;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.net.TrafficStats;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

/** Rounded, live inspector for the app currently resumed on the secondary display. */
final class SecondaryAppInspectorDialog {
    private static final int TEXT = Color.rgb(31, 41, 51);
    private static final int MUTED = Color.rgb(103, 117, 129);
    private static final int BORDER = Color.rgb(218, 226, 231);
    private static final int TEAL = Color.rgb(24, 157, 143);
    private final Activity activity;
    private final int displayId;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Dialog dialog;
    private final ImageView icon;
    private final TextView title;
    private final TextView subtitle;
    private final TextView cpuChip;
    private final TextView memoryChip;
    private final TextView pidChip;
    private final TextView threadChip;
    private final TextView screenText;
    private final TextView runtimeText;
    private final TextView memoryText;
    private final TextView packageText;
    private final TextView permissionText;
    private boolean active;
    private boolean refreshing;
    private String renderedPackage = "";
    private int previousPid;
    private long previousCpuTicks = -1;
    private long previousCpuSampleAt;

    SecondaryAppInspectorDialog(Activity activity, int displayId) {
        this.activity = activity;
        this.displayId = displayId;
        dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = vertical();
        root.setPadding(dp(22), dp(18), dp(22), dp(18));
        root.setBackground(round(Color.WHITE, BORDER, 18));

        LinearLayout heading = horizontal();
        heading.setGravity(Gravity.CENTER_VERTICAL);
        icon = new ImageView(activity);
        icon.setPadding(dp(3), dp(3), dp(3), dp(3));
        heading.addView(icon, new LinearLayout.LayoutParams(dp(54), dp(54)));
        LinearLayout names = vertical();
        title = text("正在读取副屏 APP…", 19, TEXT, true);
        subtitle = text("Display " + displayId, 11, MUTED, false);
        subtitle.setPadding(0, dp(4), 0, 0);
        names.addView(title); names.addView(subtitle);
        LinearLayout.LayoutParams namesLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        namesLp.leftMargin = dp(13); heading.addView(names, namesLp);
        TextView live = text("●  实时", 12, TEAL, true);
        live.setGravity(Gravity.CENTER); live.setBackground(round(Color.rgb(234, 248, 246), Color.rgb(176, 224, 217), 14));
        heading.addView(live, new LinearLayout.LayoutParams(dp(84), dp(36)));
        Button close = new Button(activity);
        close.setText("×"); close.setTextSize(24); close.setTextColor(TEXT); close.setPadding(0, 0, 0, dp(3));
        close.setBackground(round(Color.rgb(239, 243, 246), Color.TRANSPARENT, 18));
        close.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(dp(42), dp(42)); closeLp.leftMargin = dp(12); heading.addView(close, closeLp);
        root.addView(heading, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));

        LinearLayout chips = horizontal(); chips.setPadding(0, dp(13), 0, dp(13));
        cpuChip = chip("CPU", "读取中"); memoryChip = chip("内存", "读取中"); pidChip = chip("PID", "读取中"); threadChip = chip("线程", "读取中");
        chips.addView(cpuChip, weighted()); chips.addView(memoryChip, weighted()); chips.addView(pidChip, weighted()); chips.addView(threadChip, weighted());
        root.addView(chips, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(88)));

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        LinearLayout content = vertical();
        screenText = section(content, "副屏与前台任务");
        runtimeText = section(content, "实时进程");
        memoryText = section(content, "内存明细");
        packageText = section(content, "应用与安装信息");
        permissionText = section(content, "组件与权限");
        TextView note = text("每 1 秒刷新 · 系统任务栈与 ActivityManager 为权威数据；/proc 仅作可用时补充", 10, MUTED, false);
        note.setPadding(dp(4), dp(8), dp(4), dp(4)); content.addView(note);
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        dialog.setContentView(root);
        dialog.setOnDismissListener(ignored -> { active = false; handler.removeCallbacksAndMessages(null); });
    }

    void show() {
        active = true;
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            WindowManager.LayoutParams params = window.getAttributes();
            int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
            int screenHeight = activity.getResources().getDisplayMetrics().heightPixels;
            params.width = Math.min(screenWidth - dp(80), dp(1120));
            params.height = Math.min(screenHeight - dp(80), dp(980));
            params.gravity = Gravity.CENTER;
            params.dimAmount = .28f;
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setAttributes(params);
        }
        refresh();
    }

    private void refresh() {
        if (!active || refreshing) return;
        refreshing = true;
        new Thread(() -> {
            Map<Integer, SystemPrivilege.ForegroundTask> tasks = SystemPrivilege.foregroundTasksByDisplay(activity);
            SystemPrivilege.ForegroundTask task = tasks.get(displayId);
            Inspection inspection = task == null ? null : inspect(task);
            activity.runOnUiThread(() -> {
                refreshing = false;
                if (!active) return;
                if (inspection == null) renderUnavailable(); else render(inspection);
                handler.postDelayed(this::refresh, 1000);
            });
        }, "kemi-secondary-inspector").start();
    }

    private Inspection inspect(SystemPrivilege.ForegroundTask task) {
        Inspection value = new Inspection();
        value.task = task;
        value.process = SystemPrivilege.inspectProcess(activity, task.packageName);
        long sampledAt = SystemClock.elapsedRealtime();
        if (value.process.pid == previousPid && previousCpuTicks >= 0
                && value.process.cpuTicks >= previousCpuTicks && sampledAt > previousCpuSampleAt) {
            double percent = (value.process.cpuTicks - previousCpuTicks) * 1000d
                    / (sampledAt - previousCpuSampleAt);
            value.cpu = String.format(Locale.CHINA, "%.1f%%", Math.min(100d, Math.max(0d, percent)));
        }
        previousPid = value.process.pid;
        previousCpuTicks = value.process.cpuTicks;
        previousCpuSampleAt = sampledAt;
        return value;
    }

    private void render(Inspection value) {
        PackageManager pm = activity.getPackageManager();
        try {
            ApplicationInfo app = pm.getApplicationInfo(value.task.packageName, 0);
            title.setText(friendlyLabel(value.task.packageName, pm.getApplicationLabel(app).toString()));
            icon.setImageDrawable(pm.getApplicationIcon(app));
            subtitle.setText(value.task.packageName + "  ·  Display " + value.task.displayId + "  ·  每秒刷新");
            long rx = TrafficStats.getUidRxBytes(app.uid), tx = TrafficStats.getUidTxBytes(app.uid);
            cpuChip.setText("CPU\n" + (value.cpu.isEmpty() ? "采样中" : value.cpu));
            memoryChip.setText("内存 PSS\n" + kb(value.process.totalPssKb));
            pidChip.setText("PID\n" + (value.process.pid > 0 ? value.process.pid : "未运行"));
            threadChip.setText("线程\n" + empty(value.process.threads));

            Display display = displayFor(value.task.displayId);
            String displayDetails = display == null ? "显示设备信息未公开" :
                    "Display ID：" + display.getDisplayId() + "\n"
                    + "名称：" + display.getName() + "\n"
                    + "分辨率：" + display.getMode().getPhysicalWidth() + " × " + display.getMode().getPhysicalHeight() + "\n"
                    + "刷新率：" + String.format(Locale.CHINA, "%.2f Hz", display.getRefreshRate()) + "\n"
                    + "状态：" + displayState(display.getState()) + "  ·  有效：" + (display.isValid() ? "是" : "否");
            screenText.setText("当前 Activity：" + value.task.componentName() + "\n" + displayDetails);
            runtimeText.setText("进程名：" + empty(value.process.processName) + "\n"
                    + "进程状态：" + empty(value.process.state) + "  ·  Android 重要级：" + importance(value.process.importance) + "\n"
                    + "PID / PPID：" + (value.process.pid > 0 ? value.process.pid : "未运行") + " / " + empty(value.process.ppid) + "\n"
                    + "Linux UID：" + value.process.uid + "  ·  LRU：" + value.process.lru + "\n"
                    + "线程：" + empty(value.process.threads) + "  ·  文件描述符：" + empty(value.process.fdCount) + "\n"
                    + "虚拟内存：" + empty(value.process.vmSize) + "  ·  RSS：" + empty(value.process.vmRss) + "  ·  Swap：" + empty(value.process.vmSwap) + "\n"
                    + "网络累计：接收 " + bytes(rx) + "  ·  发送 " + bytes(tx));
            memoryText.setText("总 PSS：" + kb(value.process.totalPssKb)
                    + "  ·  私有脏页：" + kb(value.process.totalPrivateDirtyKb)
                    + "  ·  共享脏页：" + kb(value.process.totalSharedDirtyKb) + "\n"
                    + "Java 堆：" + memoryStat(value.process.javaHeapKb)
                    + "  ·  Native 堆：" + memoryStat(value.process.nativeHeapKb) + "\n"
                    + "代码：" + memoryStat(value.process.codeKb)
                    + "  ·  栈：" + memoryStat(value.process.stackKb)
                    + "  ·  图形：" + memoryStat(value.process.graphicsKb));
            if (!renderedPackage.equals(value.task.packageName)) {
                renderPackageStatic(pm, app, value.task.packageName);
                renderedPackage = value.task.packageName;
            }
        } catch (Exception error) {
            title.setText(value.task.packageName);
            subtitle.setText("应用元数据读取失败：" + error.getClass().getSimpleName());
        }
    }

    private void renderPackageStatic(PackageManager pm, ApplicationInfo app, String packageName) throws Exception {
        int flags = PackageManager.GET_ACTIVITIES | PackageManager.GET_SERVICES | PackageManager.GET_RECEIVERS
                | PackageManager.GET_PROVIDERS | PackageManager.GET_PERMISSIONS;
        PackageInfo info = pm.getPackageInfo(packageName, flags);
        String version = info.versionName == null ? "—" : info.versionName;
        packageText.setText("包名：" + packageName + "\n"
                + "版本：" + version + "  ·  versionCode " + info.getLongVersionCode() + "\n"
                + "Android UID：" + app.uid + "  ·  目标 SDK：" + app.targetSdkVersion + "  ·  最低 SDK：" + app.minSdkVersion + "\n"
                + "类型：" + (((app.flags & ApplicationInfo.FLAG_SYSTEM) != 0) ? "系统应用" : "用户应用")
                + (((app.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) ? "  ·  可调试" : "") + "\n"
                + "首次安装：" + date(info.firstInstallTime) + "\n"
                + "最近更新：" + date(info.lastUpdateTime) + "\n"
                + "APK：" + app.sourceDir + "\n"
                + "数据目录：" + app.dataDir + "\n"
                + "进程名：" + app.processName);

        StringBuilder components = new StringBuilder();
        components.append("Activity ").append(count(info.activities))
                .append("  ·  Service ").append(count(info.services))
                .append("  ·  Receiver ").append(count(info.receivers))
                .append("  ·  Provider ").append(count(info.providers)).append("\n\n权限：");
        if (info.requestedPermissions == null || info.requestedPermissions.length == 0) components.append("\n未声明权限");
        else for (int i = 0; i < info.requestedPermissions.length; i++) {
            boolean granted = info.requestedPermissionsFlags != null && i < info.requestedPermissionsFlags.length
                    && (info.requestedPermissionsFlags[i] & PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0;
            components.append("\n").append(granted ? "✓ " : "○ ").append(info.requestedPermissions[i]);
        }
        permissionText.setText(components.toString());
    }

    private void renderUnavailable() {
        title.setText("副屏当前没有可识别 APP"); icon.setImageDrawable(null);
        subtitle.setText("Display " + displayId + " · 正在持续检测");
        cpuChip.setText("CPU\n—"); memoryChip.setText("内存 RSS\n—"); pidChip.setText("PID\n—"); threadChip.setText("线程\n—");
        screenText.setText("系统任务栈暂未返回副屏前台 Activity。弹窗会每秒自动重试。");
        runtimeText.setText("未取得进程信息"); memoryText.setText("未取得内存信息");
    }

    private TextView section(LinearLayout parent, String heading) {
        LinearLayout card = vertical(); card.setPadding(dp(16), dp(13), dp(16), dp(14));
        card.setBackground(round(Color.rgb(248, 250, 252), BORDER, 12));
        card.addView(text(heading, 12, MUTED, true));
        TextView body = text("读取中…", 11, TEXT, false);
        body.setTypeface(Typeface.MONOSPACE); body.setTextIsSelectable(true); body.setPadding(0, dp(9), 0, 0);
        card.addView(body);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(10)); parent.addView(card, lp);
        return body;
    }

    private TextView chip(String label, String value) {
        TextView view = text(label + "\n" + value, 11, TEXT, true); view.setGravity(Gravity.CENTER);
        view.setLineSpacing(0, 1.12f); view.setBackground(round(Color.rgb(242, 247, 249), BORDER, 11)); return view;
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1); lp.setMargins(dp(4), 0, dp(4), 0); return lp;
    }

    private Display displayFor(int id) {
        DisplayManager manager = (DisplayManager) activity.getSystemService(Context.DISPLAY_SERVICE);
        return manager == null ? null : manager.getDisplay(id);
    }

    private String friendlyLabel(String pkg, String fallback) {
        if ("com.huanglong.portui".equals(pkg)) return "Source";
        if ("com.newlinksz.kemi.remote".equals(pkg)) return "KEMI 远程办公";
        return fallback;
    }

    private String displayState(int state) {
        if (state == Display.STATE_ON) return "开启";
        if (state == Display.STATE_OFF) return "关闭";
        if (state == Display.STATE_DOZE || state == Display.STATE_DOZE_SUSPEND) return "休眠";
        return "状态 " + state;
    }

    private int count(Object[] values) { return values == null ? 0 : values.length; }
    private String empty(String value) { return value == null || value.isEmpty() ? "未公开" : value; }
    private String kb(int value) { return value <= 0 ? "未公开" : bytes(value * 1024L); }
    private String memoryStat(String value) {
        if (value == null || value.isEmpty() || "0".equals(value)) return "未公开";
        try { return bytes(Long.parseLong(value) * 1024L); } catch (Exception ignored) { return value; }
    }
    private String importance(int value) {
        if (value <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) return "前台";
        if (value <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE) return "可见";
        if (value <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE) return "服务";
        if (value <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED) return "缓存";
        return "未运行";
    }
    private String date(long time) { return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date(time)); }
    private String bytes(long value) {
        if (value < 0) return "未公开";
        if (value < 1024) return value + " B";
        double kb = value / 1024d; if (kb < 1024) return String.format(Locale.CHINA, "%.1f KB", kb);
        double mb = kb / 1024d; if (mb < 1024) return String.format(Locale.CHINA, "%.1f MB", mb);
        return String.format(Locale.CHINA, "%.2f GB", mb / 1024d);
    }

    private LinearLayout vertical() { LinearLayout value = new LinearLayout(activity); value.setOrientation(LinearLayout.VERTICAL); return value; }
    private LinearLayout horizontal() { LinearLayout value = new LinearLayout(activity); value.setOrientation(LinearLayout.HORIZONTAL); return value; }
    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(activity); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return view;
    }
    private GradientDrawable round(int fill, int stroke, int radius) {
        GradientDrawable shape = new GradientDrawable(); shape.setColor(fill); shape.setCornerRadius(dp(radius));
        if (stroke != Color.TRANSPARENT) shape.setStroke(dp(1), stroke); return shape;
    }
    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }

    private static final class Inspection {
        SystemPrivilege.ForegroundTask task;
        SystemPrivilege.ProcessDetails process;
        String cpu = "";
    }
}
