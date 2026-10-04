package com.myreader.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class PdfReaderView extends ListView {
    static final int CROP_FULL = 0;
    static final int CROP_AUTO = 1;
    static final int CROP_MANUAL = 2;

    interface Listener {
        void onReady(int pageCount);
        void onProgress(int page, float fraction, int pageCount);
        void onTap();
        void onInteraction();
        void onError(String message);
    }

    interface PreviewCallback {
        void onPreview(Bitmap bitmap);
        void onError(String message);
    }

    private static final RectF FULL_PAGE = new RectF(0f, 0f, 1f, 1f);
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Set<Integer> pendingPages = new HashSet<>();
    private final LruCache<Integer, Bitmap> bitmaps = new LruCache<Integer, Bitmap>(24 * 1024 * 1024) {
        @Override
        protected int sizeOf(Integer key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private final BaseAdapter adapter = new BaseAdapter() {
        @Override
        public int getCount() {
            return pageCount;
        }

        @Override
        public Object getItem(int position) {
            return position;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            PageView pageView = convertView instanceof PageView ? (PageView) convertView : new PageView(getContext());
            pageView.setDisplayColors(displayBackground, displayFilter);
            pageView.bind(position, pageAspect(position), bitmaps.get(position));
            requestPage(position);
            return pageView;
        }
    };

    private PdfRenderer renderer;
    private Listener listener;
    private RectF[] autoCrops;
    private float[] pageAspects;
    private int pageCount;
    private int cropMode = CROP_AUTO;
    private RectF manualCrop = new RectF(FULL_PAGE);
    private int displayBackground = Color.WHITE;
    private ColorFilter displayFilter;
    private volatile int generation;
    private volatile boolean closed;
    private int restorePage = -1;
    private float restoreFraction;
    private int restoreAttempts;

    PdfReaderView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        setDivider(null);
        setDividerHeight(0);
        setCacheColorHint(Color.WHITE);
        setSelector(android.R.color.transparent);
        setVerticalFadingEdgeEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
        setOnItemClickListener((parent, view, position, id) -> {
            if (this.listener != null) {
                this.listener.onTap();
            }
        });
        setOnScrollListener(new OnScrollListener() {
            @Override
            public void onScrollStateChanged(android.widget.AbsListView view, int state) {
                if (state == SCROLL_STATE_TOUCH_SCROLL) {
                    restorePage = -1;
                }
                if (state != SCROLL_STATE_IDLE && PdfReaderView.this.listener != null) {
                    PdfReaderView.this.listener.onInteraction();
                }
            }

            @Override
            public void onScroll(android.widget.AbsListView view, int first, int visible, int total) {
                reportProgress();
            }
        });
    }

    void open(File file, int savedPage, float savedFraction) {
        restorePage = Math.max(0, savedPage);
        restoreFraction = clamp(savedFraction, 0f, 0.99f);
        restoreAttempts = 0;
        worker.execute(() -> {
            try {
                PdfRenderer opened = new PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY));
                int count = opened.getPageCount();
                if (count <= 0) {
                    opened.close();
                    throw new IllegalArgumentException("文档没有页面");
                }
                if (closed) {
                    opened.close();
                    return;
                }
                ui.post(() -> {
                    if (closed) {
                        opened.close();
                        return;
                    }
                    renderer = opened;
                    pageCount = count;
                    autoCrops = new RectF[count];
                    pageAspects = new float[count];
                    Arrays.fill(pageAspects, 1.414f);
                    setAdapter(adapter);
                    if (count > 0) {
                        setSelectionFromTop(Math.min(restorePage, count - 1), 0);
                    }
                    if (listener != null) {
                        listener.onReady(count);
                    }
                });
            } catch (Exception e) {
                ui.post(() -> {
                    if (!closed && listener != null) {
                        listener.onError("PDF 打开失败：" + e.getMessage());
                    }
                });
            }
        });
    }

    int getPageCount() {
        return pageCount;
    }

    boolean isRestoring() {
        return restorePage >= 0;
    }

    int getCurrentPage() {
        return Math.max(0, Math.min(Math.max(0, pageCount - 1), getFirstVisiblePosition()));
    }

    float getCurrentFraction() {
        View first = getChildAt(0);
        return first == null || first.getHeight() <= 0 ? 0f : clamp(-first.getTop() / (float) first.getHeight(), 0f, 0.99f);
    }

    float getProgress() {
        return pageCount <= 0 ? 0f : (getCurrentPage() + getCurrentFraction()) / pageCount;
    }

    void jumpToProgress(float progress) {
        if (pageCount <= 0) {
            return;
        }
        float exactPage = clamp(progress, 0f, 1f) * pageCount;
        restorePage = Math.min(pageCount - 1, (int) exactPage);
        restoreFraction = exactPage >= pageCount ? 0.99f : exactPage - restorePage;
        restoreAttempts = 0;
        setSelectionFromTop(restorePage, 0);
        post(this::applyRestore);
    }

    void setCrop(int mode, RectF manual) {
        int page = getCurrentPage();
        float fraction = getCurrentFraction();
        cropMode = mode;
        if (manual != null) {
            manualCrop = sanitize(manual);
        }
        generation++;
        pendingPages.clear();
        bitmaps.evictAll();
        if (autoCrops != null && mode == CROP_AUTO) {
            Arrays.fill(autoCrops, null);
        }
        restorePage = page;
        restoreFraction = fraction;
        restoreAttempts = 0;
        adapter.notifyDataSetChanged();
        setSelectionFromTop(page, 0);
        post(this::applyRestore);
    }

    void setInitialCrop(int mode, RectF manual) {
        cropMode = mode;
        if (manual != null) {
            manualCrop = sanitize(manual);
        }
    }

    void setNightPalette(boolean enabled, int background, int text) {
        displayBackground = enabled ? background : Color.WHITE;
        displayFilter = enabled ? nightColorFilter(background, text) : null;
        setBackgroundColor(displayBackground);
        setCacheColorHint(displayBackground);
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child instanceof PageView) {
                ((PageView) child).setDisplayColors(displayBackground, displayFilter);
            }
        }
    }

    private static ColorFilter nightColorFilter(int background, int text) {
        float red = (Color.red(background) - Color.red(text)) / 255f;
        float green = (Color.green(background) - Color.green(text)) / 255f;
        float blue = (Color.blue(background) - Color.blue(text)) / 255f;
        return new ColorMatrixColorFilter(new float[]{
                0.2126f * red, 0.7152f * red, 0.0722f * red, 0, Color.red(text),
                0.2126f * green, 0.7152f * green, 0.0722f * green, 0, Color.green(text),
                0.2126f * blue, 0.7152f * blue, 0.0722f * blue, 0, Color.blue(text),
                0, 0, 0, 1, 0
        });
    }

    RectF getCurrentCrop() {
        int page = getCurrentPage();
        if (cropMode == CROP_MANUAL) {
            return new RectF(manualCrop);
        }
        if (cropMode == CROP_AUTO && autoCrops != null && page < autoCrops.length && autoCrops[page] != null) {
            return new RectF(autoCrops[page]);
        }
        return new RectF(FULL_PAGE);
    }

    void loadPreview(int pageIndex, PreviewCallback callback) {
        if (renderer == null || pageIndex < 0 || pageIndex >= pageCount || closed) {
            callback.onError("PDF 页面尚未准备好");
            return;
        }
        worker.execute(() -> {
            Bitmap bitmap = null;
            try (PdfRenderer.Page page = renderer.openPage(pageIndex)) {
                float scale = Math.min(720f / page.getWidth(), 1100f / page.getHeight());
                int width = Math.max(1, Math.round(page.getWidth() * scale));
                int height = Math.max(1, Math.round(page.getHeight() * scale));
                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                bitmap.eraseColor(Color.WHITE);
                Matrix matrix = new Matrix();
                matrix.setScale(width / (float) page.getWidth(), height / (float) page.getHeight());
                page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                Bitmap result = bitmap;
                ui.post(() -> {
                    if (closed) {
                        result.recycle();
                    } else {
                        callback.onPreview(result);
                    }
                });
            } catch (Exception e) {
                if (bitmap != null) {
                    bitmap.recycle();
                }
                ui.post(() -> {
                    if (!closed) {
                        callback.onError("预览失败：" + e.getMessage());
                    }
                });
            }
        });
    }

    void close() {
        closed = true;
        listener = null;
        generation++;
        pendingPages.clear();
        bitmaps.evictAll();
        worker.execute(() -> {
            if (renderer != null) {
                renderer.close();
                renderer = null;
            }
        });
        worker.shutdown();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (width > 0 && oldWidth > 0 && width != oldWidth && pageCount > 0) {
            int page = getCurrentPage();
            float fraction = getCurrentFraction();
            post(() -> {
                if (!closed) {
                    generation++;
                    pendingPages.clear();
                    bitmaps.evictAll();
                    restorePage = page;
                    restoreFraction = fraction;
                    restoreAttempts = 0;
                    adapter.notifyDataSetChanged();
                    setSelectionFromTop(page, 0);
                    post(this::applyRestore);
                }
            });
        }
    }

    private void requestPage(int index) {
        if (closed || renderer == null || bitmaps.get(index) != null || pendingPages.contains(index)) {
            return;
        }
        pendingPages.add(index);
        final int requestGeneration = generation;
        final int mode = cropMode;
        final RectF selected = new RectF(manualCrop);
        final int width = Math.max(1, getWidth() > 0 ? getWidth() : getResources().getDisplayMetrics().widthPixels);
        final RectF knownCrop = autoCrops == null ? null : autoCrops[index];
        worker.execute(() -> {
            Bitmap bitmap = null;
            try {
                if (closed || generation != requestGeneration) {
                    return;
                }
                try (PdfRenderer.Page page = renderer.openPage(index)) {
                    RectF crop = mode == CROP_MANUAL ? selected : mode == CROP_FULL ? new RectF(FULL_PAGE)
                            : knownCrop == null ? detectCrop(page) : new RectF(knownCrop);
                    float aspect = page.getHeight() * crop.height() / (page.getWidth() * crop.width());
                    float scale = Math.min(1f, (float) Math.sqrt(4_000_000f / Math.max(1f, width * width * aspect)));
                    int bitmapWidth = Math.max(1, Math.min(2048, Math.round(width * scale)));
                    int bitmapHeight = Math.max(1, Math.round(bitmapWidth * aspect));
                    bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888);
                    bitmap.eraseColor(Color.WHITE);
                    Matrix matrix = new Matrix();
                    float pageScale = bitmapWidth / (page.getWidth() * crop.width());
                    matrix.setScale(pageScale, pageScale);
                    matrix.postTranslate(-page.getWidth() * crop.left * pageScale,
                            -page.getHeight() * crop.top * pageScale);
                    page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    Bitmap result = bitmap;
                    ui.post(() -> {
                        if (closed || generation != requestGeneration) {
                            result.recycle();
                            return;
                        }
                        pendingPages.remove(index);
                        if (mode == CROP_AUTO && autoCrops != null) {
                            autoCrops[index] = crop;
                        }
                        pageAspects[index] = aspect;
                        bitmaps.put(index, result);
                        updateVisiblePage(index);
                        if (restorePage == index) {
                            post(this::applyRestore);
                        }
                    });
                }
            } catch (Exception e) {
                if (bitmap != null) {
                    bitmap.recycle();
                }
                ui.post(() -> {
                    if (!closed && generation == requestGeneration) {
                        pendingPages.remove(index);
                        if (restorePage == index) {
                            restorePage = -1;
                            reportProgress();
                        }
                        if (listener != null) {
                            listener.onError("第 " + (index + 1) + " 页加载失败：" + e.getMessage());
                        }
                    }
                });
            }
        });
    }

    private void updateVisiblePage(int index) {
        int childIndex = index - getFirstVisiblePosition();
        View child = getChildAt(childIndex);
        if (child instanceof PageView) {
            ((PageView) child).bind(index, pageAspect(index), bitmaps.get(index));
        }
        reportProgress();
    }

    private void applyRestore() {
        if (closed || restorePage < 0 || pageCount == 0) {
            return;
        }
        int childIndex = restorePage - getFirstVisiblePosition();
        if (childIndex < 0 || childIndex >= getChildCount()) {
            if (restoreAttempts++ < 2) {
                setSelectionFromTop(restorePage, 0);
                post(this::applyRestore);
            } else {
                restorePage = -1;
                reportProgress();
            }
            return;
        }
        restoreAttempts = 0;
        View child = getChildAt(childIndex);
        if (child == null || child.getHeight() <= 0) {
            return;
        }
        setSelectionFromTop(restorePage, -Math.round(restoreFraction * child.getHeight()));
        if (bitmaps.get(restorePage) != null) {
            restorePage = -1;
            post(this::reportProgress);
        }
    }

    private void reportProgress() {
        if (listener != null && pageCount > 0 && restorePage < 0) {
            listener.onProgress(getCurrentPage(), getCurrentFraction(), pageCount);
        }
    }

    private float pageAspect(int index) {
        return pageAspects == null || index >= pageAspects.length ? 1.414f : pageAspects[index];
    }

    private static RectF detectCrop(PdfRenderer.Page page) {
        int width = 256;
        int height = Math.max(1, Math.round(width * page.getHeight() / (float) page.getWidth()));
        if (height > 512) {
            width = Math.max(1, Math.round(width * 512f / height));
            height = 512;
        }
        Bitmap preview = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        try {
            preview.eraseColor(Color.WHITE);
            Matrix matrix = new Matrix();
            matrix.setScale(width / (float) page.getWidth(), height / (float) page.getHeight());
            page.render(preview, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            int[] pixels = new int[width * height];
            preview.getPixels(pixels, 0, width, 0, 0, width, height);
            int left = width;
            int top = height;
            int right = -1;
            int bottom = -1;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int color = pixels[y * width + x];
                    int darkest = Math.min(Color.red(color), Math.min(Color.green(color), Color.blue(color)));
                    if (darkest < 237) {
                        left = Math.min(left, x);
                        top = Math.min(top, y);
                        right = Math.max(right, x);
                        bottom = Math.max(bottom, y);
                    }
                }
            }
            if (right < left || bottom < top) {
                return new RectF(FULL_PAGE);
            }
            int safetyX = Math.max(2, Math.round(width * 0.012f));
            int safetyY = Math.max(2, Math.round(height * 0.012f));
            return sanitize(new RectF(
                    Math.max(0, left - safetyX) / (float) width,
                    Math.max(0, top - safetyY) / (float) height,
                    Math.min(width, right + safetyX + 1) / (float) width,
                    Math.min(height, bottom + safetyY + 1) / (float) height));
        } finally {
            preview.recycle();
        }
    }

    static RectF sanitize(RectF rect) {
        if (rect == null || Float.isNaN(rect.left) || Float.isNaN(rect.top)
                || Float.isNaN(rect.right) || Float.isNaN(rect.bottom)
                || Float.isInfinite(rect.left) || Float.isInfinite(rect.top)
                || Float.isInfinite(rect.right) || Float.isInfinite(rect.bottom)) {
            return new RectF(FULL_PAGE);
        }
        float left = clamp(rect.left, 0f, 0.95f);
        float top = clamp(rect.top, 0f, 0.95f);
        float right = clamp(rect.right, left + 0.05f, 1f);
        float bottom = clamp(rect.bottom, top + 0.05f, 1f);
        return new RectF(left, top, right, bottom);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class PageView extends View {
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private final RectF destination = new RectF();
        private Bitmap bitmap;
        private float aspect = 1.414f;

        PageView(Context context) {
            super(context);
            setBackgroundColor(Color.WHITE);
        }

        void setDisplayColors(int background, ColorFilter filter) {
            setBackgroundColor(background);
            paint.setColorFilter(filter);
            invalidate();
        }

        void bind(int index, float newAspect, Bitmap newBitmap) {
            boolean sizeChanged = Math.abs(aspect - newAspect) > 0.001f;
            aspect = newAspect;
            bitmap = newBitmap;
            if (sizeChanged) {
                requestLayout();
            }
            invalidate();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            setMeasuredDimension(width, Math.max(1, Math.round(width * aspect)));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (bitmap != null && !bitmap.isRecycled()) {
                destination.set(0, 0, getWidth(), getHeight());
                canvas.drawBitmap(bitmap, null, destination, paint);
            }
        }
    }
}
