package com.myreader.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.Layout;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.ForegroundColorSpan;
import android.widget.TextView;

import java.text.BreakIterator;
import java.util.Locale;

final class TxtJustifiedTextView extends TextView {
    private final TextPaint glyphPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final BreakIterator characters = BreakIterator.getCharacterInstance(Locale.CHINA);
    private boolean justify;
    private boolean lastLineContinues;

    TxtJustifiedTextView(Context context) {
        super(context);
    }

    void setJustification(boolean enabled) {
        justify = enabled;
        invalidate();
    }

    void setLastLineContinues(boolean continues) {
        lastLineContinues = continues;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Layout layout = getLayout();
        CharSequence text = getText();
        if (!justify || layout == null || text == null || text.length() == 0 || containsRtl(text)) {
            super.onDraw(canvas);
            return;
        }

        int save = canvas.save();
        canvas.translate(getTotalPaddingLeft(), getTotalPaddingTop());
        glyphPaint.set(getPaint());
        for (int line = 0; line < layout.getLineCount(); line++) {
            drawLine(canvas, layout, text, line);
        }
        canvas.restoreToCount(save);
    }

    private void drawLine(Canvas canvas, Layout layout, CharSequence text, int line) {
        int lineStart = layout.getLineStart(line);
        int visibleEnd = layout.getLineVisibleEnd(line);
        int lineEnd = layout.getLineEnd(line);
        if (visibleEnd <= lineStart) {
            return;
        }

        int visibleStart = lineStart;
        while (visibleStart < visibleEnd && Character.isWhitespace(text.charAt(visibleStart))) {
            visibleStart++;
        }
        if (visibleStart >= visibleEnd) {
            return;
        }

        characters.setText(text.subSequence(visibleStart, visibleEnd).toString());
        int clusterCount = 0;
        for (int boundary = characters.first(); boundary != BreakIterator.DONE; boundary = characters.next()) {
            clusterCount++;
        }
        int gaps = clusterCount - 2;
        float perGap = 0f;
        if (gaps > 0 && lineEnd > lineStart && text.charAt(lineEnd - 1) != '\n'
                && (line != layout.getLineCount() - 1 || lastLineContinues)
                && containsHan(text, visibleStart, visibleEnd)) {
            float extra = layout.getWidth() - layout.getLineMax(line);
            float candidate = extra / gaps;
            if (extra > 0.5f && candidate <= getPaint().getTextSize() * 0.65f) {
                perGap = candidate;
            }
        }

        int baseline = layout.getLineBaseline(line);
        int cluster = 0;
        int from = characters.first();
        for (int to = characters.next(); to != BreakIterator.DONE; from = to, to = characters.next()) {
            int start = visibleStart + from;
            int end = visibleStart + to;
            glyphPaint.setColor(getCurrentTextColor());
            if (text instanceof Spanned) {
                Spanned spanned = (Spanned) text;
                ForegroundColorSpan[] colors = spanned.getSpans(start, end, ForegroundColorSpan.class);
                if (colors.length > 0) {
                    glyphPaint.setColor(colors[colors.length - 1].getForegroundColor());
                }
            }
            float x = layout.getPrimaryHorizontal(start) + cluster * perGap;
            canvas.drawTextRun(text, start, end, lineStart, visibleEnd, x, baseline, false, glyphPaint);
            cluster++;
        }
    }

    private static boolean containsHan(CharSequence text, int start, int end) {
        for (int index = start; index < end; ) {
            int codePoint = Character.codePointAt(text, index);
            if ((codePoint >= 0x3400 && codePoint <= 0x9fff)
                    || (codePoint >= 0xf900 && codePoint <= 0xfaff)
                    || (codePoint >= 0x20000 && codePoint <= 0x2fa1f)) {
                return true;
            }
            index += Character.charCount(codePoint);
        }
        return false;
    }

    private static boolean containsRtl(CharSequence text) {
        for (int index = 0; index < text.length(); ) {
            int codePoint = Character.codePointAt(text, index);
            byte direction = Character.getDirectionality(codePoint);
            if (direction == Character.DIRECTIONALITY_RIGHT_TO_LEFT
                    || direction == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) {
                return true;
            }
            index += Character.charCount(codePoint);
        }
        return false;
    }
}
