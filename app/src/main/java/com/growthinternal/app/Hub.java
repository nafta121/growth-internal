package com.growthinternal.app;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Holds the connections of one Wi-Fi Direct group. The group owner runs a server
 * and relays every frame to the other members, so a group works like a
 * walkie-talkie channel; with two phones it is a 1-to-1 call.
 *
 * Multihop: a bridge is the owner of its own group and also keeps an uplink to
 * another group's owner. Because groups only ever join as a tree, relaying every
 * frame to every link but the one it came from reaches everyone exactly once.
 */
final class Hub implements Link.Listener {
    interface Callback {
        void onStatus(String message);
        void onMemberJoined(String name, byte[] photo);
        void onMemberLeft(String name);
        void onText(String from, String text);
        void onAudio(String from, byte[] pcm);
        void onTalkState(String from, boolean talking);
        void onPresence(String from, byte[] presence);
        void onAnnouncement(String from, String text);
        void onImage(String from, byte[] jpeg);
        void onVoiceNote(String from, byte[] adpcm);
        void onVideoIncoming(String from);
        void onVideo(String from, byte[] mp4);
        void onRejected(String reason);
        /** The owner of our group says how a bridge can reach it. */
        void onJoinInfo(byte[] info);
        void onUplink(boolean connected);
    }

    static final int PORT = 8988;
    static final int MAX_LINKS = 16;
    private static final int MAX_HISTORY = 32;
    static final Charset UTF8 = Charset.forName("UTF-8");

    static final Callback NOOP_CALLBACK = new Callback() {
        @Override public void onStatus(String message) {}
        @Override public void onMemberJoined(String name, byte[] photo) {}
        @Override public void onMemberLeft(String name) {}
        @Override public void onText(String from, String text) {}
        @Override public void onAudio(String from, byte[] pcm) {}
        @Override public void onTalkState(String from, boolean talking) {}
        @Override public void onPresence(String from, byte[] presence) {}
        @Override public void onAnnouncement(String from, String text) {}
        @Override public void onImage(String from, byte[] jpeg) {}
        @Override public void onVoiceNote(String from, byte[] adpcm) {}
        @Override public void onVideoIncoming(String from) {}
        @Override public void onVideo(String from, byte[] mp4) {}
        @Override public void onRejected(String reason) {}
        @Override public void onJoinInfo(byte[] info) {}
        @Override public void onUplink(boolean connected) {}
    };

    private final Callback cb;
    private final List<Link> links = new CopyOnWriteArrayList<Link>();
    private final Set<InetSocketAddress> activeReconnects = java.util.Collections.newSetFromMap(
            new java.util.concurrent.ConcurrentHashMap<InetSocketAddress, Boolean>());
    private final Map<String, byte[]> members = new LinkedHashMap<String, byte[]>();
    private final Clips.Assembler clips = new Clips.Assembler();
    private final Clips.Assembler images = new Clips.Assembler(Clips.IMAGE_CHUNK);
    // Latest PRESENCE payload per member, replayed by the owner to phones that join later.
    private final Map<String, byte[]> presences = new LinkedHashMap<String, byte[]>();
    // Store-and-forward buffer for recent chat, instructions, and voice notes.
    private final List<Frame> history = new ArrayList<Frame>();
    private final Map<Integer, Long> seenRelays = new LinkedHashMap<Integer, Long>() {
        @Override protected boolean removeEldestEntry(Map.Entry<Integer, Long> eldest) {
            return size() > 128;
        }
    };
    private static final int[] STREAM_HEMB_COEFFS = { 1, 1, 1, 1 };
    private final byte[][] txHembWindow = new byte[Adpcm.HEMB_WINDOW][];
    private int txHembCount;
    private int txHembSeq;
    private final Map<String, byte[][]> rxHembWindows = new LinkedHashMap<String, byte[][]>();
    private final Map<String, Integer> rxHembCounts = new LinkedHashMap<String, Integer>();
    private volatile byte[] myPresence;
    private final String myName;
    private final byte[] myPhoto;
    private volatile boolean stopped;
    private volatile boolean owner;
    private final List<ServerSocket> servers = new CopyOnWriteArrayList<ServerSocket>();
    private volatile Link uplink;
    private volatile byte[] joinInfo;
    private int port = PORT;
    private volatile int clusterId;
    private volatile int lossCounter;
    private volatile int reportedClusterSize;
    private volatile Runnable clusterListener;

