package com.growthinternal.app;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.NetworkSpecifier;
import android.net.wifi.aware.AttachCallback;
import android.net.wifi.aware.DiscoverySession;
import android.net.wifi.aware.DiscoverySessionCallback;
import android.net.wifi.aware.PeerHandle;
import android.net.wifi.aware.PublishConfig;
import android.net.wifi.aware.PublishDiscoverySession;
import android.net.wifi.aware.SubscribeConfig;
import android.net.wifi.aware.SubscribeDiscoverySession;
import android.net.wifi.aware.WifiAwareManager;
import android.net.wifi.aware.WifiAwareNetworkInfo;
import android.net.wifi.aware.WifiAwareNetworkSpecifier;
import android.net.wifi.aware.WifiAwareSession;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Priority 1 transport: Wi-Fi Aware / Neighbor Awareness Networking (NAN, API 26+).
 * Discovers nearby Growth Internal phones and opens a high-speed IPv6 data-path
 * (aware_data0) with zero OS confirmation dialogs and without disconnecting hotspot/Wi-Fi.
 */
final class Aware {
    static final String SERVICE_NAME = "growthinternal";
    private static final int TRANSPORT_WIFI_AWARE = 5;
    private static final String PASS = "GrowthInternalAware2026";
    static final byte SIDELINK_MAGIC = (byte) 0xA5;
    static final int MAX_SIDELINK_BYTES = 255;
    static final int DEFAULT_SIDELINK_TTL = 15;
    static final byte SIDELINK_AUDIO = 1;
    static final byte SIDELINK_TALK = 2;
    static final byte SIDELINK_TEXT = 3;
    static final byte SIDELINK_ANNOUNCE = 4;
    static final byte SIDELINK_PRESENCE = 5;

    static final class SidelinkPacket {
        final int msgId;
        final int ttl;
        final byte type;
        final String sender;
        final byte[] body;

        SidelinkPacket(int msgId, int ttl, byte type, String sender, byte[] body) {
            this.msgId = msgId & 0xFFFF;
            this.ttl = ttl;
            this.type = type;
            this.sender = sender;
            this.body = body;
        }
    }

    interface SidelinkListener {
        void onSidelink(SidelinkPacket packet);
    }

    static final class Peer {
        final String name;
        final int port;
        final boolean inGroup;
        final int memberCount;
        final int clusterId;
        final PeerHandle handle;
        final DiscoverySession session;

        Peer(String name, int port, boolean inGroup, int memberCount, int clusterId,
                PeerHandle handle, DiscoverySession session) {
            this.name = name;
            this.port = port;
            this.inGroup = inGroup;
            this.memberCount = memberCount;
            this.clusterId = clusterId & 0xFFFF;
            this.handle = handle;
            this.session = session;
        }
    }

    interface Listener {
        void onPeer(Peer peer);
        void onResponderRequested();
        void onTargetReady(String peerName, InetAddress address, int port);
    }

    private final Context ctx;
    private final Listener listener;
    private SidelinkListener sidelinkListener;
    private final ConnectivityManager cm;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final java.util.Random random = new java.util.Random();
    private final List<ConnectivityManager.NetworkCallback> netCallbacks =
            new ArrayList<ConnectivityManager.NetworkCallback>();
    private final java.util.Map<PeerHandle, Peer> pendingPeers =
            new java.util.concurrent.ConcurrentHashMap<PeerHandle, Peer>();
    private final java.util.Map<PeerHandle, Network> clientNets =
            new java.util.concurrent.ConcurrentHashMap<PeerHandle, Network>();
    private final java.util.Map<PeerHandle, DiscoverySession> sidelinkPeers =
            new java.util.concurrent.ConcurrentHashMap<PeerHandle, DiscoverySession>();
    private final java.util.Map<Integer, Long> seenSidelinkIds =
            new java.util.LinkedHashMap<Integer, Long>() {
                @Override protected boolean removeEldestEntry(java.util.Map.Entry<Integer, Long> eldest) {
                    return size() > 256;
                }
            };
    private WifiAwareManager mgr;
    private WifiAwareSession awareSession;
    private PublishDiscoverySession publishSession;
    private SubscribeDiscoverySession subscribeSession;
    private boolean attaching;
    private String lastName = "";
    private int lastPort = Hub.PORT;
    private boolean lastInGroup;
    private int lastCount;
    private int lastClusterId;

