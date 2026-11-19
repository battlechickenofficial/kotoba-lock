package com.kotobalock;

import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.LocaleList;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/**
 * The quiz screen. Normally a full-screen window drawn over every app that stays until the quiz
 * is finished; in study mode the same screen is hosted by {@link StudyActivity} instead, with an
 * exit button and no limit on questions.
 */
public class QuizOverlay {
    private final Context ctx;
    private final WindowManager wm;
    /** Non-null in study mode. */
    private final android.app.Activity studyHost;
    private final boolean study;
    private View root;
    private QuizTheme th;

    private Deck.Card card;
    private int index, total;
    private boolean revealed, lastCorrect;
    private String meaning = "";

    private TextView progress, instructions, question, romajiPreview, result, answer, meaningView;
    private EditText input;
    private Button submit, next;
    private ScrollView scroll;
    private LinearLayout box;

    /** Screen turned off after you already answered: swap in a fresh question for next time. */
    public void onScreenOff() {
        if (exiting) { dismiss(); return; }
        if (root == null || !revealed) return;
        index--;           // same position in the "1 / N" count
        nextCard();
    }

    public QuizOverlay(Context c) {
        ctx = c;
        wm = c.getSystemService(WindowManager.class);
        studyHost = null;
        study = false;
    }

    /** Study mode: the quiz is the content of {@code host}; leaving it finishes the activity. */
    public QuizOverlay(android.app.Activity host) {
        ctx = host;
        wm = null;
        studyHost = host;
        study = true;
    }

    /** Study mode: builds the screen for the activity and shows the first question. */
    public View startStudy() {
        th = QuizTheme.load(ctx);
        total = Integer.MAX_VALUE;
        index = 0;
        root = build();
        nextCard();
        return root;
    }

    /** Back button / gesture. Returns true if it was handled here (closed the sentences panel). */
    public boolean onBack() {
        if (sentencesOpen) { closeSentences(); return true; }
        return false;
    }

    /** False while the exit animation is playing: the quiz is already done at that point. */
    public boolean isShowing() { return root != null && !exiting; }

    public void show() {
        if (exiting) dismiss(); // a new quiz is wanted while the last one is still animating out
        if (root != null) return;
        th = QuizTheme.load(ctx);
        total = Prefs.questionsPerUnlock(ctx);
        index = 0;
        root = build();
        // TRANSLUCENT (not OPAQUE) so the app underneath shows through while the exit animation plays.
        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        // ADJUST_NOTHING: the window is never resized/pushed up by the keyboard (it draws over the bottom bar).
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING | WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE;
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        lp.gravity = Gravity.TOP;
        try {
            wm.addView(root, lp);
        } catch (Exception e) {
            root = null;
            return;
        }
        if (Prefs.hideNavBar(ctx)) hideNavBar();
        nextCard();
    }

