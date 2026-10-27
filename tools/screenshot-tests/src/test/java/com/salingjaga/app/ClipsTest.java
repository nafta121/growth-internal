package com.salingjaga.app;

import static org.junit.Assert.*;

import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class ClipsTest {
    static byte[] data(int n) {
        byte[] d = new byte[n];
        for (int i = 0; i < n; i++) d[i] = (byte) (i * 31);
        return d;
    }

    @Test public void splitsAndReassemblesInAnyOrder() {
        byte[] d = data(3 * Clips.CHUNK + 10);
        List<byte[]> parts = Clips.split(42, d);
        assertEquals(4, parts.size());
        assertEquals(0, Clips.index(parts.get(0)));
        Collections.reverse(parts);
        Clips.Assembler a = new Clips.Assembler();
        byte[] out = null;
        for (byte[] p : parts) {
            assertNull(out);
            out = a.add("Budi", p, 0);
        }
        assertArrayEquals(d, out);
    }

    @Test public void keepsSendersApart() {
        Clips.Assembler a = new Clips.Assembler();
        List<byte[]> x = Clips.split(1, data(Clips.CHUNK + 1));
        assertNull(a.add("Budi", x.get(0), 0));
        assertNull(a.add("Sari", x.get(1), 0));
        assertNotNull(a.add("Budi", x.get(1), 0));
    }

    @Test public void rejectsMalformedChunks() {
        Clips.Assembler a = new Clips.Assembler();
        assertNull(a.add("Eve", new byte[4], 0));
        assertEquals(-1, Clips.index(new byte[3]));
        List<byte[]> x = Clips.split(5, data(2 * Clips.CHUNK));
        assertNull(a.add("Eve", x.get(0), 0));
        assertNull(a.add("Eve", x.get(0), 0));        // duplicate ignored
        byte[] bad = x.get(1).clone();
        bad[6] = 0; bad[7] = 3;                         // total changed mid-clip
        assertNull(a.add("Eve", bad, 0));
        byte[] shortMiddle = java.util.Arrays.copyOf(Clips.split(6, data(2 * Clips.CHUNK)).get(0), 100);
        assertNull(a.add("Eve", shortMiddle, 0));       // only the last chunk may be short
    }

    @Test public void boundsPendingClipsAndForgetsStaleOnes() {
        Clips.Assembler a = new Clips.Assembler();
        for (int id = 0; id < 4; id++) assertNull(a.add("Eve", Clips.split(id, data(Clips.CHUNK + 1)).get(0), 0));
        List<byte[]> fifth = Clips.split(99, data(Clips.CHUNK + 1));
        assertNull(a.add("Eve", fifth.get(0), 1000));
        assertNull(a.add("Eve", fifth.get(1), 1000));  // refused: four clips already pending
        assertNull(a.add("Eve", fifth.get(0), 120000)); // stale ones dropped, room again
        assertNotNull(a.add("Eve", fifth.get(1), 120000));
        a.add("Budi", Clips.split(7, data(Clips.CHUNK + 1)).get(0), 0);
        a.drop("Budi");
        assertNull(a.add("Budi", Clips.split(7, data(Clips.CHUNK + 1)).get(1), 0));
    }

    @Test(expected = IllegalArgumentException.class)
    public void refusesOversizedClip() {
        Clips.split(1, new byte[Clips.MAX_BYTES + 1]);
    }
}
