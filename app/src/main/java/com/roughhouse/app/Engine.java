package com.roughhouse.app;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Process;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

/**
 * Real-time playback + recording. Playback runs on its own thread through AudioTrack;
 * recording runs on a second thread through AudioRecord. A recorded sample i maps to
 * timeline frame (recOrigin + i - latencyFrames). All Listener callbacks happen on the UI thread.
 */
final class Engine {
    static final int SR = 48000;
    static final int BLOCK = 256;

    interface Listener {
        void onEngineStopped();
        void onTake(Track t);
        void onCalibrated(int ms);
        void onEngineError(String msg);
    }

    private final Handler ui;
    private final Listener l;
    Jam s;

    private volatile boolean running;
    private volatile long pos;
    private volatile long seekReq = Long.MIN_VALUE;
    private Thread playThread;
    private volatile long startPos;

    private volatile boolean recRunning;
    private Thread recThread;
    volatile Track recTrack;
    volatile long punchStart, recOrigin;
    private short[] recBuf;
    private int recLen;
    volatile float[] recPeaks = new float[0];
    volatile int recPeakCount;
    volatile float inputLevel;
    volatile boolean calibrating;

    volatile int latencyFrames = SR / 10;
    volatile float levelL, levelR;     // master output peaks for the VU meters

    // live pitch (recording or tune mode)
    volatile float pitchHz = -1;
    volatile boolean monitoring;       // tune mode: mic open, nothing recorded
    private Thread monThread;
    volatile float inputGain = 1f;

    Engine(Handler ui, Listener l) {
        this.ui = ui;
        this.l = l;
    }

    boolean isPlaying() { return running; }
    boolean isRecording() { return recRunning; }
    long position() { return pos; }
    long startPosition() { return startPos; }

    void seek(long frame) {
        if (running) seekReq = Math.max(0, frame);
    }

    // ---------------------------------------------------------------- playback

    void play(long from, boolean countIn) {
        if (running) return;
        startPos = from;
        long bar = Math.round(s.beatFrames(SR) * Math.max(1, s.beatsPerBar));
        pos = countIn ? from - bar : from;
        seekReq = Long.MIN_VALUE;
        running = true;
        final Mixer m = new Mixer(s, SR);
        m.calib = calibrating;
        playThread = new Thread(() -> playLoop(m), "rh-play");
        playThread.start();
    }

