package com.kotobalock;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;

/** Small hand-drawn charts for the Progress tab, coloured from the quiz theme. */
public final class Charts {
    private Charts() {}

    /** Knowledge levels, in display order. Colours are fixed traffic-light meanings + grey for new. */
    public static final String[] LEVELS = {"Struggling", "Learning", "Known", "New"};

    public static int levelColor(QuizTheme th, int level) {
        switch (level) {
            case 0: return th.bad;
            case 1: return 0xFFE9A23B; // amber: between struggling and known
            case 2: return th.good;
            default: return (th.sub & 0x00FFFFFF) | 0x59000000;
        }
    }

    /** 0 struggling, 1 learning, 2 known (for a word you have answered at least once). */
    public static int level(Stats.Word w) {
        if (w.weakness >= 0.5f) return 0;
        if (w.weakness >= 0.2f) return 1;
        return 2;
    }

    static float dp(Context c, float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics());
    }

    static float sp(Context c, float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, c.getResources().getDisplayMetrics());
    }

    static int alpha(int color, int a) { return (color & 0x00FFFFFF) | (a << 24); }

    /** Path for a rect with only the top corners rounded (bars grow up from a flat baseline). */
    static Path topRounded(float l, float t, float r, float b, float rad) {
        rad = Math.min(rad, Math.min((r - l) / 2f, b - t));
        Path p = new Path();
        p.addRoundRect(new RectF(l, t, r, b), new float[]{rad, rad, rad, rad, 0, 0, 0, 0}, Path.Direction.CW);
        return p;
    }

    /** Draws a rounded tooltip box with the text, centred on x and kept inside the view. */
    static void tooltip(Canvas c, View v, QuizTheme th, Paint text, String s, float x, float top) {
        float pad = dp(v.getContext(), 8);
        float w = text.measureText(s) + pad * 2;
        float h = text.getTextSize() + pad * 1.6f;
        float l = Math.max(0, Math.min(v.getWidth() - w, x - w / 2));
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(th.text);
        c.drawRoundRect(new RectF(l, top, l + w, top + h), dp(v.getContext(), 8), dp(v.getContext(), 8), bg);
        Paint tp = new Paint(text);
        tp.setColor(th.card);
        tp.setTextAlign(Paint.Align.LEFT);
        c.drawText(s, l + pad, top + h / 2f - (tp.ascent() + tp.descent()) / 2f, tp);
    }

    // ================================================================ daily answers (stacked bars)

    /** Answers per day: right at the bottom, wrong stacked on top. Tap or drag to read a day. */
    public static final class DailyBars extends View {
        private final QuizTheme th;
        private final int[] right, wrong;
        private final String[] labels;
        private int sel = -1;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), grid = new Paint(Paint.ANTI_ALIAS_FLAG),
                axis = new Paint(Paint.ANTI_ALIAS_FLAG), tip = new Paint(Paint.ANTI_ALIAS_FLAG);

        public DailyBars(Context c, QuizTheme th, int[] right, int[] wrong, String[] labels) {
            super(c);
            this.th = th; this.right = right; this.wrong = wrong; this.labels = labels;
            grid.setColor(alpha(th.text, 0x16));
            grid.setStrokeWidth(Math.max(1, dp(c, 1)));
            axis.setColor(th.sub);
            axis.setTextSize(sp(c, 10.5f));
            tip.setTextSize(sp(c, 12.5f));
            tip.setTypeface(Typeface.DEFAULT_BOLD);
            setMinimumHeight((int) dp(c, 190));
        }

        @Override protected void onMeasure(int w, int h) {
            super.onMeasure(w, MeasureSpec.makeMeasureSpec((int) dp(getContext(), 190), MeasureSpec.EXACTLY));
        }

        @Override protected void onDraw(Canvas c) {
            Context x = getContext();
            int n = right.length;
            float left = dp(x, 30), top = dp(x, 36), bottom = getHeight() - dp(x, 22), w = getWidth() - left;
            int max = 4;
            for (int i = 0; i < n; i++) max = Math.max(max, right[i] + wrong[i]);
            int step = niceStep(max);
            max = ((max + step - 1) / step) * step;
            // Recessive grid + y labels
            axis.setTextAlign(Paint.Align.RIGHT);
            for (int g = 0; g <= max; g += step) {
                float y = bottom - (bottom - top) * g / max;
                c.drawLine(left, y, getWidth(), y, grid);
                c.drawText(String.valueOf(g), left - dp(x, 6), y + axis.getTextSize() / 3f, axis);
            }
            float slot = w / n, bw = Math.max(dp(x, 3), slot * 0.62f), gap = dp(x, 2), rad = dp(x, 4);
            for (int i = 0; i < n; i++) {
                float cx = left + slot * i + slot / 2f, l = cx - bw / 2f, r = cx + bw / 2f;
                int a = sel < 0 || sel == i ? 0xFF : 0x66;
                float hr = (bottom - top) * right[i] / max, hw = (bottom - top) * wrong[i] / max;
                if (right[i] > 0) {
                    fill.setColor(alpha(th.good, a));
                    if (wrong[i] > 0) c.drawRect(l, bottom - hr, r, bottom, fill);
                    else c.drawPath(topRounded(l, bottom - hr, r, bottom, rad), fill);
                }
                if (wrong[i] > 0) {
                    float b = bottom - hr - (right[i] > 0 ? gap : 0);
                    fill.setColor(alpha(th.bad, a));
                    c.drawPath(topRounded(l, b - hw, r, b, rad), fill);
                }
            }
            // x labels: first, middle, last
            axis.setTextAlign(Paint.Align.CENTER);
            for (int i : new int[]{0, n / 2, n - 1}) {
                String s = i == n - 1 ? "Today" : labels[i];
                float half = axis.measureText(s) / 2f;
                float cx = Math.max(left + half, Math.min(getWidth() - half, left + slot * i + slot / 2f));
                c.drawText(s, cx, getHeight() - dp(x, 6), axis);
            }
            if (sel >= 0) {
                int t = right[sel] + wrong[sel];
                String s = labels[sel] + "  ·  " + t + " answered" + (t > 0 ? "  ·  " + Math.round(100f * right[sel] / t) + "% right" : "");
                tooltip(c, this, th, tip, s, left + slot * sel + slot / 2f, dp(x, 2));
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override public boolean onTouchEvent(MotionEvent e) {
            float left = dp(getContext(), 30);
            int i = (int) ((e.getX() - left) / ((getWidth() - left) / right.length));
            i = Math.max(0, Math.min(right.length - 1, i));
            if (e.getAction() == MotionEvent.ACTION_DOWN || e.getAction() == MotionEvent.ACTION_MOVE) {
                getParent().requestDisallowInterceptTouchEvent(true);
                if (i != sel) { sel = i; invalidate(); }
            } else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) {
                getParent().requestDisallowInterceptTouchEvent(false);
            }
            return true;
        }
    }

    static int niceStep(int max) {
        int[] steps = {1, 2, 5, 10, 20, 25, 50, 100, 200, 250, 500, 1000, 2000, 5000};
        for (int s : steps) if (max / s <= 4) return s;
        return 10000;
    }

    // ================================================================ cumulative words seen (line)

    /** Running total of distinct words you've answered. Tap or drag to read a day. */
    public static final class CumulativeLine extends View {
        private final QuizTheme th;
        private final int[] values;
        private final String[] labels;
        private int sel = -1;
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), area = new Paint(Paint.ANTI_ALIAS_FLAG),
                grid = new Paint(Paint.ANTI_ALIAS_FLAG), axis = new Paint(Paint.ANTI_ALIAS_FLAG),
                tip = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int color;

        public CumulativeLine(Context c, QuizTheme th, int[] values, String[] labels) {
            super(c);
            this.th = th; this.values = values; this.labels = labels;
            color = th.check;
            line.setColor(color);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(dp(c, 2));
            line.setStrokeJoin(Paint.Join.ROUND);
            line.setStrokeCap(Paint.Cap.ROUND);
            area.setColor(alpha(color, 0x2A));
            grid.setColor(alpha(th.text, 0x16));
            grid.setStrokeWidth(Math.max(1, dp(c, 1)));
            axis.setColor(th.sub);
            axis.setTextSize(sp(c, 10.5f));
            tip.setTextSize(sp(c, 12.5f));
            tip.setTypeface(Typeface.DEFAULT_BOLD);
        }

        @Override protected void onMeasure(int w, int h) {
            super.onMeasure(w, MeasureSpec.makeMeasureSpec((int) dp(getContext(), 170), MeasureSpec.EXACTLY));
        }

        private float px(int i, float left) { return left + (getWidth() - left - dp(getContext(), 8)) * i / Math.max(1f, values.length - 1); }

        @Override protected void onDraw(Canvas c) {
            Context x = getContext();
            int n = values.length;
            float left = dp(x, 34), top = dp(x, 36), bottom = getHeight() - dp(x, 22);
            int max = 4;
            for (int v : values) max = Math.max(max, v);
            int step = niceStep(max);
            max = ((max + step - 1) / step) * step;
            axis.setTextAlign(Paint.Align.RIGHT);
            for (int g = 0; g <= max; g += step) {
                float y = bottom - (bottom - top) * g / max;
                c.drawLine(left, y, getWidth(), y, grid);
                c.drawText(String.valueOf(g), left - dp(x, 6), y + axis.getTextSize() / 3f, axis);
            }
            Path p = new Path(), a = new Path();
            for (int i = 0; i < n; i++) {
                float px = px(i, left), py = bottom - (bottom - top) * values[i] / max;
                if (i == 0) { p.moveTo(px, py); a.moveTo(px, bottom); a.lineTo(px, py); }
                else { p.lineTo(px, py); a.lineTo(px, py); }
            }
            a.lineTo(px(n - 1, left), bottom);
            a.close();
            c.drawPath(a, area);
            c.drawPath(p, line);
            axis.setTextAlign(Paint.Align.CENTER);
            for (int i : new int[]{0, n / 2, n - 1})
                c.drawText(i == n - 1 ? "Today" : labels[i], Math.max(left + dp(x, 14), Math.min(getWidth() - dp(x, 16), px(i, left))), getHeight() - dp(x, 6), axis);
            int i = sel >= 0 ? sel : n - 1;
            float cx = px(i, left), cy = bottom - (bottom - top) * values[i] / max;
            if (sel >= 0) c.drawLine(cx, top, cx, bottom, grid);
            dot.setColor(th.card);
            c.drawCircle(cx, cy, dp(x, 6), dot);
            dot.setColor(color);
            c.drawCircle(cx, cy, dp(x, 4), dot);
            String s = sel >= 0 ? labels[i] + "  ·  " + values[i] + " words seen" : values[i] + " words seen";
            tooltip(c, this, th, tip, s, cx, dp(x, 2));
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override public boolean onTouchEvent(MotionEvent e) {
            float left = dp(getContext(), 34);
            float w = getWidth() - left - dp(getContext(), 8);
            int i = Math.round((e.getX() - left) / w * (values.length - 1));
            i = Math.max(0, Math.min(values.length - 1, i));
            if (e.getAction() == MotionEvent.ACTION_DOWN || e.getAction() == MotionEvent.ACTION_MOVE) {
                getParent().requestDisallowInterceptTouchEvent(true);
                if (i != sel) { sel = i; invalidate(); }
            } else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) {
                getParent().requestDisallowInterceptTouchEvent(false);
            }
            return true;
        }
    }

    // ================================================================ segmented bar (knowledge levels)

    /** One horizontal bar split into the LEVELS (struggling / learning / known / new), 2dp gaps between. */
    public static final class LevelBar extends View {
        private final QuizTheme th;
        private final int[] counts;
        private final float heightDp;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);

        public LevelBar(Context c, QuizTheme th, int[] counts, float heightDp) {
            super(c);
            this.th = th; this.counts = counts; this.heightDp = heightDp;
        }

        @Override protected void onMeasure(int w, int h) {
            super.onMeasure(w, MeasureSpec.makeMeasureSpec((int) dp(getContext(), heightDp), MeasureSpec.EXACTLY));
        }

        @Override protected void onDraw(Canvas c) {
            int total = 0;
            for (int k : counts) total += k;
            float h = getHeight(), w = getWidth(), rad = Math.min(dp(getContext(), 4), h / 2f), gap = dp(getContext(), 2);
            if (total == 0) {
                fill.setColor(levelColor(th, 3));
                c.drawRoundRect(new RectF(0, 0, w, h), rad, rad, fill);
                return;
            }
            int segs = 0;
            for (int k : counts) if (k > 0) segs++;
            float usable = w - gap * (segs - 1), x = 0;
            int drawn = 0;
            for (int i = 0; i < counts.length; i++) {
                if (counts[i] == 0) continue;
                float sw = Math.max(dp(getContext(), 2), usable * counts[i] / total);
                boolean first = drawn == 0, last = drawn == segs - 1;
                float rl = first ? rad : 0, rr = last ? rad : 0;
                Path p = new Path();
                p.addRoundRect(new RectF(x, 0, Math.min(w, x + sw), h), new float[]{rl, rl, rr, rr, rr, rr, rl, rl}, Path.Direction.CW);
                fill.setColor(levelColor(th, i));
                c.drawPath(p, fill);
                x += sw + gap;
                drawn++;
            }
        }
    }
}
