package com.vstplayer.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Scrollable track list with long-press drag (single or multi selection),
 * swipe-left delete and optional missing-file marking.
 */
public class TrackList extends ScrollView {

    public interface Cb {
        void onPlay(File f);
        void onOrderChanged(List<File> files);
        void onSelectionChanged(int count, boolean selectMode);
    }

    private static final int DRAG_DELAY = 380;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final LinearLayout box;
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final List<File> files = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    private final Set<File> selected = new LinkedHashSet<>();
    private final List<File> dragged = new ArrayList<>();

    private Cb cb;
    private File current;
    private boolean selectMode;
    private boolean dragging;
    private boolean swiping;
    private int gap = -1;
    private int gapY = -1;
    private float downX, downY;
    private Row touchRow;
    private File touchFile;

    private final Runnable longPress = () -> {
        if (touchFile == null || dragging || swiping) return;
        if (!selectMode) { selectMode = true; selected.clear(); selected.add(touchFile); }
        else if (!selected.contains(touchFile)) { selected.add(touchFile); }
        applyRows();   // the check marks appear in place, no row is rebuilt
        beginDrag();
    };

    public TrackList(Context c) {
        super(c);
        setVerticalScrollBarEnabled(false);
        box = new LinearLayout(c) {
            @Override protected void dispatchDraw(Canvas canvas) {
                super.dispatchDraw(canvas);
                if (dragging && gapY >= 0) {
                    linePaint.setColor(Ui.ACC);
                    canvas.drawRect(Ui.dp(getContext(), 16), gapY - Ui.dp(getContext(), 1.5f),
                            getWidth() - Ui.dp(getContext(), 16), gapY + Ui.dp(getContext(), 1.5f), linePaint);
                }
            }
        };
        box.setOrientation(LinearLayout.VERTICAL);
        addView(box, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        setClipToPadding(false);
    }

    public void setCallback(Cb cb) { this.cb = cb; }

    public void setFiles(List<File> list, File currentFile) {
        List<File> next = new ArrayList<>();
        if (list != null) next.addAll(list);
        boolean sameFiles = next.equals(files);
        boolean sameCurrent = current == null ? currentFile == null : current.equals(currentFile);
        files.clear();
        files.addAll(next);
        current = currentFile;
        selected.retainAll(files);
        if (selected.isEmpty()) selectMode = false;
        if (!sameFiles) {
            rebuild();               // the set of rows itself changed
        } else if (!sameCurrent) {
            applyRows();             // only the "now playing" highlight moved
        }
    }

    public void setCurrent(File f) {
        if (f == null ? current == null : f.equals(current)) return;
        current = f;
        applyRows();
    }

    public List<File> files() { return new ArrayList<>(files); }

    public boolean selectMode() { return selectMode; }

    public void exitSelect() {
        if (!selectMode && selected.isEmpty()) return;
        selectMode = false;
        selected.clear();
        applyRows();
        if (cb != null) cb.onSelectionChanged(0, false);
    }

    public void selectAll() {
        selectMode = true;
        selected.clear();
        selected.addAll(files);
        applyRows();
        if (cb != null) cb.onSelectionChanged(selected.size(), true);
    }

    public void setMissing(Set<String> names) {
        missing.clear();
        if (names != null) missing.addAll(names);
        applyRows();
    }

    private final Set<String> missing = new LinkedHashSet<>();

    // ------------------------------------------------------------------ build

    /** One built row plus the handles needed to restyle it without rebuilding it. */
    private static final class Row {
        final File file;
        final LinearLayout root;
        final FrameLayout mark;
        final Icon check;
        final Icon note;
        final TextView title;
        final TextView sub;
        /** The two backgrounds this row switches between; built once, never reallocated per track. */
        final GradientDrawable bgOn, bgOff;
        final GradientDrawable markOn, markOff;

        Row(File file, LinearLayout root, FrameLayout mark, Icon check, Icon note,
            TextView title, TextView sub, GradientDrawable bgOn, GradientDrawable bgOff,
            GradientDrawable markOn, GradientDrawable markOff) {
            this.file = file;
            this.root = root;
            this.mark = mark;
            this.check = check;
            this.note = note;
            this.title = title;
            this.sub = sub;
            this.bgOn = bgOn;
            this.bgOff = bgOff;
            this.markOn = markOn;
            this.markOff = markOff;
        }
    }

    private void rebuild() {
        box.removeAllViews();
        rows.clear();
        Context c = getContext();
        for (File f : files) {
            Row row = buildRow(c, f);
            box.addView(row.root, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT));
            rows.add(row);
        }
    }

