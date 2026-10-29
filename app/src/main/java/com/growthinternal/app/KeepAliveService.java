package com.growthinternal.app;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Foreground service that keeps the process, CPU, low-latency Wi-Fi radio, and UDP multicast
 * awake while standing by or in a group, so calls, PTT, SOS and messages never drop with the screen off.
 */
public class KeepAliveService extends Service {
    static final String EXTRA_TEXT = "text";
    static final int WIFI_MODE_LOW_LATENCY = 4; // WifiManager.WIFI_MODE_FULL_LOW_LATENCY (API 29+)

    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;
    private WifiManager.MulticastLock multicast;

    static int wifiLockModeForSdk(int sdkInt) {
        return sdkInt >= 29 ? WIFI_MODE_LOW_LATENCY : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
    }

    static void update(Context c, String text) {
        if (c == null) return;
        Intent i = new Intent(c, KeepAliveService.class).putExtra(EXTRA_TEXT, text);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                try {
                    Context.class.getMethod("startForegroundService", Intent.class).invoke(c, i);
                    return;
                } catch (Exception ignored) {
                }
            }
            c.startService(i);
        } catch (RuntimeException ignored) {
            // Background start refused on strict OEM policy; retried on next foreground event.
        }
    }

    static void stop(Context c) {
        if (c == null) return;
        try {
            c.stopService(new Intent(c, KeepAliveService.class));
        } catch (RuntimeException ignored) {
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String text = intent == null ? null : intent.getStringExtra(EXTRA_TEXT);
        Notification n = Alerts.builder(this, Alerts.CH_STATUS)
                .setContentTitle("Growth Internal aktif")
                .setContentText(text == null ? "Siaga komunikasi lapangan" : text)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_LOW)
                .build();
        // shortcut: manifest foregroundServiceType omitted because build.sh compiles against android-23; add when compileSdk >= 29 and targetSdk >= 34
        startForeground(Alerts.ID_ONGOING, n);
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            if (wake == null) {
                wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "growthinternal:group");
                wake.setReferenceCounted(false);
            }
            wake.acquire(12 * 60 * 60 * 1000L);
        }
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            if (wifi == null) {
                try {
                    wifi = wm.createWifiLock(wifiLockModeForSdk(Build.VERSION.SDK_INT),
                            "growthinternal:group");
                } catch (RuntimeException e) {
                    wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                            "growthinternal:group");
                }
                wifi.setReferenceCounted(false);
            }
            if (!wifi.isHeld()) {
                try { wifi.acquire(); } catch (RuntimeException ignored) { }
            }
            if (multicast == null) {
                multicast = wm.createMulticastLock("growthinternal:lan");
                multicast.setReferenceCounted(false);
            }
            if (!multicast.isHeld()) {
                try { multicast.acquire(); } catch (RuntimeException ignored) { }
            }
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        if (wake != null && wake.isHeld()) {
            try { wake.release(); } catch (RuntimeException ignored) { }
        }
        if (wifi != null && wifi.isHeld()) {
            try { wifi.release(); } catch (RuntimeException ignored) { }
        }
        if (multicast != null && multicast.isHeld()) {
            try { multicast.release(); } catch (RuntimeException ignored) { }
        }
        stopForeground(true);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