    Aware(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
        this.cm = (ConnectivityManager) this.ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (isSupported(this.ctx)) {
            try {
                mgr = (WifiAwareManager) this.ctx.getSystemService("wifiaware");
            } catch (RuntimeException ignored) {
            }
        }
    }

    Aware onSidelink(SidelinkListener sl) {
        this.sidelinkListener = sl;
        return this;
    }

    synchronized boolean recordSeenSidelink(int msgId) {
        int id = msgId & 0xFFFF;
        long now = System.currentTimeMillis();
        Long prev = seenSidelinkIds.get(id);
        seenSidelinkIds.put(id, now);
        return prev == null || now - prev > 8000L;
    }

    static byte[] packSidelink(int msgId, int ttl, byte type, String sender, byte[] body) {
        if (ttl <= 0 || sender == null || body == null) return null;
        byte[] sBytes = sender.trim().getBytes(Hub.UTF8);
        if (sBytes.length == 0) return null;
        int sLen = Math.min(24, sBytes.length);
        int hdr = 5 + sLen;
        int bLen = Math.min(body.length, Math.max(0, MAX_SIDELINK_BYTES - hdr));
        byte[] out = new byte[hdr + bLen];
        out[0] = SIDELINK_MAGIC;
        out[1] = (byte) (((type & 0x0F) << 4) | (Math.min(ttl, 15) & 0x0F));
        out[2] = (byte) ((msgId >>> 8) & 0xFF);
        out[3] = (byte) (msgId & 0xFF);
        out[4] = (byte) sLen;
        System.arraycopy(sBytes, 0, out, 5, sLen);
        if (bLen > 0) System.arraycopy(body, 0, out, hdr, bLen);
        return out;
    }

    static SidelinkPacket unpackSidelink(byte[] data) {
        if (data == null || data.length < 6 || data[0] != SIDELINK_MAGIC) return null;
        int typeAndTtl = data[1] & 0xFF;
        byte type = (byte) ((typeAndTtl >>> 4) & 0x0F);
        int ttl = typeAndTtl & 0x0F;
        if (ttl <= 0 || type <= 0) return null;
        int msgId = ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
        int sLen = data[4] & 0xFF;
        if (sLen <= 0 || 5 + sLen > data.length) return null;
        String sender = new String(data, 5, sLen, Hub.UTF8).trim();
        if (sender.isEmpty()) return null;
        int bLen = data.length - 5 - sLen;
        byte[] body = new byte[bLen];
        if (bLen > 0) System.arraycopy(data, 5 + sLen, body, 0, bLen);
        return new SidelinkPacket(msgId, ttl, type, sender, body);
    }

    void broadcastSidelinkAudio(String sender, byte[] pcm) {
        if (pcm == null || pcm.length < 4 || sidelinkPeers.isEmpty()) return;
        broadcastSidelink(SIDELINK_AUDIO, sender, Adpcm.encodeNarrowband(pcm));
    }

    void broadcastSidelinkTalk(String sender, boolean talking) {
        if (sidelinkPeers.isEmpty()) return;
        broadcastSidelink(SIDELINK_TALK, sender, new byte[] { (byte) (talking ? 1 : 0) });
    }

    void broadcastSidelinkText(String sender, String text) {
        if (text == null || text.isEmpty() || sidelinkPeers.isEmpty()) return;
        broadcastSidelink(SIDELINK_TEXT, sender, Ble.compressText(text));
    }

