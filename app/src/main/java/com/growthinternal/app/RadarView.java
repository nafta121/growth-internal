package com.growthinternal.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.location.Location;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Radar centred on this phone: each member is drawn at their real compass bearing,
 * rotated so the top of the screen is the direction the user is facing.
 */
final class RadarView extends View {
    static final class Target {
        final String name;
        final Bitmap avatar;
        final int color;
        final double lat, lon;

        Target(String name, Bitmap avatar, int color, double lat, double lon) {
            this.name = name;
            this.avatar = avatar;
            this.color = color;
            this.lat = lat;
            this.lon = lon;
        }
    }

    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private List<Target> targets = new ArrayList<Target>();
    private Location me;
    private float heading;
    private final int accent;
    private final int line;
    private final int sub;
    private final int text;

    RadarView(Context c, int accent, int line, int sub, int text) {
        super(c);
        this.accent = accent;
        this.line = line;
        this.sub = sub;
        this.text = text;
        density = c.getResources().getDisplayMetrics().density;
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(density);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setTextAlign(Paint.Align.CENTER);
        small.setTextAlign(Paint.Align.CENTER);
    }

    void setData(Location me, List<Target> targets) {
        this.me = me;
        this.targets = targets;
        invalidate();
    }

    void setHeading(float degrees) {
        if (Math.abs(degrees - heading) < 1f) return;
        heading = degrees;
        invalidate();
    }

    @Override protected void onMeasure(int w, int h) {
        int size = MeasureSpec.getSize(w);
        setMeasuredDimension(size, size);
    }

    private float dp(float v) { return v * density; }

    static String formatDistance(float m) {
        return m < 1000 ? Math.round(m) + " m" : String.format(Locale.getDefault(), "%.1f km", m / 1000f);
    }

    @Override protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float r = Math.min(cx, cy) - dp(30);

        fill.setColor(Color.WHITE);
        c.drawCircle(cx, cy, r, fill);

        // Distances of members, to choose the scale (square root keeps near ones apart).
        float max = 50;
        float[] res = new float[2];
        List<float[]> placed = new ArrayList<float[]>();
        for (Target t : targets) {
            if (me == null) break;
            Location.distanceBetween(me.getLatitude(), me.getLongitude(), t.lat, t.lon, res);
            placed.add(new float[] {res[0], res[1]});
            max = Math.max(max, res[0]);
        }
        max *= 1.1f;

        ring.setColor(line);
        small.setColor(sub);
        small.setTextSize(dp(11));
        for (int i = 1; i <= 3; i++) {
            float f = i / 3f;
            c.drawCircle(cx, cy, r * f, ring);
            if (me != null) c.drawText(formatDistance(max * f * f), cx, cy + r * f - dp(5), small);
        }

        // Cardinal points turn with the phone.
        label.setTextSize(dp(15));
        String[] names = {"U", "T", "S", "B"};
        for (int i = 0; i < 4; i++) {
            double a = Math.toRadians(i * 90 - heading);
            float x = cx + (float) Math.sin(a) * (r + dp(16));
            float y = cy - (float) Math.cos(a) * (r + dp(16)) + dp(5);
            label.setColor(i == 0 ? 0xFFD61E20 : sub);
            c.drawText(names[i], x, y, label);
        }

        // "You are facing" marker at the top.
        fill.setColor(accent);
        android.graphics.Path p = new android.graphics.Path();
        p.moveTo(cx, cy - dp(14));
        p.lineTo(cx - dp(8), cy + dp(8));
        p.lineTo(cx, cy + dp(3));
        p.lineTo(cx + dp(8), cy + dp(8));
        p.close();
        c.drawPath(p, fill);

        if (me == null) return;
        label.setTextSize(dp(12));
        float av = dp(36);
        for (int i = 0; i < placed.size(); i++) {
            Target t = targets.get(i);
            float d = placed.get(i)[0];
            double a = Math.toRadians(placed.get(i)[1] - heading);
            float rr = r * (float) Math.sqrt(d / max);
            float x = cx + (float) Math.sin(a) * rr;
            float y = cy - (float) Math.cos(a) * rr;
            fill.setColor(t.color);
            c.drawCircle(x, y, av / 2 + dp(3), fill);
            c.drawBitmap(Bitmap.createScaledBitmap(t.avatar, (int) av, (int) av, true),
                    x - av / 2, y - av / 2, null);
            label.setColor(text);
            c.drawText(t.name + " · " + formatDistance(d), x, y + av / 2 + dp(16), label);
        }
    }
}