    Hub(String myName, byte[] myPhoto, Callback cb) {
        this.myName = myName;
        this.myPhoto = myPhoto;
        this.cb = cb != null ? cb : NOOP_CALLBACK;
        this.clusterId = computeClusterId(myName, 0);
    }

    Hub onClusterChanged(Runnable listener) {
        this.clusterListener = listener;
        return this;
    }

    static int computeClusterId(String name, int lossCount) {
        int h = ((name != null ? name.hashCode() : 1) * 31) ^ (lossCount * 0x45D9F3B);
        int cid = (h ^ (h >>> 16)) & 0xFFFF;
        return cid == 0 ? 1 : cid;
    }

    int clusterId() { return clusterId; }

    int lossCounter() { return lossCounter; }

    int clusterSize() {
        synchronized (members) { return Math.max(members.size() + 1, reportedClusterSize); }
    }

    static byte[] encodeClusterUpdate(int clusterId, int clusterSize) {
        return new byte[] {
            (byte) ((clusterId >>> 8) & 0xFF),
            (byte) (clusterId & 0xFF),
            (byte) ((clusterSize >>> 8) & 0xFF),
            (byte) (clusterSize & 0xFF)
        };
    }

    private Frame buildClusterFrame() {
        return new Frame(Frame.CLUSTER_UPDATE, myName, encodeClusterUpdate(clusterId, clusterSize()));
    }

    boolean hasPeers() { return !links.isEmpty(); }

    List<Link> activeLinks() { return new ArrayList<Link>(links); }

    boolean hasMember(String name) {
        synchronized (members) { return members.containsKey(name); }
    }

    boolean hasLowBandwidthLink(String name) {
        if (name == null) return false;
        for (Link l : links) {
            if (l.lowBandwidth && (name.equals(l.peerName) || l.behind.contains(name))) {
                return true;
            }
        }
        return false;
    }

    boolean isOwner() { return owner; }

    Hub asOwner(boolean isOwner) {
        this.owner = isOwner;
        return this;
    }

    int port() { return port; }

    boolean isBridged() { return uplink != null; }

    /** Tests run several hubs on one machine, each on its own port. */
    Hub port(int p) {
        port = p;
        return this;
    }

    /** What direct members receive so they can later reach this group as a bridge. */
    void setJoinInfo(byte[] info) {
        joinInfo = info;
        for (Link l : links) {
            if (!l.relay && l.peerName != null && l != uplink) l.send(new Frame(Frame.JOIN_INFO, myName, info));
        }
    }

