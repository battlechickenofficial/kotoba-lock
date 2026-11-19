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

/** Settings, split into tabs, styled with the same theme as the quiz screen. */
public class MainActivity extends Activity {
    private QuizTheme th;
    private boolean dark;

    private Status overlayStatus, batteryStatus, lockStatus, deckStatus;
    private Button overlayBtn, batteryBtn, lockBtn;
    private EditText perUnlock, cooldown, syncHours, questionSize, exitAnimMs, newPerDay;
    private TextView heroStatus;

    /** Tab shown when the screen is (re)created, so theme changes don't jump back to Home. */
    private static int currentTab = 0;
    private static final String[] TABS = {"Home", "Decks", "Quiz", "Progress", "Look", "Lock"};
    private static final int TAB_DECKS = 1, TAB_PROGRESS = 3;
    private final java.util.List<View> pages = new java.util.ArrayList<>();
    private final java.util.List<TextView> tabViews = new java.util.ArrayList<>();
    private ScrollView scroll;
    private LinearLayout deckList, chartsBox;

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
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        root.addView(shell, matchParent());

        // ---- Tab bar, pinned at the top
        HorizontalScrollView tabScroll = new HorizontalScrollView(this);
        tabScroll.setHorizontalScrollBarEnabled(false);
        tabScroll.setClipToPadding(false);
        LinearLayout tabBar = new LinearLayout(this);
        tabBar.setPadding(dp(14), dp(10), dp(14), dp(10));
        tabBar.setClipChildren(false);
        tabScroll.addView(tabBar);
        for (int i = 0; i < TABS.length; i++) {
            final int idx = i;
            TextView t = text(TABS[i], 14.5f, th.sub, true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(16), dp(9), dp(16), dp(9));
            t.setOnClickListener(v -> selectTab(idx));
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            tl.rightMargin = dp(8);
            tabBar.addView(t, tl);
            tabViews.add(t);
        }
        shell.addView(tabScroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        shell.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        LinearLayout holder = new LinearLayout(this);
        holder.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(holder);
        setContentView(root);
        root.setOnApplyWindowInsetsListener((v, ins) -> {
            int top, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets s = ins.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                top = s.top; bottom = Math.max(s.bottom, ins.getInsets(WindowInsets.Type.ime()).bottom);
            } else {
                top = ins.getSystemWindowInsetTop(); bottom = ins.getSystemWindowInsetBottom();
            }
            shell.setPadding(0, top, 0, 0);
            scroll.setPadding(0, 0, 0, bottom);
            return WindowInsets.CONSUMED;
        });

