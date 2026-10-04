package com.myreader.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Environment;
import android.provider.Settings;
import android.text.Html;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.AlignmentSpan;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.ReplacementSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.SubscriptSpan;
import android.text.style.SuperscriptSpan;
import android.text.style.TypefaceSpan;
import android.text.style.UnderlineSpan;
import android.util.Base64;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

public class MainActivity extends Activity {
    private static final String LOG_TAG = "MyReaderPaging";
    private static final boolean DEBUG_PAGE_LOGS = true;
    private static final int REQUEST_READ_STORAGE = 1002;
    private static final int REQUEST_ALL_FILES_ACCESS = 1003;
    private static final String PREF_NAME = "myreader_prefs";
    private static final String PREF_BOOKS = "books_json_paths";
    private static final String PREF_SHELF_SORT_MODE = "shelf_sort_mode";
    private static final String SHELF_SORT_RECENT = "recent";
    private static final String SHELF_SORT_TITLE = "title";
    private static final int TXT_FORWARD_BYTES = 16 * 1024;
    private static final int TXT_FORWARD_MAX_BYTES = 256 * 1024;
    private static final int TXT_BACKWARD_INITIAL_BYTES = 8 * 1024;
    private static final int TXT_BACKWARD_MAX_BYTES = 256 * 1024;
    private static final int EPUB_FORWARD_CHARS = 80 * 1000;
    private static final int EPUB_BACKWARD_CHARS = 100 * 1000;
    private static final int IMAGE_PAGE_MAX_TEXT_CHARS = 80;
    private static final Pattern TXT_CHAPTER_MARKER_PATTERN = Pattern.compile(
            "第\\s*[0-9０-９零〇一二三四五六七八九十百千万两壹贰叁肆伍陆柒捌玖拾佰仟萬]+\\s*[章节回卷部]"
    );

