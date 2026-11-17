package com.kotobalock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        QuizService.start(c, Intent.ACTION_BOOT_COMPLETED.equals(i.getAction()) ? QuizService.ACTION_BOOT : null);
    }
}
