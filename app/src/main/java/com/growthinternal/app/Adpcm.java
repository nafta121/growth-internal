package com.growthinternal.app;

/**
 * 4:1 IMA ADPCM speech codec for Bluetooth RFCOMM links (~64 kbps instead of 256 kbps).
 * Each packet starts with a 4-byte state header (int16 sample + int8 step index + 0),
 * so every 20 ms frame is self-contained and tolerates dropped frames.
 */
final class Adpcm {
    private static final int[] INDEX_TABLE = {
            -1, -1, -1, -1, 2, 4, 6, 8,
            -1, -1, -1, -1, 2, 4, 6, 8
    };
    private static final int[] STEP_TABLE = {
            7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
            50, 55, 60, 66, 73, 80, 88, 97, 107, 118, 130, 143, 157, 173, 190, 209, 230,
            253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658, 724, 796, 876, 963,
            1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024, 3327,
            3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442,
            11487, 12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767
    };

    static final int NARROWBAND_FLAG = 0x02;
    private static final int GF_POLY = 0x11B;
    static final int HEMB_WINDOW = 4;
    static final int HEMB_HEADER = 6;
    static final int[] HEMB_DEFAULT_COEFFS = { 1, 2, 3, 5 };
    static final int[] HEMB_STREAM_COEFFS = { 1, 1, 1, 1 };

    private Adpcm() { }

    static byte[] encode(byte[] pcm) {
        if (pcm == null || pcm.length < 2) return new byte[0];
        int samples = pcm.length / 2;
        byte[] out = new byte[4 + (samples + 1) / 2];
        int pred = (short) ((pcm[0] & 0xff) | (pcm[1] << 8));
        int idx = 0;
        out[0] = (byte) pred;
        out[1] = (byte) (pred >> 8);
        out[2] = 0;
        out[3] = (byte) (samples & 1);
        int outPos = 4;
        int pack = 0;
        for (int i = 0; i < samples; i++) {
            int s = (short) ((pcm[i * 2] & 0xff) | (pcm[i * 2 + 1] << 8));
            int step = STEP_TABLE[idx];
            int diff = s - pred;
            int nib = 0;
            if (diff < 0) {
                nib = 8;
                diff = -diff;
            }
            int vpdiff = step >> 3;
            if (diff >= step) { nib |= 4; diff -= step; vpdiff += step; }
            step >>= 1;
            if (diff >= step) { nib |= 2; diff -= step; vpdiff += step; }
            step >>= 1;
            if (diff >= step) { nib |= 1; vpdiff += step; }
            pred = (nib & 8) != 0 ? Math.max(-32768, pred - vpdiff) : Math.min(32767, pred + vpdiff);
            idx = Math.max(0, Math.min(88, idx + INDEX_TABLE[nib]));
            if ((i & 1) == 0) {
                pack = nib & 0x0f;
            } else {
                out[outPos++] = (byte) (pack | ((nib & 0x0f) << 4));
            }
        }
        if ((samples & 1) != 0) out[outPos] = (byte) pack;
        return out;
    }

    /**
     * 8:1 Narrowband 8 kHz IMA ADPCM (32 kbps, 84 bytes per 20 ms frame) for multi-hop
     * kilometer chains and Wi-Fi Aware L2 Sidelink. Decimates 16 kHz -> 8 kHz with a
     * 2-tap anti-aliasing filter and marks bit 1 (0x02) of header byte 3.
     */
    static byte[] encodeNarrowband(byte[] pcm16k) {
        if (pcm16k == null || pcm16k.length < 4) return encode(pcm16k);
        int inSamples = pcm16k.length / 2;
        int outSamples = inSamples / 2;
        byte[] pcm8k = new byte[outSamples * 2];
        for (int i = 0; i < outSamples; i++) {
            int i0 = i * 4;
            int s0 = (short) ((pcm16k[i0] & 0xff) | (pcm16k[i0 + 1] << 8));
            int s1 = (short) ((pcm16k[i0 + 2] & 0xff) | (pcm16k[i0 + 3] << 8));
            int avg = (s0 + s1) >> 1;
            pcm8k[i * 2] = (byte) avg;
            pcm8k[i * 2 + 1] = (byte) (avg >> 8);
        }
        byte[] out = encode(pcm8k);
        if (out.length >= 4) out[3] |= NARROWBAND_FLAG;
        return out;
    }

