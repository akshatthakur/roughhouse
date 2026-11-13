package com.roughhouse.app;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
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

    Ruler(MainActivity a) {
        super(a);
        this.a = a;
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
        float mid = H * 0.62f, amp = H * 0.3f;
        for (int x = 0; x < w; x++) {
            int i0 = (int) (x * fpp / Track.PB), i1 = Math.max(i0 + 1, (int) ((x + 1) * fpp / Track.PB));
            float m = 0;
            for (Track t : s.tracks) {
                float[] pk = t.peaks;
                for (int i = i0; i < Math.min(i1, pk.length); i++) if (pk[i] > m) m = pk[i];
            }
            if (m > 0) c.drawRect(x, mid - m * amp, x + 1, mid + m * amp, fill);
        }

        // bar ticks and numbers
        double bar = s.beatFrames(Engine.SR) * Math.max(1, s.beatsPerBar);
        int every = 1;
        while (bar * every / fpp < 28 * u) every *= 2;
        p.setTypeface(Ui.sansBold);
        p.setTextSize(8.5f * u);
        p.setTextAlign(Paint.Align.LEFT);
        for (long b = 0; b * bar < vf; b++) {
            float x = (float) (b * bar / fpp);
            boolean label = b % every == 0;
            fill.setColor(label ? 0xFF5E5A51 : 0xFF2B2824);
            c.drawRect(x, 0, x + 1, label ? 9 * u : 5 * u, fill);
            if (label) {
                p.setColor(Ui.DIM);
                c.drawText(String.valueOf(b + 1), x + 3 * u, 10 * u, p);
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

    @Override public boolean onTouchEvent(MotionEvent e) {
        int act = e.getAction();
        if (act == MotionEvent.ACTION_DOWN || act == MotionEvent.ACTION_MOVE || act == MotionEvent.ACTION_UP) {
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
            float x = Math.max(0, Math.min(contentW(), e.getX()));
            a.seekTo((long) (x / contentW() * a.viewFrames()));
            invalidate();
        }
        return true;
    }
}
