package com.vstplayer.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

/**
 * Apple-Music style level indicator: the value is expressed by the filled part of
 * the track plus an opacity drop on the remainder, so no knob can be clipped at
 * either end. A small dot only appears while the finger is down.
 */
public class Slider extends View {
    public interface OnChange {
        void onChanging(float v);
        void onChanged(float v);
    }

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float value;
    private boolean dragging;
    private boolean enabled = true;
    private int accent = Ui.ACC;
    private int track = 0xFF3A3A3C;
    private OnChange cb;
    private float trackDp = 4f;
    private boolean showKnob = true;
    /** Pixel sizes cached from the density: dp conversion ran two to four times per drawn frame. */
    private float trackPx = -1f;
    private float knobPx = -1f;

    public Slider(Context c) {
        super(c);
        setFocusable(true);
    }

    public Slider accent(int color) { accent = color; invalidate(); return this; }

    public Slider trackColor(int color) { track = color; invalidate(); return this; }

    public Slider thick(float track, float unusedKnob) {
        trackDp = track;
        trackPx = -1f;
        invalidate();
        return this;
    }

    private float trackPx() {
        if (trackPx < 0f) trackPx = Ui.dp(getContext(), trackDp);
        return trackPx;
    }

    private float knobPx() {
        if (knobPx < 0f) knobPx = Ui.dp(getContext(), 7);
        return knobPx;
    }

    /** Hide the handle entirely (progress readout) instead of only while dragging. */
    public Slider knob(boolean on) { showKnob = on; invalidate(); return this; }

    public Slider onChange(OnChange c) { cb = c; return this; }

    public void setValue(float v) {
        value = Math.max(0f, Math.min(1f, v));
        invalidate();
    }

    public float value() { return value; }

    @Override public void setEnabled(boolean e) {
        enabled = e;
        setAlpha(e ? 1f : 0.45f);
        super.setEnabled(e);
    }

    @Override protected void onMeasure(int w, int h) {
        super.onMeasure(w, h);
        setMeasuredDimension(getMeasuredWidth(), Ui.dp(getContext(), 30));
    }

    @Override protected void onDraw(Canvas cv) {
        float cy = getHeight() / 2f;
        float half = trackPx() / 2f;
        float x0 = 0f, x1 = getWidth();
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(half * 2);

        p.setColor(track);
        cv.drawLine(x0 + half, cy, x1 - half, cy, p);

        float px = x0 + half + (x1 - x0 - 2 * half) * value;
        if (value > 0.001f) {
            p.setColor(enabled ? accent : 0xFF6B6B6E);
            cv.drawLine(x0 + half, cy, px, cy, p);
        }
        if (showKnob && dragging) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(enabled ? accent : 0xFF6B6B6E);
            cv.drawCircle(px, cy, knobPx(), p);
            p.setStyle(Paint.Style.STROKE);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (!enabled) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = true;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                update(e.getX());
                return true;
            case MotionEvent.ACTION_MOVE:
                update(e.getX());
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                invalidate();
                if (cb != null) cb.onChanged(value);
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    private void update(float x) {
        float half = trackPx() / 2f;
        float w = Math.max(1, getWidth() - 2 * half);
        value = Math.max(0f, Math.min(1f, (x - half) / w));
        invalidate();
        if (cb != null) cb.onChanging(value);
    }
}
