package com.salingjaga.app;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Short video clips travel as VIDEO_CHUNK frames of at most CHUNK bytes, so a clip never
 * holds up voice for long and a lost link costs only the clip in flight.
 * Chunk payload: int clipId, short index, short total, then the bytes.
 */
final class Clips {
    static final int CHUNK = 32 * 1024;
    static final int MAX_BYTES = 4 * 1024 * 1024;
    static final int MAX_CHUNKS = MAX_BYTES / CHUNK;
    static final int HEADER = 8;
    private static final int MAX_PENDING = 4;
    private static final long STALE_MS = 60000;

    private Clips() { }

    static List<byte[]> split(int clipId, byte[] data) {
        if (data.length == 0 || data.length > MAX_BYTES) throw new IllegalArgumentException("clip size");
        int total = (data.length + CHUNK - 1) / CHUNK;
        List<byte[]> out = new ArrayList<byte[]>(total);
        for (int i = 0; i < total; i++) {
            int off = i * CHUNK;
            int n = Math.min(CHUNK, data.length - off);
            ByteBuffer b = ByteBuffer.allocate(HEADER + n);
            b.putInt(clipId).putShort((short) i).putShort((short) total).put(data, off, n);
            out.add(b.array());
        }
        return out;
    }

    /** Index of a chunk payload, or -1 if malformed; index 0 announces a new clip. */
    static int index(byte[] payload) {
        if (payload.length < HEADER) return -1;
        return ByteBuffer.wrap(payload).getShort(4);
    }

    /** Collects chunks per sender and clip; bounded so a peer cannot exhaust memory. */
    static final class Assembler {
        private static final class Pending {
            final byte[][] parts;
            int received;
            long touched;
            Pending(int total) { parts = new byte[total][]; }
        }

        private final Map<String, Pending> pending = new HashMap<String, Pending>();

        /** Returns the whole clip when its last chunk arrives, otherwise null. */
        synchronized byte[] add(String sender, byte[] payload, long now) {
            if (payload.length <= HEADER || payload.length > HEADER + CHUNK) return null;
            ByteBuffer b = ByteBuffer.wrap(payload);
            int id = b.getInt();
            int index = b.getShort();
            int total = b.getShort();
            if (total <= 0 || total > MAX_CHUNKS || index < 0 || index >= total) return null;
            String key = sender + "\u0000" + id;
            Pending p = pending.get(key);
            if (p == null) {
                dropStale(now);
                if (pending.size() >= MAX_PENDING) return null;
                p = new Pending(total);
                pending.put(key, p);
            }
            if (p.parts.length != total || p.parts[index] != null) return null;
            if (index < total - 1 && payload.length != HEADER + CHUNK) return null;
            p.parts[index] = java.util.Arrays.copyOfRange(payload, HEADER, payload.length);
            p.received++;
            p.touched = now;
            if (p.received < total) return null;
            pending.remove(key);
            int size = 0;
            for (byte[] part : p.parts) size += part.length;
            byte[] out = new byte[size];
            int off = 0;
            for (byte[] part : p.parts) {
                System.arraycopy(part, 0, out, off, part.length);
                off += part.length;
            }
            return out;
        }

        /** Forget clips from a member who left. */
        synchronized void drop(String sender) {
            Iterator<String> it = pending.keySet().iterator();
            while (it.hasNext()) if (it.next().startsWith(sender + "\u0000")) it.remove();
        }

        private void dropStale(long now) {
            Iterator<Map.Entry<String, Pending>> it = pending.entrySet().iterator();
            while (it.hasNext()) if (now - it.next().getValue().touched > STALE_MS) it.remove();
        }
    }
}
