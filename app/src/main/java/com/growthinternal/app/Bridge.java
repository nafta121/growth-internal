package com.growthinternal.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Build;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

/**
 * Multihop plumbing. A phone can be in only one Wi-Fi Direct group, so a bridge
 * owns its own group for the phones further away and joins the other group's
 * Wi-Fi network as an ordinary Wi-Fi client.
 *
 * Every group owner has the address 192.168.49.1, including the bridge itself, so
 * the uplink goes to the far owner's IPv6 link-local address, which is unique.
 */
final class Bridge {
    static final class Info {
        final String ssid, pass, ipv6;

        Info(String ssid, String pass, String ipv6) {
            this.ssid = ssid;
            this.pass = pass;
            this.ipv6 = ipv6;
        }
    }

    interface Listener {
        /** The other group's network is up; target is its owner, scoped to that network. */
        void onNetwork(Network network, InetAddress target);
        void onFailed(String reason);
    }

    static byte[] encode(String ssid, String pass, String ipv6) {
        return (ssid + "\n" + pass + "\n" + ipv6).getBytes(Hub.UTF8);
    }

    /** Null if the payload is not three lines with a network name, passphrase and IPv6 address. */
    static Info decode(byte[] payload) {
        if (payload == null) return null;
        String[] p = new String(payload, Hub.UTF8).split("\n", -1);
        if (p.length != 3 || p[0].isEmpty() || p[1].length() < 8 || p[2].isEmpty()) return null;
        if (!p[2].matches("[0-9a-fA-F:]+")) return null;
        return new Info(p[0], p[1], p[2]);
    }

    /** The IPv6 link-local address of the interface that carries the given group address. */
    static Inet6Address linkLocalOf(InetAddress groupAddress) {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                boolean carries = false;
                Inet6Address local = null;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a.equals(groupAddress)) carries = true;
                    if (a instanceof Inet6Address && a.isLinkLocalAddress()) local = (Inet6Address) a;
                }
                if (carries && local != null) return local;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    static String withoutScope(Inet6Address a) {
        String s = a.getHostAddress();
        int i = s.indexOf('%');
        return i < 0 ? s : s.substring(0, i);
    }

    private final Context context;
    private final ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback callback;
    private int legacyNetworkId = -1;

    Bridge(Context c) {
        context = c.getApplicationContext();
        cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    void join(final Info info, final Listener listener) {
        leave();
        NetworkRequest.Builder rb = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                // WifiNetworkSpecifier is API 29; we compile against API 23.
                Class<?> bc = Class.forName("android.net.wifi.WifiNetworkSpecifier$Builder");
                Object b = bc.newInstance();
                bc.getMethod("setSsid", String.class).invoke(b, info.ssid);
                bc.getMethod("setWpa2Passphrase", String.class).invoke(b, info.pass);
                Object spec = bc.getMethod("build").invoke(b);
                NetworkRequest.Builder.class
                        .getMethod("setNetworkSpecifier", Class.forName("android.net.NetworkSpecifier"))
                        .invoke(rb, spec);
            } else {
                WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
                WifiConfiguration wc = new WifiConfiguration();
                wc.SSID = "\"" + info.ssid + "\"";
                wc.preSharedKey = "\"" + info.pass + "\"";
                legacyNetworkId = wm.addNetwork(wc);
                if (legacyNetworkId < 0 || !wm.enableNetwork(legacyNetworkId, true)) {
                    listener.onFailed("Wi‑Fi grup lain tidak bisa ditambahkan");
                    return;
                }
            }
        } catch (Exception e) {
            listener.onFailed("HP ini belum mendukung jembatan");
            return;
        }
        callback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                try {
                    LinkProperties lp = cm.getLinkProperties(network);
                    NetworkInterface ni = lp == null ? null : NetworkInterface.getByName(lp.getInterfaceName());
                    byte[] raw = InetAddress.getByName(info.ipv6).getAddress();
                    listener.onNetwork(network, Inet6Address.getByAddress(null, raw, ni));
                } catch (Exception e) {
                    listener.onFailed("Alamat grup lain tidak bisa dipakai");
                }
            }

            // API 26+; the user declined the connection prompt or the network was not found.
            public void onUnavailable() {
                listener.onFailed("Wi‑Fi grup lain tidak ditemukan atau ditolak");
            }
        };
        cm.requestNetwork(rb.build(), callback);
    }

    void leave() {
        if (callback != null) {
            try { cm.unregisterNetworkCallback(callback); } catch (RuntimeException ignored) { }
            callback = null;
        }
        if (legacyNetworkId >= 0) {
            WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            wm.removeNetwork(legacyNetworkId);
            legacyNetworkId = -1;
        }
    }
}
