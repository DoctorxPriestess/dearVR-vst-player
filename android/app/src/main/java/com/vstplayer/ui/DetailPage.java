package com.vstplayer.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.vstplayer.core.Diag;
import com.vstplayer.core.Lyrics;
import com.vstplayer.core.Player;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Full screen now-playing page: queue (A) swipes to lyrics (B). */
public class DetailPage extends FrameLayout implements Player.Listener {

    public interface Host { void closeDetail(); }

    private final Host host;
    private final CoverMorph bg;
    private View scrim;
    private LinearLayout headerRow;
    private final TextView title;
    private final TextView artist;
    private final FrameLayout art;
    private final TrackList list;
    private final TextView emptyHint;
    private final TextView selBar;
    private final TextView restoreChip;
    private final LinearLayout pills;
    private final Slider seek;
    private final TextView pos, dur, badge;
    private final Icon playIcon;
    private final FrameLayout pager;
    private final View pageA, pageB;
    private final LinearLayout lyricBox;
    private final TextView lyricHint;
    private final ScrollView lyricScroll;
    private final LinearLayout root;

    private int page = 0;
    private float downX, downY;
    private boolean seekDrag;
    private List<Lyrics.Line> lyricLines = new ArrayList<>();
    private int currentLine = -1;
    private File loadedFor;
    private boolean userScrolled;

