package com.kemi.mypad;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Draggable performance overlay attached only to the active secondary display. */
public final class SystemPerformanceOverlayService extends Service implements DisplayManager.DisplayListener {
    static final String ACTION_SHOW = "com.kemi.mypad.performance.SHOW";
    static final String ACTION_STOP = "com.kemi.mypad.performance.STOP";
    static final String PREFS = "my_pad";
    static final String PREF_ENABLED = "system_performance_overlay_enabled";
    private static final String PREF_X = "system_performance_overlay_x";
    private static final String PREF_Y = "system_performance_overlay_y";
    private static final int NOTIFICATION_ID = 7402;
    private static final String CHANNEL_ID = "kemi_performance_overlay";
    private static volatile boolean running;

    private DisplayManager displayManager;
    private HandlerThread sampleThread;
    private Handler sampleHandler;
    private Handler mainHandler;
    private SystemPerformanceSampler sampler;
    private WindowManager windowManager;
    private WindowManager.LayoutParams windowParams;
    private LinearLayout card;
    private TextView summary;
    private TextView coreGrid;
    private TextView memory;
    private LinearLayout detail;
    private Display attachedDisplay;
    private boolean expanded;
    private int detailPage;
    private List<SystemPerformanceSampler.Row> latestRows = new ArrayList<>();
    private boolean latestDetailReady;
    private float downRawX;
    private float downRawY;
    private int downWindowX;
    private int downWindowY;
    private boolean dragged;

    private static final class OverlayCard extends LinearLayout {
        OverlayCard(Context context) { super(context); }

        @Override public boolean performClick() {
            super.performClick();
            return true;
        }
    }

    private final Runnable sampleTask = new Runnable() {
        @Override public void run() {
            if (!running) return;
            if (card == null) {
                sampleHandler.postDelayed(this, SystemPerformanceSampler.SAMPLE_INTERVAL_MS);
                return;
            }
            long cycleStarted = SystemClock.elapsedRealtime();
            SystemPerformanceSampler.Snapshot snapshot = sampler.sample(expanded);
            mainHandler.post(() -> render(snapshot));
            long cost = SystemClock.elapsedRealtime() - cycleStarted;
            sampleHandler.postDelayed(this, Math.max(0L, SystemPerformanceSampler.SAMPLE_INTERVAL_MS - cost));
        }
    };

