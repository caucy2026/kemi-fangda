package com.kemi.mypad;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.ImageView;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class DestinationPickerActivity extends Activity {
    static final String ACTION_COMPLETED = "com.kemi.mypad.FILE_OPERATION_COMPLETED";
    static final String EXTRA_PATHS = "paths";
    static final String EXTRA_MOVE = "move";
    static final String EXTRA_COMPLETED = "completed";
    private static final int TEXT = Color.rgb(31, 41, 51);
    private static final int MUTED = Color.rgb(104, 118, 129);
    private static final int BLUE = Color.rgb(22, 119, 255);
    private static final int TEAL = Color.rgb(38, 169, 156);
    private static final int BORDER = Color.rgb(218, 226, 231);
    private static final String PICKER_PREFS = "destination_picker";
    private static final String LAST_COPY_DIRECTORY = "last_copy_directory";
    private static final String LAST_MOVE_DIRECTORY = "last_move_directory";
    private final List<File> sources = new ArrayList<>();
    private LinearLayout list;
    private TextView path;
    private TextView status;
    private Button confirm;
    private File current;
    private boolean move;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(248, 250, 252));
        getWindow().setNavigationBarColor(Color.rgb(248, 250, 252));
        ArrayList<String> paths = getIntent().getStringArrayListExtra(EXTRA_PATHS);
        if (paths != null) for (String value : paths) {
            File file = new File(value);
            if (file.exists()) sources.add(file);
        }
        move = getIntent().getBooleanExtra(EXTRA_MOVE, false);
        View content = buildUi();
        content.setElevation(dp(18));
        setContentView(content);
        Window window = getWindow();
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
        android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(metrics);
        window.setLayout(Math.round(metrics.widthPixels * .68f),
                Math.round(metrics.heightPixels * .64f));
        window.setGravity(Gravity.CENTER);
        restoreLastDirectory();
    }

    private View buildUi() {
        LinearLayout root = vertical(Color.WHITE);
        root.setBackground(round(Color.WHITE, 16));
        root.setClipToOutline(true);
        LinearLayout top = horizontal(Color.TRANSPARENT);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(22), 0, dp(22), 0);
        View back = navigationButton("返回");
        back.setOnClickListener(v -> navigateBack());
        top.addView(back, new LinearLayout.LayoutParams(dp(120), dp(50)));
        TextView title = text(move ? "选择剪切目标文件夹" : "选择复制目标文件夹", 20, TEXT, true);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.leftMargin = dp(20); top.addView(title, titleLp);
        path = text("选择位置", 14, MUTED, false);
        path.setGravity(Gravity.CENTER_VERTICAL);
        path.setPadding(dp(18), 0, dp(18), 0);
        path.setBackground(round(Color.WHITE, 12));
        LinearLayout.LayoutParams pathLp = new LinearLayout.LayoutParams(0, dp(50), 1); pathLp.setMargins(dp(22), 0, dp(22), 0); top.addView(path, pathLp);
        Button cancel = button("取消", MUTED); cancel.setOnClickListener(v -> finish()); top.addView(cancel, new LinearLayout.LayoutParams(dp(100), dp(50)));
        root.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(76)));

        ScrollView scroll = new ScrollView(this);
        list = vertical(Color.WHITE);
        list.setPadding(dp(20), dp(12), dp(20), dp(12));
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout bottom = horizontal(Color.rgb(248, 250, 252));
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setPadding(dp(24), 0, dp(24), 0);
        status = text(sources.size() + " 个项目等待" + (move ? "剪切" : "复制"), 13, MUTED, false);
        bottom.addView(status, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        confirm = button(move ? "剪切到此文件夹" : "复制到此文件夹", move ? Color.rgb(213, 123, 23) : BLUE);
        confirm.setEnabled(false);
        confirm.setOnClickListener(v -> transfer());
        bottom.addView(confirm, new LinearLayout.LayoutParams(dp(230), dp(52)));
        root.addView(bottom, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(78)));
        return root;
    }

    private void showRoots() {
        current = null;
        path.setText("可操作位置");
        confirm.setEnabled(false);
        list.removeAllViews();
        List<File> roots = FileOperations.destinationRoots(this);
        if (roots.isEmpty()) {
            TextView empty = text("没有可写入的目标位置", 17, MUTED, false); empty.setGravity(Gravity.CENTER); list.addView(empty, match(dp(180)));
            return;
        }
        for (File root : roots) {
            String name = root.getAbsolutePath().contains("/Download") ? "下载" : "USB 移动盘";
            addFolderRow(root, name, root.getAbsolutePath());
        }
    }

    private void restoreLastDirectory() {
        String key = move ? LAST_MOVE_DIRECTORY : LAST_COPY_DIRECTORY;
        String saved = getSharedPreferences(PICKER_PREFS, MODE_PRIVATE).getString(key, "");
        if (!saved.isEmpty()) {
            File directory = new File(saved);
            if (directory.isDirectory() && directory.canWrite() && isDestinationDirectory(directory)) {
                showDirectory(directory);
                return;
            }
        }
        showRoots();
    }

    private boolean isDestinationDirectory(File directory) {
        for (File root : FileOperations.destinationRoots(this)) try {
            String candidate = directory.getCanonicalPath();
            String allowed = root.getCanonicalPath();
            if (candidate.equals(allowed) || candidate.startsWith(allowed + File.separator)) return true;
        } catch (Exception ignored) { }
        return false;
    }

    private void showDirectory(File directory) {
        current = directory;
        path.setText(displayPath(directory));
        confirm.setEnabled(directory.canWrite());
        list.removeAllViews();
        File[] children = directory.listFiles(File::isDirectory);
        if (children == null || children.length == 0) {
            TextView empty = text("此文件夹没有子文件夹", 16, MUTED, false); empty.setGravity(Gravity.CENTER); list.addView(empty, match(dp(150)));
            return;
        }
        Arrays.sort(children, (a, b) -> a.getName().toLowerCase(Locale.ROOT).compareTo(b.getName().toLowerCase(Locale.ROOT)));
        for (File child : children) addFolderRow(child, child.getName(), child.canWrite() ? "可写入" : "只读");
    }

    private void addFolderRow(File directory, String name, String detail) {
        LinearLayout row = horizontal(Color.WHITE);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(6), dp(16), dp(6));
        row.setBackground(ripple(Color.WHITE, 12));
        ImageView icon = new ImageView(this); icon.setPadding(dp(2), dp(2), dp(2), dp(2));
        icon.setImageDrawable(new FinderIconDrawable(FinderIconDrawable.Kind.FOLDER));
        row.addView(icon, new LinearLayout.LayoutParams(dp(50), dp(46)));
        LinearLayout copy = vertical(Color.TRANSPARENT); copy.setGravity(Gravity.CENTER_VERTICAL);
        copy.addView(text(name, 15, TEXT, true)); copy.addView(text(detail, 10, MUTED, false));
        LinearLayout.LayoutParams copyLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1); copyLp.leftMargin = dp(14); row.addView(copy, copyLp);
        TextView enter = text("›", 28, BLUE, false); enter.setGravity(Gravity.CENTER); row.addView(enter, new LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.MATCH_PARENT));
        row.setOnClickListener(v -> showDirectory(directory));
        list.addView(row, match(dp(68)));
    }

    private void transfer() {
        if (current == null || sources.isEmpty()) return;
        confirm.setEnabled(false);
        confirm.setText(move ? "正在剪切…" : "正在复制…");
        status.setText("正在处理，请不要拔出 U 盘…");
        ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        list.addView(progress, 0, match(dp(8)));
        File destination = current;
        new Thread(() -> {
            int completed = FileOperations.transfer(sources, destination, move);
            runOnUiThread(() -> {
                if (completed > 0) {
                    getSharedPreferences(PICKER_PREFS, MODE_PRIVATE).edit()
                            .putString(move ? LAST_MOVE_DIRECTORY : LAST_COPY_DIRECTORY,
                                    destination.getAbsolutePath()).apply();
                }
                sendBroadcast(new Intent(ACTION_COMPLETED).setPackage(getPackageName()).putExtra(EXTRA_COMPLETED, completed));
                finish();
            });
        }, "kemi-pads-transfer").start();
    }

    private void navigateBack() {
        if (current == null) { finish(); return; }
        File parent = current.getParentFile();
        boolean withinRoot = false;
        for (File root : FileOperations.destinationRoots(this)) try {
            String currentPath = current.getCanonicalPath();
            String rootPath = root.getCanonicalPath();
            if (currentPath.equals(rootPath)) { showRoots(); return; }
            if (parent != null && parent.getCanonicalPath().startsWith(rootPath)) { withinRoot = true; break; }
        } catch (Exception ignored) { }
        if (withinRoot && parent != null) showDirectory(parent); else showRoots();
    }

    @Override public void onBackPressed() { navigateBack(); }

    private String displayPath(File file) {
        String path = file.getAbsolutePath().replace("/storage/emulated/0/Download", "下载");
        return path.replace('/', '›').replace("›", "  ›  ");
    }

    private LinearLayout vertical(int color) { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); view.setBackgroundColor(color); return view; }
    private LinearLayout horizontal(int color) { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.HORIZONTAL); view.setBackgroundColor(color); return view; }
    private TextView text(String value, float size, int color, boolean bold) { TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color); view.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL); return view; }
    private Button button(String value, int color) { Button view = new Button(this); view.setAllCaps(false); view.setText(value); view.setTextSize(14); view.setTextColor(color); view.setBackground(ripple(Color.rgb(237, 242, 245), 12)); return view; }
    private View navigationButton(String value) {
        LinearLayout control = horizontal(Color.TRANSPARENT);
        control.setGravity(Gravity.CENTER);
        control.setPadding(dp(10), 0, dp(10), 0);
        control.setBackground(ripple(Color.rgb(237, 242, 245), 12));
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(new MacChevronDrawable(false, TEXT));
        control.addView(icon, new LinearLayout.LayoutParams(dp(20), dp(20)));
        TextView label = text(value, 14, TEXT, false);
        label.setIncludeFontPadding(false);
        label.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(24));
        labelLp.leftMargin = dp(4);
        control.addView(label, labelLp);
        return control;
    }
    private LinearLayout.LayoutParams match(int height) { return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height); }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private GradientDrawable round(int color, int radius) { GradientDrawable value = new GradientDrawable(); value.setColor(color); value.setCornerRadius(dp(radius)); value.setStroke(dp(1), BORDER); return value; }
    private RippleDrawable ripple(int color, int radius) { return new RippleDrawable(ColorStateList.valueOf(Color.argb(35, 22, 119, 255)), round(color, radius), null); }
}
