package com.roughhouse.app;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

/**
 * One tape lane: waveform on the shared timeline, live take while recording, optional
 * detected-notes overlay, and a channel LED meter on the right edge.
 * Tap = move playhead, long-press = channel menu.
 */
final class WaveView extends View {
    private final MainActivity a;
    final Track t;
    private final Paint wave = new Paint(), recP = new Paint(), head = new Paint(), loopP = new Paint(),
            label = new Paint(Paint.ANTI_ALIAS_FLAG), noteP = new Paint(Paint.ANTI_ALIAS_FLAG), meterP = new Paint();
    private float[] lines = new float[0];
    private final GestureDetector gd;

    WaveView(MainActivity a, Track t) {
        super(a);
        this.a = a;
        this.t = t;
        float d = Ui.density;
        wave.setStrokeWidth(1f);
        recP.setColor(Ui.RED);
        recP.setStrokeWidth(1f);
        head.setColor(Ui.CREAM);
        head.setStrokeWidth(Math.max(1.5f, d * 1.2f));
        label.setTextSize(9.5f * d);
        label.setTypeface(Ui.sansBold);
        label.setLetterSpacing(0.1f);
        loopP.setColor(0x1AE9A23B);
        noteP.setTypeface(Ui.sansBold);
        noteP.setTextSize(8 * d);
        gd = new GestureDetector(a, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }

            @Override public boolean onSingleTapUp(MotionEvent e) {
                a.openTrack(t);
                return true;
            }

            @Override public void onLongPress(MotionEvent e) {
                a.showTrackMenu(t);
            }
        });
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        return gd.onTouchEvent(e) || super.onTouchEvent(e);
    }

    private float[] buf(int n) {
        if (lines.length < n) lines = new float[n + 256];
        return lines;
    }

    @Override
    protected void onDraw(Canvas c) {
        int fullW = getWidth(), h = getHeight();
        if (fullW <= 0 || h <= 0) return;
        float u = Ui.density;
        int meterW = Math.round(5 * u);
        int w = fullW - meterW - Math.round(3 * u);
        c.drawColor(Ui.LANE);
        Jam s = a.s;
        long vf = a.viewFrames();
        double fpp = vf / (double) w;
        float mid = h / 2f, amp = h / 2f - 3;

        if (s.loop) {
            long lout = s.loopOut > 0 ? s.loopOut : s.lengthFrames();
            if (lout > s.loopIn) c.drawRect((float) (s.loopIn / fpp), 0, (float) (lout / fpp), h, loopP);
        }

        // bar lines from the jam's tempo and time signature
        double bar = s.beatFrames(Engine.SR) * Math.max(1, s.beatsPerBar);
        if (bar / fpp > 6 * u) {
            meterP.setColor(0xFF1F1C19);
            for (double f = bar; f < vf; f += bar) {
                float x = (float) (f / fpp);
                c.drawRect(x, 0, x + 1, h, meterP);
            }
        }

        boolean audible = a.isAudible(t);
        boolean notes = t.showNotes && t.analysis != null && !t.analysis.notes.isEmpty();
        float[] pk = t.peaks;
        wave.setColor(!audible ? 0xFF3A3630 : notes ? 0x66E9A23B : Ui.AMBER);
        float[] l = buf(w * 4);
        int cnt = 0;
        for (int xi = 0; xi < w; xi++) {
            int i0 = (int) (xi * fpp / Track.PB);
            if (i0 >= pk.length) break;
            int i1 = Math.min(pk.length, Math.max(i0 + 1, (int) ((xi + 1) * fpp / Track.PB)));
            float m = 0;
            for (int i = i0; i < i1; i++) if (pk[i] > m) m = pk[i];
            float hh = Math.max(0.5f, m * amp);
            l[cnt++] = xi; l[cnt++] = mid - hh; l[cnt++] = xi; l[cnt++] = mid + hh;
        }
        if (cnt > 0) c.drawLines(l, 0, cnt, wave);

        if (notes) drawNotes(c, w, h, fpp);

        Engine e = a.eng;
        if (e.isRecording() && e.recTrack == t) {
            float[] rp = e.recPeaks;
            int rc = Math.min(e.recPeakCount, rp.length);
            long first = e.recOrigin - e.latencyFrames, punch = e.punchStart;
            float[] rl = buf(rc * 4);
            cnt = 0;
            for (int k = 0; k < rc; k++) {
                long f = first + (long) k * Track.PB;
                if (f < punch) continue;
                float xx = (float) (f / fpp);
                if (xx > w) break;
                float hh = Math.max(0.5f, rp[k] * amp);
                rl[cnt++] = xx; rl[cnt++] = mid - hh; rl[cnt++] = xx; rl[cnt++] = mid + hh;
            }
            if (cnt > 0) c.drawLines(rl, 0, cnt, recP);
        }

        long ph = a.playhead();
        if (ph >= 0) {
            float px = (float) (ph / fpp);
            c.drawLine(px, 0, px, h, head);
        }

        boolean armed = t == a.nextRecTrack();
        label.setColor(armed ? Ui.RED : Ui.DIM);
        c.drawText(t.name.toUpperCase(java.util.Locale.ROOT) + (t.vocal ? "  ·  VOX" : ""),
                5 * u, 12 * u, label);

        // channel LED meter: 10 segments, green / amber / red
        float m = e.isPlaying() ? t.meter : 0;
        int lit = (int) Math.ceil(Math.max(0, Math.min(1, (m > 1e-4f ? (20 * Math.log10(m) + 30) / 30 : 0))) * 10);
        float segH = (h - 4 * u) / 10f;
        float mx = fullW - meterW;
        for (int i = 0; i < 10; i++) {
            float y1 = h - 2 * u - (i + 1) * segH + 1;
            int col = i >= 9 ? Ui.RED : i >= 7 ? Ui.AMBER : Ui.GREEN;
            meterP.setColor(i < lit ? col : 0xFF24211D);
            c.drawRect(mx, y1, fullW, y1 + segH - 1.5f * u, meterP);
        }
    }

    /** Piano-roll style blocks for the detected melody; out-of-key notes are drawn in red. */
    private void drawNotes(Canvas c, int w, int h, double fpp) {
        Analyzer.Full an = t.analysis;
        int lo = an.low, hi = Math.max(an.high, an.low + 1);
        float u = Ui.density;
        float top = 16 * u, bottom = h - 4 * u;
        float rowH = Math.max(2 * u, Math.min(6 * u, (bottom - top) / (hi - lo + 1)));
        Jam s = a.s;
        int root = s.keyRoot >= 0 ? s.keyRoot : an.base.keyRoot;
        boolean minor = s.keyRoot >= 0 ? s.keyMinor : an.base.minor;
        for (Analyzer.Note n : an.notes) {
            float x1 = (float) (n.start / fpp), x2 = (float) ((n.start + n.len) / fpp);
            if (x2 < 0 || x1 > w) continue;
            float y = bottom - (n.midi - lo + 1) * (bottom - top) / (hi - lo + 1);
            boolean out = root >= 0 && !Theory.inKey(n.midi, root, minor);
            noteP.setColor(out ? Ui.RED : Ui.CREAM);
            c.drawRect(x1, y, Math.max(x1 + 2 * u, x2 - 1), y + rowH, noteP);
            if (x2 - x1 > 22 * u) {
                noteP.setColor(Ui.DIM);
                c.drawText(Pitch.noteName(n.midi), x1 + 2 * u, y - 2 * u, noteP);
            }
        }
    }
}
