package com.kotobalock;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/**
 * Your answer history: a lifetime count, per-word results, and how many new words you met today.
 * Words are keyed by their question text, so they survive deck re-syncs.
 * Only the first attempt at a card counts (strict-mode retypes don't).
 */
public final class Stats {
    public static final class Word {
        public int right, wrong;
        /** 0 = you always get it right, 1 = you always miss it. Recent answers count most. */
        public float weakness = 0.5f;
        public long lastSeen;
    }

    private static Map<String, Word> words;
    private static long total;
    private static String day = "";
    private static int newToday;

    private Stats() {}

    private static File file(Context c) { return new File(c.getFilesDir(), "stats.json"); }

    private static String today() { return new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date()); }

    private static void load(Context c) {
        if (words != null) return;
        words = new HashMap<>();
        File f = file(c);
        if (!f.exists()) return;
        try {
            JSONObject o = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            total = o.optLong("total", 0);
            day = o.optString("day", "");
            newToday = o.optInt("newToday", 0);
            JSONObject w = o.optJSONObject("words");
            if (w != null) {
                for (Iterator<String> it = w.keys(); it.hasNext(); ) {
                    String k = it.next();
                    JSONObject j = w.optJSONObject(k);
                    if (j == null) continue;
                    Word x = new Word();
                    x.right = j.optInt("r");
                    x.wrong = j.optInt("w");
                    x.weakness = (float) j.optDouble("k", 0.5);
                    x.lastSeen = j.optLong("t");
                    words.put(k, x);
                }
            }
        } catch (Exception ignored) {}
    }

    private static void save(Context c) {
        try {
            JSONObject w = new JSONObject();
            for (Map.Entry<String, Word> e : words.entrySet()) {
                Word x = e.getValue();
                w.put(e.getKey(), new JSONObject().put("r", x.right).put("w", x.wrong).put("k", x.weakness).put("t", x.lastSeen));
            }
            JSONObject o = new JSONObject().put("total", total).put("day", day).put("newToday", newToday).put("words", w);
            File tmp = new File(c.getFilesDir(), "stats.json.tmp");
            try (FileOutputStream fo = new FileOutputStream(tmp)) { fo.write(o.toString().getBytes(StandardCharsets.UTF_8)); }
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(file(c));
        } catch (Exception ignored) {}
    }

    private static void rollDay() {
        String t = today();
        if (!t.equals(day)) { day = t; newToday = 0; }
    }

    /** Record the first attempt at a card. */
    public static synchronized void record(Context c, Deck.Card card, boolean correct) {
        load(c);
        rollDay();
        Word x = words.get(card.question);
        if (x == null) {
            x = new Word();
            words.put(card.question, x);
            newToday++;
        }
        if (correct) x.right++; else x.wrong++;
        x.weakness = 0.6f * x.weakness + 0.4f * (correct ? 0f : 1f);
        x.lastSeen = System.currentTimeMillis();
        total++;
        save(c);
    }

    public static synchronized long total(Context c) { load(c); return total; }

    public static synchronized int newToday(Context c) { load(c); rollDay(); return newToday; }

    /** Null if you have never answered this word. */
    public static synchronized Word get(Context c, String question) { load(c); return words.get(question); }

    /** {words seen, total right, total wrong, words you're struggling with}. */
    public static synchronized int[] summary(Context c) {
        load(c);
        int right = 0, wrong = 0, weak = 0;
        for (Word x : words.values()) {
            right += x.right; wrong += x.wrong;
            if (x.weakness >= 0.5f && x.wrong > 0) weak++;
        }
        return new int[]{words.size(), right, wrong, weak};
    }

    public static synchronized void reset(Context c) {
        words = new HashMap<>();
        total = 0;
        newToday = 0;
        day = today();
        save(c);
    }
}
