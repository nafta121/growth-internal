package com.growthinternal.app;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In-app GitHub Releases updater with SHA-256 integrity verification.
 * Checks https://api.github.com/repos/nafta121/growth-internal/releases/latest
 * when internet connectivity is available, downloads growth-internal.apk into
 * getCacheDir()/update.apk, verifies its SHA-256 digest, and launches the
 * system package installer via CaptureProvider.UPDATE_APK.
 */
final class Updater {
    static final String REPO = "nafta121/growth-internal";
    static final String API_LATEST = "https://api.github.com/repos/" + REPO + "/releases/latest";
    static final String DEFAULT_APK_URL =
            "https://github.com/" + REPO + "/releases/latest/download/growth-internal.apk";
    static final String DEFAULT_SHA_URL =
            "https://github.com/" + REPO + "/releases/latest/download/growth-internal.apk.sha256";

    private static final Pattern VERSION_CODE_PATTERN =
            Pattern.compile("versionCode\\):\\s*(\\d+)");
    private static final Pattern SHA256_PATTERN =
            Pattern.compile("([0-9a-fA-F]{64})");

    interface CheckCallback {
        void onResult(Release release, boolean isNewer, String error);
    }

    interface DownloadCallback {
        void onProgress(int percent);
        void onReady(File apkFile);
        void onError(String message);
    }

    static final class Release {
        final String versionName;
        final int versionCode;
        final String apkUrl;
        final String shaUrl;
        final String sha256;
        final String notes;

        Release(String versionName, int versionCode, String apkUrl,
                String shaUrl, String sha256, String notes) {
            this.versionName = versionName != null ? versionName : "";
            this.versionCode = versionCode;
            this.apkUrl = apkUrl != null ? apkUrl : DEFAULT_APK_URL;
            this.shaUrl = shaUrl != null ? shaUrl : DEFAULT_SHA_URL;
            this.sha256 = sha256 != null ? sha256.toLowerCase(Locale.US) : "";
            this.notes = notes != null ? notes : "";
        }
    }

