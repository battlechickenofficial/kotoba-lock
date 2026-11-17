package com.kotobalock;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;

import java.util.Random;

/**
 * Calm animated backdrop for the quiz: a soft vertical gradient, three blurred colour glows
 * drifting slowly, and a handful of faint words from your deck floating upwards. ~30 fps, and it only
 * animates while the window is visible.
 */
public class AmbientBackground extends View {
    private static final String KANA = "あいうえおかきくけこさしすせそたちつてとなにぬねのはひふへほまみむめもやゆよらりるれろわをん";
    private final java.util.List<String> words = new java.util.ArrayList<>();
    private static final int GLYPHS = 12;

    private final QuizTheme th;
    private final Paint base = new Paint();
    private final Paint blob = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random rng = new Random();
    private final long start = SystemClock.uptimeMillis();

    private final int[] blobColors;
    private final float[][] blobPath = { // speedX, speedY, phase, radius (fraction of width)
            {0.031f, 0.023f, 0.0f, 0.75f},
            {0.019f, 0.029f, 2.1f, 0.65f},
            {0.025f, 0.017f, 4.2f, 0.85f},
    };
    private final RadialGradient[] shaders = new RadialGradient[3];

    private final String[] gText = new String[GLYPHS];
    private final float[] gX = new float[GLYPHS], gY = new float[GLYPHS], gSize = new float[GLYPHS],
            gSpeed = new float[GLYPHS], gSway = new float[GLYPHS], gAlpha = new float[GLYPHS];
    private boolean seeded, running;
    private long lastFrame;

    public AmbientBackground(Context c, QuizTheme theme, java.util.List<Deck.Card> cards) {
        super(c);
        th = theme;
        for (Deck.Card card : cards) if (card.question.length() <= 12) words.add(card.question);
        if (words.isEmpty()) for (int i = 0; i < KANA.length(); i++) words.add(String.valueOf(KANA.charAt(i)));
        blobColors = new int[]{th.check, th.unlock, th.good};
        glyph.setTypeface(Typeface.DEFAULT_BOLD);
        glyph.setTextAlign(Paint.Align.CENTER);
        glyph.setColor(th.text);
    }

    private static int mix(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return 0xFF000000 | ((int) (ar + (br - ar) * t) << 16) | ((int) (ag + (bg - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        base.setShader(new LinearGradient(0, 0, 0, h, th.bg, mix(th.bg, th.unlock, 0.12f), Shader.TileMode.CLAMP));
        for (int i = 0; i < 3; i++) {
            float r = w * blobPath[i][3];
            int col = (blobColors[i] & 0x00FFFFFF) | 0x38000000;
            shaders[i] = new RadialGradient(0, 0, r, new int[]{col, col & 0x00FFFFFF}, null, Shader.TileMode.CLAMP);
        }
        if (!seeded && w > 0) {
            for (int i = 0; i < GLYPHS; i++) spawn(i, w, h, rng.nextFloat() * h);
            seeded = true;
        }
    }

    private void spawn(int i, int w, int h, float y) {
        float dens = getResources().getDisplayMetrics().scaledDensity;
        gText[i] = words.get(rng.nextInt(words.size()));
        // Longer words get a smaller font so they stay subtle and fit on screen.
        int len = gText[i].length();
        float sp = (len <= 1 ? 18 + rng.nextFloat() * 26 : len <= 3 ? 16 + rng.nextFloat() * 16 : 13 + rng.nextFloat() * 9);
        gSize[i] = sp * dens;
        glyph.setTextSize(gSize[i]);
        float half = Math.min(w / 2f, glyph.measureText(gText[i]) / 2f + gSize[i] * 0.6f);
        gX[i] = half + rng.nextFloat() * Math.max(1f, w - 2 * half);
        gY[i] = y;
        gSpeed[i] = (8 + rng.nextFloat() * 14) * dens;   // px per second
        gSway[i] = rng.nextFloat() * 6.28f;
        gAlpha[i] = 0.05f + rng.nextFloat() * 0.07f;
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w == 0) return;
        float t = (SystemClock.uptimeMillis() - start) / 1000f;
        float dt = lastFrame == 0 ? 0 : Math.min(0.1f, (SystemClock.uptimeMillis() - lastFrame) / 1000f);
        lastFrame = SystemClock.uptimeMillis();

        c.drawRect(0, 0, w, h, base);

        for (int i = 0; i < 3; i++) {
            float[] p = blobPath[i];
            float x = w * (0.5f + 0.42f * (float) Math.sin(t * p[0] * 6.28f + p[2]));
            float y = h * (0.45f + 0.38f * (float) Math.cos(t * p[1] * 6.28f + p[2] * 1.3f));
            blob.setShader(shaders[i]);
            c.save();
            c.translate(x, y);
            c.drawCircle(0, 0, w * p[3], blob);
            c.restore();
        }

        for (int i = 0; i < GLYPHS; i++) {
            gY[i] -= gSpeed[i] * dt;
            if (gY[i] < -gSize[i]) spawn(i, w, h, h + gSize[i]);
            float x = gX[i] + (float) Math.sin(t * 0.4f + gSway[i]) * gSize[i] * 0.6f;
            // Fade in at the bottom and out near the top.
            float edge = Math.min(1f, Math.min(gY[i] / (h * 0.25f), (h - gY[i]) / (h * 0.15f)));
            glyph.setTextSize(gSize[i]);
            glyph.setAlpha((int) (255 * gAlpha[i] * Math.max(0f, edge)));
            c.drawText(gText[i], x, gY[i], glyph);
        }

        if (running) postInvalidateDelayed(33);
    }

    @Override protected void onWindowVisibilityChanged(int v) {
        super.onWindowVisibilityChanged(v);
        boolean was = running;
        running = v == VISIBLE;
        lastFrame = 0;
        if (running && !was) invalidate();
    }

    @Override protected void onDetachedFromWindow() {
        running = false;
        super.onDetachedFromWindow();
    }
}
