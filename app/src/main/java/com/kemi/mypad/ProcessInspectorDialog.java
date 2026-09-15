package com.kemi.mypad;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Dialog;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.TrafficStats;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
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

/** Rounded 3-second live inspector bound to one selected process PID. */
final class ProcessInspectorDialog {
    private static final int TEXT = Color.rgb(31, 41, 51);
    private static final int MUTED = Color.rgb(103, 117, 129);
    private static final int BORDER = Color.rgb(218, 226, 231);
    private static final int TEAL = Color.rgb(24, 157, 143);
    private static final long REFRESH_MS = 3000L;

    private final Activity activity;
    private final int pid;
    private final String initialName;
    private final String initialLabel;
    private final String[] packages;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Dialog dialog;
    private final ImageView icon;
    private final TextView title;
    private final TextView subtitle;
    private final TextView cpuChip;
    private final TextView memoryChip;
    private final TextView threadChip;
    private final TextView stateChip;
    private final TextView runtimeText;
    private final TextView memoryText;
    private final TextView ioText;
    private final TextView packageText;
    private boolean active;
    private boolean refreshing;
    private long previousCpuTicks = -1;
    private long previousTotalCpuTicks = -1;
    private long previousStartTimeTicks = -1;

    ProcessInspectorDialog(Activity activity, int pid, String processName, String label, String[] packages) {
        this.activity = activity;
        this.pid = pid;
        this.initialName = processName == null ? "" : processName;
        this.initialLabel = label == null ? this.initialName : label;
        this.packages = packages == null ? new String[0] : packages.clone();
        dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = vertical();
        root.setPadding(dp(22), dp(18), dp(22), dp(18));
        root.setBackground(round(Color.WHITE, BORDER, 18));
        LinearLayout heading = horizontal(); heading.setGravity(Gravity.CENTER_VERTICAL);
        icon = new ImageView(activity); icon.setPadding(dp(3), dp(3), dp(3), dp(3));
        heading.addView(icon, new LinearLayout.LayoutParams(dp(54), dp(54)));
        LinearLayout names = vertical();
        title = text(initialLabel, 19, TEXT, true);
        subtitle = text(initialName + "  ·  PID " + pid, 11, MUTED, false); subtitle.setPadding(0, dp(4), 0, 0);
        names.addView(title); names.addView(subtitle);
        LinearLayout.LayoutParams namesLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        namesLp.leftMargin = dp(13); heading.addView(names, namesLp);
        TextView live = text("●  3 秒刷新", 12, TEAL, true); live.setGravity(Gravity.CENTER);
        live.setBackground(round(Color.rgb(234, 248, 246), Color.rgb(176, 224, 217), 14));
        heading.addView(live, new LinearLayout.LayoutParams(dp(106), dp(36)));
        Button close = new Button(activity); close.setText("×"); close.setTextSize(24); close.setTextColor(TEXT);
        close.setPadding(0, 0, 0, dp(3)); close.setBackground(round(Color.rgb(239, 243, 246), Color.TRANSPARENT, 18));
        close.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(dp(42), dp(42)); closeLp.leftMargin = dp(12);
        heading.addView(close, closeLp);
        root.addView(heading, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));

