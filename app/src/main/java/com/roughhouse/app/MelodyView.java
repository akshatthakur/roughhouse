package com.roughhouse.app;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Compact melody summary: how much each note is used, then the melody split into phrases
 * (breaks at rests), with repeated phrases folded into one row. Tapping a phrase calls back
 * with its start so the track view can jump there.
 */
final class MelodyView extends View {
    interface OnPhrase { void open(long startFrame); }

    static final class Phrase {
        final List<Analyzer.Note> notes = new ArrayList<>();
        final List<Integer> bars = new ArrayList<>(); // 1-based bar of each occurrence
        long start;
        int[] key() {
            int[] k = new int[notes.size()];
            for (int i = 0; i < k.length; i++) k[i] = notes.get(i).midi;
            return k;
        }
    }

    static final int MAX_ROWS = 16;

    private final Analyzer.Full f;
    private final int root;
    private final boolean minor;
    final List<Phrase> phrases = new ArrayList<>();
    final int totalPhrases;
    private final double[] use = new double[12];
    OnPhrase onPhrase;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint();
    private final RectF r = new RectF();

    MelodyView(MainActivity a, Analyzer.Full f, int root, boolean minor, double beatFrames, int beatsPerBar) {
        super(a);
        this.f = f;
        this.root = root;
        this.minor = minor;
        for (Analyzer.Note n : f.notes) use[Math.floorMod(n.midi, 12)] += n.len;

        // split into phrases at rests longer than a beat (at least 0.35 s)
        double gap = Math.max(beatFrames, 0.35 * Engine.SR);
        double bar = beatFrames * Math.max(1, beatsPerBar);
        List<Phrase> all = new ArrayList<>();
        Phrase cur = null;
        long lastEnd = Long.MIN_VALUE;
        for (Analyzer.Note n : f.notes) {
            if (cur == null || n.start - lastEnd > gap || cur.notes.size() >= 24) {
                cur = new Phrase();
                cur.start = n.start;
                all.add(cur);
            }
            cur.notes.add(n);
            lastEnd = n.start + n.len;
        }
        totalPhrases = all.size();
        // fold repeats: same note sequence -> one row listing every bar it starts in
        for (Phrase ph : all) {
            int b = (int) Math.floor(ph.start / bar) + 1;
            Phrase same = null;
            for (Phrase q : phrases) if (Arrays.equals(q.key(), ph.key())) { same = q; break; }
            if (same != null) same.bars.add(b);
            else { ph.bars.add(b); phrases.add(ph); }
        }
    }

    private float histH() { return Ui.dp(92); }
    private float rowH() { return Ui.dp(52); }
    private int rows() { return Math.min(MAX_ROWS, phrases.size()); }