    static byte[] decode(byte[] adpcm) {
        if (adpcm == null || adpcm.length <= 4) return new byte[0];
        int pred = (short) ((adpcm[0] & 0xff) | (adpcm[1] << 8));
        int idx = Math.max(0, Math.min(88, adpcm[2] & 0xff));
        int odd = adpcm[3] & 1;
        boolean narrowband = (adpcm[3] & NARROWBAND_FLAG) != 0;
        int samples = (adpcm.length - 4) * 2 - odd;
        byte[] pcm = new byte[samples * 2];
        for (int i = 0; i < samples; i++) {
            int b = adpcm[4 + (i >> 1)] & 0xff;
            int nib = (i & 1) == 0 ? (b & 0x0f) : ((b >> 4) & 0x0f);
            int step = STEP_TABLE[idx];
            int vpdiff = step >> 3;
            if ((nib & 4) != 0) vpdiff += step;
            if ((nib & 2) != 0) vpdiff += step >> 1;
            if ((nib & 1) != 0) vpdiff += step >> 2;
            pred = (nib & 8) != 0 ? Math.max(-32768, pred - vpdiff) : Math.min(32767, pred + vpdiff);
            idx = Math.max(0, Math.min(88, idx + INDEX_TABLE[nib]));
            pcm[i * 2] = (byte) pred;
            pcm[i * 2 + 1] = (byte) (pred >> 8);
        }
        if (!narrowband || samples == 0) return pcm;
        // Linear 1:2 upsampling from 8 kHz back to 16 kHz (640 bytes per 20 ms)
        byte[] up = new byte[samples * 4];
        for (int i = 0; i < samples; i++) {
            int s0 = (short) ((pcm[i * 2] & 0xff) | (pcm[i * 2 + 1] << 8));
            int s1 = (i + 1 < samples)
                    ? (short) ((pcm[(i + 1) * 2] & 0xff) | (pcm[(i + 1) * 2 + 1] << 8))
                    : s0;
            int mid = (s0 + s1) >> 1;
            int dst = i * 4;
            up[dst] = (byte) s0;
            up[dst + 1] = (byte) (s0 >> 8);
            up[dst + 2] = (byte) mid;
            up[dst + 3] = (byte) (mid >> 8);
        }
        return up;
    }

    /** MeshSat HeMB GF(2^8) multiplication modulo x^8 + x^4 + x^3 + x + 1 (0x11B). */
    static int gfMul(int a, int b) {
        int res = 0;
        int x = a & 0xFF;
        int y = b & 0xFF;
        for (int i = 0; i < 8; i++) {
            if ((y & 1) != 0) res ^= x;
            boolean hi = (x & 0x80) != 0;
            x = (x << 1) & 0xFF;
            if (hi) x ^= (GF_POLY & 0xFF);
            y >>>= 1;
        }
        return res & 0xFF;
    }

    /** MeshSat HeMB GF(2^8) multiplicative inverse via Fermat's little theorem a^254. */
    static int gfInv(int a) {
        int x = a & 0xFF;
        if (x == 0) return 0;
        int res = 1;
        int exp = 254;
        while (exp > 0) {
            if ((exp & 1) != 0) res = gfMul(res, x);
            x = gfMul(x, x);
            exp >>>= 1;
        }
        return res;
    }

    /**
     * Encodes a MeshSat HeMB GF(2^8) RLNC parity frame over 4 consecutive voice frames.
     * Header: [seqBase_hi, seqBase_lo, c0, c1, c2, c3] followed by GF(2^8) linear combination.
     */
    static byte[] encodeHembParity(int seqBase, byte[][] frames) {
        return encodeHembParity(seqBase, frames, HEMB_STREAM_COEFFS);
    }

    static byte[] encodeHembParity(int seqBase, byte[][] frames, int[] coeffs) {
        if (frames == null || frames.length != HEMB_WINDOW || coeffs == null || coeffs.length != HEMB_WINDOW) {
            return null;
        }
        int len = frames[0] != null ? frames[0].length : 0;
        if (len == 0) return null;
        for (int i = 1; i < HEMB_WINDOW; i++) {
            if (frames[i] == null || frames[i].length != len) return null;
        }
        byte[] out = new byte[HEMB_HEADER + len];
        out[0] = (byte) ((seqBase >>> 8) & 0xFF);
        out[1] = (byte) (seqBase & 0xFF);
        for (int j = 0; j < HEMB_WINDOW; j++) {
            out[2 + j] = (byte) (coeffs[j] & 0xFF);
        }
        for (int b = 0; b < len; b++) {
            int acc = 0;
            for (int j = 0; j < HEMB_WINDOW; j++) {
                acc ^= gfMul(coeffs[j] & 0xFF, frames[j][b] & 0xFF);
            }
            out[HEMB_HEADER + b] = (byte) acc;
        }
        return out;
    }

    /**
     * Reconstructs a missing voice frame at missingIdx in 0 ms using GF(2^8) Gauss-Jordan elimination
     * when the other 3 frames in the window and the HeMB parity packet have arrived.
     */
    static byte[] recoverHembFrame(byte[][] frames, int missingIdx, byte[] parityPacket) {
        if (frames == null || frames.length != HEMB_WINDOW
                || missingIdx < 0 || missingIdx >= HEMB_WINDOW
                || parityPacket == null || parityPacket.length <= HEMB_HEADER) {
            return null;
        }
        int len = parityPacket.length - HEMB_HEADER;
        int cMissing = parityPacket[2 + missingIdx] & 0xFF;
        if (cMissing == 0) return null;
        for (int j = 0; j < HEMB_WINDOW; j++) {
            if (j == missingIdx) continue;
            if (frames[j] == null || frames[j].length != len) return null;
        }
        int inv = gfInv(cMissing);
        byte[] recovered = new byte[len];
        for (int b = 0; b < len; b++) {
            int syn = parityPacket[HEMB_HEADER + b] & 0xFF;
            for (int j = 0; j < HEMB_WINDOW; j++) {
                if (j != missingIdx) {
                    int cj = parityPacket[2 + j] & 0xFF;
                    syn ^= gfMul(cj, frames[j][b] & 0xFF);
                }
            }
            recovered[b] = (byte) gfMul(inv, syn);
        }
        return recovered;
    }
}
