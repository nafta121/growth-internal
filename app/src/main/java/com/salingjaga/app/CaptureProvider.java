package com.salingjaga.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Hands the camera app one writable file for a full-size photo or a short clip. Only
 * these two capture files are served, and only to apps the activity grants access to.
 */
public final class CaptureProvider extends ContentProvider {
    static final String AUTHORITY = "com.salingjaga.app.capture";
    static final Uri PHOTO = Uri.parse("content://" + AUTHORITY + "/photo.jpg");
    static final Uri VIDEO = Uri.parse("content://" + AUTHORITY + "/video.mp4");

    static File file(android.content.Context c) {
        return new File(c.getCacheDir(), "capture.jpg");
    }

    static File videoFile(android.content.Context c) {
        return new File(c.getCacheDir(), "capture.mp4");
    }

    private File fileFor(Uri uri) throws FileNotFoundException {
        if (PHOTO.equals(uri)) return file(getContext());
        if (VIDEO.equals(uri)) return videoFile(getContext());
        throw new FileNotFoundException(uri.toString());
    }

    @Override public boolean onCreate() { return true; }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = fileFor(uri);
        int m = mode.contains("w")
                ? ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_CREATE
                        | ParcelFileDescriptor.MODE_TRUNCATE
                : ParcelFileDescriptor.MODE_READ_ONLY;
        return ParcelFileDescriptor.open(f, m);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        File f;
        try {
            f = fileFor(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        MatrixCursor c = new MatrixCursor(new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        c.addRow(new Object[] {uri.getLastPathSegment(), f.length()});
        return c;
    }

    @Override public String getType(Uri uri) {
        if (PHOTO.equals(uri)) return "image/jpeg";
        if (VIDEO.equals(uri)) return "video/mp4";
        return null;
    }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
