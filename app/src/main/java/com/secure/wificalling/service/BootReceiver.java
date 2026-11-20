package com.secure.wificalling.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * Ensures CallBackgroundService starts automatically on phone boot / restart.
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            Log.i(TAG, "Device boot completed -> Starting 24/7 CallBackgroundService");
            try {
                Intent serviceIntent = new Intent(context, CallBackgroundService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
            } catch (Throwable t) {
                Log.e(TAG, "Failed to start service on boot", t);
            }
        }
    }
}