    /**
     * Listens only on the Wi-Fi Direct group address, so phones on another network
     * the device is joined to (a café Wi-Fi, a hotspot) cannot reach the server.
     */
    void startServer(final InetAddress groupAddress) {
        owner = true;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    ServerSocket s = new ServerSocket();
                    s.setReuseAddress(true);
                    s.bind(groupAddress == null
                            ? new InetSocketAddress(port)
                            : new InetSocketAddress(groupAddress, port));
                    synchronized (Hub.this) {
                        if (stopped) { s.close(); return; }
                        servers.add(s);
                    }
                    if (servers.size() == 1) cb.onStatus("Menunggu anggota bergabung");
                    while (!stopped) {
                        Socket c = s.accept();
                        if (links.size() >= MAX_LINKS) {
                            c.close();
                            continue;
                        }
                        addLink(c);
                    }
                } catch (IOException e) {
                    if (!stopped) cb.onStatus("Server berhenti: " + e.getMessage());
                }
            }
        }, "hub-server").start();
    }

    void startClient(final InetAddress host) {
        owner = false;
        final InetSocketAddress target = new InetSocketAddress(host, port);
        new Thread(new Runnable() {
            @Override public void run() {
                for (int attempt = 0; attempt < 15 && !stopped; attempt++) {
                    try {
                        Socket s = new Socket();
                        s.connect(target, 4000);
                        addLink(s, target);
                        return;
                    } catch (IOException e) {
                        try { Thread.sleep(1000); } catch (InterruptedException ie) { return; }
                    }
                }
                if (!stopped) cb.onStatus("Gagal tersambung ke pemilik grup");
            }
        }, "hub-client").start();
    }

    void connectPeer(final InetAddress host, final int peerPort, final Runnable onFail) {
        final InetSocketAddress target = new InetSocketAddress(host, peerPort);
        new Thread(new Runnable() {
            @Override public void run() {
                for (int attempt = 0; attempt < 4 && !stopped; attempt++) {
                    try {
                        Socket s = new Socket();
                        s.connect(target, 3500);
                        addLink(s, target);
                        return;
                    } catch (IOException e) {
                        try { Thread.sleep(800); } catch (InterruptedException ie) { return; }
                    }
                }
                if (!stopped && onFail != null) onFail.run();
            }
        }, "hub-peer").start();
    }

    /**
     * Makes this owner a bridge: connects to another group's owner and merges the two
     * groups. The socket factory routes over the Wi-Fi network joined to that group.
     */
    void startUplink(final InetAddress host, final int hostPort, final javax.net.SocketFactory factory) {
        if (!owner || uplink != null) return;
        new Thread(new Runnable() {
            @Override public void run() {
                for (int attempt = 0; attempt < 10 && !stopped && uplink == null; attempt++) {
                    try {
                        Socket s = factory.createSocket();
                        s.connect(new InetSocketAddress(host, hostPort), 4000);
                        if (stopped) { s.close(); return; }
                        Link link = new Link(s, Hub.this);
                        link.relay = true;
                        link.sentBridge = true;
                        // Queued before the reader starts, so the far owner knows our cluster
                        // and that we are a bridge before any phone behind us is introduced.
                        link.send(buildClusterFrame());
                        link.send(new Frame(Frame.HELLO, myName, myPhoto));
                        link.send(new Frame(Frame.BRIDGE, myName, null));
                        byte[] p = myPresence;
                        if (p != null) link.send(new Frame(Frame.PRESENCE, myName, p));
                        uplink = link;
                        links.add(link);
                        link.start();
                        cb.onUplink(true);
                        return;
                    } catch (IOException e) {
                        try { Thread.sleep(1500); } catch (InterruptedException ie) { return; }
                    }
                }
                if (!stopped && uplink == null) cb.onStatus("Jembatan gagal tersambung ke grup lain");
            }
        }, "hub-uplink").start();
    }

    private void addLink(Socket s) throws IOException {
        addLink(s, null);
    }

    private void addLink(Socket s, InetSocketAddress reconnectTarget) throws IOException {
        if (stopped) { s.close(); return; }
        Link link = new Link(s, this);
        link.reconnectTarget = reconnectTarget;
        link.send(buildClusterFrame());
        link.send(new Frame(Frame.HELLO, myName, myPhoto));
        byte[] p = myPresence;
        if (p != null) link.send(new Frame(Frame.PRESENCE, myName, p));
        links.add(link);
        link.start();
    }

    void addStreamLink(java.io.InputStream in, java.io.OutputStream out,
            java.io.Closeable raw, boolean lowBandwidth) throws IOException {
        if (stopped || links.size() >= MAX_LINKS) { raw.close(); return; }
        Link link = new Link(in, out, raw, lowBandwidth, this);
        link.send(buildClusterFrame());
        link.send(new Frame(Frame.HELLO, myName, myPhoto));
        byte[] p = myPresence;
        if (p != null) link.send(new Frame(Frame.PRESENCE, myName, p));
        links.add(link);
        link.start();
    }

    private void recordHistory(Frame f) {
        synchronized (history) {
            for (Frame existing : history) {
                if (existing.type == f.type && existing.sender.equals(f.sender)
                        && java.util.Arrays.equals(existing.payload, f.payload)) {
                    return;
                }
            }
            if (history.size() >= MAX_HISTORY) history.remove(0);
            history.add(f);
        }
    }

    private boolean recordSeenRelay(byte[] env) {
        if (env == null || env.length < 2) return false;
        int h = java.util.Arrays.hashCode(env);
        long now = System.currentTimeMillis();
        synchronized (seenRelays) {
            Long prev = seenRelays.get(h);
            seenRelays.put(h, now);
            return prev == null || now - prev > 10000L;
        }
    }

    static byte[] encodeMeshRelay(byte innerType, String originSender, byte[] innerPayload) {
        if (originSender == null) return null;
        byte[] s = originSender.trim().getBytes(UTF8);
        if (s.length == 0 || s.length > 64) return null;
        int pLen = innerPayload != null ? innerPayload.length : 0;
        byte[] out = new byte[2 + s.length + pLen];
        out[0] = innerType;
        out[1] = (byte) s.length;
        System.arraycopy(s, 0, out, 2, s.length);
        if (pLen > 0) System.arraycopy(innerPayload, 0, out, 2 + s.length, pLen);
        return out;
    }

    void relayMeshText(String originSender, String text) {
        if (text == null || text.isEmpty()) return;
        relayMeshPacket(Frame.TEXT, originSender, text.getBytes(UTF8));
    }

    void relayMeshAnnounce(String originSender, String text) {
        if (text == null || text.isEmpty()) return;
        relayMeshPacket(Frame.ANNOUNCE, originSender, text.getBytes(UTF8));
    }

    void relayMeshPresence(String originSender, byte[] presence) {
        if (presence == null || presence.length == 0) return;
        relayMeshPacket(Frame.PRESENCE, originSender, presence);
    }

    void relayMeshAudio(String originSender, byte[] adpcm) {
        if (adpcm == null || adpcm.length == 0) return;
        relayMeshPacket(Frame.AUDIO_ADPCM, originSender, adpcm);
    }

    void relayMeshTalkState(String originSender, boolean talking) {
        relayMeshPacket(talking ? Frame.TALK_START : Frame.TALK_END, originSender, new byte[] { (byte) (talking ? 1 : 0) });
    }

    void relayMeshPacket(byte innerType, String originSender, byte[] innerPayload) {
        if (originSender == null || originSender.equals(myName) || links.isEmpty()) return;
        byte[] env = encodeMeshRelay(innerType, originSender, innerPayload);
        if (env == null || !recordSeenRelay(env)) return;
        broadcast(new Frame(Frame.MESH_RELAY, myName, env), null);
    }

    boolean isPrioritySpeaker(String sender) {
        if (sender == null) return false;
        byte[] p = sender.equals(myName) ? myPresence : null;
        if (p == null) {
            synchronized (presences) { p = presences.get(sender); }
        }
        return p != null && p.length >= 2 && p[1] == Presence.SOS;
    }

    void sendPresence(byte[] presence) {
        myPresence = presence;
        broadcast(new Frame(Frame.PRESENCE, myName, presence), null);
    }

    void sendAnnouncement(String text) {
        Frame f = new Frame(Frame.ANNOUNCE, myName, text.getBytes(UTF8));
        recordHistory(f);
        broadcast(f, null);
    }

    void sendText(String text) {
        Frame f = new Frame(Frame.TEXT, myName, text.getBytes(UTF8));
        recordHistory(f);
        broadcast(f, null);
    }

    void sendVoiceNote(byte[] adpcm) {
        Frame f = new Frame(Frame.VOICE_NOTE, myName, adpcm);
        recordHistory(f);
        broadcast(f, null);
    }

    void sendImage(byte[] jpeg) {
        if (jpeg == null || jpeg.length == 0) return;
        int id = new java.util.Random().nextInt();
        for (byte[] chunk : Clips.split(id, jpeg, Clips.IMAGE_CHUNK)) {
            broadcast(new Frame(Frame.IMAGE_CHUNK, myName, chunk), null);
        }
    }

    void sendVideo(byte[] mp4) {
        int id = new java.util.Random().nextInt();
        for (byte[] chunk : Clips.split(id, mp4)) {
            broadcast(new Frame(Frame.VIDEO_CHUNK, myName, chunk), null);
        }
    }

    void sendAudio(byte[] pcm) {
        boolean prio = isPrioritySpeaker(myName);
        broadcast(new Frame(Frame.AUDIO, myName, pcm, prio), null);
        if (pcm != null && pcm.length == Voice.FRAME_BYTES) {
            byte[] parity = null;
            synchronized (txHembWindow) {
                txHembWindow[txHembCount++] = Adpcm.encodeNarrowband(pcm);
                if (txHembCount == Adpcm.HEMB_WINDOW) {
                    parity = Adpcm.encodeHembParity(txHembSeq, txHembWindow, STREAM_HEMB_COEFFS);
                    txHembSeq = (txHembSeq + Adpcm.HEMB_WINDOW) & 0xFFFF;
                    txHembCount = 0;
                }
            }
            if (parity != null) {
                broadcast(new Frame(Frame.AUDIO_HEMB, myName, parity, prio), null);
            }
        }
    }

    void sendTalkState(boolean talking) {
        synchronized (txHembWindow) { txHembCount = 0; }
        broadcast(new Frame(talking ? Frame.TALK_START : Frame.TALK_END, myName, null), null);
    }

    private void broadcast(Frame f, Link except) {
        for (Link l : links) {
            if (l != except) l.send(f);
        }
    }

    @Override public void onFrame(Link from, Frame f) {
        if (from != null && f.type != Frame.HELLO) {
            Link old = from.replacedLink;
            if (old != null) {
                from.replacedLink = null;
                old.close();
            }
        }
        if (f.type == Frame.HELLO) {
            boolean multiHop = owner || links.size() > 1;
            boolean directJoin;
            // The first HELLO on a link names the phone at the other end; a client or relay
            // later receives relayed HELLOs of the other members over the same link.
            synchronized (members) {
                directJoin = (from.peerName == null);
                if (directJoin) {
                    Link handoverOld = findHandoverLink(from, f.sender);
                    if (handoverOld != null) {
                        links.remove(handoverOld);
                        from.behind.addAll(handoverOld.behind);
                        handoverOld.peerName = null;
                        handoverOld.behind.clear();
                        from.replacedLink = handoverOld;
                    } else if (multiHop && nameTaken(f.sender)) {
                        if (from == uplink) {
                            cb.onStatus("Nama \"" + f.sender + "\" dipakai di dua grup; jembatan dibatalkan");
                            from.close();
                        } else {
                            from.send(new Frame(Frame.REJECT, myName,
                                    ("Nama \"" + f.sender + "\" sudah dipakai di grup ini. Ganti nama lalu sambung lagi.")
                                            .getBytes(UTF8)));
                        }
                        return;
                    }
                    from.peerName = f.sender;
                    from.peerPhoto = f.payload;
                    from.behind.add(f.sender);
                    if (links.size() > 1 || !members.isEmpty()) {
                        for (Link l : links) {
                            if (!l.sentBridge) {
                                l.sentBridge = true;
                                l.send(new Frame(Frame.BRIDGE, myName, null));
                            }
                        }
                    }
                    if (multiHop || handoverOld != null) {
                        from.send(buildClusterFrame());
                        // Everyone we know of, including phones behind bridges/relays.
                        for (Map.Entry<String, byte[]> e : members.entrySet()) {
                            if (!e.getKey().equals(f.sender)) from.send(new Frame(Frame.HELLO, e.getKey(), e.getValue()));
                        }
                        synchronized (presences) {
                            for (Map.Entry<String, byte[]> e : presences.entrySet()) {
                                from.send(new Frame(Frame.PRESENCE, e.getKey(), e.getValue()));
                            }
                        }
                        synchronized (history) {
                            for (Frame h : history) {
                                if (!h.sender.equals(f.sender)
                                        && (h.sender.equals(myName) || members.containsKey(h.sender))) {
                                    from.send(h);
                                }
                            }
                        }
                        byte[] info = joinInfo;
                        if (owner && info != null && from != uplink) from.send(new Frame(Frame.JOIN_INFO, myName, info));
                    }
                } else {
                    // Only a bridge/relay may introduce further phones, and never a name already in use.
                    if ((owner && !from.relay) || nameTaken(f.sender)) return;
                    from.behind.add(f.sender);
                }
                members.put(f.sender, f.payload);
            }
            if (multiHop) {
                broadcast(buildClusterFrame(), directJoin ? null : from);
                broadcast(f, from);
            }
            cb.onMemberJoined(f.sender, f.payload);
            return;
        }
        if (f.type == Frame.CLUSTER_UPDATE) {
            if (f.payload != null && f.payload.length >= 4) {
                int peerCid = ((f.payload[0] & 0xFF) << 8) | (f.payload[1] & 0xFF);
                int peerSize = ((f.payload[2] & 0xFF) << 8) | (f.payload[3] & 0xFF);
                if (peerCid != 0) {
                    if (peerCid == clusterId) {
                        if (peerSize > clusterSize()) {
                            reportedClusterSize = peerSize;
                            broadcast(buildClusterFrame(), from);
                        }
                    } else {
                        int mySize = clusterSize();
                        boolean adoptPeer = peerSize > mySize
                                || (peerSize == mySize && peerCid < clusterId);
                        if (adoptPeer) {
                            clusterId = peerCid;
                            reportedClusterSize = peerSize;
                            broadcast(buildClusterFrame(), from);
                            Runnable cl = clusterListener;
                            if (cl != null) cl.run();
                        } else {
                            from.send(buildClusterFrame());
                        }
                    }
                }
            }
            return;
        }
        if (f.type == Frame.BRIDGE) {
            if (f.sender.equals(from.peerName)) from.relay = true;
            return;
        }
        if (f.type == Frame.JOIN_INFO) {
            // Point to point from our own owner; never relayed.
            if (!owner && f.sender.equals(from.peerName)) cb.onJoinInfo(f.payload);
            return;
        }
        if (f.type == Frame.REJECT) {
            cb.onRejected(new String(f.payload, UTF8));
            return;
        }
        // A sender belongs to the link it joined through: a plain member only speaks for
        // itself, a bridge/relay for the phones behind it. This stops one member from posing
        // as another (fake SOS or announcements) and prevents mesh loops.
        if (from != null && !from.behind.contains(f.sender)) return;
        if (f.type == Frame.MESH_RELAY) {
            if (!recordSeenRelay(f.payload)) return;
            if (owner || links.size() > 1) {
                broadcast(new Frame(Frame.MESH_RELAY, myName, f.payload), from);
            }
            handleMeshRelay(f.payload);
            return;
        }
        byte[] hembFrame = null;
        if (f.type == Frame.AUDIO_ADPCM) {
            byte[] rawAdpcm = f.payload;
            byte[] decodedPcm = Adpcm.decode(rawAdpcm);
            if (rawAdpcm != null && rawAdpcm.length == 84) {
                hembFrame = rawAdpcm;
            } else if (decodedPcm.length == Voice.FRAME_BYTES) {
                hembFrame = Adpcm.encodeNarrowband(decodedPcm);
            }
            f = new Frame(Frame.AUDIO, f.sender, decodedPcm,
                    f.createdMs, isPrioritySpeaker(f.sender));
        } else if (f.type == Frame.AUDIO && f.payload != null && f.payload.length == Voice.FRAME_BYTES) {
            hembFrame = Adpcm.encodeNarrowband(f.payload);
            if (isPrioritySpeaker(f.sender) && !f.priority) {
                f = new Frame(f.type, f.sender, f.payload, f.createdMs, true);
            }
        } else if (f.type == Frame.AUDIO_HEMB && isPrioritySpeaker(f.sender) && !f.priority) {
            f = new Frame(f.type, f.sender, f.payload, f.createdMs, true);
        }
        if (f.type == Frame.LEAVE && from != null) from.behind.remove(f.sender);
        if (owner || links.size() > 1) {
            broadcast(f, from);
        }
        switch (f.type) {
            case Frame.TEXT:
                recordHistory(f);
                cb.onText(f.sender, new String(f.payload, UTF8));
                break;
            case Frame.AUDIO:
                if (hembFrame != null) {
                    synchronized (rxHembWindows) {
                        byte[][] win = rxHembWindows.get(f.sender);
                        if (win == null) {
                            win = new byte[Adpcm.HEMB_WINDOW][];
                            rxHembWindows.put(f.sender, win);
                        }
                        Integer cntObj = rxHembCounts.get(f.sender);
                        int cnt = cntObj != null ? cntObj : 0;
                        if (cnt >= Adpcm.HEMB_WINDOW) cnt = 0;
                        win[cnt] = hembFrame;
                        rxHembCounts.put(f.sender, cnt + 1);
                    }
                }
                cb.onAudio(f.sender, f.payload);
                break;
            case Frame.AUDIO_HEMB:
                byte[] recoveredPcm = null;
                synchronized (rxHembWindows) {
                    byte[][] win = rxHembWindows.get(f.sender);
                    Integer cntObj = rxHembCounts.remove(f.sender);
                    int cnt = cntObj != null ? cntObj : 0;
                    if (win != null && cnt == Adpcm.HEMB_WINDOW - 1) {
                        win[Adpcm.HEMB_WINDOW - 1] = null;
                        byte[] recAdpcm = Adpcm.recoverHembFrame(win, Adpcm.HEMB_WINDOW - 1, f.payload);
                        if (recAdpcm != null) recoveredPcm = Adpcm.decode(recAdpcm);
                    }
                }
                if (recoveredPcm != null && recoveredPcm.length > 0) {
                    cb.onAudio(f.sender, recoveredPcm);
                }
                break;
            case Frame.TALK_START:
                synchronized (rxHembWindows) { rxHembCounts.remove(f.sender); }
                cb.onTalkState(f.sender, true);
                break;
            case Frame.TALK_END:
                synchronized (rxHembWindows) { rxHembCounts.remove(f.sender); }
                cb.onTalkState(f.sender, false);
                break;
            case Frame.PRESENCE:
                synchronized (presences) { presences.put(f.sender, f.payload); }
                cb.onPresence(f.sender, f.payload);
                break;
            case Frame.ANNOUNCE:
                recordHistory(f);
                cb.onAnnouncement(f.sender, new String(f.payload, UTF8));
                break;
            case Frame.VOICE_NOTE:
                recordHistory(f);
                cb.onVoiceNote(f.sender, f.payload);
                break;
            case Frame.IMAGE:
                cb.onImage(f.sender, f.payload);
                break;
            case Frame.IMAGE_CHUNK:
                byte[] img = images.add(f.sender, f.payload, System.currentTimeMillis());
                if (img != null) cb.onImage(f.sender, img);
                break;
            case Frame.VIDEO_CHUNK:
                if (Clips.index(f.payload) == 0) cb.onVideoIncoming(f.sender);
                byte[] clip = clips.add(f.sender, f.payload, System.currentTimeMillis());
                if (clip != null) cb.onVideo(f.sender, clip);
                break;
            case Frame.LEAVE:
                removeMember(f.sender);
                break;
            default:
                break;
        }
    }

    private void handleMeshRelay(byte[] env) {
        if (env == null || env.length < 3) return;
        byte innerType = env[0];
        int sLen = env[1] & 0xFF;
        if (sLen <= 0 || 2 + sLen > env.length) return;
        String originSender = new String(env, 2, sLen, UTF8).trim();
        if (originSender.isEmpty() || originSender.equals(myName)) return;
        byte[] innerPayload = java.util.Arrays.copyOfRange(env, 2 + sLen, env.length);
        switch (innerType) {
            case Frame.TEXT:
                cb.onText(originSender, new String(innerPayload, UTF8));
                break;
            case Frame.ANNOUNCE:
                cb.onAnnouncement(originSender, new String(innerPayload, UTF8));
                break;
            case Frame.PRESENCE:
                cb.onPresence(originSender, innerPayload);
                break;
            case Frame.AUDIO_ADPCM:
                byte[] pcm = Adpcm.decode(innerPayload);
                if (pcm.length > 0) cb.onAudio(originSender, pcm);
                break;
            case Frame.TALK_START:
                cb.onTalkState(originSender, true);
                break;
            case Frame.TALK_END:
                cb.onTalkState(originSender, false);
                break;
            default:
                break;
        }
    }

    @Override public void onClosed(Link link) {
        Link old = link.replacedLink;
        if (old != null) {
            link.replacedLink = null;
            old.close();
        }
        boolean wasActive = links.remove(link);
        boolean wasUplink = link == uplink;
        if (wasUplink) uplink = null;
        if (stopped || (!wasActive && !wasUplink)) return;
        lossCounter++;
        reportedClusterSize = 0;
        if (wasUplink) cb.onUplink(false);
        if (link.peerName == null) {
            if (links.isEmpty()) {
                clusterId = computeClusterId(myName, lossCounter);
                Runnable cl = clusterListener;
                if (cl != null) cl.run();
            }
            if (!owner && links.isEmpty()) cb.onRejected("Koneksi ke pemilik grup terputus");
            return;
        }
        for (String name : new ArrayList<String>(link.behind)) {
            if (stillReachable(name)) continue;
            if (owner || !links.isEmpty()) {
                broadcast(new Frame(Frame.LEAVE, name, null), null);
            }
            removeMember(name);
        }
        if (wasUplink || link.relay || links.isEmpty()) {
            clusterId = computeClusterId(myName, lossCounter);
            Runnable cl = clusterListener;
            if (cl != null) cl.run();
        }
        if (!links.isEmpty()) {
            broadcast(buildClusterFrame(), null);
        }
        final String lostPeer = link.peerName;
        final InetSocketAddress target = link.reconnectTarget;
        if (!stopped && !wasUplink && target != null && !stillReachable(lostPeer)) {
            scheduleAutoReconnect(lostPeer, target);
        }
    }

    private void scheduleAutoReconnect(final String expectedPeer, final InetSocketAddress target) {
        if (!activeReconnects.add(target)) return;
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                final long[] delays = { 450L, 1200L, 2500L, 4500L };
                try {
                    for (long delay : delays) {
                        try { Thread.sleep(delay); } catch (InterruptedException ie) { return; }
                        if (stopped || hasMember(expectedPeer) || links.size() >= MAX_LINKS) return;
                        Socket s = new Socket();
                        try {
                            s.connect(target, 3000);
                            if (stopped || hasMember(expectedPeer)) {
                                s.close();
                                return;
                            }
                            addLink(s, target);
                            return;
                        } catch (IOException ignored) {
                            try { s.close(); } catch (IOException ignoredClose) { }
                        }
                    }
                } finally {
                    activeReconnects.remove(target);
                }
            }
        }, "hub-reconnect");
        t.setDaemon(true);
        t.start();
    }

    private Link findHandoverLink(Link incoming, String sender) {
        if (incoming == null || sender == null) return null;
        for (Link l : links) {
            if (l != incoming && sender.equals(l.peerName) && l.lowBandwidth != incoming.lowBandwidth) {
                return l;
            }
        }
        return null;
    }

    private boolean stillReachable(String name) {
        if (name == null) return false;
        for (Link l : links) {
            if (l.behind.contains(name)) return true;
        }
        return false;
    }

    private boolean nameTaken(String name) {
        if (name.equals(myName)) return true;
        synchronized (members) { return members.containsKey(name); }
    }

    private void removeMember(String name) {
        boolean had;
        synchronized (members) { had = members.remove(name) != null; }
        synchronized (presences) { presences.remove(name); }
        synchronized (rxHembWindows) {
            rxHembWindows.remove(name);
            rxHembCounts.remove(name);
        }
        clips.drop(name);
        images.drop(name);
        if (had) {
            cb.onTalkState(name, false);
            cb.onMemberLeft(name);
        }
    }

    void stop() {
        synchronized (this) { stopped = true; }
        for (ServerSocket s : servers) {
            try { s.close(); } catch (IOException ignored) { }
        }
        for (Link l : links) l.close();
        links.clear();
    }
}
