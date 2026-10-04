package com.myreader.app;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Environment;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

final class BookFilePicker {
    interface OnBookSelected {
        void onBookSelected(File file);
    }

    private final Activity activity;
    private final OnBookSelected onBookSelected;
    private final ArrayList<File> storageRoots = new ArrayList<>();
    private Dialog dialog;
    private LinearLayout fileList;
    private TextView locationText;
    private File currentDirectory;
    private int loadGeneration;

    BookFilePicker(Activity activity, OnBookSelected onBookSelected) {
        this.activity = activity;
        this.onBookSelected = onBookSelected;
    }

    void show() {
        findStorageRoots();
        dialog = new Dialog(activity, android.R.style.Theme_Material_Light_NoActionBar);
        LinearLayout page = new LinearLayout(activity);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Color.parseColor("#F7F1E7"));
        dialog.setContentView(page);

        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(8), 0, dp(8), 0);
        header.setBackgroundColor(Color.parseColor("#1F3B31"));
        page.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        TextView back = text("←", 28, Color.WHITE, Typeface.NORMAL);
        back.setGravity(Gravity.CENTER);
        back.setContentDescription("返回上级目录");
        back.setOnClickListener(view -> navigateBack());
        header.addView(back, new LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.MATCH_PARENT));

        TextView title = text("导入书籍", 18, Color.WHITE, Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView close = text("×", 28, Color.WHITE, Typeface.NORMAL);
        close.setGravity(Gravity.CENTER);
        close.setContentDescription("关闭文件浏览器");
        close.setOnClickListener(view -> dialog.dismiss());
        header.addView(close, new LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.MATCH_PARENT));

        locationText = text("", 13, Color.parseColor("#59665D"), Typeface.NORMAL);
        locationText.setSingleLine(true);
        locationText.setEllipsize(TextUtils.TruncateAt.START);
        locationText.setPadding(dp(20), dp(12), dp(20), dp(12));
        page.addView(locationText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scrollView = new ScrollView(activity);
        scrollView.setFillViewport(true);
        page.addView(scrollView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        fileList = new LinearLayout(activity);
        fileList.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(fileList, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        dialog.setOnKeyListener((unused, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_BACK) {
                return false;
            }
            if (event.getAction() == KeyEvent.ACTION_UP) {
                navigateBack();
            }
            return true;
        });
        dialog.setOnDismissListener(unused -> loadGeneration++);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.parseColor("#F7F1E7")));
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
        showStorageRoots();
    }

    private void findStorageRoots() {
        addStorageRoot(Environment.getExternalStorageDirectory());
        File[] appDirectories = activity.getExternalFilesDirs(null);
        if (appDirectories == null) {
            return;
        }
        for (File appDirectory : appDirectories) {
            if (appDirectory == null) {
                continue;
            }
            String path = appDirectory.getAbsolutePath();
            int appData = path.indexOf("/Android/data/");
            if (appData > 0) {
                addStorageRoot(new File(path.substring(0, appData)));
            }
        }
    }

    private void addStorageRoot(File root) {
        if (root == null || !root.isDirectory() || !root.canRead()) {
            return;
        }
        for (File existing : storageRoots) {
            if (existing.equals(root)) {
                return;
            }
        }
        storageRoots.add(root);
    }

    private void showStorageRoots() {
        loadGeneration++;
        currentDirectory = null;
        locationText.setText("存储位置");
        fileList.removeAllViews();
        for (int i = 0; i < storageRoots.size(); i++) {
            File root = storageRoots.get(i);
            addRow(i == 0 ? "手机存储" : "SD 卡", root.getAbsolutePath(), "▸",
                    () -> openDirectory(root));
        }
        if (storageRoots.isEmpty()) {
            showMessage("无法读取手机存储");
        }
    }

    private void openDirectory(File directory) {
        currentDirectory = directory;
        locationText.setText(directory.getAbsolutePath());
        fileList.removeAllViews();
        showMessage("正在读取…");
        int generation = ++loadGeneration;
        new Thread(() -> {
            List<File> entries = listVisibleEntries(directory);
            activity.runOnUiThread(() -> {
                if (generation != loadGeneration || !dialog.isShowing()) {
                    return;
                }
                fileList.removeAllViews();
                if (entries == null) {
                    showMessage("无法读取此文件夹");
                } else if (entries.isEmpty()) {
                    showMessage("此文件夹没有可导入的书籍");
                } else {
                    for (File entry : entries) {
                        if (entry.isDirectory()) {
                            addRow(entry.getName(), "文件夹", "▸", () -> openDirectory(entry));
                        } else {
                            String extension = entry.getName().substring(entry.getName().lastIndexOf('.') + 1)
                                    .toUpperCase(Locale.ROOT);
                            addRow(entry.getName(), Formatter.formatFileSize(activity, entry.length()),
                                    extension, () -> {
                                        dialog.dismiss();
                                        onBookSelected.onBookSelected(entry);
                                    });
                        }
                    }
                }
            });
        }, "MyReader-file-browser").start();
    }

    private void navigateBack() {
        if (currentDirectory == null) {
            dialog.dismiss();
            return;
        }
        for (File root : storageRoots) {
            if (root.equals(currentDirectory)) {
                showStorageRoots();
                return;
            }
        }
        File parent = currentDirectory.getParentFile();
        if (parent == null) {
            showStorageRoots();
        } else {
            openDirectory(parent);
        }
    }

    private void addRow(String title, String subtitle, String symbol, Runnable action) {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(20), 0, dp(20), 0);
        row.setOnClickListener(view -> action.run());
        fileList.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(68)));

        TextView icon = text(symbol, "▸".equals(symbol) ? 25 : 12,
                Color.parseColor("#2B6650"), Typeface.BOLD);
        icon.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(icon, new LinearLayout.LayoutParams(dp(58), ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout labels = new LinearLayout(activity);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        TextView name = text(title, 16, Color.parseColor("#26322C"), Typeface.NORMAL);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        labels.addView(name);

        TextView detail = text(subtitle, 12, Color.parseColor("#687069"), Typeface.NORMAL);
        detail.setSingleLine(true);
        detail.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(detail);

        View divider = new View(activity);
        divider.setBackgroundColor(Color.parseColor("#E2DDD1"));
        fileList.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
    }

    private void showMessage(String message) {
        TextView empty = text(message, 15, Color.parseColor("#687069"), Typeface.NORMAL);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(20), dp(56), dp(20), dp(56));
        fileList.addView(empty);
    }

    private TextView text(String value, int sp, int color, int style) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setTypeface(null, style);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    static List<File> listVisibleEntries(File directory) {
        File[] children = directory.listFiles();
        if (children == null) {
            return null;
        }
        ArrayList<File> visible = new ArrayList<>();
        for (File child : children) {
            if (child.isDirectory() && child.canRead()) {
                visible.add(child);
            } else if (child.isFile() && isSupportedFileName(child.getName())) {
                visible.add(child);
            }
        }
        Collator collator = Collator.getInstance(Locale.CHINA);
        Collections.sort(visible, (left, right) -> {
            if (left.isDirectory() != right.isDirectory()) {
                return left.isDirectory() ? -1 : 1;
            }
            return collator.compare(left.getName(), right.getName());
        });
        return visible;
    }

    static boolean isSupportedFileName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".txt") || lower.endsWith(".epub") || lower.endsWith(".pdf");
    }
}
