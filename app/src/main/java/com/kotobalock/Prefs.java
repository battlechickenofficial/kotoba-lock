package com.kotobalock;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

/** All user-editable settings, stored in SharedPreferences. */
public final class Prefs {
    public static final String DEFAULT_DECK_URL =
            ""; // paste your own deck link in the app's settings

    public static final String ENABLED = "enabled";
    public static final String DECK_URL = "deck_url";
    public static final String REQUIRE_CORRECT = "require_correct";
    public static final String QUESTIONS_PER_UNLOCK = "questions_per_unlock";
    public static final String COOLDOWN_MIN = "cooldown_min";
    public static final String ACCEPT_ROMAJI = "accept_romaji";
    public static final String AUTO_SYNC_HOURS = "auto_sync_hours";
    public static final String LAST_SYNC = "last_sync";
    public static final String LAST_SYNC_MSG = "last_sync_msg";
    public static final String LAST_QUIZ_DONE = "last_quiz_done";
    public static final String LOCK_MODE = "lock_mode";
    public static final String ALLOW_SKIP = "allow_skip";
    public static final String MEANING_WRONG = "meaning_wrong";
    public static final String MEANING_RIGHT = "meaning_right";
    public static final String MEANING_LOOKUP = "meaning_lookup";
    public static final String QUESTION_SIZE = "question_size";
    public static final String KEEP_KEYBOARD_WRONG = "keep_keyboard_wrong";
    public static final String HIDE_NAV_BAR = "hide_nav_bar";
    public static final String FLOAT_DECK_WORDS = "float_deck_words";
    public static final String SHOW_ANSWERED = "show_answered";
    public static final String PRIORITISE_WEAK = "prioritise_weak";
    public static final String NEW_LIMIT_ON = "new_limit_on";
    public static final String NEW_PER_DAY = "new_per_day";

    /** Default true: lifetime answered count in the middle of the quiz's bottom bar. */
    public static boolean showAnswered(Context c) { return get(c).getBoolean(SHOW_ANSWERED, true); }
    /** Default true: words you get wrong come up more often. */
    public static boolean prioritiseWeak(Context c) { return get(c).getBoolean(PRIORITISE_WEAK, true); }
    /** Default true, 20/day: cap on words you've never answered before. */
    public static boolean newLimitOn(Context c) { return get(c).getBoolean(NEW_LIMIT_ON, true); }
    public static int newPerDay(Context c) { return Math.max(1, Math.min(999, get(c).getInt(NEW_PER_DAY, 20))); }

    public static final String EXIT_ANIM = "exit_anim";
    public static final String EXIT_ANIM_MS = "exit_anim_ms";

    /** How the quiz leaves the screen after 解放. Index into this list is what gets stored. */
    public static final String[] EXIT_ANIMS = {
            "None (instant)", "Fade", "Slide up", "Slide down", "Slide sideways", "Zoom through",
            "Shrink away", "Circle close", "Curtain", "Spin away", "Card flip", "Drop", "Blur dissolve",
    };
    public static int exitAnim(Context c) {
        int v = get(c).getInt(EXIT_ANIM, 1);
        return v < 0 || v >= EXIT_ANIMS.length ? 1 : v;
    }
    /** Exit animation length in milliseconds. */
    public static int exitAnimMs(Context c) { return Math.max(50, Math.min(5000, get(c).getInt(EXIT_ANIM_MS, 450))); }

    /** Background animation: true (default) = words from your deck, false = single hiragana. */
    public static boolean floatDeckWords(Context c) { return get(c).getBoolean(FLOAT_DECK_WORDS, true); }

    /** Default true: hide Android's home/back/recents bar while the quiz is shown. */
    public static boolean hideNavBar(Context c) { return get(c).getBoolean(HIDE_NAV_BAR, true); }

    /** Default false: the keyboard closes after a wrong answer so the answer and meaning are visible. */
    public static boolean keepKeyboardOnWrong(Context c) { return get(c).getBoolean(KEEP_KEYBOARD_WRONG, false); }

    /** Lock screen mode: the quiz is put up when the screen turns off, so it is the first thing you see. */
    public static boolean lockMode(Context c) { return get(c).getBoolean(LOCK_MODE, false); }
    public static boolean allowSkip(Context c) { return get(c).getBoolean(ALLOW_SKIP, true); }
    public static boolean meaningWrong(Context c) { return get(c).getBoolean(MEANING_WRONG, true); }
    public static boolean meaningRight(Context c) { return get(c).getBoolean(MEANING_RIGHT, true); }
    /** Look meanings up on jisho.org when the deck card has no comment. */
    public static boolean meaningLookup(Context c) { return get(c).getBoolean(MEANING_LOOKUP, true); }
    public static int questionSize(Context c) { return Math.max(20, Math.min(96, get(c).getInt(QUESTION_SIZE, 52))); }

    private Prefs() {}

    public static SharedPreferences get(Context c) {
        return c.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    public static boolean enabled(Context c) { return get(c).getBoolean(ENABLED, true); }
    public static String deckUrl(Context c) { return get(c).getString(DECK_URL, DEFAULT_DECK_URL); }
    /** Default false: one attempt is enough, the answer is then shown and you may unlock. */
    public static boolean requireCorrect(Context c) { return get(c).getBoolean(REQUIRE_CORRECT, false); }
    public static int questionsPerUnlock(Context c) { return Math.max(1, get(c).getInt(QUESTIONS_PER_UNLOCK, 1)); }
    public static int cooldownMin(Context c) { return Math.max(0, get(c).getInt(COOLDOWN_MIN, 0)); }
    public static boolean acceptRomaji(Context c) { return get(c).getBoolean(ACCEPT_ROMAJI, false); }
    public static int autoSyncHours(Context c) { return Math.max(0, get(c).getInt(AUTO_SYNC_HOURS, 6)); }

    /** Returns {deckId, secret} parsed from a kotobaweb deck link (secret may be ""), or null. */
    public static String[] parseDeckUrl(String url) {
        if (url == null) return null;
        url = url.trim();
        if (url.isEmpty()) return null;
        try {
            Uri u = Uri.parse(url);
            String id = null;
            if (u.getHost() != null) {
                java.util.List<String> seg = u.getPathSegments();
                int i = seg.indexOf("decks");
                if (i >= 0 && i + 1 < seg.size()) id = seg.get(i + 1);
                String s = u.getQueryParameter("secret");
                if (id != null) return new String[]{id, s == null ? "" : s};
            } else if (url.matches("[0-9a-fA-F]{24}")) {
                return new String[]{url, ""};
            }
        } catch (Exception ignored) {}
        return null;
    }
}
