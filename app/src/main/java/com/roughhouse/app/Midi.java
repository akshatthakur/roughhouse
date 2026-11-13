package com.roughhouse.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Standard MIDI File (type 1) writer: a conductor track with tempo, time signature and key,
 * one track per analyzed melody, and one track of block chords.
 */
final class Midi {
    private Midi() {}

    static final int PPQ = 480;

    static final class Part {
        String name;
        List<Analyzer.Note> notes = new ArrayList<>();
    }

    static final class ChordAt {
        long start;     // frame
        long len;       // frames
        int[] midis;
    }

    /** Builds MIDI notes for a chord name like "Am", "F#", "Bdim", voiced around middle C. */
    static int[] voice(String name) {
        int root = -1, i = 0;
        String[] sharp = Analyzer.NOTES;
        String[] flat = {"C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B"};
        String[] other = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};
        for (String[] names : new String[][]{sharp, flat, other}) {
            for (int k = 0; k < 12; k++) {
                // longest match wins, so "Db" beats "D"
                if (name.startsWith(names[k]) && names[k].length() > i) { root = k; i = names[k].length(); }
            }
        }
        if (root < 0) return new int[0];
        String q = name.substring(i);
        int third = q.startsWith("m") || q.startsWith("dim") ? 3 : 4;
        int fifth = q.startsWith("dim") ? 6 : 7;
        int base = 48 + root;               // C3..B3 roots
        return new int[]{base, base + third, base + fifth};
    }

    static void write(OutputStream os, Jam s, int sr, List<Part> parts, List<ChordAt> chords) throws IOException {
        double qf = s.quarterFrames(sr);
        int tracks = 1 + parts.size() + (chords.isEmpty() ? 0 : 1);
        // header
        os.write(new byte[]{'M', 'T', 'h', 'd'});
        be32(os, 6);
        be16(os, 1);
        be16(os, tracks);
        be16(os, PPQ);

        // conductor
        ByteArrayOutputStream t = new ByteArrayOutputStream();
        meta(t, 0, 0x03, "Roughhouse".getBytes("UTF-8"));
        int usPerQ = (int) Math.round(qf / sr * 1_000_000);
        meta(t, 0, 0x51, new byte[]{(byte) (usPerQ >> 16), (byte) (usPerQ >> 8), (byte) usPerQ});
        int dd = Integer.numberOfTrailingZeros(Math.max(1, s.beatUnit));
        meta(t, 0, 0x58, new byte[]{(byte) s.beatsPerBar, (byte) dd, 24, 8});
        if (s.keyRoot >= 0) {
            meta(t, 0, 0x59, new byte[]{(byte) sharps(s.keyRoot, s.keyMinor), (byte) (s.keyMinor ? 1 : 0)});
        }
        meta(t, 0, 0x2F, new byte[0]);
        chunk(os, t.toByteArray());

        int ch = 0;
        for (Part p : parts) {
            List<long[]> ev = new ArrayList<>(); // {tick, order, status, data1, data2}
            for (Analyzer.Note n : p.notes) {
                long on = ticks(n.start, qf), off = Math.max(on + 1, ticks(n.start + n.len, qf));
                ev.add(new long[]{on, 1, 0x90 | ch, n.midi, 96});
                ev.add(new long[]{off, 0, 0x80 | ch, n.midi, 0});
            }
            chunk(os, events(p.name, ev));
            ch = Math.min(ch + 1, 8);
        }
        if (!chords.isEmpty()) {
            List<long[]> ev = new ArrayList<>();
            int cc = 9 == ch ? 10 : ch; // never the drum channel
            for (ChordAt c : chords) {
                long on = ticks(c.start, qf), off = Math.max(on + 1, ticks(c.start + c.len, qf) - 1);
                for (int m : c.midis) {
                    ev.add(new long[]{on, 1, 0x90 | cc, m, 72});
                    ev.add(new long[]{off, 0, 0x80 | cc, m, 0});
                }
            }
            chunk(os, events("chords", ev));
        }
        os.flush();
    }

    private static byte[] events(String name, List<long[]> ev) throws IOException {
        // sort by tick, note-offs before note-ons at the same tick
        Collections.sort(ev, (a, b) -> a[0] != b[0] ? Long.compare(a[0], b[0]) : Long.compare(a[1], b[1]));
        ByteArrayOutputStream t = new ByteArrayOutputStream();
        meta(t, 0, 0x03, name.getBytes("UTF-8"));
        long last = 0;
        for (long[] e : ev) {
            varLen(t, e[0] - last);
            last = e[0];
            t.write((int) e[2]);
            t.write((int) e[3] & 0x7F);
            t.write((int) e[4] & 0x7F);
        }
        meta(t, 0, 0x2F, new byte[0]);
        return t.toByteArray();
    }

    private static long ticks(long frame, double quarterFrames) {
        return Math.max(0, Math.round(frame / quarterFrames * PPQ));
    }

    /** Key signature as sharps (+) or flats (-) for the MIDI key meta event. */
    static int sharps(int root, boolean minor) {
        int major = minor ? (root + 3) % 12 : root;
        int[] bySemitone = {0, -5, 2, -3, 4, -1, 6, 1, -4, 3, -2, 5}; // C Db D Eb E F F# G Ab A Bb B
        return bySemitone[major];
    }

    private static void meta(ByteArrayOutputStream t, long delta, int type, byte[] data) throws IOException {
        varLen(t, delta);
        t.write(0xFF);
        t.write(type);
        varLen(t, data.length);
        t.write(data);
    }

    private static void chunk(OutputStream os, byte[] body) throws IOException {
        os.write(new byte[]{'M', 'T', 'r', 'k'});
        be32(os, body.length);
        os.write(body);
    }

    private static void varLen(OutputStream o, long v) throws IOException {
        long buf = v & 0x7F;
        while ((v >>= 7) > 0) buf = (buf << 8) | 0x80 | (v & 0x7F);
        while (true) {
            o.write((int) (buf & 0xFF));
            if ((buf & 0x80) != 0) buf >>= 8;
            else break;
        }
    }

    private static void be32(OutputStream o, int v) throws IOException {
        o.write(v >>> 24); o.write(v >>> 16); o.write(v >>> 8); o.write(v);
    }

    private static void be16(OutputStream o, int v) throws IOException {
        o.write(v >>> 8); o.write(v);
    }
}
