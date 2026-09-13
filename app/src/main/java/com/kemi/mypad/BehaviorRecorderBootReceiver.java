package com.kemi.mypad;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restarts an explicitly enabled recorder after boot or an app upgrade. */
public final class BehaviorRecorderBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!BehaviorRecordStore.isEnabled(context)) return;
        BehaviorRecorderService.start(context, intent == null ? "boot" : intent.getAction());
    }
}
