package com.growthinternal.app;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.os.Process;
import android.os.SystemClock;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Captures the microphone and plays incoming voice, as raw 16 kHz mono PCM. */
final class Voice {
    interface Sink { void onPcm(byte[] pcm); }

    static final int RATE = 16000;
    static final int FRAME_BYTES = 640; // 20 ms
    static final int FRAME_MS = 20;
    static final int PRE_ROLL_FRAMES = 3;   // 60 ms pre-roll absorbs Wi-Fi/BLE jitter
    static final int MAX_JITTER_FRAMES = 12; // 240 ms latency cap
    static final int MAX_PLC_FRAMES = 2;    // up to 40 ms zero-underrun concealment
    private static final long SHORT_SPURT_WAIT_MS = 120L;
    private static final long MAX_HW_CUSHION_MS = 140L;
    private static final long PLC_TRIGGER_CUSHION_MS = 12L;
    private static final long IDLE_RELEASE_MS = 3000L;

    /**
     * Adaptive pre-roll jitter buffer with bounded latency and automatic Packet Loss Concealment.
     * Buffers PRE_ROLL_FRAMES (60 ms) before starting a talk spurt so wireless packet arrival
     * jitter never starves AudioTrack, and synthesizes up to MAX_PLC_FRAMES (40 ms) when late.
     */
    static final class JitterBuffer {
        private final ArrayDeque<byte[]> queue = new ArrayDeque<byte[]>(MAX_JITTER_FRAMES);
        private boolean primed;
        private long firstBufferedMs;
        private byte[] lastGoodFrame;
        private int plcCount;

        synchronized void offer(byte[] pcm, long nowMs) {
            if (pcm == null || pcm.length == 0) return;
            while (queue.size() >= MAX_JITTER_FRAMES) {
                queue.pollFirst();
            }
            if (queue.isEmpty() && !primed) {
                firstBufferedMs = nowMs;
            }
            queue.addLast(pcm);
            if (queue.size() >= PRE_ROLL_FRAMES) {
                primed = true;
            }
            notifyAll();
        }

        synchronized byte[] pollQueued(long nowMs) {
            if (!primed) {
                if (queue.isEmpty()) return null;
                if (queue.size() >= PRE_ROLL_FRAMES
                        || (nowMs - firstBufferedMs) >= SHORT_SPURT_WAIT_MS) {
                    primed = true;
                } else {
                    return null;
                }
            }
            byte[] next = queue.pollFirst();
            if (next != null) {
                lastGoodFrame = next;
                plcCount = 0;
            }
            return next;
        }

        synchronized byte[] pollNext(long nowMs) {
            byte[] next = pollQueued(nowMs);
            if (next != null) return next;
            if (!primed) return null;
            if (lastGoodFrame != null && lastGoodFrame.length == FRAME_BYTES
                    && plcCount < MAX_PLC_FRAMES) {
                plcCount++;
                byte[] plc = concealFrame(lastGoodFrame);
                lastGoodFrame = plc;
                return plc;
            }
            primed = false;
            plcCount = 0;
            return null;
        }

        synchronized int size() { return queue.size(); }

        synchronized boolean isPrimed() { return primed; }
    }

    private final class PlayoutWorker implements Runnable {
        final String sender;
        final AudioTrack track;
        final JitterBuffer jitter = new JitterBuffer();
        final AtomicBoolean running = new AtomicBoolean(true);
        private boolean trackReleased;

        PlayoutWorker(String sender, AudioTrack track) {
            this.sender = sender;
            this.track = track;
        }

        @Override public void run() {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
            } catch (RuntimeException ignored) {
            }
            final byte[] leadInSilence = new byte[FRAME_BYTES];
            long playoutEndMs = 0L;
            long idleSince = SystemClock.elapsedRealtime();
            while (running.get()) {
                long now = SystemClock.elapsedRealtime();
                long cushionMs = playoutEndMs - now;
                byte[] frame = cushionMs < MAX_HW_CUSHION_MS ? jitter.pollQueued(now) : null;
                if (frame != null) {
                    if (cushionMs <= 0L) {
                        // Cross AudioFlinger's half-buffer start threshold immediately on spurt start
                        if (!writeTrack(leadInSilence)) break;
                        now = SystemClock.elapsedRealtime();
                        playoutEndMs = now + FRAME_MS;
                    }
                    if (!writeTrack(frame)) {
                        break;
                    }
                    long after = SystemClock.elapsedRealtime();
                    playoutEndMs = Math.max(after, playoutEndMs) + FRAME_MS;
                    idleSince = after;
                    continue;
                }
                if (jitter.isPrimed()) {
                    if (cushionMs <= PLC_TRIGGER_CUSHION_MS) {
                        byte[] plc = jitter.pollNext(now);
                        if (plc != null) {
                            if (!writeTrack(plc)) break;
                            long after = SystemClock.elapsedRealtime();
                            playoutEndMs = Math.max(after, playoutEndMs) + FRAME_MS;
                            idleSince = after;
                            continue;
                        }
                    } else {
                        long waitMs = Math.min(20L, Math.max(2L, cushionMs - PLC_TRIGGER_CUSHION_MS));
                        synchronized (jitter) {
                            if (running.get() && (jitter.size() == 0
                                    || (playoutEndMs - SystemClock.elapsedRealtime()) >= MAX_HW_CUSHION_MS)) {
                                try {
                                    jitter.wait(waitMs);
                                } catch (InterruptedException e) {
                                    break;
                                }
                            }
                        }
                        continue;
                    }
                }
                if (now - idleSince >= IDLE_RELEASE_MS) {
                    break;
                }
                synchronized (jitter) {
                    if (running.get() && jitter.size() == 0) {
                        try {
                            jitter.wait(20L);
                        } catch (InterruptedException e) {
                            break;
                        }
                    }
                }
            }
            closeSelf();
        }

