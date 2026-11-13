package com.roughhouse.app;

/**
 * YIN fundamental-frequency detector working on audio decimated to 12 kHz
 * (cheap enough to run live on the recording thread and over whole tracks).
 * Range roughly 60 Hz - 1 kHz: low male voice to high soprano / lead instruments.
 */
final class Pitch {
    static final int RATE = 12000;
    static final int N = 512;                 // analysis window at 12 kHz (~43 ms)
    private static final int W = N / 2;
    private static final int TAU_MIN = RATE / 1000, TAU_MAX = RATE / 60;
    private static final float THRESH = 0.15f;

    private final float[] d = new float[TAU_MAX + 2];
    float conf; // 1 - aperiodicity of the last detection

    /** Detects f0 of x[off .. off+N). Returns Hz, or -1 if unvoiced/too quiet. */
    float detect(float[] x, int off) {
        double energy = 0;
        for (int i = 0; i < N; i++) energy += x[off + i] * x[off + i];
        if (energy / N < 1e-5) { conf = 0; return -1; } // about -50 dBFS gate

        d[0] = 1;
        double run = 0;
        int tau = -1;
        for (int t = 1; t <= TAU_MAX; t++) {
            double s = 0;
            for (int j = 0; j < W; j++) {
                float v = x[off + j] - x[off + j + t];
                s += v * v;
            }
            run += s;
            d[t] = run > 0 ? (float) (s * t / run) : 1;
        }
        for (int t = TAU_MIN; t < TAU_MAX; t++) {
            if (d[t] < THRESH) {
                while (t + 1 < TAU_MAX && d[t + 1] < d[t]) t++;
                tau = t;
                break;
            }
        }
        if (tau < 0) { conf = 0; return -1; }
        float a = d[tau - 1], b = d[tau], c = d[tau + 1];
        float den = a - 2 * b + c;
        float shift = den != 0 ? 0.5f * (a - c) / den : 0;
        if (shift > 1 || shift < -1) shift = 0;
        conf = 1 - b;
        return RATE / (tau + shift);
    }

    /** Streaming 48 kHz -> 12 kHz decimator with a gentle anti-alias low-pass. */
    static final class Decimator {
        private final Dsp.Biquad lp1 = new Dsp.Biquad(), lp2 = new Dsp.Biquad();
        private int phase;

        Decimator(int inRate) {
            lp1.lowpass(4800, 0.54, inRate);
            lp2.lowpass(4800, 1.31, inRate);
        }

        /** Feeds n input samples, writes decimated samples to out starting at outPos; returns count written. */
        int process(short[] in, int n, float gain, float[] out, int outPos) {
            int w = 0;
            for (int i = 0; i < n; i++) {
                float v = lp2.process(lp1.process(in[i] * gain * (1f / 32768f)));
                if (++phase == 4) { phase = 0; out[outPos + w++] = v; }
            }
            return w;
        }
    }

    static float midi(float hz) {
        return (float) (69 + 12 * Math.log(hz / 440.0) / Math.log(2));
    }

    static String noteName(int midi) {
        return Analyzer.NOTES[Math.floorMod(midi, 12)] + (Math.floorDiv(midi, 12) - 1);
    }
}
