package com.salingjaga.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.location.Location;
import android.net.wifi.p2p.WifiP2pDevice;
import android.view.View;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34, qualifiers = "w393dp-h852dp-xxhdpi")
public class ScreenshotTest {
    static void snap(MainActivity a, String name) throws Exception {
        ShadowLooper.idleMainLooper();
        View root = a.getWindow().getDecorView();
        root.measure(View.MeasureSpec.makeMeasureSpec(root.getWidth(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(root.getHeight(), View.MeasureSpec.EXACTLY));
        root.layout(0, 0, root.getWidth(), root.getHeight());
        Bitmap b = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(b));
        File out = new File(System.getProperty("shots.out"), name + ".png");
        out.getParentFile().mkdirs();
        FileOutputStream fo = new FileOutputStream(out);
        b.compress(Bitmap.CompressFormat.PNG, 100, fo);
        fo.close();
    }

    static Object get(Object o, String f) throws Exception {
        Field x = o.getClass().getDeclaredField(f); x.setAccessible(true); return x.get(o);
    }
    static void set(Object o, String f, Object v) throws Exception {
        Field x = o.getClass().getDeclaredField(f); x.setAccessible(true); x.set(o, v);
    }
    static Object call(Object o, String m, Class<?>[] t, Object... args) throws Exception {
        Method x = o.getClass().getDeclaredMethod(m, t); x.setAccessible(true); return x.invoke(o, args);
    }

    static byte[] presence(int status, int battery, double lat, double lon) {
        Presence p = new Presence();
        p.status = status; p.battery = battery;
        if (lat != 0) { p.hasLocation = true; p.lat = lat; p.lon = lon; }
        return p.encode();
    }

    MainActivity start() throws Exception {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        org.robolectric.shadows.ShadowLooper.idleMainLooper(2, java.util.concurrent.TimeUnit.SECONDS);
        call(a, "saveProfile", new Class<?>[] {String.class, byte[].class}, "Chalid", null);
        return a;
    }

    MainActivity group() throws Exception {
        MainActivity a = start();
        set(a, "hub", new Hub("Chalid", null, a));
        call(a, "setMyStatus", new Class<?>[] {int.class}, Presence.SAFE);
        call(a, "refreshAll", new Class<?>[0]);
        a.onMemberJoined("Budi", null);
        a.onMemberJoined("Sari", null);
        a.onMemberJoined("Pak RT", null);
        a.onPresence("Budi", presence(Presence.SAFE, 78, 0, 0));
        a.onPresence("Sari", presence(Presence.NEED_HELP, 23, 0, 0));
        a.onPresence("Pak RT", presence(Presence.SAFE, 91, 0, 0));
        ShadowLooper.idleMainLooper();
        a.onText("Budi", "Aku sudah di masjid, aman. Air mulai naik di jalan utama.");
        call(a, "addBubble", new Class<?>[] {String.class, String.class, boolean.class},
                "Chalid", "Oke, aku menuju ke sana lewat gang belakang.", true);
        a.onAnnouncement("Pak RT", "Titik kumpul pindah ke lapangan SD 2. Bawa obat dan air minum.");
        a.onText("Sari", "Kakiku terkilir, ada yang bisa jemput?");
        return a;
    }

    @Test public void shots() throws Exception {
        MainActivity sp = Robolectric.buildActivity(MainActivity.class).setup().get();
        snap(sp, "0-splash");
        MainActivity a = start();
        @SuppressWarnings("unchecked") List<WifiP2pDevice> peers = (List<WifiP2pDevice>) get(a, "peers");
        String[][] devs = {{"Galaxy A15 Budi", "3"}, {"Redmi Note 13", "3"}, {"Oppo A78", "1"}};
        for (String[] d : devs) {
            WifiP2pDevice w = new WifiP2pDevice();
            w.deviceName = d[0]; w.deviceAddress = "02:00:00:00:00:0" + d[1]; w.status = Integer.parseInt(d[1]);
            peers.add(w);
        }
        call(a, "refreshPeers", new Class<?>[0]);
        call(a, "setStatus", new Class<?>[] {String.class, int.class}, "3 perangkat ditemukan", android.graphics.Color.rgb(217, 140, 20));
        snap(a, "1-cari-perangkat");

        MainActivity g = group();
        g.onTalkState("Budi", true);
        snap(g, "2-grup-dan-chat");

        MainActivity s = group();
        Location me = new Location("gps"); me.setLatitude(-6.20000); me.setLongitude(106.81660);
        set(s, "myLocation", me);
        s.onPresence("Sari", presence(Presence.SOS, 21, -6.19850, 106.81480));
        snap(s, "3-sos-masuk");

        MainActivity k = group();
        set(k, "myLocation", me);
        k.onPresence("Budi", presence(Presence.SAFE, 78, -6.19780, 106.81720));
        k.onPresence("Sari", presence(Presence.SOS, 21, -6.19850, 106.81480));
        k.onPresence("Pak RT", presence(Presence.SAFE, 91, -6.20310, 106.81950));
        call(k, "showCompass", new Class<?>[0]);
        ShadowLooper.idleMainLooper();
        call(k, "stopHeading", new Class<?>[0]);
        set(k, "headingListener", new android.hardware.SensorEventListener() {
            public void onSensorChanged(android.hardware.SensorEvent e) { }
            public void onAccuracyChanged(android.hardware.Sensor s, int a) { }
        });
        ((RadarView) get(k, "radar")).setHeading(20);
        call(k, "refreshCompass", new Class<?>[0]);
        snap(k, "5-kompas");

        MainActivity m = group();
        set(m, "statusBeforeSos", Presence.SAFE);
        call(m, "setMyStatus", new Class<?>[] {int.class}, Presence.SOS);
        m.onMemberLeft("Budi");
        snap(m, "4-sos-sendiri-dan-terputus");
    }
}
