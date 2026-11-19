package com.kotobalock;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;

/**
 * Study mode: the quiz screen inside the app, on your enabled decks, for as long as you like.
 * No emergency / flashlight buttons; 終了 or back leaves it. Answers count toward your stats.
 */
public class StudyActivity extends Activity {
    private QuizOverlay quiz;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (getActionBar() != null) getActionBar().hide();
        QuizTheme th = QuizTheme.load(this);
        int bg = th.bg;
        float lum = (0.299f * ((bg >> 16) & 0xFF) + 0.587f * ((bg >> 8) & 0xFF) + 0.114f * (bg & 0xFF)) / 255f;
        if (Build.VERSION.SDK_INT >= 30 && lum >= 0.5f && getWindow().getInsetsController() != null) {
            int light = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            getWindow().getInsetsController().setSystemBarsAppearance(light, light);
        }
        getWindow().getDecorView().setBackgroundColor(bg);

        quiz = new QuizOverlay(this);
        setContentView(quiz.startStudy());

        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    () -> { if (!quiz.onBack()) finish(); });
        }
    }

    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        if (!quiz.onBack()) super.onBackPressed();
    }
}