    public DetailPage(Context c, Host host) {
        super(c);
        this.host = host;
        setBackgroundColor(Color.BLACK);
        int pad = Ui.dp(c, 20);

        bg = new CoverMorph(c);
        addView(bg, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        scrim = new View(c);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xF2000000, 0x99000000, 0x66000000});
        scrim.setBackground(g);
        addView(scrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        root = Ui.col(c);
        addView(root, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout header = Ui.row(c);
        headerRow = header;
        header.setPadding(pad, Ui.dp(c, 40), pad, Ui.dp(c, 12));
        art = new FrameLayout(c);
        art.setBackground(Ui.bg(Ui.withAlpha(0xFFFFFFFF, 0.10f), 10, c));
        Icon note = new Icon(c).name("music").tint(Ui.withAlpha(0xFFFFFFFF, 0.8f));
        FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(Ui.dp(c, 24), Ui.dp(c, 24));
        nlp.gravity = Gravity.CENTER;
        art.addView(note, nlp);
        header.addView(art, new LinearLayout.LayoutParams(Ui.dp(c, 56), Ui.dp(c, 56)));
        LinearLayout meta = Ui.col(c);
        meta.setPadding(Ui.dp(c, 14), 0, 0, 0);
        title = Ui.tv(c, "", 18, Ui.TXT, true);
        title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        artist = Ui.tv(c, "", 13, Ui.withAlpha(0xFFFFFFFF, 0.7f), false);
        artist.setMaxLines(1);
        meta.addView(title);
        meta.addView(Ui.space(c, 0, Ui.dp(c, 3)));
        meta.addView(artist);
        header.addView(meta, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        selBar = Ui.tv(c, "", 12, Ui.ACC, true);
        selBar.setPadding(pad, Ui.dp(c, 2), pad, Ui.dp(c, 4));
        selBar.setVisibility(GONE);
        root.addView(selBar);

        restoreChip = Ui.tv(c, "已临时调整 · 恢复原样", 12, 0xFF000000, true);
        restoreChip.setGravity(Gravity.CENTER);
        restoreChip.setBackground(Ui.bg(Ui.withAlpha(0xFFFFFFFF, 0.85f), 14, c));
        LinearLayout chipWrap = Ui.row(c);
        chipWrap.setPadding(pad, 0, pad, Ui.dp(c, 8));
        chipWrap.addView(restoreChip, new LinearLayout.LayoutParams(Ui.dp(c, 168), Ui.dp(c, 30)));
        chipWrap.setVisibility(GONE);
        chipWrap.setTag("chipWrap");
        root.addView(chipWrap);
        restoreChip.setOnClickListener(v -> restoreAll());

        // pager
        pager = new FrameLayout(c);
        root.addView(pager, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout a = Ui.col(c);
        pills = Ui.row(c);
        LinearLayout pillWrap = Ui.row(c);
        pillWrap.setPadding(pad, 0, pad, Ui.dp(c, 6));
        pillWrap.addView(pills, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        a.addView(pillWrap);
        emptyHint = Ui.tv(c, "还没有在播放的音频\n去「资料库」点一首开始", 13.5f, Ui.SUB, false);
        emptyHint.setGravity(Gravity.CENTER);
        emptyHint.setPadding(pad, Ui.dp(c, 40), pad, 0);
        a.addView(emptyHint);
        list = new TrackList(c);
        list.setCallback(new TrackList.Cb() {
            @Override public void onPlay(File f) { Player.get().playTrack(f); }
            @Override public void onOrderChanged(List<File> files) {
                Player.get().setOrder(files, true);
                updateChip();
            }
            @Override public void onSelectionChanged(int count, boolean selectMode) {
                selBar.setVisibility(selectMode ? VISIBLE : GONE);
                selBar.setText(count == 0 ? "选择曲目 · 取消"
                        : "已选 " + count + " 首 · 长按已选可拖拽 · 点空白取消");
            }
        });
        list.setOnLongClickListener(v -> { list.exitSelect(); return true; });
        a.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        pageA = a;

        LinearLayout b = Ui.col(c);
        LinearLayout lyricHead = Ui.row(c);
        lyricHead.setPadding(pad, 0, pad, Ui.dp(c, 4));
        lyricHead.addView(Ui.tv(c, "歌 词", 12, Ui.SUB, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView search = Ui.tv(c, "搜索歌词", 12, Ui.ACC, false);
        search.setPadding(Ui.dp(c, 8), Ui.dp(c, 4), 0, Ui.dp(c, 4));
        search.setOnClickListener(v -> askManualSearch());
        lyricHead.addView(search);
        b.addView(lyricHead);
        lyricScroll = new ScrollView(c);
        lyricScroll.setVerticalScrollBarEnabled(false);
        lyricBox = Ui.col(c);
        lyricBox.setPadding(pad, Ui.dp(c, 60), pad, Ui.dp(c, 60));
        lyricScroll.addView(lyricBox);
        lyricScroll.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_MOVE) userScrolled = true;
            return false;
        });
        b.addView(lyricScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        lyricHint = Ui.tv(c, "", 13, Ui.SUB, false);
        lyricHint.setGravity(Gravity.CENTER);
        lyricHint.setPadding(pad, 0, pad, Ui.dp(c, 8));
        b.addView(lyricHint);
        pageB = b;

        pager.addView(pageA, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        pager.addView(pageB, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        pageB.setVisibility(GONE);

        // bottom controls
        LinearLayout bottom = Ui.col(c);
        bottom.setPadding(pad, 0, pad, Ui.dp(c, 12));
        seek = new Slider(c).accent(Ui.withAlpha(0xFFFFFFFF, 0.95f))
                .trackColor(Ui.withAlpha(0xFFFFFFFF, 0.25f)).thick(4f, 0f).knob(true);
        seek.onChange(new Slider.OnChange() {
            @Override public void onChanging(float v) { seekDrag = true; pos.setText(fmt((int) (durationMs * v))); }
            @Override public void onChanged(float v) { seekDrag = false; Player.get().seek(v); }
        });
        bottom.addView(seek, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout times = Ui.row(c);
        pos = Ui.tv(c, "--:--", 11, Ui.withAlpha(0xFFFFFFFF, 0.7f), false);
        dur = Ui.tv(c, "--:--", 11, Ui.withAlpha(0xFFFFFFFF, 0.7f), false);
        badge = Ui.tv(c, "", 11, Ui.withAlpha(0xFFFFFFFF, 0.55f), false);
        times.addView(pos, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        times.addView(badge);
        FrameLayout rightCell = new FrameLayout(c);
        FrameLayout.LayoutParams rlp2 = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp2.gravity = Gravity.END;
        rightCell.addView(dur, rlp2);
        times.addView(rightCell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bottom.addView(times, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout ctl = Ui.row(c);
        ctl.setGravity(Gravity.CENTER);
        Icon prev = new Icon(c).name("prev").tint(Ui.TXT);
        FrameLayout playBtn = new FrameLayout(c);
        playIcon = new Icon(c).name("play").tint(0xFF000000);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(Ui.dp(c, 30), Ui.dp(c, 30));
        ilp.gravity = Gravity.CENTER;
        playIcon.tint(0xFF000000);
        playBtn.setBackground(Ui.circle(0xFFFFFFFF, c));
        playBtn.addView(playIcon, ilp);
        Icon next = new Icon(c).name("next").tint(Ui.TXT);
        ctl.addView(prev, new LinearLayout.LayoutParams(Ui.dp(c, 64), Ui.dp(c, 64)));
        LinearLayout.LayoutParams playLp = new LinearLayout.LayoutParams(Ui.dp(c, 72), Ui.dp(c, 72));
        playLp.leftMargin = Ui.dp(c, 10);
        playLp.rightMargin = Ui.dp(c, 10);
        ctl.addView(playBtn, playLp);
        ctl.addView(next, new LinearLayout.LayoutParams(Ui.dp(c, 64), Ui.dp(c, 64)));
        bottom.addView(ctl, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(bottom);

        prev.setOnClickListener(v -> Player.get().prev());
        next.setOnClickListener(v -> Player.get().next());
        playBtn.setOnClickListener(v -> Player.get().toggle());
        art.setOnClickListener(v -> switchPage(page == 0 ? 1 : 0));

        buildPills();

        // Pull-down-to-dismiss lives in onInterceptTouchEvent, which only claims gestures that
        // start on the header row. A transparent 150 dp overlay used to sit here and swallowed
        // every touch in the top band of the page, which is exactly where the loop pills are.
    }

    private void restoreAll() {
        Player.get().restore();
        if (list != null) list.setFiles(Player.get().queue(), Player.get().track());
        View chip = findViewWithTag("chipWrap");
        if (chip != null) chip.setVisibility(GONE);
    }

    private void buildPills() {
        Context c = getContext();
        pills.removeAllViews();
        String[] fontIcons = {"shuffle", "repeat", "repeatOne"};
        String[] desc = {"随机播放并循环", "列表循环", "单曲循环"};
        for (int i = 0; i < 3; i++) {
            final int mode = i == 0 ? Player.MODE_SHUFFLE : i == 1 ? Player.MODE_LIST : Player.MODE_SINGLE;
            boolean on = Player.get().mode() == mode;
            FrameLayout pill = new FrameLayout(c);
            pill.setContentDescription(desc[i]);
            pill.setBackground(Ui.bg(on ? Ui.withAlpha(0xFFFFFFFF, 0.95f) : Ui.withAlpha(0xFFFFFFFF, 0.16f), 18, c));
            Icon ic = new Icon(c).name(fontIcons[i]).tint(on ? 0xFF000000 : Ui.TXT);
            FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(Ui.dp(c, 22), Ui.dp(c, 22));
            ilp.gravity = Gravity.CENTER;
            pill.addView(ic, ilp);
            pill.setOnClickListener(v -> { Player.get().setMode(mode); buildPills(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(c, 40), 1f);
            lp.rightMargin = i < 2 ? Ui.dp(c, 8) : 0;
            pills.addView(pill, lp);
        }
    }

    // ------------------------------------------------------------------ pages

    private void switchPage(int to) {
        if (to == page) return;
        page = to;
        int w = pager.getWidth();
        pageA.setVisibility(View.VISIBLE);
        pageB.setVisibility(View.VISIBLE);
        pageA.animate().translationX(to == 1 ? -w : 0).alpha(to == 1 ? 0.4f : 1f).setDuration(220).start();
        pageB.animate().translationX(to == 1 ? 0 : w).alpha(to == 0 ? 0.4f : 1f).setDuration(220)
                .withEndAction(() -> { if (page == 0) pageB.setVisibility(GONE); }).start();
        if (to == 0) {
            userScrolled = false;
            scrollToCurrentLine();
        }
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                break;
            case MotionEvent.ACTION_MOVE:
                float dx = ev.getX() - downX, dy = ev.getY() - downY;
                boolean onHeader = headerRow != null && downY <= headerRow.getBottom();
                if (onHeader && dy > Ui.dp(getContext(), 60) && dy > Math.abs(dx) * 1.5f) {
                    downY = ev.getY();
                    host.closeDetail();
                    return true;
                }
                if (Math.abs(dx) > Ui.dp(getContext(), 60) && Math.abs(dx) > Math.abs(dy) * 1.6f) {
                    if (page == 0 && dx > 0) { switchPage(1); downX = ev.getX(); return true; }
                    if (page == 1 && dx < 0) { switchPage(0); downX = ev.getX(); return true; }
                }
                break;
        }
        return false;
    }

    // ------------------------------------------------------------------ player

    public void bind() {
        onQueue();
        File t = Player.get().track();
        if (t != null) onTrack(t, Player.get().index(), Player.get().queue().size());
        onState(Player.get().isPlaying());
        buildPills();
    }

    @Override public void onTrack(File f, int index, int total) {
        if (f == null) return;
        title.setText(f.getName().replaceAll("\\.[^.]+$", ""));
        artist.setText((index + 1) + " / " + total + (Player.get().label().isEmpty()
                ? "" : " · " + Player.get().label()));
        list.setCurrent(f);
        loadBackdrop(f);
        loadLyrics(f);
        updateChip();
    }

    @Override public void onState(boolean playing) {
        playIcon.name(playing ? "pause" : "play");
        playIcon.invalidate();
    }

    private int durationMs = 1;

    @Override public void onProgress(float fraction, int posMs, int durMs) {
        durationMs = Math.max(1, durMs);
        dur.setText(durMs > 0 ? "-" + fmt(Math.max(0, durMs - posMs)) : "--:--");
        if (!seekDrag) {
            seek.setValue(fraction);
            pos.setText(fmt(posMs));
        }
        syncLyrics(posMs);
    }

    @Override public void onQueue() {
        list.setFiles(Player.get().queue(), Player.get().track());
        emptyHint.setVisibility(Player.get().queue().isEmpty() ? VISIBLE : GONE);
        if (Player.get().track() == null) {
            title.setText("未在播放");
            artist.setText("从资料库选一首开始");
        }
        updateChip();
    }

    @Override public void onMode(int mode) { buildPills(); }

    private void updateChip() {
        View v = findViewWithTag("chipWrap");
        if (v != null) v.setVisibility(Player.get().modified() ? VISIBLE : GONE);
    }

    // ------------------------------------------------------------------ backdrop

    private File backdropFor;
    private File badgeFor;
    private CoverLoader.Request backdropReq;
    private CoverLoader.Request lyricsReq;

    private void loadBackdrop(final File f) {
        if (badgeFor == null || !badgeFor.equals(f)) {
            badgeFor = f;
            CoverLoader.tags(f, (file, info) -> {
                if (!file.equals(badgeFor)) return;
                badge.setText(info != null && info.sampleRate > 0
                        ? String.format(Locale.US, "%.1f kHz / %d bit", info.sampleRate / 1000f, info.bits)
                        : "");
            });
        }
        if (backdropFor != null && backdropFor.equals(f)) return;
        backdropFor = f;
        if (backdropReq != null) backdropReq.cancel();
        // Decoding and the six-level blur ladder both run on the loader thread; the UI thread only
        // installs the finished bitmaps.
        final int ref = bg.getWidth() > 0 ? bg.getWidth() : getResources().getDisplayMetrics().widthPixels;
        backdropReq = CoverLoader.backdrop(getContext(), f, 1024, ref, (file, prepared) -> {
            if (prepared == null || !file.equals(backdropFor)) return;
            bg.setPrepared(prepared);
        });
    }

    // -------------------------------------------------------------- page transition

    /** Starts the artwork grow: sharp cover on the home position to full bleed blurred backdrop. */
    public void beginEnter(Rect from) {
        bg.setFrom(from);
        setBackgroundColor(Color.TRANSPARENT);
        setEnterProgress(0f);
    }

    /** 0 = home screen with the sharp artwork, 1 = settled detail page. */
    public void setEnterProgress(float p) {
        bg.setProgress(p);
        bg.setBackdrop(Math.min(1f, p / 0.45f));
        scrim.setAlpha(clamp01((p - 0.2f) / 0.45f));
        root.setAlpha(clamp01((p - 0.3f) / 0.5f));
        root.setTranslationY((1f - clamp01((p - 0.25f) / 0.55f)) * Ui.dp(getContext(), 18));
    }

    public void endEnter() {
        setEnterProgress(1f);
        setBackgroundColor(Color.BLACK);
    }

    /** Reverse of {@link #beginEnter}. */
    public void beginExit() {
        setBackgroundColor(Color.TRANSPARENT);
    }

    /**
     * Frees the blurred backdrop ladder once the page is gone: it is ~5.8 MB and would otherwise sit
     * in memory (and pin the 1024 px artwork in the cover cache) until the next track. The next open
     * asks for it again, which is a background decode plus the blur the loader already runs off the
     * UI thread.
     */
    public void releaseArt() {
        backdropFor = null;   // the ladder is gone, so the next open must rebuild it
        bg.release();
        bg.invalidate();
    }

    private static float clamp01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }

    // ------------------------------------------------------------------ lyrics

    private void loadLyrics(final File f) {
        if (loadedFor != null && loadedFor.equals(f) && !lyricLines.isEmpty()) return;
        lyricLines = new ArrayList<>();
        currentLine = -1;
        userScrolled = false;
        renderLyrics("正在加载歌词…");
        loadedFor = f;
        if (lyricsReq != null) lyricsReq.cancel();
        lyricsReq = CoverLoader.tags(f, (file, info) -> {
            if (!f.equals(loadedFor)) return;
            String ti = info != null && info.title != null
                    ? info.title : f.getName().replaceAll("\\.[^.]+$", "");
            String ar = info != null && info.artist != null ? info.artist : "";
            Lyrics.load(getContext(), f, ti, ar, info == null ? 0 : info.durationMs(), doc -> {
                if (!f.equals(loadedFor)) return;   // a newer track already owns the lyric pane
                if (doc == null || !doc.ok()) { lyricLines = new ArrayList<>(); renderLyrics(null); return; }
                lyricLines = doc.lines;
                renderLyrics(doc.note);
            });
        });
    }

    private void renderLyrics(String note) {
        lyricBox.removeAllViews();
        Context c = getContext();
        if (lyricLines.isEmpty()) {
            lyricHint.setText(note == null ? "暂无歌词 · 可点右上「搜索歌词」手动查找" : note);
            return;
        }
        lyricHint.setText(note == null ? "" : note);
        if (lyricLines.isEmpty()) return;
        for (int i = 0; i < lyricLines.size(); i++) {
            final Lyrics.Line ln = lyricLines.get(i);
            LinearLayout row = Ui.row(c);
            row.setPadding(0, Ui.dp(c, 10), 0, Ui.dp(c, 10));
            TextView t = Ui.tv(c, ln.text, 17, Ui.withAlpha(0xFFFFFFFF, 0.45f), true);
            t.setMaxLines(3);
            row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            Icon play = new Icon(c).name("play").tint(Ui.ACC);
            play.setVisibility(GONE);
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(Ui.dp(c, 26), Ui.dp(c, 26));
            plp.leftMargin = Ui.dp(c, 10);
            row.addView(play, plp);
            final int idx = i;
            play.setOnClickListener(v -> {
                Player.get().seekTo(ln.ms);
                if (!Player.get().isPlaying()) Player.get().toggle();
                userScrolled = false;
                scrollToCurrentLine();
            });
            row.setTag(idx);
            lyricBox.addView(row);
        }
        scrollToCurrentLine();
    }

    private void syncLyrics(int posMs) {
        if (lyricLines.isEmpty()) return;
        int idx = -1;
        for (int i = 0; i < lyricLines.size(); i++) {
            if (lyricLines.get(i).ms <= posMs) idx = i;
            else break;
        }
        if (idx < 0) return;
        if (idx == currentLine) return;
        int old = currentLine;
        currentLine = idx;
        updateLine(old);
        updateLine(idx);
        if (!userScrolled) scrollToCurrentLine();
    }

    private void updateLine(int i) {
        if (i < 0 || i >= lyricBox.getChildCount()) return;
        LinearLayout row = (LinearLayout) lyricBox.getChildAt(i);
        TextView t = (TextView) row.getChildAt(0);
        View play = row.getChildAt(1);
        boolean on = i == currentLine;
        t.setTextColor(on ? Ui.TXT : Ui.withAlpha(0xFFFFFFFF, 0.42f));
        t.setTextSize(on ? 21 : 17);
        play.setVisibility(on ? GONE : VISIBLE);
    }

    private void scrollToCurrentLine() {
        if (currentLine < 0 || currentLine >= lyricBox.getChildCount()) return;
        View v = lyricBox.getChildAt(currentLine);
        int target = v.getTop() - lyricScroll.getHeight() / 2 + v.getHeight() / 2;
        lyricScroll.smoothScrollTo(0, Math.max(0, target));
    }

    private void askManualSearch() {
        Context c = getContext();
        EditText e = new EditText(c);
        e.setSingleLine(true);
        e.setHint("歌名 歌手");
        File t = Player.get().track();
        if (t != null) e.setText(t.getName().replaceAll("\\.[^.]+$", "").replace('_', ' '));
        new AlertDialog.Builder(c).setTitle("手动搜索歌词").setView(e)
                .setPositiveButton("搜索", (d, w) -> {
                    Lyrics.search(e.getText().toString(), doc -> {
                        if (doc == null || !doc.ok()) {
                            Toast.makeText(c, "没有找到歌词", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        lyricLines = doc.lines;
                        currentLine = -1;
                        renderLyrics(doc.note);
                    });
                })
                .setNegativeButton("取消", null).show();
    }

    static String fmt(int ms) {
        int s = Math.max(0, ms / 1000);
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

}