    /** Hide the home/back/recents bar while the quiz is up. A swipe from the bottom edge shows it briefly. */
    @SuppressWarnings("deprecation")
    private void hideNavBar() {
        final View r = root;
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            Runnable hide = () -> {
                android.view.WindowInsetsController c = r.getWindowInsetsController();
                if (c == null) return;
                c.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                c.hide(android.view.WindowInsets.Type.navigationBars());
            };
            r.post(hide);
            // Some phones bring the bar back with the keyboard; hide it again when focus returns.
            r.getViewTreeObserver().addOnWindowFocusChangeListener(focused -> { if (focused && root == r) r.post(hide); });
        } else {
            r.setSystemUiVisibility(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    public void dismiss() {
        exiting = false;
        if (root == null) return;
        closeSentences();
        if (study) {
            hideKeyboard();
            root = null;
            studyHost.finish();
            return;
        }
        InputMethodManager imm = ctx.getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(root.getWindowToken(), 0);
        try { wm.removeView(root); } catch (Exception ignored) {}
        torchButton = null; // the flashlight stays as it is, like on the normal lock screen
        root = null;
    }

    private void finish() {
        if (study) { dismiss(); return; }
        Prefs.get(ctx).edit().putLong(Prefs.LAST_QUIZ_DONE, System.currentTimeMillis()).apply();
        animateOut();
    }

    // ---------------------------------------------------------------- exit animation

    private boolean exiting;
    private WindowManager.LayoutParams lp;

    /** Plays the chosen exit animation, then removes the overlay. Touches pass through meanwhile. */
    private void animateOut() {
        if (root == null || exiting) return;
        int style = Prefs.exitAnim(ctx);
        if (style == 0) { dismiss(); return; }
        exiting = true;
        hideKeyboard();
        try {
            lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            wm.updateViewLayout(root, lp);
        } catch (Exception ignored) {}

        final View r = root;
        final long dur = Prefs.exitAnimMs(ctx);
        final float w = Math.max(1, r.getWidth()), h = Math.max(1, r.getHeight());
        android.view.animation.Interpolator accel = new android.view.animation.AccelerateInterpolator(1.6f);
        android.view.animation.Interpolator smooth = new android.view.animation.AccelerateDecelerateInterpolator();
        r.setPivotX(w / 2f);
        r.setPivotY(h / 2f);

        switch (style) {
            case 1: // Fade
                r.animate().alpha(0f).setInterpolator(smooth).setDuration(dur).start();
                break;
            case 2: // Slide up
                r.animate().translationY(-h).setInterpolator(accel).setDuration(dur).start();
                break;
            case 3: // Slide down
                r.animate().translationY(h).setInterpolator(accel).setDuration(dur).start();
                break;
            case 4: // Slide sideways
                r.animate().translationX(-w).setInterpolator(accel).setDuration(dur).start();
                break;
            case 5: // Zoom through: the quiz flies past the camera
                r.animate().scaleX(1.4f).scaleY(1.4f).alpha(0f).setInterpolator(accel).setDuration(dur).start();
                break;
            case 6: // Shrink away
                r.animate().scaleX(0.7f).scaleY(0.7f).alpha(0f).setInterpolator(accel).setDuration(dur).start();
                break;
            case 7: { // Circle close: shrinks into the 解放 button
                int[] rl = new int[2], nl = new int[2];
                r.getLocationOnScreen(rl);
                next.getLocationOnScreen(nl);
                int cx = nl[0] - rl[0] + next.getWidth() / 2, cy = nl[1] - rl[1] + next.getHeight() / 2;
                float radius = (float) Math.hypot(Math.max(cx, w - cx), Math.max(cy, h - cy));
                try {
                    android.animation.Animator a = android.view.ViewAnimationUtils.createCircularReveal(r, cx, cy, radius, 0f);
                    a.setInterpolator(accel);
                    a.setDuration(dur);
                    a.start();
                } catch (Exception e) {
                    r.animate().alpha(0f).setDuration(dur).start();
                }
                break;
            }
            case 8: // Curtain: rolls up into the top edge
                r.setPivotY(0f);
                r.animate().scaleY(0f).setInterpolator(accel).setDuration(dur).start();
                break;
            case 9: // Spin away
                r.animate().rotation(-120f).scaleX(0.15f).scaleY(0.15f).alpha(0f)
                        .setInterpolator(accel).setDuration(dur).start();
                break;
            case 10: // Card flip: turns edge-on like a page
                r.setCameraDistance(w * 8f);
                r.setPivotX(0f);
                r.animate().rotationY(-90f).setInterpolator(accel).setDuration(dur).start();
                break;
            case 11: // Drop: the word card falls off the screen, then everything fades
                box.animate().translationY(h).rotation(14f).setInterpolator(accel).setDuration(dur).start();
                r.animate().alpha(0f).setStartDelay(dur / 3).setInterpolator(smooth).setDuration(dur - dur / 3).start();
                break;
            case 12: // Blur dissolve (Android 12+), otherwise a plain fade
                if (android.os.Build.VERSION.SDK_INT >= 31) {
                    android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(0f, 1f);
                    va.setDuration(dur);
                    va.setInterpolator(smooth);
                    va.addUpdateListener(an -> {
                        float t = (float) an.getAnimatedValue();
                        float rad = 1f + 60f * t;
                        r.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(rad, rad, android.graphics.Shader.TileMode.DECAL));
                        r.setAlpha(1f - t);
                        r.setScaleX(1f + 0.08f * t);
                        r.setScaleY(1f + 0.08f * t);
                    });
                    va.start();
                } else {
                    r.animate().alpha(0f).setInterpolator(smooth).setDuration(dur).start();
                }
                break;
            default:
                r.animate().alpha(0f).setDuration(dur).start();
        }
        // Remove the window once the animation is over (unless it was already dismissed or replaced).
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            if (root == r && exiting) dismiss();
        }, dur + 40);
    }

    private void nextCard() {
        closeSentences();
        card = Deck.random(ctx);
        if (card == null) {
            if (study) Toast.makeText(ctx, "No cards: add a deck and sync first", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        index++;
        revealed = false;
        meaning = "";
        if (study) progress.setText("学習モード  ·  " + index);
        else progress.setText(total > 1 ? "ことばロック  ·  " + index + " / " + total : "ことばロック");
        boolean defaultPrompt = card.instructions.isEmpty()
                || card.instructions.replaceAll("[!！.。\\s]+$", "").equalsIgnoreCase("Type the reading");
        instructions.setText(defaultPrompt ? "読み方を入力しなさい" : card.instructions);
        question.setText(card.question);
        input.setText("");
        input.setHint("ひらがなで入力");
        input.setEnabled(true);
        result.setVisibility(View.GONE);
        answer.setVisibility(View.GONE);
        meaningView.setVisibility(View.GONE);
        next.setVisibility(View.GONE);
        submit.setVisibility(View.VISIBLE);
        submit.setText("確認");
        // Card slides up and fades in.
        box.setAlpha(0f);
        box.setTranslationY(dp(28));
        box.animate().alpha(1f).translationY(0).setDuration(380)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(2f)).start();
        focusInput();
        // Fetch the meaning now so it is ready by the time the answer is checked.
        if (Prefs.meaningWrong(ctx) || Prefs.meaningRight(ctx)) {
            final Deck.Card forCard = card;
            Meanings.get(ctx, card, m -> {
                if (root == null || card != forCard) return;
                meaning = m;
                updateMeaning();
            });
        }
    }

    private void updateMeaning() {
        boolean want = revealed && !meaning.isEmpty()
                && (lastCorrect ? Prefs.meaningRight(ctx) : Prefs.meaningWrong(ctx));
        meaningView.setText(meaning);
        meaningView.setVisibility(want ? View.VISIBLE : View.GONE);
    }

    private String typed() {
        String t = input.getText().toString();
        return Prefs.acceptRomaji(ctx) ? Kana.romajiToHiragana(t) : t;
    }

    private boolean isCorrect() {
        String in = Kana.normalize(typed());
        if (in.isEmpty()) return false;
        for (String a : card.answers) if (Kana.normalize(a).equals(in)) return true;
        return false;
    }

    private void check() {
        if (input.getText().toString().trim().isEmpty()) {
            Toast.makeText(ctx, "答えを入力してください", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean ok = isCorrect();
        boolean mustBeCorrect = Prefs.requireCorrect(ctx);
        // Once you got it wrong, the meaning stays visible while you retype it (strict mode).
        if (!revealed) {
            lastCorrect = ok;
            Stats.record(ctx, card, ok);
            updateAnsweredCount(true);
        }
        revealed = true;
        // The quiz word's furigana in the example sentences can be shown now.
        if (sentencesOpen && shownSentences != null) showSentenceRows(shownSentences, shownCount);

        result.setVisibility(View.VISIBLE);
        answer.setVisibility(View.VISIBLE);
        answer.setText(String.join("、", card.answers));
        updateMeaning();

        if (ok) {
            result.setText("✓ 正解！");
            result.setTextColor(th.good);
            result.setScaleX(0.8f); result.setScaleY(0.8f);
            result.animate().scaleX(1f).scaleY(1f).setDuration(320)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(3f)).start();
        } else {
            result.setText("✗ 不正解 — あなたの答え：「" + input.getText().toString().trim() + "」");
            result.setTextColor(th.bad);
            shake(box);
        }
        for (View v : new View[]{result, answer, meaningView}) fadeIn(v);

        if (ok || !mustBeCorrect) {
            input.setEnabled(false);
            submit.setVisibility(View.GONE);
            next.setVisibility(View.VISIBLE);
            next.setText(study || index < total ? "次の単語 →" : "解放");
            hideKeyboard();
        } else {
            // Strict mode: the answer is shown, now type it correctly to continue.
            input.setText("");
            input.setHint("ひらがなで入力");
            submit.setText("もう一度確認");
            if (Prefs.keepKeyboardOnWrong(ctx)) focusInput();
            else hideKeyboard();
        }
        // Bring the result / answer / meaning into view.
        scroll.postDelayed(() -> { if (root != null) scroll.smoothScrollTo(0, result.getTop()); }, 200);
    }

    private void fadeIn(View v) {
        v.setAlpha(0f);
        v.setTranslationY(dp(10));
        v.animate().alpha(1f).translationY(0).setDuration(300).start();
    }

    private void shake(View v) {
        android.animation.ObjectAnimator a = android.animation.ObjectAnimator.ofFloat(v, "translationX",
                0, dp(-12), dp(12), dp(-8), dp(8), dp(-4), dp(4), 0);
        a.setDuration(420);
        a.start();
    }

    private static int mix(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return (a & 0xFF000000) | ((int) (ar + (br - ar) * t) << 16) | ((int) (ag + (bg - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
    }

    /** Soft shadow under a view (needs an outline, which a GradientDrawable background provides). */
    private void shadow(View v, float elevationDp) {
        v.setElevation(dp(elevationDp));
        v.setOutlineAmbientShadowColor(0xFF000000);
        v.setOutlineSpotShadowColor(0xFF000000);
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private void pressEffect(View v) {
        v.setOnTouchListener((view, e) -> {
            if (e.getAction() == android.view.MotionEvent.ACTION_DOWN)
                view.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90).start();
            else if (e.getAction() == android.view.MotionEvent.ACTION_UP || e.getAction() == android.view.MotionEvent.ACTION_CANCEL)
                view.animate().scaleX(1f).scaleY(1f).setDuration(140).start();
            return false;
        });
    }

    private void onNext() {
        if (index < total) nextCard(); else finish();
    }

    /** Never block emergency calls: drop the quiz and open the dialer. It comes back next time. */
    private void emergency() {
        dismiss();
        try {
            ctx.startActivity(new Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {}
    }

    private void focusInput() {
        input.requestFocus();
        input.postDelayed(() -> {
            if (root == null) return;
            InputMethodManager imm = ctx.getSystemService(InputMethodManager.class);
            if (imm != null) imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        }, 250);
    }

    private void hideKeyboard() {
        InputMethodManager imm = ctx.getSystemService(InputMethodManager.class);
        if (imm != null && root != null) imm.hideSoftInputFromWindow(root.getWindowToken(), 0);
    }

    // ---------------------------------------------------------------- layout

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.getResources().getDisplayMetrics());
    }

    private TextView text(float sp, int color, boolean bold) {
        TextView t = new TextView(ctx);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setTextLocale(Locale.JAPANESE);
        return t;
    }

    private Button button(String label, int bg) {
        Button b = new Button(ctx);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(th.btnText);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{mix(bg, 0xFFFFFFFF, 0.14f), bg});
        d.setCornerRadius(dp(16));
        b.setBackground(d);
        b.setPadding(0, dp(15), 0, dp(15));
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setLetterSpacing(0.05f);
        b.setStateListAnimator(null);
        shadow(b, 6);
        pressEffect(b);
        return b;
    }

    private LinearLayout.LayoutParams full(int topMargin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(topMargin);
        return p;
    }

    private View build() {
        scroll = new ScrollView(ctx);
        scroll.setFillViewport(true);
        scroll.setFitsSystemWindows(true);

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(22), dp(48), dp(22), dp(24));
        col.setClipToPadding(false);
        col.setClipChildren(false);
        scroll.addView(col);

        progress = text(13, th.sub, true);
        progress.setLetterSpacing(0.3f);
        if (!study && Prefs.allowSkip(ctx)) {
            // Escape hatch, in case something goes wrong: long-press the header.
            progress.setOnLongClickListener(v -> { Toast.makeText(ctx, "クイズをスキップしました", Toast.LENGTH_SHORT).show(); dismiss(); return true; });
        }
        col.addView(progress, full(0));

        box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(26), dp(18), dp(32));
        // Frosted glass: translucent card tint (lighter at the top) so the ambient background shows
        // through, with a faint bright edge. No elevation shadow — it would darken through the glass.
        GradientDrawable boxBg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{(mix(th.card, th.text, 0.10f) & 0x00FFFFFF) | 0xB3000000,
                          (th.card & 0x00FFFFFF) | 0x99000000});
        boxBg.setCornerRadius(dp(26));
        boxBg.setStroke(dp(1), (th.text & 0x00FFFFFF) | 0x33000000);
        box.setBackground(boxBg);
        col.addView(box, full(16));

        instructions = text(14, th.sub, false);
        box.addView(instructions, full(0));
        question = text(Prefs.questionSize(ctx), th.text, true);
        box.addView(question, full(12));

        input = new EditText(ctx);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        input.setTextColor(th.text);
        input.setHintTextColor(th.hint());
        GradientDrawable field = new GradientDrawable();
        field.setColor(th.card);
        field.setCornerRadius(dp(18));
        field.setStroke(dp(2), (th.sub & 0x00FFFFFF) | 0x40000000);
        GradientDrawable fieldFocused = new GradientDrawable();
        fieldFocused.setColor(th.card);
        fieldFocused.setCornerRadius(dp(18));
        fieldFocused.setStroke(dp(2), th.check);
        android.graphics.drawable.StateListDrawable fieldBg = new android.graphics.drawable.StateListDrawable();
        fieldBg.addState(new int[]{android.R.attr.state_focused}, fieldFocused);
        fieldBg.addState(new int[]{}, field);
        input.setBackground(fieldBg);
        input.setPadding(dp(16), dp(14), dp(16), dp(14));
        shadow(input, 4);
        input.setGravity(Gravity.CENTER);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        input.setImeHintLocales(new LocaleList(Locale.JAPANESE));
        input.setTextLocale(Locale.JAPANESE);
        input.setOnEditorActionListener((v, actionId, ev) -> {
            boolean enter = ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER && ev.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_DONE || enter) {
                if (submit.getVisibility() == View.VISIBLE) check(); else onNext();
                return true;
            }
            return false;
        });
        col.addView(input, full(24));

        romajiPreview = text(14, th.sub, false);
        romajiPreview.setVisibility(View.GONE);
        col.addView(romajiPreview, full(2));
        if (Prefs.acceptRomaji(ctx)) {
            input.addTextChangedListener(new TextWatcher() {
                public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                public void onTextChanged(CharSequence s, int a, int b, int c) {}
                public void afterTextChanged(Editable s) {
                    String conv = Kana.romajiToHiragana(s.toString());
                    romajiPreview.setVisibility(conv.equals(s.toString()) ? View.GONE : View.VISIBLE);
                    romajiPreview.setText("→ " + conv);
                }
            });
        }

        submit = button("確認", th.check);
        submit.setOnClickListener(v -> check());
        col.addView(submit, full(16));

        result = text(18, th.good, true);
        col.addView(result, full(24));
        answer = text(34, th.text, true);
        col.addView(answer, full(6));
        meaningView = text(18, th.sub, false);
        col.addView(meaningView, full(8));

        next = button("解放", th.unlock);
        next.setOnClickListener(v -> onNext());
        col.addView(next, full(24));

        // Example sentences from jisho.org: a toggle at the bottom of the quiz that opens a
        // panel of sentences right below it, inside the page.
        sentencesBtn = text(14, th.sub, true);
        sentencesBtn.setPadding(dp(16), dp(10), dp(16), dp(10));
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(dp(50));
        pill.setColor((th.card & 0x00FFFFFF) | 0x99000000);
        pill.setStroke(dp(1), (th.sub & 0x00FFFFFF) | 0x40000000);
        sentencesBtn.setBackground(pill);
        sentencesBtn.setOnClickListener(v -> toggleSentences());
        pressEffect(sentencesBtn);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sp.topMargin = dp(36);
        sp.gravity = Gravity.CENTER_HORIZONTAL;
        col.addView(sentencesBtn, sp);

        sentencesPanel = new LinearLayout(ctx);
        sentencesPanel.setOrientation(LinearLayout.VERTICAL);
        sentencesPanel.setPadding(dp(18), dp(16), dp(18), dp(16));
        GradientDrawable glass = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{(mix(th.card, th.text, 0.10f) & 0x00FFFFFF) | 0xB3000000,
                          (th.card & 0x00FFFFFF) | 0x99000000});
        glass.setCornerRadius(dp(22));
        glass.setStroke(dp(1), (th.text & 0x00FFFFFF) | 0x33000000);
        sentencesPanel.setBackground(glass);
        col.addView(sentencesPanel, full(12));
        closeSentences();

        // Root: quiz fills the screen; the bar (緊急通報 left, flashlight right) is pinned to the
        // bottom and never moves — the keyboard simply opens on top of it.
        android.widget.FrameLayout rootCol = new android.widget.FrameLayout(ctx);
        rootFrame = rootCol;
        rootCol.setBackgroundColor(th.bg);
        rootCol.addView(new AmbientBackground(ctx, th, Prefs.floatDeckWords(ctx) ? Deck.cards(ctx) : java.util.Collections.<Deck.Card>emptyList()), new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        scroll.setFitsSystemWindows(false);
        scroll.setClipToPadding(false);
        rootCol.addView(scroll, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(28), dp(8), dp(28), dp(20));
        bar.setClipChildren(false);
        bar.setClipToPadding(false);
        // Content scrolling under the bar fades out instead of colliding with the buttons.
        bar.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{th.bg, th.bg, (th.bg & 0x00FFFFFF) | 0xCC000000, th.bg & 0x00FFFFFF}));

        // Equal-width side slots keep the counter in the true centre even if a button is missing.
        android.widget.FrameLayout leftSlot = new android.widget.FrameLayout(ctx), rightSlot = new android.widget.FrameLayout(ctx);
        leftSlot.setClipChildren(false);
        rightSlot.setClipChildren(false);
        if (study) {
            View exit = roundButton(R.drawable.ic_close, "終了", th.text);
            exit.setOnClickListener(v -> dismiss());
            leftSlot.addView(exit, new android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.START));
        } else if (Prefs.lockMode(ctx)) {
            View sos = roundButton(R.drawable.ic_call, "緊急通報", th.bad);
            sos.setOnClickListener(v -> emergency());
            leftSlot.addView(sos, new android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.START));
        }
        bar.addView(leftSlot, new LinearLayout.LayoutParams(dp(80), LinearLayout.LayoutParams.WRAP_CONTENT));
        // Middle: lifetime answered count (or an empty spacer when turned off).
        answeredView = null;
        LinearLayout counter = new LinearLayout(ctx);
        counter.setOrientation(LinearLayout.VERTICAL);
        counter.setGravity(Gravity.CENTER);
        if (Prefs.showAnswered(ctx)) {
            answeredView = text(22, th.text, true);
            counter.addView(answeredView);
            TextView lbl = text(11, th.sub, false);
            lbl.setText("回答した問題");
            lbl.setLetterSpacing(0.1f);
            counter.addView(lbl);
            updateAnsweredCount(false);
        }
        bar.addView(counter, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        if (!study && torchId() != null) {
            View torch = roundButton(R.drawable.ic_flashlight, "ライト", th.text);
            torch.setOnClickListener(v -> toggleTorch());
            rightSlot.addView(torch, new android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.END));
            torchButton = torch;
            paintTorch();
        }
        bar.addView(rightSlot, new LinearLayout.LayoutParams(dp(80), LinearLayout.LayoutParams.WRAP_CONTENT));
        rootCol.addView(bar, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        // Handle insets by hand: status/nav bars pad the edges, and the keyboard only adds
        // scroll room to the quiz so the answer field stays reachable — the bar stays put.
        rootCol.setOnApplyWindowInsetsListener((v, insets) -> {
            int top, left, right, navBottom, ime = 0;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets sys = insets.getInsets(android.view.WindowInsets.Type.systemBars()
                        | android.view.WindowInsets.Type.displayCutout());
                top = sys.top; left = sys.left; right = sys.right; navBottom = sys.bottom;
                ime = insets.getInsets(android.view.WindowInsets.Type.ime()).bottom;
            } else {
                top = insets.getStableInsetTop(); left = insets.getStableInsetLeft();
                right = insets.getStableInsetRight(); navBottom = insets.getStableInsetBottom();
            }
            v.setPadding(left, top, right, 0);
            lastNavBottom = navBottom;
            bar.setPadding(dp(28), dp(8), dp(28), dp(20) + navBottom);
            int barSpace = dp(110) + navBottom;
            scroll.setPadding(0, 0, 0, Math.max(barSpace, ime));
            if (ime > 0 && input.hasFocus()) scroll.post(() -> scroll.smoothScrollTo(0, Math.max(0, input.getTop() - dp(120))));
            return android.view.WindowInsets.CONSUMED;
        });
        return rootCol;
    }

    // ---------------------------------------------------------------- bottom bar

    private TextView answeredView;

    // ---------------------------------------------------------------- example sentences (jisho.org)

    private TextView sentencesBtn;
    private android.widget.FrameLayout rootFrame;
    private LinearLayout sentencesPanel;
    private boolean sentencesOpen;
    private int lastNavBottom;
    private static final int SENTENCES_SHOWN = 5;

    private void toggleSentences() {
        if (sentencesOpen) closeSentences(); else openSentences();
    }

    private void openSentences() {
        if (card == null || sentencesPanel == null) return;
        hideKeyboard();
        sentencesOpen = true;
        sentencesBtn.setText("例文を隠す  ▴");
        sentencesPanel.removeAllViews();
        sentencesPanel.addView(sentencesNote("読み込み中…"));
        sentencesPanel.setVisibility(View.VISIBLE);
        fadeIn(sentencesPanel);
        scrollToSentences();
        final Deck.Card forCard = card;
        Sentences.get(card.question, list -> {
            if (root == null || card != forCard || !sentencesOpen) return;
            sentencesPanel.removeAllViews();
            if (list == null) { sentencesPanel.addView(sentencesNote("例文を読み込めませんでした（オフライン？）")); return; }
            if (list.isEmpty()) { sentencesPanel.addView(sentencesNote("jisho.org に例文がありません")); return; }
            showSentenceRows(list, Math.min(SENTENCES_SHOWN, list.size()));
            scrollToSentences();
        });
    }

    /** Lists the first {@code count} sentences, plus a "show more" row if there are more. */
    private void showSentenceRows(java.util.List<Sentences.Sentence> list, int count) {
        shownSentences = list;
        shownCount = count;
        sentencesPanel.removeAllViews();
        for (int i = 0; i < count; i++) {
            Sentences.Sentence s = list.get(i);
            if (i > 0) {
                View d = new View(ctx);
                d.setBackgroundColor((th.text & 0x00FFFFFF) | 0x14000000);
                LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.8f)));
                dl.topMargin = dp(12);
                dl.bottomMargin = dp(12);
                sentencesPanel.addView(d, dl);
            }
            sentencesPanel.addView(sentenceView(s));
            if (!s.english.isEmpty()) {
                TextView en = text(13.5f, th.sub, false);
                en.setGravity(Gravity.START);
                en.setTextLocale(Locale.ENGLISH);
                en.setText(s.english);
                sentencesPanel.addView(en, full(4));
            }
        }
        if (count < list.size()) {
            TextView more = text(14, th.check, true);
            more.setText("もっと見る（" + (list.size() - count) + "）");
            more.setPadding(0, dp(14), 0, dp(2));
            more.setOnClickListener(v -> showSentenceRows(list, list.size()));
            sentencesPanel.addView(more);
        }
        TextView src = text(11, th.sub, false);
        src.setText("例文: jisho.org");
        src.setAlpha(0.7f);
        sentencesPanel.addView(src, full(12));
    }

    /** A Japanese sentence with furigana drawn above the words that have it. */
    private TextView sentenceView(Sentences.Sentence s) {
        TextView ja = text(17, th.text, false);
        ja.setGravity(Gravity.START);
        float rubySize = ja.getTextSize() * 0.5f;
        android.text.SpannableStringBuilder b = new android.text.SpannableStringBuilder();
        boolean anyRuby = false;
        String word = card.question;
        for (String[] p : s.parts) {
            int start = b.length();
            b.append(p[0]);
            boolean hasWord = !word.isEmpty() && p[0].contains(word);
            if (p[1] != null) {
                // The quiz word's own reading stays hidden until you've answered.
                String reading = hasWord && !revealed ? null : p[1];
                b.setSpan(new RubySpan(reading, rubySize, th.sub, hasWord ? th.check : 0),
                        start, b.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                anyRuby = true;
            } else if (hasWord) {
                // Plain text only: extra spans inside a RubySpan would split it and draw the reading twice.
                for (int i = p[0].indexOf(word); i >= 0; i = p[0].indexOf(word, i + word.length())) {
                    b.setSpan(new android.text.style.ForegroundColorSpan(th.check), start + i, start + i + word.length(), 0);
                    b.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), start + i, start + i + word.length(), 0);
                }
            }
        }
        // Room for the readings: above the first line, and between lines.
        int rubyRoom = anyRuby ? (int) (rubySize * 1.3f) : 0;
        ja.setLineSpacing(rubyRoom, 1.15f);
        ja.setPadding(0, rubyRoom, 0, 0);
        ja.setText(b);
        return ja;
    }

    /** Draws a word with its reading in small text centred above it (like HTML ruby). */
    private static final class RubySpan extends android.text.style.ReplacementSpan {
        /** Null: draw the word without its reading. */
        private final String reading;
        /** Non-zero: draw the word bold in this colour (it's the quiz word). */
        private final int highlightColor;
        private final android.graphics.Paint rubyPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        RubySpan(String reading, float size, int color, int highlightColor) {
            this.reading = reading;
            this.highlightColor = highlightColor;
            rubyPaint.setTextSize(size);
            rubyPaint.setColor(color);
            rubyPaint.setTextLocale(Locale.JAPANESE);
        }

        private float rubyWidth() { return reading == null ? 0 : rubyPaint.measureText(reading); }

        private float baseWidth(android.graphics.Paint paint, CharSequence text, int start, int end) {
            if (highlightColor == 0) return paint.measureText(text, start, end);
            boolean was = paint.isFakeBoldText();
            paint.setFakeBoldText(true);
            float w = paint.measureText(text, start, end);
            paint.setFakeBoldText(was);
            return w;
        }

        @Override public int getSize(android.graphics.Paint paint, CharSequence text, int start, int end, android.graphics.Paint.FontMetricsInt fm) {
            if (fm != null) {
                android.graphics.Paint.FontMetricsInt p = paint.getFontMetricsInt();
                fm.ascent = p.ascent; fm.descent = p.descent; fm.top = p.top; fm.bottom = p.bottom;
            }
            return (int) Math.ceil(Math.max(baseWidth(paint, text, start, end), rubyWidth()));
        }

        @Override public void draw(android.graphics.Canvas c, CharSequence text, int start, int end, float x,
                                   int top, int y, int bottom, android.graphics.Paint paint) {
            float baseW = baseWidth(paint, text, start, end), rubyW = rubyWidth();
            float w = Math.max(baseW, rubyW);
            int color = paint.getColor();
            boolean bold = paint.isFakeBoldText();
            if (highlightColor != 0) { paint.setColor(highlightColor); paint.setFakeBoldText(true); }
            c.drawText(text, start, end, x + (w - baseW) / 2f, y, paint);
            paint.setColor(color);
            paint.setFakeBoldText(bold);
            if (reading == null) return;
            float rubyBaseline = y + paint.ascent() - rubyPaint.descent() + rubyPaint.getTextSize() * 0.05f;
            c.drawText(reading, x + (w - rubyW) / 2f, rubyBaseline, rubyPaint);
        }
    }

    private TextView sentencesNote(String msg) {
        TextView t = text(14, th.sub, false);
        t.setText(msg);
        return t;
    }

    private void scrollToSentences() {
        scroll.postDelayed(() -> {
            if (root != null && sentencesOpen) scroll.smoothScrollTo(0, Math.max(0, sentencesBtn.getTop() - dp(24)));
        }, 120);
    }

    private java.util.List<Sentences.Sentence> shownSentences;
    private int shownCount;

    private void closeSentences() {
        sentencesOpen = false;
        shownSentences = null;
        if (sentencesPanel == null) return;
        sentencesPanel.removeAllViews();
        sentencesPanel.setVisibility(View.GONE);
        sentencesBtn.setText("例文を見る  ·  jisho.org  ▾");
    }

    private void updateAnsweredCount(boolean bump) {
        if (answeredView == null) return;
        answeredView.setText(String.format(Locale.US, "%,d", Stats.total(ctx)));
        if (bump) {
            answeredView.setScaleX(1.25f); answeredView.setScaleY(1.25f);
            answeredView.animate().scaleX(1f).scaleY(1f).setDuration(300)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(3f)).start();
        }
    }

    private View torchButton;
    private boolean torchOn;
    private String torchCamId;
    private boolean torchCallbackRegistered;
    private final android.hardware.camera2.CameraManager.TorchCallback torchCallback =
            new android.hardware.camera2.CameraManager.TorchCallback() {
                @Override public void onTorchModeChanged(String cameraId, boolean enabled) {
                    if (cameraId.equals(torchCamId)) { torchOn = enabled; paintTorch(); }
                }
            };

    /** A circular icon button with a small label underneath. */
    private View roundButton(int icon, String label, int tint) {
        LinearLayout b = new LinearLayout(ctx);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER_HORIZONTAL);
        android.widget.ImageView iv = new android.widget.ImageView(ctx);
        iv.setImageResource(icon);
        iv.setImageTintList(android.content.res.ColorStateList.valueOf(tint));
        iv.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        iv.setPadding(dp(16), dp(16), dp(16), dp(16));
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(th.card);
        iv.setBackground(circle);
        iv.setTag("icon");
        shadow(iv, 8);
        pressEffect(b);
        b.setClipChildren(false);
        b.setClipToPadding(false);
        b.addView(iv, new LinearLayout.LayoutParams(dp(60), dp(60)));
        TextView t = text(12, th.sub, false);
        t.setText(label);
        b.addView(t, full(4));
        b.setContentDescription(label);
        return b;
    }

    private String torchId() {
        if (torchCamId != null) return torchCamId;
        try {
            android.hardware.camera2.CameraManager cm = ctx.getSystemService(android.hardware.camera2.CameraManager.class);
            for (String id : cm.getCameraIdList()) {
                android.hardware.camera2.CameraCharacteristics ch = cm.getCameraCharacteristics(id);
                Boolean flash = ch.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE);
                Integer facing = ch.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING);
                if (Boolean.TRUE.equals(flash) && facing != null
                        && facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK) {
                    torchCamId = id;
                    if (!torchCallbackRegistered) {
                        cm.registerTorchCallback(torchCallback, new android.os.Handler(android.os.Looper.getMainLooper()));
                        torchCallbackRegistered = true;
                    }
                    return id;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void toggleTorch() {
        try {
            ctx.getSystemService(android.hardware.camera2.CameraManager.class).setTorchMode(torchId(), !torchOn);
        } catch (Exception e) {
            Toast.makeText(ctx, "ライトを使用できません", Toast.LENGTH_SHORT).show();
        }
    }

    /** Lit-up circle while the flashlight is on. */
    private void paintTorch() {
        if (torchButton == null) return;
        android.widget.ImageView iv = torchButton.findViewWithTag("icon");
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(torchOn ? th.text : th.card);
        iv.setBackground(circle);
        iv.setElevation(dp(torchOn ? 14 : 8)); // lifted while on
        iv.setImageTintList(android.content.res.ColorStateList.valueOf(torchOn ? th.bg : th.text));
    }
}