        buildHome(page(holder));
        buildDecks(page(holder));
        buildQuiz(page(holder));
        buildProgress(page(holder));
        buildLook(page(holder));
        buildLock(page(holder));
        selectTab(currentTab);

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != 0) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    private LinearLayout page(LinearLayout holder) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(18), dp(0), dp(18), dp(40));
        col.setClipChildren(false);
        col.setClipToPadding(false);
        holder.addView(col, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        pages.add(col);
        return col;
    }

    private void selectTab(int i) {
        currentTab = i;
        for (int k = 0; k < pages.size(); k++) pages.get(k).setVisibility(k == i ? View.VISIBLE : View.GONE);
        for (int k = 0; k < tabViews.size(); k++) {
            TextView t = tabViews.get(k);
            GradientDrawable d = new GradientDrawable();
            d.setCornerRadius(dp(50));
            if (k == i) {
                d.setColor(th.check);
                t.setTextColor(th.btnText);
            } else {
                d.setColor((th.card & 0x00FFFFFF) | 0xB3000000);
                d.setStroke(dp(1), (th.text & 0x00FFFFFF) | 0x14000000);
                t.setTextColor(th.sub);
            }
            t.setBackground(d);
        }
        scroll.scrollTo(0, 0);
        if (i == TAB_PROGRESS) refreshProgress();
        if (i == TAB_DECKS) refreshDecks();
    }

    // ================================================================ tabs

    private void buildHome(LinearLayout col) {
        TextView title = text("ことばロック", 34, th.text, true);
        title.setLetterSpacing(0.04f);
        col.addView(title, lp(8));
        TextView sub = text("Kotoba Lock", 14, th.sub, false);
        sub.setLetterSpacing(0.08f);
        col.addView(sub, lp(2));
        heroStatus = text("", 14, th.text, true);
        heroStatus.setPadding(dp(14), dp(7), dp(14), dp(7));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hp.topMargin = dp(14);
        col.addView(heroStatus, hp);
        Button study = primary("学習する  ·  Study", v -> {
            saveFields();
            if (Deck.cards(this).isEmpty()) { toast("Add a deck and sync it first"); selectTab(TAB_DECKS); return; }
            startActivity(new Intent(this, StudyActivity.class));
        });
        study.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        study.setPadding(dp(12), dp(17), dp(12), dp(17));
        col.addView(study, lp(18));
        TextView hint = text("Quizzes you on your enabled decks for as long as you like. Answers count toward your progress.",
                13, th.sub, false);
        hint.setPadding(dp(6), 0, dp(6), 0);
        col.addView(hint, lp(8));

        LinearLayout setup = section(col, "Setup");
        overlayStatus = status(setup);
        overlayBtn = secondary("Allow \"Display over other apps\"", v -> startActivity(new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))));
        setup.addView(overlayBtn, lp(8));
        batteryStatus = status(setup);
        batteryBtn = secondary("Disable battery optimisation", v -> startActivity(new Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()))));
        setup.addView(batteryBtn, lp(8));

        TextView foot = text("ことばロック  v" + versionName(), 12, th.sub, false);
        foot.setAlpha(0.6f);
        col.addView(foot, lp(28));
    }

    private void buildDecks(LinearLayout col) {
        LinearLayout list = section(col, "Your decks");
        deckStatus = status(list);
        deckList = new LinearLayout(this);
        deckList.setOrientation(LinearLayout.VERTICAL);
        list.addView(deckList);

        LinearLayout add = section(col, "Add a deck");
        TextView how = text("Paste a deck link from kotobaweb.com (include ?secret=… for private decks).", 13, th.sub, false);
        add.addView(how, lp(10));
        EditText url = field(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        url.setHint("https://kotobaweb.com/dashboard/decks/…?secret=…");
        url.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        add.addView(url, lp(10));
        add.addView(primary("Add deck", v -> {
            String err = Deck.add(this, url.getText().toString());
            if (err != null) { toast(err); return; }
            url.setText("");
            java.util.List<Deck.Info> ds = Deck.decks(this);
            String id = ds.get(ds.size() - 1).id;
            refreshDecks();
            deckStatus.set(0, "Downloading the new deck…");
            new Thread(() -> {
                Deck.SyncResult r = Deck.syncDeck(getApplicationContext(), id);
                new Handler(Looper.getMainLooper()).post(() -> {
                    toast(r.message);
                    refreshDecks();
                    refreshHero();
                    if (r.needsLogin) startActivity(new Intent(this, LoginActivity.class));
                });
            }).start();
        }), lp(12));

        LinearLayout sync = section(col, "Sync");
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setClipChildren(false);
        Button syncBtn = primary("Sync all", v -> { saveFields(); syncNow(); });
        Button login = secondary("Log in with Discord", v -> { saveFields(); startActivity(new Intent(this, LoginActivity.class)); });
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        half2.leftMargin = dp(10);
        btnRow.addView(syncBtn, half);
        btnRow.addView(login, half2);
        sync.addView(btnRow, lp(14));
        syncHours = numberRow(sync, "Auto-sync every (hours)", "Runs in the background when you unlock. 0 = off.", Prefs.autoSyncHours(this));
    }

    private void buildQuiz(LinearLayout col) {
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

        LinearLayout pick = section(col, "Which words you get");
        toggleRow(pick, "Prioritise words I struggle with",
                "Every first attempt is tracked per word. Words you miss come up more often until you get them right.",
                Prefs.PRIORITISE_WEAK, true, null);
        toggleRow(pick, "Limit new words per day",
                "Once the limit is reached you only get words you've already seen, until tomorrow. Applies to study mode too.",
                Prefs.NEW_LIMIT_ON, true, on -> newPerDay.setEnabled(on));
        newPerDay = numberRow(pick, "New words per day", null, Prefs.newPerDay(this));
        newPerDay.setEnabled(Prefs.newLimitOn(this));

        LinearLayout meanings = section(col, "Meanings");
        toggleRow(meanings, "Show meaning when I get it wrong", null, Prefs.MEANING_WRONG, true, null);
        toggleRow(meanings, "Show meaning when I get it right", null, Prefs.MEANING_RIGHT, true, null);
        toggleRow(meanings, "Look up missing meanings on jisho.org",
                "Uses the card's comment from your deck; otherwise looks it up once and saves it.",
                Prefs.MEANING_LOOKUP, true, null);
    }

    private void buildProgress(LinearLayout col) {
        chartsBox = new LinearLayout(this);
        chartsBox.setOrientation(LinearLayout.VERTICAL);
        chartsBox.setClipChildren(false);
        chartsBox.setClipToPadding(false);
        col.addView(chartsBox, lp(0));

        LinearLayout opts = section(col, "Counter & data");
        toggleRow(opts, "Show how many questions I've answered",
                "A running total in the middle of the quiz's bottom bar.", Prefs.SHOW_ANSWERED, true, null);
        opts.addView(secondary("Reset statistics", v -> {
            new AlertDialog.Builder(this, dialogTheme())
                    .setTitle("Reset statistics?")
                    .setMessage("Your answered count, every word's history and these graphs will be erased. This can't be undone.")
                    .setPositiveButton("Reset", (d, w) -> { Stats.reset(this); refreshProgress(); toast("Statistics reset"); })
                    .setNegativeButton("Cancel", null)
                    .show();
        }), lp(14));
    }

    private void buildLook(LinearLayout col) {
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
    }

    private void buildLock(LinearLayout col) {
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
        lock.addView(secondary("Preview the unlock quiz", v -> {
            saveFields();
            if (!Settings.canDrawOverlays(this)) { toast("Allow \"Display over other apps\" first"); return; }
            if (Deck.cards(this).isEmpty()) { toast("Add a deck and sync it first"); return; }
            QuizService.showNow(this);
        }), lp(14));
        TextView warn = text("⚠  Not real security: anyone can get in by typing an answer, and notifications, quick "
                + "settings and the power menu stay reachable. Removing your PIN also turns off fingerprint/face "
                + "unlock and may disable tap-to-pay and some banking or work apps. 「緊急通報」 is always on the quiz in this mode.",
                12.5f, th.sub, false);
        warn.setGravity(Gravity.START);
        warn.setLineSpacing(0, 1.15f);
        lock.addView(warn, lp(14));
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
        refreshDecks();
        refreshLockStatus();
        refreshHero();
        if (currentTab == TAB_PROGRESS) refreshProgress();
        QuizService.start(this);
    }

    @Override protected void onPause() {
        saveFields();
        super.onPause();
    }

    // ================================================================ logic

    private void saveFields() {
        SharedPreferences.Editor e = Prefs.get(this).edit();
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
                refreshDecks();
                refreshHero();
                if (r.needsLogin) startActivity(new Intent(this, LoginActivity.class));
            });
        }).start();
    }

    /** {struggling, learning, known, new} for a list of cards. */
    private static int[] levelCounts(java.util.List<Deck.Card> cards, java.util.Map<String, Stats.Word> words) {
        int[] n = new int[4];
        for (Deck.Card k : cards) {
            Stats.Word w = words.get(k.question);
            n[w == null ? 3 : Charts.level(w)]++;
        }
        return n;
    }

    /** Rebuilds the deck list (names, switches, coverage) and the sync status line. */
    private void refreshDecks() {
        if (deckList == null) return;
        deckList.removeAllViews();
        java.util.List<Deck.Info> ds = Deck.decks(this);
        java.util.Map<String, Stats.Word> words = Stats.words(this);
        int pool = Deck.cards(this).size(), enabledTotal = 0, enabled = 0;
        for (Deck.Info d : ds) {
            int size = Deck.cardsOf(this, d.id).size();
            if (d.enabled) { enabledTotal += size; enabled++; }
            divider(deckList);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, dp(12), 0, dp(4));
            LinearLayout top = new LinearLayout(this);
            top.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.addView(rowTitle(d.label()));
            int[] lv = levelCounts(Deck.cardsOf(this, d.id), words);
            int seen = size - lv[3];
            TextView info = text(size == 0 ? "Not downloaded yet — tap Sync all"
                    : String.format(java.util.Locale.US, "%,d cards · %d%% seen", size, Math.round(100f * seen / size)), 13, th.sub, false);
            texts.addView(info, lp(3));
            top.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            Switch sw = themedSwitch();
            sw.setChecked(d.enabled);
            sw.setOnCheckedChangeListener((v, on) -> { Deck.setEnabled(this, d.id, on); refreshDecks(); refreshHero(); });
            top.addView(sw);
            row.addView(top);
            if (size > 0) {
                Charts.LevelBar bar = new Charts.LevelBar(this, th, lv, 8);
                bar.setAlpha(d.enabled ? 1f : 0.45f);
                row.addView(bar, lp(10));
            }
            TextView remove = text("Remove", 13, th.bad, true);
            remove.setPadding(0, dp(8), 0, dp(4));
            remove.setOnClickListener(v -> new AlertDialog.Builder(this, dialogTheme())
                    .setTitle("Remove \"" + d.label() + "\"?")
                    .setMessage("The deck is removed from the app. Your progress on its words is kept.")
                    .setPositiveButton("Remove", (dlg, w) -> { Deck.remove(this, d.id); refreshDecks(); refreshHero(); })
                    .setNegativeButton("Cancel", null)
                    .show());
            row.addView(remove, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            deckList.addView(row);
        }

        long last = Prefs.get(this).getLong(Prefs.LAST_SYNC, 0);
        String msg = Prefs.get(this).getString(Prefs.LAST_SYNC_MSG, "");
        StringBuilder s = new StringBuilder();
        if (ds.isEmpty()) s.append("No decks yet — add one below.");
        else {
            s.append(String.format(java.util.Locale.US, "%,d cards in the question pool from %d of %d decks", pool, enabled, ds.size()));
            int overlap = enabledTotal - pool;
            if (overlap > 0) s.append(String.format(java.util.Locale.US, "\n%,d shared between decks, counted once", overlap));
        }
        if (last > 0) s.append("\nLast sync ").append(DateUtils.getRelativeTimeSpanString(last));
        if (!msg.isEmpty() && !msg.startsWith("Synced")) s.append("\n").append(msg);
        deckStatus.set(pool == 0 ? -1 : (msg.startsWith("Synced") || msg.isEmpty() ? 1 : 0), s.toString());
    }

    // ================================================================ progress tab

    private void refreshProgress() {
        if (chartsBox == null) return;
        chartsBox.removeAllViews();
        java.util.Map<String, Stats.Word> words = Stats.words(this);
        long total = Stats.total(this);
        int[] sum = Stats.summary(this); // seen, right, wrong, struggling

        // ---- Headline tiles
        LinearLayout tiles = new LinearLayout(this);
        tiles.setOrientation(LinearLayout.VERTICAL);
        tiles.setClipChildren(false);
        int acc = sum[1] + sum[2] == 0 ? 0 : Math.round(100f * sum[1] / (sum[1] + sum[2]));
        int streak = Stats.streak(this);
        tileRow(tiles, String.format(java.util.Locale.US, "%,d", total), "answered",
                String.valueOf(streak), streak == 1 ? "day streak" : "days streak");
        tileRow(tiles, sum[1] + sum[2] == 0 ? "–" : acc + "%", "right first try",
                String.format(java.util.Locale.US, "%,d", sum[0]), "words seen");
        chartsBox.addView(tiles, lp(4));
        TextView today = text("New words today: " + Stats.newToday(this)
                + (Prefs.newLimitOn(this) ? " / " + Prefs.newPerDay(this) : " (no limit)"), 13, th.sub, false);
        today.setPadding(dp(6), 0, 0, 0);
        chartsBox.addView(today, lp(10));

        // ---- Last 30 days
        int days = 30;
        int[] right = new int[days], wrong = new int[days], cum = new int[days];
        String[] labels = new String[days];
        java.text.SimpleDateFormat key = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US);
        java.text.SimpleDateFormat lab = new java.text.SimpleDateFormat("M/d", java.util.Locale.US);
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0); cal.set(java.util.Calendar.MINUTE, 0);
        cal.set(java.util.Calendar.SECOND, 0); cal.set(java.util.Calendar.MILLISECOND, 0);
        cal.add(java.util.Calendar.DAY_OF_YEAR, -(days - 1));
        long[] firsts = new long[words.size()];
        int fi = 0;
        for (Stats.Word w : words.values()) firsts[fi++] = w.first();
        java.util.Arrays.sort(firsts);
        for (int i = 0; i < days; i++) {
            Stats.Day d = Stats.day(this, key.format(cal.getTime()));
            right[i] = d == null ? 0 : d.right;
            wrong[i] = d == null ? 0 : d.answered - d.right;
            labels[i] = lab.format(cal.getTime());
            cal.add(java.util.Calendar.DAY_OF_YEAR, 1);
            long end = cal.getTimeInMillis();
            int n = 0;
            while (n < firsts.length && firsts[n] < end) n++;
            cum[i] = n;
        }

        LinearLayout daily = section(chartsBox, "Answers per day");
        daily.addView(caption("Last 30 days · tap a bar for details"), lp(10));
        daily.addView(new Charts.DailyBars(this, th, right, wrong, labels), lp(10));
        LinearLayout legend = new LinearLayout(this);
        legend.addView(legendItem(th.good, "Right"));
        legend.addView(legendItem(th.bad, "Wrong"));
        daily.addView(legend, lp(6));

        LinearLayout seenCard = section(chartsBox, "Words seen");
        seenCard.addView(caption("Distinct words you've answered, last 30 days"), lp(10));
        seenCard.addView(new Charts.CumulativeLine(this, th, cum, labels), lp(10));

        // ---- Knowledge levels across the question pool
        java.util.List<Deck.Card> poolCards = Deck.cards(this);
        int[] lv = levelCounts(poolCards, words);
        LinearLayout know = section(chartsBox, "How well you know your words");
        know.addView(caption(String.format(java.util.Locale.US, "Across your enabled decks · %,d words", poolCards.size())), lp(10));
        know.addView(new Charts.LevelBar(this, th, lv, 16), lp(12));
        String[] desc = {"you miss these more often than not", "getting there", "you get these right", "not answered yet"};
        for (int i = 0; i < 4; i++) {
            LinearLayout r = new LinearLayout(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(0, dp(8), 0, 0);
            r.addView(dot(Charts.levelColor(th, i)));
            LinearLayout tx = new LinearLayout(this);
            tx.setOrientation(LinearLayout.VERTICAL);
            tx.addView(text(Charts.LEVELS[i], 15, th.text, true));
            tx.addView(text(desc[i], 12.5f, th.sub, false));
            r.addView(tx, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            int pct = poolCards.isEmpty() ? 0 : Math.round(100f * lv[i] / poolCards.size());
            TextView val = text(String.format(java.util.Locale.US, "%,d  ·  %d%%", lv[i], pct), 14, th.text, true);
            r.addView(val);
            know.addView(r);
        }

        // ---- Coverage per deck
        java.util.List<Deck.Info> ds = Deck.decks(this);
        if (!ds.isEmpty()) {
            LinearLayout cover = section(chartsBox, "Deck coverage");
            cover.addView(caption("How much of each deck you've seen, split by how well you know it"), lp(10));
            for (Deck.Info d : ds) {
                java.util.List<Deck.Card> cards = Deck.cardsOf(this, d.id);
                int[] c = levelCounts(cards, words);
                int seen = cards.size() - c[3];
                LinearLayout r = new LinearLayout(this);
                r.setGravity(Gravity.CENTER_VERTICAL);
                r.setPadding(0, dp(12), 0, dp(6));
                TextView name = text(d.label() + (d.enabled ? "" : "  (off)"), 15, th.text, true);
                r.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
                r.addView(text(cards.isEmpty() ? "–" : String.format(java.util.Locale.US, "%,d / %,d  ·  %d%%",
                        seen, cards.size(), Math.round(100f * seen / cards.size())), 13.5f, th.sub, true));
                cover.addView(r);
                cover.addView(new Charts.LevelBar(this, th, c, 10), lp(2));
            }
        }
    }

    private TextView caption(String s) {
        TextView t = text(s, 13, th.sub, false);
        t.setGravity(Gravity.START);
        return t;
    }

    private View dot(int color) {
        View v = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(3));
        g.setColor(color);
        v.setBackground(g);
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(dp(12), dp(12));
        l.rightMargin = dp(12);
        v.setLayoutParams(l);
        return v;
    }

    private View legendItem(int color, String label) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, 0, dp(18), 0);
        View d = dot(color);
        ((LinearLayout.LayoutParams) d.getLayoutParams()).rightMargin = dp(6);
        r.addView(d);
        r.addView(text(label, 13, th.sub, false));
        return r;
    }

    private void tileRow(LinearLayout parent, String v1, String l1, String v2, String l2) {
        LinearLayout row = new LinearLayout(this);
        row.setClipChildren(false);
        LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        LinearLayout.LayoutParams b = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        b.leftMargin = dp(10);
        row.addView(tile(v1, l1), a);
        row.addView(tile(v2, l2), b);
        parent.addView(row, lp(10));
    }

    private View tile(String value, String label) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{mix(th.card, th.text, 0.035f), th.card});
        bg.setCornerRadius(dp(20));
        bg.setStroke(dp(1), (th.text & 0x00FFFFFF) | 0x12000000);
        t.setBackground(bg);
        shadow(t, 6);
        TextView v = text(value, 28, th.text, true);
        t.addView(v);
        t.addView(text(label, 13, th.sub, false));
        return t;
    }

    private int dialogTheme() {
        return dark ? android.R.style.Theme_DeviceDefault_Dialog_Alert : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;
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
        Switch sw = themedSwitch();
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

    private Switch themedSwitch() {
        Switch sw = new Switch(this);
        int[][] st = {{android.R.attr.state_checked}, {}};
        sw.setThumbTintList(new ColorStateList(st, new int[]{th.check, mix(th.sub, th.text, 0.4f)}));
        sw.setTrackTintList(new ColorStateList(st, new int[]{(th.check & 0x00FFFFFF) | 0x80000000, (th.sub & 0x00FFFFFF) | 0x50000000}));
        return sw;
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
