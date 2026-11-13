package com.roughhouse.app;

/** Small, allocation-free DSP blocks used by the mixer. */
final class Dsp {
    private Dsp() {}

    static final double[] DIV_BEATS = {0.25, 0.5, 0.75, 1.0, 2.0};
    static final String[] DIV_NAMES = {"1/16", "1/8", "1/8 dotted", "1/4", "1/2"};

    /** RBJ biquad, transposed direct form II. */
    static final class Biquad {
        float b0 = 1, b1, b2, a1, a2, z1, z2;

        void highpass(double f, double q, int sr) {
            double w = 2 * Math.PI * f / sr, c = Math.cos(w), al = Math.sin(w) / (2 * q);
            double a0 = 1 + al;
            b0 = (float) ((1 + c) / 2 / a0);
            b1 = (float) (-(1 + c) / a0);
            b2 = b0;
            a1 = (float) (-2 * c / a0);
            a2 = (float) ((1 - al) / a0);
        }

        void lowpass(double f, double q, int sr) {
            double w = 2 * Math.PI * f / sr, c = Math.cos(w), al = Math.sin(w) / (2 * q);
            double a0 = 1 + al;
            b0 = (float) ((1 - c) / 2 / a0);
            b1 = (float) ((1 - c) / a0);
            b2 = b0;
            a1 = (float) (-2 * c / a0);
            a2 = (float) ((1 - al) / a0);
        }

        void shelf(boolean low, double f, double gainDb, int sr) {
            double A = Math.pow(10, gainDb / 40), w = 2 * Math.PI * f / sr;
            double c = Math.cos(w), al = Math.sin(w) / 2 * Math.sqrt(2), sq = 2 * Math.sqrt(A) * al;
            double nb0, nb1, nb2, a0, na1, na2;
            if (low) {
                nb0 = A * ((A + 1) - (A - 1) * c + sq);
                nb1 = 2 * A * ((A - 1) - (A + 1) * c);
                nb2 = A * ((A + 1) - (A - 1) * c - sq);
                a0 = (A + 1) + (A - 1) * c + sq;
                na1 = -2 * ((A - 1) + (A + 1) * c);
                na2 = (A + 1) + (A - 1) * c - sq;
            } else {
                nb0 = A * ((A + 1) + (A - 1) * c + sq);
                nb1 = -2 * A * ((A - 1) + (A + 1) * c);
                nb2 = A * ((A + 1) + (A - 1) * c - sq);
                a0 = (A + 1) - (A - 1) * c + sq;
                na1 = 2 * ((A - 1) - (A + 1) * c);
                na2 = (A + 1) - (A - 1) * c - sq;
            }
            b0 = (float) (nb0 / a0); b1 = (float) (nb1 / a0); b2 = (float) (nb2 / a0);
            a1 = (float) (na1 / a0); a2 = (float) (na2 / a0);
        }

        float process(float x) {
            float y = b0 * x + z1;
            z1 = b1 * x - a1 * y + z2;
            z2 = b2 * x - a2 * y;
            // flush denormals: decaying filter state otherwise gets very slow on some CPUs
            if (z1 < 1e-15f && z1 > -1e-15f) z1 = 0f;
            if (z2 < 1e-15f && z2 > -1e-15f) z2 = 0f;
            return y;
        }
    }

    /** Per-track chain: low cut -> bass/treble shelves -> compressor -> echo. */
    static final class Fx {
        final int sr;
        final Biquad hp = new Biquad(), lo = new Biquad(), hi = new Biquad();
        boolean hpOn, loOn, hiOn;
        // compressor
        float cAmt, thrLin, slope, makeup, env, cg = 1f;
        int cc;
        int idle; // frames of silent input, lets the mixer skip finished tracks
        final float att, rel;
        // echo
        final float[] dl;
        int dw, dlen;
        float emix, efb, lp;
        // cached params so coefficients are only recomputed on change
        float cLow = -1, cBass = 99, cTreble = 99;

        Fx(int sr) {
            this.sr = sr;
            dl = new float[(int) (sr * 2.6)];
            att = (float) Math.exp(-1.0 / (0.003 * sr));
            rel = (float) Math.exp(-1.0 / (0.12 * sr));
        }

