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
        void onVideoIncoming(String from);
        void onVideo(String from, byte[] mp4);
        void onRejected(String reason);
        /** The owner of our group says how a bridge can reach it. */
        void onJoinInfo(byte[] info);
        void onUplink(boolean connected);
    }

    static final int PORT = 8988;
    static final int MAX_LINKS = 16;
    static final Charset UTF8 = Charset.forName("UTF-8");

    private final Callback cb;
    private final List<Link> links = new CopyOnWriteArrayList<Link>();
    private final Map<String, byte[]> members = new LinkedHashMap<String, byte[]>();
    private final Clips.Assembler clips = new Clips.Assembler();
    // Latest PRESENCE payload per member, replayed by the owner to phones that join later.
    private final Map<String, byte[]> presences = new LinkedHashMap<String, byte[]>();
    private volatile byte[] myPresence;
    private final String myName;
    private final byte[] myPhoto;
    private volatile boolean stopped;
    private volatile boolean owner;
    private final List<ServerSocket> servers = new CopyOnWriteArrayList<ServerSocket>();
    private volatile Link uplink;
    private volatile byte[] joinInfo;
    private int port = PORT;

    Hub(String myName, byte[] myPhoto, Callback cb) {
        this.myName = myName;
        this.myPhoto = myPhoto;
        this.cb = cb;
    }

    boolean hasPeers() { return !links.isEmpty(); }

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
                    s.bind(new InetSocketAddress(groupAddress, port));
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
        new Thread(new Runnable() {
            @Override public void run() {
                for (int attempt = 0; attempt < 15 && !stopped; attempt++) {
                    try {
                        Socket s = new Socket();
                        s.connect(new InetSocketAddress(host, port), 4000);
                        addLink(s);
                        return;
                    } catch (IOException e) {
                        try { Thread.sleep(1000); } catch (InterruptedException ie) { return; }
                    }
                }
                if (!stopped) cb.onStatus("Gagal tersambung ke pemilik grup");
            }
        }, "hub-client").start();
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
                        // Queued before the reader starts, so the far owner knows we are a
                        // bridge before any phone behind us is introduced.
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
        if (stopped) { s.close(); return; }
        Link link = new Link(s, this);
        link.send(new Frame(Frame.HELLO, myName, myPhoto));
        byte[] p = myPresence;
        if (p != null) link.send(new Frame(Frame.PRESENCE, myName, p));
        links.add(link);
        link.start();
    }

    void sendPresence(byte[] presence) {
        myPresence = presence;
        broadcast(new Frame(Frame.PRESENCE, myName, presence), null);
    }

    void sendAnnouncement(String text) {
        broadcast(new Frame(Frame.ANNOUNCE, myName, text.getBytes(UTF8)), null);
    }

    void sendText(String text) {
        broadcast(new Frame(Frame.TEXT, myName, text.getBytes(UTF8)), null);
    }

    void sendImage(byte[] jpeg) {
        broadcast(new Frame(Frame.IMAGE, myName, jpeg), null);
    }

    void sendVideo(byte[] mp4) {
        int id = new java.util.Random().nextInt();
        for (byte[] chunk : Clips.split(id, mp4)) {
            broadcast(new Frame(Frame.VIDEO_CHUNK, myName, chunk), null);
        }
    }

    void sendAudio(byte[] pcm) {
        broadcast(new Frame(Frame.AUDIO, myName, pcm), null);
    }

    void sendTalkState(boolean talking) {
        broadcast(new Frame(talking ? Frame.TALK_START : Frame.TALK_END, myName, null), null);
    }

    private void broadcast(Frame f, Link except) {
        for (Link l : links) {
            if (l != except) l.send(f);
        }
    }

    @Override public void onFrame(Link from, Frame f) {
        if (f.type == Frame.HELLO) {
            // The first HELLO on a link names the phone at the other end; a client
            // later receives relayed HELLOs of the other members over the same link.
            synchronized (members) {
                if (from.peerName == null) {
                    if (owner && nameTaken(f.sender)) {
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
                    if (owner) {
                        // Everyone we know of, including phones behind bridges.
                        for (Map.Entry<String, byte[]> e : members.entrySet()) {
                            if (!e.getKey().equals(f.sender)) from.send(new Frame(Frame.HELLO, e.getKey(), e.getValue()));
                        }
                        synchronized (presences) {
                            for (Map.Entry<String, byte[]> e : presences.entrySet()) {
                                from.send(new Frame(Frame.PRESENCE, e.getKey(), e.getValue()));
                            }
                        }
                        byte[] info = joinInfo;
                        if (info != null && from != uplink) from.send(new Frame(Frame.JOIN_INFO, myName, info));
                    }
                } else if (owner) {
                    // Only a bridge may introduce further phones, and never a name already in use.
                    if (!from.relay || nameTaken(f.sender)) return;
                    from.behind.add(f.sender);
                }
                members.put(f.sender, f.payload);
            }
            if (owner) broadcast(f, from);
            cb.onMemberJoined(f.sender, f.payload);
            return;
        }
        if (f.type == Frame.BRIDGE) {
            if (owner && f.sender.equals(from.peerName)) from.relay = true;
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
        if (owner) {
            // A sender belongs to the link it joined through: a plain member only speaks for
            // itself, a bridge for the phones behind it. This stops one member from posing
            // as another (fake SOS or announcements).
            if (!from.behind.contains(f.sender)) return;
            if (f.type == Frame.LEAVE) from.behind.remove(f.sender);
            broadcast(f, from);
        }
        switch (f.type) {
            case Frame.TEXT:
                cb.onText(f.sender, new String(f.payload, UTF8));
                break;
            case Frame.AUDIO:
                cb.onAudio(f.sender, f.payload);
                break;
            case Frame.TALK_START:
                cb.onTalkState(f.sender, true);
                break;
            case Frame.TALK_END:
                cb.onTalkState(f.sender, false);
                break;
            case Frame.PRESENCE:
                synchronized (presences) { presences.put(f.sender, f.payload); }
                cb.onPresence(f.sender, f.payload);
                break;
            case Frame.ANNOUNCE:
                cb.onAnnouncement(f.sender, new String(f.payload, UTF8));
                break;
            case Frame.IMAGE:
                cb.onImage(f.sender, f.payload);
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

    @Override public void onClosed(Link link) {
        links.remove(link);
        boolean wasUplink = link == uplink;
        if (wasUplink) uplink = null;
        if (stopped) return;
        if (wasUplink) cb.onUplink(false);
        if (link.peerName == null) {
            if (!owner && links.isEmpty()) cb.onRejected("Koneksi ke pemilik grup terputus");
            return;
        }
        if (owner) {
            // Everyone reached through this link is gone for the rest of us.
            for (String name : new ArrayList<String>(link.behind)) {
                broadcast(new Frame(Frame.LEAVE, name, null), null);
                removeMember(name);
            }
        } else {
            // Lost the group owner, and with it everyone it relayed for us.
            List<String> all;
            synchronized (members) { all = new ArrayList<String>(members.keySet()); }
            for (String n : all) removeMember(n);
        }
    }

    private boolean nameTaken(String name) {
        if (name.equals(myName)) return true;
        synchronized (members) { return members.containsKey(name); }
    }

    private void removeMember(String name) {
        boolean had;
        synchronized (members) { had = members.remove(name) != null; }
        synchronized (presences) { presences.remove(name); }
        clips.drop(name);
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
