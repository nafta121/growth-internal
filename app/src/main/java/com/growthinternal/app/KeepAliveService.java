package com.growthinternal.app;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Foreground service that keeps the process, CPU and Wi-Fi awake while in a group,
 * so calls, SOS and messages still arrive with the screen off.
 */
public class KeepAliveService extends Service {
    static final String EXTRA_TEXT = "text";

    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;

    static void update(Context c, String text) {
        Intent i = new Intent(c, KeepAliveService.class).putExtra(EXTRA_TEXT, text);
        try {
            c.startService(i);
        } catch (RuntimeException ignored) {
            // Background start refused; the activity is visible again soon enough.
        }
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, KeepAliveService.class));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String text = intent == null ? null : intent.getStringExtra(EXTRA_TEXT);
        Notification n = Alerts.builder(this, Alerts.CH_STATUS)
                .setContentTitle("Growth Internal aktif")
                .setContentText(text == null ? "Tersambung ke grup" : text)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_LOW)
                .build();
        // shortcut: manifest foregroundServiceType omitted because build.sh compiles against android-23; add when compileSdk >= 29 and targetSdk >= 34
        startForeground(Alerts.ID_ONGOING, n);
        if (wake == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "growthinternal:group");
            wake.setReferenceCounted(false);
        }
        wake.acquire(6 * 60 * 60 * 1000L);
        if (wifi == null) {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "growthinternal:group");
            wifi.acquire();
        }
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        if (wake != null && wake.isHeld()) wake.release();
        if (wifi != null && wifi.isHeld()) wifi.release();
        stopForeground(true);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
