package com.growthinternal.app;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * Zero-configuration UDP discovery on a shared mobile hotspot or local Wi-Fi router
 * (no internet required). Announces this device on UDP port 8989 so nearby phones on
 * the same LAN/Hotspot can join or form a Hub group over TCP port 8988.
 */
final class Lan {
    static final int PORT = 8989;
    private static final String MAGIC = "GI1";

    static final class Peer {
        final String name;
        final InetAddress address;
        final int port;
        final boolean inGroup;
        final int memberCount;
        final int clusterId;

        Peer(String name, InetAddress address, int port, boolean inGroup, int memberCount, int clusterId) {
            this.name = name;
            this.address = address;
            this.port = port;
            this.inGroup = inGroup;
            this.memberCount = memberCount;
            this.clusterId = clusterId & 0xFFFF;
        }
    }

    interface Listener {
        void onPeer(Peer peer);
    }

    private final Listener listener;
    private volatile boolean stopped;
    private volatile DatagramSocket socket;

    Lan(Listener listener) {
        this.listener = listener;
    }

    void start() {
        stopped = false;
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    DatagramSocket s = new DatagramSocket(null);
                    s.setReuseAddress(true);
                    s.setBroadcast(true);
                    s.bind(new InetSocketAddress(PORT));
                    socket = s;
                    byte[] buf = new byte[512];
                    while (!stopped) {
                        DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                        s.receive(pkt);
                        if (isLocalAddress(pkt.getAddress())) continue;
                        Peer peer = decodePacket(pkt.getData(), pkt.getLength(), pkt.getAddress());
                        if (peer != null && listener != null) {
                            listener.onPeer(peer);
                        }
                    }
                } catch (IOException ignored) {
                }
            }
        }, "lan-udp");
        t.setDaemon(true);
        t.start();
    }

    void announce(final String name, final int hubPort, final boolean inGroup,
            final int memberCount, final int clusterId) {
        if (name == null || name.trim().isEmpty()) return;
        new Thread(new Runnable() {
            @Override public void run() {
                byte[] data = encodePacket(name, hubPort, inGroup, memberCount, clusterId);
                DatagramSocket s = socket;
                boolean tempSocket = false;
                try {
                    if (s == null || s.isClosed()) {
                        s = new DatagramSocket();
                        s.setBroadcast(true);
                        tempSocket = true;
                    }
                    for (InetAddress bcast : broadcastAddresses()) {
                        try {
                            s.send(new DatagramPacket(data, data.length, bcast, PORT));
                        } catch (IOException ignored) {
                        }
                    }
                } catch (IOException ignored) {
                } finally {
                    if (tempSocket && s != null) s.close();
                }
            }
        }, "lan-announce").start();
    }

    void stop() {
        stopped = true;
        DatagramSocket s = socket;
        socket = null;
        if (s != null) s.close();
    }

    static byte[] encodePacket(String name, int hubPort, boolean inGroup, int memberCount) {
        return encodePacket(name, hubPort, inGroup, memberCount, 0);
    }

    static byte[] encodePacket(String name, int hubPort, boolean inGroup, int memberCount, int clusterId) {
        String clean = name.replace('\n', ' ').trim();
        String msg = MAGIC + "\n" + clean + "\n" + hubPort + "\n"
                + (inGroup ? "1" : "0") + "\n" + Math.max(0, memberCount)
                + ((clusterId & 0xFFFF) != 0 ? "\n" + (clusterId & 0xFFFF) : "");
        return msg.getBytes(Hub.UTF8);
    }

    static Peer decodePacket(byte[] buf, int len, InetAddress sender) {
        if (buf == null || len <= 0 || len > 512) return null;
        String s = new String(buf, 0, len, Hub.UTF8);
        String[] parts = s.split("\n");
        if (parts.length < 4 || !MAGIC.equals(parts[0])) return null;
        String name = parts[1].trim();
        if (name.isEmpty()) return null;
        int port;
        try {
            port = Integer.parseInt(parts[2].trim());
        } catch (NumberFormatException e) {
            return null;
        }
        if (port <= 0 || port > 65535) return null;
        boolean inGroup = "1".equals(parts[3].trim());
        int count = inGroup ? 1 : 0;
        if (parts.length >= 5) {
            try { count = Integer.parseInt(parts[4].trim()); } catch (NumberFormatException ignored) {}
        }
        int clusterId = 0;
        if (parts.length >= 6) {
            try { clusterId = Integer.parseInt(parts[5].trim()) & 0xFFFF; } catch (NumberFormatException ignored) {}
        }
        return new Peer(name, sender, port, inGroup, count, clusterId);
    }

    static boolean isLocalAddress(InetAddress addr) {
        if (addr == null) return false;
        if (addr.isLoopbackAddress()) return true;
        try {
            return NetworkInterface.getByInetAddress(addr) != null;
        } catch (IOException e) {
            return false;
        }
    }

    static boolean isVirtualOrCellularInterface(String ifName) {
        if (ifName == null) return true;
        String lower = ifName.trim().toLowerCase();
        if (lower.isEmpty() || "lo".equals(lower)) return true;
        String[] blocked = {
            "tun", "tap", "ppp", "wg", "tailscale", "rmnet", "ccmni", "clat", "dummy", "v4-", "ipsec"
        };
        for (String prefix : blocked) {
            if (lower.startsWith(prefix)) return true;
        }
        return false;
    }

    static boolean isPreferredWifiInterface(String ifName) {
        if (ifName == null) return false;
        String lower = ifName.trim().toLowerCase();
        return lower.startsWith("wlan") || lower.startsWith("ap")
                || lower.startsWith("softap") || lower.startsWith("swlan")
                || lower.startsWith("wifi") || lower.startsWith("uap");
    }

    static InetAddress gatewayFor(InetAddress ipv4) {
        if (!(ipv4 instanceof Inet4Address) || ipv4.isLoopbackAddress()) return null;
        byte[] raw = ipv4.getAddress();
        if (raw == null || raw.length != 4 || (raw[3] & 0xFF) == 1) return null;
        raw[3] = 1;
        try {
            return InetAddress.getByAddress(raw);
        } catch (IOException e) {
            return null;
        }
    }

    static InetAddress localIPv4() {
        InetAddress otherLan = null;
        InetAddress p2pFallback = null;
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            if (ifs == null) return null;
            while (ifs.hasMoreElements()) {
                NetworkInterface nif = ifs.nextElement();
                if (!nif.isUp() || nif.isLoopback()) continue;
                String ifName = nif.getName() != null ? nif.getName().toLowerCase() : "";
                if (isVirtualOrCellularInterface(ifName)) continue;
                boolean isP2p = ifName.startsWith("p2p");
                boolean isWifi = isPreferredWifiInterface(ifName);
                Enumeration<InetAddress> addrs = nif.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        if (isWifi) return a;
                        if (!isP2p && otherLan == null) otherLan = a;
                        if (isP2p && p2pFallback == null) p2pFallback = a;
                    }
                }
            }
        } catch (IOException ignored) {
        }
        return otherLan != null ? otherLan : p2pFallback;
    }

    private static List<InetAddress> broadcastAddresses() {
        List<InetAddress> list = new ArrayList<InetAddress>();
        try {
            list.add(InetAddress.getByName("255.255.255.255"));
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            if (ifs != null) {
                while (ifs.hasMoreElements()) {
                    NetworkInterface nif = ifs.nextElement();
                    if (!nif.isUp() || nif.isLoopback()) continue;
                    if (isVirtualOrCellularInterface(nif.getName())) continue;
                    for (InterfaceAddress ia : nif.getInterfaceAddresses()) {
                        InetAddress b = ia.getBroadcast();
                        if (b != null && !list.contains(b)) list.add(b);
                        InetAddress gw = gatewayFor(ia.getAddress());
                        if (gw != null && !list.contains(gw)) list.add(gw);
                    }
                }
            }
        } catch (IOException ignored) {
        }
        return list;
    }
}
