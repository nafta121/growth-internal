package com.growthinternal.app;

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

    /** Draws the activity dimmed, with the latest dialog on top, the way it looks on a phone. */
    static void snapDialog(MainActivity a, String name) throws Exception {
        ShadowLooper.idleMainLooper();
        View root = a.getWindow().getDecorView();
        int w = root.getWidth(), h = root.getHeight();
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        root.draw(c);
        c.drawColor(0x99000000);
        android.app.Dialog d = org.robolectric.shadows.ShadowDialog.getLatestDialog();
        View dv = d.getWindow().getDecorView();
        float density = a.getResources().getDisplayMetrics().density;
        int dw = w - Math.round(48 * density);
        dv.measure(View.MeasureSpec.makeMeasureSpec(dw, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h - Math.round(96 * density), View.MeasureSpec.AT_MOST));
        dv.layout(0, 0, dv.getMeasuredWidth(), dv.getMeasuredHeight());
        c.save();
        c.translate((w - dw) / 2f, (h - dv.getMeasuredHeight()) / 2f);
        dv.draw(c);
        c.restore();
        File out = new File(System.getProperty("shots.out"), name + ".png");
        FileOutputStream fo = new FileOutputStream(out);
        b.compress(Bitmap.CompressFormat.PNG, 100, fo);
        fo.close();
        d.dismiss();
        ShadowLooper.idleMainLooper();
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

    static void freshInstall() {
        org.robolectric.RuntimeEnvironment.getApplication()
                .getSharedPreferences("growthinternal", 0).edit().clear().commit();
    }

    MainActivity start() throws Exception {
        freshInstall();
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

        MainActivity e = start();
        snap(e, "6-layar-awal");

        freshInstall();
        org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build.class, "MODEL", "Galaxy A15");
        MainActivity n = Robolectric.buildActivity(MainActivity.class).setup().get();
        ShadowLooper.idleMainLooper(2, java.util.concurrent.TimeUnit.SECONDS);
        snapDialog(n, "7-isi-nama-pertama-kali");

        MainActivity d = group();
        // Dialogs that need a live group check hub.hasPeers(): give the hub one loopback link.
        java.net.ServerSocket ss = new java.net.ServerSocket(0);
        java.net.Socket sock = new java.net.Socket("127.0.0.1", ss.getLocalPort());
        ss.accept();
        Hub dh = (Hub) get(d, "hub");
        @SuppressWarnings("unchecked") List<Link> links = (List<Link>) get(dh, "links");
        links.add(new Link(sock, dh));
        call(d, "toggleSos", new Class<?>[0]);
        snapDialog(d, "8-konfirmasi-sos-dan-sms");
        call(d, "askStatus", new Class<?>[0]);
        snapDialog(d, "9-pilih-status");
        call(d, "askAnnouncement", new Class<?>[0]);
        android.app.Dialog ann = org.robolectric.shadows.ShadowDialog.getLatestDialog();
        ((android.widget.EditText) findEdit(ann.getWindow().getDecorView()))
                .setText("Semua kumpul di lapangan SD 2 sebelum jam 5 sore.");
        snapDialog(d, "10-tulis-pengumuman");
        a.getSharedPreferences("growthinternal", 0).edit().putString("sms_contacts", "081234567890,081398765432").apply();
        call(d, "showProfileMenu", new Class<?>[0]);
        snapDialog(d, "11-menu-profil");
        call(d, "askEmergencyContacts", new Class<?>[] {Runnable.class}, (Object) null);
        snapDialog(d, "12-kontak-darurat-sms");

        MainActivity t = group();
        set(t, "callMode", true);
        call(t, "addSystemMessage", new Class<?>[] {String.class}, "Mode telepon aktif: mikrofon terus menyala.");
        call(t, "refreshControls", new Class<?>[0]);
        t.onTalkState("Sari", true);
        snap(t, "13-mode-telepon");

        MainActivity f = group();
        f.onImage("Budi", sampleJpeg());
        ShadowLooper.idleMainLooper();
        call(f, "addBubble", new Class<?>[] {String.class, String.class, boolean.class},
                "Chalid", "Jalan depan masjid masih bisa dilewati?", true);
        f.onText("Budi", "Bisa, air setinggi lutut. Lewat kiri.");
        ShadowLooper.idleMainLooper();
        snap(f, "14-kirim-foto");

        MainActivity v = group();
        call(v, "addBubble", new Class<?>[] {String.class, String.class, boolean.class},
                "Chalid", "Ada yang bisa rekam kondisi jembatan?", true);
        v.onVideoIncoming("Sari");
        v.onVideo("Sari", new byte[64 * 1024]);
        ShadowLooper.idleMainLooper();
        v.onText("Sari", "Ini 10 detik lalu. Arus deras, jangan lewat sini.");
        ShadowLooper.idleMainLooper();
        snap(v, "15-kirim-video");

        MainActivity p = group();
        set(p, "myLocation", me);
        writeSampleTiles(new java.io.File(p.getFilesDir(), "tiles"), -6.2000, 106.8166);
        p.onPresence("Budi", presence(Presence.SAFE, 78, -6.19780, 106.81720));
        p.onPresence("Sari", presence(Presence.SOS, 21, -6.19850, 106.81480));
        p.onPresence("Pak RT", presence(Presence.SAFE, 91, -6.20310, 106.81950));
        call(p, "showMap", new Class<?>[0]);
        ShadowLooper.idleMainLooper();
        MapView mv = (MapView) get(p, "mapView");
        set(mv, "zoom", 16.2);
        mv.centerOn(-6.2003, 106.8170);
        snap(p, "16-peta-offline");

        MainActivity j = group();
        set(j, "upstreamInfo", Bridge.encode("DIRECT-xy-Pak RT", "rahasia123", "fe80::1"));
        call(j, "askBridge", new Class<?>[0]);
        snapDialog(j, "17-jadi-jembatan");
    }

    /**
     * Drawn street tiles standing in for downloaded OpenStreetMap tiles (the test has
     * no internet). Roads follow a world grid so neighbouring tiles line up.
     */
    static void writeSampleTiles(java.io.File root, double lat, double lon) throws Exception {
        for (int z = 14; z <= 16; z++) {
            int cx = (int) Tiles.lonToX(lon, z), cy = (int) Tiles.latToY(lat, z);
            int r = z == 16 ? 3 : 2;
            for (int x = cx - r; x <= cx + r; x++) {
                for (int y = cy - r; y <= cy + r; y++) {
                    java.io.File f = Tiles.file(root, z, x, y);
                    f.getParentFile().mkdirs();
                    java.io.FileOutputStream out = new java.io.FileOutputStream(f);
                    sampleTile(z, x, y).compress(Bitmap.CompressFormat.PNG, 100, out);
                    out.close();
                }
            }
        }
    }

    static Bitmap sampleTile(int z, int tx, int ty) {
        Bitmap b = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        c.drawColor(0xFFF2EFE9);
        double k = Math.pow(2, 16 - z);              // world pixels at zoom 16 per pixel here
        double ox = tx * 256 * k, oy = ty * 256 * k;
        // Blocks, parks and buildings on a 160 px grid at zoom 16.
        int g = 160;
        for (long bx = (long) Math.floor(ox / g) - 1; bx * g < ox + 256 * k + g; bx++) {
            for (long by = (long) Math.floor(oy / g) - 1; by * g < oy + 256 * k + g; by++) {
                long h = (bx * 73856093L) ^ (by * 19349663L);
                float l = (float) ((bx * g - ox) / k), t = (float) ((by * g - oy) / k);
                float s = (float) (g / k);
                if (Math.floorMod(h, 7) == 0) {
                    p.setColor(0xFFCDEBC0);
                    c.drawRect(l + 4 / (float) k, t + 4 / (float) k, l + s - 4 / (float) k, t + s - 4 / (float) k, p);
                } else if (z >= 15) {
                    p.setColor(0xFFDFD8CE);
                    for (int i = 0; i < 4; i++) {
                        float bl = l + s * (0.12f + 0.42f * (i % 2)), bt = t + s * (0.12f + 0.42f * (i / 2));
                        c.drawRect(bl, bt, bl + s * 0.32f, bt + s * 0.32f, p);
                    }
                }
            }
        }
        // River.
        p.setStyle(android.graphics.Paint.Style.STROKE);
        p.setColor(0xFFAAD3DF);
        p.setStrokeWidth((float) (34 / k) + 2);
        android.graphics.Path river = new android.graphics.Path();
        for (int i = -10; i <= 266; i += 4) {
            double wx = ox + i * k;
            double wy = 17550000 + 260 * Math.sin(wx / 900.0) + (wx - 13460000) * 0.35;
            float px = i, py = (float) ((wy - oy) / k);
            if (i == -10) river.moveTo(px, py); else river.lineTo(px, py);
        }
        c.drawPath(river, p);
        // Roads: minor every grid line, main every fourth.
        for (int pass = 0; pass < 2; pass++) {
            for (long gx = (long) Math.floor(ox / g); gx * g <= ox + 256 * k; gx++) {
                boolean main = Math.floorMod(gx, 4) == 0;
                if (!main && z < 15) continue;
                float x = (float) ((gx * g - ox) / k);
                road(c, p, pass, main, k, x, -2, x, 258);
            }
            for (long gy = (long) Math.floor(oy / g); gy * g <= oy + 256 * k; gy++) {
                boolean main = Math.floorMod(gy, 4) == 0;
                if (!main && z < 15) continue;
                float y = (float) ((gy * g - oy) / k);
                road(c, p, pass, main, k, -2, y, 258, y);
            }
        }
        return b;
    }

    static void road(Canvas c, android.graphics.Paint p, int pass, boolean main, double k,
            float x0, float y0, float x1, float y1) {
        float w = (float) ((main ? 22 : 12) / k) + 1;
        p.setStyle(android.graphics.Paint.Style.STROKE);
        p.setStrokeCap(android.graphics.Paint.Cap.BUTT);
        if (pass == 0) {
            p.setColor(main ? 0xFFE0B470 : 0xFFD0CBC2);
            p.setStrokeWidth(w + 2);
        } else {
            p.setColor(main ? 0xFFFCD68A : 0xFFFFFFFF);
            p.setStrokeWidth(w);
        }
        c.drawLine(x0, y0, x1, y1, p);
    }

    /** A simple drawn street scene standing in for a phone photo. */
    static byte[] sampleJpeg() {
        Bitmap b = Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        p.setShader(new android.graphics.LinearGradient(0, 0, 0, 500, 0xFF9EC9E8, 0xFFDDEBF2,
                android.graphics.Shader.TileMode.CLAMP));
        c.drawRect(0, 0, 1200, 560, p);
        p.setShader(null);
        p.setColor(0xFFB9A88F); c.drawRect(120, 260, 520, 600, p);
        p.setColor(0xFF8C3B2E);
        android.graphics.Path roof = new android.graphics.Path();
        roof.moveTo(90, 270); roof.lineTo(320, 130); roof.lineTo(550, 270); roof.close();
        c.drawPath(roof, p);
        p.setColor(0xFFE8E2D5); c.drawRect(640, 220, 1080, 600, p);
        p.setColor(0xFF2F6F4E); c.drawRect(780, 120, 940, 230, p);
        p.setColor(0xFF5B4A3A);
        for (int x = 680; x < 1060; x += 90) c.drawRect(x, 300, x + 50, 380, p);
        p.setShader(new android.graphics.LinearGradient(0, 540, 0, 900, 0xFF9C8562, 0xFF6E5B40,
                android.graphics.Shader.TileMode.CLAMP));
        c.drawRect(0, 540, 1200, 900, p);
        p.setShader(null);
        p.setColor(0x55FFFFFF);
        for (int y = 600; y < 900; y += 60) c.drawRect(40 + (y % 120), y, 360 + (y % 120), y + 6, p);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, 80, out);
        return out.toByteArray();
    }

    static View findEdit(View v) {
        if (v instanceof android.widget.EditText) return v;
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View r = findEdit(g.getChildAt(i));
                if (r != null) return r;
            }
        }
        return null;
    }
}