        LinearLayout chips = horizontal(); chips.setPadding(0, dp(13), 0, dp(13));
        cpuChip = chip("CPU", "采样中"); memoryChip = chip("内存 PSS", "读取中");
        threadChip = chip("线程", "读取中"); stateChip = chip("状态", "读取中");
        chips.addView(cpuChip, weighted()); chips.addView(memoryChip, weighted());
        chips.addView(threadChip, weighted()); chips.addView(stateChip, weighted());
        root.addView(chips, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(88)));

        ScrollView scroll = new ScrollView(activity); scroll.setFillViewport(true);
        LinearLayout content = vertical();
        runtimeText = section(content, "实时进程");
        memoryText = section(content, "内存明细");
        ioText = section(content, "I/O 与网络累计");
        packageText = section(content, "应用与安装信息");
        TextView note = text("CPU 为该进程占整台设备总算力的比例；数据每 3 秒按增量计算。系统未公开的字段明确显示为“未公开”。", 10, MUTED, false);
        note.setPadding(dp(4), dp(8), dp(4), dp(4)); content.addView(note);
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        dialog.setContentView(root);
        dialog.setOnDismissListener(ignored -> { active = false; handler.removeCallbacksAndMessages(null); });
        renderStaticPackageInfo();
    }

    void show() {
        active = true;
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            WindowManager.LayoutParams params = window.getAttributes();
            int width = activity.getResources().getDisplayMetrics().widthPixels;
            int height = activity.getResources().getDisplayMetrics().heightPixels;
            params.width = Math.min(width - dp(80), dp(1040));
            params.height = Math.min(height - dp(80), dp(920));
            params.gravity = Gravity.CENTER; params.dimAmount = .28f;
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); window.setAttributes(params);
        }
        refresh();
    }

    private void refresh() {
        if (!active || refreshing) return;
        refreshing = true;
        new Thread(() -> {
            long started = android.os.SystemClock.elapsedRealtime();
            SystemPrivilege.ProcessDetails details = SystemPrivilege.inspectProcess(activity, pid);
            long totalTicks = SystemPrivilege.aggregateCpuTicks();
            double cpu = -1;
            boolean cpuIsAverage = false;
            long cpuWindowMillis = -1;
            if (details.pid == pid && details.startTimeTicks == previousStartTimeTicks
                    && previousCpuTicks >= 0 && previousTotalCpuTicks >= 0) {
                cpu = ProcessCpuUsage.percent(details.cpuTicks - previousCpuTicks, totalTicks - previousTotalCpuTicks);
            }
            if (cpu < 0) {
                SystemPrivilege.CpuInfoSnapshot cpuInfo = SystemPrivilege.processCpuInfo();
                Double fallback = cpuInfo.machinePercentByPid.get(pid);
                if (fallback == null && cpuInfo.error.isEmpty() && !cpuInfo.machinePercentByPid.isEmpty()) fallback = 0d;
                if (fallback != null) {
                    cpu = fallback;
                    cpuIsAverage = true;
                    cpuWindowMillis = cpuInfo.windowMillis;
                }
            }
            previousCpuTicks = details.cpuTicks;
            previousTotalCpuTicks = totalTicks;
            previousStartTimeTicks = details.startTimeTicks;
            final double measuredCpu = cpu;
            final boolean measuredCpuIsAverage = cpuIsAverage;
            final long measuredCpuWindow = cpuWindowMillis;
            final long readMillis = android.os.SystemClock.elapsedRealtime() - started;
            activity.runOnUiThread(() -> {
                refreshing = false;
                if (!active) return;
                render(details, measuredCpu, measuredCpuIsAverage, measuredCpuWindow, readMillis);
                handler.postDelayed(this::refresh, REFRESH_MS);
            });
        }, "kemi-process-inspector-" + pid).start();
    }

    private void render(SystemPrivilege.ProcessDetails value, double cpu, boolean cpuIsAverage,
                        long cpuWindowMillis, long readMillis) {
        if (!value.visibleToActivityManager && !value.procReadable) {
            cpuChip.setText("CPU\n—"); memoryChip.setText("内存 PSS\n—"); threadChip.setText("线程\n—"); stateChip.setText("状态\n已结束");
            subtitle.setText(initialName + "  ·  PID " + pid + " 已结束");
            runtimeText.setText("选中的进程已经退出。窗口仍会每 3 秒检查一次，PID 不会自动切换到其他进程。");
            return;
        }
        cpuChip.setText((cpuIsAverage ? "CPU 近期平均\n" : "CPU\n")
                + (cpu < 0 ? "系统未公开" : String.format(Locale.CHINA, "%.1f%%", cpu)));
        memoryChip.setText("内存 PSS\n" + kb(value.totalPssKb));
        threadChip.setText("线程\n" + empty(value.threads));
        stateChip.setText("状态\n" + processState(value.state));
        String cpuWindow = cpuIsAverage && cpuWindowMillis > 0
                ? "  ·  CPU 窗口 " + duration(cpuWindowMillis) : "";
        subtitle.setText(empty(value.processName) + "  ·  PID " + pid + cpuWindow + "  ·  本次读取 " + readMillis + " ms");
        runtimeText.setText("进程名：" + empty(value.processName) + "\n"
                + "命令行：" + empty(value.commandLine) + "\n"
                + "PID / PPID：" + pid + " / " + empty(value.ppid) + "  ·  Linux UID：" + value.uid + "\n"
                + "状态：" + empty(value.state) + "  ·  Android 重要级：" + importance(value.importance) + "\n"
                + "优先级 / nice：" + value.priority + " / " + value.nice + "  ·  最近运行核心：" + core(value.processor) + "\n"
                + "线程：" + empty(value.threads) + "  ·  文件描述符：" + empty(value.fdCount) + "  ·  OOM 调整：" + empty(value.oomScoreAdj) + "\n"
                + "主动 / 被动上下文切换：" + empty(value.voluntaryContextSwitches) + " / " + empty(value.involuntaryContextSwitches));
        memoryText.setText("总 PSS：" + kb(value.totalPssKb)
                + "  ·  私有脏页：" + kb(value.totalPrivateDirtyKb) + "  ·  共享脏页：" + kb(value.totalSharedDirtyKb) + "\n"
                + "虚拟内存：" + empty(value.vmSize) + "  ·  RSS：" + empty(value.vmRss) + "  ·  Swap：" + empty(value.vmSwap) + "\n"
                + "Java 堆：" + memoryStat(value.javaHeapKb) + "  ·  Native 堆：" + memoryStat(value.nativeHeapKb) + "\n"
                + "代码：" + memoryStat(value.codeKb) + "  ·  栈：" + memoryStat(value.stackKb) + "  ·  图形：" + memoryStat(value.graphicsKb));
        long rx = value.uid > 0 ? TrafficStats.getUidRxBytes(value.uid) : -1;
        long tx = value.uid > 0 ? TrafficStats.getUidTxBytes(value.uid) : -1;
        ioText.setText("磁盘读取：" + bytes(value.ioReadBytes) + "  ·  磁盘写入：" + bytes(value.ioWriteBytes) + "\n"
                + "UID 网络接收：" + bytes(rx) + "  ·  UID 网络发送：" + bytes(tx) + "\n"
                + "说明：网络为同一 UID 下所有进程累计值，磁盘 I/O 为当前 PID 累计值。");
    }

    private void renderStaticPackageInfo() {
        PackageManager pm = activity.getPackageManager();
        StringBuilder output = new StringBuilder("关联包：");
        if (packages.length == 0) output.append("未公开");
        for (String pkg : packages) output.append("\n• ").append(pkg);
        for (String pkg : packages) {
            try {
                ApplicationInfo app = pm.getApplicationInfo(pkg, 0);
                PackageInfo info = pm.getPackageInfo(pkg, 0);
                icon.setImageDrawable(pm.getApplicationIcon(app));
                title.setText(pm.getApplicationLabel(app));
                output.append("\n\n主包：").append(pkg)
                        .append("\n版本：").append(info.versionName == null ? "—" : info.versionName)
                        .append("  ·  versionCode ").append(info.getLongVersionCode())
                        .append("\n类型：").append((app.flags & ApplicationInfo.FLAG_SYSTEM) != 0 ? "系统应用" : "用户应用")
                        .append("  ·  targetSdk ").append(app.targetSdkVersion)
                        .append("\n首次安装：").append(date(info.firstInstallTime))
                        .append("\n最近更新：").append(date(info.lastUpdateTime))
                        .append("\nAPK：").append(app.sourceDir)
                        .append("\n数据目录：").append(app.dataDir);
                break;
            } catch (Exception ignored) { }
        }
        packageText.setText(output.toString());
    }

    private TextView section(LinearLayout parent, String heading) {
        LinearLayout card = vertical(); card.setPadding(dp(16), dp(13), dp(16), dp(14));
        card.setBackground(round(Color.rgb(248, 250, 252), BORDER, 12));
        card.addView(text(heading, 12, MUTED, true));
        TextView body = text("读取中…", 11, TEXT, false); body.setTypeface(Typeface.MONOSPACE);
        body.setTextIsSelectable(true); body.setPadding(0, dp(9), 0, 0); card.addView(body);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(10)); parent.addView(card, lp); return body;
    }

    private TextView chip(String label, String value) {
        TextView view = text(label + "\n" + value, 11, TEXT, true); view.setGravity(Gravity.CENTER);
        view.setLineSpacing(0, 1.12f); view.setBackground(round(Color.rgb(242, 247, 249), BORDER, 11)); return view;
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.setMargins(dp(4), 0, dp(4), 0); return lp;
    }

    private String processState(String value) {
        if (value == null || value.isEmpty()) return "未公开";
        if (value.startsWith("R")) return "运行";
        if (value.startsWith("S")) return "休眠";
        if (value.startsWith("D")) return "等待 I/O";
        if (value.startsWith("T")) return "暂停";
        if (value.startsWith("Z")) return "僵尸";
        return value;
    }

    private String importance(int value) {
        if (value <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) return "前台";
        if (value <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE) return "可见";
        if (value <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE) return "服务";
        if (value <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED) return "缓存";
        return "系统未公开";
    }

    private String core(int value) { return value < 0 ? "未公开" : "CPU " + value; }
    private String duration(long millis) {
        if (millis < 60_000) return Math.max(1, millis / 1000) + " 秒";
        return Math.max(1, millis / 60_000) + " 分钟";
    }
    private String empty(String value) { return value == null || value.isEmpty() ? "未公开" : value; }
    private String kb(int value) { return value <= 0 ? "未公开" : bytes(value * 1024L); }
    private String memoryStat(String value) {
        if (value == null || value.isEmpty() || "0".equals(value)) return "未公开";
        try { return bytes(Long.parseLong(value) * 1024L); } catch (Exception ignored) { return value; }
    }
    private String date(long value) { return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date(value)); }
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
}
