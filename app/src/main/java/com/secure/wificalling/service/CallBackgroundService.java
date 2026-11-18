package com.secure.wificalling.service;

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
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import com.secure.wificalling.network.CallSignaling;
import com.secure.wificalling.ui.IncomingCallActivity;
import com.secure.wificalling.ui.MainActivity;

/**
 * 24/7 Persistent Call Listener Service.
 * Manages incoming call alerts, loud ringtones, and deep sleep wakeup.
 * Guarantees ringtone stops immediately when call is answered or declined.
 */
public class CallBackgroundService extends Service implements CallSignaling.CallListener {

    private static final String TAG = "CallBackgroundService";
    private static final String CHANNEL_ID = "WiFiCallingChannel";
    private static final String CALL_CHANNEL_ID = "WiFiIncomingCallChannel";
    private static final int NOTIFICATION_ID = 1001;
    private static final int INCOMING_CALL_NOTIF_ID = 1002;

    private static CallBackgroundService instance;
    private CallSignaling signaling;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private MediaPlayer incomingRingtonePlayer;
    private BroadcastReceiver networkReceiver;

    public static synchronized void stopRingtone(Context context) {
        if (instance != null) {
            instance.stopIncomingRingtone();
            instance.dismissIncomingNotification();
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        try {
            startForegroundServiceNotification();
            acquireLocks();

            signaling = new CallSignaling(this, this);
            signaling.start();
            registerNetworkReceiver();
            Log.i(TAG, "CallBackgroundService active 24/7 (Battery-Optimized).");
        } catch (Throwable t) {
            Log.e(TAG, "onCreate error", t);
        }
    }

    private void registerNetworkReceiver() {
        try {
            networkReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (signaling != null) {
                        Log.i(TAG, "Network state changed -> triggering reconnect & presence publish");
                        signaling.reconnect();
                        signaling.publishPresence();
                    }
                }
            };
            IntentFilter filter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
            registerReceiver(networkReceiver, filter);
        } catch (Throwable ignored) {}
    }

    private void acquireLocks() {
        try {
            String info = (Build.MANUFACTURER + " " + Build.BRAND).toLowerCase();
            boolean isVivoOrOldAndroid = info.contains("vivo") || Build.VERSION.SDK_INT <= Build.VERSION_CODES.O;
            if (isVivoOrOldAndroid) {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null && (wakeLock == null || !wakeLock.isHeld())) {
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WiFiCalling:KeepAliveLock");
                    wakeLock.acquire();
                }

                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wm != null && (wifiLock == null || !wifiLock.isHeld())) {
                    wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL, "WiFiCalling:WifiLock");
                    wifiLock.acquire();
                }
                Log.i(TAG, "Acquired persistent locks for Vivo / legacy background stability");
            }
        } catch (Throwable t) {
            Log.w(TAG, "Error acquiring locks", t);
        }
    }

    private void startForegroundServiceNotification() {
        try {
            createNotificationChannels();

            Intent notificationIntent = new Intent(this, MainActivity.class);
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    this, 0, notificationIntent,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0
            );

            Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("Wi-Fi Phone Active")
                    .setContentText("Ready to receive calls (Ext: " + CallSignaling.detectMyExtensionId() + ")")
                    .setSmallIcon(android.R.drawable.sym_action_call)
                    .setContentIntent(pendingIntent)
                    .setOngoing(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .build();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // Android 14+ / 15 / 16 / 17
                try {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL);
                } catch (Throwable t1) {
                    startForeground(NOTIFICATION_ID, notification);
                }
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Throwable t) {
            Log.e(TAG, "startForeground error", t);
        }
    }

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                NotificationManager manager = getSystemService(NotificationManager.class);
                if (manager != null) {
                    NotificationChannel channel = new NotificationChannel(
                            CHANNEL_ID,
                            "Wi-Fi Calling Active",
                            NotificationManager.IMPORTANCE_LOW
                    );
                    manager.createNotificationChannel(channel);

                    NotificationChannel callChannel = new NotificationChannel(
                            CALL_CHANNEL_ID,
                            "Incoming Calls",
                            NotificationManager.IMPORTANCE_HIGH
                    );
                    callChannel.enableVibration(true);
                    callChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
                    manager.createNotificationChannel(callChannel);
                }
            } catch (Throwable t) {
                Log.e(TAG, "Notification channel error", t);
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onIncomingCall(String callerName, String callerIp, String callerExt) {
        String resolvedName = MainActivity.getContactDisplayName(this, callerExt, callerName);
        Log.i(TAG, "Incoming call from " + resolvedName + " [" + callerName + "] (Ext: " + callerExt + ", IP: " + callerIp + ")");

        // 1. Record recents interaction & Send RINGING response back to caller immediately
        MainActivity.recordInteraction(this, callerExt);
        if (signaling != null) {
            signaling.sendCommand(callerExt, callerIp, "RINGING");
        }

        // 2. Wake screen from deep sleep
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                PowerManager.WakeLock fullWake = pm.newWakeLock(
                        PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE,
                        "WiFiCalling:IncomingCallWake"
                );
                fullWake.acquire(30000);
            }
        } catch (Throwable ignored) {}

        // 3. Play loud ringtone immediately
        playIncomingRingtone();

        // 4. Launch FullScreen Intent & Activity
        try {
            Intent callIntent = new Intent(this, IncomingCallActivity.class);
            callIntent.putExtra("CALLER_NAME", resolvedName);
            callIntent.putExtra("CALLER_MODEL", callerName);
            callIntent.putExtra("CALLER_IP", callerIp);
            callIntent.putExtra("CALLER_EXT", callerExt);
            callIntent.putExtra("CALLER_PUBLIC_EP", CallSignaling.getPeerPublicEndpoint(callerExt));
            callIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);

            PendingIntent fullScreenPendingIntent = PendingIntent.getActivity(
                    this, 0, callIntent,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_UPDATE_CURRENT
            );

            NotificationCompat.Builder notifBuilder = new NotificationCompat.Builder(this, CALL_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.sym_action_call)
                    .setContentTitle(resolvedName)
                    .setContentText(callerName + " (Ext " + callerExt + ") is calling...")
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setCategory(NotificationCompat.CATEGORY_CALL)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setFullScreenIntent(fullScreenPendingIntent, true)
                    .setAutoCancel(true);

            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(INCOMING_CALL_NOTIF_ID, notifBuilder.build());
            }

            startActivity(callIntent);
        } catch (Throwable t) {
            Log.e(TAG, "Error displaying incoming call", t);
        }
    }

    public synchronized void playIncomingRingtone() {
        try {
            stopIncomingRingtone();
            Uri ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            incomingRingtonePlayer = new MediaPlayer();
            incomingRingtonePlayer.setDataSource(this, ringtoneUri);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                incomingRingtonePlayer.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build());
            } else {
                incomingRingtonePlayer.setAudioStreamType(AudioManager.STREAM_RING);
            }
            incomingRingtonePlayer.setLooping(true);
            incomingRingtonePlayer.prepare();
            incomingRingtonePlayer.start();
        } catch (Throwable t) {
            Log.e(TAG, "Ringtone error", t);
        }
    }

    public synchronized void stopIncomingRingtone() {
        try {
            if (incomingRingtonePlayer != null) {
                if (incomingRingtonePlayer.isPlaying()) {
                    incomingRingtonePlayer.stop();
                }
                incomingRingtonePlayer.release();
                incomingRingtonePlayer = null;
            }
        } catch (Throwable ignored) {}
    }

    @Override
    public void onCallRinging(String remoteIp) {}

    @Override
    public void onCallAccepted(String remoteIp) {
        stopIncomingRingtone();
        dismissIncomingNotification();
    }

    @Override
    public void onCallRejected(String remoteIp) {
        stopIncomingRingtone();
        dismissIncomingNotification();
    }

    @Override
    public void onCallEnded(String remoteIp) {
        stopIncomingRingtone();
        dismissIncomingNotification();
    }

    public void dismissIncomingNotification() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.cancel(INCOMING_CALL_NOTIF_ID);
            }
        } catch (Throwable ignored) {}
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        Log.i(TAG, "App swiped from recents -> Scheduling immediate service resurrection");
        try {
            Intent restartIntent = new Intent(getApplicationContext(), CallBackgroundService.class);
            restartIntent.setPackage(getPackageName());
            PendingIntent restartPendingIntent = PendingIntent.getService(
                    getApplicationContext(), 101, restartIntent,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_ONE_SHOT
            );
            android.app.AlarmManager alarmManager = (android.app.AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (alarmManager != null) {
                alarmManager.set(
                        android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        android.os.SystemClock.elapsedRealtime() + 800,
                        restartPendingIntent
                );
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error in onTaskRemoved", t);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopIncomingRingtone();
        try {
            if (networkReceiver != null) {
                try { unregisterReceiver(networkReceiver); } catch (Throwable ignored) {}
                networkReceiver = null;
            }
            if (signaling != null) signaling.removeListener(this);
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        } catch (Throwable ignored) {}
        instance = null;

        // Auto-restart if killed unexpectedly by aggressive task cleaners
        try {
            Intent restartIntent = new Intent(getApplicationContext(), CallBackgroundService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(restartIntent);
            } else {
                startService(restartIntent);
            }
        } catch (Throwable ignored) {}
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
