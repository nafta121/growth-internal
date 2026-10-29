package com.growthinternal.app;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.LoudnessEnhancer;
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
    static final int BOOST_UNITY_Q10 = 1024;
    static final int BOOST_MAX_Q10 = 5120;      // 5.0x (+14.0 dB) speech gain for quiet field audio
    static final int BOOST_TARGET_PEAK = 26000; // ~-2.0 dBFS outdoor speech peak target
    static final int BOOST_NOISE_FLOOR = 280;   // ~-41 dBFS noise gate avoids amplifying idle hiss
    static final int BOOST_SPEECH_OPEN = 840;   // smooth expansion knee up to full AGC
    static final int BOOST_SOFT_KNEE = 26000;   // C1-continuous soft-knee limiter threshold
    private static final int LOUDNESS_GAIN_MB = 750; // +7.5 dB hardware DSP LoudnessEnhancer post-gain
    private static final int WINDOW_SAMPLES = FRAME_BYTES / 2;
    private static final int RAMP_SAMPLES = 64;
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
        final int streamType;
        final JitterBuffer jitter = new JitterBuffer();
        final AtomicBoolean running = new AtomicBoolean(true);
        private final byte[] boostScratch = new byte[FRAME_BYTES];
        private LoudnessEnhancer enhancer;
        private boolean enhancerActive;
        private int gainQ10 = BOOST_UNITY_Q10;
        private boolean trackReleased;

        PlayoutWorker(String sender, AudioTrack track, int streamType) {
            this.sender = sender;
            this.track = track;
            this.streamType = streamType;
            try {
                enhancer = new LoudnessEnhancer(track.getAudioSessionId());
                syncEnhancer(fieldBoost);
            } catch (RuntimeException ignored) {
                // Devices or test shadows without LoudnessEnhancer still run software Speech AGC.
            }
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
                        if (!writeTrack(leadInSilence, false)) break;
                        now = SystemClock.elapsedRealtime();
                        playoutEndMs = now + FRAME_MS;
                    }
                    if (!writeTrack(frame, fieldBoost)) {
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
                            if (!writeTrack(plc, fieldBoost)) break;
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

        private void syncEnhancer(boolean wantBoost) {
            LoudnessEnhancer le = enhancer;
            if (le == null || enhancerActive == wantBoost) return;
            try {
                le.setTargetGain(wantBoost ? LOUDNESS_GAIN_MB : 0);
                le.setEnabled(wantBoost);
                enhancerActive = wantBoost;
            } catch (RuntimeException ignored) {
            }
        }

        private boolean writeTrack(byte[] frame, boolean applyBoost) {
            byte[] out = frame;
            if (applyBoost) {
                out = (frame.length == FRAME_BYTES) ? boostScratch : new byte[frame.length];
                gainQ10 = boostPcm(frame, out, gainQ10);
            } else if (frame != null && frame.length > 0 && !fieldBoost) {
                gainQ10 = BOOST_UNITY_Q10;
            }
            synchronized (this) {
                if (trackReleased || !running.get()) return false;
                syncEnhancer(fieldBoost);
                try {
                    track.write(out, 0, out.length);
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
                if (enhancer != null) {
                    try { enhancer.setEnabled(false); } catch (RuntimeException ignored) { }
                    try { enhancer.release(); } catch (RuntimeException ignored) { }
                    enhancer = null;
                }
                try { track.stop(); } catch (RuntimeException ignored) { }
                try { track.release(); } catch (RuntimeException ignored) { }
            }
        }
    }

    private final ConcurrentHashMap<String, PlayoutWorker> workers =
            new ConcurrentHashMap<String, PlayoutWorker>();
    private volatile int outputStream = AudioManager.STREAM_MUSIC;
    private volatile boolean fieldBoost = true;
    private volatile boolean capturing;
    private AtomicBoolean activeCapture;
    private Thread captureThread;

    static boolean isLoudSpeakerActive(boolean speakerOn, boolean callMode, boolean nearEar) {
        return speakerOn && !(callMode && nearEar);
    }

    /**
     * Walkie-talkie PTT & Voice Notes use MODE_NORMAL + STREAM_MUSIC to unlock Android's full-wattage
     * media loudspeaker amplifier; full-duplex Mode Telepon and earpiece mode use MODE_IN_COMMUNICATION.
     */
    static int selectAudioMode(boolean hubActive, boolean speakerOn, boolean callMode) {
        return (hubActive && (callMode || !speakerOn))
                ? AudioManager.MODE_IN_COMMUNICATION
                : AudioManager.MODE_NORMAL;
    }

    static int selectOutputStream(boolean speakerOn, boolean callMode, boolean nearEar) {
        return (isLoudSpeakerActive(speakerOn, callMode, nearEar) && !callMode)
                ? AudioManager.STREAM_MUSIC
                : AudioManager.STREAM_VOICE_CALL;
    }

    static int targetFieldVolume(int currentVolume, int maxVolume, boolean forceMax) {
        if (maxVolume <= 0) return currentVolume;
        if (forceMax) return maxVolume;
        int minField = (maxVolume * 9 + 9) / 10;
        return Math.max(currentVolume, Math.min(maxVolume, minField));
    }

    /**
     * 20 ms windowed outdoor speech AGC + noise gate + C1 soft-knee limiter.
     * Boosts quiet field speech up to 5.0x (+14 dB) toward -2 dBFS without amplifying idle hiss or clipping.
     */
    static int boostPcm(byte[] src, byte[] dst, int prevGainQ10) {
        if (src == null || dst == null) return BOOST_UNITY_Q10;
        int len = Math.min(src.length, dst.length) & ~1;
        if (len <= 0) return prevGainQ10;
        if ((src.length & 1) != 0 && dst.length > len) {
            dst[len] = src[len];
        }
        int totalSamples = len / 2;
        int currentGain = prevGainQ10 > 0 ? prevGainQ10 : BOOST_UNITY_Q10;
        for (int base = 0; base < totalSamples; base += WINDOW_SAMPLES) {
            int end = Math.min(totalSamples, base + WINDOW_SAMPLES);
            int peak = 0;
            for (int i = base; i < end; i++) {
                int lo = src[i * 2] & 0xFF;
                int hi = src[i * 2 + 1];
                int s = (short) ((hi << 8) | lo);
                int abs = s < 0 ? -s : s;
                if (abs > peak) peak = abs;
            }
            int targetGain;
            if (peak <= BOOST_NOISE_FLOOR) {
                targetGain = BOOST_UNITY_Q10 + (currentGain - BOOST_UNITY_Q10) / 2;
            } else {
                int rawGain = (BOOST_TARGET_PEAK * BOOST_UNITY_Q10) / peak;
                if (rawGain < BOOST_UNITY_Q10) rawGain = BOOST_UNITY_Q10;
                else if (rawGain > BOOST_MAX_Q10) rawGain = BOOST_MAX_Q10;
                int desiredGain;
                if (peak < BOOST_SPEECH_OPEN) {
                    desiredGain = BOOST_UNITY_Q10
                            + ((rawGain - BOOST_UNITY_Q10) * (peak - BOOST_NOISE_FLOOR))
                            / (BOOST_SPEECH_OPEN - BOOST_NOISE_FLOOR);
                } else {
                    desiredGain = rawGain;
                }
                if (desiredGain <= currentGain) {
                    targetGain = desiredGain;
                } else {
                    targetGain = currentGain + ((desiredGain - currentGain) * 3) / 4;
                }
            }
            int winLen = end - base;
            int ramp = Math.min(RAMP_SAMPLES, winLen);
            for (int i = base; i < end; i++) {
                int step = i - base;
                int g = (step < ramp && ramp > 0)
                        ? (currentGain + ((targetGain - currentGain) * step) / ramp)
                        : targetGain;
                int lo = src[i * 2] & 0xFF;
                int hi = src[i * 2 + 1];
                int s = (short) ((hi << 8) | lo);
                int limited = softLimit((s * g) >> 10);
                dst[i * 2] = (byte) (limited & 0xFF);
                dst[i * 2 + 1] = (byte) ((limited >> 8) & 0xFF);
            }
            currentGain = targetGain;
        }
        return currentGain;
    }

    static int softLimit(int sample) {
        int abs = sample < 0 ? -sample : sample;
        if (abs <= BOOST_SOFT_KNEE) return sample;
        long excess = abs - BOOST_SOFT_KNEE;
        long headroom = 32767L - BOOST_SOFT_KNEE;
        int compressed = BOOST_SOFT_KNEE + (int) ((headroom * excess) / (headroom + excess));
        return sample < 0 ? -compressed : compressed;
    }

    static byte[] boostFieldAudio(byte[] pcm) {
        if (pcm == null) return new byte[0];
        byte[] out = new byte[pcm.length];
        boostPcm(pcm, out, BOOST_UNITY_Q10);
        return out;
    }

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
        AcousticEchoCanceler aecTmp = null;
        NoiseSuppressor nsTmp = null;
        AutomaticGainControl agcTmp = null;
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                aecTmp = AcousticEchoCanceler.create(rec.getAudioSessionId());
                if (aecTmp != null) aecTmp.setEnabled(true);
            }
            if (NoiseSuppressor.isAvailable()) {
                nsTmp = NoiseSuppressor.create(rec.getAudioSessionId());
                if (nsTmp != null) nsTmp.setEnabled(true);
            }
            if (AutomaticGainControl.isAvailable()) {
                agcTmp = AutomaticGainControl.create(rec.getAudioSessionId());
                if (agcTmp != null) agcTmp.setEnabled(true);
            }
        } catch (RuntimeException ignored) {
        }
        final AcousticEchoCanceler aec = aecTmp;
        final NoiseSuppressor ns = nsTmp;
        final AutomaticGainControl agc = agcTmp;
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
                try { rec.stop(); } catch (RuntimeException ignored) { }
                try { rec.release(); } catch (RuntimeException ignored) { }
                if (aec != null) try { aec.release(); } catch (RuntimeException ignored) { }
                if (ns != null) try { ns.release(); } catch (RuntimeException ignored) { }
                if (agc != null) try { agc.release(); } catch (RuntimeException ignored) { }
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

    synchronized void configureOutput(int streamType, boolean loudBoost) {
        fieldBoost = loudBoost;
        if (outputStream == streamType) return;
        outputStream = streamType;
        for (Map.Entry<String, PlayoutWorker> e : workers.entrySet()) {
            PlayoutWorker w = e.getValue();
            if (w.streamType != streamType && workers.remove(e.getKey(), w)) {
                w.stop();
            }
        }
    }

    /** Enqueues incoming PCM onto the sender's non-blocking jitter buffer playout worker. */
    void play(String from, byte[] pcm) {
        if (from == null || pcm == null || pcm.length == 0) return;
        PlayoutWorker w = workers.get(from);
        if (w == null || !w.running.get() || w.streamType != outputStream) {
            synchronized (this) {
                int stream = outputStream;
                w = workers.get(from);
                if (w != null && w.streamType != stream) {
                    workers.remove(from, w);
                    w.stop();
                    w = null;
                }
                if (w == null || !w.running.get()) {
                    int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO,
                            AudioFormat.ENCODING_PCM_16BIT);
                    AudioTrack t = new AudioTrack(stream, RATE,
                            AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                            Math.max(min, FRAME_BYTES * 8), AudioTrack.MODE_STREAM);
                    if (t.getState() != AudioTrack.STATE_INITIALIZED) {
                        t.release();
                        return;
                    }
                    try {
                        t.setVolume(AudioTrack.getMaxVolume());
                    } catch (RuntimeException ignored) {
                    }
                    t.play();
                    w = new PlayoutWorker(from, t, stream);
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
