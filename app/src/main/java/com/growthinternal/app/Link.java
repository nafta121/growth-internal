package com.growthinternal.app;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** A framed connection to one other phone (TCP or Bluetooth RFCOMM), with reader and writer threads. */
final class Link {
    interface Listener {
        void onFrame(Link link, Frame frame);
        void onClosed(Link link);
    }

    private static final int MAX_QUEUED_AUDIO = 25;
    private static final int MAX_LOW_BW_BULK = 160;
    static final long MAX_AUDIO_AGE_MS = 350L;
    // Each side pings when idle; a link silent for longer than the timeout is dropped,
    // so a phone walking out of range is noticed within seconds.
    private static final int PING_MS = 3000;
    private static final int TIMEOUT_MS = 10000;

    private final Closeable raw;
    final boolean lowBandwidth;
    private final Listener listener;
    private final DataInputStream in;
    private final DataOutputStream out;
    // BlueRange 3-tier queues: vital control/SOS -> realtime voice/chat -> chunked media bulk
    private final LinkedBlockingQueue<Frame> vital = new LinkedBlockingQueue<Frame>();
    private final LinkedBlockingQueue<Frame> queue = new LinkedBlockingQueue<Frame>();
    // Photos, voice notes, and clip chunks wait here and go out only when no vital/voice/chat is queued.
    private final LinkedBlockingQueue<Frame> bulk = new LinkedBlockingQueue<Frame>();
    // Put on the main queue to wake an idle writer when vital or bulk work arrives; never sent.
    private static final Frame WAKE = new Frame(Frame.PING, "", null);
    private volatile boolean closed;
    private Thread writer;
    volatile String peerName;
    volatile byte[] peerPhoto;
    // Members reachable through this link: just the peer, or everyone behind a bridge.
    final java.util.Set<String> behind = java.util.Collections.newSetFromMap(
            new java.util.concurrent.ConcurrentHashMap<String, Boolean>());
    // True for a link to or from a bridge, which carries frames of many senders.
    volatile boolean relay;
    volatile boolean sentBridge;
    volatile Link replacedLink;

    Link(Socket socket, Listener listener) throws IOException {
        this(configure(socket).getInputStream(), socket.getOutputStream(), socket, false, listener);
    }

    Link(InputStream in, OutputStream out, Closeable raw, boolean lowBandwidth, Listener listener) {
        this.raw = raw;
        this.lowBandwidth = lowBandwidth;
        this.listener = listener;
        this.in = new DataInputStream(new BufferedInputStream(in));
        this.out = new DataOutputStream(new BufferedOutputStream(out));
    }

    private static Socket configure(Socket socket) throws IOException {
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(TIMEOUT_MS);
        return socket;
    }

    void start() {
        writer = new Thread(new Runnable() {
            @Override public void run() { writeLoop(); }
        }, "link-writer");
        Thread reader = new Thread(new Runnable() {
            @Override public void run() { readLoop(); }
        }, "link-reader");
        writer.start();
        reader.start();
    }

    void send(Frame f) {
        if (closed) return;
        // Drop audio rather than build up delay when the radio link is slow.
        if (f.type == Frame.AUDIO || f.type == Frame.AUDIO_ADPCM || f.type == Frame.AUDIO_HEMB) {
            if (!f.priority && queue.size() > MAX_QUEUED_AUDIO) return;
            if (f.priority && vital.size() > MAX_QUEUED_AUDIO) return;
            if (System.currentTimeMillis() - f.createdMs > MAX_AUDIO_AGE_MS) return;
            if ((lowBandwidth || relay) && f.type == Frame.AUDIO && f.payload.length >= 4) {
                byte[] enc = (lowBandwidth && relay)
                        ? Adpcm.encodeNarrowband(f.payload)
                        : Adpcm.encode(f.payload);
                f = new Frame(Frame.AUDIO_ADPCM, f.sender, enc, f.createdMs, f.priority);
            }
            if (f.priority) {
                vital.offer(f);
                if (queue.isEmpty()) queue.offer(WAKE);
                return;
            }
        }
        if (f.type == Frame.IMAGE || f.type == Frame.IMAGE_CHUNK
                || f.type == Frame.VIDEO_CHUNK || f.type == Frame.VOICE_NOTE) {
            if (lowBandwidth && bulk.size() >= MAX_LOW_BW_BULK) return;
            bulk.offer(f);
            if (queue.isEmpty()) queue.offer(WAKE);
        } else if (f.type == Frame.AUDIO || f.type == Frame.AUDIO_ADPCM || f.type == Frame.AUDIO_HEMB
                || f.type == Frame.TEXT || f.type == Frame.ANNOUNCE) {
            queue.offer(f);
        } else {
            vital.offer(f);
            if (queue.isEmpty()) queue.offer(WAKE);
        }
    }

    boolean isClosed() { return closed; }

    private void writeLoop() {
        try {
            while (!closed) {
                Frame f = vital.poll();
                if (f == null) f = queue.poll();
                if (f == null) f = bulk.poll();
                if (f == null) f = queue.poll(PING_MS, TimeUnit.MILLISECONDS);
                if (f == WAKE) {
                    f = vital.poll();
                    if (f == null) f = queue.poll();
                    if (f == null) f = bulk.poll();
                    if (f == null) {
                        out.flush();
                        continue;
                    }
                }
                if (f == null) f = new Frame(Frame.PING, "", null);
                if ((f.type == Frame.AUDIO || f.type == Frame.AUDIO_ADPCM || f.type == Frame.AUDIO_HEMB)
                        && System.currentTimeMillis() - f.createdMs > MAX_AUDIO_AGE_MS) {
                    continue;
                }
                out.writeByte(f.type);
                out.writeUTF(f.sender);
                out.writeInt(f.payload.length);
                out.write(f.payload);
                if (vital.isEmpty() && (queue.isEmpty() || queue.peek() == WAKE)) out.flush();
            }
        } catch (InterruptedException ignored) {
        } catch (IOException e) {
            close();
        }
    }

    private void readLoop() {
        try {
            while (!closed) {
                byte type = in.readByte();
                String sender = in.readUTF();
                int n = in.readInt();
                if (n < 0 || n > Frame.maxPayload(type) || sender.length() > 40) {
                    throw new IOException("bad frame");
                }
                byte[] p = new byte[n];
                in.readFully(p);
                if (type == Frame.PING) continue;
                listener.onFrame(this, new Frame(type, sender, p));
            }
        } catch (IOException e) {
            close();
        }
    }

    void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
        }
        try { raw.close(); } catch (IOException ignored) { }
        if (writer != null) writer.interrupt();
        listener.onClosed(this);
    }
}
