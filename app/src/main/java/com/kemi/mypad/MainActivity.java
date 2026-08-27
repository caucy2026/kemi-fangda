package com.kemi.mypad;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.app.usage.UsageEvents;
import android.animation.ObjectAnimator;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Debug;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.StatFs;
import android.os.SystemClock;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.Settings;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.RelativeSizeSpan;
import android.view.ActionMode;
import android.view.Display;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.webkit.MimeTypeMap;
import android.hardware.display.DisplayManager;
import android.util.DisplayMetrics;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class MainActivity extends Activity {
    private static final int BLUE = Color.rgb(22, 119, 255);
    private static final int TEAL = Color.rgb(38, 169, 156);
    private static final int TEXT = Color.rgb(31, 41, 51);
    private static final int MUTED = Color.rgb(104, 118, 129);
    private static final int BORDER = Color.rgb(218, 226, 231);
    private static final int SIDEBAR = Color.rgb(238, 243, 246);
    private static final int SURFACE = Color.rgb(255, 255, 255);
    private static final String PREFS = "my_pad";
    private static final String RECENT_KEY = "recent_paths";
    private static final String FAVORITES_KEY = "favorite_paths";
    private static final long INSTALLED_PHYSICAL_MEMORY = 6L * 1024 * 1024 * 1024;
    private static final long CONFIGURED_SWAP_MEMORY = 2L * 1024 * 1024 * 1024;

    private LinearLayout fileList;
    private LinearLayout headerView;
    private TextView titleView;
    private TextView breadcrumbView;
    private TextView footerLeft;
    private TextView footerRight;
    private View backButton;
    private Button forwardButton;
    private Button primaryToolbarButton;
    private Button sortToolbarButton;
    private Button refreshToolbarButton;
    private Button usbButton;
    private ProgressBar monitorProgress;
    private TextView monitorStatus;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, Long> cleanedPackages = new ConcurrentHashMap<>();
    private long monitorGeneration;
    private Boolean lastUsbAvailable;
    private String lastUsbPath = "";
    private boolean usbWatcherRunning;
    private boolean usbReceiverRegistered;
    private boolean storageCallbackRegistered;
    private boolean fileOperationReceiverRegistered;
    private File currentDirectory;
    private File previousDirectory;
    private File navigationRoot;
    private String currentLanBase = "";
    private String currentLanPath = "";
    private final Map<String, Button> sidebarButtons = new HashMap<>();
    private final Map<File, View> rowViews = new HashMap<>();
    private final Set<File> selectedFiles = new HashSet<>();
    private final List<File> pendingFiles = new ArrayList<>();
    private boolean pendingMove;
    private ActionMode selectionMode;
    private String currentSection = "下载";
    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            updateUsbState(true);
        }
    };
    private final BroadcastReceiver fileOperationReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            int completed = intent == null ? 0 : intent.getIntExtra(DestinationPickerActivity.EXTRA_COMPLETED, 0);
            refreshCurrent();
            footerRight.setText("文件操作完成 · " + completed + " 个项目已处理");
        }
    };
    private final StorageManager.StorageVolumeCallback storageVolumeCallback = new StorageManager.StorageVolumeCallback() {
        @Override public void onStateChanged(StorageVolume volume) {
            updateUsbState(true);
        }
    };
    private final Runnable usbWatcher = new Runnable() {
        @Override public void run() {
            if (!usbWatcherRunning) return;
            updateUsbState(true);
            handler.postDelayed(this, 800);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(248, 250, 252));
        getWindow().setNavigationBarColor(Color.rgb(248, 250, 252));
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        setContentView(buildUi());
        ensureStorageAccess();
        showDownloads();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateUsbState(false);
        if (fileList != null && "下载".equals(currentSection)
                && (currentDirectory == null || !currentDirectory.exists())) showDownloads();
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_MEDIA_MOUNTED);
        filter.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        filter.addAction(Intent.ACTION_MEDIA_EJECT);
        filter.addAction(Intent.ACTION_MEDIA_REMOVED);
        filter.addAction(Intent.ACTION_MEDIA_BAD_REMOVAL);
        filter.addAction(Intent.ACTION_MEDIA_CHECKING);
        filter.addAction(Intent.ACTION_MEDIA_UNMOUNTABLE);
        filter.addDataScheme("file");
        try {
            registerReceiver(usbReceiver, filter);
            usbReceiverRegistered = true;
        } catch (Exception ignored) { }
        try {
            registerReceiver(fileOperationReceiver, new IntentFilter(DestinationPickerActivity.ACTION_COMPLETED));
            fileOperationReceiverRegistered = true;
        } catch (Exception ignored) { }
        StorageManager storage = getSystemService(StorageManager.class);
        if (storage != null) try {
            storage.registerStorageVolumeCallback(getMainExecutor(), storageVolumeCallback);
            storageCallbackRegistered = true;
        } catch (Exception ignored) { }
        usbWatcherRunning = true;
        handler.removeCallbacks(usbWatcher);
        handler.post(usbWatcher);
    }

    @Override
    protected void onStop() {
        usbWatcherRunning = false;
        handler.removeCallbacks(usbWatcher);
        if (usbReceiverRegistered) {
            try { unregisterReceiver(usbReceiver); } catch (Exception ignored) { }
            usbReceiverRegistered = false;
        }
        if (storageCallbackRegistered) {
            StorageManager storage = getSystemService(StorageManager.class);
            if (storage != null) try { storage.unregisterStorageVolumeCallback(storageVolumeCallback); } catch (Exception ignored) { }
            storageCallbackRegistered = false;
        }
        if (fileOperationReceiverRegistered) {
            try { unregisterReceiver(fileOperationReceiver); } catch (Exception ignored) { }
            fileOperationReceiverRegistered = false;
        }
        super.onStop();
    }

    private View buildUi() {
        LinearLayout root = vertical(SURFACE);
        root.addView(buildTopBar(), lpMatch(dp(68)));

        LinearLayout body = horizontal(SURFACE);
        body.addView(buildSidebar(), new LinearLayout.LayoutParams(dp(220), ViewGroup.LayoutParams.MATCH_PARENT));
        body.addView(buildMainPane(), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(buildFooter(), lpMatch(dp(42)));
        return root;
    }

    private View buildTopBar() {
        LinearLayout bar = horizontal(Color.rgb(250, 252, 253));
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), 0, dp(16), 0);
        bar.setDividerDrawable(lineDrawable());
        bar.setShowDividers(LinearLayout.SHOW_DIVIDER_END);

        LinearLayout brand = horizontal(Color.TRANSPARENT);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text("KEMI Pads", 18, TEXT, true);
        brand.addView(name);
        bar.addView(brand, new LinearLayout.LayoutParams(dp(220), ViewGroup.LayoutParams.MATCH_PARENT));

        backButton = navigationLabel("返回", TEXT, true);
        backButton.setBackground(ripple(Color.rgb(237, 242, 245), 12));
        backButton.setContentDescription("返回上一级");
        backButton.setOnClickListener(v -> navigateBack());
        bar.addView(backButton, new LinearLayout.LayoutParams(dp(88), dp(46)));
        forwardButton = iconButton("›");
        LinearLayout.LayoutParams fwdLp = new LinearLayout.LayoutParams(dp(46), dp(46));
        fwdLp.leftMargin = dp(8);
        bar.addView(forwardButton, fwdLp);

        breadcrumbView = text("本机存储  ›  下载", 14, TEXT, false);
        breadcrumbView.setGravity(Gravity.CENTER_VERTICAL);
        breadcrumbView.setPadding(dp(16), 0, dp(16), 0);
        breadcrumbView.setBackground(roundStroke(Color.rgb(252, 253, 254), BORDER, 12));
        LinearLayout.LayoutParams pathLp = new LinearLayout.LayoutParams(0, dp(46), 1);
        pathLp.leftMargin = dp(12);
        pathLp.rightMargin = dp(12);
        bar.addView(breadcrumbView, pathLp);

        Button search = iconButton("⌕");
        search.setContentDescription("搜索");
        search.setOnClickListener(v -> showSearchDialog());
        bar.addView(search, new LinearLayout.LayoutParams(dp(46), dp(46)));
        return bar;
    }

    private View buildSidebar() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(SIDEBAR);
        LinearLayout side = vertical(SIDEBAR);
        side.setPadding(dp(10), dp(16), dp(10), dp(16));

        side.addView(sideLabel("快捷入口"));
        side.addView(sideButton("最近使用", "◷", () -> showRecent()));
        side.addView(sideButton("下载", "⇩", () -> showDownloads()));
        side.addView(sideButton("收藏", "★", () -> showFavorites()));

        side.addView(sideLabel("位置"));
        side.addView(sideButton("本机文件", "▰", () -> showRootDirectory(Environment.getExternalStorageDirectory(), "本机文件")));
        usbButton = sideButton("USB 移动盘", "▱", this::showUsb);
        side.addView(usbButton);
        side.addView(sideButton("局域网文件", "☁", this::showLanFiles));

        side.addView(sideLabel("系统原有功能"));
        side.addView(sideButton("双屏管理", "▣", this::showDualScreenManager));
        side.addView(sideButton("全部应用", "▦", this::showInstalledApps));
        side.addView(sideButton("系统设置", "⚙", this::openSystemSettings));
        side.addView(sideButton("清理后台", "◌", this::showActivityMonitor));
        side.addView(sideButton("文件分发", "⌁", this::showFileDistribution));
        scroll.addView(side);
        updateUsbState(false);
        return scroll;
    }

    private View buildMainPane() {
        LinearLayout main = vertical(SURFACE);
        main.addView(buildToolbar(), lpMatch(dp(58)));
        headerView = buildHeader();
        main.addView(headerView, lpMatch(dp(36)));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        fileList = vertical(SURFACE);
        fileList.setPadding(dp(8), dp(6), dp(8), dp(6));
        scroll.addView(fileList);
        main.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return main;
    }

    private View buildToolbar() {
        LinearLayout toolbar = horizontal(SURFACE);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(14), 0, dp(14), 0);
        titleView = text("下载", 20, TEXT, true);
        toolbar.addView(titleView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        primaryToolbarButton = toolbarButton("＋");
        primaryToolbarButton.setContentDescription("新建文件夹");
        primaryToolbarButton.setOnClickListener(v -> createFolderDialog());
        toolbar.addView(primaryToolbarButton, new LinearLayout.LayoutParams(dp(44), dp(44)));
        sortToolbarButton = toolbarButton("↕");
        sortToolbarButton.setContentDescription("排序");
        sortToolbarButton.setOnClickListener(v -> sortMenu(sortToolbarButton));
        LinearLayout.LayoutParams buttonLp = new LinearLayout.LayoutParams(dp(44), dp(44));
        buttonLp.leftMargin = dp(8);
        toolbar.addView(sortToolbarButton, buttonLp);
        refreshToolbarButton = toolbarButton("↻");
        refreshToolbarButton.setContentDescription("刷新");
        refreshToolbarButton.setOnClickListener(v -> refreshCurrent());
        LinearLayout.LayoutParams refreshLp = new LinearLayout.LayoutParams(dp(44), dp(44));
        refreshLp.leftMargin = dp(8);
        toolbar.addView(refreshToolbarButton, refreshLp);
        return toolbar;
    }

    private LinearLayout buildHeader() {
        LinearLayout header = horizontal(Color.rgb(252, 253, 254));
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), 0, dp(16), 0);
        header.addView(text("名称", 11, MUTED, false), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        header.addView(text("修改时间", 11, MUTED, false), new LinearLayout.LayoutParams(dp(130), ViewGroup.LayoutParams.WRAP_CONTENT));
        header.addView(text("大小", 11, MUTED, false), new LinearLayout.LayoutParams(dp(100), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView action = text("分享", 11, MUTED, false);
        action.setGravity(Gravity.CENTER);
        header.addView(action, new LinearLayout.LayoutParams(dp(92), ViewGroup.LayoutParams.WRAP_CONTENT));
        return header;
    }

    private View buildFooter() {
        LinearLayout footer = horizontal(Color.rgb(248, 250, 252));
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setPadding(dp(16), 0, dp(16), 0);
        footerLeft = text("0 个项目", 11, MUTED, false);
        footerRight = text("点文件直接打开 · 长按多选", 11, MUTED, false);
        footer.addView(footerLeft, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        footer.addView(footerRight);
        return footer;
    }

    private View sideLabel(String value) {
        TextView label = text(value, 11, Color.rgb(123, 136, 146), true);
        label.setGravity(Gravity.BOTTOM);
        label.setPadding(dp(11), dp(13), 0, dp(7));
        return label;
    }

    private Button sideButton(String label, String symbol, Runnable action) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(sideText(symbol, label));
        button.setTextSize(14);
        button.setTextColor(TEXT);
        button.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        button.setPadding(dp(12), 0, dp(8), 0);
        button.setBackground(ripple(Color.TRANSPARENT, 10));
        button.setOnClickListener(v -> {
            if (selectionMode != null) selectionMode.finish();
            action.run();
        });
        sidebarButtons.put(label, button);
        return button;
    }

    private CharSequence sideText(String symbol, String label) {
        SpannableString value = new SpannableString(symbol + "   " + label);
        value.setSpan(new RelativeSizeSpan(1.55f), 0, symbol.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        return value;
    }

    private void setActiveSection(String label) {
        if (!label.equals(currentSection)) monitorGeneration++;
        currentSection = label;
        for (Map.Entry<String, Button> entry : sidebarButtons.entrySet()) {
            boolean active = label.equals(entry.getKey());
            entry.getValue().setTextColor(active ? Color.rgb(9, 109, 101) : TEXT);
            entry.getValue().setTypeface(Typeface.DEFAULT, active ? Typeface.BOLD : Typeface.NORMAL);
            entry.getValue().setBackground(ripple(active ? Color.rgb(204, 236, 232) : Color.TRANSPARENT, 10));
        }
        if (Boolean.TRUE.equals(lastUsbAvailable) && usbButton != null && !"USB 移动盘".equals(label)) {
            usbButton.setTextColor(Color.rgb(9, 109, 101));
            usbButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            usbButton.setBackground(ripple(Color.rgb(224, 243, 240), 10));
        }
        titleView.setText(label);
    }

    private void useFileToolbar() {
        primaryToolbarButton.setVisibility(View.VISIBLE);
        boolean canPaste = !pendingFiles.isEmpty() && currentDirectory != null && currentDirectory.canWrite();
        primaryToolbarButton.setText(canPaste ? (pendingMove ? "移动到这里" : "复制到这里") : "＋");
        primaryToolbarButton.setContentDescription(canPaste ? "粘贴到当前文件夹" : "新建文件夹");
        primaryToolbarButton.setOnClickListener(v -> { if (canPaste) pastePendingFiles(); else createFolderDialog(); });
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) primaryToolbarButton.getLayoutParams();
        lp.width = dp(canPaste ? 132 : 44);
        primaryToolbarButton.setLayoutParams(lp);
        sortToolbarButton.setVisibility(View.VISIBLE);
        refreshToolbarButton.setVisibility(View.VISIBLE);
        refreshToolbarButton.setOnClickListener(v -> refreshCurrent());
        setFileHeader();
    }

    private void useSectionToolbar(String actionText, Runnable action, boolean showRefresh) {
        sortToolbarButton.setVisibility(View.GONE);
        refreshToolbarButton.setVisibility(showRefresh ? View.VISIBLE : View.GONE);
        primaryToolbarButton.setVisibility(actionText == null ? View.GONE : View.VISIBLE);
        if (actionText != null) {
            primaryToolbarButton.setText(actionText);
            primaryToolbarButton.setContentDescription(actionText);
            primaryToolbarButton.setOnClickListener(v -> action.run());
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) primaryToolbarButton.getLayoutParams();
            lp.width = dp(Math.max(92, actionText.length() * 18 + 30));
            primaryToolbarButton.setLayoutParams(lp);
        }
    }

    private void setHeader(String first, String second, String third, String fourth) {
        headerView.setVisibility(View.VISIBLE);
        headerView.removeAllViews();
        headerView.addView(text(first, 11, MUTED, false), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        headerView.addView(text(second, 11, MUTED, false), new LinearLayout.LayoutParams(dp(130), ViewGroup.LayoutParams.WRAP_CONTENT));
        headerView.addView(text(third, 11, MUTED, false), new LinearLayout.LayoutParams(dp(100), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView action = text(fourth, 11, MUTED, false);
        action.setGravity(Gravity.CENTER);
        headerView.addView(action, new LinearLayout.LayoutParams(dp(92), ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void setFileHeader() {
        headerView.setVisibility(View.VISIBLE);
        headerView.removeAllViews();
        headerView.addView(text("名称", 11, MUTED, false), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        headerView.addView(text("修改时间", 11, MUTED, false), new LinearLayout.LayoutParams(dp(130), ViewGroup.LayoutParams.WRAP_CONTENT));
        headerView.addView(text("大小", 11, MUTED, false), new LinearLayout.LayoutParams(dp(100), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView direct = text("分享", 11, MUTED, false); direct.setGravity(Gravity.CENTER);
        headerView.addView(direct, new LinearLayout.LayoutParams(dp(74), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView options = text("•••", 11, MUTED, false); options.setGravity(Gravity.CENTER);
        headerView.addView(options, new LinearLayout.LayoutParams(dp(62), ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void showDownloads() {
        File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        navigationRoot = downloads;
        showDirectory(downloads, "下载");
    }

    private void showRootDirectory(File directory, String label) {
        navigationRoot = directory;
        showDirectory(directory, label);
    }

    private void showDirectory(File directory, String label) {
        if (directory == null) {
            message("未找到该位置");
            return;
        }
        previousDirectory = currentDirectory;
        currentDirectory = directory;
        setActiveSection(label);
        useFileToolbar();
        breadcrumbView.setText(displayPath(directory, label));
        renderFiles(filesIn(directory));
        if ("USB 移动盘".equals(label)) updateUsbFooter(directory);
        updateNavigationButtons();
    }

    private void showRecent() {
        setActiveSection("最近使用");
        currentDirectory = null;
        navigationRoot = null;
        useFileToolbar();
        breadcrumbView.setText("最近使用");
        List<File> recent = new ArrayList<>();
        try {
            JSONArray items = new JSONArray(getPreferences(MODE_PRIVATE).getString(RECENT_KEY, "[]"));
            for (int i = 0; i < items.length(); i++) {
                File file = new File(items.getString(i));
                if (file.exists()) recent.add(file);
            }
        } catch (Exception ignored) { }
        renderFiles(recent);
        updateNavigationButtons();
    }

    private void showFavorites() {
        setActiveSection("收藏");
        currentDirectory = null;
        navigationRoot = null;
        useFileToolbar();
        breadcrumbView.setText("收藏");
        List<File> favorites = new ArrayList<>();
        File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (downloads.exists()) favorites.add(downloads);
        try {
            JSONArray saved = new JSONArray(getPreferences(MODE_PRIVATE).getString(FAVORITES_KEY, "[]"));
            for (int i = 0; i < saved.length(); i++) {
                File file = new File(saved.getString(i));
                if (file.exists() && !favorites.contains(file)) favorites.add(file);
            }
        } catch (Exception ignored) { }
        renderFiles(favorites);
        updateNavigationButtons();
    }

    private void showUsb() {
        File directory = findUsbVolume();
        if (directory != null) showRootDirectory(directory, "USB 移动盘");
    }

    private File findUsbVolume() {
        StorageManager manager = getSystemService(StorageManager.class);
        if (manager == null) return null;
        for (StorageVolume volume : manager.getStorageVolumes()) {
            File directory = volume.getDirectory();
            String state = volume.getState();
            boolean mounted = Environment.MEDIA_MOUNTED.equals(state) || Environment.MEDIA_MOUNTED_READ_ONLY.equals(state);
            if (!volume.isPrimary() && mounted && directory != null && directory.exists() && directory.canRead()) return directory;
        }
        return null;
    }

    private void updateUsbState(boolean notifyChange) {
        if (usbButton == null) return;
        File usb = findUsbVolume();
        boolean available = usb != null;
        Boolean previous = lastUsbAvailable;
        String previousPath = lastUsbPath;
        lastUsbAvailable = available;
        lastUsbPath = available ? usb.getAbsolutePath() : "";
        usbButton.setEnabled(available);
        usbButton.setAlpha(available ? 1f : 0.42f);
        String usbLabel = "USB 移动盘";
        if (available) {
            try {
                StatFs stat = new StatFs(usb.getAbsolutePath());
                usbButton.setContentDescription("USB 移动盘已连接，" + formatBytes(stat.getAvailableBytes()) + " 可用");
            } catch (Exception ignored) { }
        } else {
            usbButton.setContentDescription("USB 移动盘未连接");
        }
        usbButton.setText(sideText("▱", usbLabel));
        if (available) {
            usbButton.setTextColor(Color.rgb(9, 109, 101));
            usbButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            usbButton.setBackground(ripple("USB 移动盘".equals(currentSection)
                    ? Color.rgb(204, 236, 232) : Color.rgb(224, 243, 240), 10));
        } else {
            usbButton.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
        }
        if (available && "USB 移动盘".equals(currentSection)) {
            boolean currentOnUsb = currentDirectory != null && currentDirectory.getAbsolutePath().startsWith(usb.getAbsolutePath());
            if (!currentOnUsb || !previousPath.equals(lastUsbPath)) showRootDirectory(usb, "USB 移动盘");
            else updateUsbFooter(usb);
        }
        if (!notifyChange || previous == null || (previous == available && previousPath.equals(lastUsbPath))) return;
        if (available) {
            footerRight.setText("USB 已连接 · 可直接浏览、打开和分享");
        } else {
            if ("USB 移动盘".equals(currentSection)) showDownloads();
            footerRight.setText("USB 已安全移除");
        }
    }

    private void updateUsbFooter(File usb) {
        try {
            StatFs stat = new StatFs(usb.getAbsolutePath());
            footerLeft.setText(filesIn(currentDirectory).size() + " 个项目");
            footerRight.setText("USB 已连接 · " + formatBytes(stat.getAvailableBytes()) + " 可用 / " + formatBytes(stat.getTotalBytes()));
        } catch (Exception ignored) { }
    }

    private List<File> filesIn(File directory) {
        File[] children = directory != null ? directory.listFiles() : null;
        if (children == null) return new ArrayList<>();
        List<File> result = new ArrayList<>(Arrays.asList(children));
        result.sort((a, b) -> {
            if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
            return Long.compare(b.lastModified(), a.lastModified());
        });
        return result;
    }

    private void renderFiles(List<File> files) {
        fileList.removeAllViews();
        rowViews.clear();
        selectedFiles.clear();
        if (canNavigateToParent()) fileList.addView(parentDirectoryRow(), lpMatch(dp(58)));
        if (files.isEmpty()) {
            TextView empty = text("这里还没有文件", 16, MUTED, false);
            empty.setGravity(Gravity.CENTER);
            fileList.addView(empty, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(180)));
        } else {
            for (File file : files) fileList.addView(fileRow(file), lpMatch(file.isDirectory() ? dp(60) : dp(64)));
        }
        footerLeft.setText(files.size() + " 个项目");
        footerRight.setText("点文件直接打开 · 长按多选");
    }

    private View fileRow(File file) {
        LinearLayout row = horizontal(Color.TRANSPARENT);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(4), dp(8), dp(4));
        row.setBackground(ripple(Color.TRANSPARENT, 10));

        LinearLayout nameGroup = horizontal(Color.TRANSPARENT);
        nameGroup.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        icon.setPadding(dp(2), dp(2), dp(2), dp(2));
        icon.setImageDrawable(new FinderIconDrawable(file.isDirectory()
                ? FinderIconDrawable.Kind.FOLDER : FinderIconDrawable.kindFor(mimeFor(file))));
        nameGroup.addView(icon, new LinearLayout.LayoutParams(dp(44), dp(42)));
        LinearLayout copy = vertical(Color.TRANSPARENT);
        copy.setGravity(Gravity.CENTER_VERTICAL);
        TextView fileName = text(file.getName(), 14, TEXT, true);
        copy.addView(fileName);
        if (!file.isDirectory()) {
            TextView type = text(mimeFor(file), 10, MUTED, false);
            type.setSingleLine(true);
            type.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams typeLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            typeLp.topMargin = dp(3);
            copy.addView(type, typeLp);
        }
        LinearLayout.LayoutParams copyLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        copyLp.leftMargin = dp(11);
        nameGroup.addView(copy, copyLp);
        row.addView(nameGroup, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));

        DateFormat dateFormat = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);
        row.addView(text(dateFormat.format(file.lastModified()), 12, MUTED, false), new LinearLayout.LayoutParams(dp(130), ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(text(file.isDirectory() ? "文件夹" : formatBytes(file.length()), 12, MUTED, false), new LinearLayout.LayoutParams(dp(100), ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView share = centerText(file.isDirectory() ? "进入" : "分享", 11, BLUE, true);
        share.setContentDescription(file.isDirectory() ? "进入文件夹" : "分享文件");
        share.setBackground(ripple(Color.rgb(242, 247, 252), 8));
        share.setOnClickListener(v -> {
            if (file.isDirectory()) browseInto(file);
            else shareFile(file);
        });
        LinearLayout.LayoutParams shareLp = new LinearLayout.LayoutParams(dp(64), dp(34));
        shareLp.setMargins(dp(5), 0, dp(5), 0);
        row.addView(share, shareLp);

        TextView options = centerText("•••", 16, TEXT, true);
        options.setContentDescription("更多文件操作");
        options.setBackground(ripple(Color.TRANSPARENT, 12));
        options.setOnClickListener(v -> showFileOperations(options, file));
        LinearLayout.LayoutParams optionsLp = new LinearLayout.LayoutParams(dp(62), dp(42));
        row.addView(options, optionsLp);

        row.setOnClickListener(v -> {
            if (selectionMode != null) toggleSelection(file);
            else if (file.isDirectory()) browseInto(file);
            else openFile(file);
        });
        row.setOnLongClickListener(v -> {
            if (selectionMode == null) startSelectionMode();
            toggleSelection(file);
            return true;
        });
        rowViews.put(file, row);
        return row;
    }

    private void browseInto(File directory) {
        if (navigationRoot == null) navigationRoot = directory;
        showDirectory(directory, currentSection);
    }

    private void navigateBack() {
        if ("局域网文件".equals(currentSection) && !currentLanPath.isEmpty()) {
            String parent = new File(currentLanPath).getParent();
            loadLanPath(currentLanBase, parent == null ? "" : parent);
        } else if (canNavigateToParent()) {
            showDirectory(currentDirectory.getParentFile(), currentSection);
        }
    }

    @Override public void onBackPressed() {
        if (canNavigateToParent() || ("局域网文件".equals(currentSection) && !currentLanPath.isEmpty())) {
            navigateBack();
        } else {
            super.onBackPressed();
        }
    }

    private boolean canNavigateToParent() {
        if (currentDirectory == null || currentDirectory.getParentFile() == null || navigationRoot == null) return false;
        try {
            String current = currentDirectory.getCanonicalPath();
            String root = navigationRoot.getCanonicalPath();
            return !current.equals(root) && current.startsWith(root + File.separator);
        } catch (Exception ignored) {
            return false;
        }
    }

    private View parentDirectoryRow() {
        LinearLayout row = horizontal(Color.rgb(248, 251, 252));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), 0, dp(12), 0);
        row.setBackground(ripple(Color.rgb(248, 251, 252), 10));
        View label = navigationLabel("返回上一级", BLUE, false);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        row.addView(label, labelLp);
        TextView currentPath = text(displayPath(currentDirectory, currentSection), 12, MUTED, false);
        currentPath.setSingleLine(true);
        currentPath.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        row.addView(currentPath, new LinearLayout.LayoutParams(dp(430), ViewGroup.LayoutParams.MATCH_PARENT));
        row.setOnClickListener(v -> navigateBack());
        return row;
    }

    private void updateNavigationButtons() {
        backButton.setEnabled(canNavigateToParent()
                || ("局域网文件".equals(currentSection) && !currentLanPath.isEmpty()));
        backButton.setAlpha(backButton.isEnabled() ? 1f : 0.36f);
        forwardButton.setEnabled(previousDirectory != null && previousDirectory.exists());
        forwardButton.setOnClickListener(v -> {
            File target = previousDirectory;
            if (target != null) showDirectory(target, currentSection);
        });
    }

    private void openFile(File file) {
        rememberRecent(file);
        Uri uri = SharedFileProvider.uriFor(file);
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mimeFor(file))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException error) {
            message("系统中没有可打开此文件的软件");
        }
    }

    private void shareFile(File file) {
        Uri uri = SharedFileProvider.uriFor(file);
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType(mimeFor(file))
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(send, "分享“" + file.getName() + "”"));
    }

    private void showFileOperations(View anchor, File file) {
        LinearLayout panel = vertical(Color.TRANSPARENT);
        panel.setPadding(dp(6), dp(6), dp(6), dp(6));
        int menuWidth = dp(205);
        int menuHeight = dp(190);
        PopupWindow popup = new PopupWindow(panel, menuWidth, menuHeight, true);
        panel.addView(macMenuRow(MacActionIconDrawable.Kind.COPY, "复制到…", true, () -> {
            popup.dismiss(); launchDestinationPicker(Collections.singletonList(file), false);
        }), lpMatch(dp(42)));
        panel.addView(macMenuRow(MacActionIconDrawable.Kind.CUT, "剪切到…", true, () -> {
            popup.dismiss(); launchDestinationPicker(Collections.singletonList(file), true);
        }), lpMatch(dp(42)));
        View separator = new View(this); separator.setBackgroundColor(Color.rgb(229, 232, 236));
        LinearLayout.LayoutParams sepLp = lpMatch(dp(1)); sepLp.setMargins(dp(8), dp(5), dp(8), dp(5)); panel.addView(separator, sepLp);
        boolean deletable = FileOperations.canDelete(this, file);
        panel.addView(macMenuRow(MacActionIconDrawable.Kind.DELETE, "删除", deletable, () -> {
            popup.dismiss(); confirmDeleteFile(file);
        }), lpMatch(dp(42)));
        panel.addView(macMenuRow(MacActionIconDrawable.Kind.REFRESH, "刷新", true, () -> {
            popup.dismiss(); refreshCurrent();
        }), lpMatch(dp(42)));
        popup.setBackgroundDrawable(roundStroke(Color.WHITE, Color.rgb(205, 210, 216), 10));
        popup.setOutsideTouchable(true);
        popup.setElevation(dp(12));
        int[] location = new int[2]; anchor.getLocationOnScreen(location);
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int x = Math.max(dp(8), Math.min(location[0] + anchor.getWidth() - menuWidth, metrics.widthPixels - menuWidth - dp(8)));
        int below = location[1] + anchor.getHeight() + dp(4);
        int above = location[1] - menuHeight - dp(4);
        int y = below + menuHeight <= metrics.heightPixels - dp(16) ? below : Math.max(dp(16), above);
        popup.showAtLocation(anchor, Gravity.TOP | Gravity.START, x, y);
    }

    private View macMenuRow(MacActionIconDrawable.Kind iconKind, String title, boolean enabled, Runnable action) {
        LinearLayout row = horizontal(Color.TRANSPARENT);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), 0, dp(8), 0);
        row.setEnabled(enabled); row.setAlpha(enabled ? 1f : .38f);
        row.setBackground(ripple(Color.TRANSPARENT, 7));
        ImageView icon = new ImageView(this);
        icon.setPadding(dp(4), dp(4), dp(4), dp(4));
        icon.setImageDrawable(new MacActionIconDrawable(iconKind));
        row.addView(icon, new LinearLayout.LayoutParams(dp(29), dp(29)));
        TextView label = text(title, 13, TEXT, false); label.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1); labelLp.leftMargin = dp(8); row.addView(label, labelLp);
        if (title.endsWith("…")) row.addView(centerText("›", 20, MUTED, false), new LinearLayout.LayoutParams(dp(20), ViewGroup.LayoutParams.MATCH_PARENT));
        if (enabled) row.setOnClickListener(v -> action.run());
        return row;
    }

    private void launchDestinationPicker(List<File> files, boolean move) {
        ArrayList<String> paths = new ArrayList<>();
        for (File file : files) if (file != null && file.exists()) paths.add(file.getAbsolutePath());
        if (paths.isEmpty()) return;
        if (selectionMode != null) selectionMode.finish();
        Intent intent = new Intent(this, DestinationPickerActivity.class)
                .putStringArrayListExtra(DestinationPickerActivity.EXTRA_PATHS, paths)
                .putExtra(DestinationPickerActivity.EXTRA_MOVE, move)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        int displayId = externalDisplayId();
        try {
            if (displayId >= 0) {
                ActivityOptions options = ActivityOptions.makeBasic();
                options.setLaunchDisplayId(displayId);
                startActivity(intent, options.toBundle());
            } else {
                startActivity(intent);
            }
            footerRight.setText("请在副屏选择目标文件夹");
        } catch (Exception error) {
            startActivity(intent);
        }
    }

    private void confirmDeleteFile(File file) {
        if (!FileOperations.canDelete(this, file)) return;
        String detail = file.isDirectory() ? "文件夹及其全部内容将递归删除。" : "文件删除后无法恢复。";
        new AlertDialog.Builder(this)
                .setTitle("确认删除“" + file.getName() + "”？")
                .setMessage(detail + "\n\n仅下载目录和外接 U 盘文件允许删除。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> new Thread(() -> {
                    boolean deleted = FileOperations.deleteRecursively(file);
                    runOnUiThread(() -> {
                        refreshCurrent();
                        if (!deleted) message("删除失败，请检查文件是否被占用");
                    });
                }, "kemi-pads-delete").start())
                .show();
    }

    private void shareSelected() {
        ArrayList<Uri> uris = new ArrayList<>();
        for (File file : selectedFiles) if (file.isFile()) uris.add(SharedFileProvider.uriFor(file));
        if (uris.isEmpty()) return;
        Intent send = new Intent(Intent.ACTION_SEND_MULTIPLE)
                .setType("*/*")
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(send, "分享所选文件"));
    }

    private void rememberRecent(File file) {
        try {
            JSONArray old = new JSONArray(getPreferences(MODE_PRIVATE).getString(RECENT_KEY, "[]"));
            JSONArray next = new JSONArray();
            next.put(file.getAbsolutePath());
            for (int i = 0; i < old.length() && next.length() < 20; i++) {
                String path = old.getString(i);
                if (!path.equals(file.getAbsolutePath())) next.put(path);
            }
            getPreferences(MODE_PRIVATE).edit().putString(RECENT_KEY, next.toString()).apply();
        } catch (Exception ignored) { }
    }

    private void startSelectionMode() {
        selectionMode = startActionMode(new ActionMode.Callback() {
            @Override public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                menu.add(0, 1, 0, "分享").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
                menu.add(0, 2, 1, "复制到").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
                menu.add(0, 3, 2, "剪切到").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
                menu.add(0, 4, 3, "收藏").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
                menu.add(0, 5, 4, "重命名").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
                menu.add(0, 6, 5, "详情").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
                menu.add(0, 7, 6, "删除").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
                return true;
            }
            @Override public boolean onPrepareActionMode(ActionMode mode, Menu menu) { return false; }
            @Override public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                if (item.getItemId() == 1) shareSelected();
                else if (item.getItemId() == 2) launchDestinationPicker(new ArrayList<>(selectedFiles), false);
                else if (item.getItemId() == 3) launchDestinationPicker(new ArrayList<>(selectedFiles), true);
                else if (item.getItemId() == 4) favoriteSelected();
                else if (item.getItemId() == 5) renameSelected();
                else if (item.getItemId() == 6) showSelectedDetails();
                else if (item.getItemId() == 7) confirmDeleteSelected();
                return true;
            }
            @Override public void onDestroyActionMode(ActionMode mode) {
                selectedFiles.clear();
                for (View row : rowViews.values()) row.setBackground(ripple(Color.TRANSPARENT, 10));
                selectionMode = null;
            }
        }, ActionMode.TYPE_PRIMARY);
    }

    private void toggleSelection(File file) {
        if (selectedFiles.contains(file)) selectedFiles.remove(file); else selectedFiles.add(file);
        View row = rowViews.get(file);
        if (row != null) row.setBackground(ripple(selectedFiles.contains(file) ? Color.rgb(216, 240, 237) : Color.TRANSPARENT, 10));
        if (selectionMode != null) {
            selectionMode.setTitle("已选择 " + selectedFiles.size() + " 项");
            MenuItem delete = selectionMode.getMenu().findItem(7);
            if (delete != null) {
                boolean deletable = !selectedFiles.isEmpty();
                for (File selected : selectedFiles) if (!FileOperations.canDelete(this, selected)) { deletable = false; break; }
                delete.setEnabled(deletable);
            }
            if (selectedFiles.isEmpty()) selectionMode.finish();
        }
    }

    private void stageSelected(boolean move) {
        pendingFiles.clear();
        pendingFiles.addAll(selectedFiles);
        pendingMove = move;
        if (selectionMode != null) selectionMode.finish();
        useFileToolbar();
        footerRight.setText("已选择 " + pendingFiles.size() + " 项 · 进入目标文件夹后点“" + (move ? "移动到这里" : "复制到这里") + "”");
    }

    private void pastePendingFiles() {
        if (pendingFiles.isEmpty() || currentDirectory == null || !currentDirectory.canWrite()) return;
        File destination = currentDirectory;
        List<File> sources = new ArrayList<>(pendingFiles);
        boolean move = pendingMove;
        primaryToolbarButton.setEnabled(false);
        primaryToolbarButton.setText(move ? "正在移动…" : "正在复制…");
        new Thread(() -> {
            int completed = 0;
            for (File source : sources) try {
                String sourcePath = source.getCanonicalPath();
                String destinationPath = destination.getCanonicalPath();
                if (source.isDirectory() && destinationPath.startsWith(sourcePath + File.separator)) continue;
                File target = uniqueDestination(destination, source.getName());
                boolean ok;
                if (move && source.renameTo(target)) ok = true;
                else {
                    ok = copyRecursively(source, target);
                    if (ok && move) ok = deleteRecursively(source);
                }
                if (ok) completed++;
            } catch (Exception ignored) { }
            int result = completed;
            runOnUiThread(() -> {
                pendingFiles.clear();
                pendingMove = false;
                primaryToolbarButton.setEnabled(true);
                refreshCurrent();
                message((move ? "已移动 " : "已复制 ") + result + " 个项目");
            });
        }, "mypad-file-operation").start();
    }

    private File uniqueDestination(File directory, String name) {
        File target = new File(directory, name);
        if (!target.exists()) return target;
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        int index = 2;
        while (target.exists()) target = new File(directory, stem + " (" + index++ + ")" + ext);
        return target;
    }

    private boolean copyRecursively(File source, File target) {
        try {
            if (source.isDirectory()) {
                if (!target.mkdirs() && !target.isDirectory()) return false;
                File[] children = source.listFiles();
                if (children != null) for (File child : children) {
                    if (!copyRecursively(child, new File(target, child.getName()))) return false;
                }
                return true;
            }
            try (FileInputStream input = new FileInputStream(source); FileOutputStream output = new FileOutputStream(target)) {
                byte[] buffer = new byte[128 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            }
            target.setLastModified(source.lastModified());
            return true;
        } catch (Exception error) {
            return false;
        }
    }

    private void favoriteSelected() {
        try {
            JSONArray old = new JSONArray(getPreferences(MODE_PRIVATE).getString(FAVORITES_KEY, "[]"));
            Set<String> paths = new HashSet<>();
            for (int i = 0; i < old.length(); i++) paths.add(old.getString(i));
            for (File file : selectedFiles) paths.add(file.getAbsolutePath());
            JSONArray saved = new JSONArray();
            for (String path : paths) saved.put(path);
            getPreferences(MODE_PRIVATE).edit().putString(FAVORITES_KEY, saved.toString()).apply();
        } catch (Exception ignored) { }
        if (selectionMode != null) selectionMode.finish();
        message("已加入收藏");
    }

    private void renameSelected() {
        if (selectedFiles.size() != 1) { message("请选择一个项目重命名"); return; }
        File source = selectedFiles.iterator().next();
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(source.getName());
        input.selectAll();
        new AlertDialog.Builder(this).setTitle("重命名").setView(input).setNegativeButton("取消", null)
                .setPositiveButton("完成", (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    boolean renamed = !name.isEmpty() && source.renameTo(new File(source.getParentFile(), name));
                    if (selectionMode != null) selectionMode.finish();
                    refreshCurrent();
                    if (!renamed) message("重命名失败");
                }).show();
    }

    private void showSelectedDetails() {
        if (selectedFiles.size() != 1) { message("请选择一个项目查看详情"); return; }
        File file = selectedFiles.iterator().next();
        String info = "位置：" + file.getParent() + "\n类型：" + (file.isDirectory() ? "文件夹" : mimeFor(file))
                + "\n大小：" + formatBytes(totalFileBytes(file)) + "\n修改时间："
                + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(file.lastModified())
                + "\n权限：" + (file.canWrite() ? "可读写" : "只读");
        new AlertDialog.Builder(this).setTitle(file.getName()).setMessage(info).setPositiveButton("完成", null).show();
    }

    private long totalFileBytes(File file) {
        if (file.isFile()) return file.length();
        long total = 0;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) total += totalFileBytes(child);
        return total;
    }

    private void confirmDeleteSelected() {
        for (File file : selectedFiles) if (!FileOperations.canDelete(this, file)) return;
        new AlertDialog.Builder(this)
                .setTitle("删除所选项目？")
                .setMessage("此操作会直接删除 " + selectedFiles.size() + " 个项目。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> {
                    int deleted = 0;
                    for (File file : new ArrayList<>(selectedFiles)) if (FileOperations.deleteRecursively(file)) deleted++;
                    if (selectionMode != null) selectionMode.finish();
                    refreshCurrent();
                    message("已删除 " + deleted + " 个项目");
                }).show();
    }

    private boolean deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) if (!deleteRecursively(child)) return false;
        }
        return file.delete();
    }

    private void ensureStorageAccess() {
        if (!Environment.isExternalStorageManager()) {
            new AlertDialog.Builder(this)
                    .setTitle("允许管理文件")
                    .setMessage("“KEMI Pads”需要文件管理权限，才能读取下载、U盘和本机文件。")
                    .setNegativeButton("稍后", null)
                    .setPositiveButton("去设置", (dialog, which) -> {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    }).show();
        }
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 7);
        }
    }

    private void showInstalledApps() {
        setActiveSection("全部应用");
        currentDirectory = null;
        breadcrumbView.setText("系统  ›  全部应用");
        useSectionToolbar(null, null, true);
        refreshToolbarButton.setOnClickListener(v -> showInstalledApps());
        setHeader("应用名称", "版本", "占用", externalDisplayId() >= 0 ? "副屏" : "操作");
        fileList.removeAllViews();

        PackageManager pm = getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved = pm.queryIntentActivities(launcher, 0);
        Map<String, ResolveInfo> unique = new HashMap<>();
        for (ResolveInfo info : resolved) unique.put(info.activityInfo.packageName, info);
        List<ResolveInfo> apps = new ArrayList<>(unique.values());
        apps.sort(Comparator.comparing(info -> info.loadLabel(pm).toString().toLowerCase(Locale.ROOT)));
        for (ResolveInfo info : apps) fileList.addView(appRow(info, pm), lpMatch(dp(66)));
        footerLeft.setText(apps.size() + " 个可启动应用");
        footerRight.setText(externalDisplayId() >= 0 ? "点应用在主屏打开 · 右侧可直接在副屏打开" : "点应用即可打开");
        updateNavigationButtons();
    }

    private View appRow(ResolveInfo info, PackageManager pm) {
        LinearLayout row = horizontal(Color.TRANSPARENT);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(4), dp(8), dp(4));
        row.setBackground(ripple(Color.TRANSPARENT, 10));
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(info.loadIcon(pm));
        icon.setPadding(dp(3), dp(3), dp(3), dp(3));
        row.addView(icon, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout labels = vertical(Color.TRANSPARENT);
        labels.setGravity(Gravity.CENTER_VERTICAL);
        labels.addView(text(info.loadLabel(pm).toString(), 14, TEXT, true));
        labels.addView(text(info.activityInfo.packageName, 10, MUTED, false));
        LinearLayout.LayoutParams labelsLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        labelsLp.leftMargin = dp(12);
        row.addView(labels, labelsLp);
        String version = "—";
        long apkBytes = 0;
        try {
            version = pm.getPackageInfo(info.activityInfo.packageName, 0).versionName;
            apkBytes = new File(info.activityInfo.applicationInfo.sourceDir).length();
        } catch (Exception ignored) { }
        row.addView(text(version == null ? "—" : version, 12, MUTED, false), new LinearLayout.LayoutParams(dp(130), ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(text(formatBytes(apkBytes), 12, MUTED, false), new LinearLayout.LayoutParams(dp(100), ViewGroup.LayoutParams.WRAP_CONTENT));
        int secondDisplay = externalDisplayId();
        Button open = smallActionButton(secondDisplay >= 0 ? "副屏打开" : "打开", BLUE);
        Runnable launch = () -> {
            Intent intent = pm.getLaunchIntentForPackage(info.activityInfo.packageName);
            if (intent != null) startActivity(intent); else message("该应用没有可打开的界面");
        };
        open.setOnClickListener(v -> {
            if (secondDisplay < 0) {
                launch.run();
                return;
            }
            Intent intent = pm.getLaunchIntentForPackage(info.activityInfo.packageName);
            if (intent == null) { message("该应用没有可打开的界面"); return; }
            try {
                ActivityOptions options = ActivityOptions.makeBasic();
                options.setLaunchDisplayId(secondDisplay);
                startActivity(intent, options.toBundle());
            } catch (Exception error) {
                message("该应用暂不支持在副屏启动");
            }
        });
        row.addView(open, new LinearLayout.LayoutParams(dp(92), dp(46)));
        row.setOnClickListener(v -> launch.run());
        return row;
    }

    private void openSystemSettings() {
        startActivity(new Intent(Settings.ACTION_SETTINGS));
    }

    private int externalDisplayId() {
        DisplayManager manager = getSystemService(DisplayManager.class);
        if (manager == null) return -1;
        for (Display display : manager.getDisplays()) {
            if (display.getDisplayId() != Display.DEFAULT_DISPLAY && display.getState() != Display.STATE_OFF) return display.getDisplayId();
        }
        return -1;
    }

    private void showDualScreenManager() {
        setActiveSection("双屏管理");
        currentDirectory = null;
        navigationRoot = null;
        breadcrumbView.setText("系统  ›  双屏管理");
        useSectionToolbar(null, null, true);
        refreshToolbarButton.setOnClickListener(v -> showDualScreenManager());
        headerView.setVisibility(View.GONE);
        fileList.removeAllViews();
        DisplayManager manager = getSystemService(DisplayManager.class);
        Display[] displays = manager == null ? new Display[0] : manager.getDisplays();
        for (Display display : displays) {
            DisplayMetrics metrics = new DisplayMetrics();
            display.getRealMetrics(metrics);
            String role = display.getDisplayId() == Display.DEFAULT_DISPLAY ? "主屏" : "副屏";
            LinearLayout card = horizontal(Color.rgb(248, 250, 252));
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(dp(18), dp(12), dp(18), dp(12));
            card.setBackground(roundStroke(Color.rgb(248, 250, 252), BORDER, 13));
            TextView icon = text(display.getDisplayId() == Display.DEFAULT_DISPLAY ? "▣" : "▢", 27, TEAL, false);
            icon.setGravity(Gravity.CENTER);
            card.addView(icon, new LinearLayout.LayoutParams(dp(52), ViewGroup.LayoutParams.MATCH_PARENT));
            LinearLayout copy = vertical(Color.TRANSPARENT);
            copy.addView(text(role + " · " + display.getName(), 16, TEXT, true));
            copy.addView(text(metrics.widthPixels + " × " + metrics.heightPixels + " · " + Math.round(display.getRefreshRate()) + " Hz · 触控可用", 11, MUTED, false));
            LinearLayout.LayoutParams copyLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1); copyLp.leftMargin = dp(12); card.addView(copy, copyLp);
            TextView state = text(display.getState() == Display.STATE_ON ? "正在使用" : "已连接", 12, TEAL, true);
            state.setGravity(Gravity.CENTER);
            card.addView(state, new LinearLayout.LayoutParams(dp(100), ViewGroup.LayoutParams.MATCH_PARENT));
            LinearLayout.LayoutParams cardLp = lpMatch(dp(82)); cardLp.setMargins(dp(6), dp(6), dp(6), dp(4)); fileList.addView(card, cardLp);
        }
        LinearLayout actions = horizontal(Color.TRANSPARENT);
        actions.setPadding(dp(8), dp(14), dp(8), dp(8));
        Button controller = smallActionButton("打开双屏设置", BLUE);
        controller.setOnClickListener(v -> launchPackage("com.huanglong.portui"));
        actions.addView(controller, new LinearLayout.LayoutParams(dp(180), dp(48)));
        Button wallpaper = smallActionButton("设置双屏壁纸", TEAL);
        wallpaper.setOnClickListener(v -> launchPackage("com.kemi.dualwallpaper"));
        LinearLayout.LayoutParams wallpaperLp = new LinearLayout.LayoutParams(dp(180), dp(48)); wallpaperLp.leftMargin = dp(12); actions.addView(wallpaper, wallpaperLp);
        fileList.addView(actions, lpMatch(dp(76)));
        TextView help = text("在“全部应用”中，点应用名称在主屏打开；点右侧“副屏打开”直接送到副屏。清理后台会同时保护两个屏幕上正在显示的应用。", 13, MUTED, false);
        help.setPadding(dp(18), dp(10), dp(18), dp(10));
        fileList.addView(help, lpMatch(dp(62)));
        footerLeft.setText(displays.length + " 块屏幕已连接");
        footerRight.setText("双屏状态实时读取");
        updateNavigationButtons();
    }

    private void launchPackage(String packageName) {
        Intent intent = getPackageManager().getLaunchIntentForPackage(packageName);
        if (intent != null) startActivity(intent); else message("系统中未找到该功能");
    }

    private void showFileDistribution() {
        setActiveSection("文件分发");
        currentDirectory = null;
        navigationRoot = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        breadcrumbView.setText("下载  ›  局域网文件分发");
        boolean running = FileDistributionService.isRunning();
        useSectionToolbar(running ? "停止分发" : "开始分发", running ? this::stopDistribution : this::startDistribution, true);
        refreshToolbarButton.setOnClickListener(v -> showFileDistribution());
        setFileHeader();
        fileList.removeAllViews();

        LinearLayout panel = vertical(Color.rgb(248, 250, 252));
        panel.setPadding(dp(20), dp(16), dp(20), dp(16));
        panel.setBackground(roundStroke(Color.rgb(248, 250, 252), BORDER, 14));
        panel.addView(text(running ? "文件分发正在运行" : "文件分发未启动", 17, running ? Color.rgb(14, 128, 112) : TEXT, true));
        List<String> urls = FileDistributionService.accessUrls();
        String primary = urls.isEmpty() ? "点击右上角“开始分发”" : urls.get(0);
        TextView url = text(primary, 15, BLUE, false);
        url.setPadding(0, dp(9), 0, dp(8));
        panel.addView(url);
        panel.addView(text("同一局域网的电脑或手机，在浏览器输入上方地址即可浏览和下载本机“下载”目录。", 12, MUTED, false));
        if (!urls.isEmpty()) {
            LinearLayout actions = horizontal(Color.TRANSPARENT);
            actions.setPadding(0, dp(12), 0, 0);
            Button copy = smallActionButton("复制地址", BLUE);
            copy.setOnClickListener(v -> copyText(primary));
            actions.addView(copy, new LinearLayout.LayoutParams(dp(108), dp(44)));
            Button share = smallActionButton("分享地址", TEAL);
            share.setOnClickListener(v -> shareText(primary));
            LinearLayout.LayoutParams shareLp = new LinearLayout.LayoutParams(dp(108), dp(44)); shareLp.leftMargin = dp(10);
            actions.addView(share, shareLp);
            panel.addView(actions);
        }
        Button kemiSend = smallActionButton("打开 KEMI Send · 自动发现并收发文件", TEAL);
        kemiSend.setOnClickListener(v -> openKemiSend());
        LinearLayout.LayoutParams kemiSendLp = lpMatch(dp(48));
        kemiSendLp.topMargin = dp(12);
        panel.addView(kemiSend, kemiSendLp);
        LinearLayout.LayoutParams panelLp = lpMatch(ViewGroup.LayoutParams.WRAP_CONTENT);
        panelLp.setMargins(dp(4), dp(6), dp(4), dp(12));
        fileList.addView(panel, panelLp);
        List<File> files = filesIn(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS));
        for (File file : files) fileList.addView(fileRow(file), lpMatch(file.isDirectory() ? dp(60) : dp(64)));
        footerLeft.setText(files.size() + " 个项目可分发");
        footerRight.setText(running ? "局域网访问已开启 · 端口 " + FileDistributionService.FIXED_PORT : "服务关闭时外部无法访问");
        updateNavigationButtons();
    }

    private void startDistribution() {
        Intent intent = new Intent(this, FileDistributionService.class).setAction(FileDistributionService.ACTION_START);
        startForegroundService(intent);
        primaryToolbarButton.setEnabled(false);
        handler.postDelayed(() -> { primaryToolbarButton.setEnabled(true); showFileDistribution(); }, 450);
    }

    private void stopDistribution() {
        Intent intent = new Intent(this, FileDistributionService.class).setAction(FileDistributionService.ACTION_STOP);
        startService(intent);
        handler.postDelayed(this::showFileDistribution, 250);
    }

    private void showLanFiles() {
        setActiveSection("局域网文件");
        currentDirectory = null;
        navigationRoot = null;
        currentLanBase = "";
        currentLanPath = "";
        breadcrumbView.setText("局域网  ›  其他设备");
        useSectionToolbar("连接设备", this::showLanAddressDialog, true);
        refreshToolbarButton.setOnClickListener(v -> loadLanPath(getPreferences(MODE_PRIVATE).getString("lan_url", ""), ""));
        setHeader("名称", "修改时间", "大小", "操作");
        fileList.removeAllViews();
        String base = getPreferences(MODE_PRIVATE).getString("lan_url", "");
        if (base.isEmpty()) showLanEmpty(); else loadLanPath(base, "");
        updateNavigationButtons();
    }

    private void showLanEmpty() {
        fileList.removeAllViews();
        LinearLayout empty = vertical(Color.TRANSPARENT);
        empty.setGravity(Gravity.CENTER);
        empty.addView(centerText("连接另一台“KEMI Pads”", 19, TEXT, true));
        TextView hint = centerText("输入对方文件分发页面显示的地址，例如 192.168.3.63:8686", 13, MUTED, false);
        hint.setPadding(0, dp(10), 0, dp(18));
        empty.addView(hint);
        Button connect = smallActionButton("连接设备", BLUE);
        connect.setOnClickListener(v -> showLanAddressDialog());
        empty.addView(connect, new LinearLayout.LayoutParams(dp(130), dp(48)));
        Button kemiSend = smallActionButton("自动发现设备 · KEMI Send", TEAL);
        kemiSend.setOnClickListener(v -> openKemiSend());
        LinearLayout.LayoutParams kemiSendLp = new LinearLayout.LayoutParams(dp(220), dp(48));
        kemiSendLp.topMargin = dp(12);
        empty.addView(kemiSend, kemiSendLp);
        fileList.addView(empty, lpMatch(dp(240)));
        footerLeft.setText("未连接局域网设备");
        footerRight.setText("只访问同一局域网内主动开启分发的设备");
    }

    private void showLanAddressDialog() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("192.168.3.63:8686");
        String saved = getPreferences(MODE_PRIVATE).getString("lan_url", "");
        input.setText(saved.replace("http://", ""));
        input.setPadding(dp(16), 0, dp(16), 0);
        input.setBackground(roundStroke(Color.rgb(248, 250, 252), BORDER, 12));
        LinearLayout wrap = vertical(SURFACE); wrap.setPadding(dp(22), dp(8), dp(22), 0); wrap.addView(input, lpMatch(dp(50)));
        new AlertDialog.Builder(this).setTitle("连接局域网设备").setView(wrap).setNegativeButton("取消", null)
                .setPositiveButton("连接", (d, w) -> {
                    String value = input.getText().toString().trim();
                    if (!value.startsWith("http://") && !value.startsWith("https://")) value = "http://" + value;
                    while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
                    getPreferences(MODE_PRIVATE).edit().putString("lan_url", value).apply();
                    loadLanPath(value, "");
                }).show();
    }

    private void loadLanPath(String base, String path) {
        if (base == null || base.isEmpty()) { showLanEmpty(); return; }
        currentLanBase = base;
        currentLanPath = path == null ? "" : path;
        fileList.removeAllViews();
        ProgressBar progress = new ProgressBar(this);
        fileList.addView(progress, lpMatch(dp(120)));
        footerLeft.setText("正在连接 " + base.replace("http://", ""));
        new Thread(() -> {
            try {
                URL url = new URL(base + "/api/files?path=" + URLEncoder.encode(path, "UTF-8"));
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(3500); connection.setReadTimeout(6000);
                String json = readText(connection.getInputStream());
                JSONObject root = new JSONObject(json);
                JSONArray items = root.getJSONArray("items");
                runOnUiThread(() -> renderLanItems(base, path, items));
            } catch (Exception error) {
                runOnUiThread(() -> {
                    showLanEmpty();
                    message("连接失败，请确认对方已开启文件分发");
                    footerLeft.setText("无法连接 " + base.replace("http://", ""));
                });
            }
        }, "mypad-lan-list").start();
    }

    private void renderLanItems(String base, String path, JSONArray items) {
        if (!"局域网文件".equals(currentSection)) return;
        currentLanBase = base;
        currentLanPath = path == null ? "" : path;
        fileList.removeAllViews();
        breadcrumbView.setText("局域网  ›  " + base.replace("http://", "") + (path.isEmpty() ? "" : "  ›  " + path.replace('/', '›')));
        if (!path.isEmpty()) {
            Button parent = smallActionButton("‹ 返回上一级", TEXT);
            String parentPath = new File(path).getParent();
            parent.setOnClickListener(v -> loadLanPath(base, parentPath == null ? "" : parentPath));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(150), dp(46)); lp.setMargins(dp(8), dp(6), 0, dp(6));
            fileList.addView(parent, lp);
        }
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null) fileList.addView(lanRow(base, item), lpMatch(dp(64)));
        }
        footerLeft.setText(items.length() + " 个局域网项目");
        footerRight.setText("文件下载后保存在本机“下载”目录");
        updateNavigationButtons();
    }

    private View lanRow(String base, JSONObject item) {
        boolean directory = item.optBoolean("directory");
        String name = item.optString("name");
        String path = item.optString("path");
        LinearLayout row = horizontal(Color.TRANSPARENT); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(8), dp(4), dp(8), dp(4)); row.setBackground(ripple(Color.TRANSPARENT, 10));
        TextView icon = text(directory ? "▰" : "▤", 22, TEXT, false); icon.setGravity(Gravity.CENTER); icon.setBackground(round(Color.rgb(234, 240, 243), 9));
        row.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView label = text(name, 14, TEXT, true); LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1); labelLp.leftMargin = dp(12); row.addView(label, labelLp);
        DateFormat date = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);
        row.addView(text(date.format(item.optLong("modified")), 12, MUTED, false), new LinearLayout.LayoutParams(dp(130), ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(text(directory ? "文件夹" : formatBytes(item.optLong("size")), 12, MUTED, false), new LinearLayout.LayoutParams(dp(100), ViewGroup.LayoutParams.WRAP_CONTENT));
        Button action = smallActionButton(directory ? "进入" : "下载", directory ? TEXT : BLUE);
        Runnable click = directory ? () -> loadLanPath(base, path) : () -> downloadLanFile(base, path, name, action);
        action.setOnClickListener(v -> click.run()); row.addView(action, new LinearLayout.LayoutParams(dp(92), dp(46))); row.setOnClickListener(v -> click.run());
        return row;
    }

    private void downloadLanFile(String base, String path, String name, Button button) {
        button.setEnabled(false); button.setText("下载中");
        new Thread(() -> {
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(base + "/download?path=" + URLEncoder.encode(path, "UTF-8")).openConnection();
                connection.setConnectTimeout(4000); connection.setReadTimeout(30000);
                File output = uniqueDownloadFile(name);
                try (InputStream input = connection.getInputStream(); FileOutputStream file = new FileOutputStream(output)) {
                    byte[] buffer = new byte[64 * 1024]; int read; while ((read = input.read(buffer)) != -1) file.write(buffer, 0, read);
                }
                runOnUiThread(() -> { button.setEnabled(true); button.setText("已下载"); message("已保存到下载目录"); });
            } catch (Exception error) {
                runOnUiThread(() -> { button.setEnabled(true); button.setText("重试"); message("下载失败"); });
            }
        }, "mypad-lan-download").start();
    }

    private File uniqueDownloadFile(String name) {
        File root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File output = new File(root, name); int index = 2;
        int dot = name.lastIndexOf('.'); String stem = dot > 0 ? name.substring(0, dot) : name; String ext = dot > 0 ? name.substring(dot) : "";
        while (output.exists()) output = new File(root, stem + " (" + index++ + ")" + ext);
        return output;
    }

    private String readText(InputStream input) throws Exception {
        StringBuilder value = new StringBuilder(); byte[] buffer = new byte[8192]; int read;
        try (InputStream source = input) { while ((read = source.read(buffer)) != -1) value.append(new String(buffer, 0, read, StandardCharsets.UTF_8)); }
        return value.toString();
    }

    private void copyText(String value) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("KEMI Pads 文件分发", value));
        message("地址已复制");
    }

    private void shareText(String value) {
        startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, value), "分享文件分发地址"));
    }

    private void openKemiSend() {
        Intent intent = getPackageManager().getLaunchIntentForPackage("org.kemi.send");
        if (intent == null) {
            message("未安装 KEMI Send");
            return;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    private void showActivityMonitor() {
        setActiveSection("清理后台");
        currentDirectory = null;
        breadcrumbView.setText("系统  ›  活动监控");
        useSectionToolbar("一键清理", this::cleanBackgroundAnimated, true);
        refreshToolbarButton.setOnClickListener(v -> refreshActivityMonitor(true));
        setHeader("进程名称", "内存", "PID", "分析结果");
        headerView.setVisibility(View.GONE);
        long generation = ++monitorGeneration;
        showMonitorLoading("正在分析后台程序…");
        loadMonitorSnapshot(generation, true);
        updateNavigationButtons();
    }

    private void refreshActivityMonitor(boolean showLoading) {
        if (!"清理后台".equals(currentSection)) return;
        long generation = monitorGeneration;
        if (showLoading) showMonitorLoading("正在刷新系统状态…");
        loadMonitorSnapshot(generation, false);
    }

    private void showMonitorLoading(String status) {
        fileList.removeAllViews();
        LinearLayout loading = horizontal(Color.TRANSPARENT);
        loading.setGravity(Gravity.CENTER);
        monitorProgress = new ProgressBar(this);
        loading.addView(monitorProgress, new LinearLayout.LayoutParams(dp(42), dp(42)));
        monitorStatus = text(status, 14, MUTED, false);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusLp.leftMargin = dp(12); loading.addView(monitorStatus, statusLp);
        fileList.addView(loading, lpMatch(dp(130)));
        footerLeft.setText(status);
        footerRight.setText("只清理普通后台应用，系统与当前应用会保留");
    }

    private void loadMonitorSnapshot(long generation, boolean scheduleNext) {
        new Thread(() -> {
            MonitorSnapshot snapshot = collectMonitorSnapshot();
            runOnUiThread(() -> {
                if (!"清理后台".equals(currentSection) || generation != monitorGeneration) return;
                renderMonitorSnapshot(snapshot);
                if (scheduleNext) scheduleMonitorRefresh(generation);
            });
        }, "mypad-monitor").start();
    }

    private void scheduleMonitorRefresh(long generation) {
        handler.postDelayed(() -> {
            if ("清理后台".equals(currentSection) && generation == monitorGeneration) {
                loadMonitorSnapshot(generation, false);
                scheduleMonitorRefresh(generation);
            }
        }, 3000);
    }

    private MonitorSnapshot collectMonitorSnapshot() {
        ActivityManager manager = getSystemService(ActivityManager.class);
        MonitorSnapshot snapshot = new MonitorSnapshot();
        if (manager == null) return snapshot;
        Map<String, String> screenApps = detectScreenApps(manager, snapshot);
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        manager.getMemoryInfo(memory);
        snapshot.totalMemory = memory.totalMem;
        snapshot.availableMemory = memory.availMem;
        snapshot.lowMemory = memory.lowMemory;
        readMemoryDetails(snapshot);
        readDeviceDetails(snapshot);
        float[] cpu = readCpuUsage();
        snapshot.cpuPercent = cpu.length > 0 ? cpu[0] : 0;
        snapshot.corePercents = cpu.length > 1 ? Arrays.copyOfRange(cpu, 1, cpu.length) : new float[0];
        snapshot.coreFrequencies = readCoreFrequencies(snapshot.corePercents.length);
        StatFs storage = new StatFs(Environment.getExternalStorageDirectory().getAbsolutePath());
        snapshot.totalStorage = storage.getTotalBytes();
        snapshot.availableStorage = storage.getAvailableBytes();
        List<ActivityManager.RunningAppProcessInfo> processes = manager.getRunningAppProcesses();
        if (processes != null) {
            for (ActivityManager.RunningAppProcessInfo process : processes) {
                ProcessEntry entry = new ProcessEntry();
                entry.pid = process.pid;
                entry.processName = process.processName;
                entry.packages = process.pkgList == null ? new String[0] : process.pkgList;
                entry.importance = process.importance;
                try {
                    Debug.MemoryInfo[] details = manager.getProcessMemoryInfo(new int[]{process.pid});
                    if (details.length > 0) entry.memoryBytes = details[0].getTotalPss() * 1024L;
                } catch (Exception ignored) { }
                entry.label = appLabel(entry.packages, entry.processName);
                entry.screenRole = screenRole(entry.packages, screenApps);
                entry.protectedEntry = isProtectedPackages(entry.packages);
                entry.cleanable = isCleanable(entry);
                entry.analysisReason = processReason(entry);
                snapshot.processes.add(entry);
            }
        }
        snapshot.usageAccess = hasUsageAccess();
        if (snapshot.usageAccess) mergeRecentlyActiveApps(snapshot);
        ensureScreenProcessEntries(snapshot);
        snapshot.processes.sort((a, b) -> Long.compare(b.memoryBytes, a.memoryBytes));
        return snapshot;
    }

    private void mergeRecentlyActiveApps(MonitorSnapshot snapshot) {
        UsageStatsManager usage = getSystemService(UsageStatsManager.class);
        if (usage == null) return;
        long now = System.currentTimeMillis();
        List<UsageStats> stats = usage.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 6 * 60 * 60 * 1000L, now);
        if (stats == null) return;
        Set<String> existing = new HashSet<>();
        for (ProcessEntry entry : snapshot.processes) for (String pkg : entry.packages) existing.add(pkg);
        stats.sort((a, b) -> Long.compare(b.getLastTimeUsed(), a.getLastTimeUsed()));
        for (UsageStats stat : stats) {
            String pkg = stat.getPackageName();
            if (stat.getLastTimeUsed() <= 0 || existing.contains(pkg) || pkg.equals(getPackageName())) continue;
            try {
                ApplicationInfo info = getPackageManager().getApplicationInfo(pkg, 0);
                if ((info.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
            } catch (Exception ignored) { continue; }
            ProcessEntry entry = new ProcessEntry();
            entry.processName = pkg;
            entry.packages = new String[]{pkg};
            entry.label = appLabel(entry.packages, pkg);
            entry.importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED;
            entry.lastUsed = stat.getLastTimeUsed();
            entry.screenRole = screenRole(entry.packages, snapshot.screenPackages);
            entry.protectedEntry = isProtectedPackages(entry.packages);
            Long cleanedAt = cleanedPackages.get(pkg);
            if (cleanedAt != null && entry.lastUsed > cleanedAt) cleanedPackages.remove(pkg);
            entry.cleaned = cleanedAt != null && entry.lastUsed <= cleanedAt && now - cleanedAt < 10 * 60 * 1000L;
            entry.cleanable = !entry.cleaned && now - entry.lastUsed > 90_000 && isCleanable(entry);
            entry.analysisReason = processReason(entry);
            snapshot.processes.add(entry);
            existing.add(pkg);
            if (snapshot.processes.size() >= 40) break;
        }
    }

    private boolean hasUsageAccess() {
        AppOpsManager ops = getSystemService(AppOpsManager.class);
        if (ops == null) return false;
        int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    private Map<String, String> detectScreenApps(ActivityManager manager, MonitorSnapshot snapshot) {
        Map<String, String> result = new HashMap<>();
        DisplayManager displays = getSystemService(DisplayManager.class);
        Display[] activeDisplays = displays == null ? new Display[0] : displays.getDisplays();
        snapshot.displayCount = activeDisplays.length;
        for (Display display : activeDisplays) {
            if (display.getState() == Display.STATE_OFF) continue;
            snapshot.activeDisplayCount++;
            if (display.getDisplayId() != Display.DEFAULT_DISPLAY) snapshot.externalDisplayId = display.getDisplayId();
        }
        try {
            List<ActivityManager.RunningTaskInfo> tasks = manager.getRunningTasks(20);
            if (tasks != null) for (ActivityManager.RunningTaskInfo task : tasks) {
                if (task.topActivity == null) continue;
                String packageName = task.topActivity.getPackageName();
                int displayId = runningTaskDisplayId(task);
                String role = displayId == Display.DEFAULT_DISPLAY ? "主屏前台" : "副屏前台";
                result.put(packageName, role);
                snapshot.screenApps.put(role, appLabel(new String[]{packageName}, packageName));
            }
        } catch (Exception ignored) { }
        addLikelyScreenAppsFromUsage(result, snapshot);
        snapshot.screenPackages.putAll(result);
        return result;
    }

    private void ensureScreenProcessEntries(MonitorSnapshot snapshot) {
        Set<String> existing = new HashSet<>();
        for (ProcessEntry entry : snapshot.processes) {
            for (String pkg : entry.packages) existing.add(pkg);
        }
        for (Map.Entry<String, String> screen : snapshot.screenPackages.entrySet()) {
            String pkg = screen.getKey();
            if (existing.contains(pkg)) {
                for (ProcessEntry entry : snapshot.processes) {
                    for (String value : entry.packages) if (pkg.equals(value)) {
                        entry.screenRole = screen.getValue();
                        entry.cleanable = false;
                        entry.analysisReason = "正在" + ("副屏前台".equals(screen.getValue()) ? "副屏" : "主屏") + "显示，禁止清理";
                    }
                }
                continue;
            }
            ProcessEntry entry = processEntryFromProc(pkg);
            entry.packages = new String[]{pkg};
            entry.processName = pkg;
            entry.label = appLabel(entry.packages, pkg);
            entry.screenRole = screen.getValue();
            entry.importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
            entry.protectedEntry = true;
            entry.cleanable = false;
            entry.analysisReason = "由双屏任务栈确认正在" + ("副屏前台".equals(entry.screenRole) ? "副屏" : "主屏") + "运行";
            snapshot.processes.add(entry);
        }
    }

    private ProcessEntry processEntryFromProc(String packageName) {
        ProcessEntry result = new ProcessEntry();
        File proc = new File("/proc");
        File[] entries = proc.listFiles(file -> file.isDirectory() && file.getName().matches("\\d+"));
        if (entries == null) return result;
        for (File entry : entries) {
            try {
                String command;
                try (FileInputStream input = new FileInputStream(new File(entry, "cmdline"))) {
                    byte[] buffer = new byte[256];
                    int read = input.read(buffer);
                    if (read <= 0) continue;
                    command = new String(buffer, 0, read, StandardCharsets.UTF_8).replace("\u0000", "").trim();
                }
                if (!command.equals(packageName) && !command.startsWith(packageName + ":")) continue;
                result.pid = Integer.parseInt(entry.getName());
                try (BufferedReader reader = new BufferedReader(new FileReader(new File(entry, "status")))) {
                    String line;
                    while ((line = reader.readLine()) != null) if (line.startsWith("VmRSS:")) {
                        String[] parts = line.trim().split("\\s+");
                        if (parts.length > 1) result.memoryBytes = Long.parseLong(parts[1]) * 1024L;
                        break;
                    }
                }
                return result;
            } catch (Exception ignored) { }
        }
        return result;
    }

    private void addLikelyScreenAppsFromUsage(Map<String, String> screenApps, MonitorSnapshot snapshot) {
        if (snapshot.activeDisplayCount < 2 || !hasUsageAccess()) return;
        UsageStatsManager usage = getSystemService(UsageStatsManager.class);
        if (usage == null) return;
        long now = System.currentTimeMillis();
        Map<String, Long> resumed = new HashMap<>();
        try {
            UsageEvents events = usage.queryEvents(now - 12 * 60 * 60 * 1000L, now);
            UsageEvents.Event event = new UsageEvents.Event();
            while (events.hasNextEvent()) {
                events.getNextEvent(event);
                int type = event.getEventType();
                if (type == UsageEvents.Event.ACTIVITY_RESUMED || type == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    resumed.put(event.getPackageName(), event.getTimeStamp());
                }
            }
        } catch (Exception ignored) { return; }
        List<Map.Entry<String, Long>> ordered = new ArrayList<>(resumed.entrySet());
        ordered.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        for (Map.Entry<String, Long> item : ordered) {
            String pkg = item.getKey();
            if (screenApps.containsKey(pkg) || pkg.equals("com.android.launcher3")) continue;
            if (getPackageManager().getLaunchIntentForPackage(pkg) == null) continue;
            screenApps.put(pkg, "副屏前台");
            snapshot.screenApps.put("副屏前台", appLabel(new String[]{pkg}, pkg));
            break;
        }
    }

    private int intAfter(String value, String marker, int fallback) {
        int start = value.indexOf(marker);
        if (start < 0) return fallback;
        start += marker.length();
        int end = start;
        while (end < value.length() && Character.isDigit(value.charAt(end))) end++;
        try { return Integer.parseInt(value.substring(start, end)); } catch (Exception ignored) { return fallback; }
    }

    private int runningTaskDisplayId(ActivityManager.RunningTaskInfo task) {
        try {
            java.lang.reflect.Field field = task.getClass().getField("displayId");
            field.setAccessible(true);
            return field.getInt(task);
        } catch (Exception ignored) { }
        return intAfter(task.toString(), "displayId=", Display.DEFAULT_DISPLAY);
    }

    private String screenRole(String[] packages, Map<String, String> screenApps) {
        for (String pkg : packages) {
            String role = screenApps.get(pkg);
            if (role != null) return role;
        }
        return "";
    }

    private void renderMonitorSnapshot(MonitorSnapshot snapshot) {
        fileList.removeAllViews();
        LinearLayout metrics = horizontal(Color.TRANSPARENT);
        metrics.setPadding(dp(4), dp(6), dp(4), dp(12));
        long usedMemory = snapshot.totalMemory - snapshot.availableMemory;
        long usedStorage = snapshot.totalStorage - snapshot.availableStorage;
        metrics.addView(metricCard("CPU", String.format(Locale.CHINA, "%.0f%%", snapshot.cpuPercent), snapshot.corePercents.length + " 核实时负载", snapshot.cpuPercent < 80 ? TEAL : Color.rgb(230, 126, 34)), weightedCard());
        metrics.addView(metricCard("内存", "6 GB + 2 GB", formatBytes(usedMemory) + " 已用 · " + formatBytes(snapshot.availableMemory) + " 可用", snapshot.lowMemory ? Color.rgb(220, 70, 70) : TEAL), weightedCard());
        metrics.addView(metricCard("存储", formatBytes(usedStorage) + " / " + formatBytes(snapshot.totalStorage), formatBytes(snapshot.availableStorage) + " 可用", percent(usedStorage, snapshot.totalStorage) > 90 ? Color.rgb(220, 70, 70) : BLUE), weightedCard());
        String stability = snapshot.lowMemory || percent(usedStorage, snapshot.totalStorage) > 94 || snapshot.cpuPercent > 92 ? "需要关注" : "运行稳定";
        metrics.addView(metricCard("系统稳定性", stability, snapshot.processes.size() + " 个活动进程 · 已运行 " + uptimeText(), "运行稳定".equals(stability) ? TEAL : Color.rgb(230, 126, 34)), weightedCard());
        LinearLayout.LayoutParams metricsLp = lpMatch(dp(124));
        metricsLp.setMargins(0, dp(6), 0, dp(4));
        fileList.addView(metrics, metricsLp);
        fileList.addView(screenProtectionPanel(snapshot), lpMatch(dp(88)));
        fileList.addView(coreUsagePanel(snapshot.corePercents, snapshot.coreFrequencies), lpMatch(dp(100)));
        fileList.addView(memoryPressurePanel(snapshot), lpMatch(dp(126)));
        fileList.addView(deviceDiagnosticsPanel(snapshot), lpMatch(dp(142)));
        if (!snapshot.usageAccess) {
            LinearLayout permission = horizontal(Color.rgb(255, 249, 235)); permission.setGravity(Gravity.CENTER_VERTICAL); permission.setPadding(dp(16), 0, dp(10), 0); permission.setBackground(roundStroke(Color.rgb(255, 249, 235), Color.rgb(239, 204, 126), 11));
            permission.addView(text("Android 限制了其他应用的活动信息，启用一次分析权限后可显示完整的近期后台应用。", 12, Color.rgb(126, 88, 18), false), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            Button allow = smallActionButton("启用活动分析", Color.rgb(160, 99, 8));
            allow.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)));
            permission.addView(allow, new LinearLayout.LayoutParams(dp(138), dp(44)));
            LinearLayout.LayoutParams permissionLp = lpMatch(dp(58)); permissionLp.setMargins(dp(8), 0, dp(8), dp(8)); fileList.addView(permission, permissionLp);
        }
        fileList.addView(processHeaderRow(), lpMatch(dp(36)));
        int cleanable = 0;
        int cleaned = 0;
        for (ProcessEntry entry : snapshot.processes) {
            if (entry.cleanable) cleanable++;
            if (entry.cleaned) cleaned++;
            fileList.addView(processRow(entry), lpMatch(dp(68)));
        }
        footerLeft.setText(snapshot.processes.size() + " 个活动进程 · " + cleanable + " 个可清理" + (cleaned > 0 ? " · " + cleaned + " 个已清理" : ""));
        footerRight.setText("只释放普通后台缓存 · 主屏、副屏、服务和系统进程全部保留");
    }

    private View screenProtectionPanel(MonitorSnapshot snapshot) {
        int screenProtected = 0;
        int systemProtected = 0;
        int serviceProtected = 0;
        int cleanable = 0;
        for (ProcessEntry entry : snapshot.processes) {
            if (entry.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE || !entry.screenRole.isEmpty()) screenProtected++;
            else if (entry.protectedEntry || isSystemPackages(entry.packages)) systemProtected++;
            else if (entry.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE) serviceProtected++;
            if (entry.cleanable) cleanable++;
        }
        LinearLayout panel = horizontal(Color.rgb(240, 249, 247));
        panel.setGravity(Gravity.CENTER_VERTICAL);
        panel.setPadding(dp(16), dp(10), dp(16), dp(10));
        panel.setBackground(roundStroke(Color.rgb(240, 249, 247), Color.rgb(174, 220, 212), 12));
        LinearLayout copy = vertical(Color.TRANSPARENT);
        copy.addView(text("双屏前台保护已开启", 14, Color.rgb(10, 117, 102), true));
        String main = snapshot.screenApps.getOrDefault("主屏前台", "系统可见应用");
        String secondary = snapshot.activeDisplayCount > 1
                ? snapshot.screenApps.getOrDefault("副屏前台", "系统可见应用") : "未检测到副屏";
        TextView detail = text("主屏：" + main + "    副屏：" + secondary, 11, MUTED, false);
        detail.setPadding(0, dp(7), 0, 0);
        copy.addView(detail);
        panel.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        LinearLayout counts = vertical(Color.TRANSPARENT);
        counts.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        counts.addView(text("仅 " + cleanable + " 个普通后台可清理", 13, cleanable > 0 ? Color.rgb(190, 112, 20) : TEAL, true));
        counts.addView(text("屏幕保护 " + screenProtected + " · 系统 " + systemProtected + " · 服务 " + serviceProtected, 10, MUTED, false));
        panel.addView(counts, new LinearLayout.LayoutParams(dp(330), ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams lp = lpMatch(dp(88));
        lp.setMargins(dp(8), dp(3), dp(8), dp(8));
        panel.setLayoutParams(lp);
        return panel;
    }

    private View metricCard(String label, String value, String detail, int accent) {
        LinearLayout card = vertical(Color.rgb(248, 250, 252));
        card.setPadding(dp(15), dp(12), dp(12), dp(10));
        card.setBackground(roundStroke(Color.rgb(248, 250, 252), BORDER, 13));
        card.addView(text(label, 11, MUTED, true));
        TextView primary = text(value, 16, accent, true); primary.setPadding(0, dp(5), 0, dp(3)); card.addView(primary);
        card.addView(text(detail, 10, MUTED, false));
        return card;
    }

    private View coreUsagePanel(float[] cores, long[] frequencies) {
        LinearLayout panel = vertical(Color.TRANSPARENT);
        panel.setPadding(dp(8), dp(4), dp(8), dp(8));
        panel.addView(text("CPU 各核心实时占用", 11, MUTED, true), lpMatch(dp(24)));
        LinearLayout cells = horizontal(Color.TRANSPARENT);
        int count = cores.length == 0 ? 8 : cores.length;
        for (int i = 0; i < count; i++) {
            float usage = cores.length > i ? cores[i] : 0;
            int accent = usage > 85 ? Color.rgb(220, 70, 70) : usage > 60 ? Color.rgb(230, 153, 45) : TEAL;
            LinearLayout cell = vertical(Color.rgb(248, 250, 252));
            cell.setPadding(dp(9), dp(5), dp(9), dp(4));
            cell.setBackground(roundStroke(Color.rgb(248, 250, 252), BORDER, 9));
            LinearLayout top = horizontal(Color.TRANSPARENT);
            top.addView(text("核心 " + (i + 1), 9, MUTED, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            top.addView(text(String.format(Locale.CHINA, "%.0f%%", usage), 11, accent, true));
            cell.addView(top);
            long frequency = frequencies.length > i ? frequencies[i] : 0;
            if (frequency > 0) cell.addView(text(String.format(Locale.CHINA, "%.2f GHz", frequency / 1_000_000d), 8, MUTED, false));
            ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(100);
            bar.setProgress(Math.round(usage));
            bar.setProgressTintList(ColorStateList.valueOf(accent));
            bar.setProgressBackgroundTintList(ColorStateList.valueOf(Color.rgb(224, 231, 235)));
            LinearLayout.LayoutParams barLp = lpMatch(dp(8)); barLp.topMargin = dp(5); cell.addView(bar, barLp);
            LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1); cellLp.setMargins(dp(3), 0, dp(3), 0);
            cells.addView(cell, cellLp);
        }
        panel.addView(cells, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return panel;
    }

    private View deviceDiagnosticsPanel(MonitorSnapshot snapshot) {
        LinearLayout panel = horizontal(Color.rgb(248, 250, 252));
        panel.setPadding(dp(16), dp(12), dp(16), dp(12));
        panel.setBackground(roundStroke(Color.rgb(248, 250, 252), BORDER, 12));

        LinearLayout device = vertical(Color.TRANSPARENT);
        device.addView(text("设备与系统", 11, MUTED, true));
        device.addView(diagnosticValue(snapshot.deviceName));
        device.addView(diagnosticLine(snapshot.androidVersion));
        device.addView(diagnosticLine(snapshot.activeDisplayCount + " 块屏幕 · 已运行 " + uptimeText()));
        panel.addView(device, diagnosticColumn());

        LinearLayout cpu = vertical(Color.TRANSPARENT);
        cpu.addView(text("处理器", 11, MUTED, true));
        cpu.addView(diagnosticValue(snapshot.cpuModel));
        cpu.addView(diagnosticLine(snapshot.abi + " · " + snapshot.corePercents.length + " 核"));
        cpu.addView(diagnosticLine("App 堆 " + formatBytes(snapshot.appHeapUsed) + " / " + formatBytes(snapshot.appHeapLimit)));
        panel.addView(cpu, diagnosticColumn());

        LinearLayout power = vertical(Color.TRANSPARENT);
        power.addView(text("电源与温度", 11, MUTED, true));
        String battery = snapshot.batteryPercent < 0 ? "电池信息未公开" : snapshot.batteryPercent + "%" + (snapshot.charging ? " · 充电中" : "");
        power.addView(diagnosticValue(battery));
        power.addView(diagnosticLine(String.format(Locale.CHINA, "电池 %.1f°C · %.2f V", snapshot.batteryTemperature, snapshot.batteryVoltage)));
        power.addView(diagnosticLine(thermalText(snapshot.thermalStatus) + (snapshot.powerSave ? " · 省电模式" : "")));
        panel.addView(power, diagnosticColumn());

        LinearLayout network = vertical(Color.TRANSPARENT);
        network.addView(text("当前网络", 11, MUTED, true));
        network.addView(diagnosticValue(snapshot.networkType));
        network.addView(diagnosticLine(snapshot.ipAddress.isEmpty() ? "未取得 IPv4 地址" : snapshot.ipAddress));
        network.addView(diagnosticLine("本机处理 · 不上传监控数据"));
        panel.addView(network, diagnosticColumn());
        LinearLayout.LayoutParams lp = lpMatch(dp(142)); lp.setMargins(dp(8), dp(3), dp(8), dp(8)); panel.setLayoutParams(lp);
        return panel;
    }

    private LinearLayout.LayoutParams diagnosticColumn() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.setMargins(dp(6), 0, dp(10), 0);
        return lp;
    }

    private TextView diagnosticValue(String value) {
        TextView view = text(value, 13, TEXT, true);
        view.setSingleLine(true);
        view.setPadding(0, dp(8), 0, dp(5));
        return view;
    }

    private TextView diagnosticLine(String value) {
        TextView view = text(value, 10, MUTED, false);
        view.setSingleLine(true);
        return view;
    }

    private View memoryPressurePanel(MonitorSnapshot snapshot) {
        long used = snapshot.totalMemory - snapshot.availableMemory;
        int usedPercent = percent(used, snapshot.totalMemory);
        int accent = usedPercent > 90 ? Color.rgb(220, 70, 70) : usedPercent > 75 ? Color.rgb(230, 153, 45) : Color.rgb(82, 196, 111);
        LinearLayout panel = horizontal(Color.rgb(250, 251, 252));
        panel.setPadding(dp(14), dp(11), dp(14), dp(11));
        panel.setBackground(roundStroke(Color.rgb(250, 251, 252), BORDER, 12));

        LinearLayout pressure = vertical(Color.TRANSPARENT);
        pressure.addView(text("内存压力", 11, MUTED, true));
        TextView state = text(usedPercent > 90 ? "紧张" : usedPercent > 75 ? "偏高" : "良好", 16, accent, true);
        state.setPadding(0, dp(8), 0, dp(7));
        pressure.addView(state);
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100); bar.setProgress(usedPercent);
        bar.setProgressTintList(ColorStateList.valueOf(accent));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(Color.rgb(224, 231, 235)));
        pressure.addView(bar, lpMatch(dp(10)));
        TextView ratio = text(usedPercent + "% 已使用", 10, MUTED, false); ratio.setPadding(0, dp(5), 0, 0); pressure.addView(ratio);
        panel.addView(pressure, new LinearLayout.LayoutParams(dp(235), ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout physical = vertical(Color.TRANSPARENT);
        physical.setPadding(dp(18), 0, dp(12), 0);
        physical.addView(memoryLine("已安装物理内存", fixedGb(INSTALLED_PHYSICAL_MEMORY), true));
        physical.addView(memoryLine("系统可管理", fixedGb(snapshot.totalMemory), false));
        physical.addView(memoryLine("硬件/系统预留", fixedGb(snapshot.systemReservedMemory), false));
        physical.addView(memoryLine("当前已使用", fixedGb(used), false));
        panel.addView(physical, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));

        LinearLayout detail = vertical(Color.TRANSPARENT);
        detail.setPadding(dp(18), 0, 0, 0);
        detail.addView(memoryLine("App 内存", fixedGb(snapshot.appMemory), true));
        detail.addView(memoryLine("缓存文件", fixedGb(snapshot.cachedMemory), false));
        detail.addView(memoryLine("系统内核", fixedGb(snapshot.kernelMemory), false));
        detail.addView(memoryLine("交换分区", fixedGb(snapshot.swapUsed) + " / " + fixedGb(snapshot.swapTotal), false));
        panel.addView(detail, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        LinearLayout.LayoutParams panelLp = lpMatch(dp(126)); panelLp.setMargins(dp(8), dp(3), dp(8), dp(8)); panel.setLayoutParams(panelLp);
        return panel;
    }

    private View memoryLine(String label, String value, boolean bold) {
        LinearLayout row = horizontal(Color.TRANSPARENT);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(text(label + "：", 10, bold ? TEXT : MUTED, bold), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(text(value, 10, TEXT, bold));
        return row;
    }

    private String fixedGb(long bytes) {
        return String.format(Locale.CHINA, "%.2f GB", Math.max(0, bytes) / (1024d * 1024d * 1024d));
    }

    private void readMemoryDetails(MonitorSnapshot snapshot) {
        Map<String, Long> values = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/meminfo"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int colon = line.indexOf(':');
                if (colon <= 0) continue;
                String key = line.substring(0, colon);
                String number = line.substring(colon + 1).trim().split("\\s+")[0];
                values.put(key, Long.parseLong(number) * 1024L);
            }
        } catch (Exception ignored) { }
        snapshot.systemReservedMemory = Math.max(0, INSTALLED_PHYSICAL_MEMORY - snapshot.totalMemory);
        snapshot.swapTotal = values.getOrDefault("SwapTotal", CONFIGURED_SWAP_MEMORY);
        if (snapshot.swapTotal <= 0) snapshot.swapTotal = CONFIGURED_SWAP_MEMORY;
        snapshot.swapUsed = Math.max(0, snapshot.swapTotal - values.getOrDefault("SwapFree", snapshot.swapTotal));
        snapshot.cachedMemory = values.getOrDefault("Cached", 0L) + values.getOrDefault("SReclaimable", 0L);
        snapshot.appMemory = values.getOrDefault("Active(anon)", 0L) + values.getOrDefault("Inactive(anon)", 0L);
        snapshot.kernelMemory = values.getOrDefault("Slab", 0L) + values.getOrDefault("KernelStack", 0L) + values.getOrDefault("PageTables", 0L);
    }

    private void readDeviceDetails(MonitorSnapshot snapshot) {
        snapshot.deviceName = Build.MANUFACTURER + " " + Build.MODEL;
        snapshot.androidVersion = "Android " + Build.VERSION.RELEASE + " · API " + Build.VERSION.SDK_INT;
        snapshot.abi = Build.SUPPORTED_ABIS.length == 0 ? "未知" : Build.SUPPORTED_ABIS[0];
        snapshot.cpuModel = readCpuModel();
        Runtime runtime = Runtime.getRuntime();
        snapshot.appHeapUsed = runtime.totalMemory() - runtime.freeMemory();
        snapshot.appHeapLimit = runtime.maxMemory();

        Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery != null) {
            int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            snapshot.batteryPercent = level < 0 ? -1 : Math.round(level * 100f / Math.max(1, scale));
            snapshot.batteryTemperature = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f;
            snapshot.batteryVoltage = battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) / 1000f;
            int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN);
            snapshot.charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
        }
        PowerManager power = getSystemService(PowerManager.class);
        if (power != null) {
            snapshot.thermalStatus = power.getCurrentThermalStatus();
            snapshot.powerSave = power.isPowerSaveMode();
        }

        ConnectivityManager connectivity = getSystemService(ConnectivityManager.class);
        if (connectivity != null) {
            Network network = connectivity.getActiveNetwork();
            NetworkCapabilities capabilities = network == null ? null : connectivity.getNetworkCapabilities(network);
            if (capabilities != null) {
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) snapshot.networkType = "有线网络";
                else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) snapshot.networkType = "Wi-Fi";
                else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) snapshot.networkType = "移动网络";
                else snapshot.networkType = "其他网络";
            }
            LinkProperties properties = network == null ? null : connectivity.getLinkProperties(network);
            if (properties != null) for (LinkAddress address : properties.getLinkAddresses()) {
                String host = address.getAddress().getHostAddress();
                if (host != null && host.indexOf(':') < 0 && !host.startsWith("127.")) { snapshot.ipAddress = host; break; }
            }
        }
    }

    private String readCpuModel() {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/cpuinfo"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String lower = line.toLowerCase(Locale.ROOT);
                if (lower.startsWith("hardware") || lower.startsWith("model name")) {
                    int colon = line.indexOf(':');
                    if (colon >= 0) return line.substring(colon + 1).trim();
                }
            }
        } catch (Exception ignored) { }
        return Build.HARDWARE == null ? "未知处理器" : Build.HARDWARE;
    }

    private long[] readCoreFrequencies(int requestedCount) {
        int count = requestedCount > 0 ? requestedCount : Runtime.getRuntime().availableProcessors();
        long[] result = new long[count];
        for (int i = 0; i < count; i++) {
            File current = new File("/sys/devices/system/cpu/cpu" + i + "/cpufreq/scaling_cur_freq");
            File maximum = new File("/sys/devices/system/cpu/cpu" + i + "/cpufreq/cpuinfo_max_freq");
            result[i] = readLongFile(current);
            if (result[i] <= 0) result[i] = readLongFile(maximum);
        }
        return result;
    }

    private long readLongFile(File file) {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            return Long.parseLong(reader.readLine().trim());
        } catch (Exception ignored) { return 0; }
    }

    private String thermalText(int status) {
        if (status >= PowerManager.THERMAL_STATUS_CRITICAL) return "严重发热";
        if (status >= PowerManager.THERMAL_STATUS_SEVERE) return "温度偏高";
        if (status >= PowerManager.THERMAL_STATUS_MODERATE) return "轻微升温";
        return "温度正常";
    }

    private View processHeaderRow() {
        LinearLayout header = horizontal(Color.rgb(252, 253, 254));
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(38), 0, dp(8), 0);
        header.addView(text("进程名称", 11, MUTED, false), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        header.addView(text("内存", 11, MUTED, false), new LinearLayout.LayoutParams(dp(110), ViewGroup.LayoutParams.WRAP_CONTENT));
        header.addView(text("PID", 11, MUTED, false), new LinearLayout.LayoutParams(dp(80), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView result = text("清理判定", 11, MUTED, false); result.setGravity(Gravity.CENTER);
        header.addView(result, new LinearLayout.LayoutParams(dp(230), ViewGroup.LayoutParams.WRAP_CONTENT));
        return header;
    }

    private LinearLayout.LayoutParams weightedCard() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1); lp.setMargins(dp(4), 0, dp(4), 0); return lp;
    }

    private View processRow(ProcessEntry entry) {
        LinearLayout row = horizontal(Color.TRANSPARENT); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(8), dp(3), dp(8), dp(3)); row.setBackground(ripple(Color.TRANSPARENT, 8));
        TextView dot = text("●", 11, entry.cleanable ? Color.rgb(230, 153, 45) : TEAL, false); dot.setGravity(Gravity.CENTER); row.addView(dot, new LinearLayout.LayoutParams(dp(30), ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout labels = vertical(Color.TRANSPARENT); labels.setGravity(Gravity.CENTER_VERTICAL); labels.addView(text(entry.label, 13, TEXT, true));
        if (!entry.label.equals(entry.processName)) labels.addView(text(entry.processName, 9, MUTED, false));
        row.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        row.addView(text(entry.memoryBytes > 0 ? formatBytes(entry.memoryBytes) : "系统未公开", 12, MUTED, false), new LinearLayout.LayoutParams(dp(110), ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(text(entry.pid > 0 ? String.valueOf(entry.pid) : "—", 12, MUTED, false), new LinearLayout.LayoutParams(dp(80), ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout analysis = vertical(Color.TRANSPARENT);
        analysis.setGravity(Gravity.CENTER);
        String result = entry.cleaned ? "已清理" : entry.cleanable ? "可清理" : !entry.screenRole.isEmpty() ? entry.screenRole
                : entry.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE ? "双屏前台保护"
                : entry.protectedEntry || isSystemPackages(entry.packages) ? "系统保留" : entry.lastUsed > 0 ? "近期保留" : importanceText(entry.importance);
        int resultColor = entry.cleanable ? Color.rgb(190, 112, 20) : Color.rgb(14, 128, 112);
        TextView status = text(result, 11, resultColor, true); status.setGravity(Gravity.CENTER); analysis.addView(status);
        TextView reason = text(entry.analysisReason, 9, MUTED, false); reason.setGravity(Gravity.CENTER); analysis.addView(reason);
        row.addView(analysis, new LinearLayout.LayoutParams(dp(230), ViewGroup.LayoutParams.MATCH_PARENT));
        return row;
    }

    private void cleanBackgroundAnimated() {
        if (!"清理后台".equals(currentSection)) return;
        primaryToolbarButton.setEnabled(false);
        showMonitorLoading("正在分析可安全清理的后台应用…");
        if (monitorProgress != null) ObjectAnimator.ofFloat(monitorProgress, View.ROTATION, 0f, 360f).setDuration(850).start();
        handler.postDelayed(() -> { if (monitorStatus != null) monitorStatus.setText("正在释放后台占用…"); }, 550);
        new Thread(() -> {
            SystemClock.sleep(850);
            ActivityManager manager = getSystemService(ActivityManager.class);
            if (manager != null) {
                MonitorSnapshot analyzed = collectMonitorSnapshot();
                Set<String> handled = new HashSet<>();
                for (ProcessEntry entry : analyzed.processes) {
                    if (!entry.cleanable) continue;
                    for (String pkg : entry.packages) if (handled.add(pkg)) {
                        manager.killBackgroundProcesses(pkg);
                        cleanedPackages.put(pkg, System.currentTimeMillis());
                    }
                }
            }
            runOnUiThread(() -> {
                primaryToolbarButton.setEnabled(true);
                showActivityMonitor();
            });
        }, "mypad-cleaner").start();
    }

    private boolean isCleanable(ProcessEntry entry) {
        if (!entry.screenRole.isEmpty()) return false;
        if (entry.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE) return false;
        if (entry.packages.length == 0) return false;
        if (isProtectedPackages(entry.packages)) return false;
        for (String pkg : entry.packages) {
            try {
                ApplicationInfo info = getPackageManager().getApplicationInfo(pkg, 0);
                if ((info.flags & ApplicationInfo.FLAG_SYSTEM) != 0) return false;
            } catch (Exception ignored) { }
        }
        return true;
    }

    private boolean isSystemPackages(String[] packages) {
        for (String pkg : packages) try {
            ApplicationInfo info = getPackageManager().getApplicationInfo(pkg, 0);
            if ((info.flags & ApplicationInfo.FLAG_SYSTEM) != 0) return true;
        } catch (Exception ignored) { }
        return false;
    }

    private String processReason(ProcessEntry entry) {
        if (entry.cleaned) return "本次已释放后台缓存";
        if (!entry.screenRole.isEmpty()) return "正在该屏显示，禁止清理";
        if (entry.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE) return "主屏或副屏可见，禁止清理";
        if (entry.protectedEntry) return "系统关键组件，禁止清理";
        if (isSystemPackages(entry.packages)) return "Android 系统应用，保留";
        if (entry.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE) return "应用服务仍在工作，保留";
        if (entry.cleanable) return "仅后台缓存，可安全释放";
        if (entry.lastUsed > 0) return "近期使用，暂时保留";
        return "Android 当前不建议释放";
    }

    private boolean isProtectedPackages(String[] packages) {
        for (String pkg : packages) {
            if (pkg.equals(getPackageName()) || pkg.equals("android") || pkg.startsWith("com.android")
                    || pkg.startsWith("com.google.android") || pkg.startsWith("com.huanglong")
                    || pkg.startsWith("org.lineageos") || pkg.equals("com.newlink.featuredapps")
                    || pkg.startsWith("org.fcitx") || pkg.equals("com.thermal.manager")
                    || pkg.equals("com.kemi.dualwallpaper")) return true;
        }
        return false;
    }

    private String appLabel(String[] packages, String fallback) {
        PackageManager pm = getPackageManager();
        for (String pkg : packages) try { return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString(); } catch (Exception ignored) { }
        return fallback;
    }

    private String importanceText(int importance) {
        if (importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) return "当前使用";
        if (importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE) return "屏幕可见";
        if (importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE) return "系统服务";
        return "保留";
    }

    private float[] readCpuUsage() {
        List<long[]> first = readCpuTimes();
        SystemClock.sleep(220);
        List<long[]> second = readCpuTimes();
        int count = Math.min(first.size(), second.size());
        float[] result = new float[count];
        for (int i = 0; i < count; i++) {
            long total = second.get(i)[0] - first.get(i)[0];
            long idle = second.get(i)[1] - first.get(i)[1];
            result[i] = total <= 0 ? 0 : Math.max(0, Math.min(100, (total - idle) * 100f / total));
        }
        return result;
    }

    private List<long[]> readCpuTimes() {
        List<long[]> result = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/stat"))) {
            String line;
            while ((line = reader.readLine()) != null && line.startsWith("cpu")) {
                String[] parts = line.trim().split("\\s+");
                long total = 0;
                for (int i = 1; i < parts.length; i++) total += Long.parseLong(parts[i]);
                long idle = Long.parseLong(parts[4]) + (parts.length > 5 ? Long.parseLong(parts[5]) : 0);
                result.add(new long[]{total, idle});
            }
        } catch (Exception ignored) { }
        return result;
    }

    private int percent(long used, long total) { return total <= 0 ? 0 : (int) Math.round(used * 100d / total); }
    private String uptimeText() {
        long hours = SystemClock.elapsedRealtime() / 3600000L;
        return hours < 24 ? hours + " 小时" : (hours / 24) + " 天";
    }

    private void showSearchDialog() {
        EditText input = new EditText(this);
        input.setHint("输入文件名");
        input.setSingleLine(true);
        input.setPadding(dp(16), 0, dp(16), 0);
        input.setBackground(roundStroke(Color.rgb(248, 250, 252), BORDER, 12));
        LinearLayout wrap = vertical(SURFACE);
        wrap.setPadding(dp(22), dp(6), dp(22), 0);
        wrap.addView(input, lpMatch(dp(50)));
        new AlertDialog.Builder(this)
                .setTitle("搜索当前目录")
                .setView(wrap)
                .setNegativeButton("取消", null)
                .setPositiveButton("搜索", (dialog, which) -> searchCurrent(input.getText().toString()))
                .show();
    }

    private void searchCurrent(String query) {
        if (query == null || query.trim().isEmpty()) return;
        String lower = query.trim().toLowerCase(Locale.ROOT);
        File root = currentDirectory != null ? currentDirectory : Environment.getExternalStorageDirectory();
        titleView.setText("正在搜索：“" + query.trim() + "”…");
        new Thread(() -> {
            List<File> matches = new ArrayList<>();
            collectSearchMatches(root, lower, matches, 300);
            runOnUiThread(() -> {
                titleView.setText("搜索：“" + query.trim() + "”");
                renderFiles(matches);
                footerRight.setText("已搜索当前目录及所有子目录 · 最多显示 300 项");
            });
        }, "mypad-file-search").start();
    }

    private void collectSearchMatches(File directory, String query, List<File> matches, int limit) {
        if (directory == null || matches.size() >= limit) return;
        File[] children = directory.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (matches.size() >= limit) return;
            if (child.getName().toLowerCase(Locale.ROOT).contains(query)) matches.add(child);
            if (child.isDirectory()) collectSearchMatches(child, query, matches, limit);
        }
    }

    private void createFolderDialog() {
        if (currentDirectory == null || !currentDirectory.canWrite()) {
            message("当前位置不能新建文件夹");
            return;
        }
        EditText input = new EditText(this);
        input.setHint("文件夹名称");
        input.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle("新建文件夹")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("创建", (dialog, which) -> {
                    String value = input.getText().toString().trim();
                    if (!value.isEmpty() && new File(currentDirectory, value).mkdir()) refreshCurrent();
                    else message("创建失败");
                }).show();
    }

    private void sortMenu(View anchor) {
        android.widget.PopupMenu menu = new android.widget.PopupMenu(this, anchor);
        menu.getMenu().add("按修改时间");
        menu.getMenu().add("按名称");
        menu.setOnMenuItemClickListener(item -> {
            List<File> files = filesIn(currentDirectory);
            if ("按名称".contentEquals(item.getTitle())) {
                files.sort(Comparator.comparing(file -> file.getName().toLowerCase(Locale.ROOT)));
            }
            renderFiles(files);
            return true;
        });
        menu.show();
    }

    private void refreshCurrent() {
        if ("最近使用".equals(currentSection)) showRecent();
        else if ("收藏".equals(currentSection)) showFavorites();
        else if ("全部应用".equals(currentSection)) showInstalledApps();
        else if ("双屏管理".equals(currentSection)) showDualScreenManager();
        else if ("局域网文件".equals(currentSection)) showLanFiles();
        else if ("文件分发".equals(currentSection)) showFileDistribution();
        else if ("清理后台".equals(currentSection)) refreshActivityMonitor(true);
        else if (currentDirectory != null) showDirectory(currentDirectory, currentSection);
    }

    private String displayPath(File directory, String label) {
        if (navigationRoot != null) {
            try {
                String current = directory.getCanonicalPath();
                String root = navigationRoot.getCanonicalPath();
                String prefix = "下载".equals(label) ? "本机存储  ›  下载" : label;
                if (current.equals(root)) return prefix;
                if (current.startsWith(root + File.separator)) {
                    String relative = current.substring(root.length() + 1).replace(File.separatorChar, '›');
                    return prefix + "  ›  " + relative.replace("›", "  ›  ");
                }
            } catch (Exception ignored) { }
        }
        String path = directory.getAbsolutePath().replace("/storage/emulated/0", "本机存储");
        return path.replace('/', '›').replace("›", "  ›  ");
    }

    private String iconFor(File file) {
        if (file.isDirectory()) return "▰";
        String mime = mimeFor(file);
        if (mime.startsWith("image/")) return "▧";
        if (mime.startsWith("video/")) return "▶";
        if (mime.startsWith("audio/")) return "♫";
        if (mime.equals("application/pdf")) return "PDF";
        if (mime.equals("application/vnd.android.package-archive")) return "APK";
        return "▤";
    }

    private String mimeFor(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot < name.length() - 1) {
            String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
            if ("apk".equals(ext)) return "application/vnd.android.package-archive";
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (mime != null) return mime;
        }
        return "application/octet-stream";
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024d;
        if (kb < 1024) return String.format(Locale.CHINA, "%.1f KB", kb);
        double mb = kb / 1024d;
        if (mb < 1024) return String.format(Locale.CHINA, "%.1f MB", mb);
        return String.format(Locale.CHINA, "%.1f GB", mb / 1024d);
    }

    private LinearLayout vertical(int color) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(color);
        return layout;
    }

    private LinearLayout horizontal(int color) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setBackgroundColor(color);
        return layout;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private TextView centerText(String value, float size, int color, boolean bold) {
        TextView view = text(value, size, color, bold);
        view.setGravity(Gravity.CENTER);
        return view;
    }

    private Button smallActionButton(String value, int color) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(value);
        button.setTextSize(12);
        button.setTextColor(color);
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setBackground(ripple(Color.rgb(237, 242, 245), 11));
        return button;
    }

    private Button iconButton(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextSize(21);
        button.setTextColor(TEXT);
        button.setPadding(0, 0, 0, dp(2));
        button.setBackground(ripple(Color.rgb(237, 242, 245), 12));
        return button;
    }

    private View navigationLabel(String value, int color, boolean centered) {
        LinearLayout control = horizontal(Color.TRANSPARENT);
        control.setGravity(centered ? Gravity.CENTER : Gravity.CENTER_VERTICAL);
        control.setPadding(centered ? dp(8) : 0, 0, centered ? dp(8) : 0, 0);
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(new MacChevronDrawable(false, color));
        control.addView(icon, new LinearLayout.LayoutParams(dp(20), dp(20)));
        TextView label = text(value, centered ? 13 : 14, color, !centered);
        label.setIncludeFontPadding(false);
        label.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(24));
        labelLp.leftMargin = dp(4);
        control.addView(label, labelLp);
        return control;
    }

    private Button toolbarButton(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextSize(17);
        button.setTextColor(TEXT);
        button.setPadding(0, 0, 0, 0);
        button.setBackground(ripple(Color.rgb(237, 242, 245), 11));
        return button;
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private GradientDrawable roundStroke(int color, int stroke, int radiusDp) {
        GradientDrawable drawable = round(color, radiusDp);
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private RippleDrawable ripple(int color, int radiusDp) {
        return new RippleDrawable(ColorStateList.valueOf(Color.argb(35, 22, 119, 255)), round(color, radiusDp), null);
    }

    private GradientDrawable lineDrawable() {
        GradientDrawable line = new GradientDrawable();
        line.setColor(BORDER);
        line.setSize(dp(1), dp(1));
        return line;
    }

    private LinearLayout.LayoutParams lpMatch(int height) {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void message(String value) {
        Toast.makeText(this, value, Toast.LENGTH_SHORT).show();
    }

    private static final class ProcessEntry {
        int pid;
        int importance;
        long memoryBytes;
        String processName = "";
        String label = "";
        String screenRole = "";
        String analysisReason = "";
        String[] packages = new String[0];
        boolean cleanable;
        long lastUsed;
        boolean protectedEntry;
        boolean cleaned;
    }

    private static final class MonitorSnapshot {
        float cpuPercent;
        long totalMemory;
        long availableMemory;
        long totalStorage;
        long availableStorage;
        boolean lowMemory;
        boolean usageAccess;
        String deviceName = "";
        String androidVersion = "";
        String cpuModel = "";
        String abi = "";
        String networkType = "未连接";
        String ipAddress = "";
        int batteryPercent = -1;
        float batteryTemperature;
        float batteryVoltage;
        boolean charging;
        boolean powerSave;
        int thermalStatus;
        long appHeapUsed;
        long appHeapLimit;
        int displayCount;
        int activeDisplayCount;
        int externalDisplayId = -1;
        final Map<String, String> screenApps = new HashMap<>();
        final Map<String, String> screenPackages = new HashMap<>();
        float[] corePercents = new float[0];
        long[] coreFrequencies = new long[0];
        long systemReservedMemory;
        long swapTotal;
        long swapUsed;
        long cachedMemory;
        long appMemory;
        long kernelMemory;
        final List<ProcessEntry> processes = new ArrayList<>();
    }
}
