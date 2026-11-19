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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Your Kotoba decks: each is cached in its own file and can be switched on or off.
 * The question pool is every enabled deck merged together, with questions that appear in more
 * than one deck counted once (their accepted answers are combined).
 */
public final class Deck {
    public static final class Card {
        public final String question, comment, instructions;
        public final List<String> answers;
        Card(String q, List<String> a, String c, String ins) { question = q; answers = a; comment = c; instructions = ins; }
    }

    /** One deck you've added. */
    public static final class Info {
        public final String id, secret, url;
        public String name;
        public boolean enabled;
        Info(String id, String secret, String url, String name, boolean enabled) {
            this.id = id; this.secret = secret; this.url = url; this.name = name; this.enabled = enabled;
        }
        public String label() { return name == null || name.isEmpty() ? "Deck " + id.substring(Math.max(0, id.length() - 6)) : name; }
    }

    public static final class SyncResult {
        public final boolean ok; public final boolean needsLogin; public final String message;
        SyncResult(boolean ok, boolean needsLogin, String message) { this.ok = ok; this.needsLogin = needsLogin; this.message = message; }
    }

    private static final String BASE = "https://kotobaweb.com";
    private static final String PREF_DECKS = "decks";
    private static final Random RNG = new Random();

    private static List<Info> infos;
    private static final Map<String, List<Card>> perDeck = new HashMap<>();
    private static List<Card> merged;
    private static String lastQuestion;

    private Deck() {}

    private static File file(Context c, String id) { return new File(c.getFilesDir(), "deck_" + id + ".json"); }

    // ================================================================ deck list