    void broadcastSidelinkAnnounce(String sender, String text) {
        if (text == null || text.isEmpty() || sidelinkPeers.isEmpty()) return;
        broadcastSidelink(SIDELINK_ANNOUNCE, sender, Ble.compressText(text));
    }

    void broadcastSidelinkPresence(String sender, byte[] presence) {
        if (presence == null || presence.length == 0 || sidelinkPeers.isEmpty()) return;
        broadcastSidelink(SIDELINK_PRESENCE, sender, presence);
    }

    void broadcastSidelink(byte type, String sender, byte[] body) {
        if (sender == null || body == null || sidelinkPeers.isEmpty()) return;
        int msgId = (random.nextInt(0xFFFE) + 1) & 0xFFFF;
        recordSeenSidelink(msgId);
        byte[] raw = packSidelink(msgId, DEFAULT_SIDELINK_TTL, type, sender, body);
        if (raw != null) sendRawToPeers(raw, null);
    }

    private void sendRawToPeers(byte[] raw, PeerHandle except) {
        int id = ((raw[2] & 0xFF) << 8) | (raw[3] & 0xFF);
        for (java.util.Map.Entry<PeerHandle, DiscoverySession> e : sidelinkPeers.entrySet()) {
            PeerHandle h = e.getKey();
            DiscoverySession s = e.getValue();
            if (h == null || s == null || h.equals(except)) continue;
            try {
                s.sendMessage(h, id, raw);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private boolean handleSidelinkMessage(DiscoverySession session, PeerHandle fromHandle, byte[] message) {
        if (message == null || message.length < 6 || message[0] != SIDELINK_MAGIC) return false;
        if (fromHandle != null && session != null) sidelinkPeers.put(fromHandle, session);
        SidelinkPacket pkt = unpackSidelink(message);
        if (pkt == null || !recordSeenSidelink(pkt.msgId)) return true;
        if (sidelinkListener != null) sidelinkListener.onSidelink(pkt);
        if (pkt.ttl > 1) {
            byte[] relayed = packSidelink(pkt.msgId, pkt.ttl - 1, pkt.type, pkt.sender, pkt.body);
            if (relayed != null) sendRawToPeers(relayed, fromHandle);
        }
        return true;
    }

    static boolean isSupported(Context c) {
        if (Build.VERSION.SDK_INT < 26 || c == null) return false;
        try {
            PackageManager pm = c.getPackageManager();
            return pm != null && pm.hasSystemFeature("android.hardware.wifi.aware");
        } catch (RuntimeException e) {
            return false;
        }
    }

    boolean isAvailable() {
        if (Build.VERSION.SDK_INT < 26 || mgr == null) return false;
        try {
            return mgr.isAvailable();
        } catch (RuntimeException e) {
            return false;
        }
    }

    void start(String name, int port, boolean inGroup, int memberCount, int clusterId) {
        lastName = name != null ? name : "";
        lastPort = port;
        lastInGroup = inGroup;
        lastCount = memberCount;
        lastClusterId = clusterId & 0xFFFF;
        if (!isAvailable()) return;
        if (awareSession != null) {
            updatePublish(lastName, lastPort, lastInGroup, lastCount, lastClusterId);
            return;
        }
        if (attaching) return;
        attaching = true;
        try {
            mgr.attach(new AttachCallback() {
                @Override public void onAttached(WifiAwareSession session) {
                    attaching = false;
                    awareSession = session;
                    startPublishAndSubscribe();
                }
                @Override public void onAttachFailed() {
                    attaching = false;
                }
            }, handler);
        } catch (RuntimeException e) {
            attaching = false;
        }
    }

    void updatePublish(String name, int port, boolean inGroup, int memberCount, int clusterId) {
        lastName = name != null ? name : "";
        lastPort = port;
        lastInGroup = inGroup;
        lastCount = memberCount;
        lastClusterId = clusterId & 0xFFFF;
        if (publishSession == null || lastName.isEmpty()) return;
        try {
            PublishConfig cfg = new PublishConfig.Builder()
                    .setServiceName(SERVICE_NAME)
                    .setServiceSpecificInfo(Lan.encodePacket(
                            lastName, lastPort, lastInGroup, lastCount, lastClusterId))
                    .build();
            publishSession.updatePublish(cfg);
        } catch (RuntimeException ignored) {
        }
    }

    private void startPublishAndSubscribe() {
        if (awareSession == null || lastName.isEmpty()) return;
        try {
            PublishConfig pubCfg = new PublishConfig.Builder()
                    .setServiceName(SERVICE_NAME)
                    .setServiceSpecificInfo(Lan.encodePacket(
                            lastName, lastPort, lastInGroup, lastCount, lastClusterId))
                    .build();
            awareSession.publish(pubCfg, new DiscoverySessionCallback() {
                @Override public void onPublishStarted(PublishDiscoverySession session) {
                    publishSession = session;
                }
                @Override public void onMessageReceived(PeerHandle peerHandle, byte[] message) {
                    if (message == null || publishSession == null) return;
                    if (handleSidelinkMessage(publishSession, peerHandle, message)) return;
                    String s = new String(message, Hub.UTF8);
                    if ("JOIN".equals(s)) {
                        if (peerHandle != null) sidelinkPeers.put(peerHandle, publishSession);
                        if (listener != null) listener.onResponderRequested();
                        openDataPath(publishSession, peerHandle, false, null);
                    }
                }
            }, handler);

            SubscribeConfig subCfg = new SubscribeConfig.Builder()
                    .setServiceName(SERVICE_NAME)
                    .build();
            awareSession.subscribe(subCfg, new DiscoverySessionCallback() {
                @Override public void onSubscribeStarted(SubscribeDiscoverySession session) {
                    subscribeSession = session;
                }
                @Override public void onServiceDiscovered(PeerHandle peerHandle,
                        byte[] serviceSpecificInfo, List<byte[]> matchFilter) {
                    if (serviceSpecificInfo == null || subscribeSession == null) return;
                    if (peerHandle != null) sidelinkPeers.put(peerHandle, subscribeSession);
                    Lan.Peer lp = Lan.decodePacket(serviceSpecificInfo, serviceSpecificInfo.length, null);
                    if (lp != null && listener != null) {
                        listener.onPeer(new Peer(lp.name, lp.port, lp.inGroup, lp.memberCount,
                                lp.clusterId, peerHandle, subscribeSession));
                    }
                }
                @Override public void onMessageReceived(PeerHandle peerHandle, byte[] message) {
                    if (message == null || peerHandle == null) return;
                    if (handleSidelinkMessage(subscribeSession, peerHandle, message)) return;
                    String s = new String(message, Hub.UTF8);
                    if (s.startsWith("IPV6:")) {
                        String host = s.substring(5).trim();
                        deliverFromIpv6String(peerHandle, host);
                    }
                }
            }, handler);
        } catch (RuntimeException ignored) {
        }
    }

    void connect(Peer peer) {
        if (peer == null || peer.session == null || peer.handle == null) return;
        pendingPeers.put(peer.handle, peer);
        try {
            peer.session.sendMessage(peer.handle, 1, "JOIN".getBytes(Hub.UTF8));
        } catch (RuntimeException ignored) {
        }
        openDataPath(peer.session, peer.handle, true, peer);
    }

    private void openDataPath(final DiscoverySession session, final PeerHandle handle,
            final boolean isClient, final Peer peer) {
        if (cm == null) return;
        try {
            NetworkSpecifier spec;
            if (Build.VERSION.SDK_INT >= 29) {
                WifiAwareNetworkSpecifier.Builder b = new WifiAwareNetworkSpecifier.Builder(session, handle)
                        .setPskPassphrase(PASS);
                if (!isClient) b.setPort(lastPort);
                spec = b.build();
            } else {
                spec = session.createNetworkSpecifierOpen(handle);
            }
            if (spec == null) return;
            NetworkRequest.Builder rb = new NetworkRequest.Builder()
                    .addTransportType(TRANSPORT_WIFI_AWARE)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            NetworkRequest.Builder.class
                    .getMethod("setNetworkSpecifier", NetworkSpecifier.class)
                    .invoke(rb, spec);

            ConnectivityManager.NetworkCallback cb = new ConnectivityManager.NetworkCallback() {
                private boolean delivered;

                @Override public void onAvailable(Network network) {
                    if (isClient) {
                        if (handle != null && network != null) clientNets.put(handle, network);
                    } else {
                        String ipv6 = localLinkIpv6(network);
                        if (ipv6 != null) {
                            try {
                                session.sendMessage(handle, 2, ("IPV6:" + ipv6).getBytes(Hub.UTF8));
                            } catch (RuntimeException ignored) {
                            }
                        }
                    }
                }

                @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities nc) {
                    if (!isClient || delivered || peer == null || Build.VERSION.SDK_INT < 29) return;
                    try {
                        Object ti = NetworkCapabilities.class.getMethod("getTransportInfo").invoke(nc);
                        if (ti instanceof WifiAwareNetworkInfo) {
                            WifiAwareNetworkInfo info = (WifiAwareNetworkInfo) ti;
                            Inet6Address addr = info.getPeerIpv6Addr();
                            int p = info.getPort() > 0 ? info.getPort() : peer.port;
                            if (addr != null) {
                                delivered = true;
                                pendingPeers.remove(handle);
                                clientNets.remove(handle);
                                if (listener != null) listener.onTargetReady(peer.name, addr, p);
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
            };
            synchronized (netCallbacks) {
                netCallbacks.add(cb);
            }
            cm.requestNetwork(rb.build(), cb);
        } catch (Exception ignored) {
        }
    }

    private void deliverFromIpv6String(PeerHandle peerHandle, String ipv6) {
        Peer peer = pendingPeers.remove(peerHandle);
        Network net = clientNets.remove(peerHandle);
        if (peer == null || net == null || ipv6.isEmpty()) return;
        try {
            LinkProperties lp = cm.getLinkProperties(net);
            if (lp == null || lp.getInterfaceName() == null) return;
            NetworkInterface ni = NetworkInterface.getByName(lp.getInterfaceName());
            if (ni == null) return;
            byte[] raw = InetAddress.getByName(ipv6).getAddress();
            Inet6Address scoped = Inet6Address.getByAddress(null, raw, ni);
            if (listener != null) listener.onTargetReady(peer.name, scoped, peer.port);
        } catch (Exception ignored) {
        }
    }

    private String localLinkIpv6(Network network) {
        try {
            LinkProperties lp = cm.getLinkProperties(network);
            if (lp == null || lp.getInterfaceName() == null) return null;
            NetworkInterface ni = NetworkInterface.getByName(lp.getInterfaceName());
            if (ni == null) return null;
            for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                if (a instanceof Inet6Address && a.isLinkLocalAddress()) {
                    return Bridge.withoutScope((Inet6Address) a);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    void stop() {
        pendingPeers.clear();
        clientNets.clear();
        sidelinkPeers.clear();
        synchronized (netCallbacks) {
            for (ConnectivityManager.NetworkCallback cb : netCallbacks) {
                try { cm.unregisterNetworkCallback(cb); } catch (RuntimeException ignored) {}
            }
            netCallbacks.clear();
        }
        if (publishSession != null) {
            try { publishSession.close(); } catch (RuntimeException ignored) {}
            publishSession = null;
        }
        if (subscribeSession != null) {
            try { subscribeSession.close(); } catch (RuntimeException ignored) {}
            subscribeSession = null;
        }
        if (awareSession != null) {
            try { awareSession.close(); } catch (RuntimeException ignored) {}
            awareSession = null;
        }
        attaching = false;
    }
}