    static boolean isEnabled(Context context) {
        return context.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_ENABLED, false);
    }

    static boolean isRunning() { return running; }

    static void setEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_ENABLED, enabled).apply();
        Intent intent = new Intent(context, SystemPerformanceOverlayService.class)
                .setAction(enabled ? ACTION_SHOW : ACTION_STOP);
        if (enabled) context.startForegroundService(intent); else context.startService(intent);
    }

    static void restoreIfEnabled(Context context) {
        if (isEnabled(context)) {
            context.startForegroundService(new Intent(context, SystemPerformanceOverlayService.class).setAction(ACTION_SHOW));
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        running = true;
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        mainHandler = new Handler(getMainLooper());
        sampleThread = new HandlerThread("kemi-performance-sampler", android.os.Process.THREAD_PRIORITY_BACKGROUND);
        sampleThread.start();
        sampleHandler = new Handler(sampleThread.getLooper());
        sampler = new SystemPerformanceSampler(this);
        displayManager = getSystemService(DisplayManager.class);
        if (displayManager != null) displayManager.registerDisplayListener(this, mainHandler);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_ENABLED, false).apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_ENABLED, true).apply();
        attachToSecondaryDisplay();
        sampleHandler.removeCallbacks(sampleTask);
        sampleHandler.post(sampleTask);
        return START_STICKY;
    }

    @Override public void onDestroy() {
        running = false;
        if (displayManager != null) displayManager.unregisterDisplayListener(this);
        if (sampleHandler != null) sampleHandler.removeCallbacksAndMessages(null);
        if (sampler != null) sampler.reset();
        removeOverlay();
        if (sampleThread != null) sampleThread.quitSafely();
        stopForeground(true);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDisplayAdded(int displayId) { attachToSecondaryDisplay(); }
    @Override public void onDisplayRemoved(int displayId) {
        if (attachedDisplay != null && attachedDisplay.getDisplayId() == displayId) removeOverlay();
        attachToSecondaryDisplay();
    }
    @Override public void onDisplayChanged(int displayId) {
        if (attachedDisplay != null && attachedDisplay.getDisplayId() == displayId) removeOverlay();
        attachToSecondaryDisplay();
    }

    private void attachToSecondaryDisplay() {
        if (displayManager == null) return;
        Display target = null;
        for (Display display : displayManager.getDisplays()) {
            if (display.getDisplayId() == 2 && display.getState() != Display.STATE_OFF && display.isValid()) {
                target = display;
                break;
            }
        }
        if (target == null) {
            for (Display display : displayManager.getDisplays()) {
                if (display.getDisplayId() != Display.DEFAULT_DISPLAY
                        && display.getState() != Display.STATE_OFF && display.isValid()) {
                    target = display;
                    break;
                }
            }
        }
        if (target == null) {
            removeOverlay();
            return;
        }
        if (attachedDisplay != null && attachedDisplay.getDisplayId() == target.getDisplayId() && card != null) return;
        removeOverlay();
        attachedDisplay = target;
        Context displayContext = createDisplayContext(target);
        Context windowContext = displayContext.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
        windowManager = windowContext.getSystemService(WindowManager.class);
        card = buildCard(windowContext);
        android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
        target.getRealMetrics(metrics);
        // 500 physical pixels fits the two core columns on the fixed 1920-wide
        // D2 without turning the monitor into a side panel.
        int width = Math.min(500, Math.max(320, metrics.widthPixels - 24));
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        windowParams = new WindowManager.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        windowParams.gravity = Gravity.TOP | Gravity.START;
        int edge = 16;
        windowParams.x = clamp(prefs.getInt(PREF_X, 18), edge,
                Math.max(edge, metrics.widthPixels - width - edge));
        windowParams.y = clamp(prefs.getInt(PREF_Y, 18), edge,
                Math.max(edge, metrics.heightPixels - 120 - edge));
        try { windowManager.addView(card, windowParams); }
        catch (Exception error) {
            android.util.Log.e("KEMI_PERF_OVERLAY", "Unable to attach overlay to display " + target.getDisplayId(), error);
            removeOverlay();
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_ENABLED, false).apply();
            stopSelf();
        }
    }

    private LinearLayout buildCard(Context context) {
        LinearLayout root = new OverlayCard(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(context, 15), dp(context, 12), dp(context, 15), dp(context, 12));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.argb(205, 21, 25, 31));
        background.setCornerRadius(dp(context, 18));
        background.setStroke(dp(context, 1), Color.argb(72, 255, 255, 255));
        root.setBackground(background);
        root.setElevation(dp(context, 12));

        summary = label(context, "CPU 采集中…", 15, Color.WHITE);
        coreGrid = label(context, "CPU0 --    CPU1 --", 12, Color.rgb(220, 225, 232));
        coreGrid.setPadding(0, dp(context, 6), 0, dp(context, 5));
        memory = label(context, "内存读取中…", 12, Color.rgb(220, 225, 232));
        detail = new LinearLayout(context);
        detail.setOrientation(LinearLayout.VERTICAL);
        summary.setHorizontallyScrolling(true);
        coreGrid.setHorizontallyScrolling(true);
        memory.setHorizontallyScrolling(true);
        detail.setPadding(0, dp(context, 8), 0, 0);
        detail.setVisibility(View.GONE);
        root.addView(summary);
        root.addView(coreGrid);
        root.addView(memory);
        root.addView(detail);
        root.setOnClickListener(ignored -> cycleDetail());
        root.setOnTouchListener(this::onTouch);
        return root;
    }

    private boolean onTouch(View view, MotionEvent event) {
        if (windowManager == null || windowParams == null || attachedDisplay == null) return false;
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            downRawX = event.getRawX();
            downRawY = event.getRawY();
            downWindowX = windowParams.x;
            downWindowY = windowParams.y;
            dragged = false;
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            int dx = Math.round(event.getRawX() - downRawX);
            int dy = Math.round(event.getRawY() - downRawY);
            if (Math.abs(dx) + Math.abs(dy) > dp(view.getContext(), 6)) dragged = true;
            android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
            attachedDisplay.getRealMetrics(metrics);
            int edge = 16;
            windowParams.x = clamp(downWindowX + dx, edge,
                    Math.max(edge, metrics.widthPixels - windowParams.width - edge));
            windowParams.y = clamp(downWindowY + dy, edge,
                    Math.max(edge, metrics.heightPixels - view.getHeight() - edge));
            try { windowManager.updateViewLayout(card, windowParams); } catch (Exception ignored) { }
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            if (!dragged && event.getActionMasked() == MotionEvent.ACTION_UP) view.performClick();
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(PREF_X, windowParams.x).putInt(PREF_Y, windowParams.y).apply();
            return true;
        }
        return true;
    }

    private void cycleDetail() {
        if (!expanded) {
            expanded = true;
            detailPage = 0;
            detail.setVisibility(View.VISIBLE);
            showDetailMessage("进程明细采集中…");
            sampler.reset();
        } else {
            int pages = Math.max(1, (latestRows.size() + 17) / 18);
            if (detailPage + 1 < pages) detailPage++;
            else {
                expanded = false;
                detailPage = 0;
                detail.setVisibility(View.GONE);
                sampler.reset();
            }
        }
        if (sampleHandler != null) {
            sampleHandler.removeCallbacks(sampleTask);
            sampleHandler.post(sampleTask);
        }
    }

    private void render(SystemPerformanceSampler.Snapshot snapshot) {
        if (card == null) return;
        summary.setText("CPU " + percent(snapshot.totalCpuPercent) + "  ·  " + snapshot.cores.size() + " 核合计");
        StringBuilder cores = new StringBuilder();
        for (int i = 0; i < snapshot.cores.size(); i++) {
            SystemPerformanceSampler.Core core = snapshot.cores.get(i);
            if (i > 0) cores.append(i % 2 == 0 ? '\n' : "        ");
            cores.append("CPU").append(core.index).append(' ').append(percent(core.percent));
            cores.append(' ').append(core.currentFrequencyKhz > 0 ? ghz(core.currentFrequencyKhz) : "--");
        }
        coreGrid.setText(cores.length() == 0 ? "CPU 核心信息不可读" : cores.toString());
        long used = Math.max(0, snapshot.totalMemory - snapshot.availableMemory);
        String swap = snapshot.totalSwapKb > 0
                ? "  ·  SWAP " + gb((snapshot.totalSwapKb - snapshot.freeSwapKb) * 1024L) + "/" + gb(snapshot.totalSwapKb * 1024L)
                : "";
        memory.setText("MEM " + gb(used) + "/" + gb(snapshot.totalMemory) + swap);
        latestRows = snapshot.rows;
        latestDetailReady = snapshot.intervalMs > 0;
        if (expanded) renderDetails();
    }

    private void renderDetails() {
        if (latestRows.isEmpty()) {
            showDetailMessage((latestDetailReady ? "当前没有达到显示阈值的活跃进程" : "进程明细采集中…")
                    + "\n轻点收起 · 拖动调整位置");
            return;
        }
        int pages = Math.max(1, (latestRows.size() + 17) / 18);
        detailPage = Math.min(detailPage, pages - 1);
        int start = detailPage * 18;
        int end = Math.min(start + 18, latestRows.size());
        detail.removeAllViews();
        detail.addView(detailRow("进程 / App", "CPU", "MEM", true));
        for (int i = start; i < end; i++) {
            SystemPerformanceSampler.Row row = latestRows.get(i);
            detail.addView(detailRow(row.name, percent(row.cpuPercent), memoryKb(row.memoryKb), false));
        }
        StringBuilder footer = new StringBuilder().append(latestRows.size()).append(" 项 · ");
        if (pages > 1) footer.append(detailPage + 1).append('/').append(pages).append(" · 轻点翻页");
        else footer.append("轻点收起");
        footer.append(" · 拖动");
        TextView footerView = detailText(footer.toString(), Gravity.START, false);
        footerView.setPadding(0, dp(detail.getContext(), 5), 0, 0);
        detail.addView(footerView);
    }

    private void showDetailMessage(String message) {
        if (detail == null) return;
        detail.removeAllViews();
        detail.addView(detailText(message, Gravity.START, false));
    }

    /** Real view columns: CPU and MEM share the exact same X coordinate on every row. */
    private LinearLayout detailRow(String name, String cpu, String mem, boolean header) {
        Context context = detail.getContext();
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView nameView = detailText(name, Gravity.START, header);
        nameView.setSingleLine(true);
        nameView.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(nameView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        int cpuWidth = dp(context, 52);
        int memoryWidth = dp(context, 48);
        row.addView(detailText(cpu, Gravity.END, header),
                new LinearLayout.LayoutParams(cpuWidth, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(detailText(mem, Gravity.END, header),
                new LinearLayout.LayoutParams(memoryWidth, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private TextView detailText(String value, int gravity, boolean header) {
        TextView view = label(detail.getContext(), value, 11,
                header ? Color.rgb(225, 230, 237) : Color.rgb(211, 217, 225));
        view.setTypeface(Typeface.MONOSPACE, header ? Typeface.BOLD : Typeface.NORMAL);
        view.setGravity(gravity);
        view.setSingleLine(false);
        return view;
    }

    private void removeOverlay() {
        if (windowManager != null && card != null) {
            try { windowManager.removeViewImmediate(card); } catch (Exception ignored) { }
        }
        card = null;
        summary = null;
        coreGrid = null;
        memory = null;
        detail = null;
        windowManager = null;
        windowParams = null;
        attachedDisplay = null;
    }

    private void createNotificationChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "系统实时信息", NotificationManager.IMPORTANCE_MIN);
        channel.setDescription("第二屏系统性能悬浮窗");
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(com.kemi.mypad.R.drawable.ic_launcher)
                .setContentTitle("系统实时信息正在显示")
                .setContentText("仅在第二屏显示")
                .setContentIntent(pending)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    private static TextView label(Context context, String value, float size, int color) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        view.setIncludeFontPadding(false);
        view.setLineSpacing(0, 1.08f);
        return view;
    }

    private static String percent(double value) {
        return Double.isNaN(value) ? "--" : String.format(Locale.CHINA, "%.1f%%", value);
    }

    private static String ghz(long khz) { return String.format(Locale.CHINA, "%.2fG", khz / 1_000_000d); }
    private static String gb(long bytes) { return String.format(Locale.CHINA, "%.1fG", bytes / 1073741824d); }
    private static String memoryKb(long kb) {
        if (kb <= 0) return "--";
        if (kb >= 1048576) return String.format(Locale.CHINA, "%.1fG", kb / 1048576d);
        return String.format(Locale.CHINA, "%.0fM", kb / 1024d);
    }
    private static int dp(Context context, int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
}
