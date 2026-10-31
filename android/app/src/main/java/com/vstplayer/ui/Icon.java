package com.vstplayer.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.view.View;

import com.vstplayer.R;

import java.util.HashMap;
import java.util.Map;

/**
 * Thin tinted wrapper around the bundled vector drawables
 * (Material Icons for the player controls, Lucide for the interface glyphs).
 */
public class Icon extends View {

    private static final Map<String, Integer> RES = new HashMap<>();

    static {
        RES.put("play", R.drawable.ic_play);
        RES.put("pause", R.drawable.ic_pause);
        RES.put("prev", R.drawable.ic_prev);
        RES.put("next", R.drawable.ic_next);
        RES.put("music", R.drawable.ic_music);
        RES.put("home", R.drawable.ic_home);
        RES.put("library", R.drawable.ic_library);
        RES.put("sliders", R.drawable.ic_sliders);
        RES.put("search", R.drawable.ic_search);
        RES.put("star", R.drawable.ic_star);
        RES.put("starOn", R.drawable.ic_star_on);
        RES.put("plus", R.drawable.ic_plus);
        RES.put("close", R.drawable.ic_close);
        RES.put("check", R.drawable.ic_check);
        RES.put("trash", R.drawable.ic_trash);
        RES.put("folder", R.drawable.ic_folder);
        RES.put("download", R.drawable.ic_download);
        RES.put("back", R.drawable.ic_back);
        RES.put("shuffle", R.drawable.ic_shuffle);
        RES.put("repeat", R.drawable.ic_repeat);
        RES.put("repeatOne", R.drawable.ic_repeat_one);
    }

    private String name = "music";
    private int tint = Ui.TXT;
    private Drawable drawable;

    public Icon(Context c) {
        super(c);
        load();
    }

    public Icon name(String n) {
        name = n;
        drawable = null;
        load();
        invalidate();
        return this;
    }

    public Icon tint(int c) {
        tint = c;
        invalidate();
        return this;
    }

    /** Kept for source compatibility: the vector assets define their own weight. */
    public Icon stroke(float w) {
        return this;
    }

    private void load() {
        Integer id = RES.get(name);
        if (id == null) id = RES.get("music");
        drawable = getContext().getDrawable(id);
    }

    @Override protected void onDraw(Canvas cv) {
        if (drawable == null) return;
        drawable.setColorFilter(new PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN));
        drawable.setBounds(0, 0, getWidth(), getHeight());
        drawable.draw(cv);
    }
}
