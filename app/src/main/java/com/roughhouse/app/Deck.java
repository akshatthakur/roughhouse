package com.roughhouse.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Custom-drawn deck hardware: cassette window, tape counter, VU meter, tuner and transport keys. */
final class Deck {
    private Deck() {}

    // ------------------------------------------------------------- cassette

    /** The cassette in its window: paper label with the jam name, reels that turn with the tape. */
    static final class Cassette extends View {
        String title = "", sub = "";
        float angle;          // reel rotation (radians)
        float frac;           // 0..1 how far through the tape we are
        boolean recording;

        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final Path path = new Path();

        Cassette(Context c) { super(c); }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), u = Ui.dp(1);
            // smoked window
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF0F0D0C);
            r.set(0, 0, w, h);
            c.drawRoundRect(r, 8 * u, 8 * u, p);
            // shell
            float sx = 10 * u, sy = 8 * u, sw = w - 20 * u, sh = h - 16 * u;
            p.setColor(Ui.SHELL);
            r.set(sx, sy, sx + sw, sy + sh);
            c.drawRoundRect(r, 6 * u, 6 * u, p);
            // screws
            p.setColor(0xFF9E947C);
            float sc = 5 * u;
            c.drawCircle(sx + sc + 2 * u, sy + sc + 2 * u, 2.2f * u, p);
            c.drawCircle(sx + sw - sc - 2 * u, sy + sc + 2 * u, 2.2f * u, p);
            c.drawCircle(sx + sc + 2 * u, sy + sh - sc - 2 * u, 2.2f * u, p);
            c.drawCircle(sx + sw - sc - 2 * u, sy + sh - sc - 2 * u, 2.2f * u, p);
            // paper label
            float lx = sx + 14 * u, ly = sy + 8 * u, lw = sw - 28 * u, lh = sh * 0.72f;
            p.setColor(Ui.PAPER);
            r.set(lx, ly, lx + lw, ly + lh);
            c.drawRoundRect(r, 3 * u, 3 * u, p);
            // title (Times-style italic) on its own line at the top of the label
            p.setColor(Ui.INK);
            p.setTypeface(Ui.serifItalic);
            p.setTextSize(16 * u);
            p.setTextAlign(Paint.Align.LEFT);
            String t = ellipsize(title, p, lw - 16 * u);
            float titleBase = ly + 20 * u;
            c.drawText(t, lx + 8 * u, titleBase, p);
            // label stripes, clear of the title's descenders
            float stripe = titleBase + 7 * u;
            p.setColor(recording ? Ui.RED : 0xFFD8452E);
            c.drawRect(lx, stripe, lx + lw, stripe + 3 * u, p);
            p.setColor(Ui.AMBER);
            c.drawRect(lx, stripe + 3 * u, lx + lw, stripe + 5 * u, p);
            // sub line (Helvetica-style) along the bottom edge of the label
            p.setTypeface(Ui.sansBold);
            p.setTextSize(8.5f * u);
            p.setLetterSpacing(0.12f);
            p.setColor(0xFF6B6255);
            float subBase = ly + lh - 6 * u;
            c.drawText(ellipsize(sub, p, lw - 16 * u), lx + 8 * u, subBase, p);
            p.setLetterSpacing(0f);

            // reel window between the stripes and the sub line, with a gap on both sides
            float wy = stripe + 5 * u + 6 * u, wh = Math.max(10 * u, (subBase - 8.5f * u - 6 * u) - wy);
            float wx = lx + lw * 0.17f, ww = lw * 0.66f;
            p.setColor(0xFF15110F);
            r.set(wx, wy, wx + ww, wy + wh);
            c.drawRoundRect(r, wh / 2, wh / 2, p);
            float cy = wy + wh / 2, rx1 = wx + wh / 2 + 4 * u, rx2 = wx + ww - wh / 2 - 4 * u;
            float maxR = wh / 2 - 2 * u, minR = maxR * 0.42f;
            // tape packs (left empties as the tape plays)
            p.setColor(Ui.TAPE);
            c.drawCircle(rx1, cy, minR + (maxR - minR) * (1 - frac), p);
            c.drawCircle(rx2, cy, minR + (maxR - minR) * frac, p);
            reel(c, rx1, cy, minR * 0.85f, angle);
            reel(c, rx2, cy, minR * 0.85f, angle);
        }

        private void reel(Canvas c, float x, float y, float rad, float a) {
            p.setColor(Ui.CREAM);
            c.drawCircle(x, y, rad, p);
            p.setColor(0xFF15110F);
            c.drawCircle(x, y, rad * 0.45f, p);
            p.setColor(Ui.CREAM);
            for (int i = 0; i < 6; i++) {
                double th = a + i * Math.PI / 3;
                float tx = x + (float) Math.cos(th) * rad * 0.42f, ty = y + (float) Math.sin(th) * rad * 0.42f;
                c.drawCircle(tx, ty, rad * 0.11f, p);
            }
        }

        private static String ellipsize(String s, Paint p, float max) {
            if (p.measureText(s) <= max) return s;
            while (s.length() > 1 && p.measureText(s + "…") > max) s = s.substring(0, s.length() - 1);
            return s + "…";
        }
    }

    // --------------------------------------------------------- tape counter

    /** Mechanical-style counter: one box per digit. Shows mm:ss.t or bar.beat. */
    static final class Counter extends View {
        String text = "00:00.0";
        String caption = "TIME";
        boolean red;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        Counter(Context c) { super(c); }

        @Override protected void onDraw(Canvas c) {
            float u = Ui.dp(1), h = getHeight();
            float boxH = h - 14 * u, boxW = boxH * 0.62f, x = 0;
            p.setTypeface(Ui.sansBold);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(boxH * 0.68f);
            for (int i = 0; i < text.length(); i++) {
                char ch = text.charAt(i);
                if (ch == ':' || ch == '.') {
                    p.setColor(Ui.DIM);
                    c.drawText(String.valueOf(ch), x + 4 * u, boxH * 0.72f, p);
                    x += 8 * u;
                    continue;
                }
                p.setColor(0xFF0B0A09);
                r.set(x, 0, x + boxW, boxH);
                c.drawRoundRect(r, 2 * u, 2 * u, p);
                p.setColor(0xFF2B2824);
                c.drawRect(x, boxH / 2 - 0.5f * u, x + boxW, boxH / 2 + 0.5f * u, p);
                p.setColor(red ? Ui.RED : 0xFFF4EEDF);
                c.drawText(String.valueOf(ch), x + boxW / 2, boxH * 0.74f, p);
                x += boxW + 2 * u;
            }
            p.setTextAlign(Paint.Align.LEFT);
            p.setTextSize(8.5f * u);
            p.setLetterSpacing(0.15f);
            p.setColor(Ui.DIM);
            c.drawText(caption, 0, h - 2 * u, p);
            p.setLetterSpacing(0f);
        }
    }

    // ------------------------------------------------------------- VU meter

    /** Backlit needle meter. set() takes a peak level; the needle has VU-like ballistics. */
    static final class Vu extends View {
        final String label;
        private float needle; // 0..1 across the scale
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        Vu(Context c, String label) {
            super(c);
            this.label = label;
        }

        /** Returns true while the needle is still moving. */
        boolean step(float peak) {
            float db = peak > 1e-5f ? (float) (20 * Math.log10(peak)) : -60;
            float target = Math.max(0, Math.min(1, (db + 20) / 23)); // -20 .. +3 dB
            float k = target > needle ? 0.45f : 0.12f;
            float prev = needle;
            needle += (target - needle) * k;
            if (Math.abs(needle - prev) > 0.002f) { invalidate(); return true; }
            return false;
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), u = Ui.dp(1);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF0F0D0C);
            r.set(0, 0, w, h);
            c.drawRoundRect(r, 4 * u, 4 * u, p);
            p.setColor(0xFFF0DFAE); // warm backlit face
            r.set(3 * u, 3 * u, w - 3 * u, h - 3 * u);
            c.drawRoundRect(r, 3 * u, 3 * u, p);
            float cx = w / 2, cy = h * 1.05f, rad = h * 0.78f;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.2f * u);
            double a0 = Math.toRadians(-140), a1 = Math.toRadians(-40), aRed = a0 + (a1 - a0) * (20f / 23f);
            for (int i = 0; i <= 23; i++) {
                double a = a0 + (a1 - a0) * i / 23.0;
                boolean major = i % 5 == 0 || i == 20 || i == 23;
                float r1 = rad - (major ? 7 : 4) * u;
                p.setColor(a >= aRed ? Ui.RED : Ui.INK);
                c.drawLine(cx + (float) Math.cos(a) * r1, cy + (float) Math.sin(a) * r1,
                        cx + (float) Math.cos(a) * rad, cy + (float) Math.sin(a) * rad, p);
            }
            p.setStyle(Paint.Style.FILL);
            p.setTypeface(Ui.serifItalic);
            p.setTextSize(11 * u);
            p.setTextAlign(Paint.Align.CENTER);
            p.setColor(Ui.INK);
            c.drawText("VU", cx, h * 0.62f, p);
            p.setTypeface(Ui.sansBold);
            p.setTextSize(8 * u);
            c.drawText(label, w - 10 * u, h - 7 * u, p);
            double a = a0 + (a1 - a0) * needle;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.4f * u);
            p.setColor(0xFF1A1715);
            c.drawLine(cx, cy, cx + (float) Math.cos(a) * (rad + 2 * u), cy + (float) Math.sin(a) * (rad + 2 * u), p);
        }
    }

    // ---------------------------------------------------------------- tuner

    /** Live pitch: note name, cents needle (-50..+50) with an in-tune zone, frequency. */
    static final class Tuner extends View {
        float hz = -1;
        private float cents, shown;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        Tuner(Context c) { super(c); }

        void set(float f) {
            hz = f;
            if (f > 0) {
                float m = Pitch.midi(f);
                cents = (m - Math.round(m)) * 100;
                shown += (cents - shown) * 0.5f;
            }
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), u = Ui.dp(1);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF0F0D0C);
            r.set(0, 0, w, h);
            c.drawRoundRect(r, 4 * u, 4 * u, p);
            boolean voiced = hz > 0;
            boolean inTune = voiced && Math.abs(cents) <= 10;
            // note name
            p.setTypeface(Ui.serifBold);
            p.setTextSize(h * 0.48f);
            p.setTextAlign(Paint.Align.LEFT);
            p.setColor(!voiced ? Ui.FAINT : inTune ? Ui.GREEN : Ui.AMBER);
            String name = voiced ? Pitch.noteName(Math.round(Pitch.midi(hz))) : "–";
            c.drawText(name, 10 * u, h * 0.66f, p);
            // cents scale
            float sx = w * 0.42f, ex = w - 12 * u, my = h * 0.42f;
            p.setColor(0xFF2F2B26);
            c.drawRect(sx, my - 1 * u, ex, my + 1 * u, p);
            float zone = (ex - sx) * 0.1f;
            float mid = (sx + ex) / 2;
            p.setColor(0x447FA65A);
            c.drawRect(mid - zone, my - 7 * u, mid + zone, my + 7 * u, p);
            p.setColor(Ui.DIM);
            for (int i = -5; i <= 5; i++) {
                float x = mid + (ex - sx) / 2 * i / 5f;
                float th = i == 0 ? 8 : 4;
                c.drawRect(x - 0.5f * u, my - th * u, x + 0.5f * u, my + th * u, p);
            }
            if (voiced) {
                float x = mid + (ex - sx) / 2 * Math.max(-1, Math.min(1, shown / 50f));
                p.setColor(inTune ? Ui.GREEN : Ui.AMBER);
                c.drawRect(x - 1.5f * u, my - 11 * u, x + 1.5f * u, my + 11 * u, p);
            }
            p.setTypeface(Ui.sansBold);
            p.setTextSize(9 * u);
            p.setLetterSpacing(0.12f);
            p.setColor(Ui.DIM);
            String info = voiced ? String.format(java.util.Locale.US, "%+.0f CENTS  ·  %.1f HZ", cents, hz) : "SING OR PLAY A NOTE";
            c.drawText(info, sx, h - 9 * u, p);
            p.setLetterSpacing(0f);
        }
    }

    // ---------------------------------------------------------- transport key

    /** Piano-style transport key with an icon and an optional lit LED. */
    static final class Key extends View {
        static final int REW = 0, REC = 1, PLAY = 2, STOP = 3;
        final int type;
        boolean down, lit;
        float level;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final Path path = new Path();

        Key(Context c, int type) {
            super(c);
            this.type = type;
            setClickable(true);
        }

        void set(boolean down, boolean lit) {
            if (this.down != down || this.lit != lit) {
                this.down = down;
                this.lit = lit;
                invalidate();
            }
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), u = Ui.dp(1);
            boolean pressed = down || isPressed();
            float drop = pressed ? 2 * u : 0;
            // key shadow + face
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF0B0A09);
            r.set(0, 4 * u, w, h);
            c.drawRoundRect(r, 4 * u, 4 * u, p);
            p.setColor(pressed ? Ui.KEY_DOWN : Ui.KEY);
            r.set(0, drop, w, h - 4 * u + drop);
            c.drawRoundRect(r, 4 * u, 4 * u, p);
            p.setColor(0x22000000);
            c.drawRect(2 * u, h - 10 * u + drop, w - 2 * u, h - 5 * u + drop, p);
            // LED, tucked into the top-right corner
            float lx = w - 9 * u, ly = 9 * u + drop;
            p.setColor(lit ? (type == REC ? Ui.RED : Ui.GREEN) : 0xFF6E685C);
            c.drawCircle(lx, ly, 2.6f * u, p);
            if (lit && type == REC && level > 0) {
                p.setColor(0x55D8452E);
                c.drawCircle(lx, ly, (2.6f + 4 * Math.min(1, level * 2)) * u, p);
            }
            // icon sits in the space above the label, with a fixed gap between them
            float labelBase = h - 10 * u + drop;
            float labelTop = labelBase - 8.5f * u;
            float s = Math.min(Math.min(w, h) * 0.15f, (labelTop - 6 * u - (3 * u + drop)) / 2.4f);
            float cx = w / 2, cy = 3 * u + drop + (labelTop - 6 * u - 3 * u - drop) / 2 + 2 * u;
            p.setColor(type == REC ? Ui.RED : Ui.INK);
            path.reset();
            switch (type) {
                case REW:
                    c.drawRect(cx - s * 1.1f, cy - s, cx - s * 0.75f, cy + s, p);
                    path.moveTo(cx + s, cy - s);
                    path.lineTo(cx - s * 0.6f, cy);
                    path.lineTo(cx + s, cy + s);
                    path.close();
                    c.drawPath(path, p);
                    break;
                case REC:
                    c.drawCircle(cx, cy, s * 0.95f, p);
                    break;
                case PLAY:
                    path.moveTo(cx - s * 0.7f, cy - s);
                    path.lineTo(cx + s, cy);
                    path.lineTo(cx - s * 0.7f, cy + s);
                    path.close();
                    c.drawPath(path, p);
                    break;
                default:
                    r.set(cx - s * 0.85f, cy - s * 0.85f, cx + s * 0.85f, cy + s * 0.85f);
                    c.drawRect(r, p);
            }
            // silkscreen label
            p.setTypeface(Ui.sansBold);
            p.setTextSize(8.5f * u);
            p.setLetterSpacing(0.15f);
            p.setTextAlign(Paint.Align.CENTER);
            p.setColor(0xFF5A5347);
            String[] names = {"REW", "REC", "PLAY", "STOP"};
            c.drawText(names[type], cx, labelBase, p);
            p.setLetterSpacing(0f);
        }

        @Override protected void drawableStateChanged() {
            super.drawableStateChanged();
            invalidate();
        }
    }

    // ------------------------------------------------------- circle of fifths

    /**
     * Circle of fifths: majors on the outer ring, relative minors inside. The selected key
     * is lit amber; its closest relatives (IV, V and the ii / iii / vi minors) are lit cream.
     */
    static final class Fifths extends View {
        interface OnPick { void pick(int root, boolean minor); }

        static final String[] MAJ = {"C", "G", "D", "A", "E", "B", "F#", "Db", "Ab", "Eb", "Bb", "F"};
        static final String[] MIN = {"Am", "Em", "Bm", "F#m", "C#m", "G#m", "Ebm", "Bbm", "Fm", "Cm", "Gm", "Dm"};

        int pos = 0;          // position on the circle (0 = C at the top)
        boolean minor;
        OnPick onPick;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF oval = new RectF();

        Fifths(Context c) { super(c); }

        /** Root pitch class (0 = C) of the key at a circle position. */
        static int rootAt(int pos, boolean minor) {
            int major = (pos * 7) % 12;
            return minor ? (major + 9) % 12 : major;
        }

        /** Circle position of a key. */
        static int posOf(int root, boolean minor) {
            int major = minor ? (root + 3) % 12 : root;
            return (major * 7) % 12;
        }

        @Override protected void onMeasure(int ws, int hs) {
            int w = MeasureSpec.getSize(ws);
            setMeasuredDimension(w, w);
        }

        private boolean family(int i, boolean ringMinor) {
            int d = Math.floorMod(i - pos, 12);
            return d == 0 || d == 1 || d == 11; // same column or one step either way
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), u = Ui.dp(1);
            float cx = w / 2, cy = w / 2;
            float rOut = w / 2 - 2 * u, rMid = rOut * 0.68f, rIn = rOut * 0.40f;
            for (int i = 0; i < 12; i++) {
                float start = -90 - 15 + i * 30;
                for (int ring = 0; ring < 2; ring++) {
                    boolean ringMinor = ring == 1;
                    float ro = ringMinor ? rMid : rOut, ri = ringMinor ? rIn : rMid;
                    boolean sel = i == pos && ringMinor == minor;
                    boolean fam = family(i, ringMinor);
                    int fill = sel ? Ui.AMBER : fam ? (ringMinor ? 0xFFCFC4AA : Ui.CREAM) : (ringMinor ? 0xFF201E1B : 0xFF2B2926);
                    path.reset();
                    oval.set(cx - ro, cy - ro, cx + ro, cy + ro);
                    path.arcTo(oval, start, 30);
                    oval.set(cx - ri, cy - ri, cx + ri, cy + ri);
                    path.arcTo(oval, start + 30, -30);
                    path.close();
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(fill);
                    c.drawPath(path, p);
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(1.2f * u);
                    p.setColor(Ui.BODY);
                    c.drawPath(path, p);
                    // label
                    double a = Math.toRadians(-90 + i * 30);
                    float rr = (ro + ri) / 2;
                    p.setStyle(Paint.Style.FILL);
                    p.setTextAlign(Paint.Align.CENTER);
                    p.setTypeface(ringMinor ? Ui.sans : Ui.sansBold);
                    p.setTextSize((ringMinor ? 11 : 15) * u);
                    p.setColor(sel || fam ? Ui.INK : (ringMinor ? Ui.DIM : Ui.CREAM));
                    c.drawText(ringMinor ? MIN[i] : MAJ[i], cx + (float) Math.cos(a) * rr,
                            cy + (float) Math.sin(a) * rr + p.getTextSize() * 0.35f, p);
                }
            }
            // hub
            p.setColor(0xFF0F0D0C);
            c.drawCircle(cx, cy, rIn - 2 * u, p);
            p.setColor(Ui.RED);
            c.drawCircle(cx, cy, rIn * 0.22f, p);
            p.setTypeface(Ui.serifItalic);
            p.setTextSize(13 * u);
            p.setColor(Ui.CREAM);
            String name = minor ? MIN[pos] : MAJ[pos];
            c.drawText(name.endsWith("m") ? name.substring(0, name.length() - 1) + " minor" : name + " major",
                    cx, cy - rIn * 0.42f, p);
            p.setTypeface(Ui.sansBold);
            p.setTextSize(7.5f * u);
            p.setLetterSpacing(0.15f);
            p.setColor(Ui.DIM);
            c.drawText("TAP A KEY", cx, cy + rIn * 0.55f, p);
            p.setLetterSpacing(0f);
        }

        @Override public boolean onTouchEvent(android.view.MotionEvent e) {
            if (e.getAction() != android.view.MotionEvent.ACTION_UP) return true;
            float w = getWidth(), cx = w / 2, cy = w / 2;
            float dx = e.getX() - cx, dy = e.getY() - cy;
            double r = Math.hypot(dx, dy), rOut = w / 2 - Ui.dp(2), rMid = rOut * 0.68, rIn = rOut * 0.40;
            if (r < rIn || r > rOut) return true;
            double ang = Math.toDegrees(Math.atan2(dx, -dy)); // 0 at top, clockwise
            int i = (int) Math.floorMod(Math.round(ang / 30.0), 12L);
            pos = i;
            minor = r < rMid;
            invalidate();
            if (onPick != null) onPick.pick(rootAt(pos, minor), minor);
            return true;
        }
    }
}
