package com.growthinternal.app;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Captures the microphone and plays incoming voice, as raw 16 kHz mono PCM. */
final class Voice {
    interface Sink { void onPcm(byte[] pcm); }

    static final int RATE = 16000;
    static final int FRAME_BYTES = 640; // 20 ms

    private final Map<String, AudioTrack> tracks = new ConcurrentHashMap<String, AudioTrack>();
    private final Map<String, byte[]> lastFrames = new ConcurrentHashMap<String, byte[]>();
    private final Map<String, Long> lastTimes = new ConcurrentHashMap<String, Long>();
    private volatile boolean capturing;
    private Thread captureThread;

    /**
     * MeshLive-inspired Packet Loss Concealment (PLC): generates an attenuated continuation
     * frame (fading 75% -> 25%) from the last good 16-bit PCM frame when a brief gap occurs.
     */
    static byte[] concealFrame(byte[] prevPcm) {
        if (prevPcm == null || (prevPcm.length & 1) != 0 || prevPcm.length == 0) return new byte[0];
        int samples = prevPcm.length / 2;
        byte[] out = new byte[prevPcm.length];
        for (int i = 0; i < samples; i++) {
            int lo = prevPcm[i * 2] & 0xFF;
            int hi = prevPcm[i * 2 + 1];
            short s = (short) ((hi << 8) | lo);
            int attenuated = (s * (3 * samples - 2 * i)) / (4 * samples);
            out[i * 2] = (byte) (attenuated & 0xFF);
            out[i * 2 + 1] = (byte) ((attenuated >> 8) & 0xFF);
        }
        return out;
    }

    synchronized boolean isCapturing() { return capturing; }

    synchronized void startCapture(final Sink sink) {
        if (capturing) return;
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        final AudioRecord rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                Math.max(min, FRAME_BYTES * 8));
        if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
            rec.release();
            throw new IllegalStateException("Mikrofon tidak bisa dibuka");
        }
        if (AcousticEchoCanceler.isAvailable()) {
            AcousticEchoCanceler aec = AcousticEchoCanceler.create(rec.getAudioSessionId());
            if (aec != null) aec.setEnabled(true);
        }
        if (NoiseSuppressor.isAvailable()) {
            NoiseSuppressor ns = NoiseSuppressor.create(rec.getAudioSessionId());
            if (ns != null) ns.setEnabled(true);
        }
        capturing = true;
        captureThread = new Thread(new Runnable() {
            @Override public void run() {
                rec.startRecording();
                byte[] buf = new byte[FRAME_BYTES];
                while (capturing) {
                    int n = rec.read(buf, 0, buf.length);
                    if (n > 0) {
                        byte[] copy = new byte[n];
                        System.arraycopy(buf, 0, copy, 0, n);
                        sink.onPcm(copy);
                    }
                }
                rec.stop();
                rec.release();
            }
        }, "voice-capture");
        captureThread.start();
    }

    synchronized void stopCapture() {
        capturing = false;
        captureThread = null;
    }

    /** Called from network threads; each sender gets its own track. */
    void play(String from, byte[] pcm) {
        AudioTrack t = tracks.get(from);
        if (t == null) {
            synchronized (this) {
                t = tracks.get(from);
                if (t == null) {
                    int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO,
                            AudioFormat.ENCODING_PCM_16BIT);
                    t = new AudioTrack(AudioManager.STREAM_VOICE_CALL, RATE, AudioFormat.CHANNEL_OUT_MONO,
                            AudioFormat.ENCODING_PCM_16BIT, Math.max(min, FRAME_BYTES * 10),
                            AudioTrack.MODE_STREAM);
                    if (t.getState() != AudioTrack.STATE_INITIALIZED) {
                        t.release();
                        return;
                    }
                    t.play();
                    tracks.put(from, t);
                }
            }
        }
        long now = System.currentTimeMillis();
        Long prevMs = lastTimes.put(from, now);
        byte[] prev = lastFrames.put(from, pcm);
        try {
            if (prevMs != null && prev != null && prev.length == FRAME_BYTES) {
                long gap = now - prevMs;
                if (gap >= 35L && gap <= 95L) {
                    byte[] plc = concealFrame(prev);
                    t.write(plc, 0, plc.length);
                }
            }
            t.write(pcm, 0, pcm.length);
        } catch (IllegalStateException ignored) {
            // Track was released while this frame was in flight.
        }
    }

    synchronized void drop(String from) {
        lastFrames.remove(from);
        lastTimes.remove(from);
        AudioTrack t = tracks.remove(from);
        if (t != null) {
            try { t.stop(); } catch (IllegalStateException ignored) { }
            t.release();
        }
    }

    synchronized void release() {
        stopCapture();
        lastFrames.clear();
        lastTimes.clear();
        for (AudioTrack t : tracks.values()) {
            try { t.stop(); } catch (IllegalStateException ignored) { }
            t.release();
        }
        tracks.clear();
    }
}