    private Row buildRow(Context c, final File f) {
        LinearLayout root = Ui.row(c);
        root.setPadding(Ui.dp(c, 14), Ui.dp(c, 8), Ui.dp(c, 14), Ui.dp(c, 8));

        // The multi-select mark always exists so entering/leaving select mode never needs a rebuild.
        FrameLayout mark = new FrameLayout(c);
        Icon check = new Icon(c).name("check").tint(0xFF000000);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(Ui.dp(c, 12), Ui.dp(c, 12));
        ilp.gravity = Gravity.CENTER;
        mark.addView(check, ilp);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(Ui.dp(c, 20), Ui.dp(c, 20));
        mlp.rightMargin = Ui.dp(c, 12);
        root.addView(mark, mlp);

        FrameLayout art = new FrameLayout(c);
        art.setBackground(Ui.bg(Ui.withAlpha(0xFFFFFFFF, 0.08f), 8, c));
        // Restyling happens on every track change and on every selection change: keep the two looks
        // of this row around instead of allocating new drawables for each of them.
        final GradientDrawable bgOn = Ui.bg(Ui.withAlpha(Ui.ACC, 0.16f), 10, c);
        final GradientDrawable bgOff = Ui.bg(0x00000000, 10, c);
        final GradientDrawable markOn = Ui.circle(Ui.ACC, c);
        final GradientDrawable markOff = Ui.circle(Ui.withAlpha(0xFFFFFFFF, 0.16f), c);
        Icon note = new Icon(c).name("music");
        FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(Ui.dp(c, 18), Ui.dp(c, 18));
        nlp.gravity = Gravity.CENTER;
        art.addView(note, nlp);
        root.addView(art, new LinearLayout.LayoutParams(Ui.dp(c, 42), Ui.dp(c, 42)));

        LinearLayout body = Ui.col(c);
        body.setPadding(Ui.dp(c, 12), 0, Ui.dp(c, 6), 0);
        TextView title = Ui.tv(c, f.getName().replaceAll("\\.[^.]+$", ""), 14.5f, Ui.TXT, false);
        title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        TextView sub = Ui.tv(c, "", 11.5f, Ui.SUB2, false);
        sub.setMaxLines(1);
        body.addView(title);
        body.addView(Ui.space(c, 0, Ui.dp(c, 2)));
        body.addView(sub);
        root.addView(body, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = Ui.dp(c, 2);
        root.setLayoutParams(rlp);

        final Row row = new Row(f, root, mark, check, note, title, sub, bgOn, bgOff, markOn, markOff);
        root.setTag(row);
        root.setOnClickListener(v -> {
            if (selectMode) {
                if (!selected.remove(f)) selected.add(f);
                applyRow(row);
                if (cb != null) cb.onSelectionChanged(selected.size(), true);
            } else if (cb != null && !missing.contains(f.getName())) {
                cb.onPlay(f);
            }
        });
        applyRow(row);
        return row;
    }

    /** Restyles one row for the current selection/playhead/missing state, keeping its views. */
    private void applyRow(Row row) {
        boolean isCurrent = row.file.equals(current);
        boolean isSel = selected.contains(row.file);
        boolean miss = missing.contains(row.file.getName());
        row.root.setBackground(isSel ? row.bgOn : row.bgOff);
        row.mark.setVisibility(selectMode ? VISIBLE : GONE);
        row.mark.setBackground(isSel ? row.markOn : row.markOff);
        row.check.setVisibility(isSel ? VISIBLE : GONE);
        row.note.tint(isCurrent ? Ui.ACC : Ui.withAlpha(0xFFFFFFFF, 0.55f));
        row.title.setTextColor(miss ? Ui.SUB2 : isCurrent ? Ui.ACC : Ui.TXT);
        row.sub.setText(miss ? "文件缺失" : (isCurrent ? "正在播放" : row.file.getName()));
        row.sub.setTextColor(miss ? 0xFFFF9F0A : Ui.SUB2);
    }

    private void applyRows() {
        for (Row row : rows) applyRow(row);
    }

    // ------------------------------------------------------------------ touch

    @Override public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                touchRow = rowAt(ev.getY() + getScrollY());
                touchFile = touchRow == null ? null : touchRow.file;
                dragging = false;
                swiping = false;
                gap = -1;
                gapY = -1;
                if (touchRow != null) h.postDelayed(longPress, DRAG_DELAY);
                break;
            case MotionEvent.ACTION_MOVE:
                float dx = ev.getX() - downX, dy = ev.getY() - downY;
                if (dragging) return true;
                if (Math.abs(dx) > Ui.dp(getContext(), 24) && Math.abs(dx) > Math.abs(dy) * 1.4f) {
                    if (dx < 0 && touchRow != null) {
                        h.removeCallbacks(longPress);
                        swiping = true;
                        return true;
                    }
                }
                if (Math.abs(dy) > Ui.dp(getContext(), 8)) h.removeCallbacks(longPress);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                h.removeCallbacks(longPress);
                break;
        }
        return dragging;
    }

    @Override public boolean onTouchEvent(MotionEvent ev) {
        if (dragging) {
            if (ev.getActionMasked() == MotionEvent.ACTION_UP || ev.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                drop();
            } else if (ev.getActionMasked() == MotionEvent.ACTION_MOVE) {
                updateDrag(ev.getY() + getScrollY());
            }
            return true;
        }
        if (swiping) {
            if (ev.getActionMasked() == MotionEvent.ACTION_MOVE) {
                touchRow.root.setTranslationX(Math.min(0, ev.getX() - downX));
            } else if (ev.getActionMasked() == MotionEvent.ACTION_UP
                    || ev.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                float dx = ev.getX() - downX;
                boolean gone = dx < -getWidth() * 0.35f;
                final Row r = touchRow;
                swiping = false;
                if (r == null) { return true; }
                if (gone) {
                    r.root.animate().translationX(-getWidth()).alpha(0).setDuration(140)
                            .withEndAction(() -> removeFile(r.file));
                } else {
                    r.root.animate().translationX(0).setDuration(120);
                }
            }
            return true;
        }
        return super.onTouchEvent(ev);
    }

    private void beginDrag() {
        if (touchRow == null) return;
        dragged.clear();
        dragged.addAll(selected);
        if (dragged.isEmpty()) dragged.add(touchFile);
        dragging = true;
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        for (Row r : rows) {
            if (dragged.contains(r.file)) r.root.setAlpha(0.28f);
        }
        updateDrag(downY + getScrollY());
    }

    private void updateDrag(float y) {
        gapY = (int) y;
        int best = -1;
        int bestDist = Integer.MAX_VALUE;
        int idx = 0;
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            if (dragged.contains(r.file)) continue;
            int cy = (r.root.getTop() + r.root.getBottom()) / 2;
            int d = Math.abs(cy - (int) y);
            if (best < 0 || d < bestDist) { bestDist = d; best = idx + (y > cy ? 1 : 0); }
            idx++;
        }
        gap = best < 0 ? 0 : best;
        int lineY;
        if (rows.isEmpty()) lineY = 0;
        else {
            int count = 0;
            lineY = box.getHeight();
            for (Row r : rows) {
                if (dragged.contains(r.file)) continue;
                count++;
                if (count > gap) { lineY = r.root.getTop(); break; }
            }
        }
        gapY = lineY;
        box.invalidate();
        autoScroll(y);
    }

    private void autoScroll(float y) {
        int vh = getHeight();
        if (y < getScrollY() + Ui.dp(getContext(), 60)) smoothScrollBy(0, -Ui.dp(getContext(), 8));
        else if (y > getScrollY() + vh - Ui.dp(getContext(), 60)) smoothScrollBy(0, Ui.dp(getContext(), 8));
    }

    private void drop() {
        dragging = false;
        for (Row r : rows) r.root.setAlpha(1f);
        if (gap < 0) { gapY = -1; box.invalidate(); return; }
        List<File> rest = new ArrayList<>();
        for (File f : files) if (!dragged.contains(f)) rest.add(f);
        List<File> result = new ArrayList<>();
        int at = Math.max(0, Math.min(gap, rest.size()));
        result.addAll(rest.subList(0, at));
        result.addAll(dragged);
        result.addAll(rest.subList(at, rest.size()));
        gapY = -1;
        files.clear();
        files.addAll(result);
        rebuild();
        if (cb != null) cb.onOrderChanged(new ArrayList<>(files));
    }

    private void removeFile(File f) {
        files.remove(f);
        selected.remove(f);
        rebuild();
        if (cb != null) cb.onOrderChanged(new ArrayList<>(files));
        if (cb != null) cb.onSelectionChanged(selected.size(), selectMode && !selected.isEmpty());
    }

    private Row rowAt(float yInBox) {
        for (Row r : rows) {
            if (yInBox >= r.root.getTop() && yInBox <= r.root.getBottom()) return r;
        }
        return null;
    }
}
