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

    static final class Peer {
        final String name;
        final int port;
        final boolean inGroup;
        final int memberCount;
        final int clusterId;
        final PeerHandle handle;
        final DiscoverySession session;

        Peer(String name, int port, boolean inGroup, int memberCount,
                PeerHandle handle, DiscoverySession session) {
            this(name, port, inGroup, memberCount, 0, handle, session);
        }

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
    private final ConnectivityManager cm;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<ConnectivityManager.NetworkCallback> netCallbacks =
            new ArrayList<ConnectivityManager.NetworkCallback>();
    private final java.util.Map<PeerHandle, Peer> pendingPeers =
            new java.util.concurrent.ConcurrentHashMap<PeerHandle, Peer>();
    private final java.util.Map<PeerHandle, Network> clientNets =
            new java.util.concurrent.ConcurrentHashMap<PeerHandle, Network>();
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

    void start(String name, int port, boolean inGroup, int memberCount) {
        start(name, port, inGroup, memberCount, 0);
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

    void updatePublish(String name, int port, boolean inGroup, int memberCount) {
        updatePublish(name, port, inGroup, memberCount, lastClusterId);
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
                    String s = new String(message, Hub.UTF8);
                    if ("JOIN".equals(s)) {
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
                    Lan.Peer lp = Lan.decodePacket(serviceSpecificInfo, serviceSpecificInfo.length, null);
                    if (lp != null && listener != null) {
                        listener.onPeer(new Peer(lp.name, lp.port, lp.inGroup, lp.memberCount,
                                lp.clusterId, peerHandle, subscribeSession));
                    }
                }
                @Override public void onMessageReceived(PeerHandle peerHandle, byte[] message) {
                    if (message == null || peerHandle == null) return;
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
