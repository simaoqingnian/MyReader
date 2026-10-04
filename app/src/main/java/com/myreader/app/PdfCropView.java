package com.myreader.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

final class PdfCropView extends View {
    private final Bitmap bitmap;
    private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint shadePaint = new Paint();
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF imageBounds = new RectF();
    private final RectF selectedBounds = new RectF();
    private RectF selection;
    private float downX;
    private float downY;
    private RectF original;
    private boolean moving;
    private boolean resizeLeft;
    private boolean resizeTop;
    private boolean resizeRight;
    private boolean resizeBottom;

    PdfCropView(Context context, Bitmap bitmap, RectF initial) {
        super(context);
        this.bitmap = bitmap;
        this.selection = PdfReaderView.sanitize(initial);
        setBackgroundColor(Color.parseColor("#343936"));
        shadePaint.setColor(0x99000000);
        borderPaint.setColor(Color.parseColor("#82E0B2"));
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(getResources().getDisplayMetrics().density * 2f);
    }

    RectF getSelection() {
        return PdfReaderView.sanitize(selection);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float scale = Math.min(getWidth() / (float) bitmap.getWidth(), getHeight() / (float) bitmap.getHeight());
        float drawnWidth = bitmap.getWidth() * scale;
        float drawnHeight = bitmap.getHeight() * scale;
        float left = (getWidth() - drawnWidth) / 2f;
        float top = (getHeight() - drawnHeight) / 2f;
        imageBounds.set(left, top, left + drawnWidth, top + drawnHeight);
        canvas.drawBitmap(bitmap, null, imageBounds, imagePaint);

        selectedBounds.set(
                left + selection.left * drawnWidth,
                top + selection.top * drawnHeight,
                left + selection.right * drawnWidth,
                top + selection.bottom * drawnHeight);
        canvas.drawRect(left, top, imageBounds.right, selectedBounds.top, shadePaint);
        canvas.drawRect(left, selectedBounds.bottom, imageBounds.right, imageBounds.bottom, shadePaint);
        canvas.drawRect(left, selectedBounds.top, selectedBounds.left, selectedBounds.bottom, shadePaint);
        canvas.drawRect(selectedBounds.right, selectedBounds.top, imageBounds.right, selectedBounds.bottom, shadePaint);
        canvas.drawRect(selectedBounds, borderPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (imageBounds.isEmpty()) {
            return true;
        }
        float x = clamp((event.getX() - imageBounds.left) / imageBounds.width());
        float y = clamp((event.getY() - imageBounds.top) / imageBounds.height());
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                downX = x;
                downY = y;
                original = new RectF(selection);
                resizeLeft = Math.abs(x - selection.left) < 0.04f && y >= selection.top - 0.04f && y <= selection.bottom + 0.04f;
                resizeRight = Math.abs(x - selection.right) < 0.04f && y >= selection.top - 0.04f && y <= selection.bottom + 0.04f;
                resizeTop = Math.abs(y - selection.top) < 0.04f && x >= selection.left - 0.04f && x <= selection.right + 0.04f;
                resizeBottom = Math.abs(y - selection.bottom) < 0.04f && x >= selection.left - 0.04f && x <= selection.right + 0.04f;
                boolean resizing = resizeLeft || resizeRight || resizeTop || resizeBottom;
                moving = !resizing && selection.contains(x, y)
                        && (selection.width() < 0.99f || selection.height() < 0.99f);
                if (!moving && !resizing) {
                    selection = new RectF(x, y, x, y);
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_UP:
                if (resizeLeft || resizeRight || resizeTop || resizeBottom) {
                    selection.set(
                            resizeLeft ? Math.max(0f, Math.min(x, original.right - 0.05f)) : original.left,
                            resizeTop ? Math.max(0f, Math.min(y, original.bottom - 0.05f)) : original.top,
                            resizeRight ? Math.min(1f, Math.max(x, original.left + 0.05f)) : original.right,
                            resizeBottom ? Math.min(1f, Math.max(y, original.top + 0.05f)) : original.bottom);
                } else if (moving) {
                    float dx = Math.max(-original.left, Math.min(1f - original.right, x - downX));
                    float dy = Math.max(-original.top, Math.min(1f - original.bottom, y - downY));
                    selection.set(original.left + dx, original.top + dy,
                            original.right + dx, original.bottom + dy);
                } else {
                    selection.set(Math.min(downX, x), Math.min(downY, y),
                            Math.max(downX, x), Math.max(downY, y));
                }
                if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    if (selection.width() < 0.05f || selection.height() < 0.05f) {
                        selection = original;
                    }
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
                invalidate();
                return true;
            default:
                return true;
        }
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
