package com.vstplayer.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;

import com.vstplayer.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Artwork view that grows from the sharp square on the home screen into the full bleed backdrop
 * of the detail page.
 *
 * progress 0 draws the sharp artwork at {@link #setFrom(Rect)}; progress 1 draws a heavily
 * Gaussian blurred copy covering the whole view. Every level is pre blurred and then scaled up,
 * so the result stays smooth instead of showing pixels, and the border of the moving rectangle is
 * feathered by about one blur radius so it melts into the backdrop.
 */
public class CoverMorph extends View {

    /** Standard deviation of each level in screen pixels. 0 keeps the sharp artwork. */
    private static final float[] SIGMA = {0f, 4f, 10f, 24f, 56f, 130f};
    private static final int SHARP_MAX = 1024;
    /** Backdrop brightness, shared by every blurred state. */
    private static final float DIM = 0.55f;

    private final List<Bitmap> levels = new ArrayList<>();
    private final List<Float> sigmas = new ArrayList<>();
    /** Downscaled copy this view created and therefore owns; the source bitmap itself is only borrowed. */
    private Bitmap sharpOwned;
    private final RectF from = new RectF();
    private final RectF dst = new RectF();
    private final Rect src = new Rect();
    private final Paint bmp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint mask = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ColorMatrix cm = new ColorMatrix();
    private final ColorMatrixColorFilter dimFilter = new ColorMatrixColorFilter(cm);

    private float progress = 1f;
    private float backdrop = 1f;

    public CoverMorph(Context c) {
        super(c);
        mask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
    }

    /** Decodes embedded artwork, downsampled so it is at most {@code max} pixels wide. */
    public static Bitmap decode(byte[] data, int max) {
        if (data == null) return null;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            int s = 1;
            while (o.outWidth / s > max) s *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = s;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Stand in artwork for files without an embedded cover: a dark gradient keyed by the file path
     * plus the music note. Home, transition and backdrop all use this one bitmap so they match.
     */
    public static Bitmap placeholder(Context c, String key, int px) {
        Bitmap b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        int hash = key == null ? 0 : key.hashCode();
        int top = Color.rgb(56 + Math.abs(hash % 22), 14, 20 + Math.abs((hash >> 8) % 16));
        int bottom = Color.rgb(12, 11, 14);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, px, px, top, bottom, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, px, px, p);
        try {
            Drawable d = c.getResources().getDrawable(R.drawable.ic_music);
            d = d.mutate();
            d.setTint(0x8CFFFFFF);
            int s = px / 4;
            d.setBounds((px - s) / 2, (px - s) / 2, (px + s) / 2, (px + s) / 2);
            d.draw(cv);
        } catch (Throwable ignored) {
        }
        return b;
    }

    /** A fully pre-blurred ladder, built off the UI thread by {@link #prepare}. */
    public static final class Prepared {
        private final List<Bitmap> levels = new ArrayList<>();
        private final List<Float> sigmas = new ArrayList<>();
        private Bitmap scaled;
    }

    /**
     * Builds the blur ladder for {@code art}. Pure CPU/bitmap work that touches no view state, so it
     * is safe to call from a background thread; hand the result to {@link #setPrepared} on the UI
     * thread. {@code ref} is the reference width in pixels (the view width, or the screen width).
     */
    public static Prepared prepare(Bitmap art, int ref) {
        Prepared p = new Prepared();
        if (art == null || art.isRecycled()) return p;
        Bitmap sharp = art;
        if (sharp.getWidth() > SHARP_MAX) {
            sharp = Bitmap.createScaledBitmap(art, SHARP_MAX,
                    Math.max(2, art.getHeight() * SHARP_MAX / Math.max(1, art.getWidth())), true);
            p.scaled = sharp;
        }
        int r = ref > 0 ? ref : 1080;
        p.levels.add(sharp);
        p.sigmas.add(0f);
        for (int k = 1; k < SIGMA.length; k++) addBlurLevel(p, sharp, r, SIGMA[k]);
        return p;
    }

    /** Installs a ladder built by {@link #prepare}; the view takes ownership of the bitmaps it made. */
    public void setPrepared(Prepared p) {
        release();
        if (p == null || p.levels.isEmpty()) { invalidate(); return; }
        levels.addAll(p.levels);
        sigmas.addAll(p.sigmas);
        sharpOwned = p.scaled;
        // Ownership moved into this view: keep the source from releasing them twice.
        p.levels.clear();
        p.sigmas.clear();
        p.scaled = null;
        invalidate();
    }

    /** Convenience for callers that have their final artwork already: builds and installs in one go. */
    public void setArt(Bitmap art) {
        int ref = getWidth() > 0 ? getWidth() : getResources().getDisplayMetrics().widthPixels;
        setPrepared(prepare(art, ref));
    }

    private static void addBlurLevel(Prepared p, Bitmap sharp, int ref, float sigmaScreen) {
        int w = sharp.getWidth(), h = sharp.getHeight();
        // keep the kernel cheap but never so small that bilinear upscaling shows its grid
        int targetW = Math.round(ref * 6f / sigmaScreen);
        targetW = Math.max(128, Math.min(384, Math.min(targetW, w)));
        int targetH = Math.max(2, Math.round(targetW * h / (float) Math.max(1, w)));
        Bitmap small = Bitmap.createScaledBitmap(sharp, targetW, targetH, true);
        int[] px = new int[targetW * targetH];
        small.getPixels(px, 0, targetW, 0, 0, targetW, targetH);
        if (small != sharp) small.recycle();
        float sigmaBmp = Math.max(0.8f, sigmaScreen * targetW / (float) Math.max(1, ref));
        gauss(px, targetW, targetH, sigmaBmp);
        Bitmap out = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888);
        out.setPixels(px, 0, targetW, 0, 0, targetW, targetH);
        p.levels.add(out);
        p.sigmas.add(sigmaBmp * ref / (float) targetW);
    }

    private void release() {
        for (int i = 0; i < levels.size(); i++) {
            Bitmap b = levels.get(i);
            if (b == null || b.isRecycled()) continue;
            // Index 0 is the caller's artwork unless it is the scaled copy we created ourselves.
            if (i == 0 && b != sharpOwned) continue;
            b.recycle();
        }
        levels.clear();
        sigmas.clear();
        sharpOwned = null;
    }

    /** Rectangle the sharp artwork starts from, in this view's coordinates. */
    public void setFrom(Rect r) {
        if (r == null) return;
        from.set(r);
        invalidate();
    }

    public void setProgress(float p) {
        progress = Math.max(0f, Math.min(1f, p));
        invalidate();
    }

    /** Alpha of the fully blurred backdrop behind the growing artwork. */
    public void setBackdrop(float a) {
        backdrop = Math.max(0f, Math.min(1f, a));
        invalidate();
    }

    @Override protected void onDraw(Canvas cv) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0 || levels.isEmpty()) return;

        // one brightness for backdrop and artwork, so the moving rectangle never shows a tone step
        float dimNow = 1f - (1f - DIM) * progress;
        if (backdrop > 0.004f) {
            dst.set(0, 0, w, h);
            drawCover(cv, levels.get(levels.size() - 1), dst, dimNow, backdrop);
        }
        if (progress >= 0.999f) return;

        float f = progress * (levels.size() - 1);
        int i0 = Math.min((int) f, levels.size() - 1);
        int i1 = Math.min(i0 + 1, levels.size() - 1);
        float frac = f - i0;
        dst.set(from.left + (0 - from.left) * progress, from.top + (0 - from.top) * progress,
                from.right + (w - from.right) * progress, from.bottom + (h - from.bottom) * progress);

        float sigma = sigmas.get(i0) + (sigmas.get(i1) - sigmas.get(i0)) * frac;
        float feather = Math.min(Math.min(dst.width(), dst.height()) * 0.22f, 2.2f * sigma);

        int save = cv.saveLayer(dst, null);
        drawCover(cv, levels.get(i0), dst, dimNow, 1f);
        if (frac > 0.02f) drawCover(cv, levels.get(i1), dst, dimNow, frac);
        if (feather > 1.2f) feather(cv, dst, feather, w, h);
        cv.restoreToCount(save);
    }

    /** Draws {@code b} center cropped into {@code d}. */
    private void drawCover(Canvas cv, Bitmap b, RectF d, float dim, float alpha) {
        if (b == null || b.isRecycled() || d.width() <= 0.5f || d.height() <= 0.5f) return;
        float s = Math.max(d.width() / b.getWidth(), d.height() / b.getHeight());
        float sw = Math.min(b.getWidth(), d.width() / s);
        float sh = Math.min(b.getHeight(), d.height() / s);
        int left = Math.round((b.getWidth() - sw) * 0.5f);
        int top = Math.round((b.getHeight() - sh) * 0.5f);
        src.set(left, top, left + Math.max(1, Math.round(sw)), top + Math.max(1, Math.round(sh)));

        bmp.setAlpha((int) (255 * alpha));
        if (dim < 0.999f) {
            cm.setScale(dim, dim, dim, 1f);
            bmp.setColorFilter(dimFilter);
        } else {
            bmp.setColorFilter(null);
        }
        cv.drawBitmap(b, src, d, bmp);
        bmp.setColorFilter(null);
        bmp.setAlpha(255);
    }

    /** Softens the border of {@code r} over {@code f} pixels so it blends into the backdrop. */
    private void feather(Canvas cv, RectF r, float f, int w, int h) {
        mask.setShader(new LinearGradient(r.left, 0, r.left + f, 0, 0x00000000, 0xFF000000, Shader.TileMode.CLAMP));
        cv.drawRect(r.left - 1, 0, r.left + f, h, mask);
        mask.setShader(new LinearGradient(r.right - f, 0, r.right, 0, 0xFF000000, 0x00000000, Shader.TileMode.CLAMP));
        cv.drawRect(r.right - f, 0, r.right + 1, h, mask);
        mask.setShader(new LinearGradient(0, r.top, 0, r.top + f, 0x00000000, 0xFF000000, Shader.TileMode.CLAMP));
        cv.drawRect(0, r.top - 1, w, r.top + f, mask);
        mask.setShader(new LinearGradient(0, r.bottom - f, 0, r.bottom, 0xFF000000, 0x00000000, Shader.TileMode.CLAMP));
        cv.drawRect(0, r.bottom - f, w, r.bottom + 1, mask);
        mask.setShader(null);
    }

    // ------------------------------------------------------------------ gaussian

    /** Separable Gaussian blur over premultiplied ARGB pixels. */
    private static void gauss(int[] px, int w, int h, float sigma) {
        if (sigma < 0.3f) return;
        int r = Math.max(1, (int) Math.ceil(sigma * 3f));
        float[] k = new float[2 * r + 1];
        float denom = 2f * sigma * sigma, sum = 0f;
        for (int i = -r; i <= r; i++) {
            float v = (float) Math.exp(-(i * i) / denom);
            k[i + r] = v;
            sum += v;
        }
        for (int i = 0; i < k.length; i++) k[i] /= sum;

        int[] tmp = new int[px.length];
        // horizontal
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                float a = 0, rr = 0, gg = 0, bb = 0;
                for (int i = -r; i <= r; i++) {
                    int xx = x + i;
                    if (xx < 0) xx = 0; else if (xx >= w) xx = w - 1;
                    int c = px[row + xx];
                    float kw = k[i + r];
                    a += (c >>> 24) * kw;
                    rr += ((c >> 16) & 0xFF) * kw;
                    gg += ((c >> 8) & 0xFF) * kw;
                    bb += (c & 0xFF) * kw;
                }
                tmp[row + x] = ((int) a << 24) | ((int) rr << 16) | ((int) gg << 8) | (int) bb;
            }
        }
        // vertical
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                float a = 0, rr = 0, gg = 0, bb = 0;
                for (int i = -r; i <= r; i++) {
                    int yy = y + i;
                    if (yy < 0) yy = 0; else if (yy >= h) yy = h - 1;
                    int c = tmp[yy * w + x];
                    float kw = k[i + r];
                    a += (c >>> 24) * kw;
                    rr += ((c >> 16) & 0xFF) * kw;
                    gg += ((c >> 8) & 0xFF) * kw;
                    bb += (c & 0xFF) * kw;
                }
                px[y * w + x] = ((int) a << 24) | ((int) rr << 16) | ((int) gg << 8) | (int) bb;
            }
        }
    }
}