    private final ArrayList<Book> books = new ArrayList<>();
    private final Handler handler = new Handler();
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.CHINA);

    private SharedPreferences preferences;
    private ReaderPrefs readerPrefs;
    private String shelfSortMode = SHELF_SORT_RECENT;
    private LinearLayout shelfList;

    private Book currentBook;
    private BookSource currentSource;
    private Page currentPage;
    private FrameLayout readerRoot;
    private TextView readerText;
    private PdfReaderView pdfReaderView;
    private LinearLayout bottomBar;
    private TextView bottomTimeText;
    private TextView bottomBatteryText;
    private TextView bottomProgressText;
    private boolean loadingPage;
    private boolean pendingReaderReflow;
    private int systemShadeDialogCount;
    private final Runnable pdfProgressSaver = new Runnable() {
        @Override
        public void run() {
            if (currentBook != null && currentBook.isPdf()) {
                saveBooks();
            }
        }
    };

    private final Runnable timeTicker = new Runnable() {
        @Override
        public void run() {
            updateBottomTime();
            updateBottomBattery();
            if (bottomBar != null && bottomBar.getVisibility() == View.VISIBLE) {
                handler.postDelayed(this, 30 * 1000L);
            }
        }
    };

    private final Runnable screenTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (currentBook != null && readerPrefs.screenTimeoutMinutes > 0) {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                if (readerRoot != null) {
                    readerRoot.setKeepScreenOn(false);
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        readerPrefs = ReaderPrefs.load(preferences);
        shelfSortMode = normalizeShelfSortMode(preferences.getString(PREF_SHELF_SORT_MODE, SHELF_SORT_RECENT));
        loadBooks();
        showShelf();
        if (savedInstanceState != null) {
            String openBookId = savedInstanceState.getString("open_book_id");
            if (openBookId != null) {
                for (Book book : books) {
                    if (openBookId.equals(book.id)) {
                        openBook(book);
                        break;
                    }
                }
            }
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        saveCurrentProgress();
        if (currentBook != null) {
            outState.putString("open_book_id", currentBook.id);
        }
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (currentBook == null) {
            showShelf();
        } else if (!currentBook.isPdf() && readerRoot != null) {
            if (loadingPage) {
                pendingReaderReflow = true;
            } else {
                readerRoot.post(() -> applyReaderStyle(true));
            }
        }
    }

    @Override
    protected void onPause() {
        saveCurrentProgress();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        closeCurrentSource();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (currentBook != null) {
            closeReaderToShelf();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ALL_FILES_ACCESS) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
                showBookFileBrowser();
            } else {
                Toast.makeText(this, "未授予文件访问权限", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_READ_STORAGE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                showBookFileBrowser();
            } else {
                Toast.makeText(this, "未授予文件访问权限", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && currentBook != null && systemShadeDialogCount == 0) {
            enterImmersiveMode();
        }
    }

    private void showShelf() {
        closeCurrentSource();
        resetReaderWindowControls();
        currentBook = null;
        currentPage = null;
        loadingPage = false;
        pendingReaderReflow = false;
        exitImmersiveMode();
        getWindow().setStatusBarColor(Color.parseColor("#1F3B31"));
        getWindow().setNavigationBarColor(Color.parseColor("#F7F1E7"));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#F7F1E7"));
        setContentView(root);

        LinearLayout topBar = new LinearLayout(this);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(22), dp(20), dp(22), dp(18));
        topBar.setBackgroundColor(Color.parseColor("#1F3B31"));
        root.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout titleBlock = new LinearLayout(this);
        titleBlock.setOrientation(LinearLayout.VERTICAL);
        topBar.addView(titleBlock, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = makeText("书架", 28, Color.parseColor("#FFFDF6"), Typeface.BOLD);
        titleBlock.addView(title);

        String countText = books.isEmpty() ? "导入 txt、epub 或 pdf 后开始阅读" : "共 " + books.size() + " 本书";
        TextView subtitle = makeText(countText, 14, Color.parseColor("#D8E3DA"), Typeface.NORMAL);
        subtitle.setPadding(0, dp(5), 0, 0);
        titleBlock.addView(subtitle);

        TextView importButton = makePillButton("+ 导入书籍", Color.TRANSPARENT, Color.parseColor("#F5D087"));
        setRoundedBackground(importButton, Color.TRANSPARENT, Color.parseColor("#D7B86B"), dp(8));
        importButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openImporter();
            }
        });
        topBar.addView(importButton, new LinearLayout.LayoutParams(dp(118), dp(44)));

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        root.addView(scrollView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));

        shelfList = new LinearLayout(this);
        shelfList.setOrientation(LinearLayout.VERTICAL);
        shelfList.setPadding(dp(16), dp(18), dp(16), dp(28));
        scrollView.addView(shelfList, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        renderShelfBooks();
    }

    private void renderShelfBooks() {
        if (shelfList == null) {
            return;
        }
        shelfList.removeAllViews();
        if (books.isEmpty()) {
            LinearLayout empty = new LinearLayout(this);
            empty.setOrientation(LinearLayout.VERTICAL);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(22), dp(84), dp(22), dp(84));
            setRoundedBackground(empty, Color.parseColor("#FFFDF7"), Color.parseColor("#E5DDCF"), dp(8));
            shelfList.addView(empty, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));

            TextView emptyTitle = makeText("书架还是空的", 22, Color.parseColor("#26322C"), Typeface.BOLD);
            emptyTitle.setGravity(Gravity.CENTER);
            empty.addView(emptyTitle);

            TextView emptyHint = makeText("点右上角“导入书籍”，选择手机本地的 txt、epub 或 pdf 文件。", 15, Color.parseColor("#6A716C"), Typeface.NORMAL);
            emptyHint.setGravity(Gravity.CENTER);
            emptyHint.setPadding(0, dp(12), 0, dp(22));
            emptyHint.setLineSpacing(dp(4), 1f);
            empty.addView(emptyHint);

            TextView importNow = makePillButton("导入书籍", Color.parseColor("#1F3B31"), Color.WHITE);
            importNow.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openImporter();
                }
            });
            empty.addView(importNow, new LinearLayout.LayoutParams(dp(132), dp(46)));
            return;
        }

        LinearLayout summary = new LinearLayout(this);
        summary.setGravity(Gravity.CENTER_VERTICAL);
        summary.setPadding(dp(2), 0, dp(2), dp(12));
        shelfList.addView(summary, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView summaryTitle = makeText(shelfSortMode.equals(SHELF_SORT_TITLE) ? "书名排序" : "最近打开", 16, Color.parseColor("#26322C"), Typeface.BOLD);
        summary.addView(summaryTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout sortSwitch = new LinearLayout(this);
        sortSwitch.setGravity(Gravity.CENTER_VERTICAL);
        summary.addView(sortSwitch);

        sortSwitch.addView(makeShelfSortButton("最近打开", SHELF_SORT_RECENT), new LinearLayout.LayoutParams(dp(76), dp(34)));
        LinearLayout.LayoutParams titleSortParams = new LinearLayout.LayoutParams(dp(76), dp(34));
        titleSortParams.setMargins(dp(8), 0, 0, 0);
        sortSwitch.addView(makeShelfSortButton("书名排序", SHELF_SORT_TITLE), titleSortParams);

        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        shelfList.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        int columns = shelfColumnCount();
        ArrayList<Book> sortedBooks = sortedShelfBooks();
        for (int i = 0; i < sortedBooks.size(); i += columns) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            grid.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));

            for (int column = 0; column < columns; column++) {
                int index = i + column;
                LinearLayout.LayoutParams itemParams = new LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f
                );
                int left = column == 0 ? 0 : dp(6);
                int right = column == columns - 1 ? 0 : dp(6);
                itemParams.setMargins(left, 0, right, dp(16));
                if (index < sortedBooks.size()) {
                    row.addView(makeBookGridItem(sortedBooks.get(index)), itemParams);
                } else {
                    row.addView(new View(this), itemParams);
                }
            }
        }
    }

    private int shelfColumnCount() {
        float widthDp = getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density;
        if (widthDp >= 520f) {
            return 4;
        }
        if (widthDp >= 360f) {
            return 3;
        }
        return 2;
    }

    private TextView makeShelfSortButton(String label, final String mode) {
        boolean selected = mode.equals(shelfSortMode);
        TextView button = makeText(label, 12, selected ? Color.WHITE : Color.parseColor("#566158"), Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setClickable(true);
        setRoundedBackground(
                button,
                selected ? Color.parseColor("#1F3B31") : Color.TRANSPARENT,
                selected ? 0 : Color.parseColor("#D6CFC2"),
                dp(6)
        );
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!mode.equals(shelfSortMode)) {
                    shelfSortMode = mode;
                    preferences.edit().putString(PREF_SHELF_SORT_MODE, shelfSortMode).apply();
                    renderShelfBooks();
                }
            }
        });
        return button;
    }

    private ArrayList<Book> sortedShelfBooks() {
        ArrayList<Book> sorted = new ArrayList<>(books);
        if (SHELF_SORT_TITLE.equals(shelfSortMode)) {
            final Collator collator = Collator.getInstance(Locale.CHINA);
            Collections.sort(sorted, new Comparator<Book>() {
                @Override
                public int compare(Book left, Book right) {
                    int result = collator.compare(safeBookTitle(left), safeBookTitle(right));
                    if (result != 0) {
                        return result;
                    }
                    return compareRecentBooks(left, right);
                }
            });
        } else {
            Collections.sort(sorted, new Comparator<Book>() {
                @Override
                public int compare(Book left, Book right) {
                    return compareRecentBooks(left, right);
                }
            });
        }
        return sorted;
    }

    private int compareRecentBooks(Book left, Book right) {
        long leftOpened = left == null ? 0L : left.lastOpenedAt;
        long rightOpened = right == null ? 0L : right.lastOpenedAt;
        if (leftOpened != rightOpened) {
            return Long.compare(rightOpened, leftOpened);
        }
        long leftAdded = left == null ? 0L : left.addedAt;
        long rightAdded = right == null ? 0L : right.addedAt;
        if (leftAdded != rightAdded) {
            return Long.compare(rightAdded, leftAdded);
        }
        return safeBookTitle(left).compareToIgnoreCase(safeBookTitle(right));
    }

    private String safeBookTitle(Book book) {
        return book == null || book.title == null ? "" : book.title;
    }

    private String normalizeShelfSortMode(String mode) {
        return SHELF_SORT_TITLE.equals(mode) ? SHELF_SORT_TITLE : SHELF_SORT_RECENT;
    }

    private View makeBookGridItem(final Book book) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setPadding(dp(6), dp(6), dp(6), dp(8));
        item.setClickable(true);
        item.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openBook(book);
            }
        });

        View cover = makeBookCover(book);
        cover.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openBook(book);
            }
        });
        cover.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                confirmDelete(book);
                return true;
            }
        });
        item.addView(cover, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(shelfColumnCount() >= 3 ? 148 : 178)
        ));

        TextView name = makeText(book.title, 15, Color.parseColor("#222823"), Typeface.BOLD);
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setPadding(0, dp(8), 0, 0);
        item.addView(name, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout meta = new LinearLayout(this);
        meta.setGravity(Gravity.CENTER_VERTICAL);
        meta.setPadding(0, dp(5), 0, 0);
        item.addView(meta, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView progress = makeText("已读 " + formatProgress(book.progress), 12, Color.parseColor("#6F746D"), Typeface.NORMAL);
        meta.addView(progress, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView type = makeText(book.type.toUpperCase(Locale.US), 11, Color.parseColor("#566158"), Typeface.BOLD);
        type.setGravity(Gravity.CENTER);
        setRoundedBackground(type, Color.TRANSPARENT, Color.parseColor("#D6CFC2"), dp(5));
        meta.addView(type, new LinearLayout.LayoutParams(dp(38), dp(24)));

        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(4)
        );
        progressParams.setMargins(0, dp(6), 0, 0);
        item.addView(makeProgressBar(book.progress), progressParams);

        return item;
    }

    private View makeBookCover(Book book) {
        int coverColor = bookCoverColor(book);
        int textColor = readableTextColor(coverColor);

        LinearLayout cover = new LinearLayout(this);
        cover.setOrientation(LinearLayout.VERTICAL);
        cover.setGravity(Gravity.CENTER);
        cover.setPadding(dp(7), dp(8), dp(7), dp(8));
        setRoundedBackground(cover, coverColor, Color.argb(45, 0, 0, 0), dp(7));

        TextView title = makeText(coverTitle(book.title), 15, textColor, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setMaxLines(5);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setIncludeFontPadding(false);
        title.setLineSpacing(dp(1), 0.96f);
        cover.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));

        TextView type = makeText(book.type.toUpperCase(Locale.US), 10, Color.argb(210, Color.red(textColor), Color.green(textColor), Color.blue(textColor)), Typeface.BOLD);
        type.setGravity(Gravity.CENTER);
        cover.addView(type, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        return cover;
    }

    private View makeProgressBar(float progress) {
        float safeProgress = Math.max(0f, Math.min(1f, progress));
        LinearLayout track = new LinearLayout(this);
        track.setOrientation(LinearLayout.HORIZONTAL);
        setRoundedBackground(track, Color.parseColor("#E9E2D6"), 0, dp(3));

        if (safeProgress > 0f) {
            View fill = new View(this);
            setRoundedBackground(fill, Color.parseColor("#58734F"), 0, dp(3));
            track.addView(fill, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, safeProgress));
        }
        if (safeProgress < 1f) {
            View rest = new View(this);
            track.addView(rest, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f - safeProgress));
        }
        return track;
    }

    private String coverTitle(String title) {
        String safeTitle = title == null ? "" : title.trim();
        if (safeTitle.isEmpty()) {
            return "书";
        }
        return safeTitle;
    }

    private int bookCoverColor(Book book) {
        int[] colors = new int[]{
                Color.parseColor("#244739"),
                Color.parseColor("#763A28"),
                Color.parseColor("#1F3440"),
                Color.parseColor("#4E5D42"),
                Color.parseColor("#2F4F5A"),
                Color.parseColor("#6F5736")
        };
        String key = (book == null ? "" : book.title + book.type);
        int index = (key.hashCode() & 0x7fffffff) % colors.length;
        return colors[index];
    }

    private void openImporter() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                showBookFileBrowser();
            } else {
                new AlertDialog.Builder(this)
                        .setTitle("浏览本地书籍")
                        .setMessage("从原文件位置直接阅读，需要允许访问手机存储。")
                        .setPositiveButton("授权文件访问", (dialog, which) -> requestAllFilesAccess())
                        .setNegativeButton("取消", null)
                        .show();
            }
        } else if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED) {
            showBookFileBrowser();
        } else {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQUEST_READ_STORAGE);
        }
    }

    private void requestAllFilesAccess() {
        Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        try {
            startActivityForResult(intent, REQUEST_ALL_FILES_ACCESS);
        } catch (ActivityNotFoundException e) {
            startActivityForResult(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
                    REQUEST_ALL_FILES_ACCESS);
        }
    }

    private void showBookFileBrowser() {
        new BookFilePicker(this, this::importBook).show();
    }

    private void importBook(File file) {
        String type = detectType(file.getName());
        if (type == null || !file.isFile() || !file.canRead()) {
            Toast.makeText(this, "只支持导入 txt、epub 和 pdf 文件", Toast.LENGTH_LONG).show();
            return;
        }
        String path = file.getAbsolutePath();
        for (Book existing : books) {
            if (path.equals(existing.sourcePath)) {
                Toast.makeText(this, "这本书已在书架", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        Book book = new Book();
        book.id = UUID.randomUUID().toString();
        book.title = trimExtension(file.getName());
        book.type = type;
        book.sourcePath = path;
        book.size = file.length();
        book.addedAt = System.currentTimeMillis();
        book.progress = 0f;
        books.add(0, book);
        saveBooks();
        showShelf();
        Toast.makeText(this, "已加入书架：" + book.title, Toast.LENGTH_SHORT).show();
    }

    private void openBook(final Book book) {
        File file = new File(book.sourcePath);
        if (!file.isFile() || !file.canRead()) {
            Toast.makeText(this, "原文件不存在或不可访问，请检查文件位置和访问权限", Toast.LENGTH_LONG).show();
            return;
        }

        closeCurrentSource();
        try {
            currentSource = book.isPdf() ? null : book.isEpub() ? new EpubBookSource(file, getResources()) : new TxtBookSource(file);
            currentBook = book;
            book.size = file.length();
            book.lastOpenedAt = System.currentTimeMillis();
            saveBooks();
            if (book.isPdf()) {
                showPdfReader(book, file);
            } else {
                showReader(book);
            }
        } catch (Exception e) {
            closeCurrentSource();
            Toast.makeText(this, "打开失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void showReader(final Book book) {
        enterImmersiveMode();
        readerRoot = new FrameLayout(this);
        readerRoot.setKeepScreenOn(false);
        readerRoot.setBackgroundColor(readerPrefs.backgroundColor);
        setContentView(readerRoot);

        readerText = book.isEpub() ? new TextView(this) : new TxtJustifiedTextView(this);
        readerText.setGravity(Gravity.START | Gravity.TOP);
        readerText.setTextIsSelectable(false);
        readerText.setIncludeFontPadding(false);
        readerText.setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE);
        readerText.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
        readerText.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        readerText.setText("正在打开...");
        readerText.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                return handleReaderTouch(event);
            }
        });
        readerRoot.addView(readerText, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        bottomBar = makeBottomBar();
        bottomBar.setVisibility(View.GONE);
        FrameLayout.LayoutParams barParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
        );
        readerRoot.addView(bottomBar, barParams);

        applyReaderStyle(false);
        readerRoot.post(new Runnable() {
            @Override
            public void run() {
                applyReaderStyle(false);
                Position position = currentSource.normalize(book.toPosition());
                loadPage(position, false);
            }
        });
    }

    private void showPdfReader(final Book book, File file) {
        enterImmersiveMode();
        readerRoot = new FrameLayout(this);
        readerRoot.setBackgroundColor(readerPrefs.pdfNightMode ? readerPrefs.pdfBackgroundColor : Color.WHITE);
        setContentView(readerRoot);
        readerText = null;
        currentPage = null;
        pdfReaderView = new PdfReaderView(this, new PdfReaderView.Listener() {
            @Override
            public void onReady(int count) {
                if (book == currentBook) {
                    updateBottomProgress();
                    refreshScreenWakeTimer();
                }
            }

            @Override
            public void onProgress(int page, float fraction, int count) {
                if (book == currentBook && count > 0) {
                    book.pdfPage = page;
                    book.pdfPageFraction = fraction;
                    book.progress = (page + fraction) / count;
                    updateBottomProgress();
                    handler.removeCallbacks(pdfProgressSaver);
                    handler.postDelayed(pdfProgressSaver, 700);
                }
            }

            @Override
            public void onTap() {
                toggleBottomBar();
                refreshScreenWakeTimer();
            }

            @Override
            public void onInteraction() {
                refreshScreenWakeTimer();
            }

            @Override
            public void onError(String message) {
                if (book == currentBook) {
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                }
            }
        });
        pdfReaderView.setInitialCrop(book.pdfCropMode, book.pdfManualCrop());
        pdfReaderView.setNightPalette(readerPrefs.pdfNightMode,
                readerPrefs.pdfBackgroundColor, readerPrefs.pdfTextColor);
        readerRoot.addView(pdfReaderView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        bottomBar = makeBottomBar();
        bottomBar.setVisibility(View.GONE);
        readerRoot.addView(bottomBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        applyReaderWindowControls();
        pdfReaderView.open(file, book.pdfPage, book.pdfPageFraction);
        applyPdfOrientation();
    }

    private LinearLayout makeBottomBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(10), dp(12), dp(10));
        bar.setClickable(true);
        setRoundedBackground(bar, Color.argb(238, 35, 39, 36), 0, 0);

        bottomTimeText = makeText("--:--", 14, Color.WHITE, Typeface.BOLD);
        bottomTimeText.setGravity(Gravity.CENTER);
        bar.addView(bottomTimeText, new LinearLayout.LayoutParams(dp(54), dp(38)));

        bottomBatteryText = makeText("--%", 14, Color.parseColor("#E7ECE8"), Typeface.NORMAL);
        bottomBatteryText.setGravity(Gravity.CENTER);
        bar.addView(bottomBatteryText, new LinearLayout.LayoutParams(dp(58), dp(38)));

        bottomProgressText = makeText("0%", 14, Color.parseColor("#E7ECE8"), Typeface.NORMAL);
        bottomProgressText.setGravity(Gravity.CENTER);
        bar.addView(bottomProgressText, new LinearLayout.LayoutParams(dp(currentBook != null && currentBook.isPdf() ? 72 : 62), dp(38)));

        TextView shelf = makeBottomButton("书架");
        shelf.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeReaderToShelf();
            }
        });
        bar.addView(shelf, bottomButtonParams());

        if (currentBook != null && currentBook.isPdf()) {
            TextView crop = makeBottomButton("裁剪");
            crop.setOnClickListener(v -> showPdfCropDialog());
            bar.addView(crop, bottomButtonParams());

            TextView rotate = makeBottomButton("旋转");
            rotate.setOnClickListener(v -> togglePdfOrientation());
            bar.addView(rotate, bottomButtonParams());
        } else {
            TextView fontDown = makeBottomButton("A-");
            fontDown.setOnClickListener(v -> adjustFontSize(-1f));
            bar.addView(fontDown, bottomButtonParams());

            TextView fontUp = makeBottomButton("A+");
            fontUp.setOnClickListener(v -> adjustFontSize(1f));
            bar.addView(fontUp, bottomButtonParams());
        }

        TextView settings = makeBottomButton("设置");
        settings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showReaderSettings();
            }
        });
        bar.addView(settings, bottomButtonParams());

        return bar;
    }

    private LinearLayout.LayoutParams bottomButtonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(38), 1f);
        params.setMargins(dp(5), 0, 0, 0);
        return params;
    }

    private TextView makeBottomButton(String text) {
        TextView button = makeText(text, 14, Color.WHITE, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setClickable(true);
        setRoundedBackground(button, Color.argb(70, 255, 255, 255), 0, dp(6));
        return button;
    }

    private boolean handleReaderTouch(MotionEvent event) {
        if (event.getAction() != MotionEvent.ACTION_UP) {
            return true;
        }
        refreshScreenWakeTimer();
        if (bottomBar != null && bottomBar.getVisibility() == View.VISIBLE) {
            int barTop = readerRoot.getHeight() - bottomBar.getHeight();
            if (event.getY() >= barTop) {
                return true;
            }
        }
        float x = event.getX();
        int width = readerText == null ? 0 : readerText.getWidth();
        if (width <= 0) {
            return true;
        }
        if (x < width * 0.35f) {
            previousPage();
        } else if (x > width * 0.65f) {
            nextPage();
        } else {
            toggleBottomBar();
        }
        return true;
    }

    private void toggleBottomBar() {
        if (bottomBar == null) {
            return;
        }
        boolean show = bottomBar.getVisibility() != View.VISIBLE;
        bottomBar.setVisibility(show ? View.VISIBLE : View.GONE);
        handler.removeCallbacks(timeTicker);
        if (show) {
            updateBottomTime();
            updateBottomBattery();
            updateBottomProgress();
            handler.postDelayed(timeTicker, 30 * 1000L);
        }
    }

    private void previousPage() {
        if (loadingPage || currentSource == null || currentPage == null) {
            return;
        }
        if (!currentPage.hasPrevious) {
            Toast.makeText(this, "已经是第一页", Toast.LENGTH_SHORT).show();
            return;
        }
        loadPage(currentPage.start, true);
    }

    private void nextPage() {
        if (loadingPage || currentSource == null || currentPage == null) {
            return;
        }
        if (!currentPage.hasNext || currentPage.next == null) {
            Toast.makeText(this, "已经是最后一页", Toast.LENGTH_SHORT).show();
            return;
        }
        loadPage(currentPage.next, false);
    }

    private void loadPage(final Position position, final boolean previous) {
        if (currentSource == null || readerText == null || loadingPage) {
            return;
        }
        final PageMetrics metrics = buildPageMetrics();
        if (metrics.width <= 0 || metrics.height <= 0) {
            readerRoot.postDelayed(new Runnable() {
                @Override
                public void run() {
                    loadPage(position, previous);
                }
            }, 40);
            return;
        }

        loadingPage = true;
        final BookSource source = currentSource;
        final Book book = currentBook;
        logPage("REQ dir=" + (previous ? "prev" : "next")
                + " pos=" + positionSummary(position)
                + " metrics=" + metricsSummary(metrics)
                + " tv=" + readerText.getWidth() + "x" + readerText.getHeight()
                + " padding=" + readerText.getPaddingLeft() + "," + readerText.getPaddingTop()
                + "," + readerText.getPaddingRight() + "," + readerText.getPaddingBottom()
                + " prefs=font:" + readerPrefs.fontSizeSp
                + " lineDp:" + readerPrefs.lineSpacingDp
                + " letter:" + readerPrefs.letterSpacingEm
                + " removeBreaks:" + readerPrefs.txtRemoveLineBreaks);
        if (readerText instanceof TxtJustifiedTextView) {
            ((TxtJustifiedTextView) readerText).setLastLineContinues(false);
        }
        readerText.setText(previous ? "正在加载上一页..." : "正在加载下一页...");

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final Page page = previous ? source.previousPage(position, metrics) : source.loadPage(position, metrics);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (source != currentSource || book != currentBook) {
                                return;
                            }
                            displayPage(page);
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (source == currentSource) {
                                loadingPage = false;
                                Toast.makeText(MainActivity.this, "加载失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                            }
                        }
                    });
                }
            }
        }).start();
    }

    private void displayPage(Page page) {
        currentPage = page;
        loadingPage = false;
        CharSequence displayContent = isBlank(page.content)
                ? "这一页没有可显示的正文。"
                : contentForDisplay(page.content);
        readerText.setGravity(Gravity.START | Gravity.TOP);
        if (readerText instanceof TxtJustifiedTextView) {
            ((TxtJustifiedTextView) readerText).setLastLineContinues(page.lastLineContinues);
        }
        readerText.setText(displayContent);
        logRenderedPage(page);
        saveCurrentProgress();
        updateBottomProgress();
        updateBottomTime();
        refreshScreenWakeTimer();
        if (pendingReaderReflow && readerRoot != null) {
            pendingReaderReflow = false;
            readerRoot.post(() -> applyReaderStyle(true));
        }
    }

    private void logRenderedPage(final Page page) {
        if (!DEBUG_PAGE_LOGS || readerText == null) {
            return;
        }
        readerText.post(new Runnable() {
            @Override
            public void run() {
                Layout layout = readerText == null ? null : readerText.getLayout();
                CharSequence rendered = readerText == null ? "" : readerText.getText();
                if (layout == null) {
                    logPage("RENDER layout=null page=" + pageSummary(page));
                    return;
                }
                int lineCount = layout.getLineCount();
                int usableHeight = Math.max(0, readerText.getHeight() - readerText.getTotalPaddingTop() - readerText.getTotalPaddingBottom());
                if (lineCount <= 0) {
                    logPage("RENDER lines=0 usableH=" + usableHeight + " page=" + pageSummary(page));
                    return;
                }
                int last = lineCount - 1;
                Paint.FontMetricsInt fm = readerText.getPaint().getFontMetricsInt();
                int lineBottom = layout.getLineBottom(last);
                int glyphBottom = layout.getLineBaseline(last) + Math.max(fm.descent, fm.bottom);
                int neededBottom = Math.max(lineBottom, glyphBottom);
                logPage("RENDER page=" + pageSummary(page)
                        + " displayLen=" + rendered.length()
                        + " lines=" + lineCount
                        + " usableH=" + usableHeight
                        + " lastStartEnd=" + layout.getLineStart(last) + "-" + layout.getLineEnd(last)
                        + " lineBottom=" + lineBottom
                        + " glyphBottom=" + glyphBottom
                        + " neededBottom=" + neededBottom
                        + " fits=" + (neededBottom <= usableHeight)
                        + " last='" + sampleRange(rendered, layout.getLineStart(last), layout.getLineEnd(last)) + "'");
            }
        });
    }

    private CharSequence contentForDisplay(CharSequence content) {
        if (readerText == null) {
            return content;
        }
        CharSequence displayContent = highlightTxtChapterMarkers(content);
        if (!isImageFocusedPage(displayContent)) {
            return displayContent;
        }
        PageMetrics metrics = buildPageMetrics();
        if (metrics.height <= 0) {
            return displayContent;
        }
        Spanned spanned = (Spanned) displayContent;
        CenteredImageSpan[] spans = spanned.getSpans(0, displayContent.length(), CenteredImageSpan.class);
        if (spans.length == 0) {
            return displayContent;
        }
        SpannableStringBuilder display = trimDisplayEdges(displayContent);
        StaticLayout layout = makeLayout(display, metrics);
        if (layout.getLineCount() == 0) {
            return display;
        }
        int contentHeight = layout.getLineBottom(layout.getLineCount() - 1);
        int topSpace = Math.max(0, (metrics.height - contentHeight) / 2);
        if (topSpace <= 0) {
            return display;
        }
        display.insert(0, "\uFFFC\n");
        display.setSpan(new VerticalSpaceSpan(topSpace), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return display;
    }

    private CharSequence highlightTxtChapterMarkers(CharSequence content) {
        if (content == null || content.length() == 0 || currentBook == null || currentBook.isEpub()) {
            return content;
        }
        Matcher matcher = TXT_CHAPTER_MARKER_PATTERN.matcher(content);
        if (!matcher.find()) {
            return content;
        }
        SpannableStringBuilder display = new SpannableStringBuilder(content);
        int highlightColor = txtChapterHighlightColor();
        do {
            display.setSpan(
                    new ForegroundColorSpan(highlightColor),
                    matcher.start(),
                    matcher.end(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            );
        } while (matcher.find());
        return display;
    }

    private int txtChapterHighlightColor() {
        boolean lightBackground = colorLuminance(readerPrefs.backgroundColor) > 150;
        int[] candidates = lightBackground
                ? new int[]{
                Color.parseColor("#9A5A13"),
                Color.parseColor("#1E6E5A"),
                Color.parseColor("#8B3E2F")
        }
                : new int[]{
                Color.parseColor("#F4C96B"),
                Color.parseColor("#7FD0B4"),
                Color.parseColor("#E58A62")
        };
        int best = candidates[0];
        double bestDistance = -1;
        for (int candidate : candidates) {
            double distance = colorDistance(candidate, readerPrefs.textColor);
            if (distance > bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    private PageMetrics buildPageMetrics() {
        Rect visible = readerLocalVisibleRect();
        int horizontalMargin = dp(readerPrefs.horizontalMarginDp);
        int verticalMargin = dp(readerPrefs.verticalMarginDp);
        int width = visible.width() - horizontalMargin * 2;
        int height = visible.height() - verticalMargin * 2;
        TextPaint paint = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
        paint.setTextSize(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                readerPrefs.fontSizeSp,
                getResources().getDisplayMetrics()
        ));
        paint.setColor(readerPrefs.textColor);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            paint.setLetterSpacing(readerPrefs.letterSpacingEm);
        }
        boolean txtPagination = currentBook != null && !currentBook.isEpub();
        return new PageMetrics(Math.max(1, width), Math.max(1, height), paint, dp(readerPrefs.lineSpacingDp), txtPagination, readerPrefs.txtRemoveLineBreaks);
    }

    private Rect readerLocalVisibleRect() {
        Rect visible = new Rect();
        if (readerText != null
                && readerText.getWidth() > 0
                && readerText.getHeight() > 0
                && readerText.getLocalVisibleRect(visible)
                && visible.width() > 0
                && visible.height() > 0) {
            return visible;
        }
        int width = readerText == null ? 0 : readerText.getWidth();
        int height = readerText == null ? 0 : readerText.getHeight();
        return new Rect(0, 0, Math.max(1, width), Math.max(1, height));
    }

    private void applyReaderStyle(boolean reloadPage) {
        if (readerRoot == null || readerText == null) {
            return;
        }
        readerRoot.setBackgroundColor(readerPrefs.backgroundColor);
        readerText.setBackgroundColor(readerPrefs.backgroundColor);
        readerText.setTextColor(readerPrefs.textColor);
        readerText.setTextSize(TypedValue.COMPLEX_UNIT_SP, readerPrefs.fontSizeSp);
        readerText.setLineSpacing(dp(readerPrefs.lineSpacingDp), 1f);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            readerText.setLetterSpacing(readerPrefs.letterSpacingEm);
        }
        readerText.setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE);
        readerText.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
        readerText.setIncludeFontPadding(false);
        if (readerText instanceof TxtJustifiedTextView) {
            ((TxtJustifiedTextView) readerText).setJustification(readerPrefs.txtJustify);
        }
        int horizontalMargin = dp(readerPrefs.horizontalMarginDp);
        int verticalMargin = dp(readerPrefs.verticalMarginDp);
        Rect visible = readerLocalVisibleRect();
        int hiddenLeft = Math.max(0, visible.left);
        int hiddenTop = Math.max(0, visible.top);
        int hiddenRight = Math.max(0, readerText.getWidth() - visible.right);
        int hiddenBottom = Math.max(0, readerText.getHeight() - visible.bottom);
        readerText.setPadding(
                horizontalMargin + hiddenLeft,
                verticalMargin + hiddenTop,
                horizontalMargin + hiddenRight,
                verticalMargin + hiddenBottom
        );
        getWindow().setNavigationBarColor(readerPrefs.backgroundColor);
        applyReaderWindowControls();

        if (reloadPage && currentPage != null) {
            if (loadingPage) {
                pendingReaderReflow = true;
            } else {
                loadPage(currentPage.start, false);
            }
        }
    }

    private void applyReaderWindowControls() {
        Window window = getWindow();
        WindowManager.LayoutParams attributes = window.getAttributes();
        attributes.screenBrightness = readerPrefs.brightnessFollowSystem
                ? WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                : Math.max(0.01f, Math.min(1f, readerPrefs.brightnessPercent / 100f));
        window.setAttributes(attributes);
        refreshScreenWakeTimer();
    }

    private void refreshScreenWakeTimer() {
        handler.removeCallbacks(screenTimeoutRunnable);
        if (currentBook == null) {
            return;
        }
        if (readerPrefs.screenTimeoutMinutes < 0) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            if (readerRoot != null) {
                readerRoot.setKeepScreenOn(true);
            }
            return;
        }
        if (readerPrefs.screenTimeoutMinutes == 0) {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            if (readerRoot != null) {
                readerRoot.setKeepScreenOn(false);
            }
            return;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (readerRoot != null) {
            readerRoot.setKeepScreenOn(true);
        }
        handler.postDelayed(screenTimeoutRunnable, readerPrefs.screenTimeoutMinutes * 60 * 1000L);
    }

    private void resetReaderWindowControls() {
        handler.removeCallbacks(screenTimeoutRunnable);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowManager.LayoutParams attributes = getWindow().getAttributes();
        attributes.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        getWindow().setAttributes(attributes);
        if (readerRoot != null) {
            readerRoot.setKeepScreenOn(false);
        }
    }

    private void adjustFontSize(float delta) {
        float next = Math.max(14f, Math.min(32f, readerPrefs.fontSizeSp + delta));
        if (next == readerPrefs.fontSizeSp) {
            return;
        }
        readerPrefs.fontSizeSp = next;
        readerPrefs.save(preferences);
        applyReaderStyle(true);
    }

    private void jumpToProgress(float progress) {
        if (currentBook != null && currentBook.isPdf() && pdfReaderView != null) {
            int count = pdfReaderView.getPageCount();
            if (count > 0) {
                float exactPage = Math.max(0f, Math.min(1f, progress)) * count;
                currentBook.pdfPage = Math.min(count - 1, (int) exactPage);
                currentBook.pdfPageFraction = exactPage >= count ? 0.99f : exactPage - currentBook.pdfPage;
                currentBook.progress = (currentBook.pdfPage + currentBook.pdfPageFraction) / count;
                saveBooks();
            }
            pdfReaderView.jumpToProgress(progress);
            refreshScreenWakeTimer();
            return;
        }
        if (currentSource == null || readerText == null || loadingPage) {
            return;
        }
        Position position = currentSource.positionAtProgress(progress);
        loadPage(currentSource.normalize(position), false);
    }

    private void showReaderSettings() {
        if (currentBook != null && currentBook.isPdf()) {
            showPdfSettings();
            return;
        }
        if (bottomBar != null) {
            bottomBar.setVisibility(View.GONE);
            handler.removeCallbacks(timeTicker);
        }
        final Dialog dialog = new Dialog(this);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(16), dp(18), dp(18));
        panel.setBackgroundColor(Color.WHITE);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        panel.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView title = makeText("阅读设置", 20, Color.parseColor("#222823"), Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView close = makePillButton("完成", Color.parseColor("#2E4A3F"), Color.WHITE);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        header.addView(close, new LinearLayout.LayoutParams(dp(74), dp(40)));

        panel.addView(makeSectionLabel("跳转进度"));
        panel.addView(makeProgressJumpSetting());
        if (currentBook != null && !currentBook.isEpub()) {
            panel.addView(makeTxtJustificationSetting());
            panel.addView(makeTxtRemoveLineBreakSetting());
        }

        panel.addView(makeSectionLabel("背景色"));
        panel.addView(makeColorSetting(new ColorOption[]{
                new ColorOption("纸白", Color.parseColor("#F8F3E7")),
                new ColorOption("浅绿", Color.parseColor("#E8F1E6")),
                new ColorOption("浅灰", Color.parseColor("#EEF0F2")),
                new ColorOption("夜间", Color.parseColor("#20242A"))
        }, true, false));

        panel.addView(makeSectionLabel("字体色"));
        panel.addView(makeColorSetting(new ColorOption[]{
                new ColorOption("深墨", Color.parseColor("#27221C")),
                new ColorOption("墨绿", Color.parseColor("#243B32")),
                new ColorOption("暗灰", Color.parseColor("#3D4245")),
                new ColorOption("米白", Color.parseColor("#EFE7D6"))
        }, false, false));

        panel.addView(makeSettingSeek("字体大小", 14, 32, Math.round(readerPrefs.fontSizeSp), "sp", new ValueUpdater() {
            @Override
            public void update(int value) {
                readerPrefs.fontSizeSp = value;
                readerPrefs.save(preferences);
                applyReaderStyle(true);
            }
        }));

        panel.addView(makeSettingSeek("行间距", -8, 24, readerPrefs.lineSpacingDp, "dp", new ValueUpdater() {
            @Override
            public void update(int value) {
                readerPrefs.lineSpacingDp = value;
                readerPrefs.save(preferences);
                applyReaderStyle(true);
            }
        }));

        panel.addView(makeSettingSeek("字间距", -8, 12, Math.round(readerPrefs.letterSpacingEm * 100f), "%", new ValueUpdater() {
            @Override
            public void update(int value) {
                readerPrefs.letterSpacingEm = value / 100f;
                readerPrefs.save(preferences);
                applyReaderStyle(true);
            }
        }));

        panel.addView(makeSettingSeek("左右边距", 0, 60, readerPrefs.horizontalMarginDp, "dp", new ValueUpdater() {
            @Override
            public void update(int value) {
                readerPrefs.horizontalMarginDp = value;
                readerPrefs.save(preferences);
                applyReaderStyle(true);
            }
        }));

        panel.addView(makeSettingSeek("上下边距", 0, 60, readerPrefs.verticalMarginDp, "dp", new ValueUpdater() {
            @Override
            public void update(int value) {
                readerPrefs.verticalMarginDp = value;
                readerPrefs.save(preferences);
                applyReaderStyle(true);
            }
        }));

        panel.addView(makeSectionLabel("屏幕"));
        panel.addView(makeScreenTimeoutSetting());
        panel.addView(makeBrightnessSetting());

        ScrollView settingsScroll = makePreviewScrollView(0.68f, 620);
        settingsScroll.addView(panel, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        dialog.setContentView(settingsScroll);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        allowSystemShade(dialog);
        dialog.show();
        Window shownWindow = dialog.getWindow();
        if (shownWindow != null) {
            shownWindow.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            shownWindow.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            shownWindow.setGravity(Gravity.BOTTOM);
        }
    }

    private void showPdfSettings() {
        if (pdfReaderView == null || currentBook == null) {
            return;
        }
        if (bottomBar != null) {
            bottomBar.setVisibility(View.GONE);
            handler.removeCallbacks(timeTicker);
        }
        final Dialog dialog = new Dialog(this);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(16), dp(18), dp(18));
        panel.setBackgroundColor(Color.WHITE);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        panel.addView(header);
        TextView title = makeText("PDF 阅读设置", 20, Color.parseColor("#222823"), Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = makePillButton("完成", Color.parseColor("#2E4A3F"), Color.WHITE);
        close.setOnClickListener(v -> dialog.dismiss());
        header.addView(close, new LinearLayout.LayoutParams(dp(74), dp(40)));

        panel.addView(makeSectionLabel("跳转进度"));
        panel.addView(makeProgressJumpSetting());
        panel.addView(makeSectionLabel("裁剪区域"));
        RadioGroup cropGroup = new RadioGroup(this);
        cropGroup.setOrientation(RadioGroup.HORIZONTAL);
        cropGroup.addView(makePdfRadio("整页", 1), new RadioGroup.LayoutParams(0, dp(44), 1f));
        cropGroup.addView(makePdfRadio("自动", 2), new RadioGroup.LayoutParams(0, dp(44), 1f));
        cropGroup.addView(makePdfRadio("手动", 3), new RadioGroup.LayoutParams(0, dp(44), 1f));
        cropGroup.check(currentBook.pdfCropMode == PdfReaderView.CROP_FULL ? 1
                : currentBook.pdfCropMode == PdfReaderView.CROP_MANUAL ? 3 : 2);
        cropGroup.setOnCheckedChangeListener((group, checkedId) -> setPdfCropMode(
                checkedId == 1 ? PdfReaderView.CROP_FULL
                        : checkedId == 3 ? PdfReaderView.CROP_MANUAL : PdfReaderView.CROP_AUTO,
                null));
        panel.addView(cropGroup);
        TextView cropButton = makePillButton("调整手动裁剪区域", Color.parseColor("#E6EFE8"), Color.parseColor("#2E4A3F"));
        cropButton.setOnClickListener(v -> {
            dialog.dismiss();
            showPdfCropDialog();
        });
        panel.addView(cropButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));

        panel.addView(makeSectionLabel("屏幕方向"));
        RadioGroup orientationGroup = new RadioGroup(this);
        orientationGroup.setOrientation(RadioGroup.HORIZONTAL);
        orientationGroup.addView(makePdfRadio("跟随系统", 11), new RadioGroup.LayoutParams(0, dp(44), 1f));
        orientationGroup.addView(makePdfRadio("竖屏", 12), new RadioGroup.LayoutParams(0, dp(44), 1f));
        orientationGroup.addView(makePdfRadio("横屏", 13), new RadioGroup.LayoutParams(0, dp(44), 1f));
        orientationGroup.check(11 + Math.max(0, Math.min(2, currentBook.pdfOrientation)));
        orientationGroup.setOnCheckedChangeListener((group, checkedId) -> {
            currentBook.pdfOrientation = checkedId - 11;
            saveBooks();
            applyPdfOrientation();
        });
        panel.addView(orientationGroup);
        panel.addView(makeSectionLabel("夜间模式"));
        CheckBox nightMode = new CheckBox(this);
        nightMode.setText("启用");
        nightMode.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        nightMode.setTextColor(Color.parseColor("#222823"));
        nightMode.setChecked(readerPrefs.pdfNightMode);
        nightMode.setOnCheckedChangeListener((button, checked) -> {
            readerPrefs.pdfNightMode = checked;
            readerPrefs.save(preferences);
            applyPdfPalette();
        });
        panel.addView(nightMode);
        panel.addView(makeSectionLabel("背景色"));
        panel.addView(makeColorSetting(new ColorOption[]{
                new ColorOption("纯黑", Color.BLACK),
                new ColorOption("深灰", Color.parseColor("#202428")),
                new ColorOption("深绿", Color.parseColor("#17231D"))
        }, true, true));
        panel.addView(makeSectionLabel("字体色"));
        panel.addView(makeColorSetting(new ColorOption[]{
                new ColorOption("柔灰", Color.parseColor("#B8BDC2")),
                new ColorOption("浅灰", Color.parseColor("#D5D8D6")),
                new ColorOption("暖白", Color.parseColor("#E6DFD0"))
        }, false, true));
        panel.addView(makeSectionLabel("屏幕"));
        panel.addView(makeScreenTimeoutSetting());
        panel.addView(makeBrightnessSetting());

        ScrollView scroll = makePreviewScrollView(0.68f, 620);
        scroll.addView(panel, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        dialog.setContentView(scroll);
        allowSystemShade(dialog);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setGravity(Gravity.BOTTOM);
        }
    }

    private RadioButton makePdfRadio(String label, int id) {
        RadioButton button = new RadioButton(this);
        button.setId(id);
        button.setText(label);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        button.setTextColor(Color.parseColor("#222823"));
        return button;
    }

    private void setPdfCropMode(int mode, RectF crop) {
        if (currentBook == null || pdfReaderView == null) {
            return;
        }
        currentBook.pdfCropMode = mode;
        if (crop != null) {
            currentBook.setPdfManualCrop(crop);
        }
        pdfReaderView.setCrop(mode, currentBook.pdfManualCrop());
        saveBooks();
    }

    private void applyPdfPalette() {
        if (pdfReaderView == null) {
            return;
        }
        if (readerRoot != null) {
            readerRoot.setBackgroundColor(readerPrefs.pdfNightMode ? readerPrefs.pdfBackgroundColor : Color.WHITE);
        }
        pdfReaderView.setNightPalette(readerPrefs.pdfNightMode,
                readerPrefs.pdfBackgroundColor, readerPrefs.pdfTextColor);
    }

    private void showPdfCropDialog() {
        if (pdfReaderView == null || pdfReaderView.getPageCount() <= 0) {
            Toast.makeText(this, "PDF 页面尚未准备好", Toast.LENGTH_SHORT).show();
            return;
        }
        if (bottomBar != null) {
            bottomBar.setVisibility(View.GONE);
            handler.removeCallbacks(timeTicker);
        }
        FrameLayout preview = new FrameLayout(this);
        preview.setBackgroundColor(Color.parseColor("#343936"));
        TextView loading = makeText("正在生成预览...", 15, Color.WHITE, Typeface.NORMAL);
        loading.setGravity(Gravity.CENTER);
        preview.addView(loading, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        int height = Math.min(dp(520), Math.round(getResources().getDisplayMetrics().heightPixels * 0.65f));
        final PdfCropView[] cropView = new PdfCropView[1];
        final Bitmap[] previewBitmap = new Bitmap[1];
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("手动裁剪区域")
                .setView(preview)
                .setNegativeButton("取消", null)
                .setNeutralButton("显示整页", (d, which) -> setPdfCropMode(PdfReaderView.CROP_FULL, null))
                .setPositiveButton("应用到整本书", (d, which) -> {
                    if (cropView[0] != null) {
                        setPdfCropMode(PdfReaderView.CROP_MANUAL, cropView[0].getSelection());
                    }
                })
                .create();
        allowSystemShade(dialog, () -> {
            if (previewBitmap[0] != null) {
                previewBitmap[0].recycle();
            }
        });
        dialog.show();
        preview.getLayoutParams().height = height;
        preview.requestLayout();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        final RectF initial = pdfReaderView.getCurrentCrop();
        pdfReaderView.loadPreview(pdfReaderView.getCurrentPage(), new PdfReaderView.PreviewCallback() {
            @Override
            public void onPreview(Bitmap bitmap) {
                if (!dialog.isShowing()) {
                    bitmap.recycle();
                    return;
                }
                previewBitmap[0] = bitmap;
                cropView[0] = new PdfCropView(MainActivity.this, bitmap, initial);
                preview.removeAllViews();
                preview.addView(cropView[0], new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
            }

            @Override
            public void onError(String message) {
                if (dialog.isShowing()) {
                    loading.setText(message);
                }
            }
        });
    }

    private void togglePdfOrientation() {
        if (currentBook == null || !currentBook.isPdf()) {
            return;
        }
        currentBook.pdfOrientation = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE ? 1 : 2;
        saveBooks();
        applyPdfOrientation();
    }

    private void applyPdfOrientation() {
        if (currentBook == null || !currentBook.isPdf()) {
            return;
        }
        int orientation = currentBook.pdfOrientation == 1 ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                : currentBook.pdfOrientation == 2 ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        if (getRequestedOrientation() != orientation) {
            setRequestedOrientation(orientation);
        }
    }

    private View makeColorSetting(final ColorOption[] options, final boolean background, final boolean pdf) {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(8), 0, dp(8));
        scroll.addView(row);
        group.addView(scroll);

        final TextView[] chips = new TextView[options.length];
        for (int i = 0; i < options.length; i++) {
            ColorOption option = options[i];
            TextView chip = makeText(option.name, 14, readableTextColor(option.color), Typeface.BOLD);
            chip.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(72), dp(40));
            params.setMargins(0, 0, dp(10), 0);
            row.addView(chip, params);
            chips[i] = chip;
        }

        String label = background ? "自定义背景 RGB" : "自定义字体 RGB";
        TextView button = makeText(label, 14, Color.parseColor("#2E4A3F"), Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setClickable(true);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(42)
        );
        params.setMargins(0, 0, 0, dp(6));
        group.addView(button, params);

        Runnable refresh = () -> {
            int current = selectedColor(background, pdf);
            boolean matchesPreset = false;
            for (ColorOption option : options) {
                if (option.color == current) {
                    matchesPreset = true;
                    break;
                }
            }
            boolean custom = isCustomColor(background, pdf) || !matchesPreset;
            for (int i = 0; i < options.length; i++) {
                ColorOption option = options[i];
                boolean selected = !custom && option.color == current;
                TextView chip = chips[i];
                chip.setSelected(selected);
                chip.setText(option.name + (selected ? " ✓" : ""));
                chip.setContentDescription(option.name + (selected ? "，已选中" : ""));
                GradientDrawable shape = new GradientDrawable();
                shape.setColor(option.color);
                shape.setCornerRadius(dp(6));
                shape.setStroke(dp(selected ? 2 : 1), selected
                        ? Color.parseColor("#C49132") : Color.parseColor("#D9D5CA"));
                chip.setBackground(shape);
            }
            button.setSelected(custom);
            button.setText(label + (custom ? " ✓" : ""));
            button.setContentDescription(label + (custom ? "，已选中" : ""));
            button.setTextColor(custom ? Color.WHITE : Color.parseColor("#2E4A3F"));
            setRoundedBackground(button, custom ? Color.parseColor("#2E4A3F")
                    : Color.parseColor("#E6EFE8"), Color.parseColor("#C9D8CD"), dp(6));
        };
        for (int i = 0; i < options.length; i++) {
            final ColorOption option = options[i];
            chips[i].setOnClickListener(v -> {
                selectColor(background, pdf, option.color, false);
                refresh.run();
            });
        }
        button.setOnClickListener(v -> showRgbColorDialog(background, pdf, refresh));
        refresh.run();
        return group;
    }

    private int selectedColor(boolean background, boolean pdf) {
        return pdf ? (background ? readerPrefs.pdfBackgroundColor : readerPrefs.pdfTextColor)
                : (background ? readerPrefs.backgroundColor : readerPrefs.textColor);
    }

    private boolean isCustomColor(boolean background, boolean pdf) {
        return pdf ? (background ? readerPrefs.pdfBackgroundCustom : readerPrefs.pdfTextCustom)
                : (background ? readerPrefs.backgroundCustom : readerPrefs.textCustom);
    }

    private void selectColor(boolean background, boolean pdf, int color, boolean custom) {
        if (pdf) {
            if (background) {
                readerPrefs.pdfBackgroundColor = color;
                readerPrefs.pdfBackgroundCustom = custom;
            } else {
                readerPrefs.pdfTextColor = color;
                readerPrefs.pdfTextCustom = custom;
            }
        } else if (background) {
            readerPrefs.backgroundColor = color;
            readerPrefs.backgroundCustom = custom;
        } else {
            readerPrefs.textColor = color;
            readerPrefs.textCustom = custom;
        }
        readerPrefs.save(preferences);
        if (pdf) {
            applyPdfPalette();
        } else {
            applyReaderStyle(false);
        }
    }

    private void showRgbColorDialog(final boolean background, final boolean pdf, final Runnable refresh) {
        final Dialog dialog = new Dialog(this);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(16), dp(18), dp(18));
        panel.setBackgroundColor(Color.WHITE);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        panel.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView title = makeText(background ? "自定义背景色" : "自定义字体色", 20, Color.parseColor("#222823"), Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView close = makePillButton("完成", Color.parseColor("#2E4A3F"), Color.WHITE);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        header.addView(close, new LinearLayout.LayoutParams(dp(74), dp(40)));

        final TextView preview = makeText("", 15, Color.WHITE, Typeface.BOLD);
        preview.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(56)
        );
        previewParams.setMargins(0, dp(16), 0, dp(8));
        panel.addView(preview, previewParams);

        int current = selectedColor(background, pdf);
        final int[] rgb = new int[]{Color.red(current), Color.green(current), Color.blue(current)};

        final Runnable previewColor = new Runnable() {
            @Override
            public void run() {
                int color = Color.rgb(rgb[0], rgb[1], rgb[2]);
                preview.setText(String.format(Locale.CHINA, "RGB(%d, %d, %d)  #%02X%02X%02X", rgb[0], rgb[1], rgb[2], rgb[0], rgb[1], rgb[2]));
                preview.setTextColor(readableTextColor(color));
                setRoundedBackground(preview, color, Color.parseColor("#D9D5CA"), dp(6));
            }
        };

        Runnable applyColor = () -> {
            previewColor.run();
            selectColor(background, pdf, Color.rgb(rgb[0], rgb[1], rgb[2]), true);
            refresh.run();
        };

        panel.addView(makeRgbSeek("R", rgb, 0, applyColor));
        panel.addView(makeRgbSeek("G", rgb, 1, applyColor));
        panel.addView(makeRgbSeek("B", rgb, 2, applyColor));
        previewColor.run();

        ScrollView scroll = makePreviewScrollView(0.58f, 420);
        scroll.addView(panel, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        dialog.setContentView(scroll);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        allowSystemShade(dialog);
        dialog.show();
        Window shownWindow = dialog.getWindow();
        if (shownWindow != null) {
            shownWindow.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            shownWindow.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            shownWindow.setGravity(Gravity.BOTTOM);
        }
    }

    private ScrollView makePreviewScrollView(float maxScreenFraction, int maxHeightDp) {
        return new ScrollView(this) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int screenHeight = getResources().getDisplayMetrics().heightPixels;
                int maxHeight = Math.min(Math.round(screenHeight * maxScreenFraction), dp(maxHeightDp));
                int availableHeight = View.MeasureSpec.getSize(heightMeasureSpec);
                int height = availableHeight > 0 ? Math.min(availableHeight, maxHeight) : maxHeight;
                super.onMeasure(widthMeasureSpec,
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST));
            }
        };
    }

    private View makeRgbSeek(String label, final int[] rgb, final int index, final Runnable updater) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(8), 0, dp(2));

        LinearLayout line = new LinearLayout(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        box.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView name = makeText(label, 15, Color.parseColor("#222823"), Typeface.BOLD);
        line.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView valueText = makeText(String.valueOf(rgb[index]), 14, Color.parseColor("#687069"), Typeface.NORMAL);
        valueText.setGravity(Gravity.END);
        line.addView(valueText, new LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.WRAP_CONTENT));

        SeekBar seekBar = new SeekBar(this);
        seekBar.setMax(255);
        seekBar.setProgress(rgb[index]);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                rgb[index] = progress;
                valueText.setText(String.valueOf(progress));
                if (fromUser) {
                    updater.run();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                rgb[index] = seekBar.getProgress();
                valueText.setText(String.valueOf(rgb[index]));
                updater.run();
            }
        });
        box.addView(seekBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        return box;
    }

    private View makeTxtRemoveLineBreakSetting() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(2));

        TextView name = makeText("删除换行", 15, Color.parseColor("#222823"), Typeface.BOLD);
        row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final CheckBox checkBox = new CheckBox(this);
        checkBox.setText("启用");
        checkBox.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        checkBox.setTextColor(Color.parseColor("#687069"));
        checkBox.setChecked(readerPrefs.txtRemoveLineBreaks);
        checkBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                readerPrefs.txtRemoveLineBreaks = isChecked;
                readerPrefs.save(preferences);
                applyReaderStyle(true);
            }
        });
        row.addView(checkBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)));
        return row;
    }

    private View makeTxtJustificationSetting() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(2));
        TextView name = makeText("两端对齐", 15, Color.parseColor("#222823"), Typeface.BOLD);
        row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        CheckBox checkBox = new CheckBox(this);
        checkBox.setText("启用");
        checkBox.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        checkBox.setTextColor(Color.parseColor("#687069"));
        checkBox.setChecked(readerPrefs.txtJustify);
        checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            readerPrefs.txtJustify = isChecked;
            readerPrefs.save(preferences);
            if (readerText instanceof TxtJustifiedTextView) {
                ((TxtJustifiedTextView) readerText).setJustification(isChecked);
            }
        });
        row.addView(checkBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)));
        return row;
    }

    private View makeProgressJumpSetting() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(10), 0, dp(2));

        LinearLayout line = new LinearLayout(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        box.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView name = makeText("进度", 15, Color.parseColor("#222823"), Typeface.BOLD);
        line.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        int current = currentBook == null ? 0 : Math.max(0, Math.min(100, Math.round(currentBook.progress * 100f)));
        final TextView valueText = makeText(current + "%", 14, Color.parseColor("#687069"), Typeface.NORMAL);
        valueText.setGravity(Gravity.END);
        line.addView(valueText, new LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.WRAP_CONTENT));

        SeekBar seekBar = new SeekBar(this);
        seekBar.setMax(100);
        seekBar.setProgress(current);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                valueText.setText(progress + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                jumpToProgress(seekBar.getProgress() / 100f);
            }
        });
        box.addView(seekBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        return box;
    }

    private View makeScreenTimeoutSetting() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(10), 0, dp(2));

        LinearLayout line = new LinearLayout(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        box.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView name = makeText("亮屏时长", 15, Color.parseColor("#222823"), Typeface.BOLD);
        line.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView valueText = makeText(screenTimeoutLabel(readerPrefs.screenTimeoutMinutes), 14, Color.parseColor("#687069"), Typeface.NORMAL);
        valueText.setGravity(Gravity.END);
        line.addView(valueText, new LinearLayout.LayoutParams(dp(92), ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView always = makePillButton("常亮", Color.parseColor("#E6EFE8"), Color.parseColor("#2E4A3F"));
        LinearLayout.LayoutParams alwaysParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(38)
        );
        alwaysParams.setMargins(0, dp(8), 0, 0);
        box.addView(always, alwaysParams);

        LinearLayout seekRow = new LinearLayout(this);
        seekRow.setGravity(Gravity.CENTER_VERTICAL);
        box.addView(seekRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        final SeekBar seekBar = new SeekBar(this);
        seekBar.setMax(14);
        seekBar.setProgress(Math.max(0, Math.min(14, Math.max(1, readerPrefs.screenTimeoutMinutes) - 1)));
        seekBar.setEnabled(readerPrefs.screenTimeoutMinutes != 0);
        seekRow.addView(seekBar, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final CheckBox followSystem = new CheckBox(this);
        followSystem.setText("跟随系统");
        followSystem.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        followSystem.setTextColor(Color.parseColor("#687069"));
        followSystem.setChecked(readerPrefs.screenTimeoutMinutes == 0);
        seekRow.addView(followSystem, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)));

        always.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                readerPrefs.screenTimeoutMinutes = -1;
                readerPrefs.save(preferences);
                followSystem.setChecked(false);
                seekBar.setEnabled(true);
                valueText.setText(screenTimeoutLabel(readerPrefs.screenTimeoutMinutes));
                applyReaderWindowControls();
            }
        });
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                valueText.setText(screenTimeoutLabel(progress + 1));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                readerPrefs.screenTimeoutMinutes = seekBar.getProgress() + 1;
                readerPrefs.save(preferences);
                followSystem.setChecked(false);
                valueText.setText(screenTimeoutLabel(readerPrefs.screenTimeoutMinutes));
                applyReaderWindowControls();
            }
        });
        followSystem.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                seekBar.setEnabled(!isChecked);
                if (isChecked) {
                    readerPrefs.screenTimeoutMinutes = 0;
                    readerPrefs.save(preferences);
                    valueText.setText(screenTimeoutLabel(readerPrefs.screenTimeoutMinutes));
                    applyReaderWindowControls();
                } else if (readerPrefs.screenTimeoutMinutes == 0) {
                    readerPrefs.screenTimeoutMinutes = seekBar.getProgress() + 1;
                    readerPrefs.save(preferences);
                    valueText.setText(screenTimeoutLabel(readerPrefs.screenTimeoutMinutes));
                    applyReaderWindowControls();
                }
            }
        });
        return box;
    }

    private View makeBrightnessSetting() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(10), 0, dp(2));

        LinearLayout line = new LinearLayout(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        box.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView name = makeText("屏幕亮度", 15, Color.parseColor("#222823"), Typeface.BOLD);
        line.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView valueText = makeText(brightnessLabel(), 14, Color.parseColor("#687069"), Typeface.NORMAL);
        valueText.setGravity(Gravity.END);
        line.addView(valueText, new LinearLayout.LayoutParams(dp(92), ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout seekRow = new LinearLayout(this);
        seekRow.setGravity(Gravity.CENTER_VERTICAL);
        box.addView(seekRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        final SeekBar seekBar = new SeekBar(this);
        seekBar.setMax(99);
        seekBar.setProgress(Math.max(0, Math.min(99, readerPrefs.brightnessPercent - 1)));
        seekBar.setEnabled(!readerPrefs.brightnessFollowSystem);
        seekRow.addView(seekBar, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final CheckBox followSystem = new CheckBox(this);
        followSystem.setText("跟随系统");
        followSystem.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        followSystem.setTextColor(Color.parseColor("#687069"));
        followSystem.setChecked(readerPrefs.brightnessFollowSystem);
        seekRow.addView(followSystem, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)));

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) {
                    return;
                }
                readerPrefs.brightnessFollowSystem = false;
                readerPrefs.brightnessPercent = progress + 1;
                readerPrefs.save(preferences);
                followSystem.setChecked(false);
                valueText.setText(brightnessLabel());
                applyReaderWindowControls();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                readerPrefs.brightnessFollowSystem = false;
                readerPrefs.brightnessPercent = seekBar.getProgress() + 1;
                readerPrefs.save(preferences);
                followSystem.setChecked(false);
                valueText.setText(brightnessLabel());
                applyReaderWindowControls();
            }
        });
        followSystem.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                seekBar.setEnabled(!isChecked);
                readerPrefs.brightnessFollowSystem = isChecked;
                readerPrefs.save(preferences);
                valueText.setText(brightnessLabel());
                applyReaderWindowControls();
            }
        });
        return box;
    }

    private String screenTimeoutLabel(int minutes) {
        if (minutes < 0) {
            return "常亮";
        }
        if (minutes == 0) {
            return "";
        }
        return minutes + "分钟";
    }

    private String brightnessLabel() {
        return readerPrefs.brightnessFollowSystem ? "" : readerPrefs.brightnessPercent + "%";
    }

    private View makeSettingSeek(String label, int min, int max, int current, final String suffix, final ValueUpdater updater) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(10), 0, dp(2));

        LinearLayout line = new LinearLayout(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        box.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView name = makeText(label, 15, Color.parseColor("#222823"), Typeface.BOLD);
        line.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView valueText = makeText(current + suffix, 14, Color.parseColor("#687069"), Typeface.NORMAL);
        valueText.setGravity(Gravity.END);
        line.addView(valueText, new LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.WRAP_CONTENT));

        final int realMin = min;
        SeekBar seekBar = new SeekBar(this);
        seekBar.setMax(max - min);
        seekBar.setProgress(Math.max(0, Math.min(max - min, current - min)));
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int value = realMin + progress;
                valueText.setText(value + suffix);
                if (fromUser) {
                    updater.update(value);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                updater.update(realMin + seekBar.getProgress());
            }
        });
        box.addView(seekBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        return box;
    }

    private TextView makeSectionLabel(String label) {
        TextView view = makeText(label, 15, Color.parseColor("#222823"), Typeface.BOLD);
        view.setPadding(0, dp(18), 0, 0);
        return view;
    }

    private void updateBottomTime() {
        if (bottomTimeText != null) {
            bottomTimeText.setText(timeFormat.format(new Date()));
        }
    }

    private void updateBottomBattery() {
        if (bottomBatteryText == null) {
            return;
        }
        Intent intent = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (intent == null) {
            bottomBatteryText.setText("--%");
            return;
        }
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        if (level < 0 || scale <= 0) {
            bottomBatteryText.setText("--%");
            return;
        }
        int percent = Math.max(0, Math.min(100, Math.round(level * 100f / scale)));
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
        bottomBatteryText.setText(charging ? percent + "%+" : percent + "%");
    }

    private void updateBottomProgress() {
        if (bottomProgressText != null && currentBook != null) {
            if (currentBook.isPdf() && pdfReaderView != null && pdfReaderView.getPageCount() > 0) {
                bottomProgressText.setText((pdfReaderView.getCurrentPage() + 1) + "/" + pdfReaderView.getPageCount());
            } else {
                bottomProgressText.setText(formatProgress(currentBook.progress));
            }
        }
    }

    private void saveCurrentProgress() {
        if (currentBook != null && currentBook.isPdf() && pdfReaderView != null) {
            if (pdfReaderView.getPageCount() > 0 && !pdfReaderView.isRestoring()) {
                currentBook.pdfPage = pdfReaderView.getCurrentPage();
                currentBook.pdfPageFraction = pdfReaderView.getCurrentFraction();
                currentBook.progress = pdfReaderView.getProgress();
            }
            currentBook.lastOpenedAt = System.currentTimeMillis();
            saveBooks();
            updateBottomProgress();
            return;
        }
        if (currentBook == null || currentSource == null || currentPage == null) {
            return;
        }
        currentBook.applyPosition(currentPage.start);
        currentBook.progress = currentSource.progress(currentPage.start);
        currentBook.lastOpenedAt = System.currentTimeMillis();
        saveBooks();
        updateBottomProgress();
    }

    private void closeReaderToShelf() {
        saveCurrentProgress();
        showShelf();
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
    }

    private void closeCurrentSource() {
        handler.removeCallbacks(timeTicker);
        handler.removeCallbacks(screenTimeoutRunnable);
        handler.removeCallbacks(pdfProgressSaver);
        if (pdfReaderView != null) {
            pdfReaderView.close();
            pdfReaderView = null;
        }
        if (currentSource != null) {
            currentSource.close();
            currentSource = null;
        }
    }

    private void allowSystemShade(Dialog dialog) {
        allowSystemShade(dialog, null);
    }

    private void allowSystemShade(Dialog dialog, Runnable onDismiss) {
        Window window = dialog.getWindow();
        if (window == null) {
            return;
        }
        systemShadeDialogCount++;
        showSystemBarsForDialog(getWindow(), false);
        showSystemBarsForDialog(window, true);
        dialog.setOnDismissListener(d -> {
            if (onDismiss != null) {
                onDismiss.run();
            }
            systemShadeDialogCount--;
            if (systemShadeDialogCount == 0 && currentBook != null) {
                enterImmersiveMode();
            }
        });
    }

    private void showSystemBarsForDialog(Window window, boolean fitContent) {
        View decor = window.getDecorView();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(fitContent);
        }
        int flags = decor.getSystemUiVisibility() & ~(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        if (fitContent) {
            flags &= ~(View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        } else {
            flags |= View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        decor.setSystemUiVisibility(flags);
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(Color.parseColor("#1F3B31"));
        window.setNavigationBarColor(Color.parseColor("#F7F1E7"));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            decor.post(() -> {
                WindowInsetsController controller = decor.getWindowInsetsController();
                if (controller != null && systemShadeDialogCount > 0) {
                    controller.setSystemBarsBehavior(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                            ? WindowInsetsController.BEHAVIOR_DEFAULT
                            : WindowInsetsController.BEHAVIOR_SHOW_BARS_BY_SWIPE);
                    controller.show(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                }
            });
        }
    }

    private void enterImmersiveMode() {
        Window window = getWindow();
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setStatusBarContrastEnforced(false);
            window.setNavigationBarContrastEnforced(false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            window.setAttributes(attributes);
        }
        View decor = window.getDecorView();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = decor.getWindowInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
    }

    private void exitImmersiveMode() {
        Window window = getWindow();
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        View decor = window.peekDecorView();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(true);
            WindowInsetsController controller = decor == null ? null : decor.getWindowInsetsController();
            if (controller != null) {
                controller.show(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            }
        }
        if (decor != null) {
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    private void confirmDelete(final Book book) {
        new AlertDialog.Builder(this)
                .setTitle("删除书籍")
                .setMessage("从书架移除《" + book.title + "》？")
                .setPositiveButton("删除", (dialog, which) -> {
                    books.remove(book);
                    saveBooks();
                    renderShelfBooks();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void loadBooks() {
        books.clear();
        String json = preferences.getString(PREF_BOOKS, "[]");
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                Book book = Book.fromJson(array.getJSONObject(i));
                if (book.sourcePath != null && !book.sourcePath.isEmpty()) {
                    books.add(book);
                }
            }
        } catch (JSONException ignored) {
            preferences.edit().putString(PREF_BOOKS, "[]").apply();
        }
    }

    private void saveBooks() {
        JSONArray array = new JSONArray();
        for (Book book : books) {
            array.put(book.toJson());
        }
        preferences.edit().putString(PREF_BOOKS, array.toString()).apply();
    }

    private String detectType(String displayName) {
        String lowerName = displayName.toLowerCase(Locale.ROOT);
        if (lowerName.endsWith(".epub")) {
            return "epub";
        }
        if (lowerName.endsWith(".pdf")) {
            return "pdf";
        }
        if (lowerName.endsWith(".txt")) {
            return "txt";
        }
        return null;
    }

    private String trimExtension(String name) {
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            return name.substring(0, dot);
        }
        return name;
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.CHINA, "%.1f KB", bytes / 1024f);
        }
        return String.format(Locale.CHINA, "%.1f MB", bytes / 1024f / 1024f);
    }

    private String formatProgress(float progress) {
        return String.format(Locale.CHINA, "%.0f%%", Math.max(0f, Math.min(1f, progress)) * 100f);
    }

    private TextView makeText(String value, int sp, int color, int style) {
        TextView textView = new TextView(this);
        textView.setText(value);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        textView.setTextColor(color);
        textView.setTypeface(Typeface.DEFAULT, style);
        textView.setIncludeFontPadding(true);
        return textView;
    }

    private TextView makePillButton(String value, int backgroundColor, int textColor) {
        TextView button = makeText(value, 15, textColor, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setClickable(true);
        button.setPadding(dp(10), 0, dp(10), 0);
        setRoundedBackground(button, backgroundColor, 0, dp(8));
        return button;
    }

    private void setRoundedBackground(View view, int color, int strokeColor, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        if (strokeColor != 0) {
            drawable.setStroke(dp(1), strokeColor);
        }
        view.setBackground(drawable);
    }

    private int readableTextColor(int backgroundColor) {
        return colorLuminance(backgroundColor) > 150 ? Color.parseColor("#222823") : Color.WHITE;
    }

    private double colorLuminance(int color) {
        return 0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color);
    }

    private double colorDistance(int first, int second) {
        int red = Color.red(first) - Color.red(second);
        int green = Color.green(first) - Color.green(second);
        int blue = Color.blue(first) - Color.blue(second);
        return Math.sqrt(red * red + green * green + blue * blue);
    }

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                getResources().getDisplayMetrics()
        ));
    }

    private static void logPage(String message) {
        if (DEBUG_PAGE_LOGS) {
            Log.d(LOG_TAG, message);
        }
    }

    private static String pageSummary(Page page) {
        if (page == null) {
            return "null";
        }
        return "start=" + positionSummary(page.start)
                + " next=" + positionSummary(page.next)
                + " len=" + page.content.length()
                + " hasPrev=" + page.hasPrevious
                + " hasNext=" + page.hasNext
                + " tail='" + sampleTail(page.content) + "'";
    }

    private static String positionSummary(Position position) {
        if (position == null) {
            return "null";
        }
        return "txt:" + position.byteOffset
                + "/epub:" + position.chapterIndex + ":" + position.charOffset
                + "/hint:" + position.progressHint;
    }

    private static String metricsSummary(PageMetrics metrics) {
        if (metrics == null) {
            return "null";
        }
        Paint.FontMetricsInt fm = metrics.paint.getFontMetricsInt();
        return metrics.width + "x" + metrics.height
                + " textSize=" + metrics.paint.getTextSize()
                + " lineSpacingPx=" + metrics.lineSpacingPx
                + " fm=" + fm.top + "," + fm.ascent + "," + fm.descent + "," + fm.bottom
                + " txt=" + metrics.txtPagination
                + " removeBreaks=" + metrics.txtRemoveLineBreaks;
    }

    private static void logLayoutForPage(String label, CharSequence text, PageMetrics metrics) {
        if (!DEBUG_PAGE_LOGS) {
            return;
        }
        StaticLayout layout = makeLayout(text == null ? "" : text, metrics);
        int lineCount = layout.getLineCount();
        if (lineCount <= 0) {
            logPage(label + " lines=0 len=" + (text == null ? 0 : text.length()) + " metrics=" + metricsSummary(metrics));
            return;
        }
        int last = lineCount - 1;
        Paint.FontMetricsInt fm = metrics.paint.getFontMetricsInt();
        int lineBottom = layout.getLineBottom(last);
        int glyphBottom = layout.getLineBaseline(last) + Math.max(fm.descent, fm.bottom);
        int neededBottom = Math.max(lineBottom, glyphBottom);
        logPage(label
                + " len=" + (text == null ? 0 : text.length())
                + " metrics=" + metricsSummary(metrics)
                + " lines=" + lineCount
                + " lastStartEnd=" + layout.getLineStart(last) + "-" + layout.getLineEnd(last)
                + " lineBottom=" + lineBottom
                + " glyphBottom=" + glyphBottom
                + " neededBottom=" + neededBottom
                + " fits=" + (neededBottom <= paginationHeight(metrics))
                + " last='" + sampleRange(text, layout.getLineStart(last), layout.getLineEnd(last)) + "'");
    }

    private static String sampleTail(CharSequence text) {
        if (text == null || text.length() == 0) {
            return "";
        }
        int start = Math.max(0, text.length() - 48);
        return sampleRange(text, start, text.length());
    }

    private static String sampleRange(CharSequence text, int start, int end) {
        if (text == null || text.length() == 0) {
            return "";
        }
        int safeStart = Math.max(0, Math.min(text.length(), start));
        int safeEnd = Math.max(safeStart, Math.min(text.length(), end));
        String value = text.subSequence(safeStart, safeEnd).toString();
        value = value.replace("\\", "\\\\")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t");
        if (value.length() > 80) {
            return value.substring(0, 80);
        }
        return value;
    }

    private interface ValueUpdater {
        void update(int value);
    }

    private interface BookSource {
        Position normalize(Position saved);

        Page loadPage(Position start, PageMetrics metrics) throws Exception;

        Page previousPage(Position currentStart, PageMetrics metrics) throws Exception;

        Position positionAtProgress(float progress);

        float progress(Position position);

        void close();
    }

    private static class ColorOption {
        final String name;
        final int color;

        ColorOption(String name, int color) {
            this.name = name;
            this.color = color;
        }
    }

    private static class ReaderPrefs {
        int backgroundColor = Color.parseColor("#F8F3E7");
        int textColor = Color.parseColor("#27221C");
        boolean backgroundCustom;
        boolean textCustom;
        boolean pdfNightMode = false;
        int pdfBackgroundColor = Color.BLACK;
        int pdfTextColor = Color.parseColor("#B8BDC2");
        boolean pdfBackgroundCustom;
        boolean pdfTextCustom;
        float fontSizeSp = 20f;
        int lineSpacingDp = 0;
        float letterSpacingEm = 0f;
        int horizontalMarginDp = 20;
        int verticalMarginDp = 20;
        int screenTimeoutMinutes = 0;
        boolean brightnessFollowSystem = true;
        int brightnessPercent = 60;
        boolean txtRemoveLineBreaks = false;
        boolean txtJustify = true;

        static ReaderPrefs load(SharedPreferences preferences) {
            ReaderPrefs prefs = new ReaderPrefs();
            int legacyMargin = preferences.getInt("reader_margin", 20);
            prefs.backgroundColor = preferences.getInt("reader_background", prefs.backgroundColor);
            prefs.textColor = preferences.getInt("reader_text_color", prefs.textColor);
            prefs.backgroundCustom = preferences.getBoolean("reader_background_custom", false);
            prefs.textCustom = preferences.getBoolean("reader_text_custom", false);
            prefs.pdfNightMode = preferences.getBoolean("pdf_night_mode", prefs.pdfNightMode);
            prefs.pdfBackgroundColor = preferences.getInt("pdf_background_color", prefs.pdfBackgroundColor);
            prefs.pdfTextColor = preferences.getInt("pdf_text_color", prefs.pdfTextColor);
            prefs.pdfBackgroundCustom = preferences.getBoolean("pdf_background_custom", false);
            prefs.pdfTextCustom = preferences.getBoolean("pdf_text_custom", false);
            prefs.fontSizeSp = preferences.getFloat("reader_font_size", prefs.fontSizeSp);
            prefs.lineSpacingDp = preferences.getInt("reader_line_spacing", prefs.lineSpacingDp);
            prefs.letterSpacingEm = preferences.getFloat("reader_letter_spacing", prefs.letterSpacingEm);
            prefs.horizontalMarginDp = preferences.getInt("reader_margin_horizontal", legacyMargin);
            prefs.verticalMarginDp = preferences.getInt("reader_margin_vertical", legacyMargin);
            prefs.screenTimeoutMinutes = preferences.getInt("reader_screen_timeout_minutes", prefs.screenTimeoutMinutes);
            prefs.brightnessFollowSystem = preferences.getBoolean("reader_brightness_follow_system", prefs.brightnessFollowSystem);
            prefs.brightnessPercent = Math.max(1, Math.min(100, preferences.getInt("reader_brightness_percent", prefs.brightnessPercent)));
            prefs.txtRemoveLineBreaks = preferences.getBoolean("reader_txt_remove_line_breaks", prefs.txtRemoveLineBreaks);
            prefs.txtJustify = preferences.getBoolean("reader_txt_justify", prefs.txtJustify);
            return prefs;
        }

        void save(SharedPreferences preferences) {
            preferences.edit()
                    .putInt("reader_background", backgroundColor)
                    .putInt("reader_text_color", textColor)
                    .putBoolean("reader_background_custom", backgroundCustom)
                    .putBoolean("reader_text_custom", textCustom)
                    .putBoolean("pdf_night_mode", pdfNightMode)
                    .putInt("pdf_background_color", pdfBackgroundColor)
                    .putInt("pdf_text_color", pdfTextColor)
                    .putBoolean("pdf_background_custom", pdfBackgroundCustom)
                    .putBoolean("pdf_text_custom", pdfTextCustom)
                    .putFloat("reader_font_size", fontSizeSp)
                    .putInt("reader_line_spacing", lineSpacingDp)
                    .putFloat("reader_letter_spacing", letterSpacingEm)
                    .putInt("reader_margin_horizontal", horizontalMarginDp)
                    .putInt("reader_margin_vertical", verticalMarginDp)
                    .putInt("reader_margin", Math.max(horizontalMarginDp, verticalMarginDp))
                    .putInt("reader_screen_timeout_minutes", screenTimeoutMinutes)
                    .putBoolean("reader_brightness_follow_system", brightnessFollowSystem)
                    .putInt("reader_brightness_percent", brightnessPercent)
                    .putBoolean("reader_txt_remove_line_breaks", txtRemoveLineBreaks)
                    .putBoolean("reader_txt_justify", txtJustify)
                    .apply();
        }
    }

    private static class Book {
        String id;
        String title;
        String type;
        String sourcePath;
        long size;
        long addedAt;
        long lastOpenedAt;
        float progress;
        long byteOffset;
        int chapterIndex;
        int charOffset;
        int pdfPage;
        float pdfPageFraction;
        int pdfCropMode = PdfReaderView.CROP_AUTO;
        float pdfCropLeft = 0f;
        float pdfCropTop = 0f;
        float pdfCropRight = 1f;
        float pdfCropBottom = 1f;
        int pdfOrientation;

        boolean isEpub() {
            return "epub".equals(type);
        }

        boolean isPdf() {
            return "pdf".equals(type);
        }

        RectF pdfManualCrop() {
            return PdfReaderView.sanitize(new RectF(pdfCropLeft, pdfCropTop, pdfCropRight, pdfCropBottom));
        }

        void setPdfManualCrop(RectF crop) {
            RectF safe = PdfReaderView.sanitize(crop);
            pdfCropLeft = safe.left;
            pdfCropTop = safe.top;
            pdfCropRight = safe.right;
            pdfCropBottom = safe.bottom;
        }

        Position toPosition() {
            if (isEpub()) {
                Position position = Position.epub(chapterIndex, charOffset);
                if (chapterIndex <= 0 && charOffset <= 0 && progress > 0f) {
                    position.progressHint = progress;
                }
                return position;
            }
            long fallback = byteOffset;
            if (fallback <= 0 && progress > 0f && size > 0) {
                fallback = Math.round(size * progress);
            }
            return Position.txt(fallback);
        }

        void applyPosition(Position position) {
            byteOffset = position.byteOffset;
            chapterIndex = position.chapterIndex;
            charOffset = position.charOffset;
        }

        JSONObject toJson() {
            JSONObject json = new JSONObject();
            try {
                json.put("id", id);
                json.put("title", title);
                json.put("type", type);
                json.put("sourcePath", sourcePath);
                json.put("size", size);
                json.put("addedAt", addedAt);
                json.put("lastOpenedAt", lastOpenedAt);
                json.put("progress", progress);
                json.put("byteOffset", byteOffset);
                json.put("chapterIndex", chapterIndex);
                json.put("charOffset", charOffset);
                json.put("pdfPage", pdfPage);
                json.put("pdfPageFraction", pdfPageFraction);
                json.put("pdfCropMode", pdfCropMode);
                json.put("pdfCropLeft", pdfCropLeft);
                json.put("pdfCropTop", pdfCropTop);
                json.put("pdfCropRight", pdfCropRight);
                json.put("pdfCropBottom", pdfCropBottom);
                json.put("pdfOrientation", pdfOrientation);
            } catch (JSONException ignored) {
            }
            return json;
        }

        static Book fromJson(JSONObject json) {
            Book book = new Book();
            book.id = json.optString("id");
            book.title = json.optString("title", "未命名书籍");
            book.type = json.optString("type", "txt");
            book.sourcePath = json.optString("sourcePath");
            book.size = json.optLong("size", 0);
            book.addedAt = json.optLong("addedAt", 0);
            book.lastOpenedAt = json.optLong("lastOpenedAt", 0);
            book.progress = (float) json.optDouble("progress", 0);
            book.byteOffset = json.optLong("byteOffset", 0);
            book.chapterIndex = json.optInt("chapterIndex", 0);
            book.charOffset = json.optInt("charOffset", 0);
            book.pdfPage = json.optInt("pdfPage", 0);
            book.pdfPageFraction = (float) json.optDouble("pdfPageFraction", 0);
            book.pdfCropMode = json.optInt("pdfCropMode", PdfReaderView.CROP_AUTO);
            book.pdfCropLeft = (float) json.optDouble("pdfCropLeft", 0);
            book.pdfCropTop = (float) json.optDouble("pdfCropTop", 0);
            book.pdfCropRight = (float) json.optDouble("pdfCropRight", 1);
            book.pdfCropBottom = (float) json.optDouble("pdfCropBottom", 1);
            book.pdfOrientation = json.optInt("pdfOrientation", 0);
            return book;
        }
    }

    private static class Position {
        long byteOffset;
        int chapterIndex;
        int charOffset;
        float progressHint = -1f;

        static Position txt(long byteOffset) {
            Position position = new Position();
            position.byteOffset = byteOffset;
            return position;
        }

        static Position epub(int chapterIndex, int charOffset) {
            Position position = new Position();
            position.chapterIndex = chapterIndex;
            position.charOffset = charOffset;
            return position;
        }

        Position copy() {
            Position position = new Position();
            position.byteOffset = byteOffset;
            position.chapterIndex = chapterIndex;
            position.charOffset = charOffset;
            position.progressHint = progressHint;
            return position;
        }
    }

    private static class Page {
        final CharSequence content;
        final Position start;
        final Position next;
        final boolean hasPrevious;
        final boolean hasNext;
        final boolean lastLineContinues;

        Page(CharSequence content, Position start, Position next, boolean hasPrevious, boolean hasNext) {
            this(content, start, next, hasPrevious, hasNext, false);
        }

        Page(CharSequence content, Position start, Position next, boolean hasPrevious, boolean hasNext, boolean lastLineContinues) {
            this.content = content == null ? "" : content;
            this.start = start;
            this.next = next;
            this.hasPrevious = hasPrevious;
            this.hasNext = hasNext;
            this.lastLineContinues = lastLineContinues;
        }
    }

    private static class PageMetrics {
        final int width;
        final int height;
        final TextPaint paint;
        final int lineSpacingPx;
        final boolean txtPagination;
        final boolean txtRemoveLineBreaks;

        PageMetrics(int width, int height, TextPaint paint, int lineSpacingPx, boolean txtPagination, boolean txtRemoveLineBreaks) {
            this.width = width;
            this.height = height;
            this.paint = paint;
            this.lineSpacingPx = lineSpacingPx;
            this.txtPagination = txtPagination;
            this.txtRemoveLineBreaks = txtRemoveLineBreaks;
        }

        static PageMetrics forProgress() {
            return new PageMetrics(720, 1080, new TextPaint(TextPaint.ANTI_ALIAS_FLAG), 0, false, false);
        }
    }

    private static class PageCut {
        final int start;
        final int end;

        PageCut(int start, int end) {
            this.start = start;
            this.end = end;
        }
    }

    private static class CenteredImageSpan extends ReplacementSpan {
        private final Drawable drawable;
        private final int lineWidth;

        CenteredImageSpan(Drawable drawable, int lineWidth) {
            this.drawable = drawable;
            this.lineWidth = Math.max(1, lineWidth);
        }

        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            if (fm != null) {
                int height = drawable.getBounds().height();
                fm.ascent = -height;
                fm.descent = 0;
                fm.top = fm.ascent;
                fm.bottom = 0;
            }
            return lineWidth;
        }

        @Override
        public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top, int y, int bottom, Paint paint) {
            int drawableWidth = drawable.getBounds().width();
            int drawableHeight = drawable.getBounds().height();
            int translateX = Math.round(x + Math.max(0, (lineWidth - drawableWidth) / 2f));
            int availableHeight = Math.max(drawableHeight, bottom - top);
            int translateY = top + Math.max(0, (availableHeight - drawableHeight) / 2);
            canvas.save();
            canvas.translate(translateX, translateY);
            drawable.draw(canvas);
            canvas.restore();
        }
    }

    private static class VerticalSpaceSpan extends ReplacementSpan {
        private final int height;

        VerticalSpaceSpan(int height) {
            this.height = Math.max(0, height);
        }

        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            if (fm != null) {
                fm.ascent = -height;
                fm.descent = 0;
                fm.top = fm.ascent;
                fm.bottom = 0;
            }
            return 0;
        }

        @Override
        public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top, int y, int bottom, Paint paint) {
        }
    }

    private static class TxtBookSource implements BookSource {
        private final File file;
        private final Charset charset;
        private final long dataStart;
        private final long fileLength;
        private final int maxBytesPerChar;
        private final ArrayList<Long> indexedPageStarts = new ArrayList<>();
        private String indexedMetricsKey = "";
        private boolean indexComplete;

        TxtBookSource(File file) throws IOException {
            this.file = file;
            this.fileLength = file.length();
            CharsetDetection detection = detectCharset(file);
            this.charset = detection.charset;
            this.dataStart = detection.dataStart;
            this.maxBytesPerChar = maxBytesForCharset(charset);
        }

        @Override
        public Position normalize(Position saved) {
            long offset = saved == null ? dataStart : saved.byteOffset;
            if (offset <= 0) {
                offset = dataStart;
            }
            return Position.txt(clampOffset(offset));
        }

        @Override
        public synchronized Page loadPage(Position start, PageMetrics metrics) throws Exception {
            long offset = clampOffset(start.byteOffset);
            String metricsKey = paginationMetricsKey(metrics);
            int pageIndex = metricsKey.equals(indexedMetricsKey)
                    ? Collections.binarySearch(indexedPageStarts, offset) : -1;
            Page page;
            if (pageIndex >= 0) {
                page = pageAtIndex(pageIndex, metrics);
            } else {
                if (offset >= fileLength && fileLength > dataStart) {
                    long pageStart = findPreviousPageStart(fileLength, metrics);
                    page = buildPageBetween(pageStart, fileLength, metrics);
                } else {
                    page = buildSinglePage(offset, metrics);
                }
                resetPageIndex(metricsKey, page.start.byteOffset);
                rememberNextPageStart(page);
                pageIndex = 0;
            }
            logLayoutForPage("TXT_FWD_LAYOUT", page.content, metrics);
            logPage("TXT_FWD offset=" + offset
                    + " pageIndex=" + pageIndex
                    + " indexedPages=" + indexedPageStarts.size()
                    + " complete=" + indexComplete
                    + " pageStart=" + page.start.byteOffset
                    + " next=" + (page.next == null ? -1 : page.next.byteOffset)
                    + " pageLen=" + page.content.length()
                    + " tail='" + sampleTail(page.content) + "'");
            return page;
        }

        @Override
        public synchronized Page previousPage(Position currentStart, PageMetrics metrics) throws Exception {
            long current = clampOffset(currentStart.byteOffset);
            String metricsKey = paginationMetricsKey(metrics);
            if (!metricsKey.equals(indexedMetricsKey)
                    || Collections.binarySearch(indexedPageStarts, current) < 0) {
                Page anchored = buildSinglePage(current, metrics);
                current = anchored.start.byteOffset;
                resetPageIndex(metricsKey, current);
                rememberNextPageStart(anchored);
            }
            if (current <= dataStart) {
                return loadPage(Position.txt(dataStart), metrics);
            }
            int currentIndex = Collections.binarySearch(indexedPageStarts, current);
            if (currentIndex == 0) {
                long previousStart = findPreviousPageStart(current, metrics);
                if (previousStart >= current) {
                    previousStart = dataStart;
                }
                if (previousStart == dataStart) {
                    Page fullFirstPage = buildSinglePage(dataStart, metrics);
                    long fullEnd = fullFirstPage.next == null ? fileLength : fullFirstPage.next.byteOffset;
                    if (fullEnd != current) {
                        resetPageIndex(metricsKey, fullFirstPage.start.byteOffset);
                        rememberNextPageStart(fullFirstPage);
                        return fullFirstPage;
                    }
                }
                indexedPageStarts.add(0, previousStart);
                currentIndex++;
            }
            int previousIndex = currentIndex - 1;
            Page page = pageAtIndex(previousIndex, metrics);
            logLayoutForPage("TXT_PREV_LAYOUT", page.content, metrics);
            logPage("TXT_PREV current=" + current
                    + " currentIndex=" + currentIndex
                    + " previousIndex=" + previousIndex
                    + " indexedPages=" + indexedPageStarts.size()
                    + " complete=" + indexComplete
                    + " pageStart=" + page.start.byteOffset
                    + " pageEnd=" + (page.next == null ? -1 : page.next.byteOffset)
                    + " gapToCurrent=" + (current - (page.next == null ? current : page.next.byteOffset))
                    + " pageLen=" + page.content.length()
                    + " tail='" + sampleTail(page.content) + "'");
            return page;
        }

        @Override
        public Position positionAtProgress(float progress) {
            float safeProgress = Math.max(0f, Math.min(1f, progress));
            long readable = Math.max(1L, fileLength - dataStart);
            return Position.txt(clampOffset(dataStart + Math.round(readable * safeProgress)));
        }

        @Override
        public float progress(Position position) {
            long offset = position == null ? dataStart : clampOffset(position.byteOffset);
            long readable = Math.max(1L, fileLength - dataStart);
            return Math.max(0f, Math.min(1f, (offset - dataStart) / (float) readable));
        }

        @Override
        public void close() {
        }

        private void resetPageIndex(String metricsKey, long anchor) {
            indexedMetricsKey = metricsKey;
            indexedPageStarts.clear();
            indexedPageStarts.add(anchor);
            indexComplete = anchor >= fileLength;
            logPage("TXT_INDEX_RESET key=" + metricsKey + " anchor=" + anchor);
        }

        private Page pageAtIndex(int index, PageMetrics metrics) throws IOException {
            int safeIndex = Math.max(0, Math.min(index, indexedPageStarts.size() - 1));
            long pageStart = indexedPageStarts.get(safeIndex);
            if (safeIndex + 1 < indexedPageStarts.size()) {
                return buildPageBetween(pageStart, indexedPageStarts.get(safeIndex + 1), metrics);
            }
            Page page = buildSinglePage(pageStart, metrics);
            rememberNextPageStart(page);
            return page;
        }

        private void rememberNextPageStart(Page page) {
            if (page.next == null || page.next.byteOffset >= fileLength) {
                indexComplete = true;
            } else if (page.next.byteOffset > indexedPageStarts.get(indexedPageStarts.size() - 1)) {
                indexedPageStarts.add(page.next.byteOffset);
            } else {
                indexComplete = true;
                logPage("TXT_INDEX_STOP start=" + page.start.byteOffset + " next=" + page.next.byteOffset);
            }
        }

        private Page buildPageBetween(long start, long end, PageMetrics metrics) throws IOException {
            TextWindow rawWindow = readRange(start, end - start, false);
            NormalizedTextWindow window = new NormalizedTextWindow(rawWindow, metrics.txtRemoveLineBreaks);
            Page page = pageFromCut(window, new PageCut(0, window.text.length()));
            boolean hasNext = end < fileLength;
            return new Page(page.content, Position.txt(start), hasNext ? Position.txt(end) : null,
                    start > dataStart, hasNext, page.lastLineContinues);
        }

        private long findPreviousPageStart(long end, PageMetrics metrics) throws IOException {
            long requestedBytes = TXT_BACKWARD_INITIAL_BYTES;
            while (true) {
                long start = Math.max(dataStart, end - requestedBytes);
                start = clampOffset(start);
                TextWindow rawWindow = readRange(start, end - start, false);
                NormalizedTextWindow window = new NormalizedTextWindow(rawWindow, metrics.txtRemoveLineBreaks);
                int textEnd = window.text.length();
                if (start <= dataStart || requestedBytes >= TXT_BACKWARD_MAX_BYTES
                        || !fitsWholePage(window.text, 0, textEnd, metrics)) {
                    int low = 0;
                    int high = textEnd;
                    while (low < high) {
                        int mid = low + (high - low) / 2;
                        if (fitsWholePage(window.text, mid, textEnd, metrics)) {
                            high = mid;
                        } else {
                            low = mid + 1;
                        }
                    }
                    int textStart = low;
                    if (textStart >= textEnd && textEnd > 0) {
                        textStart = Character.offsetByCodePoints(window.text, textEnd, -1);
                    } else if (textStart > 0 && textStart < textEnd
                            && Character.isLowSurrogate(window.text.charAt(textStart))
                            && Character.isHighSurrogate(window.text.charAt(textStart - 1))) {
                        textStart++;
                    }
                    long previous = rawWindow.startOffset
                            + encodedLength(window.rawSlice(0, textStart));
                    return Math.max(dataStart, Math.min(end, previous));
                }
                requestedBytes = Math.min(TXT_BACKWARD_MAX_BYTES, requestedBytes * 2);
            }
        }

        private static boolean fitsWholePage(CharSequence text, int start, int end, PageMetrics metrics) {
            int visibleEnd = trimTrailingPageWhitespace(text, start, end);
            if (visibleEnd <= start) {
                return true;
            }
            StaticLayout layout = makeLayout(text.subSequence(start, visibleEnd), metrics);
            return layout.getLineCount() > 0
                    && lineFitsPage(layout, layout.getLineCount() - 1, metrics, paginationHeight(metrics));
        }

        private Page buildSinglePage(long offset, PageMetrics metrics) throws IOException {
            long startOffset = clampOffset(offset);
            if (startOffset >= fileLength) {
                return new Page("", Position.txt(fileLength), null, fileLength > dataStart, false);
            }
            long requestedBytes = TXT_FORWARD_BYTES;
            while (true) {
                TextWindow rawWindow = readRange(startOffset, Math.min(requestedBytes, fileLength - startOffset), true);
                NormalizedTextWindow window = new NormalizedTextWindow(rawWindow, metrics.txtRemoveLineBreaks);
                PageCut cut = firstPage(window.text, metrics, 0);
                long decodedEnd = rawWindow.startOffset + encodedLength(rawWindow.text);
                boolean reachedEndOfFile = decodedEnd >= fileLength;
                boolean pageHasMoreTextAvailable = cut.end < window.text.length();
                if (pageHasMoreTextAvailable || reachedEndOfFile || requestedBytes >= TXT_FORWARD_MAX_BYTES) {
                    return pageFromCut(window, cut);
                }
                requestedBytes = Math.min(TXT_FORWARD_MAX_BYTES, requestedBytes * 2);
            }
        }

        private Page pageFromCut(NormalizedTextWindow window, PageCut cut) {
            int safeStart = Math.max(0, Math.min(window.text.length(), cut.start));
            int safeEnd = Math.max(safeStart, Math.min(window.text.length(), cut.end));
            int displayEnd = trimTrailingPageWhitespace(window.text, safeStart, safeEnd);
            String pageText = safeSubstring(window.text, safeStart, displayEnd);
            long pageStart = window.source.startOffset + encodedLength(window.rawSlice(0, safeStart));
            long pageEnd = window.source.startOffset + encodedLength(window.rawSlice(0, safeEnd));
            pageStart = clampOffset(pageStart);
            pageEnd = clampOffset(pageEnd);
            if (pageEnd <= pageStart && pageStart < fileLength) {
                pageEnd = Math.min(fileLength, pageStart + Math.max(1, maxBytesPerChar));
                pageEnd = clampOffset(pageEnd);
                if (pageEnd <= pageStart) {
                    pageEnd = fileLength;
                }
            }
            boolean hasNext = pageEnd < fileLength;
            return new Page(
                    pageText,
                    Position.txt(pageStart),
                    hasNext ? Position.txt(pageEnd) : null,
                    pageStart > dataStart,
                    hasNext,
                    hasNext && safeEnd > safeStart && window.text.charAt(safeEnd - 1) != '\n'
            );
        }

        private static String paginationMetricsKey(PageMetrics metrics) {
            float letterSpacing = 0f;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                letterSpacing = metrics.paint.getLetterSpacing();
            }
            return metrics.width + "|"
                    + metrics.height + "|"
                    + metrics.paint.getTextSize() + "|"
                    + metrics.lineSpacingPx + "|"
                    + letterSpacing + "|"
                    + metrics.txtRemoveLineBreaks + "|"
                    + metrics.txtPagination;
        }

        private TextWindow readRange(long offset, long requestedBytes, boolean extendTail) throws IOException {
            offset = clampOffset(offset);
            if (requestedBytes <= 0 || offset >= fileLength) {
                return new TextWindow(offset, "");
            }
            int extraBytes = extendTail && offset + requestedBytes < fileLength ? maxBytesPerChar : 0;
            int length = (int) Math.min(requestedBytes + extraBytes, fileLength - offset);
            byte[] bytes = new byte[length];
            try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                raf.seek(offset);
                raf.readFully(bytes);
            }
            return decodeTextWindow(bytes, offset, charset, maxBytesPerChar);
        }

        private long encodedLength(String text) {
            if (text == null || text.isEmpty()) {
                return 0;
            }
            return text.getBytes(charset).length;
        }

        private long clampOffset(long offset) {
            long clamped = Math.max(dataStart, Math.min(fileLength, offset));
            if (isUtf16Charset(charset) && ((clamped - dataStart) % 2L != 0L)) {
                clamped -= 1L;
            }
            return Math.max(dataStart, clamped);
        }
    }

    private static class EpubBookSource implements BookSource {
        private static final char IMAGE_PLACEHOLDER = '\uFFFC';

        private final ZipFile zip;
        private final Resources resources;
        private final ArrayList<String> entries = new ArrayList<>();
        private int cachedChapter = -1;
        private int cachedImageWidth = -1;
        private int cachedImageHeight = -1;
        private CharSequence cachedContent = "";

        EpubBookSource(File file, Resources resources) throws Exception {
            this.resources = resources;
            zip = new ZipFile(file);
            String opfPath = findOpfPath(zip);
            if (opfPath == null) {
                throw new IOException("EPUB 缺少 OPF 文件");
            }
            ZipEntry opfEntry = zip.getEntry(opfPath);
            if (opfEntry == null) {
                throw new IOException("EPUB 结构不完整");
            }
            Document opf = parseXml(readZipEntry(zip, opfEntry));
            String baseDir = baseDir(opfPath);
            Map<String, String> manifest = readManifest(opf);
            List<String> spine = readSpine(opf);
            for (String idref : spine) {
                String href = manifest.get(idref);
                if (href != null) {
                    String entryName = normalizeZipPath(baseDir + href);
                    if (getEntry(zip, entryName) != null) {
                        entries.add(entryName);
                    }
                }
            }
            if (entries.isEmpty()) {
                throw new IOException("EPUB 没有可阅读章节");
            }
        }

        @Override
        public Position normalize(Position saved) {
            if (saved == null) {
                return Position.epub(0, 0);
            }
            if (saved.progressHint > 0f && saved.chapterIndex <= 0 && saved.charOffset <= 0) {
                int hintedChapter = (int) Math.floor(Math.max(0f, Math.min(0.999f, saved.progressHint)) * entries.size());
                return Position.epub(Math.max(0, Math.min(entries.size() - 1, hintedChapter)), 0);
            }
            int chapter = Math.max(0, Math.min(entries.size() - 1, saved.chapterIndex));
            return Position.epub(chapter, Math.max(0, saved.charOffset));
        }

        @Override
        public synchronized Page loadPage(Position start, PageMetrics metrics) throws Exception {
            Position normalized = normalize(start);
            int chapter = normalized.chapterIndex;
            CharSequence content = loadChapter(chapter, metrics);
            int offset = Math.max(0, Math.min(content.length(), normalized.charOffset));
            while (offset >= content.length() && chapter < entries.size() - 1) {
                chapter++;
                content = loadChapter(chapter, metrics);
                offset = 0;
            }
            CharSequence window = safeSubSequence(content, offset, Math.min(content.length(), offset + EPUB_FORWARD_CHARS));
            PageCut cut = firstPage(window, metrics, 0);
            int end = offset + Math.max(0, cut.end);
            if (end <= offset && offset < content.length()) {
                end = Math.min(content.length(), offset + 1);
            }

            Position next = null;
            boolean hasNext;
            if (end < content.length()) {
                next = Position.epub(chapter, end);
                hasNext = true;
            } else if (chapter < entries.size() - 1) {
                next = Position.epub(chapter + 1, 0);
                hasNext = true;
            } else {
                hasNext = false;
            }

            return new Page(
                    safeSubSequence(content, offset, end),
                    Position.epub(chapter, offset),
                    next,
                    chapter > 0 || offset > 0,
                    hasNext
            );
        }

        @Override
        public synchronized Page previousPage(Position currentStart, PageMetrics metrics) throws Exception {
            Position normalized = normalize(currentStart);
            int chapter = normalized.chapterIndex;
            CharSequence content = loadChapter(chapter, metrics);
            int offset = Math.max(0, Math.min(content.length(), normalized.charOffset));
            if (chapter == 0 && offset <= 0) {
                return loadPage(Position.epub(0, 0), metrics);
            }
            if (offset <= 0) {
                chapter--;
                content = loadChapter(chapter, metrics);
                offset = content.length();
            }
            int windowStart = Math.max(0, offset - EPUB_BACKWARD_CHARS);
            CharSequence window = safeSubSequence(content, windowStart, offset);
            List<PageCut> cuts = allPages(window, metrics);
            if (cuts.isEmpty()) {
                return loadPage(Position.epub(chapter, 0), metrics);
            }
            for (int i = cuts.size() - 1; i >= 0; i--) {
                PageCut candidate = cuts.get(i);
                int pageStart = windowStart + candidate.start;
                int pageEnd = windowStart + candidate.end;
                if (pageStart >= offset || pageEnd > offset || candidate.end <= candidate.start) {
                    continue;
                }
                return new Page(
                        safeSubSequence(content, pageStart, pageEnd),
                        Position.epub(chapter, pageStart),
                        Position.epub(chapter, pageEnd),
                        chapter > 0 || pageStart > 0,
                        true
                );
            }
            return loadPage(Position.epub(chapter, 0), metrics);
        }

        @Override
        public synchronized Position positionAtProgress(float progress) {
            if (entries.isEmpty()) {
                return Position.epub(0, 0);
            }
            float safeProgress = Math.max(0f, Math.min(0.9999f, progress));
            float scaled = safeProgress * entries.size();
            int chapter = Math.max(0, Math.min(entries.size() - 1, (int) Math.floor(scaled)));
            float chapterProgress = Math.max(0f, Math.min(1f, scaled - chapter));
            int charOffset = 0;
            try {
                CharSequence content = loadChapter(chapter, PageMetrics.forProgress());
                charOffset = Math.max(0, Math.min(content.length(), Math.round(content.length() * chapterProgress)));
            } catch (Exception ignored) {
            }
            return Position.epub(chapter, charOffset);
        }

        @Override
        public synchronized float progress(Position position) {
            if (entries.isEmpty()) {
                return 0f;
            }
            Position normalized = normalize(position);
            float chapterProgress = 0f;
            try {
                CharSequence content;
                if (normalized.chapterIndex == cachedChapter) {
                    content = cachedContent;
                } else {
                    content = loadChapter(normalized.chapterIndex, PageMetrics.forProgress());
                }
                if (content.length() > 0) {
                    chapterProgress = Math.max(0f, Math.min(1f, normalized.charOffset / (float) content.length()));
                }
            } catch (Exception ignored) {
            }
            return Math.max(0f, Math.min(1f, (normalized.chapterIndex + chapterProgress) / entries.size()));
        }

        @Override
        public void close() {
            try {
                zip.close();
            } catch (IOException ignored) {
            }
        }

        private CharSequence loadChapter(int index, PageMetrics metrics) throws Exception {
            index = Math.max(0, Math.min(entries.size() - 1, index));
            int imageWidth = Math.max(1, metrics.width);
            int imageHeight = Math.max(1, metrics.height);
            if (index == cachedChapter && imageWidth == cachedImageWidth && imageHeight == cachedImageHeight) {
                return cachedContent;
            }
            ZipEntry entry = getEntry(zip, entries.get(index));
            if (entry == null) {
                cachedChapter = index;
                cachedImageWidth = imageWidth;
                cachedImageHeight = imageHeight;
                cachedContent = "";
                return cachedContent;
            }
            String html = decodeHtml(readZipEntry(zip, entry));
            cachedChapter = index;
            cachedImageWidth = imageWidth;
            cachedImageHeight = imageHeight;
            cachedContent = htmlToRichContent(html, entries.get(index), imageWidth, imageHeight);
            return cachedContent;
        }

        private CharSequence htmlToRichContent(String html, String chapterEntryName, int maxImageWidth, int maxImageHeight) {
            SpannableStringBuilder builder = new SpannableStringBuilder();
            Map<String, StyleSpec> cssRules = collectCssRules(html, chapterEntryName);
            ArrayList<StyleMark> openStyles = new ArrayList<>();
            String cleaned = html == null ? "" : html.replaceAll("(?is)<(script|style|head)[^>]*>.*?</\\1>", " ");
            Matcher matcher = Pattern.compile("(?is)<[^>]+>").matcher(cleaned);
            int last = 0;
            while (matcher.find()) {
                appendDecodedText(builder, cleaned.substring(last, matcher.start()));
                handleHtmlTag(builder, matcher.group(), chapterEntryName, maxImageWidth, maxImageHeight, cssRules, openStyles);
                last = matcher.end();
            }
            appendDecodedText(builder, cleaned.substring(last));
            while (!openStyles.isEmpty()) {
                closeStyle(builder, openStyles, openStyles.get(openStyles.size() - 1).tagName);
            }
            trimBuilder(builder);
            return builder;
        }

        private void handleHtmlTag(SpannableStringBuilder builder, String tag, String chapterEntryName, int maxImageWidth, int maxImageHeight, Map<String, StyleSpec> cssRules, ArrayList<StyleMark> openStyles) {
            String tagName = tagName(tag);
            if (tagName.length() == 0) {
                return;
            }
            boolean closing = isClosingTag(tag);
            boolean selfClosing = isSelfClosingTag(tag) || isVoidTag(tagName);
            if (closing) {
                if (isBlockTag(tagName)) {
                    appendNewline(builder);
                }
                closeStyle(builder, openStyles, tagName);
                return;
            }
            if ("br".equals(tagName)) {
                appendNewline(builder);
                return;
            }
            if ("img".equals(tagName) || "image".equals(tagName)) {
                String href = firstNonEmpty(
                        attribute(tag, "src"),
                        attribute(tag, "href"),
                        attribute(tag, "xlink:href"),
                        attribute(tag, "data-src")
                );
                appendImage(builder, href, chapterEntryName, maxImageWidth, maxImageHeight);
                return;
            }
            if (isBlockTag(tagName)) {
                appendNewline(builder);
            }
            if ("li".equals(tagName)) {
                appendText(builder, "\u2022 ");
            }
            StyleSpec style = styleForTag(tagName, tag, cssRules);
            if (style.hasAny() && !selfClosing) {
                openStyles.add(new StyleMark(tagName, builder.length(), style));
            }
        }

        private void appendImage(SpannableStringBuilder builder, String href, String chapterEntryName, int maxImageWidth, int maxImageHeight) {
            Drawable drawable = loadImageDrawable(href, chapterEntryName, maxImageWidth, maxImageHeight);
            appendNewline(builder);
            int start = builder.length();
            if (drawable != null) {
                builder.append(IMAGE_PLACEHOLDER);
                builder.setSpan(new CenteredImageSpan(drawable, maxImageWidth), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                builder.append("[图片]");
            }
            appendNewline(builder);
        }

        private Drawable loadImageDrawable(String href, String chapterEntryName, int maxImageWidth, int maxImageHeight) {
            if (href == null || href.trim().isEmpty()) {
                return null;
            }
            try {
                byte[] bytes = imageBytes(href.trim(), chapterEntryName);
                if (bytes == null || bytes.length == 0) {
                    return null;
                }
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    return null;
                }
                int targetMaxWidth = Math.max(1, maxImageWidth);
                int targetMaxHeight = Math.max(1, Math.round(maxImageHeight * 0.94f));
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, targetMaxWidth, targetMaxHeight);
                Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                if (bitmap == null) {
                    return null;
                }
                int width = bitmap.getWidth();
                int height = bitmap.getHeight();
                float scale = Math.min(targetMaxWidth / (float) width, targetMaxHeight / (float) height);
                scale = Math.max(0.05f, scale);
                int targetWidth = Math.max(1, Math.round(width * scale));
                int targetHeight = Math.max(1, Math.round(height * scale));
                if (targetWidth != width || targetHeight != height) {
                    bitmap = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true);
                }
                BitmapDrawable drawable = new BitmapDrawable(resources, bitmap);
                drawable.setBounds(0, 0, targetWidth, targetHeight);
                return drawable;
            } catch (Exception ignored) {
                return null;
            }
        }

        private byte[] imageBytes(String href, String chapterEntryName) throws IOException {
            return resourceBytes(href, chapterEntryName);
        }

        private byte[] resourceBytes(String href, String chapterEntryName) throws IOException {
            if (href == null) {
                return null;
            }
            if (href.startsWith("data:")) {
                int comma = href.indexOf(',');
                if (comma >= 0) {
                    String meta = href.substring(0, comma).toLowerCase(Locale.US);
                    String data = href.substring(comma + 1);
                    if (meta.contains(";base64")) {
                        return Base64.decode(data, Base64.DEFAULT);
                    }
                    return URLDecoder.decode(data, "UTF-8").getBytes(StandardCharsets.UTF_8);
                }
                return null;
            }
            String cleanHref = stripHrefSuffix(href);
            String entryName;
            if (cleanHref.startsWith("/")) {
                entryName = normalizeZipPath(cleanHref);
            } else {
                entryName = normalizeZipPath(baseDir(chapterEntryName) + cleanHref);
            }
            ZipEntry imageEntry = getEntry(zip, entryName);
            if (imageEntry == null) {
                return null;
            }
            return readZipEntry(zip, imageEntry);
        }

        private static int sampleSize(int width, int height, int targetWidth, int targetHeight) {
            int sample = 1;
            while ((width / (sample * 2)) >= targetWidth || (height / (sample * 2)) >= targetHeight) {
                sample *= 2;
            }
            return Math.max(1, sample);
        }

        private Map<String, StyleSpec> collectCssRules(String html, String chapterEntryName) {
            LinkedHashMap<String, StyleSpec> rules = new LinkedHashMap<>();
            if (html == null || html.isEmpty()) {
                return rules;
            }
            Matcher styleMatcher = Pattern.compile("(?is)<style\\b[^>]*>(.*?)</style>").matcher(html);
            while (styleMatcher.find()) {
                parseCssRulesInto(rules, styleMatcher.group(1));
            }
            Matcher linkMatcher = Pattern.compile("(?is)<link\\b[^>]*>").matcher(html);
            while (linkMatcher.find()) {
                String linkTag = linkMatcher.group();
                String href = attribute(linkTag, "href");
                String rel = attribute(linkTag, "rel");
                String type = attribute(linkTag, "type");
                String lowerHref = href == null ? "" : href.toLowerCase(Locale.US);
                boolean stylesheet = (rel != null && rel.toLowerCase(Locale.US).contains("stylesheet"))
                        || (type != null && type.toLowerCase(Locale.US).contains("css"))
                        || lowerHref.endsWith(".css");
                if (href == null || !stylesheet) {
                    continue;
                }
                try {
                    byte[] bytes = resourceBytes(href.trim(), chapterEntryName);
                    if (bytes != null && bytes.length > 0) {
                        parseCssRulesInto(rules, decodeHtml(bytes));
                    }
                } catch (Exception ignored) {
                }
            }
            return rules;
        }

        private static void parseCssRulesInto(Map<String, StyleSpec> rules, String css) {
            if (css == null || css.isEmpty()) {
                return;
            }
            String cleaned = css.replaceAll("(?is)/\\*.*?\\*/", " ");
            Matcher matcher = Pattern.compile("(?is)([^{}]+)\\{([^{}]*)\\}").matcher(cleaned);
            while (matcher.find()) {
                StyleSpec spec = parseCssDeclarations(matcher.group(2));
                if (!spec.hasDefined()) {
                    continue;
                }
                String[] selectors = matcher.group(1).split(",");
                for (String selector : selectors) {
                    String normalized = normalizeCssSelector(selector);
                    if (!normalized.isEmpty()) {
                        mergeCssRule(rules, normalized, spec);
                    }
                }
            }
        }

        private static void mergeCssRule(Map<String, StyleSpec> rules, String selector, StyleSpec spec) {
            StyleSpec existing = rules.get(selector);
            if (existing == null) {
                rules.put(selector, spec.copy());
            } else {
                existing.merge(spec);
            }
        }

        private static String normalizeCssSelector(String selector) {
            if (selector == null) {
                return "";
            }
            String normalized = selector.trim().toLowerCase(Locale.US);
            if (normalized.isEmpty() || normalized.startsWith("@")) {
                return "";
            }
            normalized = normalized.replaceAll("(?is)::?[a-z0-9_-]+(\\([^)]*\\))?", "");
            String[] parts = normalized.split("\\s+|>|\\+|~");
            normalized = parts.length == 0 ? "" : parts[parts.length - 1].trim();
            if (normalized.startsWith("*")) {
                normalized = normalized.substring(1);
            }
            int namespace = normalized.indexOf('|');
            if (namespace >= 0) {
                normalized = normalized.substring(namespace + 1);
            }
            if (normalized.equals("*")) {
                return "";
            }
            Matcher matcher = Pattern.compile("^([a-z0-9_-]+)?([.#][a-z0-9_-]+)?").matcher(normalized);
            if (matcher.find()) {
                String simple = matcher.group();
                return simple == null ? "" : simple;
            }
            return "";
        }

        private static void appendDecodedText(SpannableStringBuilder builder, String htmlText) {
            if (htmlText == null || htmlText.isEmpty()) {
                return;
            }
            String text;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                text = Html.fromHtml(htmlText, Html.FROM_HTML_MODE_LEGACY).toString();
            } else {
                //noinspection deprecation
                text = Html.fromHtml(htmlText).toString();
            }
            text = text
                    .replace('\u00A0', ' ')
                    .replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                    .replaceAll(" *\\n *", "\n")
                    .replaceAll("\\n{3,}", "\n\n");
            appendText(builder, text);
        }

        private static void appendText(SpannableStringBuilder builder, String text) {
            if (text == null || text.isEmpty()) {
                return;
            }
            for (int i = 0; i < text.length(); i++) {
                char ch = text.charAt(i);
                if (ch == '\n') {
                    appendNewline(builder);
                } else {
                    builder.append(ch);
                }
            }
        }

        private static void appendNewline(SpannableStringBuilder builder) {
            int length = builder.length();
            if (length == 0) {
                return;
            }
            char last = builder.charAt(length - 1);
            if (last != '\n') {
                builder.append('\n');
            }
        }

        private static void trimBuilder(SpannableStringBuilder builder) {
            while (builder.length() > 0 && Character.isWhitespace(builder.charAt(0))) {
                builder.delete(0, 1);
            }
            while (builder.length() > 0 && Character.isWhitespace(builder.charAt(builder.length() - 1))) {
                builder.delete(builder.length() - 1, builder.length());
            }
        }

        private static StyleSpec styleForTag(String tagName, String tag, Map<String, StyleSpec> cssRules) {
            StyleSpec style = defaultTagStyle(tagName);
            mergeCssSelector(style, cssRules, tagName);

            String classValue = attribute(tag, "class");
            if (classValue != null) {
                String[] classes = classValue.trim().split("\\s+");
                for (String className : classes) {
                    if (className.isEmpty()) {
                        continue;
                    }
                    String normalizedClass = className.toLowerCase(Locale.US);
                    mergeCssSelector(style, cssRules, "." + normalizedClass);
                    mergeCssSelector(style, cssRules, tagName + "." + normalizedClass);
                }
            }

            String id = attribute(tag, "id");
            if (id != null && !id.trim().isEmpty()) {
                String normalizedId = id.trim().toLowerCase(Locale.US);
                mergeCssSelector(style, cssRules, "#" + normalizedId);
                mergeCssSelector(style, cssRules, tagName + "#" + normalizedId);
            }

            style.merge(attributeStyle(tag));
            style.merge(parseCssDeclarations(attribute(tag, "style")));
            return style;
        }

        private static void mergeCssSelector(StyleSpec target, Map<String, StyleSpec> rules, String selector) {
            if (rules == null || selector == null) {
                return;
            }
            StyleSpec spec = rules.get(selector.toLowerCase(Locale.US));
            if (spec != null) {
                target.merge(spec);
            }
        }

        private static StyleSpec defaultTagStyle(String tagName) {
            StyleSpec style = new StyleSpec();
            if ("b".equals(tagName) || "strong".equals(tagName)) {
                style.bold = true;
            } else if ("i".equals(tagName) || "em".equals(tagName) || "cite".equals(tagName) || "dfn".equals(tagName)) {
                style.italic = true;
            } else if ("u".equals(tagName) || "ins".equals(tagName)) {
                style.underline = true;
            } else if ("s".equals(tagName) || "strike".equals(tagName) || "del".equals(tagName)) {
                style.strike = true;
            } else if ("code".equals(tagName) || "kbd".equals(tagName) || "samp".equals(tagName) || "pre".equals(tagName)) {
                style.monospace = true;
            } else if ("sup".equals(tagName)) {
                style.superscript = true;
            } else if ("sub".equals(tagName)) {
                style.subscript = true;
            } else if ("small".equals(tagName)) {
                style.relativeSize = 0.85f;
            } else if ("big".equals(tagName)) {
                style.relativeSize = 1.18f;
            } else if ("blockquote".equals(tagName)) {
                style.leadingMarginPx = 36;
            } else if ("a".equals(tagName)) {
                style.underline = true;
                style.foregroundColor = Color.parseColor("#3A6EA5");
            } else if (tagName.matches("h[1-6]")) {
                style.bold = true;
                int level = tagName.charAt(1) - '0';
                float[] sizes = {1.65f, 1.48f, 1.32f, 1.18f, 1.08f, 1.0f};
                style.relativeSize = sizes[Math.max(0, Math.min(sizes.length - 1, level - 1))];
            }
            return style;
        }

        private static StyleSpec attributeStyle(String tag) {
            StyleSpec style = new StyleSpec();
            Integer color = parseCssColor(attribute(tag, "color"));
            if (color != null) {
                style.foregroundColor = color;
            }
            Integer background = parseCssColor(firstNonEmpty(attribute(tag, "bgcolor"), attribute(tag, "background")));
            if (background != null) {
                style.backgroundColor = background;
            }
            String align = attribute(tag, "align");
            Layout.Alignment alignment = alignmentFor(align);
            if (alignment != null) {
                style.alignment = alignment;
            }
            String face = attribute(tag, "face");
            if (face != null && isMonospaceFamily(face)) {
                style.monospace = true;
            }
            Float fontSize = parseHtmlFontSize(attribute(tag, "size"));
            if (fontSize != null) {
                style.relativeSize = fontSize;
            }
            return style;
        }

        private static StyleSpec parseCssDeclarations(String declarations) {
            StyleSpec style = new StyleSpec();
            if (declarations == null || declarations.trim().isEmpty()) {
                return style;
            }
            String[] parts = declarations.split(";");
            for (String part : parts) {
                int colon = part.indexOf(':');
                if (colon <= 0) {
                    continue;
                }
                String property = part.substring(0, colon).trim().toLowerCase(Locale.US);
                String value = cleanCssValue(part.substring(colon + 1));
                String lowerValue = value.toLowerCase(Locale.US);
                if ("font-weight".equals(property)) {
                    style.bold = parseFontWeight(lowerValue);
                } else if ("font-style".equals(property)) {
                    style.italic = parseFontStyle(lowerValue);
                } else if ("text-decoration".equals(property) || "text-decoration-line".equals(property)) {
                    parseTextDecoration(style, lowerValue);
                } else if ("font-size".equals(property)) {
                    Float size = parseCssFontSize(lowerValue);
                    if (size != null) {
                        style.relativeSize = size;
                    }
                } else if ("color".equals(property)) {
                    Integer color = parseCssColor(value);
                    if (color != null) {
                        style.foregroundColor = color;
                    }
                } else if ("background-color".equals(property)) {
                    Integer color = parseCssColor(value);
                    if (color != null) {
                        style.backgroundColor = color;
                    }
                } else if ("background".equals(property)) {
                    Integer color = parseBackgroundColor(value);
                    if (color != null) {
                        style.backgroundColor = color;
                    }
                } else if ("font-family".equals(property)) {
                    if (isMonospaceFamily(lowerValue)) {
                        style.monospace = true;
                    }
                } else if ("text-align".equals(property)) {
                    Layout.Alignment alignment = alignmentFor(lowerValue);
                    if (alignment != null) {
                        style.alignment = alignment;
                    }
                } else if ("vertical-align".equals(property)) {
                    if (lowerValue.contains("super")) {
                        style.superscript = true;
                    } else if (lowerValue.contains("sub")) {
                        style.subscript = true;
                    }
                } else if ("margin-left".equals(property) || "padding-left".equals(property) || "text-indent".equals(property)) {
                    Integer margin = parseCssLengthPx(lowerValue);
                    if (margin != null && margin > 0) {
                        style.leadingMarginPx = Math.max(style.leadingMarginPx == null ? 0 : style.leadingMarginPx, margin);
                    }
                }
            }
            return style;
        }

        private static String cleanCssValue(String value) {
            if (value == null) {
                return "";
            }
            String cleaned = value.trim();
            int important = cleaned.toLowerCase(Locale.US).indexOf("!important");
            if (important >= 0) {
                cleaned = cleaned.substring(0, important).trim();
            }
            return cleaned;
        }

        private static Boolean parseFontWeight(String value) {
            if (value == null || value.isEmpty()) {
                return null;
            }
            if (value.contains("normal") || value.contains("lighter")) {
                return false;
            }
            if (value.contains("bold") || value.contains("bolder")) {
                return true;
            }
            Float number = parseLeadingFloat(value);
            if (number != null) {
                return number >= 600f;
            }
            return null;
        }

        private static Boolean parseFontStyle(String value) {
            if (value == null || value.isEmpty()) {
                return null;
            }
            if (value.contains("normal")) {
                return false;
            }
            if (value.contains("italic") || value.contains("oblique")) {
                return true;
            }
            return null;
        }

        private static void parseTextDecoration(StyleSpec style, String value) {
            if (value == null || value.isEmpty()) {
                return;
            }
            if (value.contains("none")) {
                style.underline = false;
                style.strike = false;
                return;
            }
            if (value.contains("underline")) {
                style.underline = true;
            }
            if (value.contains("line-through")) {
                style.strike = true;
            }
        }

        private static Float parseCssFontSize(String value) {
            if (value == null || value.isEmpty()) {
                return null;
            }
            if ("xx-small".equals(value)) {
                return 0.65f;
            }
            if ("x-small".equals(value)) {
                return 0.75f;
            }
            if ("small".equals(value)) {
                return 0.85f;
            }
            if ("medium".equals(value)) {
                return 1f;
            }
            if ("large".equals(value)) {
                return 1.18f;
            }
            if ("x-large".equals(value)) {
                return 1.35f;
            }
            if ("xx-large".equals(value)) {
                return 1.55f;
            }
            if ("smaller".equals(value)) {
                return 0.85f;
            }
            if ("larger".equals(value)) {
                return 1.18f;
            }
            Float number = parseLeadingFloat(value);
            if (number == null) {
                return null;
            }
            float relative;
            if (value.endsWith("%")) {
                relative = number / 100f;
            } else if (value.endsWith("em") || value.endsWith("rem")) {
                relative = number;
            } else if (value.endsWith("pt")) {
                relative = number / 12f;
            } else if (value.endsWith("px")) {
                relative = number / 16f;
            } else {
                relative = number / 16f;
            }
            return Math.max(0.55f, Math.min(2.2f, relative));
        }

        private static Float parseHtmlFontSize(String value) {
            if (value == null || value.trim().isEmpty()) {
                return null;
            }
            String trimmed = value.trim();
            try {
                int size;
                if (trimmed.startsWith("+") || trimmed.startsWith("-")) {
                    size = 3 + Integer.parseInt(trimmed);
                } else {
                    size = Integer.parseInt(trimmed);
                }
                float[] sizes = {0.7f, 0.82f, 1f, 1.15f, 1.3f, 1.48f, 1.65f};
                return sizes[Math.max(0, Math.min(sizes.length - 1, size - 1))];
            } catch (Exception ignored) {
                return null;
            }
        }

        private static Integer parseCssLengthPx(String value) {
            Float number = parseLeadingFloat(value);
            if (number == null) {
                return null;
            }
            float px;
            if (value.endsWith("em") || value.endsWith("rem")) {
                px = number * 24f;
            } else if (value.endsWith("pt")) {
                px = number * 1.333f;
            } else if (value.endsWith("%")) {
                px = number * 0.24f;
            } else {
                px = number;
            }
            return Math.max(0, Math.min(120, Math.round(px)));
        }

        private static Float parseLeadingFloat(String value) {
            if (value == null) {
                return null;
            }
            Matcher matcher = Pattern.compile("[-+]?[0-9]*\\.?[0-9]+").matcher(value.trim());
            if (!matcher.find()) {
                return null;
            }
            try {
                return Float.parseFloat(matcher.group());
            } catch (Exception ignored) {
                return null;
            }
        }

        private static Integer parseBackgroundColor(String value) {
            if (value == null) {
                return null;
            }
            String[] tokens = value.split("\\s+");
            for (String token : tokens) {
                Integer color = parseCssColor(token);
                if (color != null) {
                    return color;
                }
            }
            return null;
        }

        private static Integer parseCssColor(String value) {
            if (value == null) {
                return null;
            }
            String cleaned = cleanCssValue(value).trim();
            if (cleaned.isEmpty()) {
                return null;
            }
            String lower = cleaned.toLowerCase(Locale.US);
            if ("inherit".equals(lower) || "initial".equals(lower) || "currentcolor".equals(lower) || "transparent".equals(lower) || "none".equals(lower)) {
                return null;
            }
            if (lower.startsWith("#")) {
                String hex = lower.substring(1);
                try {
                    if (hex.length() == 3) {
                        return Color.rgb(
                                Integer.parseInt(hex.substring(0, 1) + hex.substring(0, 1), 16),
                                Integer.parseInt(hex.substring(1, 2) + hex.substring(1, 2), 16),
                                Integer.parseInt(hex.substring(2, 3) + hex.substring(2, 3), 16)
                        );
                    }
                    if (hex.length() == 6) {
                        return Color.parseColor("#" + hex);
                    }
                } catch (Exception ignored) {
                    return null;
                }
            }
            Matcher rgb = Pattern.compile("(?is)rgba?\\s*\\(([^)]*)\\)").matcher(lower);
            if (rgb.find()) {
                String[] values = rgb.group(1).split(",");
                if (values.length >= 3) {
                    Integer r = parseColorChannel(values[0]);
                    Integer g = parseColorChannel(values[1]);
                    Integer b = parseColorChannel(values[2]);
                    if (r != null && g != null && b != null) {
                        return Color.rgb(r, g, b);
                    }
                }
            }
            try {
                return Color.parseColor(lower);
            } catch (Exception ignored) {
                if ("grey".equals(lower)) {
                    return Color.GRAY;
                }
                if ("darkgrey".equals(lower)) {
                    return Color.DKGRAY;
                }
                if ("lightgrey".equals(lower)) {
                    return Color.LTGRAY;
                }
                return null;
            }
        }

        private static Integer parseColorChannel(String value) {
            String cleaned = value == null ? "" : value.trim();
            Float number = parseLeadingFloat(cleaned);
            if (number == null) {
                return null;
            }
            if (cleaned.endsWith("%")) {
                return Math.max(0, Math.min(255, Math.round(number * 2.55f)));
            }
            return Math.max(0, Math.min(255, Math.round(number)));
        }

        private static Layout.Alignment alignmentFor(String value) {
            if (value == null) {
                return null;
            }
            String lower = value.trim().toLowerCase(Locale.US);
            if (lower.contains("center")) {
                return Layout.Alignment.ALIGN_CENTER;
            }
            if (lower.contains("right") || lower.contains("end")) {
                return Layout.Alignment.ALIGN_OPPOSITE;
            }
            if (lower.contains("left") || lower.contains("start") || lower.contains("justify")) {
                return Layout.Alignment.ALIGN_NORMAL;
            }
            return null;
        }

        private static boolean isMonospaceFamily(String value) {
            if (value == null) {
                return false;
            }
            String lower = value.toLowerCase(Locale.US);
            return lower.contains("monospace")
                    || lower.contains("courier")
                    || lower.contains("consolas")
                    || lower.contains("menlo");
        }

        private static void closeStyle(SpannableStringBuilder builder, ArrayList<StyleMark> openStyles, String tagName) {
            for (int i = openStyles.size() - 1; i >= 0; i--) {
                StyleMark mark = openStyles.get(i);
                if (mark.tagName.equals(tagName)) {
                    openStyles.remove(i);
                    mark.apply(builder, builder.length());
                    return;
                }
            }
        }

        private static class StyleSpec {
            Boolean bold;
            Boolean italic;
            Boolean underline;
            Boolean strike;
            Boolean monospace;
            Boolean superscript;
            Boolean subscript;
            Float relativeSize;
            Integer foregroundColor;
            Integer backgroundColor;
            Layout.Alignment alignment;
            Integer leadingMarginPx;

            StyleSpec copy() {
                StyleSpec copy = new StyleSpec();
                copy.merge(this);
                return copy;
            }

            void merge(StyleSpec other) {
                if (other == null) {
                    return;
                }
                if (other.bold != null) {
                    bold = other.bold;
                }
                if (other.italic != null) {
                    italic = other.italic;
                }
                if (other.underline != null) {
                    underline = other.underline;
                }
                if (other.strike != null) {
                    strike = other.strike;
                }
                if (other.monospace != null) {
                    monospace = other.monospace;
                }
                if (other.superscript != null) {
                    superscript = other.superscript;
                    if (other.superscript) {
                        subscript = false;
                    }
                }
                if (other.subscript != null) {
                    subscript = other.subscript;
                    if (other.subscript) {
                        superscript = false;
                    }
                }
                if (other.relativeSize != null) {
                    relativeSize = other.relativeSize;
                }
                if (other.foregroundColor != null) {
                    foregroundColor = other.foregroundColor;
                }
                if (other.backgroundColor != null) {
                    backgroundColor = other.backgroundColor;
                }
                if (other.alignment != null) {
                    alignment = other.alignment;
                }
                if (other.leadingMarginPx != null) {
                    leadingMarginPx = other.leadingMarginPx;
                }
            }

            boolean hasDefined() {
                return bold != null
                        || italic != null
                        || underline != null
                        || strike != null
                        || monospace != null
                        || superscript != null
                        || subscript != null
                        || relativeSize != null
                        || foregroundColor != null
                        || backgroundColor != null
                        || alignment != null
                        || leadingMarginPx != null;
            }

            boolean hasAny() {
                return Boolean.TRUE.equals(bold)
                        || Boolean.TRUE.equals(italic)
                        || Boolean.TRUE.equals(underline)
                        || Boolean.TRUE.equals(strike)
                        || Boolean.TRUE.equals(monospace)
                        || Boolean.TRUE.equals(superscript)
                        || Boolean.TRUE.equals(subscript)
                        || relativeSize != null
                        || foregroundColor != null
                        || backgroundColor != null
                        || alignment != null
                        || leadingMarginPx != null;
            }
        }

        private static class StyleMark {
            final String tagName;
            final int start;
            final StyleSpec style;

            StyleMark(String tagName, int start, StyleSpec style) {
                this.tagName = tagName;
                this.start = start;
                this.style = style;
            }

            void apply(SpannableStringBuilder builder, int end) {
                int safeStart = Math.max(0, Math.min(start, builder.length()));
                int safeEnd = Math.max(safeStart, Math.min(end, builder.length()));
                if (safeEnd <= safeStart || style == null || !style.hasAny()) {
                    return;
                }
                int flags = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE;
                boolean bold = Boolean.TRUE.equals(style.bold);
                boolean italic = Boolean.TRUE.equals(style.italic);
                if (bold || italic) {
                    int textStyle = bold && italic ? Typeface.BOLD_ITALIC : (bold ? Typeface.BOLD : Typeface.ITALIC);
                    builder.setSpan(new StyleSpan(textStyle), safeStart, safeEnd, flags);
                }
                if (Boolean.TRUE.equals(style.underline)) {
                    builder.setSpan(new UnderlineSpan(), safeStart, safeEnd, flags);
                }
                if (Boolean.TRUE.equals(style.strike)) {
                    builder.setSpan(new StrikethroughSpan(), safeStart, safeEnd, flags);
                }
                if (Boolean.TRUE.equals(style.monospace)) {
                    builder.setSpan(new TypefaceSpan("monospace"), safeStart, safeEnd, flags);
                }
                if (Boolean.TRUE.equals(style.superscript)) {
                    builder.setSpan(new SuperscriptSpan(), safeStart, safeEnd, flags);
                    builder.setSpan(new RelativeSizeSpan(0.72f), safeStart, safeEnd, flags);
                }
                if (Boolean.TRUE.equals(style.subscript)) {
                    builder.setSpan(new SubscriptSpan(), safeStart, safeEnd, flags);
                    builder.setSpan(new RelativeSizeSpan(0.72f), safeStart, safeEnd, flags);
                }
                if (style.relativeSize != null && Math.abs(style.relativeSize - 1f) > 0.01f) {
                    builder.setSpan(new RelativeSizeSpan(style.relativeSize), safeStart, safeEnd, flags);
                }
                if (style.foregroundColor != null) {
                    builder.setSpan(new ForegroundColorSpan(style.foregroundColor), safeStart, safeEnd, flags);
                }
                if (style.backgroundColor != null) {
                    builder.setSpan(new BackgroundColorSpan(style.backgroundColor), safeStart, safeEnd, flags);
                }
                if (style.alignment != null) {
                    builder.setSpan(new AlignmentSpan.Standard(style.alignment), safeStart, safeEnd, flags);
                }
                if (style.leadingMarginPx != null && style.leadingMarginPx > 0) {
                    builder.setSpan(new LeadingMarginSpan.Standard(style.leadingMarginPx), safeStart, safeEnd, flags);
                }
            }
        }

        private static String tagName(String tag) {
            Matcher matcher = Pattern.compile("(?is)^<\\s*/?\\s*([a-zA-Z0-9:_-]+)").matcher(tag);
            if (!matcher.find()) {
                return "";
            }
            String name = matcher.group(1).toLowerCase(Locale.US);
            int colon = name.indexOf(':');
            if (colon >= 0) {
                name = name.substring(colon + 1);
            }
            return name;
        }

        private static boolean isClosingTag(String tag) {
            return tag != null && Pattern.compile("(?is)^<\\s*/").matcher(tag).find();
        }

        private static boolean isSelfClosingTag(String tag) {
            return tag != null && Pattern.compile("(?is)/\\s*>$").matcher(tag).find();
        }

        private static boolean isVoidTag(String tagName) {
            return "br".equals(tagName)
                    || "img".equals(tagName)
                    || "image".equals(tagName)
                    || "hr".equals(tagName)
                    || "input".equals(tagName)
                    || "meta".equals(tagName)
                    || "link".equals(tagName)
                    || "base".equals(tagName)
                    || "source".equals(tagName)
                    || "area".equals(tagName)
                    || "col".equals(tagName)
                    || "param".equals(tagName)
                    || "wbr".equals(tagName);
        }

        private static boolean isBlockTag(String tagName) {
            return "p".equals(tagName)
                    || "div".equals(tagName)
                    || "h1".equals(tagName)
                    || "h2".equals(tagName)
                    || "h3".equals(tagName)
                    || "h4".equals(tagName)
                    || "h5".equals(tagName)
                    || "h6".equals(tagName)
                    || "li".equals(tagName)
                    || "tr".equals(tagName)
                    || "section".equals(tagName)
                    || "article".equals(tagName)
                    || "blockquote".equals(tagName);
        }

        private static String firstNonEmpty(String... values) {
            for (String value : values) {
                if (value != null && !value.trim().isEmpty()) {
                    return value.trim();
                }
            }
            return null;
        }

        private static String attribute(String tag, String name) {
            Matcher quoted = Pattern.compile("(?is)\\b" + Pattern.quote(name) + "\\s*=\\s*([\"'])(.*?)\\1").matcher(tag);
            if (quoted.find()) {
                return quoted.group(2);
            }
            Matcher bare = Pattern.compile("(?is)\\b" + Pattern.quote(name) + "\\s*=\\s*([^\\s>/]+)").matcher(tag);
            if (bare.find()) {
                return bare.group(1);
            }
            return null;
        }

        private static String stripHrefSuffix(String href) {
            int hash = href.indexOf('#');
            if (hash >= 0) {
                href = href.substring(0, hash);
            }
            int query = href.indexOf('?');
            if (query >= 0) {
                href = href.substring(0, query);
            }
            return href;
        }

        private static String findOpfPath(ZipFile zip) throws Exception {
            ZipEntry container = zip.getEntry("META-INF/container.xml");
            if (container != null) {
                Document document = parseXml(readZipEntry(zip, container));
                NodeList rootFiles = elements(document, "rootfile");
                for (int i = 0; i < rootFiles.getLength(); i++) {
                    Node node = rootFiles.item(i);
                    if (node instanceof Element) {
                        String fullPath = ((Element) node).getAttribute("full-path");
                        if (fullPath != null && !fullPath.trim().isEmpty()) {
                            return normalizeZipPath(fullPath.trim());
                        }
                    }
                }
            }

            for (ZipEntry entry : java.util.Collections.list(zip.entries())) {
                if (!entry.isDirectory() && entry.getName().toLowerCase(Locale.US).endsWith(".opf")) {
                    return entry.getName();
                }
            }
            return null;
        }

        private static Map<String, String> readManifest(Document document) {
            Map<String, String> manifest = new HashMap<>();
            NodeList items = elements(document, "item");
            for (int i = 0; i < items.getLength(); i++) {
                Node node = items.item(i);
                if (!(node instanceof Element)) {
                    continue;
                }
                Element item = (Element) node;
                String id = item.getAttribute("id");
                String href = item.getAttribute("href");
                String media = item.getAttribute("media-type");
                String lowerHref = href == null ? "" : href.toLowerCase(Locale.US);
                boolean readable = media.contains("html")
                        || lowerHref.endsWith(".xhtml")
                        || lowerHref.endsWith(".html")
                        || lowerHref.endsWith(".htm");
                if (id != null && !id.isEmpty() && href != null && !href.isEmpty() && readable) {
                    manifest.put(id, href);
                }
            }
            return manifest;
        }

        private static List<String> readSpine(Document document) {
            ArrayList<String> spine = new ArrayList<>();
            NodeList itemRefs = elements(document, "itemref");
            for (int i = 0; i < itemRefs.getLength(); i++) {
                Node node = itemRefs.item(i);
                if (node instanceof Element) {
                    String idref = ((Element) node).getAttribute("idref");
                    if (idref != null && !idref.isEmpty()) {
                        spine.add(idref);
                    }
                }
            }
            return spine;
        }

        private static ZipEntry getEntry(ZipFile zip, String entryName) {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry != null) {
                return entry;
            }
            try {
                String decoded = URLDecoder.decode(entryName, "UTF-8");
                return zip.getEntry(decoded);
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    private static class CharsetDetection {
        final Charset charset;
        final long dataStart;

        CharsetDetection(Charset charset, long dataStart) {
            this.charset = charset;
            this.dataStart = dataStart;
        }
    }

    private static class TextWindow {
        final long startOffset;
        final String text;

        TextWindow(long startOffset, String text) {
            this.startOffset = startOffset;
            this.text = text == null ? "" : text;
        }
    }

    private static class NormalizedTextWindow {
        final TextWindow source;
        final String text;
        final int[] rawIndexByTextIndex;

        NormalizedTextWindow(TextWindow source, boolean removeLineBreaks) {
            this.source = source == null ? new TextWindow(0, "") : source;
            String raw = this.source.text == null ? "" : this.source.text;
            StringBuilder normalized = new StringBuilder(raw.length());
            int[] rawIndexes = new int[raw.length() + 1];
            int out = 0;
            for (int i = 0; i < raw.length(); i++) {
                rawIndexes[out] = i;
                char ch = raw.charAt(i);
                if (ch == '\r') {
                    normalized.append(removeLineBreaks ? ' ' : '\n');
                    out++;
                    while (removeLineBreaks && i + 1 < raw.length() && isRawLineBreak(raw.charAt(i + 1))) {
                        i++;
                    }
                    if (!removeLineBreaks && i + 1 < raw.length() && raw.charAt(i + 1) == '\n') {
                        i++;
                    }
                } else if (ch == '\n' && removeLineBreaks) {
                    normalized.append(' ');
                    out++;
                    while (i + 1 < raw.length() && isRawLineBreak(raw.charAt(i + 1))) {
                        i++;
                    }
                } else {
                    normalized.append(ch);
                    out++;
                }
            }
            rawIndexes[out] = raw.length();
            this.text = normalized.toString();
            this.rawIndexByTextIndex = Arrays.copyOf(rawIndexes, out + 1);
        }

        int rawIndexAt(int textIndex) {
            if (rawIndexByTextIndex.length == 0) {
                return 0;
            }
            int safeIndex = Math.max(0, Math.min(rawIndexByTextIndex.length - 1, textIndex));
            return rawIndexByTextIndex[safeIndex];
        }

        String rawSlice(int start, int end) {
            return safeSubstring(source.text, rawIndexAt(start), rawIndexAt(end));
        }

        long rawByteLengthAt(int textIndex, Charset charset) {
            return safeSubstring(source.text, 0, rawIndexAt(textIndex)).getBytes(charset).length;
        }

        int textIndexAtRawByteLength(long byteLength, Charset charset) {
            if (byteLength <= 0) {
                return 0;
            }
            int low = 0;
            int high = text.length();
            while (low < high) {
                int mid = low + (high - low) / 2;
                if (rawByteLengthAt(mid, charset) < byteLength) {
                    low = mid + 1;
                } else {
                    high = mid;
                }
            }
            return Math.max(0, Math.min(text.length(), low));
        }

        private static boolean isRawLineBreak(char ch) {
            return ch == '\r' || ch == '\n';
        }
    }

    private static PageCut firstPage(CharSequence text, PageMetrics metrics, int start) {
        if (text == null || text.length() == 0 || start >= text.length()) {
            return new PageCut(start, start);
        }
        int safeStart = Math.max(0, Math.min(text.length(), start));
        CharSequence slice = text.subSequence(safeStart, text.length());
        StaticLayout layout = makeLayout(slice, metrics);
        int lineCount = layout.getLineCount();
        if (lineCount == 0) {
            int end = Math.min(text.length(), safeStart + 1);
            return new PageCut(safeStart, end);
        }
        int pageHeight = paginationHeight(metrics);
        int lastLine = 0;
        for (int i = 0; i < lineCount; i++) {
            if (lineFitsPage(layout, i, metrics, pageHeight)) {
                lastLine = i;
            } else {
                break;
            }
        }
        int end = safeStart + layout.getLineEnd(lastLine);
        if (end <= safeStart) {
            end = Math.min(text.length(), safeStart + 1);
        }
        return new PageCut(safeStart, Math.min(text.length(), end));
    }

    private static int paginationHeight(PageMetrics metrics) {
        if (metrics == null) {
            return 1;
        }
        if (!metrics.txtPagination) {
            return metrics.height;
        }
        return metrics.height;
    }

    private static boolean lineFitsPage(StaticLayout layout, int line, PageMetrics metrics, int pageHeight) {
        int lineBottom = layout.getLineBottom(line);
        Paint.FontMetricsInt fontMetrics = metrics.paint.getFontMetricsInt();
        int glyphBottom = layout.getLineBaseline(line) + Math.max(fontMetrics.descent, fontMetrics.bottom);
        return Math.max(lineBottom, glyphBottom) <= pageHeight;
    }

    private static List<PageCut> allPages(CharSequence text, PageMetrics metrics) {
        ArrayList<PageCut> cuts = new ArrayList<>();
        int start = 0;
        while (text != null && start < text.length()) {
            PageCut cut = firstPage(text, metrics, start);
            if (cut.end <= start) {
                break;
            }
            cuts.add(cut);
            start = cut.end;
        }
        return cuts;
    }

    private static StaticLayout makeLayout(CharSequence text, PageMetrics metrics) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return StaticLayout.Builder.obtain(text, 0, text.length(), metrics.paint, metrics.width)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(metrics.lineSpacingPx, 1f)
                    .setIncludePad(false)
                    .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                    .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                    .build();
        }
        // minSdk is 23, this is only kept for completeness.
        //noinspection deprecation
        return new StaticLayout(text, metrics.paint, metrics.width, Layout.Alignment.ALIGN_NORMAL, 1f, metrics.lineSpacingPx, false);
    }

    private static String safeSubstring(String text, int start, int end) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        int safeStart = Math.max(0, Math.min(text.length(), start));
        int safeEnd = Math.max(safeStart, Math.min(text.length(), end));
        return text.substring(safeStart, safeEnd);
    }

    private static int trimTrailingPageWhitespace(CharSequence text, int start, int end) {
        if (text == null || text.length() == 0) {
            return 0;
        }
        int safeStart = Math.max(0, Math.min(text.length(), start));
        int safeEnd = Math.max(safeStart, Math.min(text.length(), end));
        while (safeEnd > safeStart && Character.isWhitespace(text.charAt(safeEnd - 1))) {
            safeEnd--;
        }
        return safeEnd;
    }

    private static CharSequence safeSubSequence(CharSequence text, int start, int end) {
        if (text == null || text.length() == 0) {
            return "";
        }
        int safeStart = Math.max(0, Math.min(text.length(), start));
        int safeEnd = Math.max(safeStart, Math.min(text.length(), end));
        return text.subSequence(safeStart, safeEnd);
    }

    private static SpannableStringBuilder trimDisplayEdges(CharSequence text) {
        if (text == null || text.length() == 0) {
            return new SpannableStringBuilder();
        }
        int start = 0;
        int end = text.length();
        while (start < end && Character.isWhitespace(text.charAt(start))) {
            start++;
        }
        while (end > start && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        return new SpannableStringBuilder(text.subSequence(start, end));
    }

    private static boolean isBlank(CharSequence text) {
        if (text == null || text.length() == 0) {
            return true;
        }
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\uFFFC') {
                return false;
            }
            if (!Character.isWhitespace(ch)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isImageFocusedPage(CharSequence text) {
        if (!(text instanceof Spanned) || text.length() == 0) {
            return false;
        }
        Spanned spanned = (Spanned) text;
        CenteredImageSpan[] imageSpans = spanned.getSpans(0, text.length(), CenteredImageSpan.class);
        if (imageSpans.length == 0) {
            return false;
        }
        int textChars = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isWhitespace(ch)) {
                continue;
            }
            if (ch == '\uFFFC' && hasCenteredImageAt(spanned, i)) {
                continue;
            }
            textChars++;
            if (textChars > IMAGE_PAGE_MAX_TEXT_CHARS) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasCenteredImageAt(Spanned spanned, int index) {
        return spanned.getSpans(index, index + 1, CenteredImageSpan.class).length > 0;
    }

    private static boolean isImageOnlyPage(CharSequence text) {
        if (text == null || text.length() == 0) {
            return false;
        }
        boolean hasImage = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\uFFFC') {
                hasImage = true;
                continue;
            }
            if (!Character.isWhitespace(ch)) {
                return false;
            }
        }
        return hasImage;
    }

    private static String normalizeTextForDisplay(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static CharsetDetection detectCharset(File file) throws IOException {
        int readLength = (int) Math.min(file.length(), 512 * 1024);
        byte[] sample = new byte[readLength];
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            if (readLength > 0) {
                raf.readFully(sample);
            }
        }
        if (sample.length >= 3
                && (sample[0] & 0xFF) == 0xEF
                && (sample[1] & 0xFF) == 0xBB
                && (sample[2] & 0xFF) == 0xBF) {
            return new CharsetDetection(StandardCharsets.UTF_8, 3);
        }
        if (sample.length >= 2
                && (sample[0] & 0xFF) == 0xFF
                && (sample[1] & 0xFF) == 0xFE) {
            return new CharsetDetection(StandardCharsets.UTF_16LE, 2);
        }
        if (sample.length >= 2
                && (sample[0] & 0xFF) == 0xFE
                && (sample[1] & 0xFF) == 0xFF) {
            return new CharsetDetection(StandardCharsets.UTF_16BE, 2);
        }
        if (looksLikeUtf16(sample, true)) {
            return new CharsetDetection(StandardCharsets.UTF_16LE, 0);
        }
        if (looksLikeUtf16(sample, false)) {
            return new CharsetDetection(StandardCharsets.UTF_16BE, 0);
        }
        if (sample.length == 0 || strictDecodeTrimmingTail(sample, 0, sample.length, StandardCharsets.UTF_8, 4) != null) {
            return new CharsetDetection(StandardCharsets.UTF_8, 0);
        }

        Charset best = preferredSimplifiedChineseCharset();
        int bestScore = Integer.MIN_VALUE;
        String[] candidateNames = new String[]{"GB18030", "GBK", "GB2312", "Big5"};
        for (String name : candidateNames) {
            Charset candidate = charsetOrNull(name);
            if (candidate == null) {
                continue;
            }
            String decoded = strictDecodeTrimmingTail(sample, 0, sample.length, candidate, maxBytesForCharset(candidate));
            if (decoded == null) {
                decoded = new String(sample, candidate);
            }
            int score = scoreDecodedText(decoded);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return new CharsetDetection(best, 0);
    }

    private static TextWindow decodeTextWindow(byte[] bytes, long fileOffset, Charset charset, int maxBytesPerChar) {
        if (bytes == null || bytes.length == 0) {
            return new TextWindow(fileOffset, "");
        }

        int maxSkip = Math.min(Math.max(0, maxBytesPerChar - 1), bytes.length - 1);
        for (int skip = 0; skip <= maxSkip; skip++) {
            String decoded = strictDecodeTrimmingTail(bytes, skip, bytes.length, charset, maxBytesPerChar);
            if (decoded != null) {
                return new TextWindow(fileOffset + skip, decoded);
            }
        }
        return new TextWindow(fileOffset, new String(bytes, charset));
    }

    private static String strictDecodeTrimmingTail(byte[] bytes, int start, int end, Charset charset, int maxTailTrim) {
        int maxTrim = Math.min(Math.max(0, maxTailTrim), Math.max(0, end - start));
        for (int trim = 0; trim <= maxTrim; trim++) {
            String decoded = strictDecode(bytes, start, end - trim, charset);
            if (decoded != null) {
                return decoded;
            }
        }
        return null;
    }

    private static String strictDecode(byte[] bytes, int start, int end, Charset charset) {
        if (bytes == null || start < 0 || end < start || start > bytes.length || end > bytes.length) {
            return null;
        }
        try {
            CharsetDecoder decoder = charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            return decoder.decode(ByteBuffer.wrap(bytes, start, end - start)).toString();
        } catch (CharacterCodingException ignored) {
            return null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static boolean looksLikeUtf16(byte[] sample, boolean littleEndian) {
        if (sample == null || sample.length < 64) {
            return false;
        }
        int pairs = sample.length / 2;
        int zeroInExpectedPosition = 0;
        int zeroInOtherPosition = 0;
        for (int i = 0; i + 1 < sample.length; i += 2) {
            int first = sample[i] & 0xFF;
            int second = sample[i + 1] & 0xFF;
            if (littleEndian) {
                if (second == 0) {
                    zeroInExpectedPosition++;
                }
                if (first == 0) {
                    zeroInOtherPosition++;
                }
            } else {
                if (first == 0) {
                    zeroInExpectedPosition++;
                }
                if (second == 0) {
                    zeroInOtherPosition++;
                }
            }
        }
        return zeroInExpectedPosition > pairs / 5 && zeroInExpectedPosition > zeroInOtherPosition * 3;
    }

    private static int scoreDecodedText(String text) {
        if (text == null || text.isEmpty()) {
            return Integer.MIN_VALUE;
        }
        int score = 0;
        int limit = Math.min(text.length(), 12000);
        for (int i = 0; i < limit; i++) {
            char ch = text.charAt(i);
            if (ch == '\uFFFD') {
                score -= 100;
            } else if (ch == '\n' || ch == '\r' || ch == '\t') {
                score += 1;
            } else if (ch < 32) {
                score -= 40;
            } else if (isCjk(ch)) {
                score += 6;
            } else if (ch >= 32 && ch < 127) {
                score += 2;
            } else if ("，。！？；：“”‘’（）《》、—…".indexOf(ch) >= 0) {
                score += 4;
            } else {
                score += 1;
            }
        }
        return score;
    }

    private static boolean isCjk(char ch) {
        return (ch >= '\u3400' && ch <= '\u9FFF') || (ch >= '\uF900' && ch <= '\uFAFF');
    }

    private static Charset preferredSimplifiedChineseCharset() {
        Charset charset = charsetOrNull("GB18030");
        if (charset != null) {
            return charset;
        }
        charset = charsetOrNull("GBK");
        if (charset != null) {
            return charset;
        }
        return StandardCharsets.UTF_8;
    }

    private static Charset charsetOrNull(String name) {
        try {
            return Charset.forName(name);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static int maxBytesForCharset(Charset charset) {
        String name = charset.name().toUpperCase(Locale.US);
        if (name.contains("GB18030") || name.contains("UTF-8") || name.contains("UTF-16")) {
            return 4;
        }
        return 2;
    }

    private static boolean isUtf16Charset(Charset charset) {
        return charset.name().toUpperCase(Locale.US).contains("UTF-16");
    }

    private static byte[] readZipEntry(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream input = zip.getInputStream(entry)) {
            return readAll(input);
        }
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static String decodeHtml(byte[] bytes) {
        String prefix = new String(bytes, 0, Math.min(bytes.length, 4096), StandardCharsets.ISO_8859_1);
        Matcher matcher = Pattern.compile("(?i)charset\\s*=\\s*['\"]?([A-Za-z0-9_\\-]+)").matcher(prefix);
        if (matcher.find()) {
            try {
                return new String(bytes, Charset.forName(matcher.group(1)));
            } catch (Exception ignored) {
            }
        }
        try {
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException ignored) {
            return new String(bytes, preferredSimplifiedChineseCharset());
        }
    }

    private static String htmlToText(String html) {
        String cleaned = html
                .replaceAll("(?is)<(script|style|head)[^>]*>.*?</\\1>", " ")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</(p|div|h[1-6]|li|tr|section|article|blockquote)>", "\n")
                .replaceAll("(?i)<(p|div|h[1-6]|li|tr|section|article|blockquote)[^>]*>", "\n");
        CharSequence sequence;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            sequence = Html.fromHtml(cleaned, Html.FROM_HTML_MODE_LEGACY);
        } else {
            //noinspection deprecation
            sequence = Html.fromHtml(cleaned);
        }
        return sequence.toString()
                .replace('\u00A0', ' ')
                .replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .replaceAll(" *\\n *", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private static Document parseXml(byte[] bytes) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setExpandEntityReferences(false);
        setFeatureIfSupported(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
        setFeatureIfSupported(factory, "http://xml.org/sax/features/external-general-entities", false);
        setFeatureIfSupported(factory, "http://xml.org/sax/features/external-parameter-entities", false);
        setFeatureIfSupported(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(new ByteArrayInputStream(bytes));
    }

    private static void setFeatureIfSupported(DocumentBuilderFactory factory, String feature, boolean value) {
        try {
            factory.setFeature(feature, value);
        } catch (Exception ignored) {
        }
    }

    private static NodeList elements(Document document, String localName) {
        NodeList list = document.getElementsByTagNameNS("*", localName);
        if (list.getLength() > 0) {
            return list;
        }
        return document.getElementsByTagName(localName);
    }

    private static String baseDir(String path) {
        int slash = path.lastIndexOf('/');
        if (slash < 0) {
            return "";
        }
        return path.substring(0, slash + 1);
    }

    private static String normalizeZipPath(String path) {
        path = path.replace('\\', '/');
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        String[] parts = path.split("/");
        ArrayList<String> clean = new ArrayList<>();
        for (String part : parts) {
            if (part.length() == 0 || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                if (!clean.isEmpty()) {
                    clean.remove(clean.size() - 1);
                }
            } else {
                clean.add(part);
            }
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < clean.size(); i++) {
            if (i > 0) {
                builder.append('/');
            }
            builder.append(clean.get(i));
        }
        return builder.toString();
    }
}
