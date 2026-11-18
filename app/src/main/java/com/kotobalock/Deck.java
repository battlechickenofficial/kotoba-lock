package com.kotobalock;

import android.content.Context;
import android.webkit.CookieManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Loads the cached kotoba deck and syncs it from kotobaweb.com. */
public final class Deck {
    public static final class Card {
        public final String question, comment, instructions;
        public final List<String> answers;
        Card(String q, List<String> a, String c, String ins) { question = q; answers = a; comment = c; instructions = ins; }
    }

    public static final class SyncResult {
        public final boolean ok; public final boolean needsLogin; public final String message;
        SyncResult(boolean ok, boolean needsLogin, String message) { this.ok = ok; this.needsLogin = needsLogin; this.message = message; }
    }

    private static final String BASE = "https://kotobaweb.com";
    private static final Random RNG = new Random();
    private static List<Card> cache;
    private static String cacheName = "";
    private static int lastIndex = -1;

    private Deck() {}

    private static File file(Context c) { return new File(c.getFilesDir(), "deck.json"); }

    public static synchronized List<Card> cards(Context c) {
        if (cache == null) {
            cache = new ArrayList<>();
            File f = file(c);
            if (f.exists()) {
                try { parse(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)); }
                catch (Exception ignored) {}
            }
        }
        return cache;
    }

    public static synchronized String name(Context c) { cards(c); return cacheName; }

    /**
     * Picks the next card, avoiding an immediate repeat when possible.
     * - Daily new-word limit: once today's quota of never-answered words is used up, only words
     *   you've already met are shown (unless there are none yet).
     * - Prioritise struggles: words you miss are weighted up to ~13x more likely than ones you know.
     */
    public static synchronized Card random(Context c) {
        List<Card> l = cards(c);
        if (l.isEmpty()) return null;
        boolean limit = Prefs.newLimitOn(c);
        boolean prioritise = Prefs.prioritiseWeak(c);
        if (!limit && !prioritise) return uniform(l);

        List<Card> fresh = new ArrayList<>(), seen = new ArrayList<>();
        List<Stats.Word> seenStats = new ArrayList<>();
        for (int i = 0; i < l.size(); i++) {
            if (i == lastIndex && l.size() > 1) continue;
            Stats.Word w = Stats.get(c, l.get(i).question);
            if (w == null) fresh.add(l.get(i)); else { seen.add(l.get(i)); seenStats.add(w); }
        }
        boolean newAllowed = !fresh.isEmpty()
                && (!limit || Stats.newToday(c) < Prefs.newPerDay(c) || seen.isEmpty());
        Card pick;
        if (newAllowed && (seen.isEmpty() || RNG.nextFloat() < 0.4f)) {
            pick = fresh.get(RNG.nextInt(fresh.size()));
        } else if (!seen.isEmpty()) {
            if (!prioritise) {
                pick = seen.get(RNG.nextInt(seen.size()));
            } else {
                double[] wt = new double[seen.size()];
                double sum = 0;
                for (int i = 0; i < wt.length; i++) {
                    float k = seenStats.get(i).weakness;
                    wt[i] = 0.25 + 3.0 * k * k;  // ~0.25 for mastered, ~3.25 for always-missed
                    sum += wt[i];
                }
                double r = RNG.nextDouble() * sum;
                int i = 0;
                while (i < wt.length - 1 && (r -= wt[i]) > 0) i++;
                pick = seen.get(i);
            }
        } else {
            return uniform(l);
        }
        lastIndex = l.indexOf(pick);
        return pick;
    }

    private static Card uniform(List<Card> l) {
        int i = RNG.nextInt(l.size());
        if (l.size() > 1 && i == lastIndex) i = (i + 1 + RNG.nextInt(l.size() - 1)) % l.size();
        lastIndex = i;
        return l.get(i);
    }

    private static void parse(String json) throws Exception {
        JSONObject o = new JSONObject(json);
        List<Card> out = new ArrayList<>();
        JSONArray arr = o.optJSONArray("cards");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject card = arr.optJSONObject(i);
                if (card == null) continue;
                if ("IMAGE_URI".equals(card.optString("questionCreationStrategy"))) continue;
                String q = card.optString("question", "").trim();
                JSONArray a = card.optJSONArray("answers");
                if (q.isEmpty() || a == null || a.length() == 0) continue;
                List<String> answers = new ArrayList<>();
                for (int j = 0; j < a.length(); j++) {
                    String s = a.optString(j, "").trim();
                    if (!s.isEmpty()) answers.add(s);
                }
                if (answers.isEmpty()) continue;
                out.add(new Card(q, answers, card.optString("comment", "").trim(), card.optString("instructions", "").trim()));
            }
        }
        if (out.isEmpty()) throw new Exception("Deck has no usable cards");
        cache = out;
        cacheName = o.optString("name", "");
    }

    /** Blocking network call: run off the main thread. */
    public static SyncResult sync(Context c) {
        SyncResult r = doSync(c);
        Prefs.get(c).edit()
                .putLong(Prefs.LAST_SYNC, r.ok ? System.currentTimeMillis() : Prefs.get(c).getLong(Prefs.LAST_SYNC, 0))
                .putString(Prefs.LAST_SYNC_MSG, r.message)
                .apply();
        return r;
    }

    private static SyncResult doSync(Context c) {
        String[] parsed = Prefs.parseDeckUrl(Prefs.deckUrl(c));
        if (parsed == null) return new SyncResult(false, false, "Deck link is not valid");
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(BASE + "/api/decks/" + parsed[0]).openConnection();
            con.setConnectTimeout(15000);
            con.setReadTimeout(20000);
            con.setRequestProperty("Accept", "application/json");
            con.setRequestProperty("Deck-Permissions-Secret", parsed[1]);
            String cookies = CookieManager.getInstance().getCookie(BASE);
            if (cookies != null) con.setRequestProperty("Cookie", cookies);
            int code = con.getResponseCode();
            if (code == 401) return new SyncResult(false, true, "Not logged in to Kotoba — tap \"Log in with Discord\"");
            if (code == 403) return new SyncResult(false, false, "No permission to view this deck (check the secret in the link)");
            if (code == 404) return new SyncResult(false, false, "Deck not found");
            if (code != 200) return new SyncResult(false, false, "Server error " + code);
            String body = readAll(con.getInputStream());
            synchronized (Deck.class) {
                parse(body);
                File tmp = new File(c.getFilesDir(), "deck.json.tmp");
                try (FileOutputStream fo = new FileOutputStream(tmp)) { fo.write(body.getBytes(StandardCharsets.UTF_8)); }
                //noinspection ResultOfMethodCallIgnored
                tmp.renameTo(file(c));
            }
            return new SyncResult(true, false, "Synced " + cache.size() + " cards" + (cacheName.isEmpty() ? "" : " from \"" + cacheName + "\""));
        } catch (Exception e) {
            return new SyncResult(false, false, "Sync failed: " + e.getMessage());
        } finally {
            if (con != null) con.disconnect();
        }
    }

    private static String readAll(InputStream in) throws Exception {
        try (InputStream is = in; ByteArrayOutputStream bo = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192]; int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString("UTF-8");
        }
    }

    /** Sync in the background if the cached deck is older than the auto-sync interval. */
    public static void maybeAutoSync(Context c) {
        int h = Prefs.autoSyncHours(c);
        if (h <= 0) return;
        long last = Prefs.get(c).getLong(Prefs.LAST_SYNC, 0);
        if (System.currentTimeMillis() - last < h * 3600_000L) return;
        Context app = c.getApplicationContext();
        new Thread(() -> sync(app), "deck-sync").start();
    }
}
