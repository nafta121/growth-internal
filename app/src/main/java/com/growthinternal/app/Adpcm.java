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

    static byte[] decode(byte[] adpcm) {
        if (adpcm == null || adpcm.length <= 4) return new byte[0];
        int pred = (short) ((adpcm[0] & 0xff) | (adpcm[1] << 8));
        int idx = Math.max(0, Math.min(88, adpcm[2] & 0xff));
        int odd = adpcm[3] & 1;
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
        return pcm;
    }
}
