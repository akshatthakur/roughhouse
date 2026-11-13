package com.roughhouse.app;

import java.util.Arrays;
import java.util.IdentityHashMap;

/** Renders the session (tracks + FX + reverb bus + metronome) to interleaved stereo float. */
final class Mixer {
    final Jam s;
    final int sr;
    boolean calib; // calibration mode: only emits test clicks

    private final IdentityHashMap<Track, Dsp.Fx> fx = new IdentityHashMap<>();
    private final Dsp.Reverb rev;
    private float[] revIn = new float[Engine.BLOCK];
    private long lastBeat = Long.MIN_VALUE;
    private int clickT = -1;
    private boolean accent;

    Mixer(Jam s, int sr) {
        this.s = s;
        this.sr = sr;
        this.rev = new Dsp.Reverb(sr);
    }

    /**
     * @param p       timeline frame for each output frame (may be negative during count-in)
     * @param exclude track not to play (the one being recorded), may be null
     * @param click   whether to add metronome clicks
     */
    void render(float[] out, long[] p, int n, Track exclude, boolean click) {
        Arrays.fill(out, 0, n * 2, 0f);
        if (revIn.length < n) revIn = new float[n];
        Arrays.fill(revIn, 0, n, 0f);

        if (!calib) {
            boolean anySolo = false;
            for (Track t : s.tracks) if (t.solo) { anySolo = true; break; }
            double qf = s.quarterFrames(sr);
            boolean anyRev = false;
            for (Track t : s.tracks) {
                if (t == exclude || t.mute || (anySolo && !t.solo)) continue;
                Dsp.Fx f = fx.get(t);
                if (f == null) { f = new Dsp.Fx(sr); fx.put(t, f); }
                short[] d = t.data;
                int len = d.length;
                if (p[n - 1] < 0 || p[0] >= len || len == 0) {
                    // block has no audio for this track: once tails have died, skip it
                    if (f.idle > sr * 4) { t.meter *= 0.9f; continue; }
                    f.idle += n;
                } else {
                    f.idle = 0;
                }
                f.update(t, qf);
                float vol = t.vol;
                double ang = (t.pan + 1) * Math.PI / 4;
                float gl = (float) (Math.cos(ang) * 1.41421356 * vol);
                float gr = (float) (Math.sin(ang) * 1.41421356 * vol);
                float rs = t.rev * vol;
                if (rs > 0) anyRev = true;
                float pk = 0;
                for (int i = 0; i < n; i++) {
                    long q = p[i];
                    float x = (q >= 0 && q < len) ? d[(int) q] * (1f / 32768f) : 0f;
                    float y = f.process(x);
                    float ay = y < 0 ? -y : y;
                    if (ay > pk) pk = ay;
                    out[2 * i] += y * gl;
                    out[2 * i + 1] += y * gr;
                    revIn[i] += y * rs;
                }
                pk *= vol;
                t.meter = Math.max(pk, t.meter * 0.92f);
            }
            // Keep running the reverb a while after sends stop so tails ring out.
            if (anyRev || revTail > 0) {
                rev.process(revIn, out, n);
                revTail = anyRev ? sr * 4 : revTail - n;
            }
        }

        if (calib) {
            int interval = sr / 2, len = sr * 3 / 1000;
            for (int i = 0; i < n; i++) {
                long q = p[i];
                if (q > 0 && q % interval == 0) clickT = 0;
                if (clickT >= 0 && clickT < len) {
                    float v = (float) Math.sin(2 * Math.PI * 1000.0 * (clickT + 0.25) / sr) * 0.9f;
                    out[2 * i] += v;
                    out[2 * i + 1] += v;
                    clickT++;
                } else {
                    clickT = -1;
                }
            }
        } else if (click) {
            double bf = s.beatFrames(sr);
            int bpb = Math.max(1, s.beatsPerBar);
            float mv = s.metroVol;
            int clen = (int) (sr * 0.04);
            for (int i = 0; i < n; i++) {
                long q = p[i];
                long beat = (long) Math.floor(q / bf);
                if (beat != lastBeat) {
                    lastBeat = beat;
                    if (q - beat * bf < sr * 0.01) {
                        clickT = 0;
                        accent = Math.floorMod(beat, (long) bpb) == 0;
                    }
                }
                if (clickT >= 0) {
                    if (clickT < clen) {
                        double t = clickT / (double) sr;
                        float v = (float) (Math.sin(2 * Math.PI * (accent ? 1760 : 1320) * t) * Math.exp(-t * 120)) * mv;
                        out[2 * i] += v;
                        out[2 * i + 1] += v;
                        clickT++;
                    } else {
                        clickT = -1;
                    }
                }
            }
        }

        float m = s.master, pl = 0, pr = 0;
        for (int i = 0; i < n * 2; i += 2) {
            float a = soft(out[i] * m), b = soft(out[i + 1] * m);
            out[i] = a;
            out[i + 1] = b;
            if (Math.abs(a) > pl) pl = Math.abs(a);
            if (Math.abs(b) > pr) pr = Math.abs(b);
        }
        peakL = pl;
        peakR = pr;
    }

    private int revTail;
    volatile float peakL, peakR; // master output peaks of the last block (VU meters)

    /** Transparent below 0.8, smooth saturation above: a cheap safety limiter. */
    static float soft(float x) {
        float a = Math.abs(x);
        if (a <= 0.8f) return x;
        float u = (a - 0.8f) * 5f;
        float y = 0.8f + 0.2f * u / (1f + u);
        return x < 0 ? -y : y;
    }
}
