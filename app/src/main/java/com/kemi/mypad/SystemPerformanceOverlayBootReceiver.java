package com.kemi.mypad;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restores the explicitly enabled D2 performance overlay after boot or app replacement. */
public final class SystemPerformanceOverlayBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            SystemPerformanceOverlayService.restoreIfEnabled(context);
        }
    }
}
