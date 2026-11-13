package com.roughhouse.app;

/** One mono track: 16-bit samples at Engine.SR plus its mix/effect settings. */
final class Track {
    static final int PB = 1024; // frames per waveform peak

    final long id;
    String name;

    volatile short[] data = new short[0];
    volatile float[] peaks = new float[0];
    short[] undo;          // audio before the last take / clear (single-level undo)
    boolean dirty;         // audio needs writing to disk

    // Mix + FX (read live by the audio thread)
    volatile float vol = 0.8f;
    volatile float pan = 0f;      // -1..1
    volatile float lowCut = 0f;   // Hz, 0 = off
    volatile float bass = 0f;     // dB
    volatile float treble = 0f;   // dB
    volatile float comp = 0f;     // 0..1 amount
    volatile float echo = 0f;     // 0..0.8 wet
    volatile float echoFb = 0.35f;
    volatile int echoDiv = 3;     // index into Dsp.DIV_BEATS
    volatile float rev = 0f;      // reverb send 0..1
    volatile boolean mute, solo;
    boolean vocal;                 // shows the live pitch monitor while recording

    volatile float meter;          // peak level for the channel LED meter (set by the mixer)
    Analyzer.Full analysis;        // last analysis result (not saved)
    boolean showNotes;             // draw detected notes on the lane

    Track(long id, String name) {
        this.id = id;
        this.name = name;
    }

    boolean isEmpty() {
        return data.length == 0;
    }

    boolean fxActive() {
        return lowCut > 0 || Math.abs(bass) > 0.1f || Math.abs(treble) > 0.1f
                || comp > 0 || echo > 0 || rev > 0;
    }

    void resetFx() {
        vol = 0.8f; pan = 0f; lowCut = 0f; bass = 0f; treble = 0f;
        comp = 0f; echo = 0f; echoFb = 0.35f; echoDiv = 3; rev = 0f;
    }

    void copySettingsFrom(Track o) {
        vol = o.vol; pan = o.pan; lowCut = o.lowCut; bass = o.bass; treble = o.treble;
        comp = o.comp; echo = o.echo; echoFb = o.echoFb; echoDiv = o.echoDiv; rev = o.rev;
    }

    /** Audio changed: earlier analysis no longer applies. */
    void invalidateAnalysis() {
        analysis = null;
        showNotes = false;
    }

    void computePeaks() {
        short[] d = data;
        int n = (d.length + PB - 1) / PB;
        float[] p = new float[n];
        for (int b = 0; b < n; b++) {
            int s = b * PB, e = Math.min(d.length, s + PB), m = 0;
            for (int i = s; i < e; i++) {
                int v = d[i];
                if (v < 0) v = -v;
                if (v > m) m = v;
            }
            p[b] = m / 32768f;
        }
        peaks = p;
    }
}
