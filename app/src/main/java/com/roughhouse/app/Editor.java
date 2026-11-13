package com.roughhouse.app;

import java.util.Arrays;

/** Non-destructive-to-the-caller edits on a track's audio. Each returns a new array. */
final class Editor {
    private Editor() {}

    static final int FADE_EDGE = Engine.SR / 200; // 5 ms ramp so cuts don't click

    /** Silences everything before frame p (keeps the timeline position of what's left). */
    static short[] trimBefore(short[] d, long p) {
        int at = (int) Math.max(0, Math.min(d.length, p));
        short[] o = Arrays.copyOf(d, d.length);
        Arrays.fill(o, 0, at, (short) 0);
        ramp(o, at, Math.min(d.length, at + FADE_EDGE), true);
        return o;
    }

    /** Cuts everything from frame p onward. */
    static short[] trimAfter(short[] d, long p) {
        int at = (int) Math.max(0, Math.min(d.length, p));
        short[] o = Arrays.copyOf(d, at);
        ramp(o, Math.max(0, at - FADE_EDGE), at, false);
        return o;
    }

    /** Part from frame p onward, in place on the timeline (for the new track when splitting). */
    static short[] tail(short[] d, long p) {
        return trimBefore(d, p);
    }

    /** Moves the audio later (positive) or earlier (negative) by n frames. */
    static short[] shift(short[] d, long n) {
        if (n == 0) return Arrays.copyOf(d, d.length);
        if (n > 0) {
            short[] o = new short[(int) Math.min(Integer.MAX_VALUE - 8, d.length + n)];
            System.arraycopy(d, 0, o, (int) n, o.length - (int) n);
            return o;
        }
        int drop = (int) Math.min(d.length, -n);
        short[] o = Arrays.copyOfRange(d, drop, d.length);
        ramp(o, 0, Math.min(o.length, FADE_EDGE), true);
        return o;
    }

    /** First frame louder than about -40 dBFS, or -1 if the track is silent. */
    static int firstSound(short[] d) {
        for (int i = 0; i < d.length; i++) if (d[i] > 328 || d[i] < -328) return i;
        return -1;
    }

    /** Last frame louder than about -40 dBFS, or -1. */
    static int lastSound(short[] d) {
        for (int i = d.length - 1; i >= 0; i--) if (d[i] > 328 || d[i] < -328) return i;
        return -1;
    }

    /** Linear fade in from the first sound up to frame p. */
    static short[] fadeInTo(short[] d, long p) {
        int a = Math.max(0, firstSound(d)), b = (int) Math.max(a + 1, Math.min(d.length, p));
        short[] o = Arrays.copyOf(d, d.length);
        Arrays.fill(o, 0, a, (short) 0);
        ramp(o, a, b, true);
        return o;
    }

    /** Linear fade out from frame p to the last sound; silence after. */
    static short[] fadeOutFrom(short[] d, long p) {
        int end = lastSound(d);
        if (end < 0) return Arrays.copyOf(d, d.length);
        int a = (int) Math.max(0, Math.min(end, p));
        short[] o = Arrays.copyOf(d, end + 1);
        ramp(o, a, end + 1, false);
        return o;
    }

    /** In-place linear gain ramp over [from, to): 0 to 1 when up, 1 to 0 otherwise. */
    private static void ramp(short[] o, int from, int to, boolean up) {
        int n = to - from;
        if (n <= 0) return;
        for (int i = 0; i < n; i++) {
            float g = up ? i / (float) n : 1 - i / (float) n;
            o[from + i] = (short) Math.round(o[from + i] * g);
        }
    }
}
