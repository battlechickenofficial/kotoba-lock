package com.kotobalock;

import android.os.Handler;
import android.os.Looper;
import android.text.Html;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Example sentences for a word, from jisho.org's sentence search ("word #sentences").
 * Only the Japanese sentence (furigana removed) and its English translation are kept.
 */
public final class Sentences {
    public static final class Sentence {
        public final String japanese, english;
        /** The Japanese sentence in pieces: {text, furigana} — furigana is null for plain text. */
        public final List<String[]> parts;
        Sentence(String j, String e, List<String[]> p) { japanese = j; english = e; parts = p; }
    }

    public interface Callback {
        /** {@code list} is null if the request failed, empty if jisho has no sentences. */
        void done(List<Sentence> list);
    }

    private static final int MAX = 20;
    private static final Map<String, List<Sentence>> cache = new HashMap<>();
    private static final Pattern JA = Pattern.compile(
            "<ul class=\"japanese_sentence[^\"]*\"[^>]*>(.*?)</ul>", Pattern.DOTALL);
    private static final Pattern EN = Pattern.compile("<span class=\"english\">(.*?)</span>", Pattern.DOTALL);

    private Sentences() {}

    /** Calls back on the main thread. */
    public static void get(String word, Callback cb) {
        synchronized (cache) {
            List<Sentence> hit = cache.get(word);
            if (hit != null) { cb.done(hit); return; }
        }
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            List<Sentence> list = fetch(word);
            if (list != null) synchronized (cache) { cache.put(word, list); }
            main.post(() -> cb.done(list));
        }, "jisho-sentences").start();
    }

    private static List<Sentence> fetch(String word) {
        HttpURLConnection con = null;
        try {
            String q = URLEncoder.encode(word + " #sentences", "UTF-8").replace("+", "%20");
            con = (HttpURLConnection) new URL("https://jisho.org/search/" + q).openConnection();
            con.setConnectTimeout(10000);
            con.setReadTimeout(15000);
            con.setRequestProperty("User-Agent", "KotobaLock (Android)");
            if (con.getResponseCode() != 200) return null;
            return parse(readAll(con.getInputStream()));
        } catch (Exception e) {
            return null;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    static List<Sentence> parse(String html) {
        List<Sentence> out = new ArrayList<>();
        String[] blocks = html.split("<li class=\"entry sentence");
        for (int i = 1; i < blocks.length && out.size() < MAX; i++) {
            Matcher j = JA.matcher(blocks[i]), e = EN.matcher(blocks[i]);
            if (!j.find() || !e.find()) continue;
            List<String[]> parts = parts(j.group(1));
            StringBuilder ja = new StringBuilder();
            for (String[] p : parts) ja.append(p[0]);
            String en = text(e.group(1));
            if (ja.length() > 0) out.add(new Sentence(ja.toString(), en, parts));
        }
        return out;
    }

    private static final Pattern LI = Pattern.compile("<li[^>]*>(.*?)</li>", Pattern.DOTALL);
    private static final Pattern FURI = Pattern.compile("<span class=\"furigana\">(.*?)</span>", Pattern.DOTALL);

    /** Splits jisho's sentence markup into plain text and {word, furigana} pieces. */
    private static List<String[]> parts(String html) {
        List<String[]> out = new ArrayList<>();
        Matcher li = LI.matcher(html);
        int pos = 0;
        while (li.find()) {
            addPlain(out, html.substring(pos, li.start()));
            String inner = li.group(1);
            Matcher f = FURI.matcher(inner);
            String reading = f.find() ? text(f.group(1)) : "";
            String base = text(FURI.matcher(inner).replaceAll(""));
            if (!base.isEmpty()) out.add(new String[]{base, reading.isEmpty() ? null : reading});
            pos = li.end();
        }
        addPlain(out, html.substring(pos));
        return out;
    }

    private static void addPlain(List<String[]> out, String html) {
        String t = text(html);
        if (!t.isEmpty()) out.add(new String[]{t, null});
    }

    /** Strip tags, decode entities, collapse whitespace (Japanese has no spaces to keep). */
    private static String text(String html) {
        String s = Html.fromHtml(html.replaceAll("<[^>]+>", ""), Html.FROM_HTML_MODE_LEGACY).toString();
        return s.replaceAll("\\s+", " ").trim().replaceAll("(?<=[^\\x00-\\x7F]) (?=[^\\x00-\\x7F])", "");
    }

    private static String readAll(InputStream in) throws Exception {
        try (InputStream is = in; ByteArrayOutputStream bo = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192]; int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString("UTF-8");
        }
    }
}
