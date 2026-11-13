package com.roughhouse.app;

/**
 * Tempo and key estimation for imported audio (mono, 16-bit).
 *
 * Tempo: spectral-flux onset envelope, then autocorrelation over 50-220 BPM with a
 * log-normal prior centred on 120 BPM (the usual guard against half/double-time picks).
 * Key: chromagram correlated against Krumhansl-Kessler major/minor profiles.
 */
final class Analyzer {
    private Analyzer() {}

    static final String[] NOTES = {"C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B"};

    static final class Result {
        float bpm;            // 0 if not found
        float bpmConfidence;  // 0..1
        int keyRoot = -1;     // 0 = C, -1 if not found
        boolean minor;
        float keyConfidence;  // 0..1
    }

    static Result analyze(short[] x, int sr) {
        Result r = new Result();
        int maxLen = Math.min(x.length, sr * 90); // first 90 s is plenty
        if (maxLen < sr * 4) return r;            // too short to say anything useful
        tempo(x, maxLen, sr, r);
        key(x, maxLen, sr, r);
        return r;
    }

    // ------------------------------------------------------------------ tempo

    // Where most songs sit; keeps busy hi-hats from reading as double time.
    static double PRIOR_BPM = 110, PRIOR_OCT = 0.8, LOW_WEIGHT = 4;

    private static void tempo(short[] x, int len, int sr, Result r) {
        final int n = 1024, hop = 512;
        int frames = (len - n) / hop;
        if (frames < 64) return;
        float[] win = hann(n);
        float[] re = new float[n], im = new float[n];
        int bins = n / 2;
        float[] prev = new float[bins], cur = new float[bins];
        float[] onset = new float[frames];
        int lowBins = (int) (250.0 * n / sr);
        for (int f = 0; f < frames; f++) {
            int o = f * hop;
            for (int i = 0; i < n; i++) { re[i] = x[o + i] * (1f / 32768f) * win[i]; im[i] = 0; }
            fft(re, im);
            float flux = 0;
            for (int k = 1; k < bins; k++) {
                float mag = (float) Math.sqrt(re[k] * re[k] + im[k] * im[k]);
                cur[k] = (float) Math.log1p(100f * mag);
                float d = cur[k] - prev[k];
                // kick/snare region carries the beat; up-weight it against hi-hats
                if (d > 0) flux += k < lowBins ? d * LOW_WEIGHT : d;
            }
            onset[f] = f == 0 ? 0 : flux;
            float[] t = prev; prev = cur; cur = t;
        }
        // remove slow trend, keep positive part
        int w = 16;
        float[] env = new float[frames];
        double acc = 0;
        for (int i = 0; i < frames; i++) {
            acc += onset[i];
            if (i >= w) acc -= onset[i - w];
            float mean = (float) (acc / Math.min(i + 1, w));
            env[i] = Math.max(0, onset[i] - mean);
        }
        double fps = sr / (double) hop;
        int minLag = (int) Math.floor(60 * fps / 200), maxLag = (int) Math.ceil(60 * fps / 55);
        if (maxLag * 2 >= frames) return;
        double[] ac = new double[maxLag * 4 + 2];
        double e0 = 0;
        for (int i = 0; i < frames; i++) e0 += env[i] * env[i];
        if (e0 <= 0) return;
        for (int lag = 1; lag < ac.length && lag < frames; lag++) {
            double s = 0;
            for (int i = lag; i < frames; i++) s += env[i] * env[i - lag];
            ac[lag] = s / e0 * frames / (frames - lag); // unbiased
        }
        int best = -1;
        double bestS = 0, sum = 0;
        int count = 0;
        for (int lag = minLag; lag <= maxLag; lag++) {
            double bpm = 60 * fps / lag;
            double oct = Math.log(bpm / PRIOR_BPM) / Math.log(2);
            double prior = Math.exp(-0.5 * oct * oct / (PRIOR_OCT * PRIOR_OCT));
            double s = ac[lag] * prior;
            sum += s;
            count++;
            if (s > bestS) { bestS = s; best = lag; }
        }
        if (best < 0) return;
        // parabolic refinement on the raw autocorrelation
        double lagF = best;
        if (best > 1 && best + 1 < ac.length) {
            double a = ac[best - 1], b = ac[best], c = ac[best + 1];
            double den = a - 2 * b + c;
            if (den < 0) lagF = best + 0.5 * (a - c) / den;
        }
        double bpm = 60 * fps / lagF;
        r.bpm = Math.round(bpm * 2) / 2f;
        double mean = sum / Math.max(1, count);
        r.bpmConfidence = (float) Math.max(0, Math.min(1, (bestS / Math.max(1e-9, mean) - 1) / 3));
    }

