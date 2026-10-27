package com.salingjaga.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.location.Location;
import android.util.LruCache;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Street map drawn from tiles saved on the phone, with every member who shares a
 * location. Drag to move, pinch or double tap to zoom. Where a detailed tile is
 * missing, a coarser saved tile is enlarged instead.
 */
final class MapView extends View {
    private static final double MAX_VIEW_ZOOM = 18;

    private final File root;
    private final float density;
    private final float tileBase;
    private final int textColor, subColor, accent;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(48);
    private final Set<String> missing = new HashSet<String>();
    private final ScaleGestureDetector scaler;
    private final GestureDetector gestures;

    private double centerLat = -6.2, centerLon = 106.82, zoom = 15;
    private boolean centered;
    private Location me;
    private List<RadarView.Target> targets = new ArrayList<RadarView.Target>();
    private boolean drewTile;

    MapView(Context c, File root, int textColor, int subColor, int accent) {
        super(c);
        this.root = root;
        this.textColor = textColor;
        this.subColor = subColor;
        this.accent = accent;
        density = c.getResources().getDisplayMetrics().density;
        tileBase = Tiles.SIZE * Math.max(1f, density / 2f);
        scaler = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                setZoom(zoom + Math.log(d.getScaleFactor()) / Math.log(2));
                return true;
            }
        });
        gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }

            @Override public boolean onScroll(MotionEvent a, MotionEvent b, float dx, float dy) {
                pan(dx, dy);
                return true;
            }

            @Override public boolean onDoubleTap(MotionEvent e) {
                setZoom(Math.floor(zoom) + 1);
                return true;
            }
        });
    }

    void setData(Location me, List<RadarView.Target> targets) {
        this.me = me;
        this.targets = targets;
        if (!centered) {
            if (me != null) centerOn(me.getLatitude(), me.getLongitude());
            else if (!targets.isEmpty()) centerOn(targets.get(0).lat, targets.get(0).lon);
        }
        invalidate();
    }

    void centerOn(double lat, double lon) {
        centerLat = lat;
        centerLon = lon;
        centered = true;
        invalidate();
    }

    void zoomBy(int steps) {
        setZoom(Math.round(zoom) + steps);
    }

    /** Forget cached misses after new tiles were downloaded. */
    void reload() {
        missing.clear();
        invalidate();
    }

    double centerLat() { return centerLat; }
    double centerLon() { return centerLon; }

    private void setZoom(double z) {
        zoom = Math.max(Tiles.MIN_ZOOM, Math.min(MAX_VIEW_ZOOM, z));
        invalidate();
    }

    private int tileZoom() {
        return (int) Math.max(Tiles.MIN_ZOOM, Math.min(Tiles.MAX_ZOOM, Math.round(zoom)));
    }

    private float tilePx(int tz) {
        return (float) (tileBase * Math.pow(2, zoom - tz));
    }

    private void pan(float dx, float dy) {
        int tz = tileZoom();
        float tp = tilePx(tz);
        double cx = Tiles.lonToX(centerLon, tz) + dx / tp;
        double cy = Tiles.latToY(centerLat, tz) + dy / tp;
        double max = 1 << tz;
        cx = (cx % max + max) % max;
        cy = Math.max(0, Math.min(max, cy));
        centerLon = Tiles.xToLon(cx, tz);
        centerLat = Tiles.yToLat(cy, tz);
        invalidate();
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        scaler.onTouchEvent(e);
        if (!scaler.isInProgress()) gestures.onTouchEvent(e);
        getParent().requestDisallowInterceptTouchEvent(true);
        return true;
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        c.drawColor(0xFFECEAE4);
        int tz = tileZoom();
        float tp = tilePx(tz);
        double cx = Tiles.lonToX(centerLon, tz), cy = Tiles.latToY(centerLat, tz);
        int x0 = (int) Math.floor(cx - w / 2f / tp), x1 = (int) Math.floor(cx + w / 2f / tp);
        int y0 = (int) Math.floor(cy - h / 2f / tp), y1 = (int) Math.floor(cy + h / 2f / tp);
        int max = 1 << tz;
        drewTile = false;
        RectF dst = new RectF();
        for (int x = x0; x <= x1; x++) {
            for (int y = Math.max(0, y0); y <= Math.min(max - 1, y1); y++) {
                float left = (float) (w / 2f + (x - cx) * tp), top = (float) (h / 2f + (y - cy) * tp);
                dst.set(left, top, left + tp, top + tp);
                drawTile(c, tz, ((x % max) + max) % max, y, dst);
            }
        }
        if (!drewTile) drawEmpty(c, w, h);
        for (RadarView.Target t : targets) drawTarget(c, t, tz, tp, cx, cy, w, h);
        if (me != null) drawMe(c, tz, tp, cx, cy, w, h);
        drawAttribution(c, h);
    }

    /** Draws the tile, or the part of the nearest saved coarser tile that covers it. */
    private void drawTile(Canvas c, int z, int x, int y, RectF dst) {
        for (int k = 0; k <= 4 && z - k >= Tiles.MIN_ZOOM; k++) {
            Bitmap b = load(z - k, x >> k, y >> k);
            if (b == null) continue;
            int part = Tiles.SIZE >> k;
            int sx = (x & ((1 << k) - 1)) * part, sy = (y & ((1 << k) - 1)) * part;
            c.drawBitmap(b, new Rect(sx, sy, sx + part, sy + part), dst, paint);
            drewTile = true;
            return;
        }
    }

    private Bitmap load(int z, int x, int y) {
        String key = z + "/" + x + "/" + y;
        Bitmap b = cache.get(key);
        if (b != null || missing.contains(key)) return b;
        File f = Tiles.file(root, z, x, y);
        b = f.exists() ? BitmapFactory.decodeFile(f.getPath()) : null;
        if (b == null) missing.add(key);
        else cache.put(key, b);
        return b;
    }

    private void drawEmpty(Canvas c, int w, int h) {
        paint.setColor(0xFFDAD7CF);
        paint.setStrokeWidth(1);
        float step = 48 * density;
        for (float x = 0; x < w; x += step) c.drawLine(x, 0, x, h, paint);
        for (float y = 0; y < h; y += step) c.drawLine(0, y, w, y, paint);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextSize(15 * density);
        paint.setColor(textColor);
        c.drawText("Peta wilayah ini belum diunduh", w / 2f, h / 2f - 10 * density, paint);
        paint.setTypeface(Typeface.DEFAULT);
        paint.setTextSize(13 * density);
        paint.setColor(subColor);
        c.drawText("Titik anggota tetap tampil di sini", w / 2f, h / 2f + 12 * density, paint);
    }

    private void drawTarget(Canvas c, RadarView.Target t, int tz, float tp, double cx, double cy, int w, int h) {
        float px = (float) (w / 2f + (Tiles.lonToX(t.lon, tz) - cx) * tp);
        float py = (float) (h / 2f + (Tiles.latToY(t.lat, tz) - cy) * tp);
        float r = 19 * density;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x33000000);
        c.drawCircle(px, py + 2 * density, r + 3 * density, paint);
        paint.setColor(t.color);
        c.drawCircle(px, py, r + 3 * density, paint);
        if (t.avatar != null) {
            c.drawBitmap(t.avatar, null, new RectF(px - r, py - r, px + r, py + r), paint);
        }
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextSize(12 * density);
        float tw = paint.measureText(t.name);
        float ty = py + r + 18 * density;
        paint.setColor(0xE6FFFFFF);
        c.drawRoundRect(new RectF(px - tw / 2 - 6 * density, ty - 13 * density,
                px + tw / 2 + 6 * density, ty + 5 * density), 8 * density, 8 * density, paint);
        paint.setColor(textColor);
        c.drawText(t.name, px, ty, paint);
    }

    private void drawMe(Canvas c, int tz, float tp, double cx, double cy, int w, int h) {
        float px = (float) (w / 2f + (Tiles.lonToX(me.getLongitude(), tz) - cx) * tp);
        float py = (float) (h / 2f + (Tiles.latToY(me.getLatitude(), tz) - cy) * tp);
        // Accuracy circle, in pixels at this zoom.
        double metersPerTile = 40075016.686 * Math.cos(Math.toRadians(me.getLatitude())) / (1 << tz);
        float acc = (float) (me.getAccuracy() / metersPerTile * tp);
        paint.setStyle(Paint.Style.FILL);
        if (acc > 12 * density) {
            paint.setColor((accent & 0x00FFFFFF) | 0x22000000);
            c.drawCircle(px, py, acc, paint);
        }
        paint.setColor(0xFFFFFFFF);
        c.drawCircle(px, py, 10 * density, paint);
        paint.setColor(accent);
        c.drawCircle(px, py, 7 * density, paint);
    }

    private void drawAttribution(Canvas c, int h) {
        String a = "© OpenStreetMap contributors";
        paint.setTypeface(Typeface.DEFAULT);
        paint.setTextSize(10 * density);
        paint.setTextAlign(Paint.Align.LEFT);
        float tw = paint.measureText(a);
        paint.setColor(0xCCFFFFFF);
        c.drawRect(0, h - 16 * density, tw + 10 * density, h, paint);
        paint.setColor(subColor);
        c.drawText(a, 5 * density, h - 4 * density, paint);
    }
}
