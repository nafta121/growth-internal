package com.salingjaga.app;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Round profile pictures: the member's photo, or their initial on a colour. */
final class Avatars {
    private static final int[] PALETTE = {
            Color.rgb(31, 122, 69), Color.rgb(37, 99, 235), Color.rgb(147, 51, 234),
            Color.rgb(219, 39, 119), Color.rgb(234, 88, 12), Color.rgb(101, 163, 13),
    };
    static final int PHOTO_PX = 160;

    private Avatars() { }

    static Bitmap circle(String name, byte[] photo, int size) {
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Bitmap src = decodeSmall(photo);
        if (src != null) {
            BitmapShader sh = new BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            Matrix m = new Matrix();
            float scale = (float) size / Math.min(src.getWidth(), src.getHeight());
            m.setScale(scale, scale);
            sh.setLocalMatrix(m);
            p.setShader(sh);
            c.drawCircle(size / 2f, size / 2f, size / 2f, p);
            return out;
        }
        String n = name == null ? "?" : name.trim();
        p.setColor(PALETTE[(n.hashCode() & 0x7fffffff) % PALETTE.length]);
        c.drawCircle(size / 2f, size / 2f, size / 2f, p);
        String initial = n.length() == 0 ? "?" : n.substring(0, 1).toUpperCase();
        p.setColor(Color.WHITE);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextSize(size * 0.44f);
        Rect b = new Rect();
        p.getTextBounds(initial, 0, initial.length(), b);
        c.drawText(initial, size / 2f - b.exactCenterX(), size / 2f - b.exactCenterY(), p);
        return out;
    }

    /** Decodes a received avatar, refusing oversized images that could exhaust memory. */
    private static Bitmap decodeSmall(byte[] photo) {
        if (photo == null || photo.length == 0) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(photo, 0, photo.length, o);
        if (o.outWidth <= 0 || o.outHeight <= 0 || o.outWidth > 512 || o.outHeight > 512) return null;
        return BitmapFactory.decodeByteArray(photo, 0, photo.length);
    }

    /** Loads an image the user picked, crops it square and shrinks it to a few KB. */
    static byte[] fromUri(ContentResolver cr, Uri uri) throws IOException {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        InputStream in = cr.openInputStream(uri);
        try { BitmapFactory.decodeStream(in, null, o); } finally { in.close(); }
        int sample = 1;
        while (Math.min(o.outWidth, o.outHeight) / (sample * 2) >= PHOTO_PX) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        in = cr.openInputStream(uri);
        Bitmap full;
        try { full = BitmapFactory.decodeStream(in, null, o); } finally { in.close(); }
        if (full == null) throw new IOException("Gambar tidak bisa dibaca");
        int side = Math.min(full.getWidth(), full.getHeight());
        Bitmap sq = Bitmap.createBitmap(full, (full.getWidth() - side) / 2,
                (full.getHeight() - side) / 2, side, side);
        Bitmap small = Bitmap.createScaledBitmap(sq, PHOTO_PX, PHOTO_PX, true);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        small.compress(Bitmap.CompressFormat.JPEG, 75, bos);
        return bos.toByteArray();
    }
}
