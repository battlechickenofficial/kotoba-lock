package com.kotobalock;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** Settings page, styled with the same theme as the quiz screen. */
public class MainActivity extends Activity {
    private QuizTheme th;
    private boolean dark;

    private Status overlayStatus, batteryStatus, lockStatus, deckStatus;
    private Button overlayBtn, batteryBtn, lockBtn;
    private EditText deckUrl, perUnlock, cooldown, syncHours, questionSize, exitAnimMs, newPerDay;
    private Status statsStatus;
    private TextView heroStatus;

    // ================================================================ lifecycle

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        th = QuizTheme.load(this);
        dark = luminance(th.bg) < 0.5f;
        if (getActionBar() != null) getActionBar().hide();
        if (Build.VERSION.SDK_INT >= 30 && !dark && getWindow().getInsetsController() != null) {
            getWindow().getInsetsController().setSystemBarsAppearance(
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        }
        getWindow().getDecorView().setBackgroundColor(th.bg);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(th.bg);
        root.addView(new AmbientBackground(this, th,
                Prefs.floatDeckWords(this) ? Deck.cards(this) : java.util.Collections.<Deck.Card>emptyList()), matchParent());
        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        root.addView(scroll, matchParent());
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(18), dp(12), dp(18), dp(40));
        col.setClipChildren(false);
        col.setClipToPadding(false);
        scroll.addView(col);
        setContentView(root);
        root.setOnApplyWindowInsetsListener((v, ins) -> {
            int top, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets s = ins.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                top = s.top; bottom = Math.max(s.bottom, ins.getInsets(WindowInsets.Type.ime()).bottom);
            } else {
                top = ins.getSystemWindowInsetTop(); bottom = ins.getSystemWindowInsetBottom();
            }
            scroll.setPadding(0, top, 0, bottom);
            return WindowInsets.CONSUMED;
        });

        // ---- Hero
        TextView title = text("ことばロック", 34, th.text, true);
        title.setLetterSpacing(0.04f);
        col.addView(title, lp(16));
        TextView sub = text("Kotoba Lock · settings", 14, th.sub, false);
        sub.setLetterSpacing(0.08f);
        col.addView(sub, lp(2));
        heroStatus = text("", 14, th.text, true);
        heroStatus.setPadding(dp(14), dp(7), dp(14), dp(7));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hp.topMargin = dp(14);
        col.addView(heroStatus, hp);
        Button tryBtn = primary("Try the quiz now", v -> {
            saveFields();
            if (!Settings.canDrawOverlays(this)) { toast("Allow \"Display over other apps\" first"); return; }
            if (Deck.cards(this).isEmpty()) { toast("Sync the deck first"); return; }
            QuizService.showNow(this);
        });
        col.addView(tryBtn, lp(18));

        // ---- Setup
        LinearLayout setup = section(col, "Setup");
        overlayStatus = status(setup);
        overlayBtn = secondary("Allow \"Display over other apps\"", v -> startActivity(new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))));
        setup.addView(overlayBtn, lp(8));
        batteryStatus = status(setup);
        batteryBtn = secondary("Disable battery optimisation", v -> startActivity(new Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()))));
        setup.addView(batteryBtn, lp(8));

        // ---- Quiz
        LinearLayout quiz = section(col, "Quiz");
        toggleRow(quiz, "Quiz me every time I unlock", null, Prefs.ENABLED, true, on -> {
            if (on) QuizService.start(this); else QuizService.stop(this);
            refreshHero();
        });
        toggleRow(quiz, "Must answer correctly to unlock",
                "Off: one attempt is enough, then the answer is shown. On: after a wrong attempt you must type the shown answer.",
                Prefs.REQUIRE_CORRECT, false, null);
        toggleRow(quiz, "Keep keyboard open after a wrong answer",
                "Off: the keyboard closes so you can see the answer — tap the box to retype.",
                Prefs.KEEP_KEYBOARD_WRONG, false, null);
        toggleRow(quiz, "Accept romaji", "Typed romaji is converted to hiragana before checking.", Prefs.ACCEPT_ROMAJI, false, null);
        perUnlock = numberRow(quiz, "Questions per unlock", null, Prefs.questionsPerUnlock(this));
        cooldown = numberRow(quiz, "Cooldown (minutes)", "Skip the quiz if one was finished this recently. 0 = always.", Prefs.cooldownMin(this));

        // ---- Meanings
        LinearLayout meanings = section(col, "Meanings");
        toggleRow(meanings, "Show meaning when I get it wrong", null, Prefs.MEANING_WRONG, true, null);
        toggleRow(meanings, "Show meaning when I get it right", null, Prefs.MEANING_RIGHT, true, null);
        toggleRow(meanings, "Look up missing meanings on jisho.org",
                "Uses the card's comment from your deck; otherwise looks it up once and saves it.",
                Prefs.MEANING_LOOKUP, true, null);

        // ---- Progress
        LinearLayout progress = section(col, "Progress");
        statsStatus = status(progress);
        toggleRow(progress, "Show how many questions I've answered",
                "A running total in the middle of the quiz's bottom bar.", Prefs.SHOW_ANSWERED, true, null);
        toggleRow(progress, "Prioritise words I struggle with",
                "Every first attempt is tracked per word. Words you miss come up more often until you get them right.",
                Prefs.PRIORITISE_WEAK, true, null);
        toggleRow(progress, "Limit new words per day",
                "Once the limit is reached you only get words you've already seen, until tomorrow.",
                Prefs.NEW_LIMIT_ON, true, on -> newPerDay.setEnabled(on));
        newPerDay = numberRow(progress, "New words per day", null, Prefs.newPerDay(this));
        newPerDay.setEnabled(Prefs.newLimitOn(this));
        progress.addView(secondary("Reset statistics", v -> {
            int dialogTheme = dark ? android.R.style.Theme_DeviceDefault_Dialog_Alert : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;
            new AlertDialog.Builder(this, dialogTheme)
                    .setTitle("Reset statistics?")
                    .setMessage("Your answered count and every word's history will be erased. This can't be undone.")
                    .setPositiveButton("Reset", (d, w) -> { Stats.reset(this); refreshStats(); toast("Statistics reset"); })
                    .setNegativeButton("Cancel", null)
                    .show();
        }), lp(14));

        // ---- Lock screen mode
        LinearLayout lock = section(col, "Lock screen mode");
        toggleRow(lock, "Use the quiz as my lock screen",
                "The quiz is put up whenever the screen turns off, so it's the first thing you see.",
                Prefs.LOCK_MODE, false, on -> refreshLockStatus());
        lockStatus = status(lock);
        lockBtn = secondary("Open Android screen lock settings", v -> {
            try { startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS)); }
            catch (Exception e) { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
        });
        lock.addView(lockBtn, lp(8));
        toggleRow(lock, "Long-press the title to skip", "Escape hatch if a quiz ever gets stuck.", Prefs.ALLOW_SKIP, true, null);
        toggleRow(lock, "Hide the navigation bar", "Home / back / recents. A swipe up from the edge still shows it briefly.",
                Prefs.HIDE_NAV_BAR, true, null);
        TextView warn = text("⚠  Not real security: anyone can get in by typing an answer, and notifications, quick "
                + "settings and the power menu stay reachable. Removing your PIN also turns off fingerprint/face "
                + "unlock and may disable tap-to-pay and some banking or work apps. 「緊急通報」 is always on the quiz in this mode.",
                12.5f, th.sub, false);
        warn.setGravity(Gravity.START);
        warn.setLineSpacing(0, 1.15f);
        lock.addView(warn, lp(14));

        // ---- Appearance
        LinearLayout look = section(col, "Appearance");
        look.addView(rowTitle("Theme"), lp(4));
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipToPadding(false);
        LinearLayout presets = new LinearLayout(this);
        presets.setPadding(0, dp(6), 0, dp(10));
        presets.setClipChildren(false);
        hs.addView(presets);
        int current = currentPreset();
        for (int i = 0; i < QuizTheme.PRESETS.length; i++) presets.addView(presetChip(i, i == current));
        look.addView(hs, lp(4));

        divider(look);
        look.addView(rowTitle("Floating in the background"), lp(12));
        look.addView(segmented(new String[]{"Deck words", "ひらがな"}, Prefs.floatDeckWords(this) ? 0 : 1, idx -> {
            Prefs.get(this).edit().putBoolean(Prefs.FLOAT_DECK_WORDS, idx == 0).apply();
            recreate();
        }), lp(10));

        choiceRow(look, "Unlock animation", "How the quiz leaves the screen when you tap 解放.",
                Prefs.EXIT_ANIMS, Prefs.exitAnim(this),
                idx -> Prefs.get(this).edit().putInt(Prefs.EXIT_ANIM, idx).apply());
        exitAnimMs = numberRow(look, "Animation length (ms)", "50 – 5000. 1000 ms = 1 second.", Prefs.exitAnimMs(this));

        divider(look);
        look.addView(rowTitle("Colours"), lp(12));
        for (int i = 0; i < QuizTheme.SLOTS.length; i++) look.addView(colorRow(i));
        questionSize = numberRow(look, "Word size on the quiz", "20 – 96", Prefs.questionSize(this));
        look.addView(secondary("Reset appearance", v -> {
            QuizTheme.applyPreset(this, 0);
            questionSize.setText("52");
            exitAnimMs.setText("450");
            Prefs.get(this).edit().remove(Prefs.EXIT_ANIM).apply();
            saveFields();
            recreate();
        }), lp(14));

        // ---- Deck
        LinearLayout deck = section(col, "Kotoba deck");
        deckStatus = status(deck);
        deck.addView(rowTitle("Deck link"), lp(12));
        deckUrl = field(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        deckUrl.setText(Prefs.deckUrl(this));
        deckUrl.setHint("https://kotobaweb.com/dashboard/decks/…?secret=…");
        deckUrl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        deck.addView(deckUrl, lp(8));
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setClipChildren(false);
        Button sync = primary("Sync now", v -> { saveFields(); syncNow(); });
        Button login = secondary("Log in with Discord", v -> { saveFields(); startActivity(new Intent(this, LoginActivity.class)); });
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        half2.leftMargin = dp(10);
        btnRow.addView(sync, half);
        btnRow.addView(login, half2);
        deck.addView(btnRow, lp(14));
        syncHours = numberRow(deck, "Auto-sync every (hours)", "Runs in the background when you unlock. 0 = off.", Prefs.autoSyncHours(this));

        TextView foot = text("ことばロック  v" + versionName(), 12, th.sub, false);
        foot.setAlpha(0.6f);
        col.addView(foot, lp(28));

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != 0) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        boolean overlay = Settings.canDrawOverlays(this);
        overlayStatus.set(overlay ? 1 : -1, overlay ? "Can display over other apps" : "Needs \"Display over other apps\" (required)");
        overlayBtn.setVisibility(overlay ? View.GONE : View.VISIBLE);
        PowerManager pm = getSystemService(PowerManager.class);
        boolean battery = pm.isIgnoringBatteryOptimizations(getPackageName());
        batteryStatus.set(battery ? 1 : 0, battery ? "Battery optimisation off" : "Battery optimisation on — Android may stop the quiz");
        batteryBtn.setVisibility(battery ? View.GONE : View.VISIBLE);
        refreshDeckStatus();
        refreshLockStatus();
        refreshHero();
        refreshStats();
        QuizService.start(this);
    }

    private void refreshStats() {
        long total = Stats.total(this);
        int[] s = Stats.summary(this); // seen, right, wrong, struggling
        if (total == 0) { statsStatus.set(0, "No answers yet — your progress will show up here."); return; }
        int pct = Math.round(100f * s[1] / Math.max(1, s[1] + s[2]));
        String limit = Prefs.newLimitOn(this)
                ? "\nNew words today: " + Stats.newToday(this) + " / " + Prefs.newPerDay(this)
                : "\nNew words today: " + Stats.newToday(this);
        statsStatus.set(1, String.format(java.util.Locale.US, "%,d answered · %d%% right on the first try", total, pct)
                + "\n" + s[0] + " words seen · " + s[3] + " you're struggling with" + limit);
    }

    @Override protected void onPause() {
        saveFields();
        super.onPause();
    }

    // ================================================================ logic (unchanged)

    private void saveFields() {
        String url = deckUrl.getText().toString().trim();
        SharedPreferences.Editor e = Prefs.get(this).edit();
        if (Prefs.parseDeckUrl(url) != null) e.putString(Prefs.DECK_URL, url);
        else if (!url.isEmpty()) toast("Deck link doesn't look right — not saved");
        e.putInt(Prefs.QUESTIONS_PER_UNLOCK, Math.max(1, Math.min(20, parse(perUnlock, 1))));
        e.putInt(Prefs.COOLDOWN_MIN, Math.max(0, parse(cooldown, 0)));
        e.putInt(Prefs.AUTO_SYNC_HOURS, Math.max(0, parse(syncHours, 6)));
        e.putInt(Prefs.QUESTION_SIZE, Math.max(20, Math.min(96, parse(questionSize, 52))));
        e.putInt(Prefs.EXIT_ANIM_MS, Math.max(50, Math.min(5000, parse(exitAnimMs, 450))));
        e.putInt(Prefs.NEW_PER_DAY, Math.max(1, Math.min(999, parse(newPerDay, 20))));
        e.apply();
    }

    private void syncNow() {
        deckStatus.set(0, "Syncing…");
        new Thread(() -> {
            Deck.SyncResult r = Deck.sync(getApplicationContext());
            new Handler(Looper.getMainLooper()).post(() -> {
                toast(r.message);
                refreshDeckStatus();
                refreshHero();
                if (r.needsLogin) startActivity(new Intent(this, LoginActivity.class));
            });
        }).start();
    }

    private void refreshDeckStatus() {
        int n = Deck.cards(this).size();
        long last = Prefs.get(this).getLong(Prefs.LAST_SYNC, 0);
        String msg = Prefs.get(this).getString(Prefs.LAST_SYNC_MSG, "");
        String name = Deck.name(this);
        StringBuilder s = new StringBuilder();
        s.append(n == 0 ? "No cards downloaded yet — log in and sync." : n + " cards" + (name.isEmpty() ? "" : " · " + name));
        if (last > 0) s.append("\nLast sync ").append(DateUtils.getRelativeTimeSpanString(last));
        if (!msg.isEmpty() && !msg.startsWith("Synced")) s.append("\n").append(msg);
        deckStatus.set(n == 0 ? -1 : (msg.startsWith("Synced") || msg.isEmpty() ? 1 : 0), s.toString());
    }

    private void refreshLockStatus() {
        KeyguardManager km = getSystemService(KeyguardManager.class);
        boolean secure = km != null && km.isDeviceSecure();
        boolean on = Prefs.lockMode(this);
        if (!on) {
            lockStatus.set(0, "Off — the quiz appears right after your normal unlock.");
            lockBtn.setVisibility(View.GONE);
        } else if (secure) {
            lockStatus.set(-1, "Your phone still has a PIN/pattern, so it comes first. Set Screen lock to None or Swipe to use the quiz instead.");
            lockBtn.setVisibility(View.VISIBLE);
        } else {
            lockStatus.set(1, "On — no Android passcode is set, so the quiz is your lock screen.");
            lockBtn.setVisibility(View.GONE);
        }
    }

    private void refreshHero() {
        boolean active = Prefs.enabled(this) && Settings.canDrawOverlays(this);
        int n = Deck.cards(this).size();
        heroStatus.setText((active ? "●  Active" : "●  Paused") + "   ·   " + n + " words");
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(dp(50));
        int c = active ? th.good : th.bad;
        pill.setColor((c & 0x00FFFFFF) | 0x26000000);
        pill.setStroke(dp(1), (c & 0x00FFFFFF) | 0x66000000);
        heroStatus.setBackground(pill);
        heroStatus.setTextColor(dark ? th.text : mix(c, 0xFF000000, 0.35f));
    }

    private String versionName() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return ""; }
    }

    // ================================================================ themed widgets

    private interface OnToggle { void on(boolean on); }
    private interface OnPick { void on(int index); }

    /** A status line with a coloured dot: state 1 = ok, 0 = neutral/warning, -1 = problem. */
    private final class Status {
        final View dot; final TextView text;
        Status(View d, TextView t) { dot = d; text = t; }
        void set(int state, String s) {
            text.setText(s);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(state > 0 ? th.good : state < 0 ? th.bad : th.sub);
            dot.setBackground(g);
        }
    }

    private Status status(LinearLayout card) {
        if (card.getChildCount() > 0) divider(card);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, dp(4));
        View dot = new View(this);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(10), dp(10));
        dl.rightMargin = dp(12);
        row.addView(dot, dl);
        TextView t = text("", 14.5f, th.text, false);
        t.setGravity(Gravity.START);
        row.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        card.addView(row);
        return new Status(dot, t);
    }

    private LinearLayout section(LinearLayout col, String title) {
        TextView h = text(title.toUpperCase(), 12, th.check, true);
        h.setLetterSpacing(0.18f);
        h.setGravity(Gravity.START);
        h.setPadding(dp(6), 0, 0, 0);
        col.addView(h, lp(30));
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(8), dp(18), dp(18));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{mix(th.card, th.text, 0.035f), th.card});
        bg.setCornerRadius(dp(24));
        bg.setStroke(dp(1), (th.text & 0x00FFFFFF) | 0x12000000);
        card.setBackground(bg);
        shadow(card, 8);
        col.addView(card, lp(10));
        return card;
    }

    private void divider(LinearLayout card) {
        View d = new View(this);
        d.setBackgroundColor((th.text & 0x00FFFFFF) | 0x14000000);
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.8f)));
        l.topMargin = dp(10);
        card.addView(d, l);
    }

    private TextView rowTitle(String s) {
        TextView t = text(s, 16, th.text, true);
        t.setGravity(Gravity.START);
        return t;
    }

    private void toggleRow(LinearLayout card, String title, String desc, String key, boolean def, OnToggle cb) {
        if (card.getChildCount() > 0) divider(card);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, dp(4));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(rowTitle(title));
        if (desc != null) {
            TextView d = text(desc, 13, th.sub, false);
            d.setGravity(Gravity.START);
            texts.addView(d, lp(3));
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Switch sw = new Switch(this);
        int[][] st = {{android.R.attr.state_checked}, {}};
        sw.setThumbTintList(new ColorStateList(st, new int[]{th.check, mix(th.sub, th.text, 0.4f)}));
        sw.setTrackTintList(new ColorStateList(st, new int[]{(th.check & 0x00FFFFFF) | 0x80000000, (th.sub & 0x00FFFFFF) | 0x50000000}));
        sw.setChecked(Prefs.get(this).getBoolean(key, def));
        sw.setOnCheckedChangeListener((v, on) -> {
            Prefs.get(this).edit().putBoolean(key, on).apply();
            if (cb != null) cb.on(on);
        });
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sl.leftMargin = dp(12);
        row.addView(sw, sl);
        row.setOnClickListener(v -> sw.toggle());
        card.addView(row);
    }

    /** A row showing the current choice; tapping it opens a single-choice list. */
    private void choiceRow(LinearLayout card, String title, String desc, String[] options, int selected, OnPick cb) {
        if (card.getChildCount() > 0) divider(card);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, dp(4));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(rowTitle(title));
        if (desc != null) {
            TextView d = text(desc, 13, th.sub, false);
            d.setGravity(Gravity.START);
            texts.addView(d, lp(3));
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView value = text(options[selected] + "  ›", 15, th.check, true);
        LinearLayout.LayoutParams vl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        vl.leftMargin = dp(12);
        row.addView(value, vl);
        final int[] cur = {selected};
        row.setOnClickListener(v -> {
            int dialogTheme = dark ? android.R.style.Theme_DeviceDefault_Dialog_Alert : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;
            new AlertDialog.Builder(this, dialogTheme)
                    .setTitle(title)
                    .setSingleChoiceItems(options, cur[0], (d, which) -> {
                        cur[0] = which;
                        value.setText(options[which] + "  ›");
                        cb.on(which);
                        d.dismiss();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });
        card.addView(row);
    }

    private EditText numberRow(LinearLayout card, String title, String desc, int value) {
        if (card.getChildCount() > 0) divider(card);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, dp(4));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(rowTitle(title));
        if (desc != null) {
            TextView d = text(desc, 13, th.sub, false);
            d.setGravity(Gravity.START);
            texts.addView(d, lp(3));
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        EditText e = field(InputType.TYPE_CLASS_NUMBER);
        e.setText(String.valueOf(value));
        e.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams el = new LinearLayout.LayoutParams(dp(76), LinearLayout.LayoutParams.WRAP_CONTENT);
        el.leftMargin = dp(12);
        row.addView(e, el);
        card.addView(row);
        return e;
    }

    private EditText field(int inputType) {
        EditText e = new EditText(this);
        e.setInputType(inputType);
        e.setSingleLine(true);
        e.setTextColor(th.text);
        e.setHintTextColor(th.hint());
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        GradientDrawable n = new GradientDrawable();
        n.setColor(mix(th.card, th.bg, 0.5f));
        n.setCornerRadius(dp(14));
        n.setStroke(dp(1.5f), (th.sub & 0x00FFFFFF) | 0x40000000);
        GradientDrawable f = new GradientDrawable();
        f.setColor(mix(th.card, th.bg, 0.5f));
        f.setCornerRadius(dp(14));
        f.setStroke(dp(2), th.check);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, f);
        s.addState(new int[]{}, n);
        e.setBackground(s);
        e.setPadding(dp(14), dp(11), dp(14), dp(11));
        return e;
    }

    private Button primary(String label, View.OnClickListener l) {
        Button b = baseButton(label, l);
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{mix(th.check, 0xFFFFFFFF, 0.14f), th.check});
        d.setCornerRadius(dp(16));
        b.setBackground(d);
        b.setTextColor(th.btnText);
        shadow(b, 6);
        return b;
    }

    private Button secondary(String label, View.OnClickListener l) {
        Button b = baseButton(label, l);
        GradientDrawable d = new GradientDrawable();
        d.setColor(mix(th.card, th.text, 0.07f));
        d.setCornerRadius(dp(16));
        d.setStroke(dp(1), (th.text & 0x00FFFFFF) | 0x1C000000);
        b.setBackground(d);
        b.setTextColor(th.text);
        shadow(b, 2);
        return b;
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private Button baseButton(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setStateListAnimator(null);
        b.setPadding(dp(12), dp(13), dp(12), dp(13));
        b.setOnClickListener(l);
        b.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(90).start();
            else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL)
                v.animate().scaleX(1f).scaleY(1f).setDuration(140).start();
            return false;
        });
        return b;
    }

    /** Pill-shaped two-option switcher. */
    private View segmented(String[] options, int selected, OnPick cb) {
        LinearLayout seg = new LinearLayout(this);
        seg.setPadding(dp(4), dp(4), dp(4), dp(4));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(mix(th.card, th.bg, 0.6f));
        bg.setCornerRadius(dp(50));
        seg.setBackground(bg);
        for (int i = 0; i < options.length; i++) {
            final int idx = i;
            TextView t = text(options[i], 14.5f, i == selected ? th.btnText : th.sub, true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(10), 0, dp(10));
            if (i == selected) {
                GradientDrawable on = new GradientDrawable();
                on.setColor(th.check);
                on.setCornerRadius(dp(50));
                t.setBackground(on);
                shadow(t, 3);
            } else {
                t.setOnClickListener(v -> cb.on(idx));
            }
            seg.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        }
        return seg;
    }

    /** Mini preview of a preset: its background, card and two button colours. */
    private View presetChip(int idx, boolean selected) {
        Object[] p = QuizTheme.PRESETS[idx];
        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.VERTICAL);
        chip.setGravity(Gravity.CENTER_HORIZONTAL);
        chip.setClipChildren(false);

        FrameLayout prev = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor((Integer) p[1]);
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(selected ? 3 : 1), selected ? th.check : (th.text & 0x00FFFFFF) | 0x22000000);
        prev.setBackground(bg);
        shadow(prev, selected ? 6 : 3);
        View card = new View(this);
        GradientDrawable cd = new GradientDrawable();
        cd.setColor((Integer) p[2]);
        cd.setCornerRadius(dp(7));
        card.setBackground(cd);
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(dp(46), dp(22), Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        cl.topMargin = dp(10);
        prev.addView(card, cl);
        LinearLayout dots = new LinearLayout(this);
        for (int k : new int[]{5, 6}) {
            View d = new View(this);
            GradientDrawable dd = new GradientDrawable();
            dd.setColor((Integer) p[k]);
            dd.setCornerRadius(dp(5));
            d.setBackground(dd);
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(dp(20), dp(10));
            l.setMargins(dp(3), 0, dp(3), 0);
            dots.addView(d, l);
        }
        FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        dl.bottomMargin = dp(10);
        prev.addView(dots, dl);
        chip.addView(prev, new LinearLayout.LayoutParams(dp(72), dp(72)));

        TextView name = text((String) p[0], 12.5f, selected ? th.text : th.sub, selected);
        chip.addView(name, lp(6));
        LinearLayout.LayoutParams outer = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        outer.rightMargin = dp(12);
        chip.setLayoutParams(outer);
        chip.setOnClickListener(v -> { saveFields(); QuizTheme.applyPreset(this, idx); recreate(); });
        return chip;
    }

    private int currentPreset() {
        outer:
        for (int i = 0; i < QuizTheme.PRESETS.length; i++) {
            for (int s = 0; s < QuizTheme.SLOTS.length; s++)
                if (QuizTheme.color(this, s) != (Integer) QuizTheme.PRESETS[i][s + 1]) continue outer;
            return i;
        }
        return -1;
    }

    private View colorRow(int slot) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));
        TextView t = text(QuizTheme.SLOTS[slot][1], 15, th.text, false);
        t.setGravity(Gravity.START);
        row.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        View sw = new View(this);
        paintSwatch(sw, QuizTheme.color(this, slot), true);
        shadow(sw, 2);
        row.addView(sw, new LinearLayout.LayoutParams(dp(30), dp(30)));
        row.setOnClickListener(v -> pickColor(slot));
        return row;
    }

    private void paintSwatch(View v, int color, boolean round) {
        GradientDrawable d = new GradientDrawable();
        if (round) d.setShape(GradientDrawable.OVAL); else d.setCornerRadius(dp(10));
        d.setColor(color);
        d.setStroke(dp(1), 0x55888888);
        v.setBackground(d);
    }

    private void pickColor(int slot) {
        final int[] chosen = {QuizTheme.color(this, slot)};
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        View preview = new View(this);
        paintSwatch(preview, chosen[0], false);
        box.addView(preview, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)));
        EditText hex = new EditText(this);
        hex.setHint("#RRGGBB");
        hex.setSingleLine(true);
        hex.setText(String.format("#%06X", chosen[0] & 0xFFFFFF));
        LinearLayout row = null;
        for (int i = 0; i < QuizTheme.SWATCHES.length; i++) {
            if (i % 6 == 0) { row = new LinearLayout(this); box.addView(row, lp(6)); }
            int c = QuizTheme.SWATCHES[i];
            View s = new View(this);
            paintSwatch(s, c, true);
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(0, dp(38), 1);
            l.setMargins(dp(4), 0, dp(4), 0);
            s.setOnClickListener(v -> {
                chosen[0] = c;
                paintSwatch(preview, c, false);
                hex.setText(String.format("#%06X", c & 0xFFFFFF));
            });
            row.addView(s, l);
        }
        hex.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(android.text.Editable s) {
                Integer c = parseColor(s.toString());
                if (c != null) { chosen[0] = c; paintSwatch(preview, c, false); }
            }
        });
        box.addView(hex, lp(10));
        int dialogTheme = dark ? android.R.style.Theme_DeviceDefault_Dialog_Alert : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;
        new AlertDialog.Builder(this, dialogTheme)
                .setTitle(QuizTheme.SLOTS[slot][1])
                .setView(box)
                .setPositiveButton("OK", (d, w) -> {
                    saveFields();
                    Prefs.get(this).edit().putInt(QuizTheme.SLOTS[slot][0], chosen[0]).apply();
                    recreate();
                })
                .setNeutralButton("Default", (d, w) -> {
                    saveFields();
                    Prefs.get(this).edit().remove(QuizTheme.SLOTS[slot][0]).apply();
                    recreate();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ================================================================ small helpers

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private void shadow(View v, float elevationDp) {
        v.setElevation(dp(elevationDp));
        v.setOutlineAmbientShadowColor(0xFF000000);
        v.setOutlineSpotShadowColor(0xFF000000);
    }

    private LinearLayout.LayoutParams lp(int topMargin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(topMargin);
        return p;
    }

    private static FrameLayout.LayoutParams matchParent() {
        return new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
    }

    private static int mix(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return 0xFF000000 | ((int) (ar + (br - ar) * t) << 16) | ((int) (ag + (bg - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
    }

    private static float luminance(int c) {
        return (0.299f * ((c >> 16) & 0xFF) + 0.587f * ((c >> 8) & 0xFF) + 0.114f * (c & 0xFF)) / 255f;
    }

    private static Integer parseColor(String s) {
        s = s.trim();
        if (!s.startsWith("#")) s = "#" + s;
        if (s.length() != 7) return null;
        try { return 0xFF000000 | Integer.parseInt(s.substring(1), 16); } catch (Exception e) { return null; }
    }

    private int dp(float v) { return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
    private static int parse(EditText e, int def) { try { return Integer.parseInt(e.getText().toString().trim()); } catch (Exception x) { return def; } }
}
