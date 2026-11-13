package com.roughhouse.app;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

/**
 * Full-screen view of one track: bar ruler, chord row, piano roll of detected notes with
 * note names, and the waveform underneath. Pinch to zoom, drag to scroll, tap to seek.
 */
final class TrackView extends View {
    private final MainActivity a;
    final Track t;
    private double left;          // timeline frame at the left edge of the content area
    private double fpp = -1;      // frames per pixel
    private boolean userMoved;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint();
    private final RectF r = new RectF();
    private final GestureDetector gd;
    private final ScaleGestureDetector sg;

    TrackView(MainActivity a, Track t) {
        super(a);
        this.a = a;
        this.t = t;
        gd = new GestureDetector(a, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }

            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                left = clampLeft(left + dx * fpp);
                userMoved = true;
                invalidate();
                return true;
            }

            @Override public boolean onSingleTapUp(MotionEvent e) {
                float x = e.getX() - gutter();
                if (x < 0) return true;
                Analyzer.Full an = t.analysis;
                // chord row: change the chord in that bar
                if (an != null && lChordH > 0 && e.getY() >= lRulerH && e.getY() < lRulerH + lChordH) {
                    int bar = (int) Math.floor((left + x * fpp) / lBar);
                    if (bar >= 0) a.editChord(t, bar, lShowSugg);
                    return true;
                }
                // a note: select it
                Analyzer.Note hit = noteAt(e.getX(), e.getY());
                if (hit != null) {
                    select(hit);
                    return true;
                }
                if (selected != null) { select(null); return true; }
                a.seekTo((long) Math.max(0, left + x * fpp));
                userMoved = false;
                invalidate();
                return true;
            }