    private void playLoop(Mixer m) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        AudioTrack at = null;
        try {
            int min = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT);
            at = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(SR)
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build())
                    .setBufferSizeInBytes(Math.max(min, BLOCK * 2 * 4 * 4))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build();
            at.play();

            float[] out = new float[BLOCK * 2];
            long[] p = new long[BLOCK];
            while (running) {
                long sk = seekReq;
                if (sk != Long.MIN_VALUE) { seekReq = Long.MIN_VALUE; pos = sk; }
                boolean rec = recRunning || calibrating;
                long len = s.lengthFrames();
                long lin = Math.max(0, s.loopIn);
                long lout = s.loopOut > 0 ? s.loopOut : len;
                boolean loop = s.loop && !rec && lout - lin > SR / 10;
                long cur = pos;
                for (int i = 0; i < BLOCK; i++) {
                    if (loop && cur >= lout) cur = lin;
                    p[i] = cur++;
                }
                boolean click = s.metronome || (recRunning && p[0] < startPos);
                m.render(out, p, BLOCK, recRunning ? recTrack : null, click);
                levelL = m.peakL;
                levelR = m.peakR;
                pos = cur;
                at.write(out, 0, out.length, AudioTrack.WRITE_BLOCKING);
                if (!rec && !loop && len > 0 && cur > len + SR / 2 && cur > startPos) {
                    running = false;
                    ui.post(this::stop);
                }
            }
        } catch (Throwable e) {
            running = false;
            final String msg = String.valueOf(e.getMessage());
            ui.post(() -> {
                l.onEngineError("Audio output failed: " + msg);
                stop();
            });
        } finally {
            levelL = 0;
            levelR = 0;
            if (at != null) {
                try { at.pause(); at.flush(); at.stop(); } catch (Exception ignored) {}
                at.release();
            }
        }
    }

    /** Stops recording (if any) and playback. Safe to call repeatedly. UI thread only. */
    void stop() {
        stopRecording();
        running = false;
        if (playThread != null) {
            join(playThread);
            playThread = null;
            l.onEngineStopped();
        }
    }

    // --------------------------------------------------------------- recording

    /** Starts capturing into track t; audio before `punch` is discarded. Playback must be running. */
    private static AudioRecord openMic() {
        try {
            int min = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            AudioRecord ar = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SR,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min * 2, 16384));
            if (ar.getState() != AudioRecord.STATE_INITIALIZED) {
                ar.release();
                return null;
            }
            return ar;
        } catch (RuntimeException e) {
            return null;
        }
    }

    boolean startRecording(Track t, long punch) {
        if (recRunning) return true;
        stopMonitor();
        AudioRecord ar = openMic();
        if (ar == null) return false;
        recTrack = t;
        punchStart = punch;
        try {
            recBuf = new short[SR * 30];
        } catch (OutOfMemoryError e) {
            ar.release();
            return false;
        }
        recLen = 0;
        recPeaks = new float[recBuf.length / Track.PB + 1];
        recPeakCount = 0;
        try {
            ar.startRecording();
        } catch (RuntimeException e) {
            ar.release();
            return false;
        }
        recOrigin = pos;
        recRunning = true;
        final AudioRecord rec = ar;
        recThread = new Thread(() -> recLoop(rec), "rh-rec");
        recThread.start();
        return true;
    }

    private void recLoop(AudioRecord rec) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        short[] buf = new short[480];
        int pk = 0, pc = 0;
        float g = inputGain;
        PitchTap tap = new PitchTap();
        while (recRunning) {
            int n = rec.read(buf, 0, buf.length);
            if (n < 0) break;
            if (n == 0) continue;
            tap.feed(buf, n, g);
            if (recLen + n > recBuf.length) {
                try {
                    recBuf = Arrays.copyOf(recBuf, recBuf.length * 2);
                } catch (OutOfMemoryError e) {
                    ui.post(() -> l.onEngineError("Out of memory: take stopped. Delete or export some tracks."));
                    break;
                }
            }
            System.arraycopy(buf, 0, recBuf, recLen, n);
            int bm = 0;
            for (int i = 0; i < n; i++) {
                int v = buf[i];
                if (v < 0) v = -v;
                if (v > pk) pk = v;
                if (v > bm) bm = v;
                if (++pc == Track.PB) {
                    float[] rp = recPeaks;
                    int c = recPeakCount;
                    if (c >= rp.length) { rp = Arrays.copyOf(rp, rp.length * 2); recPeaks = rp; }
                    rp[c] = Math.min(1f, pk * g / 32768f);
                    recPeakCount = c + 1;
                    pk = 0;
                    pc = 0;
                }
            }
            recLen += n;
            inputLevel = Math.min(1f, bm * g / 32768f);
        }
        pitchHz = -1;
        try { rec.stop(); } catch (Exception ignored) {}
        rec.release();
    }

    /** Runs the pitch detector on the mic stream, publishing pitchHz about every 21 ms. */
    private final class PitchTap {
        private final Pitch.Decimator dec = new Pitch.Decimator(SR);
        private final Pitch det = new Pitch();
        private final float[] ring = new float[Pitch.N * 2];
        private int fill, since;
        private float smooth = -1;
        private float[] tmp = new float[256];

        void feed(short[] in, int n, float g) {
            if (tmp.length < n / 4 + 2) tmp = new float[n / 4 + 2];
            int w = dec.process(in, n, g, tmp, 0);
            for (int i = 0; i < w; i++) {
                if (fill == ring.length) {
                    System.arraycopy(ring, Pitch.N, ring, 0, Pitch.N);
                    fill = Pitch.N;
                }
                ring[fill++] = tmp[i];
                if (++since >= 256 && fill >= Pitch.N) {
                    since = 0;
                    float hz = det.detect(ring, fill - Pitch.N);
                    if (hz > 0 && det.conf > 0.7f) {
                        // light smoothing, but jump straight to a new note
                        smooth = (smooth > 0 && Math.abs(Pitch.midi(hz) - Pitch.midi(smooth)) < 0.5f)
                                ? smooth * 0.6f + hz * 0.4f : hz;
                        pitchHz = smooth;
                    } else {
                        smooth = -1;
                        pitchHz = -1;
                    }
                }
            }
        }
    }

    /** Tune mode: opens the mic only to show pitch; nothing is recorded or played. */
    boolean startMonitor() {
        if (monitoring || recRunning) return true;
        final AudioRecord ar = openMic();
        if (ar == null) return false;
        try {
            ar.startRecording();
        } catch (RuntimeException e) {
            ar.release();
            return false;
        }
        monitoring = true;
        monThread = new Thread(() -> {
            short[] buf = new short[480];
            PitchTap tap = new PitchTap();
            while (monitoring) {
                int n = ar.read(buf, 0, buf.length);
                if (n < 0) break;
                if (n == 0) continue;
                float g = inputGain;
                tap.feed(buf, n, g);
                int bm = 0;
                for (int i = 0; i < n; i++) bm = Math.max(bm, Math.abs(buf[i]));
                inputLevel = Math.min(1f, bm * g / 32768f);
            }
            pitchHz = -1;
            inputLevel = 0;
            try { ar.stop(); } catch (Exception ignored) {}
            ar.release();
        }, "rh-tune");
        monThread.start();
        return true;
    }

    void stopMonitor() {
        if (!monitoring) return;
        monitoring = false;
        if (monThread != null) { join(monThread); monThread = null; }
    }

    /** Ends the take but keeps playback running (punch out). UI thread only. */
    void stopRecording() {
        if (!recRunning) return;
        recRunning = false;
        if (recThread != null) { join(recThread); recThread = null; }
        inputLevel = 0;
        if (calibrating) {
            int ms = computeCalibration();
            calibrating = false;
            l.onCalibrated(ms);
        } else {
            Track t = finalizeTake();
            if (t != null) l.onTake(t);
        }
        recTrack = null;
        recBuf = null;
        recPeakCount = 0;
    }

    private Track finalizeTake() {
        Track t = recTrack;
        if (t == null || recLen == 0) return null;
        long first = recOrigin - latencyFrames; // timeline frame of recBuf[0]
        long skip = Math.max(0, punchStart - first);
        if (skip >= recLen) return null;
        int si = (int) skip;
        int ts = (int) (first + si);
        int n = recLen - si;
        if (n < SR / 20) return null; // ignore accidental taps (<50 ms)

        short[] old = t.data;
        int need = ts + n;
        short[] nd = Arrays.copyOf(old, Math.max(old.length, need));
        float g = inputGain;
        int fade = Math.min(SR / 200, n / 2); // 5 ms crossfade at both edges
        for (int i = 0; i < n; i++) {
            float v = recBuf[si + i] * g;
            int at = ts + i;
            if (i < fade || i >= n - fade) {
                float w = i < fade ? i / (float) fade : (n - 1 - i) / (float) fade;
                float o = at < old.length ? old[at] : 0;
                v = o * (1 - w) + v * w;
            }
            if (v > 32767) v = 32767;
            else if (v < -32768) v = -32768;
            nd[at] = (short) v;
        }
        t.undo = old;
        t.data = nd;
        t.dirty = true;
        t.invalidateAnalysis();
        t.computePeaks();
        return t;
    }

    // ------------------------------------------------------------- calibration

    /** Plays clicks every 0.5 s and records them; caller stops after ~3.4 s. */
    boolean calibrate() {
        if (running) stop();
        calibrating = true;
        play(0, false);
        if (!startRecording(null, 0)) {
            calibrating = false;
            stop();
            return false;
        }
        return true;
    }

    private int computeCalibration() {
        int interval = SR / 2, win = SR * 4 / 10;
        ArrayList<Integer> lags = new ArrayList<>();
        for (long c = interval; ; c += interval) {
            long base = c - recOrigin;
            if (base < 0) continue;
            if (base + win > recLen) break;
            int b = (int) base, e = b + win, mi = -1, mv = 0;
            long sum = 0;
            for (int i = b; i < e; i++) {
                int v = Math.abs(recBuf[i]);
                sum += v;
                if (v > mv) { mv = v; mi = i; }
            }
            float mean = sum / (float) win;
            if (mv > 1200 && mv > mean * 6) lags.add(mi - b);
        }
        if (lags.size() < 3) return -1;
        Collections.sort(lags);
        int med = lags.get(lags.size() / 2);
        return Math.round(med * 1000f / SR);
    }

    private static void join(Thread t) {
        try { t.join(2000); } catch (InterruptedException ignored) {}
    }
}
