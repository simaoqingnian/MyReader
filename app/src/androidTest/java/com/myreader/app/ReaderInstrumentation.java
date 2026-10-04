package com.myreader.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Layout;
import android.text.TextPaint;
import android.util.TypedValue;
import android.view.View;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class ReaderInstrumentation extends Instrumentation {
    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override
    public void onStart() {
        Bundle result = new Bundle();
        try {
            verifyBookFileFilter();
            runOnMainSync(() -> {
                verifyStableLines("天地玄黄宇宙洪荒日月盈昃辰宿列张\n寒来暑往秋收冬藏天地玄黄宇宙洪荒", false);
                verifyStableLines("天地玄黄宇宙洪荒日月盈昃辰宿列张 寒来暑往秋收冬藏天地玄黄宇宙洪荒", true);
                verifyPageTail();
            });
            verifyPagingModes();
            result.putString("stream", "Book file filter and TXT pagination passed\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", "TXT pagination failed: " + error + "\n");
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void verifyBookFileFilter() throws Exception {
        File directory = new File(getTargetContext().getCacheDir(), "book-picker-test");
        require(directory.mkdir(), "failed to create picker test directory");
        File folder = new File(directory, "Novels");
        File txt = new File(directory, "novel.TXT");
        File epub = new File(directory, "book.epub");
        File pdf = new File(directory, "notes.Pdf");
        File video = new File(directory, "video.mp4");
        File archive = new File(directory, "archive.zip");
        try {
            require(folder.mkdir(), "failed to create picker test folder");
            require(txt.createNewFile() && epub.createNewFile() && pdf.createNewFile()
                    && video.createNewFile() && archive.createNewFile(), "failed to create picker test files");
            List<File> visible = BookFilePicker.listVisibleEntries(directory);
            require(visible != null && visible.size() == 4, "picker included unsupported files");
            require(visible.get(0).equals(folder), "picker did not keep folder first");
            require(visible.contains(txt) && visible.contains(epub) && visible.contains(pdf),
                    "picker hid a supported file");
        } finally {
            folder.delete();
            txt.delete();
            epub.delete();
            pdf.delete();
            video.delete();
            archive.delete();
            directory.delete();
        }
    }

    private void verifyStableLines(String content, boolean removedBreaks) {
        TxtJustifiedTextView view = newView(content);
        view.setLastLineContinues(true);
        Layout before = view.getLayout();
        int[] lineEnds = new int[before.getLineCount()];
        for (int i = 0; i < lineEnds.length; i++) {
            lineEnds[i] = before.getLineEnd(i);
        }
        int beforeRight = rightmostInk(view, 0);
        view.setJustification(true);
        Layout after = view.getLayout();
        require(after.getLineCount() == lineEnds.length, "line count changed, removedBreaks=" + removedBreaks);
        for (int i = 0; i < lineEnds.length; i++) {
            require(after.getLineEnd(i) == lineEnds[i], "line end changed, removedBreaks=" + removedBreaks);
        }
        int afterRight = rightmostInk(view, 0);
        require(afterRight >= beforeRight, "right edge moved left, removedBreaks=" + removedBreaks);
    }

    private void verifyPageTail() {
        TxtJustifiedTextView view = newView("天地玄黄宇宙洪荒日");
        view.setJustification(true);
        view.setLastLineContinues(false);
        int paragraphRight = rightmostInk(view, 0);
        view.setLastLineContinues(true);
        int continuedRight = rightmostInk(view, 0);
        require(continuedRight > paragraphRight + 12, "continued page line was not justified");
    }

    private void verifyPagingModes() throws Exception {
        StringBuilder text = new StringBuilder("第一回 天地玄黄宇宙洪荒\n");
        for (int i = 0; i < 240; i++) {
            text.append("第").append(i).append("段 春江潮水连海平，海上明月共潮生。天地玄黄宇宙洪荒。\r\n");
        }
        for (boolean utf16 : new boolean[]{false, true}) {
            File book = File.createTempFile("myreader-paging-", ".txt", getTargetContext().getCacheDir());
            try {
                try (FileOutputStream output = new FileOutputStream(book)) {
                    if (utf16) {
                        output.write(0xff);
                        output.write(0xfe);
                    }
                    output.write(text.toString().getBytes(utf16 ? StandardCharsets.UTF_16LE : StandardCharsets.UTF_8));
                }
                verifyPagingFile(book, utf16);
            } finally {
                require(book.delete(), "failed to remove paging test file");
            }
        }
    }

    private void verifyPagingFile(File book, boolean utf16) throws Exception {
        long dataStart = utf16 ? 2L : 0L;
        Class<?> sourceClass = Class.forName("com.myreader.app.MainActivity$TxtBookSource");
        Class<?> positionClass = Class.forName("com.myreader.app.MainActivity$Position");
        Class<?> metricsClass = Class.forName("com.myreader.app.MainActivity$PageMetrics");
        Constructor<?> sourceConstructor = sourceClass.getDeclaredConstructor(File.class);
        sourceConstructor.setAccessible(true);
        Constructor<?> metricsConstructor = metricsClass.getDeclaredConstructor(
                int.class, int.class, TextPaint.class, int.class, boolean.class, boolean.class);
        metricsConstructor.setAccessible(true);
        Method txtPosition = positionClass.getDeclaredMethod("txt", long.class);
        txtPosition.setAccessible(true);
        Method loadPage = sourceClass.getDeclaredMethod("loadPage", positionClass, metricsClass);
        loadPage.setAccessible(true);
        Method previousPage = sourceClass.getDeclaredMethod("previousPage", positionClass, metricsClass);
        previousPage.setAccessible(true);

        Object source = sourceConstructor.newInstance(book);
        for (boolean removeBreaks : new boolean[]{false, true, false}) {
            TextPaint paint = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
            paint.setTextSize(40);
            Object metrics = metricsConstructor.newInstance(410, 410, paint, 0, true, removeBreaks);
            Object start = txtPosition.invoke(null, 0L);
            ArrayList<PageSnapshot> pages = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                PageSnapshot page = new PageSnapshot(loadPage.invoke(source, start, metrics));
                if (!pages.isEmpty()) {
                    require(page.startOffset == pages.get(pages.size() - 1).nextOffset,
                            "page gap in removeBreaks=" + removeBreaks);
                }
                pages.add(page);
                if (page.next == null) {
                    break;
                }
                start = page.next;
            }
            require(pages.size() >= 20, "insufficient test pages");
            require(pages.get(0).content.startsWith("第一回"), "first page lost its beginning");
            for (int i = pages.size() - 1; i > 0; i--) {
                PageSnapshot backward = new PageSnapshot(
                        previousPage.invoke(source, pages.get(i).start, metrics));
                require(backward.sameAs(pages.get(i - 1)),
                        "backward page changed at " + i + ", removeBreaks=" + removeBreaks);
                PageSnapshot forward = new PageSnapshot(loadPage.invoke(source, backward.next, metrics));
                require(forward.sameAs(pages.get(i)),
                        "forward page changed at " + i + ", removeBreaks=" + removeBreaks);
            }

            TextPaint largerPaint = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
            largerPaint.setTextSize(52);
            Object reflowMetrics = metricsConstructor.newInstance(380, 390, largerPaint, 0, true, removeBreaks);
            PageSnapshot anchored = new PageSnapshot(
                    loadPage.invoke(source, pages.get(19).start, reflowMetrics));
            require(anchored.startOffset == pages.get(19).startOffset,
                    "reflow moved the current page start, removeBreaks=" + removeBreaks);

            PageSnapshot current = anchored;
            boolean reachedHome = false;
            for (int i = 0; i < 120 && current.startOffset > dataStart; i++) {
                PageSnapshot previous = new PageSnapshot(previousPage.invoke(source, current.start, reflowMetrics));
                if (previous.startOffset == dataStart && previous.nextOffset != current.startOffset) {
                    require(!previous.hasPrevious && previous.content.startsWith("第一回"),
                            "short home page was displayed, removeBreaks=" + removeBreaks);
                    current = previous;
                    reachedHome = true;
                    break;
                }
                require(previous.startOffset < current.startOffset && previous.nextOffset == current.startOffset,
                        "backward reflow boundary changed, removeBreaks=" + removeBreaks);
                PageSnapshot forward = new PageSnapshot(loadPage.invoke(source, previous.next, reflowMetrics));
                require(forward.sameAs(current),
                        "forward reflow page changed at " + i + ", removeBreaks=" + removeBreaks);
                current = previous;
            }
            require(current.startOffset == dataStart && current.content.startsWith("第一回"),
                    "backward reflow did not reach document start, removeBreaks=" + removeBreaks);
            PageSnapshot firstPage = new PageSnapshot(previousPage.invoke(source, current.start, reflowMetrics));
            require(firstPage.startOffset == dataStart && !firstPage.hasPrevious
                            && firstPage.sameAs(current),
                    "full first page changed, removeBreaks=" + removeBreaks);
            if (reachedHome && firstPage.next != null) {
                PageSnapshot nextFromHome = new PageSnapshot(loadPage.invoke(source, firstPage.next, reflowMetrics));
                require(nextFromHome.startOffset == firstPage.nextOffset,
                        "home page skipped text, removeBreaks=" + removeBreaks);
            }

            long nearStartOffset = utf16
                    ? 2L + "第一回 天地玄".length() * 2L
                    : "第一回 天地玄".getBytes(StandardCharsets.UTF_8).length;
            Object nearStartPosition = txtPosition.invoke(null, nearStartOffset);
            PageSnapshot nearStart = new PageSnapshot(loadPage.invoke(source, nearStartPosition, reflowMetrics));
            require(nearStart.startOffset == nearStartOffset,
                    "direct page anchor moved, removeBreaks=" + removeBreaks);
            PageSnapshot full = new PageSnapshot(previousPage.invoke(source, nearStart.start, reflowMetrics));
            require(full.startOffset == dataStart && !full.hasPrevious && full.nextOffset > nearStartOffset,
                    "previous did not show a full home page, removeBreaks=" + removeBreaks);
            PageSnapshot nextFromHome = new PageSnapshot(loadPage.invoke(source, full.next, reflowMetrics));
            require(nextFromHome.startOffset == full.nextOffset,
                    "forward from home skipped text, removeBreaks=" + removeBreaks);
        }
    }

    private static final class PageSnapshot {
        final Object start;
        final Object next;
        final long startOffset;
        final long nextOffset;
        final String content;
        final boolean hasPrevious;

        PageSnapshot(Object page) throws Exception {
            Class<?> pageClass = page.getClass();
            Field contentField = pageClass.getDeclaredField("content");
            Field startField = pageClass.getDeclaredField("start");
            Field nextField = pageClass.getDeclaredField("next");
            Field previousField = pageClass.getDeclaredField("hasPrevious");
            contentField.setAccessible(true);
            startField.setAccessible(true);
            nextField.setAccessible(true);
            previousField.setAccessible(true);
            content = contentField.get(page).toString();
            start = startField.get(page);
            next = nextField.get(page);
            hasPrevious = previousField.getBoolean(page);
            startOffset = offset(start);
            nextOffset = next == null ? -1 : offset(next);
        }

        boolean sameAs(PageSnapshot other) {
            return startOffset == other.startOffset
                    && nextOffset == other.nextOffset
                    && content.equals(other.content);
        }

        private static long offset(Object position) throws Exception {
            Field field = position.getClass().getDeclaredField("byteOffset");
            field.setAccessible(true);
            return field.getLong(position);
        }
    }

    private TxtJustifiedTextView newView(String text) {
        TxtJustifiedTextView view = new TxtJustifiedTextView(getTargetContext());
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, 40);
        view.setIncludeFontPadding(false);
        view.setTextColor(Color.BLACK);
        view.setBackgroundColor(Color.WHITE);
        view.setText(text);
        int width = 410;
        int height = 900;
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
        return view;
    }

    private int rightmostInk(TxtJustifiedTextView view, int line) {
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(bitmap);
            view.draw(canvas);
            Layout layout = view.getLayout();
            int top = Math.max(0, layout.getLineTop(line));
            int bottom = Math.min(bitmap.getHeight(), layout.getLineBottom(line));
            for (int x = bitmap.getWidth() - 1; x >= 0; x--) {
                for (int y = top; y < bottom; y++) {
                    if (Color.red(bitmap.getPixel(x, y)) < 128) {
                        return x;
                    }
                }
            }
            throw new AssertionError("line has no visible ink");
        } finally {
            bitmap.recycle();
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
