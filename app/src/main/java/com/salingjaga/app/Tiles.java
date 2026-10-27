package com.salingjaga.app;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenStreetMap raster tiles saved on the phone for use without internet.
 * The area is kept small on purpose: the OSM tile policy forbids downloading
 * more than 250 tiles at zoom 13 or higher for offline use.
 */
final class Tiles {
    static final int SIZE = 256;
    static final int MIN_ZOOM = 10;
    static final int MAX_ZOOM = 16;
    static final double RADIUS_KM = 3;
    static final int MAX_DETAILED = 240;   // tiles at zoom >= 13
    static final String USER_AGENT = "SalingJaga/1 (+https://github.com/chalidade/saling-jaga)";

    private Tiles() { }

    /** Tile x at zoom z, as a fraction so callers can place points inside a tile. */
    static double lonToX(double lon, int z) {
        return (lon + 180) / 360 * (1 << z);
    }

    static double latToY(double lat, int z) {
        double r = Math.toRadians(Math.max(-85.0511, Math.min(85.0511, lat)));
        return (1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2 * (1 << z);
    }

    static double xToLon(double x, int z) {
        return x / (1 << z) * 360 - 180;
    }

    static double yToLat(double y, int z) {
        double n = Math.PI - 2 * Math.PI * y / (1 << z);
        return Math.toDegrees(Math.atan(Math.sinh(n)));
    }

    /** Every tile within radiusKm of the point, zoom MIN_ZOOM..maxZoom, as {z, x, y}. */
    static List<int[]> around(double lat, double lon, double radiusKm, int maxZoom) {
        List<int[]> out = new ArrayList<int[]>();
        double dLat = radiusKm / 111.32;
        double dLon = radiusKm / (111.32 * Math.max(0.05, Math.cos(Math.toRadians(lat))));
        for (int z = MIN_ZOOM; z <= maxZoom; z++) {
            int max = (1 << z) - 1;
            int x0 = clamp((int) Math.floor(lonToX(lon - dLon, z)), max);
            int x1 = clamp((int) Math.floor(lonToX(lon + dLon, z)), max);
            int y0 = clamp((int) Math.floor(latToY(lat + dLat, z)), max);
            int y1 = clamp((int) Math.floor(latToY(lat - dLat, z)), max);
            for (int x = x0; x <= x1; x++) {
                for (int y = y0; y <= y1; y++) out.add(new int[] {z, x, y});
            }
        }
        return out;
    }

    /** The area to download: as deep as the policy allows, down to zoom 14. */
    static List<int[]> plan(double lat, double lon) {
        for (int maxZoom = MAX_ZOOM; maxZoom > 14; maxZoom--) {
            List<int[]> t = around(lat, lon, RADIUS_KM, maxZoom);
            if (detailed(t) <= MAX_DETAILED) return t;
        }
        return around(lat, lon, RADIUS_KM, 14);
    }

    static int detailed(List<int[]> tiles) {
        int n = 0;
        for (int[] t : tiles) if (t[0] >= 13) n++;
        return n;
    }

    static File file(File root, int z, int x, int y) {
        return new File(root, z + "/" + x + "/" + y + ".png");
    }

    /** Number of tiles saved on this phone. */
    static int count(File root) {
        int n = 0;
        File[] zs = root.listFiles();
        if (zs == null) return 0;
        for (File z : zs) {
            File[] xs = z.listFiles();
            if (xs == null) continue;
            for (File x : xs) {
                String[] ys = x.list();
                if (ys == null) continue;
                for (String y : ys) if (y.endsWith(".png")) n++;
            }
        }
        return n;
    }

    interface Progress {
        /** Return false to stop. */
        boolean onTile(int done, int total);
    }

    /**
     * Downloads the missing tiles one at a time (the OSM policy allows at most two
     * connections). Returns how many tiles could not be fetched.
     */
    static int download(File root, List<int[]> tiles, Progress progress) {
        int failed = 0;
        int done = 0;
        for (int[] t : tiles) {
            File f = file(root, t[0], t[1], t[2]);
            if (!f.exists() && !fetch(t, f)) failed++;
            done++;
            if (!progress.onTile(done, tiles.size())) break;
        }
        return failed;
    }

    private static boolean fetch(int[] t, File dest) {
        HttpURLConnection c = null;
        try {
            URL u = new URL("https://tile.openstreetmap.org/" + t[0] + "/" + t[1] + "/" + t[2] + ".png");
            c = (HttpURLConnection) u.openConnection();
            c.setRequestProperty("User-Agent", USER_AGENT);
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            if (c.getResponseCode() != 200) return false;
            dest.getParentFile().mkdirs();
            File tmp = new File(dest.getPath() + ".part");
            tmp.deleteOnExit();
            InputStream in = c.getInputStream();
            OutputStream out = new FileOutputStream(tmp);
            try {
                byte[] buf = new byte[8192];
                int n;
                int total = 0;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > 512 * 1024) {
                        tmp.delete();
                        return false;
                    }
                    out.write(buf, 0, n);
                }
            } finally {
                out.close();
                in.close();
            }
            return tmp.renameTo(dest);
        } catch (IOException e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static int clamp(int v, int max) {
        return Math.max(0, Math.min(max, v));
    }
}
