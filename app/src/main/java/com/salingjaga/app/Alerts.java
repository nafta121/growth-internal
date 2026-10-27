package com.salingjaga.app;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;

/**
 * System notifications. Channel APIs (Android 8+) are called by reflection because
 * the app is compiled against the API 23 SDK.
 */
final class Alerts {
    static final String CH_STATUS = "status";
    static final String CH_SOS = "sos";
    static final String CH_MESSAGES = "messages";
    static final int ID_ONGOING = 1;
    private static int nextId = 100;

    private Alerts() { }

    static void createChannels(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        channel(nm, CH_STATUS, "Status koneksi", 2 /* IMPORTANCE_LOW */);
        channel(nm, CH_MESSAGES, "Pesan dan pengumuman", 4 /* IMPORTANCE_HIGH */);
        channel(nm, CH_SOS, "SOS darurat", 5 /* IMPORTANCE_MAX */);
    }

    private static void channel(NotificationManager nm, String id, String name, int importance) {
        try {
            Class<?> ch = Class.forName("android.app.NotificationChannel");
            Object o = ch.getConstructor(String.class, CharSequence.class, int.class)
                    .newInstance(id, name, importance);
            if (importance >= 4) {
                ch.getMethod("enableVibration", boolean.class).invoke(o, true);
                ch.getMethod("setVibrationPattern", long[].class)
                        .invoke(o, (Object) new long[] {0, 500, 250, 500});
            }
            if (CH_SOS.equals(id)) {
                ch.getMethod("setBypassDnd", boolean.class).invoke(o, true);
            }
            NotificationManager.class.getMethod("createNotificationChannel", ch).invoke(nm, o);
        } catch (Exception ignored) {
            // Without a channel Android 8+ drops the notification; the in-app alerts still work.
        }
    }

    static Notification.Builder builder(Context c, String channel) {
        Notification.Builder b = new Notification.Builder(c);
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                Notification.Builder.class.getMethod("setChannelId", String.class).invoke(b, channel);
            } catch (Exception ignored) { }
        }
        Intent open = new Intent(c, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | 0x04000000; // FLAG_IMMUTABLE
        b.setSmallIcon(R.drawable.ic_mic)
                .setColor(Color.rgb(28, 82, 51))
                .setContentIntent(PendingIntent.getActivity(c, 0, open, flags))
                .setShowWhen(true);
        return b;
    }

    /** Heads-up notification shown when the app is in the background or the screen is off. */
    static void show(Context c, String channel, String title, String text) {
        boolean sos = CH_SOS.equals(channel);
        Notification.Builder b = builder(c, channel)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setPriority(sos ? Notification.PRIORITY_MAX : Notification.PRIORITY_HIGH)
                .setCategory(sos ? Notification.CATEGORY_ALARM : Notification.CATEGORY_MESSAGE)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setDefaults(Notification.DEFAULT_ALL);
        if (sos) {
            Intent open = new Intent(c, MainActivity.class)
                    .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            b.setFullScreenIntent(PendingIntent.getActivity(c, 1, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | 0x04000000), true);
        }
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        try {
            nm.notify(nextId++, b.build());
        } catch (SecurityException ignored) {
            // Notification permission denied on Android 13+.
        }
    }
}
