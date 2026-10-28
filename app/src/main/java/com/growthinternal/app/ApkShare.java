package com.growthinternal.app;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * MeshLive-inspired offline APK distribution server.
 * Serves the installed Growth Internal APK over local Wi-Fi/Hotspot HTTP (port 8989)
 * so teammates in the field without internet can download and install/update the app
 * directly from any browser.
 */
final class ApkShare {
    static final int HTTP_PORT = 8989;

    private ServerSocket server;
    private volatile boolean running;

    synchronized String start(final Context ctx) {
        stop();
        final File apk = apkFile(ctx);
        if (apk == null || !apk.isFile()) return null;
        try {
            ServerSocket ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(HTTP_PORT));
            server = ss;
            running = true;
            Thread t = new Thread(new Runnable() {
                @Override public void run() {
                    acceptLoop(ss, apk);
                }
            }, "apk-share-http");
            t.setDaemon(true);
            t.start();
            InetAddress ip = Lan.localIPv4();
            String host = ip != null ? ip.getHostAddress() : "192.168.49.1";
            return "http://" + host + ":" + HTTP_PORT;
        } catch (IOException e) {
            running = false;
            return null;
        }
    }

    static File apkFile(Context ctx) {
        if (ctx == null || ctx.getApplicationInfo() == null) return null;
        String src = ctx.getApplicationInfo().sourceDir;
        return src != null ? new File(src) : null;
    }

    private void acceptLoop(ServerSocket ss, final File apk) {
        try {
            while (running && !ss.isClosed()) {
                final Socket c = ss.accept();
                Thread w = new Thread(new Runnable() {
                    @Override public void run() {
                        serveClient(c, apk);
                    }
                }, "apk-share-client");
                w.setDaemon(true);
                w.start();
            }
        } catch (IOException ignored) {
        }
    }

    private static void serveClient(Socket c, File apk) {
        try {
            c.setSoTimeout(8000);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(c.getInputStream(), Hub.UTF8));
            String req = in.readLine();
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                // drain request headers
            }
            OutputStream out = c.getOutputStream();
            if (req != null && req.contains("/growth-internal.apk")) {
                long len = apk.length();
                String hdr = "HTTP/1.1 200 OK\r\n"
                        + "Content-Type: application/vnd.android.package-archive\r\n"
                        + "Content-Disposition: attachment; filename=\"growth-internal.apk\"\r\n"
                        + "Content-Length: " + len + "\r\n"
                        + "Connection: close\r\n\r\n";
                out.write(hdr.getBytes(Hub.UTF8));
                FileInputStream fis = new FileInputStream(apk);
                try {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = fis.read(buf)) > 0) {
                        out.write(buf, 0, n);
                    }
                } finally {
                    fis.close();
                }
            } else {
                long kb = Math.max(1L, apk.length() / 1024L);
                String html = "<!doctype html><html><head><meta charset='utf-8'>"
                        + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                        + "<title>Growth Internal Offline</title>"
                        + "<style>body{font-family:sans-serif;background:#131F37;color:#fff;"
                        + "text-align:center;padding:36px 20px}"
                        + ".card{background:#fff;color:#131F37;border-radius:18px;padding:24px;"
                        + "max-width:360px;margin:0 auto}"
                        + "a.btn{display:block;background:#D61E20;color:#fff;text-decoration:none;"
                        + "padding:14px 20px;border-radius:14px;font-weight:bold;margin-top:18px}</style>"
                        + "</head><body><div class='card'><h2>Growth Internal</h2>"
                        + "<p>Instalasi & Update Offline Tim Lapangan (" + kb + " KB)</p>"
                        + "<a class='btn' href='/growth-internal.apk'>Unduh APK Sekarang</a>"
                        + "</div></body></html>";
                byte[] body = html.getBytes(Hub.UTF8);
                String hdr = "HTTP/1.1 200 OK\r\n"
                        + "Content-Type: text/html; charset=utf-8\r\n"
                        + "Content-Length: " + body.length + "\r\n"
                        + "Connection: close\r\n\r\n";
                out.write(hdr.getBytes(Hub.UTF8));
                out.write(body);
            }
            out.flush();
        } catch (IOException ignored) {
        } finally {
            try { c.close(); } catch (IOException ignored) {}
        }
    }

    synchronized void stop() {
        running = false;
        if (server != null) {
            try { server.close(); } catch (IOException ignored) {}
            server = null;
        }
    }
}
