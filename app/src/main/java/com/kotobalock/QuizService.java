package com.kotobalock;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;

/**
 * Foreground service that watches for the phone being unlocked (USER_PRESENT) — or the
 * screen turning on when there is no lock screen — and shows the quiz overlay on top of
 * everything until the question has been attempted.
 */
public class QuizService extends Service {
    public static final String ACTION_SHOW_NOW = "com.kotobalock.SHOW_NOW";
    private static final String CHANNEL = "kotoba_lock";

    private QuizOverlay overlay;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(a)) {
                // Lock screen mode: put the quiz up now, so it is already there when the screen wakes.
                // Already answered (e.g. got it wrong) and the screen went off: new question on wake.
                if (overlay != null && overlay.isShowing()) overlay.onScreenOff();
                else if (Prefs.lockMode(c)) maybeShow(false);
            } else if (Intent.ACTION_USER_PRESENT.equals(a)) {
                maybeShow(false);
            } else if (Intent.ACTION_SCREEN_ON.equals(a)) {
                KeyguardManager km = getSystemService(KeyguardManager.class);
                if (km != null && !km.isKeyguardLocked()) maybeShow(false); // no lock screen set
            }
        }
    };

    public static final String ACTION_BOOT = "com.kotobalock.BOOT";

    public static void start(Context c) { start(c, null); }

    public static void start(Context c, String action) {
        if (!Prefs.enabled(c) || !Settings.canDrawOverlays(c)) return;
        try { c.startForegroundService(new Intent(c, QuizService.class).setAction(action)); } catch (Exception ignored) {}
    }

    public static void stop(Context c) { c.stopService(new Intent(c, QuizService.class)); }

    public static void showNow(Context c) {
        c.startForegroundService(new Intent(c, QuizService.class).setAction(ACTION_SHOW_NOW));
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Kotoba Lock running", NotificationManager.IMPORTANCE_MIN));
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_lock)
                .setContentTitle("Kotoba Lock is active")
                .setContentText("A word quiz will appear when you unlock your phone")
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(1, n);

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_USER_PRESENT);
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(receiver, f);
        overlay = new QuizOverlay(this);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!Prefs.enabled(this) && (intent == null || !ACTION_SHOW_NOW.equals(intent.getAction()))) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_SHOW_NOW.equals(intent.getAction())) maybeShow(true);
        else if (intent != null && ACTION_BOOT.equals(intent.getAction()) && Prefs.lockMode(this)) maybeShow(false);
        return START_STICKY;
    }

    private void maybeShow(boolean force) {
        if (!Settings.canDrawOverlays(this) || overlay.isShowing()) return;
        Deck.maybeAutoSync(this);
        if (!force) {
            if (!Prefs.enabled(this)) return;
            long cooldown = Prefs.cooldownMin(this) * 60_000L;
            long last = Prefs.get(this).getLong(Prefs.LAST_QUIZ_DONE, 0);
            if (cooldown > 0 && System.currentTimeMillis() - last < cooldown) return;
            // Never block the screen during a ringing / active phone or VoIP call.
            AudioManager am = getSystemService(AudioManager.class);
            int mode = am == null ? AudioManager.MODE_NORMAL : am.getMode();
            if (mode == AudioManager.MODE_RINGTONE || mode == AudioManager.MODE_IN_CALL
                    || mode == AudioManager.MODE_IN_COMMUNICATION) return;
        }
        if (Deck.cards(this).isEmpty()) return; // nothing synced yet: don't lock the user out
        overlay.show();
    }

    @Override public void onDestroy() {
        try { unregisterReceiver(receiver); } catch (Exception ignored) {}
        if (overlay != null) overlay.dismiss();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