    static boolean hasInternet(Context ctx) {
        if (ctx == null) return false;
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            NetworkInfo info = cm.getActiveNetworkInfo();
            return info != null && info.isConnected();
        } catch (RuntimeException e) {
            return false;
        }
    }

    static Release parseReleaseJson(String json) {
        if (json == null || json.trim().isEmpty()) return null;
        try {
            JSONObject obj = new JSONObject(json);
            String tag = obj.optString("tag_name", "").trim();
            if (tag.startsWith("v") || tag.startsWith("V")) {
                tag = tag.substring(1).trim();
            }
            if (tag.isEmpty()) return null;

            String body = obj.optString("body", "");
            int versionCode = 0;
            Matcher vcMatcher = VERSION_CODE_PATTERN.matcher(body);
            if (vcMatcher.find()) {
                try {
                    versionCode = Integer.parseInt(vcMatcher.group(1));
                } catch (NumberFormatException ignored) {}
            }

            String sha256 = "";
            int shaIdx = body.indexOf("SHA-256 APK");
            if (shaIdx >= 0) {
                Matcher sm = SHA256_PATTERN.matcher(body.substring(shaIdx));
                if (sm.find()) sha256 = sm.group(1).toLowerCase(Locale.US);
            }

            String apkUrl = DEFAULT_APK_URL;
            String shaUrl = DEFAULT_SHA_URL;
            JSONArray assets = obj.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.optJSONObject(i);
                    if (a == null) continue;
                    String name = a.optString("name", "");
                    String dl = a.optString("browser_download_url", "");
                    if (dl.isEmpty()) continue;
                    if ("growth-internal.apk".equals(name)) apkUrl = dl;
                    else if ("growth-internal.apk.sha256".equals(name)) shaUrl = dl;
                }
            }

            String notes = extractNotes(body);
            return new Release(tag, versionCode, apkUrl, shaUrl, sha256, notes);
        } catch (Exception e) {
            return null;
        }
    }

    private static String extractNotes(String body) {
        if (body == null || body.isEmpty()) return "";
        String cleaned = body;
        int start = cleaned.indexOf("### Perubahan");
        if (start >= 0) {
            int nextLine = cleaned.indexOf('\n', start);
            if (nextLine >= 0) cleaned = cleaned.substring(nextLine + 1);
        }
        int end = cleaned.indexOf("### Cek keaslian");
        if (end >= 0) cleaned = cleaned.substring(0, end);
        cleaned = cleaned.replace("`", "").trim();
        if (cleaned.length() > 500) {
            cleaned = cleaned.substring(0, 500).trim() + "…";
        }
        return cleaned;
    }

    static boolean isNewer(String localVer, int localCode, Release remote) {
        if (remote == null || remote.versionName.isEmpty()) return false;
        int cmp = compareSemver(localVer, remote.versionName);
        if (cmp < 0) return true;
        if (cmp > 0) return false;
        // Same versionName: compare versionCode only when local build also uses CI numbering (> 100)
        if (localCode >= 100 && remote.versionCode >= 100) {
            return remote.versionCode > localCode;
        }
        return false;
    }

    static int compareSemver(String a, String b) {
        int[] pa = parseSemverParts(a);
        int[] pb = parseSemverParts(b);
        for (int i = 0; i < 3; i++) {
            if (pa[i] != pb[i]) return pa[i] < pb[i] ? -1 : 1;
        }
        return 0;
    }

    private static int[] parseSemverParts(String v) {
        int[] res = new int[3];
        if (v == null) return res;
        String clean = v.trim();
        if (clean.startsWith("v") || clean.startsWith("V")) clean = clean.substring(1);
        int dash = clean.indexOf('-');
        if (dash >= 0) clean = clean.substring(0, dash);
        String[] parts = clean.split("\\.");
        for (int i = 0; i < Math.min(3, parts.length); i++) {
            try {
                res[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException ignored) {}
        }
        return res;
    }

    static String sha256File(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        FileInputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        } finally {
            try { in.close(); } catch (IOException ignored) {}
        }
        return toHex(md.digest());
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format(Locale.US, "%02x", b & 0xFF));
        }
        return sb.toString();
    }

    static void check(final Context ctx, final CheckCallback cb) {
        if (ctx == null || cb == null) return;
        String localVer = "0.0.0";
        int localCode = 0;
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            if (pi.versionName != null) localVer = pi.versionName;
            localCode = pi.versionCode;
        } catch (Exception ignored) {}
        final String fLocalVer = localVer;
        final int fLocalCode = localCode;

        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String json = httpGetText(API_LATEST, 7000);
                    Release rel = parseReleaseJson(json);
                    if (rel == null) {
                        cb.onResult(null, false, "Gagal membaca informasi rilis GitHub");
                        return;
                    }
                    boolean newer = isNewer(fLocalVer, fLocalCode, rel);
                    cb.onResult(rel, newer, null);
                } catch (Exception e) {
                    cb.onResult(null, false, "Tidak dapat menghubungi GitHub: " + e.getMessage());
                }
            }
        }, "github-update-check").start();
    }

    static void download(final Context ctx, final Release rel, final DownloadCallback cb) {
        if (ctx == null || rel == null || cb == null) return;
        new Thread(new Runnable() {
            @Override public void run() {
                File target = CaptureProvider.updateFile(ctx);
                File tmp = new File(ctx.getCacheDir(), "update.apk.part");
                try {
                    String expectedSha = rel.sha256;
                    if (expectedSha == null || expectedSha.length() != 64) {
                        try {
                            String shaTxt = httpGetText(rel.shaUrl, 6000);
                            Matcher m = SHA256_PATTERN.matcher(shaTxt);
                            if (m.find()) expectedSha = m.group(1).toLowerCase(Locale.US);
                        } catch (Exception ignored) {}
                    }

                    HttpURLConnection conn = openFollowedConnection(rel.apkUrl, 10000);
                    int total = conn.getContentLength();
                    InputStream in = conn.getInputStream();
                    FileOutputStream out = new FileOutputStream(tmp);
                    MessageDigest md = MessageDigest.getInstance("SHA-256");
                    try {
                        byte[] buf = new byte[8192];
                        int downloaded = 0;
                        int lastPct = -1;
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            out.write(buf, 0, n);
                            md.update(buf, 0, n);
                            downloaded += n;
                            if (total > 0) {
                                int pct = (int) ((downloaded * 100L) / total);
                                if (pct != lastPct && (pct % 5 == 0 || pct == 100)) {
                                    lastPct = pct;
                                    cb.onProgress(pct);
                                }
                            }
                        }
                        out.flush();
                    } finally {
                        try { out.close(); } catch (IOException ignored) {}
                        try { in.close(); } catch (IOException ignored) {}
                        conn.disconnect();
                    }

                    String actualSha = toHex(md.digest());
                    if (expectedSha != null && expectedSha.length() == 64
                            && !expectedSha.equalsIgnoreCase(actualSha)) {
                        tmp.delete();
                        cb.onError("Verifikasi SHA-256 gagal. File unduhan tidak utuh.");
                        return;
                    }
                    if (target.exists()) target.delete();
                    if (!tmp.renameTo(target)) {
                        cb.onError("Gagal menyimpan file update APK.");
                        return;
                    }
                    cb.onReady(target);
                } catch (Exception e) {
                    tmp.delete();
                    cb.onError("Gagal mengunduh update: " + e.getMessage());
                }
            }
        }, "github-apk-download").start();
    }

    static void installApk(Context ctx) {
        if (ctx == null) return;
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(CaptureProvider.UPDATE_APK, "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
    }

    private static String httpGetText(String urlStr, int timeoutMs) throws IOException {
        HttpURLConnection conn = openFollowedConnection(urlStr, timeoutMs);
        InputStream in = conn.getInputStream();
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), Hub.UTF8);
        } finally {
            try { in.close(); } catch (IOException ignored) {}
            conn.disconnect();
        }
    }

    private static HttpURLConnection openFollowedConnection(String urlStr, int timeoutMs)
            throws IOException {
        String current = urlStr;
        for (int redirects = 0; redirects < 5; redirects++) {
            HttpURLConnection conn = (HttpURLConnection) new URL(current).openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestProperty("User-Agent", "GrowthInternal-Android-Updater");
            conn.setRequestProperty("Accept", "application/vnd.github+json, */*");
            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_MOVED_PERM
                    || code == HttpURLConnection.HTTP_MOVED_TEMP
                    || code == HttpURLConnection.HTTP_SEE_OTHER
                    || code == 307 || code == 308) {
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                if (loc == null || loc.isEmpty()) break;
                current = loc;
                continue;
            }
            if (code != HttpURLConnection.HTTP_OK) {
                conn.disconnect();
                throw new IOException("HTTP " + code);
            }
            return conn;
        }
        throw new IOException("Terlalu banyak pengalihan URL");
    }
}
