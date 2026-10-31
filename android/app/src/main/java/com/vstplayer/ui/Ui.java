package com.vstplayer.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Colours, spacing and small factory helpers for the whole UI. */
public final class Ui {
    public static final int BG = 0xFF000000;
    public static final int CARD = 0xFF1C1C1E;
    public static final int CARD2 = 0xFF2C2C2E;
    public static final int TXT = 0xFFFFFFFF;
    public static final int SUB = 0xFF8E8E93;
    public static final int SUB2 = 0xFF636366;
    public static final int ACC = 0xFFFA2D48;
    public static final int LINE = 0xFF38383A;
    public static final int OK = 0xFF30D158;

    private Ui() { }

    public static int dp(Context c, float v) {
        return (int) (TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics()) + 0.5f);
    }

    public static GradientDrawable bg(int color, float radiusDp, Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    public static GradientDrawable bg(int color, float radiusDp, int stroke, float strokeDp, Context c) {
        GradientDrawable d = bg(color, radiusDp, c);
        d.setStroke(dp(c, strokeDp), stroke);
        return d;
    }

    public static GradientDrawable circle(int color, Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    public static TextView tv(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setIncludeFontPadding(false);
        return t;
    }

    public static LinearLayout col(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(android.view.Gravity.CENTER_VERTICAL);
        return l;
    }

    public static View space(Context c, int w, int h) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(w, h));
        return v;
    }

    public static int withAlpha(int color, float a) {
        return Color.argb((int) (255 * a), Color.red(color), Color.green(color), Color.blue(color));
    }
}