            @Override public void onLongPress(MotionEvent e) {
                Analyzer.Full an = t.analysis;
                if (an == null || e.getX() < gutter() || e.getY() < lRollTop || e.getY() > lRollBot) return;
                Analyzer.Note hit = noteAt(e.getX(), e.getY());
                if (hit != null) { select(hit); return; }
                // add a half-beat note at this pitch, snapped to the nearest half beat
                int midi = lLo + (int) Math.floor((lRollBot - e.getY()) / lRowH);
                double half = a.s.beatFrames(Engine.SR) / 2;
                long at = Math.round(Math.round((left + (e.getX() - gutter()) * fpp) / half) * half);
                Analyzer.Note n = new Analyzer.Note();
                n.start = Math.max(0, at);
                n.len = (int) Math.round(half);
                n.midi = midi;
                an.notes.add(n);
                Analyzer.refreshAfterEdit(an);
                a.analysisEdited(t);
                select(n);
            }
        });
        sg = new ScaleGestureDetector(a, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                float fx = d.getFocusX() - gutter();
                double at = left + fx * fpp;
                fpp = clampFpp(fpp / d.getScaleFactor());
                left = clampLeft(at - fx * fpp);
                userMoved = true;
                invalidate();
                return true;
            }
        });
    }

    private float gutter() { return Ui.dp(38); }

    private long total() {
        return Math.max(Math.max(t.data.length, a.s.lengthFrames()), 4L * Engine.SR);
    }

    private double clampFpp(double v) {
        float w = Math.max(1, getWidth() - gutter());
        double min = 32;                               // very close: ~150 px per beat at 120 BPM
        double max = total() * 1.05 / w;               // whole track on screen
        return Math.max(min, Math.min(max, v));
    }

    private double clampLeft(double v) {
        float w = Math.max(1, getWidth() - gutter());
        return Math.max(0, Math.min(v, total() - w * fpp * 0.5));
    }

    // layout from the last draw, for hit testing
    private float lRulerH, lChordH, lRollTop, lRollBot, lRowH;
    private int lLo;
    private double lBar;
    private boolean lShowSugg;

    interface OnSelect { void selected(Analyzer.Note n); }

    Analyzer.Note selected;
    OnSelect onSelect;

    void select(Analyzer.Note n) {
        selected = n;
        if (onSelect != null) onSelect.selected(n);
        invalidate();
    }

    /** The note drawn under (x, y), if any (with a little slack for small blocks). */
    private Analyzer.Note noteAt(float x, float y) {
        Analyzer.Full an = t.analysis;
        if (an == null || y < lRollTop || y > lRollBot) return null;
        float slack = 6 * Ui.density;
        Analyzer.Note best = null;
        for (Analyzer.Note n : an.notes) {
            float x1 = gutter() + (float) ((n.start - left) / fpp), x2 = gutter() + (float) ((n.start + n.len - left) / fpp);
            float ny = lRollBot - (n.midi - lLo + 1) * lRowH;
            if (x >= x1 - slack && x <= Math.max(x2, x1 + 3 * Ui.density) + slack
                    && y >= ny - slack && y <= ny + lRowH + slack) best = n;
        }
        return best;
    }

    private Analyzer.Full suggFor;
    private int suggKey = -99;
    private java.util.List<Theory.Suggestion> suggCache = new java.util.ArrayList<>();

    /** Chord suggestions for the melody (cached until the analysis or key changes). */
    private java.util.List<Theory.Suggestion> suggestions(Analyzer.Full an) {
        if (an == null || an.notes.isEmpty()) return java.util.Collections.emptyList();
        Jam s = a.s;
        int root = s.keyRoot >= 0 ? s.keyRoot : an.base.keyRoot;
        boolean minor = s.keyRoot >= 0 ? s.keyMinor : an.base.minor;
        int k = root * 2 + (minor ? 1 : 0) + s.beatsPerBar * 100 + Math.round(s.bpm * 10) * 10000;
        if (an != suggFor || k != suggKey) {
            suggFor = an;
            suggKey = k;
            suggCache = Theory.harmonize(an.notes, root, minor, s.beatFrames(Engine.SR), Math.max(1, s.beatsPerBar));
        }
        return suggCache;
    }

    /** Zooms to about four bars around a point (used when jumping to a phrase). */
    void focusOn(long frame) {
        post(() -> {
            float w = Math.max(1, getWidth() - gutter());
            double bars = a.s.beatFrames(Engine.SR) * Math.max(1, a.s.beatsPerBar) * 4;
            fpp = clampFpp(bars / w);
            left = clampLeft(frame - w * fpp * 0.1);
            userMoved = true;
            invalidate();
        });
    }

    /** Zoom to show the whole track. */
    void fit() {
        fpp = -1;
        left = 0;
        userMoved = false;
        invalidate();
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        sg.onTouchEvent(e);
        if (!sg.isInProgress()) gd.onTouchEvent(e);
        return true;
    }

    @Override protected void onDraw(Canvas c) {
        int W = getWidth(), H = getHeight();
        if (W <= 0 || H <= 0) return;
        float u = Ui.density, gx = gutter(), cw = W - gx;
        if (fpp <= 0) fpp = clampFpp(Double.MAX_VALUE);
        Jam s = a.s;
        Analyzer.Full an = t.analysis;

        // follow the playhead while playing, unless the user is browsing
        long ph = a.playhead();
        if (a.eng.isPlaying() && !userMoved) {
            double x = (ph - left) / fpp;
            if (x > cw * 0.85 || x < 0) left = clampLeft(ph - cw * 0.15 * fpp);
        }

        c.drawColor(Ui.LANE);
        java.util.List<Theory.Suggestion> sugg = suggestions(an);
        boolean showSugg = an != null && an.chords.isEmpty() && !sugg.isEmpty();
        float rulerH = 22 * u, chordH = an != null ? 30 * u : 0, waveH = 64 * u;
        float rollTop = rulerH + chordH, rollBot = H - waveH;

        // pitch range for the roll
        int lo = 48, hi = 72;
        if (an != null && an.low > 0) { lo = an.low - 2; hi = an.high + 2; }
        if (hi - lo < 12) { int mid = (lo + hi) / 2; lo = mid - 6; hi = mid + 6; }
        int rows = hi - lo + 1;
        float rowH = (rollBot - rollTop) / rows;
        lRulerH = rulerH;
        lChordH = chordH;
        lRollTop = rollTop;
        lRollBot = rollBot;
        lRowH = rowH;
        lLo = lo;
        lShowSugg = showSugg;
        lBar = s.barFrames(Engine.SR);
        int root = s.keyRoot >= 0 ? s.keyRoot : (an != null ? an.base.keyRoot : -1);
        boolean minor = s.keyRoot >= 0 ? s.keyMinor : an != null && an.base.minor;

        // roll background: in-key rows lighter, C rows marked
        for (int m = lo; m <= hi; m++) {
            float y = rollBot - (m - lo + 1) * rowH;
            boolean inKey = root >= 0 && Theory.inKey(m, root, minor);
            line.setColor(inKey ? 0xFF1E1B18 : 0xFF15120F);
            c.drawRect(gx, y, W, y + rowH, line);
            line.setColor(Math.floorMod(m, 12) == 0 ? 0xFF3A352E : 0xFF1A1714);
            c.drawRect(gx, y + rowH - 1, W, y + rowH, line);
            // gutter labels
            if (rowH >= 11 * u || Math.floorMod(m, 12) == 0) {
                p.setTypeface(Math.floorMod(m, 12) == 0 ? Ui.sansBold : Ui.sans);
                p.setTextSize(Math.min(11 * u, rowH * 0.8f + 3 * u));
                p.setTextAlign(Paint.Align.RIGHT);
                p.setColor(inKey ? Ui.CREAM : Ui.FAINT);
                c.drawText(Pitch.noteName(m), gx - 5 * u, y + rowH * 0.5f + p.getTextSize() * 0.35f, p);
            }
        }

        // bar lines and numbers
        double bar = s.beatFrames(Engine.SR) * Math.max(1, s.beatsPerBar), beat = s.beatFrames(Engine.SR);
        int every = 1;
        while (bar * every / fpp < 34 * u) every *= 2;
        long firstBar = (long) Math.floor(left / bar);
        p.setTextAlign(Paint.Align.LEFT);
        p.setTypeface(Ui.sansBold);
        p.setTextSize(9.5f * u);
        if (beat / fpp > 14 * u) {
            line.setColor(0xFF221E1A);
            for (double f = Math.floor(left / beat) * beat; f < left + cw * fpp; f += beat) {
                float x = gx + (float) ((f - left) / fpp);
                c.drawRect(x, rulerH, x + 1, rollBot, line);
            }
        }
        for (long b = firstBar; b * bar < left + cw * fpp; b++) {
            float x = gx + (float) ((b * bar - left) / fpp);
            if (x < gx) continue;
            line.setColor(0xFF3A352E);
            c.drawRect(x, rulerH, x + 1, H, line);
            if (b % every == 0) {
                p.setColor(Ui.DIM);
                c.drawText(String.valueOf(b + 1), x + 3 * u, rulerH - 7 * u, p);
            }
        }

        // section names in the ruler
        p.setTypeface(Ui.sansBold);
        p.setTextSize(9 * u);
        p.setTextAlign(Paint.Align.LEFT);
        for (Jam.Marker mk : s.sortedMarkers()) {
            float x = gx + (float) ((mk.bar * bar - left) / fpp);
            if (x < gx || x > W) continue;
            line.setColor(Ui.AMBER);
            c.drawRect(x, 0, x + 1.5f * u, rulerH, line);
            p.setColor(Ui.AMBER);
            c.drawText(mk.name, x + 4 * u, 9 * u, p);
        }

        // suggested chords when the track has none of its own
        if (showSugg) {
            line.setColor(0xFF1F1C19);
            c.drawRect(gx, rulerH, W, rulerH + chordH, line);
            p.setTypeface(Ui.sansBold);
            p.setTextSize(11 * u);
            for (Theory.Suggestion g : sugg) {
                float x1 = gx + (float) ((g.bar * bar - left) / fpp), x2 = gx + (float) (((g.bar + 1) * bar - left) / fpp);
                if (x2 < gx || x1 > W) continue;
                r.set(Math.max(gx, x1) + 2 * u, rulerH + 4 * u, x2 - 2 * u, rulerH + chordH - 4 * u);
                if (r.width() < 6 * u) continue;
                line.setColor(0xFF2A2621);
                c.drawRoundRect(r, 3 * u, 3 * u, line);
                p.setColor(0xFFB7A57F);
                String label = g.best + (g.alt != null && p.measureText(g.best + " / " + g.alt) < r.width() - 6 * u ? " / " + g.alt : "");
                if (p.measureText(label) < r.width() - 6 * u) c.drawText(label, r.left + 5 * u, r.centerY() + 4 * u, p);
            }
            p.setTextSize(7.5f * u);
            p.setColor(Ui.FAINT);
            c.drawText("SUGGESTED", 4 * u, rulerH + chordH / 2 + 3 * u, p);
        }

        // chord row
        if (chordH > 0 && !showSugg) {
            if (an.chords.isEmpty()) {
                p.setTypeface(Ui.sansBold);
                p.setTextSize(7.5f * u);
                p.setColor(Ui.FAINT);
                p.setTextAlign(Paint.Align.LEFT);
                c.drawText("TAP A BAR HERE TO ADD A CHORD", gx + 6 * u, rulerH + chordH / 2 + 3 * u, p);
            }
            line.setColor(0xFF1F1C19);
            c.drawRect(gx, rulerH, W, rulerH + chordH, line);
            p.setTypeface(Ui.sansBold);
            p.setTextSize(12 * u);
            for (Analyzer.Chord ch : an.chords) {
                float x1 = gx + (float) ((ch.bar * bar - left) / fpp), x2 = gx + (float) (((ch.bar + 1) * bar - left) / fpp);
                if (x2 < gx || x1 > W) continue;
                r.set(Math.max(gx, x1) + 2 * u, rulerH + 4 * u, x2 - 2 * u, rulerH + chordH - 4 * u);
                if (r.width() < 6 * u) continue;
                line.setColor(0xFF34302A);
                c.drawRoundRect(r, 3 * u, 3 * u, line);
                p.setColor(Ui.AMBER);
                p.setTextAlign(Paint.Align.LEFT);
                if (p.measureText(ch.name) < r.width() - 6 * u) {
                    c.drawText(ch.name, r.left + 5 * u, r.centerY() + 4.5f * u, p);
                }
            }
        }

        // notes
        if (an != null) {
            p.setTypeface(Ui.sansBold);
            p.setTextSize(Math.max(8 * u, Math.min(11 * u, rowH * 0.8f)));
            for (Analyzer.Note n : an.notes) {
                float x1 = gx + (float) ((n.start - left) / fpp), x2 = gx + (float) ((n.start + n.len - left) / fpp);
                if (x2 < gx || x1 > W) continue;
                float y = rollBot - (n.midi - lo + 1) * rowH;
                boolean out = root >= 0 && !Theory.inKey(n.midi, root, minor);
                boolean sel = n == selected;
                line.setColor(sel ? Ui.CREAM : out ? Ui.RED : Ui.AMBER);
                r.set(Math.max(gx, x1), y + 1, Math.max(Math.max(gx, x1) + 3 * u, x2 - 1), y + rowH - 1);
                c.drawRoundRect(r, 2 * u, 2 * u, line);
                if (sel) {
                    line.setColor(Ui.AMBER);
                    c.drawRect(r.left, r.bottom + 1, r.right, r.bottom + 3 * u, line);
                }
                String name = Pitch.noteName(n.midi);
                if (r.width() > p.measureText(name) + 6 * u && rowH >= 9 * u) {
                    p.setColor(Ui.INK);
                    p.setTextAlign(Paint.Align.LEFT);
                    c.drawText(name, r.left + 3 * u, r.centerY() + p.getTextSize() * 0.35f, p);
                }
            }
        } else {
            p.setTypeface(Ui.serifItalic);
            p.setTextSize(16 * u);
            p.setTextAlign(Paint.Align.CENTER);
            p.setColor(Ui.DIM);
            c.drawText("analyze this track to see its notes and chords", gx + cw / 2, (rollTop + rollBot) / 2, p);
        }

        // waveform strip
        line.setColor(0xFF0E0C0A);
        c.drawRect(gx, rollBot, W, H, line);
        float mid = rollBot + waveH / 2, amp = waveH / 2 - 4 * u;
        float[] pk = t.peaks;
        line.setColor(a.isAudible(t) ? 0xCCE9A23B : 0xFF3A3630);
        for (int xi = 0; xi < cw; xi++) {
            double f0 = left + xi * fpp, f1 = f0 + fpp;
            int i0 = (int) (f0 / Track.PB), i1 = Math.max(i0 + 1, (int) (f1 / Track.PB));
            if (i0 >= pk.length) break;
            float m = 0;
            for (int i = i0; i < Math.min(i1, pk.length); i++) if (pk[i] > m) m = pk[i];
            float hh = Math.max(0.5f, m * amp);
            c.drawRect(gx + xi, mid - hh, gx + xi + 1, mid + hh, line);
        }

        // gutter background and ruler line
        line.setColor(0xFF1B1A18);
        c.drawRect(0, rulerH, gx - 1, H, line);
        line.setColor(0xFF2B2824);
        c.drawRect(0, rulerH - 1, W, rulerH, line);
        p.setTypeface(Ui.sansBold);
        p.setTextSize(8 * u);
        p.setTextAlign(Paint.Align.LEFT);
        p.setColor(Ui.FAINT);
        c.drawText("BAR", 6 * u, rulerH - 7 * u, p);

        // playhead
        float px = gx + (float) ((ph - left) / fpp);
        if (px >= gx && px <= W) {
            line.setColor(Ui.CREAM);
            c.drawRect(px - u * 0.75f, 0, px + u * 0.75f, H, line);
        }
    }
}