    public static synchronized List<Info> decks(Context c) {
        if (infos != null) return infos;
        infos = new ArrayList<>();
        String raw = Prefs.get(c).getString(PREF_DECKS, null);
        if (raw == null) {
            migrate(c);
        } else {
            try {
                JSONArray a = new JSONArray(raw);
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i);
                    infos.add(new Info(o.getString("id"), o.optString("secret"), o.optString("url"),
                            o.optString("name"), o.optBoolean("on", true)));
                }
            } catch (Exception ignored) {}
        }
        return infos;
    }

    /** Before 1.12 there was one deck link and one deck.json: turn that into the first deck. */
    private static void migrate(Context c) {
        String url = Prefs.get(c).getString(Prefs.DECK_URL, "");
        String[] p = Prefs.parseDeckUrl(url);
        if (p != null) {
            infos.add(new Info(p[0], p[1], url.trim(), "", true));
            File old = new File(c.getFilesDir(), "deck.json");
            //noinspection ResultOfMethodCallIgnored
            if (old.exists()) old.renameTo(file(c, p[0]));
        }
        save(c);
    }

    private static void save(Context c) {
        JSONArray a = new JSONArray();
        try {
            for (Info d : infos) a.put(new JSONObject().put("id", d.id).put("secret", d.secret).put("url", d.url)
                    .put("name", d.name == null ? "" : d.name).put("on", d.enabled));
        } catch (Exception ignored) {}
        Prefs.get(c).edit().putString(PREF_DECKS, a.toString()).apply();
    }

    /** Adds a deck from its kotobaweb link. Returns an error message, or null when added. */
    public static synchronized String add(Context c, String url) {
        String[] p = Prefs.parseDeckUrl(url);
        if (p == null) return "That doesn't look like a Kotoba deck link";
        for (Info d : decks(c)) if (d.id.equals(p[0])) return "That deck is already added";
        infos.add(new Info(p[0], p[1], url.trim(), "", true));
        save(c);
        merged = null;
        return null;
    }

    public static synchronized void remove(Context c, String id) {
        decks(c).removeIf(d -> d.id.equals(id));
        perDeck.remove(id);
        //noinspection ResultOfMethodCallIgnored
        file(c, id).delete();
        save(c);
        merged = null;
    }

    public static synchronized void setEnabled(Context c, String id, boolean on) {
        for (Info d : decks(c)) if (d.id.equals(id)) d.enabled = on;
        save(c);
        merged = null;
    }

    // ================================================================ cards

    /** Cards of a single deck (whether or not it is enabled). */
    public static synchronized List<Card> cardsOf(Context c, String id) {
        List<Card> l = perDeck.get(id);
        if (l != null) return l;
        l = new ArrayList<>();
        File f = file(c, id);
        if (f.exists()) {
            try { l = parse(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8), null); }
            catch (Exception ignored) {}
        }
        perDeck.put(id, l);
        return l;
    }

    /** The question pool: all enabled decks, each question once. */
    public static synchronized List<Card> cards(Context c) {
        if (merged != null) return merged;
        Map<String, Card> byQ = new LinkedHashMap<>();
        for (Info d : decks(c)) {
            if (!d.enabled) continue;
            for (Card k : cardsOf(c, d.id)) {
                Card prev = byQ.get(k.question);
                if (prev == null) { byQ.put(k.question, k); continue; }
                // Overlap: keep one card, accept the answers from both decks.
                LinkedHashSet<String> ans = new LinkedHashSet<>(prev.answers);
                ans.addAll(k.answers);
                byQ.put(k.question, new Card(prev.question, new ArrayList<>(ans),
                        prev.comment.isEmpty() ? k.comment : prev.comment,
                        prev.instructions.isEmpty() ? k.instructions : prev.instructions));
            }
        }
        merged = new ArrayList<>(byQ.values());
        return merged;
    }

    /** How many decks are switched on. */
    public static synchronized int enabledCount(Context c) {
        int n = 0;
        for (Info d : decks(c)) if (d.enabled) n++;
        return n;
    }

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
        if (!limit && !prioritise) return remember(uniform(l));

        List<Card> fresh = new ArrayList<>(), seen = new ArrayList<>();
        List<Stats.Word> seenStats = new ArrayList<>();
        for (Card k : l) {
            if (l.size() > 1 && k.question.equals(lastQuestion)) continue;
            Stats.Word w = Stats.get(c, k.question);
            if (w == null) fresh.add(k); else { seen.add(k); seenStats.add(w); }
        }
        boolean newAllowed = !fresh.isEmpty()
                && (!limit || Stats.newToday(c) < Prefs.newPerDay(c) || seen.isEmpty());
        if (newAllowed && (seen.isEmpty() || RNG.nextFloat() < 0.4f)) {
            return remember(fresh.get(RNG.nextInt(fresh.size())));
        }
        if (seen.isEmpty()) return remember(uniform(l));
        if (!prioritise) return remember(seen.get(RNG.nextInt(seen.size())));
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
        return remember(seen.get(i));
    }

    private static Card remember(Card k) { lastQuestion = k.question; return k; }

    private static Card uniform(List<Card> l) {
        Card k = l.get(RNG.nextInt(l.size()));
        if (l.size() > 1 && k.question.equals(lastQuestion)) {
            int i = l.indexOf(k);
            k = l.get((i + 1 + RNG.nextInt(l.size() - 1)) % l.size());
        }
        return k;
    }

    private static List<Card> parse(String json, String[] nameOut) throws Exception {
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
        if (nameOut != null) nameOut[0] = o.optString("name", "");
        return out;
    }

    // ================================================================ sync

    /** Downloads every deck. Blocking network call: run off the main thread. */
    public static SyncResult sync(Context c) {
        List<Info> list;
        synchronized (Deck.class) { list = new ArrayList<>(decks(c)); }
        SyncResult r;
        if (list.isEmpty()) {
            r = new SyncResult(false, false, "Add a deck first");
        } else {
            int ok = 0;
            boolean login = false;
            StringBuilder errors = new StringBuilder();
            for (Info d : list) {
                SyncResult one = syncOne(c, d);
                if (one.ok) ok++;
                else {
                    login |= one.needsLogin;
                    errors.append(errors.length() == 0 ? "" : "\n").append(d.label()).append(": ").append(one.message);
                }
            }
            int cards;
            synchronized (Deck.class) { cards = cards(c).size(); }
            String msg = ok == list.size()
                    ? "Synced " + ok + (ok == 1 ? " deck" : " decks") + " · " + cards + " cards in the pool"
                    : ok + " of " + list.size() + " decks synced\n" + errors;
            r = new SyncResult(ok > 0, login, msg);
        }
        Prefs.get(c).edit()
                .putLong(Prefs.LAST_SYNC, r.ok ? System.currentTimeMillis() : Prefs.get(c).getLong(Prefs.LAST_SYNC, 0))
                .putString(Prefs.LAST_SYNC_MSG, r.message)
                .apply();
        return r;
    }

    /** Downloads one deck right away (used after adding it). */
    public static SyncResult syncDeck(Context c, String id) {
        Info d = null;
        synchronized (Deck.class) { for (Info x : decks(c)) if (x.id.equals(id)) d = x; }
        return d == null ? new SyncResult(false, false, "Deck not found") : syncOne(c, d);
    }

    private static SyncResult syncOne(Context c, Info d) {
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(BASE + "/api/decks/" + d.id).openConnection();
            con.setConnectTimeout(15000);
            con.setReadTimeout(20000);
            con.setRequestProperty("Accept", "application/json");
            con.setRequestProperty("Deck-Permissions-Secret", d.secret);
            String cookies = CookieManager.getInstance().getCookie(BASE);
            if (cookies != null) con.setRequestProperty("Cookie", cookies);
            int code = con.getResponseCode();
            if (code == 401) return new SyncResult(false, true, "Not logged in to Kotoba — tap \"Log in with Discord\"");
            if (code == 403) return new SyncResult(false, false, "No permission to view this deck (check the secret in the link)");
            if (code == 404) return new SyncResult(false, false, "Deck not found");
            if (code != 200) return new SyncResult(false, false, "Server error " + code);
            String body = readAll(con.getInputStream());
            String[] name = new String[1];
            List<Card> parsed = parse(body, name);
            synchronized (Deck.class) {
                File tmp = new File(c.getFilesDir(), "deck_" + d.id + ".json.tmp");
                try (FileOutputStream fo = new FileOutputStream(tmp)) { fo.write(body.getBytes(StandardCharsets.UTF_8)); }
                //noinspection ResultOfMethodCallIgnored
                tmp.renameTo(file(c, d.id));
                perDeck.put(d.id, parsed);
                d.name = name[0];
                save(c);
                merged = null;
            }
            return new SyncResult(true, false, "Synced " + parsed.size() + " cards from \"" + d.label() + "\"");
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

    /** Sync in the background if the cached decks are older than the auto-sync interval. */
    public static void maybeAutoSync(Context c) {
        int h = Prefs.autoSyncHours(c);
        if (h <= 0) return;
        long last = Prefs.get(c).getLong(Prefs.LAST_SYNC, 0);
        if (System.currentTimeMillis() - last < h * 3600_000L) return;
        Context app = c.getApplicationContext();
        new Thread(() -> sync(app), "deck-sync").start();
    }
}