    @Override protected void onMeasure(int ws, int hs) {
        int w = MeasureSpec.getSize(ws);
        setMeasuredDimension(w, Math.round(histH() + Ui.dp(14) + rows() * rowH()));
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), u = Ui.density;
        // ---- note usage, ordered from the key's root when there is one
        double max = 0;
        for (double v : use) max = Math.max(max, v);
        int start = root >= 0 ? root : 0;
        float colW = w / 12f, baseY = histH() - 18 * u, top = 14 * u;
        p.setTextAlign(Paint.Align.CENTER);
        p.setTypeface(Ui.sansBold);
        p.setTextSize(8 * u);
        p.setColor(Ui.FAINT);
        p.setTextAlign(Paint.Align.LEFT);
        c.drawText("HOW MUCH EACH NOTE IS USED", 0, 9 * u, p);
        p.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i < 12; i++) {
            int pc = (start + i) % 12;
            boolean inKey = root < 0 || Theory.inKey(pc, root, minor);
            float hgt = max > 0 ? (float) (use[pc] / max) * (baseY - top) : 0;
            fill.setColor(use[pc] == 0 ? 0xFF24211D : inKey ? Ui.AMBER : Ui.RED);
            r.set(i * colW + 3 * u, baseY - Math.max(2 * u, hgt), (i + 1) * colW - 3 * u, baseY);
            c.drawRoundRect(r, 2 * u, 2 * u, fill);
            p.setTextSize(10 * u);
            p.setTypeface(Ui.sansBold);
            p.setColor(use[pc] == 0 ? Ui.FAINT : inKey ? Ui.CREAM : Ui.RED);
            c.drawText(Analyzer.NOTES[pc], i * colW + colW / 2, baseY + 13 * u, p);
        }

        // ---- phrases
        float y0 = histH() + 14 * u;
        p.setTextAlign(Paint.Align.LEFT);
        p.setTypeface(Ui.sansBold);
        p.setTextSize(8 * u);
        p.setColor(Ui.FAINT);
        c.drawText("PHRASES  (TAP ONE TO SEE IT IN THE TRACK VIEW)", 0, y0 - 4 * u, p);
        float labelW = 70 * u;
        for (int i = 0; i < rows(); i++) {
            Phrase ph = phrases.get(i);
            float y = y0 + i * rowH();
            fill.setColor(i % 2 == 0 ? 0xFF1F1C19 : 0xFF1A1815);
            c.drawRect(0, y, w, y + rowH() - 3 * u, fill);
            // label: first bar and how many times it repeats
            p.setTypeface(Ui.sansBold);
            p.setTextSize(11 * u);
            p.setColor(Ui.CREAM);
            c.drawText("bar " + ph.bars.get(0), 6 * u, y + 17 * u, p);
            p.setTypeface(Ui.sans);
            p.setTextSize(9.5f * u);
            p.setColor(ph.bars.size() > 1 ? Ui.AMBER : Ui.DIM);
            c.drawText(ph.bars.size() > 1 ? "×" + ph.bars.size() : ph.notes.size() + " notes", 6 * u, y + 31 * u, p);
            // mini piano roll of the phrase
            float rx = labelW, rw = w - labelW - 6 * u, ry = y + 5 * u, rh = rowH() - 22 * u;
            long t0 = ph.notes.get(0).start;
            Analyzer.Note last = ph.notes.get(ph.notes.size() - 1);
            double span = Math.max(1, last.start + last.len - t0);
            int lo = 999, hi = -999;
            for (Analyzer.Note n : ph.notes) { lo = Math.min(lo, n.midi); hi = Math.max(hi, n.midi); }
            int range = Math.max(4, hi - lo);
            StringBuilder names = new StringBuilder();
            for (Analyzer.Note n : ph.notes) {
                float x1 = rx + (float) ((n.start - t0) / span) * rw, x2 = rx + (float) ((n.start + n.len - t0) / span) * rw;
                float ny = ry + rh - (n.midi - lo) / (float) range * (rh - 4 * u) - 4 * u;
                boolean out = root >= 0 && !Theory.inKey(n.midi, root, minor);
                fill.setColor(out ? Ui.RED : Ui.AMBER);
                r.set(x1, ny, Math.max(x1 + 3 * u, x2 - 1.5f * u), ny + 4 * u);
                c.drawRoundRect(r, 1.5f * u, 1.5f * u, fill);
                if (names.length() > 0) names.append(' ');
                names.append(Analyzer.NOTES[Math.floorMod(n.midi, 12)]);
            }
            p.setTypeface(Ui.sans);
            p.setTextSize(9.5f * u);
            p.setColor(Ui.DIM);
            String txt = names.toString();
            while (txt.length() > 1 && p.measureText(txt) > rw) txt = txt.substring(0, txt.length() - 2);
            if (txt.length() < names.length()) txt = txt.trim() + " …";
            c.drawText(txt, rx, y + rowH() - 8 * u, p);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_UP && onPhrase != null) {
            float y = e.getY() - (histH() + Ui.dp(14));
            int i = (int) (y / rowH());
            if (y >= 0 && i < rows()) onPhrase.open(phrases.get(i).start);
        }
        return true;
    }
}