        void stop() {
            if (running.compareAndSet(true, false)) {
                synchronized (jitter) {
                    jitter.notifyAll();
                }
            }
            releaseTrack();
        }

        private void closeSelf() {
            running.set(false);
            workers.remove(sender, this);
            releaseTrack();
        }

        private boolean writeTrack(byte[] frame) {
            synchronized (this) {
                if (trackReleased || !running.get()) return false;
                try {
                    track.write(frame, 0, frame.length);
                    return true;
                } catch (RuntimeException e) {
                    return false;
                }
            }
        }

        private void releaseTrack() {
            synchronized (this) {
                if (trackReleased) return;
                trackReleased = true;
                try { track.stop(); } catch (RuntimeException ignored) { }
                try { track.release(); } catch (RuntimeException ignored) { }
            }
        }
    }

    private final ConcurrentHashMap<String, PlayoutWorker> workers =
            new ConcurrentHashMap<String, PlayoutWorker>();
    private volatile boolean capturing;
    private AtomicBoolean activeCapture;
    private Thread captureThread;

    /**
     * MeshLive-inspired Packet Loss Concealment (PLC): generates a cross-faded, attenuated
     * continuation frame (fading 75% -> 25%) from the last good 16-bit PCM frame without boundary clicks.
     */
    static byte[] concealFrame(byte[] prevPcm) {
        if (prevPcm == null || (prevPcm.length & 1) != 0 || prevPcm.length == 0) return new byte[0];
        int samples = prevPcm.length / 2;
        byte[] out = new byte[prevPcm.length];
        int lastLo = prevPcm[(samples - 1) * 2] & 0xFF;
        int lastHi = prevPcm[(samples - 1) * 2 + 1];
        int lastSample = (short) ((lastHi << 8) | lastLo);
        int blendLen = Math.min(16, samples);
        for (int i = 0; i < samples; i++) {
            int lo = prevPcm[i * 2] & 0xFF;
            int hi = prevPcm[i * 2 + 1];
            int s = (short) ((hi << 8) | lo);
            if (i < blendLen) {
                s = ((lastSample * (blendLen - i)) + (s * i)) / blendLen;
            }
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
        final AtomicBoolean sessionActive = new AtomicBoolean(true);
        activeCapture = sessionActive;
        capturing = true;
        captureThread = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
                } catch (RuntimeException ignored) {
                }
                rec.startRecording();
                byte[] buf = new byte[FRAME_BYTES];
                while (sessionActive.get()) {
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
        if (activeCapture != null) {
            activeCapture.set(false);
            activeCapture = null;
        }
        Thread t = captureThread;
        captureThread = null;
        if (t != null && t != Thread.currentThread()) {
            try { t.join(250); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
    }

    /** Enqueues incoming PCM onto the sender's non-blocking jitter buffer playout worker. */
    void play(String from, byte[] pcm) {
        if (from == null || pcm == null || pcm.length == 0) return;
        PlayoutWorker w = workers.get(from);
        if (w == null || !w.running.get()) {
            synchronized (this) {
                w = workers.get(from);
                if (w == null || !w.running.get()) {
                    int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO,
                            AudioFormat.ENCODING_PCM_16BIT);
                    AudioTrack t = new AudioTrack(AudioManager.STREAM_VOICE_CALL, RATE,
                            AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                            Math.max(min, FRAME_BYTES * 8), AudioTrack.MODE_STREAM);
                    if (t.getState() != AudioTrack.STATE_INITIALIZED) {
                        t.release();
                        return;
                    }
                    t.play();
                    w = new PlayoutWorker(from, t);
                    workers.put(from, w);
                    Thread pt = new Thread(w, "voice-play-" + from);
                    pt.setDaemon(true);
                    pt.start();
                }
            }
        }
        w.jitter.offer(pcm, SystemClock.elapsedRealtime());
    }

    synchronized void drop(String from) {
        PlayoutWorker w = workers.remove(from);
        if (w != null) w.stop();
    }

    synchronized void release() {
        stopCapture();
        for (PlayoutWorker w : workers.values()) {
            w.stop();
        }
        workers.clear();
    }
}
