package com.kotobalock;

import android.content.Context;
import android.content.SharedPreferences;

/** User-customisable colours for the quiz screen. */
public final class QuizTheme {
    /** Colour slots: pref key, label, default (Midnight). */
    public static final String[][] SLOTS = {
            {"c_bg", "Background"},
            {"c_card", "Word card"},
            {"c_text", "Main text"},
            {"c_sub", "Secondary text"},
            {"c_check", "「確認」 button"},
            {"c_unlock", "「解放」 / 「次の単語」 button"},
            {"c_btntext", "Button text"},
            {"c_good", "Correct"},
            {"c_bad", "Wrong"},
    };

    /** name, then one colour per SLOTS entry (same order). The first one is the default. */
    public static final Object[][] PRESETS = {
            {"Midnight", 0xFF111418, 0xFF1C2128, 0xFFF2F4F7, 0xFF9AA4B2, 0xFFC8102E, 0xFF2D6CDF, 0xFFFFFFFF, 0xFF3DD68C, 0xFFFF6B6B},
            {"Light",    0xFFF5F5F7, 0xFFFFFFFF, 0xFF1C1C1E, 0xFF6B7280, 0xFFC8102E, 0xFF2563EB, 0xFFFFFFFF, 0xFF16A34A, 0xFFDC2626},
            {"Sakura",   0xFFFFF1F3, 0xFFFFFFFF, 0xFF3B1F2B, 0xFF9C6B7E, 0xFFE75480, 0xFFB56576, 0xFFFFFFFF, 0xFF2E9E6B, 0xFFD7263D},
            {"Matcha",   0xFF1E2A1F, 0xFF2B3A2C, 0xFFEEF3E8, 0xFFA7B8A0, 0xFF7FA650, 0xFFC9A227, 0xFF10170F, 0xFF9BE36B, 0xFFFF8A80},
            {"Ocean",    0xFF0B1E33, 0xFF13304F, 0xFFE6F1FF, 0xFF8FB3D9, 0xFF00A6C8, 0xFF3D7BFF, 0xFFFFFFFF, 0xFF4ADE80, 0xFFFF7A7A},
            {"OLED",     0xFF000000, 0xFF0E0E0E, 0xFFFFFFFF, 0xFF8A8A8A, 0xFF3355FF, 0xFF2A2A2A, 0xFFFFFFFF, 0xFF3DD68C, 0xFFFF5C5C},
    };

    /** Swatches offered in the colour picker. */
    public static final int[] SWATCHES = {
            0xFF000000, 0xFF111418, 0xFF1C2128, 0xFF2A2A2A, 0xFF6B7280, 0xFF9AA4B2, 0xFFF5F5F7, 0xFFFFFFFF,
            0xFFC8102E, 0xFFDC2626, 0xFFFF6B6B, 0xFFE75480, 0xFFFFB3C6, 0xFFB56576, 0xFF9333EA, 0xFF6D28D9,
            0xFF2563EB, 0xFF2D6CDF, 0xFF3D7BFF, 0xFF00A6C8, 0xFF0B1E33, 0xFF16A34A, 0xFF3DD68C, 0xFF7FA650,
            0xFF1E2A1F, 0xFFC9A227, 0xFFF59E0B, 0xFFEA580C, 0xFF78350F, 0xFFFFF1F3,
    };

    public final int bg, card, text, sub, check, unlock, btnText, good, bad;

    private QuizTheme(int[] c) {
        bg = c[0]; card = c[1]; text = c[2]; sub = c[3]; check = c[4]; unlock = c[5]; btnText = c[6]; good = c[7]; bad = c[8];
    }

    public static int defaultColor(int slot) { return (Integer) PRESETS[0][slot + 1]; }

    public static int color(Context ctx, int slot) {
        return Prefs.get(ctx).getInt(SLOTS[slot][0], defaultColor(slot));
    }

    public static QuizTheme load(Context ctx) {
        int[] c = new int[SLOTS.length];
        for (int i = 0; i < c.length; i++) c[i] = color(ctx, i);
        return new QuizTheme(c);
    }

    public static void applyPreset(Context ctx, int preset) {
        SharedPreferences.Editor e = Prefs.get(ctx).edit();
        for (int i = 0; i < SLOTS.length; i++) e.putInt(SLOTS[i][0], (Integer) PRESETS[preset][i + 1]);
        e.apply();
    }

    /** Hint text colour: secondary text at reduced opacity. */
    public int hint() { return (sub & 0x00FFFFFF) | 0x99000000; }
}
