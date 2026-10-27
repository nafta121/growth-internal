package com.growthinternal.app;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Photos shared in the group: shrunk before sending, decoded defensively on receipt. */
final class Photos {
    static final int MAX_BYTES = 400 * 1024;
    static final int MAX_SIDE = 1280;
    // Received images larger than this are refused, so a peer cannot exhaust memory.
    private static final int MAX_DECODE_SIDE = 4096;

    private Photos() { }

    /** Loads a picked or captured photo, fixes its rotation and re-encodes it under MAX_BYTES. */
    static byte[] prepare(ContentResolver cr, Uri uri) throws IOException {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        InputStream in = cr.openInputStream(uri);
        if (in == null) throw new IOException("no stream");
        try { BitmapFactory.decodeStream(in, null, o); } finally { in.close(); }
        if (o.outWidth <= 0 || o.outHeight <= 0) throw new IOException("not an image");
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        in = cr.openInputStream(uri);
        Bitmap b;
        try { b = BitmapFactory.decodeStream(in, null, d); } finally { in.close(); }
        if (b == null) throw new IOException("decode failed");
        b = fit(b, MAX_SIDE, rotation(cr, uri));
        return encode(b);
    }

    static byte[] encode(Bitmap b) {
        int q = 80;
        while (true) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.JPEG, q, out);
            if (out.size() <= MAX_BYTES || q <= 30) {
                if (out.size() <= MAX_BYTES) return out.toByteArray();
                // Still too big at low quality: halve the size and try again.
                b = Bitmap.createScaledBitmap(b, b.getWidth() / 2, b.getHeight() / 2, true);
                q = 70;
                continue;
            }
            q -= 10;
        }
    }

    /** Decodes a received photo no wider than maxSide, or null if it is not a sane image. */
    static Bitmap decode(byte[] jpeg, int maxSide) {
        if (jpeg == null || jpeg.length == 0) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
        if (o.outWidth <= 0 || o.outHeight <= 0
                || o.outWidth > MAX_DECODE_SIDE || o.outHeight > MAX_DECODE_SIDE) return null;
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, d);
    }

    private static Bitmap fit(Bitmap b, int maxSide, int degrees) {
        float scale = Math.min(1f, (float) maxSide / Math.max(b.getWidth(), b.getHeight()));
        if (scale == 1f && degrees == 0) return b;
        Matrix m = new Matrix();
        m.postScale(scale, scale);
        m.postRotate(degrees);
        return Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
    }

    /**
     * Camera photos are often stored sideways with an EXIF rotation tag. Reading EXIF from a
     * stream needs API 24, so it is called by reflection; older phones just skip the fix.
     */
    private static int rotation(ContentResolver cr, Uri uri) {
        InputStream in = null;
        try {
            in = cr.openInputStream(uri);
            Class<?> c = Class.forName("android.media.ExifInterface");
            Object exif = c.getConstructor(InputStream.class).newInstance(in);
            int o = (Integer) c.getMethod("getAttributeInt", String.class, int.class)
                    .invoke(exif, "Orientation", 1);
            switch (o) {
                case 6: return 90;
                case 3: return 180;
                case 8: return 270;
                default: return 0;
            }
        } catch (Exception e) {
            return 0;
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) { }
        }
    }
}
