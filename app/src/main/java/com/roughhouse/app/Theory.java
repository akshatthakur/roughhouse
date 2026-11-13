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
}
