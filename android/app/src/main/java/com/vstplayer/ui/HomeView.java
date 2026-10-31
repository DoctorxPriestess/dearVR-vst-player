package com.vstplayer.ui;

import android.content.Context;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.vstplayer.core.Player;

import java.io.File;
import java.util.Locale;

/** Tab 1: the now-playing screen. */
public class HomeView extends LinearLayout implements Player.Listener {

    private final TextView h;
    private final TextView title;
    private final TextView sub;
    private final TextView pos;
    private final TextView dur;
    private final TextView hint;
    private final Icon playIcon;
    private final LinearLayout art;
    private final ImageView cover;
    private final Icon note;
    private final Slider seek;
    private boolean seeking;
    private File coverFor;
    private CoverLoader.Request coverReq;

    public HomeView(Context c) {
        super(c);
        setOrientation(VERTICAL);
        setBackgroundColor(Ui.BG);
        int pad = Ui.dp(c, 20);

        LinearLayout head = Ui.row(c);
        h = Ui.tv(c, "现在播放", 12, Ui.SUB, true);
        head.addView(h, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        head.setPadding(pad, Ui.dp(c, 18), pad, Ui.dp(c, 10));
        addView(head);

        // artwork
        art = new LinearLayout(c);
        art.setOrientation(VERTICAL);
        art.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF3A0E16, 0xFF160B12, 0xFF0A0A0C});
        g.setCornerRadius(Ui.dp(c, 18));
        art.setBackground(g);
        // the real artwork sits under the placeholder note; it is the origin of the detail page transition
        cover = new ImageView(c);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setClipToOutline(true);
        cover.setVisibility(View.GONE);
        final float radius = Ui.dp(c, 18);
        cover.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        art.addView(cover, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        note = new Icon(c).name("music").tint(Ui.withAlpha(0xFFFFFFFF, 0.5f));
        art.addView(note, new LinearLayout.LayoutParams(Ui.dp(c, 72), Ui.dp(c, 72)));
        final FrameLayout holder = new FrameLayout(c);
        holder.setPadding(pad, 0, pad, 0);
        FrameLayout.LayoutParams alp = new FrameLayout.LayoutParams(0, 0);
        alp.gravity = Gravity.CENTER;
        holder.addView(art, alp);
        holder.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int w = v.getWidth() - v.getPaddingLeft() - v.getPaddingRight();
            int hh = v.getHeight() - v.getPaddingTop() - v.getPaddingBottom();
            int s = Math.max(0, Math.min(w, hh));
            if (s > 0 && (art.getWidth() != s || art.getHeight() != s)) {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) art.getLayoutParams();
                lp.width = s;
                lp.height = s;
                art.setLayoutParams(lp);
            }
        });
        addView(holder, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout info = Ui.col(c);
        info.setPadding(pad, Ui.dp(c, 18), pad, 0);
        title = Ui.tv(c, "未选择音频", 21, Ui.TXT, true);
        title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        sub = Ui.tv(c, "从「资料库」选择一首", 13, Ui.SUB, false);
        info.addView(title);
        info.addView(Ui.space(c, 0, Ui.dp(c, 4)));
        info.addView(sub);
        addView(info);

        seek = new Slider(c).accent(Ui.TXT).trackColor(Ui.withAlpha(Ui.TXT, 0.22f)).thick(4f, 0f).knob(true);
        seek.onChange(new Slider.OnChange() {
            @Override public void onChanging(float v) {
                seeking = true;
                pos.setText(fmt((int) (durationMs * v)));
            }
            @Override public void onChanged(float v) {
                seeking = false;
                Player.get().seek(v);
            }
        });
        LinearLayout seekWrap = Ui.col(c);
        seekWrap.setPadding(pad, Ui.dp(c, 6), pad, 0);
        seekWrap.addView(seek);
        LinearLayout times = Ui.row(c);
        pos = Ui.tv(c, "0:00", 11, Ui.SUB, false);
        dur = Ui.tv(c, "0:00", 11, Ui.SUB, false);
        times.addView(pos, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        times.addView(dur);
        seekWrap.addView(times);
        addView(seekWrap);

        LinearLayout ctl = Ui.row(c);
        ctl.setGravity(Gravity.CENTER);
        Icon prev = new Icon(c).name("prev").tint(Ui.TXT);
        FrameLayout playBtn = new FrameLayout(c);
        playBtn.setBackground(Ui.circle(Ui.withAlpha(Ui.TXT, 0.12f), c));
        playIcon = new Icon(c).name("play").tint(Ui.TXT);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(Ui.dp(c, 34), Ui.dp(c, 34));
        ilp.gravity = Gravity.CENTER;
        playBtn.addView(playIcon, ilp);
        Icon next = new Icon(c).name("next").tint(Ui.TXT);
        ctl.addView(prev, new LinearLayout.LayoutParams(Ui.dp(c, 56), Ui.dp(c, 56)));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(Ui.dp(c, 76), Ui.dp(c, 76));
        plp.leftMargin = Ui.dp(c, 18);
        plp.rightMargin = Ui.dp(c, 18);
        ctl.addView(playBtn, plp);
        ctl.addView(next, new LinearLayout.LayoutParams(Ui.dp(c, 56), Ui.dp(c, 56)));
        LinearLayout ctlWrap = Ui.col(c);
        ctlWrap.setPadding(pad, Ui.dp(c, 4), pad, Ui.dp(c, 2));
        ctlWrap.addView(ctl);
        addView(ctlWrap);

        hint = Ui.tv(c, "", 11, Ui.SUB2, false);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(pad, 0, pad, Ui.dp(c, 14));
        addView(hint);

        prev.setOnClickListener(v -> step(-1));
        next.setOnClickListener(v -> step(1));
        playBtn.setOnClickListener(v -> Player.get().toggle());

        art.setOnClickListener(v -> { if (openDetail != null) openDetail.run(); });
    }

    private int durationMs = 1;

    private void step(int d) {
        File t = Player.get().track();
        if (t == null) return;
        if (d < 0) { Player.get().seek(0f); return; }
        File[] files = t.getParentFile() == null ? null : t.getParentFile().listFiles();
        if (files == null || files.length < 2) return;
        java.util.Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (int i = 0; i < files.length; i++) {
            if (files[i].equals(t)) {
                Player.get().play(files[(i + 1) % files.length]);
                return;
            }
        }
    }

    private void loadCover(File f) {
        if (coverFor != null && coverFor.equals(f)) return;
        coverFor = f;
        if (coverReq != null) coverReq.cancel();
        final File wanted = f;
        // Thumbnail size: the artwork is shown at roughly screen width, so 512 is plenty and 4x
        // cheaper to decode than the detail page backdrop.
        coverReq = CoverLoader.cover(getContext(), f, 512, (file, b) -> {
            // Only the current file may win: kept on the detach-safe main Handler, so this also
            // works while the tab is off screen.
            if (!wanted.equals(coverFor)) return;
            if (b == null) return;
            cover.setImageBitmap(b);
            cover.setVisibility(View.VISIBLE);
            note.setVisibility(View.GONE);
        });
    }

    /** Artwork rectangle in window coordinates, false when the cover is not laid out yet. */
    public boolean coverRectInWindow(Rect out) {
        if (art.getWidth() <= 0 || art.getHeight() <= 0) return false;
        int[] xy = new int[2];
        art.getLocationInWindow(xy);
        out.set(xy[0], xy[1], xy[0] + art.getWidth(), xy[1] + art.getHeight());
        return true;
    }

    /** Hides the artwork while a morphing copy of it is painted by the detail page. */
    public void setCoverVisible(boolean visible) {
        cover.setVisibility(visible && cover.getDrawable() != null ? View.VISIBLE : View.GONE);
        note.setVisibility(visible || cover.getDrawable() != null ? View.GONE : View.VISIBLE);
    }

    private Runnable openDetail;

    public void setOnOpenDetail(Runnable r) { openDetail = r; }

    public void setHeader(String s) { h.setText(s); }

    @Override public void onQueue() { }

    @Override public void onMode(int mode) { }

    @Override public void onTrack(File f, int index, int total) {
        loadCover(f);
        title.setText(f.getName().replaceAll("\\.[^.]+$", ""));
        sub.setText(f.getParentFile() != null && "out".equals(f.getParentFile().getName())
                ? "渲染结果" : "源音频");
        hint.setText(f.getName());
    }

    @Override public void onState(boolean playing) {
        playIcon.name(playing ? "pause" : "play");
        playIcon.invalidate();
    }

    @Override public void onProgress(float fraction, int posMs, int durMs) {
        durationMs = Math.max(1, durMs);
        dur.setText(fmt(durMs));
        if (!seeking) {
            seek.setValue(fraction);
            pos.setText(fmt(posMs));
        }
    }

    static String fmt(int ms) {
        int s = Math.max(0, ms / 1000);
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

}
