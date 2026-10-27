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
    private volatile boolean capturing;
    private Thread captureThread;

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
            int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            t = new AudioTrack(AudioManager.STREAM_VOICE_CALL, RATE, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, FRAME_BYTES * 10),
                    AudioTrack.MODE_STREAM);
            t.play();
            tracks.put(from, t);
        }
        try {
            t.write(pcm, 0, pcm.length);
        } catch (IllegalStateException ignored) {
            // Track was released while this frame was in flight.
        }
    }

    void release() {
        stopCapture();
        for (AudioTrack t : tracks.values()) {
            try { t.stop(); } catch (IllegalStateException ignored) { }
            t.release();
        }
        tracks.clear();
    }
}
