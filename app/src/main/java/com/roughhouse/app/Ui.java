package com.roughhouse.app;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/** Palette, fonts and small styling helpers for the cassette-deck look. */
final class Ui {
    private Ui() {}

    // deck palette
    static final int BODY = 0xFF1B1A18, PANEL = 0xFF262522, PANEL_EDGE = 0xFF3A3833, LANE = 0xFF12100E,
            CREAM = 0xFFE8DFC8, DIM = 0xFF9C9584, FAINT = 0xFF5E5A51,
            AMBER = 0xFFE9A23B, RED = 0xFFD8452E, GREEN = 0xFF7FA65A, BLUE = 0xFF5B8DB8, YELLOW = 0xFFE5C547,
            KEY = 0xFFD9D2C0, KEY_DOWN = 0xFFB5AD98, INK = 0xFF2A2420, PAPER = 0xFFF1E8D0, SHELL = 0xFFCFC4AA,
            TAPE = 0xFF4A3222;

    /** Channel cap colours, like the coloured knobs on a Portastudio. */
    static final int[] CAPS = {RED, AMBER, GREEN, BLUE};

    static Typeface sans, sansBold, serif, serifItalic, serifBold;

    static void loadFonts(Context c) {
        if (sans != null) return;
        sans = c.getResources().getFont(R.font.heros_regular);
        sansBold = c.getResources().getFont(R.font.heros_bold);
        serif = c.getResources().getFont(R.font.termes_regular);
        serifItalic = c.getResources().getFont(R.font.termes_italic);
        serifBold = c.getResources().getFont(R.font.termes_bold);
    }

    static float density;

    static int dp(float v) {
        return Math.round(v * density);
    }

    static TextView text(Context c, float sp, int color, Typeface tf) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(tf);
        return t;
    }

    /** Silkscreen-style label: small bold caps with wide tracking. */
    static TextView silk(Context c, String s, float sp, int color) {
        TextView t = text(c, sp, color, sansBold);
        t.setText(s);
        t.setLetterSpacing(0.14f);
        return t;
    }

    static GradientDrawable rounded(int color, float radius, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(radius);
        g.setColor(color);
        if (stroke != 0) g.setStroke(Math.max(1, dp(1)), stroke);
        return g;
    }

    /** Small rectangular hardware button with an LED-style "on" colour. */
    static TextView chip(Context c, String label) {
        TextView t = silk(c, label, 11, CREAM);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(10), dp(8), dp(10), dp(8));
        setChip(t, false, 0);
        return t;
    }

    static void setChip(TextView t, boolean on, int color) {
        t.setBackground(rounded(on ? color : PANEL, dp(4), on ? color : PANEL_EDGE));
        t.setTextColor(on ? INK : CREAM);
    }

    static View panel(View v) {
        v.setBackground(rounded(PANEL, dp(6), PANEL_EDGE));
        return v;
    }

    /** Applies the deck sans font to every TextView under v (used on dialog contents). */
    static void fontAll(View v) {
        if (v instanceof TextView) {
            TextView t = (TextView) v;
            Typeface cur = t.getTypeface();
            boolean bold = cur != null && cur.isBold();
            if (cur != serif && cur != serifItalic && cur != serifBold && cur != sansBold) {
                t.setTypeface(bold ? sansBold : sans);
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) fontAll(g.getChildAt(i));
        }
    }
}