        void update(Track t, double quarterFrames) {
            float lc = t.lowCut, b = t.bass, tr = t.treble;
            hpOn = lc > 1;
            if (hpOn && lc != cLow) { hp.highpass(lc, 0.707, sr); cLow = lc; }
            loOn = Math.abs(b) > 0.1f;
            if (loOn && b != cBass) { lo.shelf(true, 150, b, sr); cBass = b; }
            hiOn = Math.abs(tr) > 0.1f;
            if (hiOn && tr != cTreble) { hi.shelf(false, 5000, tr, sr); cTreble = tr; }

            float a = t.comp;
            if (a != cAmt) {
                cAmt = a;
                double thrDb = -30.0 * a, ratio = 1 + 5.0 * a;
                thrLin = (float) Math.pow(10, thrDb / 20);
                slope = (float) (1 - 1 / ratio);
                makeup = (float) Math.pow(10, (-thrDb * slope * 0.75) / 20);
            }

            emix = t.echo;
            efb = Math.min(0.9f, t.echoFb);
            int div = Math.max(0, Math.min(DIV_BEATS.length - 1, t.echoDiv));
            int d = (int) (DIV_BEATS[div] * quarterFrames);
            dlen = Math.max(1, Math.min(dl.length - 1, d));
        }

        float process(float x) {
            if (hpOn) x = hp.process(x);
            if (loOn) x = lo.process(x);
            if (hiOn) x = hi.process(x);
            if (cAmt > 0) {
                float a = Math.abs(x);
                env = a > env ? att * env + (1 - att) * a : rel * env + (1 - rel) * a;
                if (env < 1e-15f) env = 0f;
                if ((++cc & 7) == 0) { // gain is smooth; update every 8 samples to save CPU
                    // gain reduction = (env/thr)^(-slope)
                    cg = env > thrLin ? makeup * (float) Math.exp(-slope * Math.log(env / thrLin)) : makeup;
                }
                x *= cg;
            }
            if (emix > 0) {
                int r = dw - dlen;
                if (r < 0) r += dl.length;
                float d = dl[r];
                lp += 0.35f * (d - lp); // darken repeats
                if (lp < 1e-15f && lp > -1e-15f) lp = 0f;
                dl[dw] = x + lp * efb;
                if (++dw >= dl.length) dw = 0;
                x += d * emix;
            }
            return x;
        }
    }

    /** Compact Freeverb-style stereo reverb (4 combs + 2 allpasses per side). */
    static final class Reverb {
        private final Comb[] cl, cr;
        private final Allpass[] al, ar;

        Reverb(int sr) {
            int[] cs = {1116, 1188, 1277, 1356};
            int[] as = {556, 441};
            double k = sr / 44100.0;
            cl = new Comb[4]; cr = new Comb[4];
            for (int i = 0; i < 4; i++) {
                cl[i] = new Comb((int) (cs[i] * k));
                cr[i] = new Comb((int) ((cs[i] + 23) * k));
            }
            al = new Allpass[2]; ar = new Allpass[2];
            for (int i = 0; i < 2; i++) {
                al[i] = new Allpass((int) (as[i] * k));
                ar[i] = new Allpass((int) ((as[i] + 23) * k));
            }
        }

        /** Adds wet stereo signal for mono send `in` into interleaved `out`. */
        void process(float[] in, float[] out, int n) {
            for (int i = 0; i < n; i++) {
                float x = in[i] * 0.03f, l = 0, r = 0;
                for (int k = 0; k < 4; k++) { l += cl[k].process(x); r += cr[k].process(x); }
                for (int k = 0; k < 2; k++) { l = al[k].process(l); r = ar[k].process(r); }
                out[2 * i] += l;
                out[2 * i + 1] += r;
            }
        }

        private static final class Comb {
            final float[] b; int i; float filt;
            Comb(int n) { b = new float[n]; }
            float process(float x) {
                float o = b[i];
                filt = o * 0.75f + filt * 0.25f;
                if (filt < 1e-15f && filt > -1e-15f) filt = 0f;
                b[i] = x + filt * 0.84f;
                if (++i >= b.length) i = 0;
                return o;
            }
        }

        private static final class Allpass {
            final float[] b; int i;
            Allpass(int n) { b = new float[n]; }
            float process(float x) {
                float bo = b[i];
                b[i] = x + bo * 0.5f;
                if (++i >= b.length) i = 0;
                return bo - x;
            }
        }
    }
}
