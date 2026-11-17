package com.kotobalock;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.Map;

/** Kana helpers: normalisation for answer comparison and optional romaji → hiragana. */
public final class Kana {
    private Kana() {}

    /** Katakana → hiragana, NFKC, lowercase, whitespace and punctuation-ish noise removed. */
    public static String normalize(String s) {
        if (s == null) return "";
        s = Normalizer.normalize(s, Normalizer.Form.NFKC).toLowerCase();
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (Character.isWhitespace(ch) || ch == '・' || ch == '。' || ch == '、' || ch == '.') continue;
            if (ch >= 'ァ' && ch <= 'ヶ') ch = (char) (ch - 0x60);
            b.append(ch);
        }
        return b.toString();
    }

    private static final Map<String, String> T = new HashMap<>();
    private static int maxKey = 1;

    private static void put(String keys, String vals) {
        String[] k = keys.split(" "), v = vals.split(" ");
        for (int i = 0; i < k.length; i++) { T.put(k[i], v[i]); maxKey = Math.max(maxKey, k[i].length()); }
    }

    static {
        put("a i u e o", "あ い う え お");
        put("ka ki ku ke ko kya kyu kyo", "か き く け こ きゃ きゅ きょ");
        put("ga gi gu ge go gya gyu gyo", "が ぎ ぐ げ ご ぎゃ ぎゅ ぎょ");
        put("sa shi si su se so sha shu sho she sya syu syo", "さ し し す せ そ しゃ しゅ しょ しぇ しゃ しゅ しょ");
        put("za ji zi zu ze zo ja ju jo je jya jyu jyo zya zyu zyo", "ざ じ じ ず ぜ ぞ じゃ じゅ じょ じぇ じゃ じゅ じょ じゃ じゅ じょ");
        put("ta chi ti tsu tu te to cha chu cho che tya tyu tyo", "た ち ち つ つ て と ちゃ ちゅ ちょ ちぇ ちゃ ちゅ ちょ");
        put("da di du de do dya dyu dyo", "だ ぢ づ で ど ぢゃ ぢゅ ぢょ");
        put("na ni nu ne no nya nyu nyo", "な に ぬ ね の にゃ にゅ にょ");
        put("ha hi fu hu he ho hya hyu hyo fa fi fe fo", "は ひ ふ ふ へ ほ ひゃ ひゅ ひょ ふぁ ふぃ ふぇ ふぉ");
        put("ba bi bu be bo bya byu byo", "ば び ぶ べ ぼ びゃ びゅ びょ");
        put("pa pi pu pe po pya pyu pyo", "ぱ ぴ ぷ ぺ ぽ ぴゃ ぴゅ ぴょ");
        put("ma mi mu me mo mya myu myo", "ま み む め も みゃ みゅ みょ");
        put("ya yu yo", "や ゆ よ");
        put("ra ri ru re ro rya ryu ryo la li lu le lo", "ら り る れ ろ りゃ りゅ りょ ら り る れ ろ");
        put("wa wo wi we vu", "わ を うぃ うぇ ゔ");
        put("nn n' xa xi xu xe xo xya xyu xyo xtsu xtu ltu ltsu xwa", "ん ん ぁ ぃ ぅ ぇ ぉ ゃ ゅ ょ っ っ っ っ ゎ");
        put("-", "ー");
    }

    private static boolean isVowel(char c) { return "aiueo".indexOf(c) >= 0; }

    /** Converts any latin letters in the input to hiragana; existing kana is kept as-is. */
    public static String romajiToHiragana(String in) {
        if (in == null) return "";
        String s = in.toLowerCase();
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            // Double consonant → small tsu (kk, tt, ss, pp ...), but not "nn".
            if (i + 1 < s.length() && c == s.charAt(i + 1) && c >= 'a' && c <= 'z' && !isVowel(c) && c != 'n') {
                out.append('っ'); i++; continue;
            }
            // "nn": ん; if a vowel/y follows (konnichiha, onna) the second n starts the next kana.
            if (c == 'n' && i + 1 < s.length() && s.charAt(i + 1) == 'n') {
                boolean vowelAfter = i + 2 < s.length() && (isVowel(s.charAt(i + 2)) || s.charAt(i + 2) == 'y');
                out.append('ん'); i += vowelAfter ? 1 : 2; continue;
            }
            // Lone n before a consonant (or end of word) → ん
            if (c == 'n' && (i + 1 == s.length() || (!isVowel(s.charAt(i + 1)) && s.charAt(i + 1) != 'y'
                    && s.charAt(i + 1) != 'n' && s.charAt(i + 1) != '\''))) {
                out.append('ん'); i++; continue;
            }
            boolean matched = false;
            for (int len = Math.min(maxKey, s.length() - i); len >= 1; len--) {
                String v = T.get(s.substring(i, i + len));
                if (v != null) { out.append(v); i += len; matched = true; break; }
            }
            if (!matched) { out.append(in.charAt(i)); i++; }
        }
        return out.toString();
    }
}
