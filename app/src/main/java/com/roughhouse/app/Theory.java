package com.roughhouse.app;

import java.util.ArrayList;

/** Songwriting helpers derived from a key: scale, diatonic chords, common progressions. */
final class Theory {
    private Theory() {}

    private static final int[] MAJOR = {0, 2, 4, 5, 7, 9, 11};
    private static final int[] MINOR = {0, 2, 3, 5, 7, 8, 10};
    private static final String[] MAJ_Q = {"", "m", "m", "", "", "m", "dim"};
    private static final String[] MIN_Q = {"m", "dim", "", "m", "m", "", ""};
    private static final String[] MAJ_N = {"I", "ii", "iii", "IV", "V", "vi", "vii°"};
    private static final String[] MIN_N = {"i", "ii°", "III", "iv", "v", "VI", "VII"};

    static int[] scale(int root, boolean minor) {
        int[] s = minor ? MINOR : MAJOR, out = new int[7];
        for (int i = 0; i < 7; i++) out[i] = (root + s[i]) % 12;
        return out;
    }

    static boolean inKey(int midi, int root, boolean minor) {
        int pc = Math.floorMod(midi, 12);
        for (int p : scale(root, minor)) if (p == pc) return true;
        // in minor, the raised 7th (harmonic minor) is normal too
        return minor && pc == (root + 11) % 12;
    }

    static String scaleNames(int root, boolean minor) {
        StringBuilder b = new StringBuilder();
        for (int p : scale(root, minor)) b.append(b.length() > 0 ? "  " : "").append(Analyzer.NOTES[p]);
        return b.toString();
    }

    /** "I  C", "ii  Dm", ... */
    static String[] diatonic(int root, boolean minor) {
        int[] sc = scale(root, minor);
        String[] out = new String[minor ? 8 : 7];
        for (int i = 0; i < 7; i++) {
            out[i] = (minor ? MIN_N : MAJ_N)[i] + "   " + Analyzer.NOTES[sc[i]] + (minor ? MIN_Q : MAJ_Q)[i];
        }
        if (minor) out[7] = "V (harmonic)   " + Analyzer.NOTES[sc[4]];
        return out;
    }

    /** Common progressions spelled in this key. */
    static ArrayList<String> progressions(int root, boolean minor) {
        int[][] degs = minor
                ? new int[][]{{0, 5, 2, 6}, {0, 3, 4, 0}, {0, 6, 5, 6}, {0, 3, 6, 2}, {0, 5, 3, -4}}
                : new int[][]{{0, 4, 5, 3}, {5, 3, 0, 4}, {0, 3, 4, 0}, {1, 4, 0, 0}, {0, 5, 3, 4}};
        String[] labels = minor
                ? new String[]{"i  VI  III  VII", "i  iv  v  i", "i  VII  VI  VII", "i  iv  VII  III", "i  VI  iv  V"}
                : new String[]{"I  V  vi  IV", "vi  IV  I  V", "I  IV  V  I", "ii  V  I", "I  vi  IV  V"};
        int[] sc = scale(root, minor);
        ArrayList<String> out = new ArrayList<>();
        for (int i = 0; i < degs.length; i++) {
            StringBuilder b = new StringBuilder();
            int count = (!minor && i == 3) ? 3 : 4;
            for (int k = 0; k < count; k++) {
                int d = degs[i][k];
                String name;
                if (d == -4) name = Analyzer.NOTES[sc[4]]; // major V borrowed from harmonic minor
                else name = Analyzer.NOTES[sc[d]] + (minor ? MIN_Q : MAJ_Q)[d];
                b.append(k > 0 ? " - " : "").append(name);
            }
            out.add(labels[i] + "      " + b);
        }
        return out;
    }

    // ------------------------------------------------------ melody harmonizer

    /** A suggested chord for one bar, with a second choice when it's close. */
    static final class Suggestion {
        int bar;          // 0-based
        String best, alt; // alt may be null
        int bestRoot;     // pitch class of the best chord's root
        boolean bestMinor, bestDim;
    }

    /**
     * For each bar with melody, picks the chords from the key whose tones cover the most of
     * the melody (weighted by note length, with the downbeat counting extra).
     */
    static ArrayList<Suggestion> harmonize(java.util.List<Analyzer.Note> notes, int root, boolean minor,
                                           double beatFrames, int beatsPerBar) {
        ArrayList<Suggestion> out = new ArrayList<>();
        if (root < 0 || notes.isEmpty()) return out;
        double bar = beatFrames * Math.max(1, beatsPerBar);
        int[] sc = scale(root, minor);
        String[] q = minor ? MIN_Q : MAJ_Q;
        // candidate triads: the seven diatonic chords (+ major V in minor)
        int n = minor ? 8 : 7;
        int[][] tones = new int[n][];
        String[] names = new String[n];
        int[] roots = new int[n];
        for (int i = 0; i < 7; i++) {
            int r = sc[i], third = sc[(i + 2) % 7], fifth = sc[(i + 4) % 7];
            tones[i] = new int[]{r, third, fifth};
            names[i] = Analyzer.NOTES[r] + q[i];
            roots[i] = r;
        }
        if (minor) {
            int r = sc[4];
            tones[7] = new int[]{r, (r + 4) % 12, (r + 7) % 12};
            names[7] = Analyzer.NOTES[r];
            roots[7] = r;
        }
        int bars = (int) Math.ceil((notes.get(notes.size() - 1).start + notes.get(notes.size() - 1).len) / bar);
        for (int b = 0; b < bars; b++) {
            double bs = b * bar, be = bs + bar;
            double[] w = new double[12];
            double total = 0;
            for (Analyzer.Note nt : notes) {
                double s0 = Math.max(bs, nt.start), s1 = Math.min(be, nt.start + nt.len);
                if (s1 <= s0) continue;
                double weight = s1 - s0;
                if (nt.start <= bs + beatFrames * 0.25 && nt.start + nt.len > bs) weight *= 1.5; // downbeat
                w[Math.floorMod(nt.midi, 12)] += weight;
                total += weight;
            }
            if (total <= 0) continue;
            int best = -1, second = -1;
            double bestS = -1e9, secondS = -1e9;
            for (int c = 0; c < n; c++) {
                double cover = 0;
                for (int t : tones[c]) cover += w[t];
                double score = cover / total - (c == 6 && !minor ? 0.15 : 0) - (c == 1 && minor ? 0.15 : 0); // diminished last
                if (c == 0) score += 0.05; // mild pull toward the home chord
                if (score > bestS) { second = best; secondS = bestS; best = c; bestS = score; }
                else if (score > secondS) { second = c; secondS = score; }
            }
            Suggestion sg = new Suggestion();
            sg.bar = b;
            sg.best = names[best];
            sg.bestRoot = roots[best];
            sg.bestMinor = names[best].endsWith("m");
            sg.bestDim = names[best].endsWith("dim");
            if (second >= 0 && bestS - secondS < 0.12) sg.alt = names[second];
            out.add(sg);
        }
        return out;
    }
}