    // -------------------------------------------------------------------- key

    private static final double[] MAJOR = {6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88};
    private static final double[] MINOR = {6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17};

    private static final int CN = 8192, CHOP = 4096;

    /** Pitch-class profile of x[start, end): each non-silent frame votes equally. Returns frames used. */
    private static int chroma(short[] x, int start, int end, int sr, double[] chroma) {
        java.util.Arrays.fill(chroma, 0);
        float[] win = hann(CN);
        float[] re = new float[CN], im = new float[CN];
        int[] pcOf = new int[CN / 2];
        for (int k = 0; k < CN / 2; k++) {
            double f = k * (double) sr / CN;
            if (f < 55 || f > 2000) { pcOf[k] = -1; continue; }
            double midi = 69 + 12 * Math.log(f / 440.0) / Math.log(2);
            pcOf[k] = (int) Math.floorMod(Math.round(midi), 12);
        }
        double[] fc = new double[12];
        int used = 0;
        for (int o = start; o + CN <= end; o += CHOP) {
            double energy = 0;
            for (int i = 0; i < CN; i++) {
                float v = x[o + i] * (1f / 32768f);
                energy += v * v;
                re[i] = v * win[i];
                im[i] = 0;
            }
            if (energy / CN < 1e-6) continue; // skip near-silence
            fft(re, im);
            java.util.Arrays.fill(fc, 0);
            for (int k = 1; k < CN / 2; k++) {
                int pc = pcOf[k];
                if (pc < 0) continue;
                fc[pc] += Math.sqrt(re[k] * re[k] + im[k] * im[k]);
            }
            double s = 0;
            for (double v : fc) s += v;
            if (s <= 0) continue;
            for (int i = 0; i < 12; i++) chroma[i] += fc[i] / s;
            used++;
        }
        return used;
    }

    private static void key(short[] x, int len, int sr, Result r) {
        double[] chroma = new double[12];
        if (chroma(x, 0, len, sr, chroma) < 4) return;
        double best = -2, second = -2;
        int bestRoot = -1;
        boolean bestMinor = false;
        for (int root = 0; root < 12; root++) {
            for (int m = 0; m < 2; m++) {
                double c = corr(chroma, m == 0 ? MAJOR : MINOR, root);
                if (c > best) { second = best; best = c; bestRoot = root; bestMinor = m == 1; }
                else if (c > second) second = c;
            }
        }
        if (bestRoot < 0 || best < 0.3) return;
        r.keyRoot = bestRoot;
        r.minor = bestMinor;
        r.keyConfidence = (float) Math.max(0, Math.min(1, (best - second) * 5));
    }

    private static double corr(double[] chroma, double[] profile, int root) {
        double ma = 0, mb = 0;
        for (int i = 0; i < 12; i++) { ma += chroma[i]; mb += profile[i]; }
        ma /= 12; mb /= 12;
        double num = 0, da = 0, db = 0;
        for (int i = 0; i < 12; i++) {
            double a = chroma[(i + root) % 12] - ma, b = profile[i] - mb;
            num += a * b; da += a * a; db += b * b;
        }
        return num / Math.sqrt(da * db + 1e-12);
    }

    // ----------------------------------------------------- full track analysis

    static final class Note {
        long start;   // timeline frame (48 kHz)
        int len;      // frames
        int midi;
        float cents;  // average deviation from the equal-tempered note
    }

    static final class Chord {
        int bar;      // 0-based
        String name;
    }

    static final class Full {
        Result base;
        final java.util.ArrayList<Note> notes = new java.util.ArrayList<>();
        final java.util.ArrayList<Chord> chords = new java.util.ArrayList<>();
        int low = -1, high = -1;   // melody range (midi)
        float avgCents;            // average tuning offset across notes
    }

    /** Everything the analysis sheet shows. beatFrames/beatsPerBar come from the jam grid. */
    static Full analyzeTrack(short[] x, int sr, double beatFrames, int beatsPerBar) {
        Full f = new Full();
        f.base = analyze(x, sr);
        transcribe(x, sr, f);
        long bar = Math.round(beatFrames * beatsPerBar);
        if (bar > CN) {
            double[] c = new double[12];
            int bars = (int) Math.min(256, (x.length + bar - 1) / bar);
            for (int b = 0; b < bars; b++) {
                int s = (int) (b * bar), e = (int) Math.min(x.length, s + bar);
                // pad short bars with a window from just before so every bar gets frames
                int used = chroma(x, Math.max(0, Math.min(s, e - CN)), e, sr, c);
                if (used == 0) continue;
                String name = chordName(c);
                if (name == null) continue;
                Chord ch = new Chord();
                ch.bar = b;
                ch.name = name;
                f.chords.add(ch);
            }
        }
        return f;
    }

