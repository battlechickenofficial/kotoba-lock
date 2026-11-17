package com.kotobalock;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * English meaning for a card: the deck's own comment if it has one, otherwise looked up
 * on jisho.org once and cached on the phone.
 */
public final class Meanings {
    public interface Callback { void onMeaning(String meaning); }

    private static JSONObject cache;

    private Meanings() {}

    private static File file(Context c) { return new File(c.getFilesDir(), "meanings.json"); }

    private static synchronized JSONObject cache(Context c) {
        if (cache == null) {
            try { cache = new JSONObject(new String(Files.readAllBytes(file(c).toPath()), StandardCharsets.UTF_8)); }
            catch (Exception e) { cache = new JSONObject(); }
        }
        return cache;
    }

    private static synchronized void store(Context c, String key, String value) {
        try {
            cache(c).put(key, value);
            try (FileOutputStream fo = new FileOutputStream(file(c))) { fo.write(cache.toString().getBytes(StandardCharsets.UTF_8)); }
        } catch (Exception ignored) {}
    }

    /** Calls back on the main thread with the meaning, or "" if none is available. */
    public static void get(Context ctx, Deck.Card card, Callback cb) {
        if (!card.comment.isEmpty()) { cb.onMeaning(card.comment); return; }
        String cached = cache(ctx).optString(card.question, null);
        if (cached != null) { cb.onMeaning(cached); return; }
        if (!Prefs.meaningLookup(ctx)) { cb.onMeaning(""); return; }
        Context app = ctx.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            String m = lookup(card);
            if (m != null) store(app, card.question, m); // null = network error, retry next time
            String out = m == null ? "" : m;
            main.post(() -> cb.onMeaning(out));
        }, "meaning").start();
    }

    private static String lookup(Deck.Card card) {
        HttpURLConnection con = null;
        try {
            URL u = new URL("https://jisho.org/api/v1/search/words?keyword=" + URLEncoder.encode(card.question, "UTF-8"));
            con = (HttpURLConnection) u.openConnection();
            con.setConnectTimeout(8000);
            con.setReadTimeout(10000);
            if (con.getResponseCode() != 200) return null;
            String body;
            try (InputStream is = con.getInputStream(); ByteArrayOutputStream bo = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192]; int n;
                while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
                body = bo.toString("UTF-8");
            }
            JSONArray data = new JSONObject(body).optJSONArray("data");
            if (data == null || data.length() == 0) return "";
            // Prefer the entry whose written form matches the word and whose reading matches an answer.
            JSONObject best = null; int bestScore = -1;
            for (int i = 0; i < data.length(); i++) {
                JSONObject e = data.getJSONObject(i);
                JSONArray jp = e.optJSONArray("japanese");
                int score = 0;
                if (jp != null) for (int j = 0; j < jp.length(); j++) {
                    JSONObject w = jp.getJSONObject(j);
                    if (card.question.equals(w.optString("word")) || card.question.equals(w.optString("reading"))) score |= 2;
                    for (String a : card.answers)
                        if (Kana.normalize(a).equals(Kana.normalize(w.optString("reading")))) score |= 1;
                }
                if (score > bestScore) { bestScore = score; best = e; }
                if (score == 3) break;
            }
            if (best == null || bestScore == 0) return "";
            JSONArray senses = best.optJSONArray("senses");
            if (senses == null) return "";
            List<String> parts = new ArrayList<>();
            for (int i = 0; i < senses.length() && parts.size() < 3; i++) {
                JSONArray defs = senses.getJSONObject(i).optJSONArray("english_definitions");
                if (defs == null || defs.length() == 0) continue;
                List<String> d = new ArrayList<>();
                for (int j = 0; j < defs.length() && j < 3; j++) d.add(defs.getString(j));
                parts.add(String.join(", ", d));
            }
            return String.join("; ", parts);
        } catch (Exception e) {
            return null;
        } finally {
            if (con != null) con.disconnect();
        }
    }
}
