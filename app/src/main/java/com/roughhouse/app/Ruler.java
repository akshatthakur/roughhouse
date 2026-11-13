package com.roughhouse.app;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

/**
 * Timeline for the whole jam, lined up with the track lanes: bar numbers, loop region,
 * an overview of every track, and the playhead. Tap or drag to move the playhead.
 */
final class Ruler extends View {
    private final MainActivity a;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint();
    private final Path tri = new Path();
    private final GestureDetector gd;

    Ruler(MainActivity a) {
        super(a);
        this.a = a;
        gd = new GestureDetector(a, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) {
                if (!a.session || !inLabels(e)) seekAt(e.getX());
                return true;
            }

            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                seekAt(e2.getX());
                return true;
            }

            @Override public boolean onSingleTapUp(MotionEvent e) {
                Jam.Marker m = a.session && inLabels(e) ? markerAt(e.getX()) : null;
                if (m != null) a.loopSection(m);
                else seekAt(e.getX());
                return true;
            }

            @Override public void onLongPress(MotionEvent e) {
                if (a.session) a.markerMenu(frameAt(e.getX()));
            }
        });
    }

    private float labelH() { return 13 * Ui.density; }

    private boolean inLabels(MotionEvent e) { return e.getY() < labelH() + 4 * Ui.density; }

    private long frameAt(float x) {
        float cx = Math.max(0, Math.min(contentW(), x));
        return (long) (cx / contentW() * a.viewFrames());
    }

    private void seekAt(float x) {
        a.seekTo(frameAt(x));
        invalidate();
    }

    /** The section whose label covers x, if any. */
    private Jam.Marker markerAt(float x) {
        double fpp = a.viewFrames() / (double) contentW();
        double bar = a.s.barFrames(Engine.SR);
        Jam.Marker hit = null;
        for (Jam.Marker m : a.s.sortedMarkers()) {
            float mx = (float) (m.bar * bar / fpp);
            p.setTextSize(9 * Ui.density);
            float wLabel = p.measureText(m.name) + 8 * Ui.density;
            if (x >= mx - 4 * Ui.density && x <= mx + Math.max(wLabel, 24 * Ui.density)) hit = m;
        }
        return hit;
    }

    /** Same content width as a WaveView of equal size (which keeps room for its LED meter). */
    private float contentW() {
        return getWidth() - Math.round(5 * Ui.density) - Math.round(3 * Ui.density);
    }

    @Override protected void onDraw(Canvas c) {
        float W = getWidth(), H = getHeight(), u = Ui.density, w = contentW();
        if (W <= 0) return;
        Jam s = a.s;
        long vf = a.viewFrames();
        double fpp = vf / (double) w;
        c.drawColor(0xFF0F0D0C);

        // loop region
        if (s.loop) {
            long lout = s.loopOut > 0 ? s.loopOut : s.lengthFrames();
            if (lout > s.loopIn) {
                fill.setColor(0x33E9A23B);
                c.drawRect((float) (s.loopIn / fpp), 0, (float) (lout / fpp), H, fill);
            }
        }

        // overview of all tracks (loudest track at each point)
        fill.setColor(0xFF3A342C);
        float mid = H * 0.7f, amp = H * 0.24f;
        for (int x = 0; x < w; x++) {
            int i0 = (int) (x * fpp / Track.PB), i1 = Math.max(i0 + 1, (int) ((x + 1) * fpp / Track.PB));
            float m = 0;
            for (Track t : s.tracks) {
                float[] pk = t.peaks;
                for (int i = i0; i < Math.min(i1, pk.length); i++) if (pk[i] > m) m = pk[i];
            }
            if (m > 0) c.drawRect(x, mid - m * amp, x + 1, mid + m * amp, fill);
        }

        float lh = labelH();
        // section labels along the top
        p.setTypeface(Ui.sansBold);
        p.setTextSize(9 * u);
        p.setTextAlign(Paint.Align.LEFT);
        java.util.List<Jam.Marker> ms = a.session ? s.sortedMarkers() : new java.util.ArrayList<>();
        for (int i = 0; i < ms.size(); i++) {
            Jam.Marker m = ms.get(i);
            float x = (float) (m.bar * bar() / fpp);
            float next = i + 1 < ms.size() ? (float) (ms.get(i + 1).bar * bar() / fpp) : w;
            if (x > w) continue;
            fill.setColor(0x55E9A23B);
            c.drawRect(x, 0, Math.max(x + 2 * u, next - 1 * u), lh, fill);
            fill.setColor(Ui.AMBER);
            c.drawRect(x, 0, x + 1.5f * u, H, fill);
            p.setColor(Ui.CREAM);
            String label = m.name;
            float room = next - x - 6 * u;
            if (room > 12 * u) {
                while (label.length() > 1 && p.measureText(label) > room) label = label.substring(0, label.length() - 1);
                c.drawText(label, x + 4 * u, lh - 3 * u, p);
            }
        }

        // bar ticks and numbers
        double bar = bar();
        int every = 1;
        while (bar * every / fpp < 28 * u) every *= 2;
        p.setTypeface(Ui.sansBold);
        p.setTextSize(8.5f * u);
        p.setTextAlign(Paint.Align.LEFT);
        for (long b = 0; b * bar < vf; b++) {
            float x = (float) (b * bar / fpp);
            boolean label = b % every == 0;
            fill.setColor(label ? 0xFF5E5A51 : 0xFF2B2824);
            c.drawRect(x, lh, x + 1, lh + (label ? 9 * u : 5 * u), fill);
            if (label) {
                p.setColor(Ui.DIM);
                p.setTextSize(8.5f * u);
                c.drawText(String.valueOf(b + 1), x + 3 * u, lh + 10 * u, p);
            }
        }

        // playhead with a handle
        long ph = Math.max(0, a.playhead());
        float px = Math.min(w, (float) (ph / fpp));
        fill.setColor(a.eng.isRecording() ? Ui.RED : Ui.CREAM);
        c.drawRect(px - 0.75f * u, 0, px + 0.75f * u, H, fill);
        tri.reset();
        tri.moveTo(px - 5 * u, H);
        tri.lineTo(px + 5 * u, H);
        tri.lineTo(px, H - 6 * u);
        tri.close();
        c.drawPath(tri, fill);
    }

    private double bar() { return a.s.barFrames(Engine.SR); }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        return gd.onTouchEvent(e);
    }
}