    /** Best major/minor triad for a chroma vector, or null if nothing triad-like. */
    static String chordName(double[] c) {
        double sum = 0;
        for (double v : c) sum += v;
        if (sum <= 0) return null;
        double best = 0;
        String name = null;
        for (int root = 0; root < 12; root++) {
            for (int q = 0; q < 2; q++) {
                int third = q == 0 ? 4 : 3;
                double in = c[root] + c[(root + third) % 12] + c[(root + 7) % 12];
                double score = in / sum - 0.25 * c[(root + (q == 0 ? 3 : 4)) % 12] / sum;
                if (score > best) { best = score; name = NOTES[root] + (q == 0 ? "" : "m"); }
            }
        }
        return best > 0.42 ? name : null;
    }

    /** Monophonic note transcription from a YIN pitch track (best on voice or single-note lines). */
    private static void transcribe(short[] x, int sr, Full f) {
        if (sr != 48000) return;
        int n12 = x.length / 4;
        float[] y = new float[n12 + Pitch.N];
        new Pitch.Decimator(sr).process(x, x.length, 1f, y, 0);
        Pitch p = new Pitch();
        int hop = 128;
        int frames = Math.max(0, (n12 - Pitch.N) / hop);
        float[] m = new float[frames];
        for (int i = 0; i < frames; i++) {
            float hz = p.detect(y, i * hop);
            m[i] = (hz > 0 && p.conf > 0.75f) ? Pitch.midi(hz) : Float.NaN;
        }
        // 5-frame median on voiced runs removes octave blips
        float[] sm = new float[frames];
        float[] w = new float[5];
        for (int i = 0; i < frames; i++) {
            if (Float.isNaN(m[i])) { sm[i] = Float.NaN; continue; }
            int k = 0;
            for (int j = i - 2; j <= i + 2; j++) if (j >= 0 && j < frames && !Float.isNaN(m[j])) w[k++] = m[j];
            java.util.Arrays.sort(w, 0, k);
            sm[i] = w[k / 2];
        }
        double framesPerSec = Pitch.RATE / (double) hop;
        int minLen = (int) Math.ceil(0.07 * framesPerSec);
        int i = 0;
        double centsSum = 0;
        int centsN = 0;
        while (i < frames) {
            if (Float.isNaN(sm[i])) { i++; continue; }
            int start = i;
            int ref = Math.round(sm[i]);
            int gap = 0;
            int j = i + 1;
            int last = i;
            while (j < frames) {
                if (Float.isNaN(sm[j])) { if (++gap > 3) break; j++; continue; }
                if (Math.abs(sm[j] - ref) > 0.7f) break;
                gap = 0;
                last = j;
                j++;
            }
            int len = last - start + 1;
            if (len >= minLen) {
                float[] vals = new float[len];
                int k = 0;
                for (int t = start; t <= last; t++) if (!Float.isNaN(sm[t])) vals[k++] = sm[t];
                java.util.Arrays.sort(vals, 0, k);
                float med = vals[k / 2];
                Note nt = new Note();
                nt.midi = Math.round(med);
                nt.cents = (med - nt.midi) * 100;
                nt.start = Math.round(start * hop * 4.0 + Pitch.N * 2.0);  // window centre, back to 48 kHz
                nt.len = (int) Math.round(len * hop * 4.0);
                f.notes.add(nt);
                centsSum += nt.cents;
                centsN++;
                if (f.low < 0 || nt.midi < f.low) f.low = nt.midi;
                if (nt.midi > f.high) f.high = nt.midi;
            }
            i = last + 1;
        }
        f.avgCents = centsN > 0 ? (float) (centsSum / centsN) : 0;
    }

    static String keyName(int root, boolean minor) {
        if (root < 0) return "No key";
        return NOTES[root] + (minor ? " minor" : " major");
    }

    // -------------------------------------------------------------------- fft

    private static float[] hann(int n) {
        float[] w = new float[n];
        for (int i = 0; i < n; i++) w[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (n - 1)));
        return w;
    }

    /** In-place iterative radix-2 complex FFT; n must be a power of two. */
    static void fft(float[] re, float[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                float t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len;
            float wr = (float) Math.cos(ang), wi = (float) Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                float cr = 1, ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k, b = a + len / 2;
                    float tr = re[b] * cr - im[b] * ci, ti = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - tr; im[b] = im[a] - ti;
                    re[a] += tr; im[a] += ti;
                    float ncr = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = ncr;
                }
            }
        }
    }
}
