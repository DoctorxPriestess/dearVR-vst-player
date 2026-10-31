package com.vstplayer.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.vstplayer.core.Decode;
import com.vstplayer.core.Diag;
import com.vstplayer.core.EngineService;
import com.vstplayer.core.Export;
import com.vstplayer.core.LibraryRef;
import com.vstplayer.core.Param;
import com.vstplayer.core.Io;
import com.vstplayer.core.Player;
import com.vstplayer.core.Segment;
import com.vstplayer.core.Paths;
import com.vstplayer.core.Tags;
import com.vstplayer.core.Vst;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Tab 3: plugin parameters, presets and the render pane. */
public class VstView extends LinearLayout {

    private final Vst vst = Vst.get();
    private final SharedPreferences prefs;
    private final Set<String> favs = new HashSet<>();
    /** Single background lane for the SAF copy; never do file IO on the UI thread. */
    private static final java.util.concurrent.ExecutorService EXPORT =
            java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                Thread t = new Thread(runnable, "vst-export");
                t.setDaemon(true);
                return t;
            });
    /** Decoding + WAV slicing for the render queue: IO heavy, so it never runs on the UI thread. */
    private static final java.util.concurrent.ExecutorService PREP =
            java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                Thread t = new Thread(runnable, "vst-prep");
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            });
    private final Handler ui = new Handler(Looper.getMainLooper());
    /**
     * Same looper, separate queue for the render watchdogs: detaching the view drops only these,
     * while the batch callbacks already posted on {@link #ui} still run.
     */
    private final Handler uiDelays = new Handler(Looper.getMainLooper());

    private final TextView statusPill;
    private final TextView pluginName;
    private final TextView pluginMeta;
    private final View paneParams, panePresets, paneRender;
    private final TextView[] segs = new TextView[3];
    private final EditText search;
    private final ListView paramList;
    private final ParamAdapter adapter;
    private final List<Param> shown = new ArrayList<>();
    /** Parameter objects currently known to the adapter, used to tell a value update from a reload. */
    private final List<Param> known = new ArrayList<>();
    /**
     * Id of the parameter under the finger. Its label is owned by the slider while it is dragged, so
     * a SET ack arriving in the middle of the gesture must not rewrite it.
     */
    private String draggingParamId;
    /** True while a trailing parameter refresh is already queued for the next message. */
    private boolean paramsPending;

    private final LinearLayout presetList;
    private final TextView presetNow;

    private final LinearLayout srcList;
    private final TextView dirBtn;
    private final TextView renderBtn;
    private final TextView stopBtn;
    private final Slider progress;
    private final TextView renderStatus;
    private final LinkedHashSet<File> selected = new LinkedHashSet<>();
    // render batch state (UI thread only)
    private final List<Job> pending = new ArrayList<>();
    private final java.util.LinkedHashMap<Integer, FileJob> fileJobs = new java.util.LinkedHashMap<>();
    private int totalFiles, preparedFiles, totalSegments, doneSegments, finishedFiles;
    private int activeRenders, pendingStitches;
    private boolean prepareDone;
    private long batchStart;
    private String loadedPreset;

    public VstView(Context c) {
        super(c);
        prefs = c.getSharedPreferences("vstp", Context.MODE_PRIVATE);
        favs.addAll(prefs.getStringSet("favs", new HashSet<String>()));

        setOrientation(VERTICAL);
        setBackgroundColor(Ui.BG);
        int pad = Ui.dp(c, 20);

        LinearLayout head = Ui.row(c);
        head.addView(Ui.tv(c, "VST", 12, Ui.SUB, true),
                new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        statusPill = Ui.tv(c, "未启动", 11, Ui.SUB, false);
        head.addView(statusPill);
        head.setPadding(pad, Ui.dp(c, 18), pad, Ui.dp(c, 8));
        addView(head);

        pluginName = Ui.tv(c, "dearVR PRO 2", 25, Ui.TXT, true);
        pluginName.setPadding(pad, 0, pad, Ui.dp(c, 2));
        addView(pluginName);

        pluginMeta = Ui.tv(c, "VST3 · 离线渲染", 12, Ui.SUB, false);
        pluginMeta.setPadding(pad, 0, pad, Ui.dp(c, 14));
        addView(pluginMeta);

        LinearLayout seg = Ui.row(c);
        seg.setBackground(Ui.bg(Ui.CARD2, 10, c));
        seg.setPadding(Ui.dp(c, 3), Ui.dp(c, 3), Ui.dp(c, 3), Ui.dp(c, 3));
        LinearLayout segWrap = Ui.col(c);
        segWrap.setPadding(pad, 0, pad, Ui.dp(c, 12));
        segWrap.addView(seg, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        String[] names = {"参数", "预设", "渲染"};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            TextView t = Ui.tv(c, names[i], 13, Ui.SUB, false);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, Ui.dp(c, 8), 0, Ui.dp(c, 8));
            t.setOnClickListener(v -> select(idx));
            segs[i] = t;
            seg.addView(t, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        }
        addView(segWrap);

        // ------------------------------------------------------------ params pane
        LinearLayout pp = Ui.col(c);
        search = new EditText(c);
        search.setHint("搜索参数");
        search.setTextSize(14);
        search.setTextColor(Ui.TXT);
        search.setHintTextColor(Ui.SUB2);
        search.setSingleLine(true);
        search.setBackground(Ui.bg(Ui.CARD, 10, c));
        search.setPadding(Ui.dp(c, 14), Ui.dp(c, 8), Ui.dp(c, 14), Ui.dp(c, 8));
        search.setInputType(InputType.TYPE_CLASS_TEXT);
        LinearLayout searchWrap = Ui.col(c);
        searchWrap.setPadding(pad, 0, pad, Ui.dp(c, 8));
        searchWrap.addView(search, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        pp.addView(searchWrap);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { rebuild(); }
        });
        paramList = new ListView(c);
        paramList.setDivider(null);
        paramList.setPadding(Ui.dp(c, 12), 0, Ui.dp(c, 12), Ui.dp(c, 12));
        paramList.setClipToPadding(false);
        paramList.setVerticalScrollBarEnabled(false);
        adapter = new ParamAdapter();
        paramList.setAdapter(adapter);
        pp.addView(paramList, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));
        paneParams = pp;

        // ------------------------------------------------------------ presets pane
        ScrollView ps = new ScrollView(c);
        ps.setVerticalScrollBarEnabled(false);
        LinearLayout pbox = Ui.col(c);
        pbox.setPadding(pad, 0, pad, Ui.dp(c, 24));
        presetNow = Ui.tv(c, "当前预设：无", 12, Ui.SUB, false);
        pbox.addView(presetNow);
        pbox.addView(Ui.space(c, 0, Ui.dp(c, 14)));
        pbox.addView(primary(c, "保存为新预设", v -> askSave()));
        pbox.addView(Ui.space(c, 0, Ui.dp(c, 8)));
        pbox.addView(secondary(c, "覆盖保存当前预设", v -> {
            if (loadedPreset == null) { toast("先载入一个预设再覆盖保存"); return; }
            vst.savePreset(loadedPreset.replaceAll("\\.vstpreset$", ""), (ok, msg) -> {
                toast(ok ? "已覆盖 " + msg : "保存失败：" + msg);
                refreshPresets();
            });
        }));
        pbox.addView(Ui.space(c, 0, Ui.dp(c, 8)));
        pbox.addView(secondary(c, "前往配置文件夹", v -> openPresetFolder()));
        TextView t = Ui.tv(c, "载入预设 · 点击即应用", 12, Ui.SUB, true);
        t.setPadding(0, Ui.dp(c, 18), 0, Ui.dp(c, 8));
        pbox.addView(t);
        presetList = Ui.col(c);
        pbox.addView(presetList);
        ps.addView(pbox);
        panePresets = ps;

        // ------------------------------------------------------------ render pane
        ScrollView rsw = new ScrollView(c);
        rsw.setVerticalScrollBarEnabled(false);
        LinearLayout rp = Ui.col(c);
        rp.setPadding(pad, 0, pad, Ui.dp(c, 24));

        LinearLayout srcHead = Ui.row(c);
        srcHead.addView(label(c, "源音频 · 可多选"),
                new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        TextView all = Ui.tv(c, "全选", 12, Ui.ACC, false);
        all.setPadding(Ui.dp(c, 8), 0, 0, Ui.dp(c, 6));
        all.setOnClickListener(v -> {
            List<File> files = inputFiles();
            if (selected.size() == files.size()) selected.clear();
            else selected.addAll(files);
            applySourceMarks();
            updateRenderBtn();
        });
        srcHead.addView(all);
        rp.addView(srcHead);

        LinearLayout srcModes = Ui.row(c);
        srcModes.setPadding(0, 0, 0, Ui.dp(c, 10));
        srcModeAddr = srcChip(c, "按地址");
        srcModeTag = srcChip(c, "按标签");
        srcModes.addView(srcModeAddr);
        LinearLayout.LayoutParams chipGap = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        chipGap.leftMargin = Ui.dp(c, 8);
        srcModes.addView(srcModeTag, chipGap);
        srcModeAddr.setOnClickListener(v -> { srcMode = 0; styleSrcModes(); refreshSourceRows(); });
        srcModeTag.setOnClickListener(v -> { srcMode = 1; styleSrcModes(); refreshSourceRows(); });
        rp.addView(srcModes);
        styleSrcModes();
        srcList = Ui.col(c);
        rp.addView(srcList);

        rp.addView(Ui.space(c, 0, Ui.dp(c, 14)));
        rp.addView(label(c, "输出目录"));
        dirBtn = secondary(c, Export.label(c), v -> pickOutDir());
        rp.addView(dirBtn);
        TextView dirNote = Ui.tv(c, "选一个系统目录即可直接导出；不选则保存在应用内。", 11.5f, Ui.SUB2, false);
        dirNote.setPadding(0, Ui.dp(c, 6), 0, 0);
        rp.addView(dirNote);

        rp.addView(Ui.space(c, 0, Ui.dp(c, 18)));
        renderBtn = primary(c, "开始渲染", v -> {
            batchOutLabel = null;
            startRender();
        });
        rp.addView(renderBtn);
        stopBtn = secondary(c, "停止渲染", v -> stopRender());
        stopBtn.setTextColor(0xFFFF453A);
        stopBtn.setVisibility(GONE);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        slp.topMargin = Ui.dp(c, 10);
        rp.addView(stopBtn, slp);
        rp.addView(Ui.space(c, 0, Ui.dp(c, 16)));
        progress = new Slider(c).accent(Ui.ACC).thick(6f, 0f).knob(false);
        progress.setEnabled(false);
        progress.setValue(0f);
        rp.addView(progress);
        renderStatus = Ui.tv(c, "待命", 12, Ui.SUB, false);
        renderStatus.setPadding(Ui.dp(c, 2), Ui.dp(c, 4), 0, 0);
        rp.addView(renderStatus);
        TextView note = Ui.tv(c, "输出保持源文件的采样率与位深；渲染时参数不可修改。", 11.5f, Ui.SUB2, false);
        note.setPadding(0, Ui.dp(c, 18), 0, 0);
        rp.addView(note);
        rsw.addView(rp);
        paneRender = rsw;

        addView(paneParams, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));
        addView(panePresets, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));
        addView(paneRender, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));

        select(0);
    }

    // ------------------------------------------------------------------ segments

    private void select(int idx) {
        paneParams.setVisibility(idx == 0 ? VISIBLE : GONE);
        panePresets.setVisibility(idx == 1 ? VISIBLE : GONE);
        paneRender.setVisibility(idx == 2 ? VISIBLE : GONE);
        for (int i = 0; i < 3; i++) {
            segs[i].setTextColor(i == idx ? Ui.TXT : Ui.SUB);
            segs[i].setBackground(i == idx ? Ui.bg(Ui.withAlpha(0xFFFFFFFF, 0.12f), 8, getContext()) : null);
        }
        if (idx == 1) refreshPresets();
        if (idx == 2) { refreshSources(); refreshOutDir(); }
    }

    public void setTab(int i) { select(i); }

    // --------------------------------------------------------------------- state

    public void onState(int state, String label) {
        if (state == Vst.READY) {
            statusPill.setText("● 就绪");
            statusPill.setTextColor(Ui.OK);
            refreshSources();
            debugHooks();
        } else if (state == Vst.STARTING) {
            statusPill.setText("● " + label);
            statusPill.setTextColor(0xFFFFD60A);
        } else {
            statusPill.setText("● " + (label == null ? "停止" : label));
            statusPill.setTextColor(Ui.SUB);
        }
    }

    /**
     * Debug-only entry points so a device run can be scripted with files/boot.conf instead of
     * tapping the UI: in a debuggable build {@code decode.selftest=1} decodes the codec fixtures
     * once and logs the result, and {@code render.autorun=1} starts a batch of every source in
     * work/in as soon as the engine reports ready.
     */
    private void debugHooks() {
        final Context app = getContext() == null ? null : getContext().getApplicationContext();
        if (app == null) return;
        if (confInt("decode.selftest", 0, 0, 1) == 1 && !selfTestDone) {
            selfTestDone = true;
            final File dir = vst.inDir();
            new Thread(() -> {
                File[] probe = {
                        new File(dir, "src_alac24.m4a"), new File(dir, "src_alac16.m4a"),
                        new File(dir, "src_aac.m4a"), new File(dir, "src_flac24.flac"),
                        new File(dir, "src_flac16.flac"), new File(dir, "src_hecc.m4a"),
                        new File(dir, "src_alac24_96k.m4a"), new File(dir, "src_aac48.m4a") };
                Decode.selfTest(app, probe);
            }, "decode-selftest").start();
        }
        // One named file instead of the fixed fixtures: used to check a single library file
        // (e.g. the ALAC that killed the platform decoder) without queueing a whole batch.
        final String one = confRaw("decode.one");
        if (one != null && !one.isEmpty() && !decodeOneDone) {
            decodeOneDone = true;
            new Thread(() -> {
                File src = new File(vst.inDir(), one);
                File dst = new File(app.getCacheDir(), "decode-one.wav");
                Decode.Result r = Decode.decode(app, src, dst, null);
                Diag.log("decode.one: " + one + " -> "
                        + (r.ok() ? "OK " + dst.length() + "B" : "FAIL " + r.error));
                // decode.keep=1 leaves the WAV on the cache dir so it can be pulled and compared.
                if (confInt("decode.keep", 0, 0, 1) != 1) dst.delete();
            }, "decode-one").start();
        }
        if (confInt("render.autorun", 0, 0, 1) == 1 && !autorunDone) {
            autorunDone = true;
            uiDelays.postDelayed(() -> {
                if (vst.state() != Vst.READY) { Diag.log("autorun: engine not ready"); return; }
                List<File> files = inputFiles();
                if (files.isEmpty()) { Diag.log("autorun: no input files"); return; }
                selected.clear();
                selected.addAll(files);
                Diag.log("autorun: " + selected.size() + " file(s)");
                startRender();
            }, 1200);
        }
    }

    public void onParams() {
        // A SET ack arrives every ~40 ms while a value is pushed, and each one used to walk every
        // bound row again. One trailing refresh per message turn is enough: it reads the current
        // parameter state, so an update can never be lost to the coalescing.
        if (paramsPending) return;
        paramsPending = true;
        ui.post(() -> {
            paramsPending = false;
            refreshParams();
        });
    }

    private void refreshParams() {
        List<Param> all = vst.params();
        if (!all.isEmpty()) pluginMeta.setText("VST3 · " + all.size() + " 个参数 · 离线渲染");
        if (sameParams(known, all)) {
            // A SET ack or a preset change only mutated values: restyle the visible rows.
            refreshValues();
        } else {
            // The parameter set itself changed (first load / plugin reload): rebind everything.
            rebuild(all);
            if (loadedPreset != null) refreshPresets();
        }
    }

    /** True when both lists hold the very same parameter instances in the same order. */
    private static boolean sameParams(List<Param> a, List<Param> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (a.get(i) != b.get(i)) return false;
        return true;
    }

    /** Refreshes the value label of every bound row without touching the adapter. */
    private void refreshValues() {
        for (int i = 0; i < paramList.getChildCount(); i++) {
            Object tag = paramList.getChildAt(i).getTag();
            if (!(tag instanceof Row)) continue;
            Row row = (Row) tag;
            if (row.p != null && !row.p.id.equals(draggingParamId)) row.val.setText(row.p.valueText());
        }
    }

    public void onRenderProgress(int pct) {
        progress.setValue(pct / 100f);
        String item = totalFiles > 1
                ? "第 " + Math.min(finishedFiles + 1, totalFiles) + "/" + totalFiles + " 个"
                : "";
        if (totalSegments > 1) {
            renderStatus.setText(item + (item.isEmpty() ? "" : " · ") + "分段 "
                    + Math.min(totalSegments, doneSegments + 1) + "/" + totalSegments + " · " + pct + "%");
        } else {
            renderStatus.setText(pct < 100 ? "渲染中 " + pct + "%" : "写入文件…");
        }
    }

    // -------------------------------------------------------------------- params

    private void rebuild() {
        rebuild(vst.params());
    }

    private void rebuild(List<Param> all) {
        known.clear();
        known.addAll(all);
        String q = search.getText().toString().trim().toLowerCase(Locale.US);
        shown.clear();
        for (Param p : all) {
            if (q.isEmpty() || p.name.toLowerCase(Locale.US).contains(q)) shown.add(p);
        }
        shown.sort((a, b) -> {
            boolean fa = favs.contains(a.id), fb = favs.contains(b.id);
            if (fa != fb) return fa ? -1 : 1;
            return Integer.compare(a.index, b.index);
        });
        adapter.notifyDataSetChanged();
    }

    /** The three controls of one parameter row, kept across adapter recycles. */
    private static final class Row {
        Param p;
        TextView name;
        TextView val;
        Icon star;
        Slider slider;
        boolean starOn;
        /** Last whole percent shown while dragging; -1 when nothing was shown yet. */
        int pct = -1;
    }

    private class ParamAdapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int i) { return shown.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int i, View reuse, ViewGroup parent) {
            Context c = getContext();
            Param p = shown.get(i);
            LinearLayout root;
            final Row row;
            if (reuse instanceof LinearLayout && reuse.getTag() instanceof Row) {
                root = (LinearLayout) reuse;
                row = (Row) root.getTag();
            } else {
                root = Ui.col(c);
                root.setPadding(Ui.dp(c, 4), Ui.dp(c, 8), Ui.dp(c, 4), Ui.dp(c, 4));
                row = new Row();

                LinearLayout top = Ui.row(c);
                row.name = Ui.tv(c, "", 13.5f, Ui.TXT, false);
                row.name.setMaxLines(1);
                row.name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                top.addView(row.name, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

                row.val = Ui.tv(c, "", 12, Ui.SUB, false);
                row.val.setMaxLines(1);
                row.val.setEllipsize(android.text.TextUtils.TruncateAt.END);
                row.val.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.42f));
                LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT,
                        LayoutParams.WRAP_CONTENT);
                vlp.rightMargin = Ui.dp(c, 8);
                top.addView(row.val, vlp);

                row.star = new Icon(c).name("star").tint(Ui.SUB2);   // off state; bind() flips it on if needed
                top.addView(row.star, new LinearLayout.LayoutParams(Ui.dp(c, 26), Ui.dp(c, 26)));
                root.addView(top);

                row.slider = new Slider(c).accent(Ui.ACC).thick(4f, 0f);
                row.slider.onChange(new Slider.OnChange() {
                    @Override public void onChanging(float v) {
                        if (row.p != null) draggingParamId = row.p.id;
                        int pct = Math.round(v * 100f);
                        if (pct != row.pct) {
                            row.pct = pct;
                            row.val.setText(String.format(Locale.US, "%.0f%%", v * 100));
                        }
                    }
                    @Override public void onChanged(float v) {
                        if (row.p == null) return;
                        draggingParamId = null;
                        row.p.norm = v;
                        Diag.log("drag " + row.p.name + " " + v);
                        vst.setParam(row.p.id, v);
                    }
                });
                root.addView(row.slider, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,
                        Ui.dp(c, 30)));

                row.star.setOnClickListener(v -> {
                    if (row.p == null) return;
                    if (!favs.remove(row.p.id)) favs.add(row.p.id);
                    prefs.edit().putStringSet("favs", new HashSet<>(favs)).apply();
                    rebuild();
                });
                root.setTag(row);
            }

            boolean rebind = row.p != p;
            row.p = p;
            if (rebind) row.pct = -1;
            row.name.setText(p.name);
            row.val.setText(p.valueText());
            boolean starOn = favs.contains(p.id);
            if (row.starOn != starOn) {
                row.starOn = starOn;
                row.star.name(starOn ? "starOn" : "star").tint(starOn ? Ui.ACC : Ui.SUB2);
            }
            // Re-assigning the value while the user drags this very row would fight the thumb.
            if (rebind) row.slider.setValue(p.norm);
            return root;
        }
    }

    // ------------------------------------------------------------------- presets

    public void refreshPresets() {
        presetList.removeAllViews();
        File[] files = vst.env() == null ? new File[0] : vst.presets();
        presetNow.setText("当前预设：" + (loadedPreset == null ? "无"
                : loadedPreset.replaceAll("\\.vstpreset$", "")));
        if (files.length == 0) {
            presetList.addView(Ui.tv(getContext(), "还没有保存过预设", 12.5f, Ui.SUB2, false));
            return;
        }
        Context c = getContext();
        for (File f : files) {
            boolean current = f.getName().equals(loadedPreset);
            LinearLayout row = Ui.row(c);
            row.setBackground(Ui.bg(current ? Ui.withAlpha(Ui.ACC, 0.16f) : Ui.CARD, 12, c));
            row.setPadding(Ui.dp(c, 14), Ui.dp(c, 12), Ui.dp(c, 14), Ui.dp(c, 12));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = Ui.dp(c, 8);
            TextView name = Ui.tv(c, f.getName().replaceAll("\\.vstpreset$", ""), 14,
                    current ? Ui.ACC : Ui.TXT, false);
            row.addView(name, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
            if (current) {
                Icon ic = new Icon(c).name("check").tint(Ui.ACC);
                row.addView(ic, new LinearLayout.LayoutParams(Ui.dp(c, 20), Ui.dp(c, 20)));
            }
            row.setOnClickListener(v -> vst.loadPreset(f, (ok, msg) -> {
                if (ok) {
                    loadedPreset = f.getName();
                    refreshPresets();
                    toast("已载入 " + msg);
                } else {
                    toast("载入失败：" + msg);
                }
            }));
            row.setOnLongClickListener(v -> {
                new AlertDialog.Builder(c).setTitle(f.getName())
                        .setItems(new String[]{"删除"}, (d, w) -> {
                            f.delete();
                            if (f.getName().equals(loadedPreset)) loadedPreset = null;
                            refreshPresets();
                        })
                        .setNegativeButton("取消", null).show();
                return true;
            });
            presetList.addView(row, lp);
        }
    }

    /**
     * Shows where the presets live and what can be done with that folder.
     *
     * <p>The preset folder is inside the app's private storage (the guest engine reads it from
     * there), which no third party file manager can browse, so "go to the folder" means: see the
     * exact path, copy it, pull presets in from / push them out to a system folder, and inspect the
     * files one by one.
     */
    private void openPresetFolder() {
        final Context c = getContext();
        final File dir = vst.presetsDir();
        if (dir == null) { toast("引擎还没准备好"); return; }
        File[] files = dir.listFiles();
        int n = files == null ? 0 : files.length;
        long bytes = 0;
        if (files != null) for (File f : files) bytes += f.length();
        // A dialog can host either a message or an item list, not both: the path and the actions are
        // therefore one custom view, so the user sees where the folder is and what can be done.
        LinearLayout box = Ui.col(c);
        box.setPadding(Ui.dp(c, 20), Ui.dp(c, 16), Ui.dp(c, 20), Ui.dp(c, 4));
        TextView path = Ui.tv(c, dir.getAbsolutePath(), 12.5f, Ui.SUB, false);
        path.setTextIsSelectable(true);
        box.addView(path);
        box.addView(Ui.space(c, 0, Ui.dp(c, 8)));
        box.addView(Ui.tv(c, n + " 个文件 · "
                + (bytes < 1024 ? bytes + " B" : (bytes / 1024) + " KB"), 11.5f, Ui.SUB2, false));
        box.addView(Ui.tv(c, "预设存放在应用私有目录，系统文件管理器无法直接打开；用下面的入口导入或导出。",
                11.5f, Ui.SUB2, false));
        box.addView(Ui.space(c, 0, Ui.dp(c, 10)));
        final String[] actions = {"复制路径", "导入预设文件…", "导出预设到系统目录…", "查看文件…", "删除全部预设…"};
        for (int i = 0; i < actions.length; i++) {
            final int which = i;
            TextView row = Ui.tv(c, actions[i], 14.5f, which == actions.length - 1 ? 0xFFFF453A : Ui.TXT, false);
            row.setPadding(Ui.dp(c, 2), Ui.dp(c, 14), 0, Ui.dp(c, 14));
            row.setOnClickListener(v -> {
                if (which == 0) {
                    Object svc = c.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (svc instanceof android.content.ClipboardManager) {
                        ((android.content.ClipboardManager) svc).setPrimaryClip(
                                android.content.ClipData.newPlainText("预设目录", dir.getAbsolutePath()));
                        toast("已复制路径");
                    }
                } else if (which == 1) {
                    if (c instanceof com.vstplayer.MainActivity) ((com.vstplayer.MainActivity) c).pickPresets();
                    else toast("当前界面不支持导入");
                } else if (which == 2) {
                    if (c instanceof com.vstplayer.MainActivity) ((com.vstplayer.MainActivity) c).pickPresetExport();
                    else toast("当前界面不支持导出");
                } else if (which == 3) {
                    showPresetFiles(dir);
                } else {
                    new AlertDialog.Builder(c).setTitle("删除全部预设？")
                            .setMessage("只删除预设文件（" + n + " 个），不影响插件参数与渲染结果。")
                            .setPositiveButton("删除", (dd, ww) -> {
                                File[] all = dir.listFiles();
                                if (all != null) for (File f : all) f.delete();
                                loadedPreset = null;
                                refreshPresets();
                                toast("已删除");
                            })
                            .setNegativeButton("取消", null).show();
                }
            });
            box.addView(row);
        }
        new AlertDialog.Builder(c).setTitle("配置文件夹").setView(box)
                .setNegativeButton("关闭", null).show();
    }

    private void showPresetFiles(File dir) {
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) { toast("文件夹是空的"); return; }
        java.util.Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        final String[] names = new String[files.length];
        for (int i = 0; i < files.length; i++) {
            names[i] = files[i].getName() + "  ·  " + Math.max(1, files[i].length() / 1024) + " KB";
        }
        new AlertDialog.Builder(getContext()).setTitle("配置文件夹内容")
                .setItems(names, (d, w) -> toast(files[w].getName()))
                .setNegativeButton("关闭", null).show();
    }

    private void askSave() {
        Context c = getContext();
        EditText e = new EditText(c);
        e.setSingleLine(true);
        e.setHint("预设名称");
        e.setText("dearVR " + android.text.format.DateFormat.format("MMdd-HHmm", System.currentTimeMillis()));
        new AlertDialog.Builder(c)
                .setTitle("保存为新预设")
                .setView(e)
                .setPositiveButton("保存", (d, w) -> vst.savePreset(e.getText().toString(), (ok, msg) -> {
                    if (ok) { loadedPreset = msg; refreshPresets(); toast("已保存 " + msg); }
                    else toast("保存失败：" + msg);
                }))
                .setNegativeButton("取消", null)
                .show();
    }

    // -------------------------------------------------------------------- render

    /** True for containers the bundled renderer decodes itself (JUCE basic formats). */
    private static boolean rendererReads(String name) {
        String n = name.toLowerCase(java.util.Locale.US);
        int dot = n.lastIndexOf(0x2E);
        if (dot < 0) return false;
        String ext = n.substring(dot + 1);
        return ext.equals("wav") || ext.equals("wave") || ext.equals("flac")
                || ext.equals("ogg") || ext.equals("oga") || ext.equals("aif")
                || ext.equals("aiff") || ext.equals("aifc");
    }

    /** Formats the renderer understands directly, plus what the on-device decoder can turn into WAV. */
    private static boolean isAudio(String name) {
        String n = name.toLowerCase(java.util.Locale.US);
        int dot = n.lastIndexOf(0x2E);
        if (dot < 0) return false;
        String ext = n.substring(dot + 1);
        switch (ext) {
            case "wav": case "wave":
            case "flac": case "ogg": case "oga": case "opus":
            case "aif": case "aiff": case "aifc":
            case "mp3": case "m4a": case "mp4": case "aac": case "alac": case "caf":
                return true;
            default:
                return false;
        }
    }

    /**
     * Every renderable source: the real files still staged in {@code work/in} (temporary copies,
     * mostly empty now) plus every library reference. References show up as their virtual path, so
     * the selection set, the row labels and the batch bookkeeping stay file based.
     */
    private List<File> inputFiles() {
        List<File> out = new ArrayList<>();
        collectAudio(vst.inDir(), out, 0);
        out.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        Context c = getContext();
        if (c != null) {
            for (LibraryRef.Track t : LibraryRef.get(c.getApplicationContext()).tracks()) {
                out.add(LibraryRef.virtualFile(t));
            }
        }
        return out;
    }

    /** Walks imported sub-directories: an import keeps its folder structure, rendering does not care. */
    private static void collectAudio(File dir, List<File> out, int depth) {
        if (dir == null || depth > 8) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isFile()) {
                if (!f.getName().startsWith(".") && isAudio(f.getName())) out.add(f);
            } else if (f.isDirectory() && !f.getName().startsWith(".")) {
                collectAudio(f, out, depth + 1);
            }
        }
    }

    /** True when {@code f} sits anywhere below {@code dir}. */
    static boolean isUnder(File dir, File f) {
        if (dir == null || f == null) return false;
        File p = f.getParentFile();
        for (int guard = 0; p != null && guard < 32; guard++) {
            if (p.equals(dir)) return true;
            p = p.getParentFile();
        }
        return false;
    }

    /**
     * Folder name the guest can actually write into.
     *
     * <p>A non-ASCII name in the render command line makes the wine host fail to create the output
     * file ("ERR render"): the guest never sees the UTF-8 bytes we send. The user-visible name (the
     * SAF export folder) keeps the original characters, only the path inside the guest is folded to
     * printable ASCII, with a short hash so two Chinese playlist names cannot collide.
     */
    static String guestDirName(String raw) {
        String label = raw == null ? "" : raw.trim();
        StringBuilder sb = new StringBuilder();
        for (char ch : label.toCharArray()) {
            sb.append((ch > 0x20 && ch < 0x7F && ch != '/' && ch != '\\' && ch != ':' && ch != '*'
                    && ch != '?' && ch != '"' && ch != '<' && ch != '>' && ch != '|') ? ch : '_');
        }
        String s = sb.toString().trim().replaceAll("_+", "_");
        if (s.length() > 48) s = s.substring(0, 48);
        if (s.isEmpty() || s.equals("_")) s = "out";
        if (!s.equals(label)) {
            s = s + "-" + Integer.toHexString(label.hashCode() & 0x7fffffff);
        }
        return s;
    }

    /**
     * Renders exactly these files — the playlist "渲染整个歌单" entry.
     *
     * <p>Only files that still live in the source folder are queued: a playlist may mix rendered
     * output with imported audio, and re-rendering an already rendered WAV would process the plugin
     * twice. The skipped ones are reported rather than silently dropped.
     */
    public void renderFiles(List<File> files) {
        renderFiles(files, null);
    }

    /**
     * @param outLabel sub-directory of the output folder for this batch (a playlist renders into
     *                 "&lt;playlist&gt;_rendered"); null writes straight into work/out.
     */
    public void renderFiles(List<File> files, String outLabel) {
        if (files == null || files.isEmpty()) { toast("没有可渲染的源音频"); return; }
        File in = vst.inDir();
        List<File> usable = new ArrayList<>();
        int skipped = 0;
        for (File f : files) {
            if (f == null) continue;
            // A reference is staged into work/in right before its own prepare step, so it counts
            // as usable even though its virtual path has no file behind it yet.
            if (f.isFile() || LibraryRef.isVirtual(f)) usable.add(f);
            else skipped++;
        }
        if (usable.isEmpty()) {
            toast(skipped > 0 ? "歌单里没有源音频（已渲染的曲目会跳过）" : "没有可渲染的源音频");
            return;
        }
        selected.clear();
        selected.addAll(usable);
        refreshSourceRows();
        updateRenderBtn();
        if (skipped > 0) {
            toast("已选 " + usable.size() + " 个源音频，跳过 " + skipped + " 个已渲染文件");
        }
        batchOutLabel = outLabel;
        startRender();
    }

    /** Select exactly this file (coming from the library long-press flow). */
    public void useSource(File f) {
        if (f == null) return;
        if (!f.exists() && !LibraryRef.isVirtual(f)) return;
        selected.clear();
        selected.add(f);
        refreshSourceRows();
        updateRenderBtn();
    }

    /** Select exactly one library entry (a local file or a SAF reference). */
    public void useSource(Player.PlaybackSource s) {
        if (s != null) useSource(s.file);
    }

    /** Renders exactly these entries — local files and SAF references may be mixed. */
    public void renderSources(List<Player.PlaybackSource> sources, String outLabel) {
        if (sources == null || sources.isEmpty()) { toast("没有可渲染的源音频"); return; }
        List<File> files = new ArrayList<>();
        for (Player.PlaybackSource s : sources) {
            if (s != null && s.file != null) files.add(s.file);
        }
        renderFiles(files, outLabel);
    }

    public void refreshSources() {
        if (selected.isEmpty()) {
            List<File> files = inputFiles();
            if (!files.isEmpty()) selected.add(files.get(0));
        }
        refreshSourceRows();
        updateRenderBtn();
    }

    /**
     * The source list is a folder tree like the library: imports keep their folder structure, so a
     * flat list would mix albums together and lose the folder level entirely. Folders start closed;
     * the marker on a folder row selects or clears every track below it.
     */
    private void refreshSourceRows() {
        srcList.removeAllViews();
        srcRestyles.clear();
        final Context c = getContext();
        List<File> files = inputFiles();
        if (files.isEmpty()) {
            srcList.addView(Ui.tv(c, "还没有源音频 · 去「资料库」右上角 + 导入（只建立引用）", 12.5f, Ui.SUB2, false));
            return;
        }
        if (srcMode == 1) {
            refreshTagRows(files);
            return;
        }
        File in = vst.inDir();
        File[] entries = in == null ? null : in.listFiles();
        List<File> dirs = new ArrayList<>();
        List<File> roots = new ArrayList<>();
        if (entries != null) {
            for (File f : entries) {
                if (f.getName().startsWith(".")) continue;
                if (f.isDirectory()) dirs.add(f);
                else if (isAudio(f.getName())) roots.add(f);
            }
        }
        dirs.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        roots.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File d : dirs) addSourceFolder(d, 0);
        for (File f : roots) srcList.addView(sourceRow(f, 0));
        addLibraryRows();
    }

    /**
     * Library references in the address picker: one group per imported root, expanding to its
     * tracks. A reference is listed by its virtual path, so selecting it costs nothing until the
     * batch starts; the file itself is copied into {@code work/in} right before it is rendered.
     */
    private void addLibraryRows() {
        Context c = getContext();
        if (c == null) return;
        LibraryRef lib = LibraryRef.get(c.getApplicationContext());
        List<LibraryRef.Root> roots = lib.roots();
        if (roots.isEmpty()) return;
        TextView head = Ui.tv(c, "资料库引用（渲染时落盘到 work/in）", 11.5f, Ui.SUB2, false);
        head.setPadding(0, Ui.dp(c, 10), 0, Ui.dp(c, 6));
        srcList.addView(head);
        for (LibraryRef.Root root : roots) {
            final List<File> files = new ArrayList<>();
            for (LibraryRef.Track t : lib.tracksOf(root.id)) files.add(LibraryRef.virtualFile(t));
            if (files.isEmpty()) continue;
            final String key = "sroot:" + root.id;
            final boolean open = srcExpanded.contains(key);
            srcList.addView(sourceGroupRow(root.name, files.size() + " 首", files, open, 0,
                    () -> { toggleSrcGroup(key); refreshSourceRows(); },
                    () -> toggleSelection(files)));
            if (!open) continue;
            files.sort((a, b) -> a.getAbsolutePath().compareToIgnoreCase(b.getAbsolutePath()));
            for (File f : files) srcList.addView(sourceRow(f, 1));
        }
    }

    private TextView srcChip(Context c, String s) {
        TextView t = Ui.tv(c, s, 12.5f, Ui.SUB, false);
        t.setBackground(Ui.bg(Ui.CARD2, 14, c));
        t.setPadding(Ui.dp(c, 14), Ui.dp(c, 7), Ui.dp(c, 14), Ui.dp(c, 7));
        return t;
    }

    private void styleSrcModes() {
        if (srcModeAddr == null || srcModeTag == null) return;
        Context c = getContext();
        srcModeAddr.setTextColor(srcMode == 0 ? 0xFF000000 : Ui.SUB);
        srcModeAddr.setBackground(srcMode == 0 ? Ui.bg(Ui.ACC, 14, c) : Ui.bg(Ui.CARD2, 14, c));
        srcModeTag.setTextColor(srcMode == 1 ? 0xFF000000 : Ui.SUB);
        srcModeTag.setBackground(srcMode == 1 ? Ui.bg(Ui.ACC, 14, c) : Ui.bg(Ui.CARD2, 14, c));
    }

    /**
     * Picks the files to render by tag instead of by folder: artist → album → tracks. The marker on
     * a group selects or clears everything below it, which is how a whole album gets queued.
     *
     * <p>Only metadata that is already cached is used for this pass: the files still missing from
     * the cache are read on a background lane and the grouping is rebuilt once they are in, so a
     * hundred documents are never opened on the UI thread.
     */
    private void refreshTagRows(List<File> files) {
        final Context c = getContext();
        java.util.Map<String, java.util.Map<String, List<File>>> tree = new java.util.LinkedHashMap<>();
        LibraryRef refs = getContext() == null ? null
                : LibraryRef.get(getContext().getApplicationContext());
        final List<File> unread = new ArrayList<>();
        for (File f : files) {
            Tags.Info t = srcTags.get(f.getAbsolutePath());
            if (t == null) {
                // References: prefer the already warm cache so grouping stays off the SAF path.
                LibraryRef.Track track = refs != null && LibraryRef.isVirtual(f) ? refs.trackByVirtual(f) : null;
                t = track == null ? null : refs.cachedTags(track);
                if (t != null) srcTags.put(f.getAbsolutePath(), t);
            }
            if (t == null) {
                unread.add(f);
                t = new Tags.Info();   // grouped as "unknown" until the background read lands
            }
            String artist = t.albumArtist != null && !t.albumArtist.trim().isEmpty() ? t.albumArtist.trim()
                    : (t.artist != null && !t.artist.trim().isEmpty() ? t.artist.trim() : "未知艺术家");
            String album = t.album != null && !t.album.trim().isEmpty() ? t.album.trim() : "未知专辑";
            java.util.Map<String, List<File>> albums = tree.get(artist);
            if (albums == null) { albums = new java.util.LinkedHashMap<>(); tree.put(artist, albums); }
            List<File> fs = albums.get(album);
            if (fs == null) { fs = new ArrayList<>(); albums.put(album, fs); }
            fs.add(f);
        }
        List<String> artists = new ArrayList<>(tree.keySet());
        artists.sort(String::compareToIgnoreCase);
        for (String artist : artists) {
            java.util.Map<String, List<File>> albums = tree.get(artist);
            List<File> allOfArtist = new ArrayList<>();
            for (List<File> fs : albums.values()) allOfArtist.addAll(fs);
            String key = "sa:" + artist;
            boolean open = srcExpanded.contains(key);
            srcList.addView(sourceGroupRow(artist, albums.size() + " 张专辑 · " + allOfArtist.size() + " 首",
                    allOfArtist, open, 0,
                    () -> { toggleSrcGroup(key); refreshSourceRows(); },
                    () -> { toggleSelection(allOfArtist); }));
            if (!open) continue;
            List<String> names = new ArrayList<>(albums.keySet());
            names.sort(String::compareToIgnoreCase);
            for (String album : names) {
                List<File> fs = albums.get(album);
                fs.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
                String aKey = "sb:" + artist + "\u0001" + album;
                boolean aOpen = srcExpanded.contains(aKey);
                srcList.addView(sourceGroupRow("　" + album, fs.size() + " 首", fs, aOpen, 1,
                        () -> { toggleSrcGroup(aKey); refreshSourceRows(); },
                        () -> toggleSelection(fs)));
                if (!aOpen) continue;
                for (File f : fs) srcList.addView(sourceRow(f, 2));
            }
        }
        if (!unread.isEmpty()) readSrcTags(unread);
    }

    /**
     * Fills the tag cache for the files the grouping could not use yet, then rebuilds the picker
     * once. A newer pass (the user opened another folder) silently supersedes this one.
     */
    private void readSrcTags(final List<File> files) {
        final int gen = ++srcTagGen;
        final java.util.concurrent.ExecutorService lane = TAG_READ;
        lane.execute(() -> {
            for (File f : files) {
                if (gen != srcTagGen) return;
                Tags.Info t;
                try {
                    t = Tags.read(f);
                } catch (Throwable e) {
                    Diag.log("src tag read failed " + f.getName() + ": " + e);
                    t = new Tags.Info();
                }
                srcTags.put(f.getAbsolutePath(), t);
            }
            ui.post(() -> {
                if (gen != srcTagGen || srcMode != 1 || getContext() == null) return;
                refreshSourceRows();
            });
        });
    }

    private void toggleSrcGroup(String key) {
        if (!srcExpanded.remove(key)) srcExpanded.add(key);
    }

    /** Selects the whole group, or clears it when every file in it is already selected. */
    private void toggleSelection(List<File> group) {
        boolean all = true;
        for (File f : group) if (!selected.contains(f)) { all = false; break; }
        if (all) selected.removeAll(group);
        else selected.addAll(group);
        applySourceMarks();
        updateRenderBtn();
    }

    /** Restyles every built source row after the selection changed; no view is created or measured. */
    private void applySourceMarks() {
        for (int i = 0; i < srcRestyles.size(); i++) srcRestyles.get(i).run();
    }

    private View sourceGroupRow(String title, String meta, List<File> members, boolean open, int depth,
                                Runnable onToggle, Runnable onSelect) {
        final Context c = getContext();
        LinearLayout row = Ui.row(c);
        int pad = Ui.dp(c, 14) + Ui.dp(c, 14) * depth;
        row.setPadding(pad, Ui.dp(c, 11), Ui.dp(c, 14), Ui.dp(c, 11));
        FrameLayout mark = new FrameLayout(c);
        // The check mark always exists and is only shown/hidden, so a tick never needs a rebuild.
        Icon check = new Icon(c).name("check").tint(0xFF000000);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(Ui.dp(c, 14), Ui.dp(c, 14));
        ilp.gravity = Gravity.CENTER;
        mark.addView(check, ilp);
        mark.setOnClickListener(v -> onSelect.run());
        row.addView(mark, new LinearLayout.LayoutParams(Ui.dp(c, 22), Ui.dp(c, 22)));
        TextView name = Ui.tv(c, title, 14, Ui.SUB, false);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        nlp.leftMargin = Ui.dp(c, 12);
        row.addView(name, nlp);
        row.addView(Ui.tv(c, meta + " " + (open ? "⌄" : "›"), 11.5f, Ui.SUB2, false));
        row.setOnClickListener(v -> onToggle.run());
        addSrcRestyle(row, mark, check, name, members, true);
        return row;
    }

    private void addSourceFolder(final File dir, final int depth) {
        final Context c = getContext();
        final List<File> inside = Paths.audioFiles(dir);
        final boolean open = srcExpanded.contains(dir.getAbsolutePath());

        LinearLayout row = Ui.row(c);
        int pad = Ui.dp(c, 14) + Ui.dp(c, 14) * Math.min(depth, 6);
        row.setPadding(pad, Ui.dp(c, 11), Ui.dp(c, 14), Ui.dp(c, 11));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(c, 8);

        FrameLayout mark = new FrameLayout(c);
        Icon check = new Icon(c).name("check").tint(0xFF000000);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(Ui.dp(c, 14), Ui.dp(c, 14));
        ilp.gravity = Gravity.CENTER;
        mark.addView(check, ilp);
        mark.setOnClickListener(v -> toggleSelection(inside));
        row.addView(mark, new LinearLayout.LayoutParams(Ui.dp(c, 22), Ui.dp(c, 22)));

        TextView name = Ui.tv(c, dir.getName(), 14, Ui.SUB, false);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        nlp.leftMargin = Ui.dp(c, 12);
        row.addView(name, nlp);
        TextView meta = Ui.tv(c, inside.size() + " 首 " + (open ? "⌄" : "›"), 11.5f, Ui.SUB2, false);
        meta.setPadding(Ui.dp(c, 8), 0, 0, 0);
        row.addView(meta);
        row.setOnClickListener(v -> {
            if (!srcExpanded.remove(dir.getAbsolutePath())) srcExpanded.add(dir.getAbsolutePath());
            refreshSourceRows();
        });
        srcList.addView(row, lp);
        addSrcRestyle(row, mark, check, name, inside, false);
        if (!open) return;

        File[] arr = dir.listFiles();
        List<File> subs = new ArrayList<>();
        List<File> kids = new ArrayList<>();
        if (arr != null) {
            for (File f : arr) {
                if (f.getName().startsWith(".")) continue;
                if (f.isDirectory()) subs.add(f);
                else if (isAudio(f.getName())) kids.add(f);
            }
        }
        subs.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        kids.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File d : subs) addSourceFolder(d, depth + 1);
        for (File f : kids) srcList.addView(sourceRow(f, depth + 1));
    }

    private View sourceRow(final File f, int depth) {
        final Context c = getContext();
        LinearLayout row = Ui.row(c);
        int pad = Ui.dp(c, 14) + Ui.dp(c, 14) * Math.min(depth, 6);
        row.setPadding(pad, Ui.dp(c, 11), Ui.dp(c, 14), Ui.dp(c, 11));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(c, 8);
        FrameLayout mark = new FrameLayout(c);
        Icon check = new Icon(c).name("check").tint(0xFF000000);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(Ui.dp(c, 14), Ui.dp(c, 14));
        ilp.gravity = Gravity.CENTER;
        mark.addView(check, ilp);
        row.addView(mark, new LinearLayout.LayoutParams(Ui.dp(c, 22), Ui.dp(c, 22)));
        TextView name = Ui.tv(c, f.getName(), 14, Ui.SUB, false);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        nlp.leftMargin = Ui.dp(c, 12);
        row.addView(name, nlp);
        row.setOnClickListener(v -> {
            if (!selected.remove(f)) selected.add(f);
            applySourceMarks();
            updateRenderBtn();
        });
        addSrcRestyle(row, mark, check, name, java.util.Collections.singletonList(f), false);
        return row;
    }

    /**
     * Registers one source picker row for in-place restyling and applies its current state.
     *
     * <p>Ticking a file used to rebuild the whole picker: every row was dropped and recreated,
     * together with a recursive directory walk. The row keeps the two backgrounds and the marker
     * drawables it can switch between, so a tick only walks the rows that already exist.
     */
    private void addSrcRestyle(final LinearLayout row, final FrameLayout mark, final Icon check,
                               final TextView name, final List<File> members, final boolean group) {
        final Context c = getContext();
        final android.graphics.drawable.GradientDrawable bgSel =
                Ui.bg(Ui.withAlpha(Ui.ACC, 0.14f), 12, c);
        final android.graphics.drawable.GradientDrawable bgNorm = Ui.bg(Ui.CARD, 12, c);
        final android.graphics.drawable.GradientDrawable circleOn = Ui.circle(Ui.ACC, c);
        final android.graphics.drawable.GradientDrawable circleSome =
                group ? Ui.circle(Ui.withAlpha(Ui.ACC, 0.5f), c) : null;
        final android.graphics.drawable.GradientDrawable circleOff =
                Ui.circle(Ui.withAlpha(0xFFFFFFFF, 0.14f), c);
        Runnable restyle = () -> {
            int on = 0;
            for (int i = 0; i < members.size(); i++) if (selected.contains(members.get(i))) on++;
            boolean all = !members.isEmpty() && on == members.size();
            boolean some = on > 0 && !all;
            row.setBackground(all ? bgSel : bgNorm);
            mark.setBackground(all ? circleOn : (some && circleSome != null ? circleSome : circleOff));
            check.setVisibility(all ? VISIBLE : GONE);
            name.setTextColor(all ? Ui.TXT : Ui.SUB);
        };
        srcRestyles.add(restyle);
        restyle.run();
    }

    private void updateRenderBtn() {
        int n = selected.size();
        renderBtn.setText(n <= 1 ? "开始渲染" : "开始渲染 · " + n + " 个文件");
        renderBtn.setAlpha(n == 0 ? 0.4f : 1f);
    }

    public void refreshOutDir() {
        dirBtn.setText(Export.label(getContext()));
    }

    private void pickOutDir() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        if (getContext() instanceof Activity) {
            try {
                ((Activity) getContext()).startActivityForResult(i, Export.REQ_TREE);
            } catch (Exception e) {
                toast("没有可用的目录选择器");
            }
        }
    }

    // ----------------------------------------------------------------- render batch

    /** One render unit: a slice of a source file, or the whole file when it is not split. */
    private static final class Job {
        final int file, seg;
        int lane = -1;
        final File in, out, tempIn;

        Job(int file, int seg, File in, File out, File tempIn) {
            this.file = file;
            this.seg = seg;
            this.in = in;
            this.out = out;
            this.tempIn = tempIn;   // decoded intermediate to delete when the job ends
        }
    }

    /** All slices of one source file plus what is needed to join them back together. */
    private static final class FileJob {
        final int id;
        final File src, finalOut;
        final File[] outs;
        final int[] skips;
        final Job[] jobs;
        int done;
        boolean failed;

        FileJob(int id, File src, File finalOut, int n) {
            this.id = id;
            this.src = src;
            this.finalOut = finalOut;
            this.outs = new File[n];
            this.skips = new int[n];
            this.jobs = new Job[n];
        }

        boolean direct() {
            return outs.length == 1;
        }
    }
    // ----------------------------------------------------------------- render batch

    /**
     * A long file is worth splitting because one guest process only ever uses one core: a 20 minute
     * file measured 3:44 whole against ~2:00 with two lanes. Short files gain nothing and pay a
     * stitch, so they render in one piece. The join was verified on real 24 bit music at two seams
     * (one inside the quietest ambient passage): the stitched result differs from a single render
     * by -134 dBFS RMS, with no click, no envelope step, and frame exact boundaries.
     */
    private static final int AUTO_SPLIT_MIN_SECONDS = 600;
    private static final int DEFAULT_SEGMENT_SECONDS = 300;
    private static final int DEFAULT_PREROLL_SECONDS = 10;

    private int laneCount;
    private boolean[] laneBusy = new boolean[1];
    private boolean batchRunning;
    /** Set by the stop button: every queued/running job and the prepare pipeline give up. */
    private volatile boolean stopping;
    /** Handed to {@link Decode} so a pending decode stops at the next buffer instead of hours later. */
    private volatile boolean cancelPrep;
    private boolean[] laneSynced = new boolean[1];
    private boolean[] laneDead = new boolean[1];
    private final java.util.ArrayDeque<Integer> syncQueue = new java.util.ArrayDeque<>();
    private int syncBusy = -1;
    private float renderPct;
    private boolean selfTestDone, autorunDone, decodeOneDone;
    /** One entry per queued source file: a retried file keeps its original id so it is counted once. */
    private final java.util.Map<File, Integer> fileIds = new java.util.LinkedHashMap<>();
    private final java.util.Set<Integer> doneFileIds = new java.util.HashSet<>();
    /** Files that no prepared job was produced for (decode failed twice); reported at the end. */
    private int failedFiles;
    /** Decode retry rounds already used by this batch; the retry must not loop forever. */
    private int retryRound;
    /** Sub-directory of work/out for this batch (playlist renders), null for the plain folder. */
    private String batchOutLabel;
    private File batchOutDir;
    /** Reset the hosted plugin before every render so one file cannot bleed into the next. */
    private boolean resetPerFile = true;
    /** Folders / tag groups the user opened in the render pane's source picker. */
    private final java.util.Set<String> srcExpanded = new java.util.HashSet<>();
    /** Restyle hooks of the source picker rows currently built (see {@link #addSrcRestyle}). */
    private final List<Runnable> srcRestyles = new ArrayList<>();
    /**
     * Metadata lane for the "by tag" grouping. Never the render-prepare lane: a tag read must not
     * queue behind a decode, and a decode must not queue behind a hundred document reads.
     */
    private static final java.util.concurrent.ExecutorService TAG_READ =
            java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                Thread t = new Thread(runnable, "src-tags");
                t.setDaemon(true);
                return t;
            });
    /** 0 = pick sources by address (folder tree), 1 = pick them by tags (artist / album). */
    private int srcMode;
    private TextView srcModeAddr, srcModeTag;
    private final java.util.Map<String, Tags.Info> srcTags = new java.util.concurrent.ConcurrentHashMap<>();
    /** Generation of the background tag pass; a newer pass makes an older one stop. */
    private volatile int srcTagGen;
    // A file whose decode failed while every lane was rendering gets one more attempt once the
    // main work is done — the platform ALAC decoder dies under full load and recovers when idle.
    private final List<File> retryFiles = new ArrayList<>();
    private File curSegDir;
    private int curSegSec, curPreSec, nextFileId;
    /**
     * SAF references staged into {@code work/in} for the running batch, keyed by their virtual path.
     * The guest only ever reads local paths, so each referenced file is copied once per batch
     * (never twice) into a temporary directory and removed again as soon as its file is done.
     */
    private final java.util.Map<File, File> refLocals = new java.util.LinkedHashMap<>();
    /** {@code work/in/.refs/<batch>}, deleted when the batch ends. */
    private File refBatchDir;
    private android.os.PowerManager.WakeLock renderWake;

    private void startRender() {
        if (vst.state() != Vst.READY) { toast("引擎未就绪"); return; }
        if (selected.isEmpty()) { toast("请选择至少一个源音频"); return; }
        stopping = false;
        cancelPrep = false;
        if (stopBtn != null) stopBtn.setVisibility(VISIBLE);
        pending.clear();
        fileJobs.clear();
        fileIds.clear();
        doneFileIds.clear();
        totalFiles = selected.size();
        int assign = 0;
        for (File f : selected) fileIds.put(f, assign++);
        preparedFiles = totalSegments = doneSegments = finishedFiles = failedFiles = retryRound = 0;
        activeRenders = pendingStitches = 0;
        prepareDone = false;
        batchStart = Diag.now();
        progress.setValue(0f);
        renderPct = 0f;
        renderStatus.setText("准备渲染 · " + totalFiles + " 个文件");

        File dir = segDir();
        if (dir == null) dir = vst.outDir();   // slices need somewhere to live
        dir.mkdirs();
        final File segDir = dir;
        final int segSec = confInt("render.segment", DEFAULT_SEGMENT_SECONDS, 0, 3600);
        final int preSec = confInt("render.preroll", DEFAULT_PREROLL_SECONDS, 0, 120);
        curSegDir = dir;
        curSegSec = segSec;
        curPreSec = preSec;
        nextFileId = selected.size();
        retryFiles.clear();
        File base = vst.outDir();
        batchOutDir = base == null ? null : (batchOutLabel == null || batchOutLabel.isEmpty()
                ? base : new File(base, guestDirName(batchOutLabel)));
        if (batchOutDir != null) {
            batchOutDir.mkdirs();
            // The guest (a proot/wine process) is not the app's uid: every folder it has to write
            // into must be world writable, exactly like the work tree the environment unpacks.
            batchOutDir.setReadable(true, false);
            batchOutDir.setWritable(true, false);
            batchOutDir.setExecutable(true, false);
        }
        if (batchOutLabel != null) Diag.log("batch output: " + batchOutDir);
        laneCount = 1 + helperLanes();
        laneBusy = new boolean[laneCount];
        laneSynced = new boolean[laneCount];
        laneDead = new boolean[laneCount];
        laneSynced[0] = true;
        resetPerFile = confInt("render.reset", 1, 0, 1) == 1;
        syncQueue.clear();
        syncBusy = -1;   // the primary owns the live plugin state
        Diag.log("render batch: " + totalFiles + " files, lanes=" + laneCount
                + ", segment=" + segSec + "s preroll=" + preSec + "s");
        keepAlive(true, "准备渲染 " + totalFiles + " 个文件");
        for (int i = 1; i < laneCount; i++) {
            final int lane = i;
            vst.setOnLaneReady(lane, () -> {
                // Helpers share one wine prefix, so replaying 36 SET| commands into two guests at
                // the same instant wedges the wineserver; the syncs are therefore serialised.
                syncQueue.add(lane);
                nextLaneSync();
                // A guest that stops answering must not idle the batch: after this long its share
                // goes to the lanes that do answer.
                uiDelays.postDelayed(() -> {
                    if (!laneSynced[lane] && !laneDead[lane]) {
                        laneDead[lane] = true;
                        Diag.log("engine" + lane + " param sync timeout; lane dropped");
                        if (syncBusy == lane) { syncBusy = -1; }
                        syncQueue.remove(Integer.valueOf(lane));
                        nextLaneSync();
                        pump();
                    }
                }, 45000);
            });
            vst.startHelper(lane);
        }

        final Context app = getContext().getApplicationContext();
        final java.util.List<File> files = new java.util.ArrayList<>(selected);
        for (int i = 0; i < files.size(); i++) {
            final int id = i;
            final File f = files.get(i);
            PREP.execute(() -> {
                FileJob fj = null;
                String err = null;
                try {
                    // A reference is materialised here, on the prep thread: the guest only reads
                    // local paths, and the copy is removed as soon as this file is done.
                    fj = plan(id, localize(f), segDir, segSec, preSec, app);
                } catch (Exception e) {
                    Diag.log("prepare failed " + f.getName() + ": " + e);
                    err = e.getMessage() == null ? e.toString() : e.getMessage();
                }
                final FileJob res = fj;
                final String bad = err;
                ui.post(() -> onPrepared(res, bad, f));
            });
        }
    }

    /**
     * Extra render lanes (helpers), i.e. lanes - 1.
     *
     * <p>Every lane is another wine guest (~400-600 MB) pinned to its own core, so a playlist batch
     * can drive the whole SoC: 8 cores → up to 8 lanes. The cap is what the device can actually
     * hold right now — an OOM kill loses the batch, so free memory decides, not the core count.
     */
    private int helperLanes() {
        int cfg = confInt("render.engines", 0, 0, 8);
        if (cfg > 0) {
            int lanes = Math.max(1, Math.min(8, cfg));
            Diag.log("lane probe: boot.conf render.engines=" + cfg + " -> lanes=" + lanes);
            return lanes - 1;
        }
        return autoLanes() - 1;
    }

    /** Lanes chosen from the cores present and the memory that is free right now. */
    private int autoLanes() {
        try {
            android.app.ActivityManager am = (android.app.ActivityManager)
                    getContext().getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return 2;
            android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            boolean low = am.isLowRamDevice();
            int cores = Runtime.getRuntime().availableProcessors();
            long perLane = 640L * 1024 * 1024;
            int byMem = (int) Math.max(1, mi.availMem / perLane);
            int lanes = Math.min(Math.min(Math.max(1, cores), 8), byMem);
            if (low) lanes = Math.min(lanes, 2);
            if (mi.availMem >= 900L * 1024 * 1024) lanes = Math.max(2, lanes);
            lanes = Math.max(1, Math.min(8, lanes));
            Diag.log("lane probe: cores=" + cores + " totalMem=" + (mi.totalMem >> 20) + "MB avail="
                    + (mi.availMem >> 20) + "MB lowRam=" + low + " -> lanes=" + lanes);
            return lanes;
        } catch (Throwable t) {
            Diag.log("lane probe failed: " + t);
            return 2;
        }
    }

    /**
     * Stops the running batch.
     *
     * <p>The Windows host has no abort command: a render that is already inside the plugin can only
     * be stopped by ending that guest process, so the primary engine and every helper are killed
     * and booted again afterwards. Queued slices are dropped, the pending decode returns at its next
     * buffer, temporary slices are removed, and finished files stay on disk.
     */
    private void stopRender() {
        if (!batchRunning || stopping) return;
        stopping = true;
        cancelPrep = true;
        Diag.log("render stop: files=" + finishedFiles + "/" + totalFiles
                + " slices=" + doneSegments + "/" + totalSegments);
        pending.clear();
        fileJobs.clear();
        for (int lane = 1; lane < laneCount; lane++) vst.setOnLaneReady(lane, null);
        syncQueue.clear();
        syncBusy = -1;
        laneCount = 0;
        activeRenders = 0;
        pendingStitches = 0;
        keepAlive(false, null);
        vst.stopEngine();
        renderPct = 0f;
        progress.setValue(0f);
        if (stopBtn != null) stopBtn.setVisibility(GONE);
        File dir = segDir();
        File[] left = dir == null ? null : dir.listFiles();
        if (left != null) for (File f : left) f.delete();
        cleanupAllRefs();
        setStatusProgress("已停止 · 完成 " + finishedFiles + "/" + totalFiles);
        batchOutLabel = null;
        batchOutDir = null;
        refreshOutDir();
        final Context c = getContext();
        if (c != null) {
            final Context app = c.getApplicationContext();
            // Warm the engine again so parameter edits and the next batch do not need a relaunch.
            uiDelays.postDelayed(() -> {
                if (getContext() != null && !batchRunning) vst.restart(app, null);
            }, 700);
        }
    }

    /**
     * Keeps the batch runnable outside the foreground: a foreground service stops the system from
     * freezing the process, and a partial wake lock stops the CPU from sleeping with the screen off.
     * Without both, rendering pauses as soon as the user switches apps or locks the phone.
     */
    private void keepAlive(boolean on, String text) {
        Context c = getContext();
        if (c == null) return;
        Context app = c.getApplicationContext();
        if (!on) {
            batchRunning = false;
            EngineService.stop(app);
            if (renderWake != null) {
                try {
                    if (renderWake.isHeld()) renderWake.release();
                } catch (Throwable ignored) {
                }
                renderWake = null;
            }
            return;
        }
        batchRunning = true;
        EngineService.start(app, text);
        if (renderWake == null) {
            try {
                android.os.PowerManager pm = (android.os.PowerManager)
                        app.getSystemService(Context.POWER_SERVICE);
                renderWake = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "vstplayer:render");
                renderWake.setReferenceCounted(false);
                renderWake.acquire(6 * 3600 * 1000L);
                Diag.log("render wake lock held");
            } catch (Throwable t) {
                Diag.log("wake lock failed: " + t);
            }
        }
    }

    /** Runs the queued helper syncs one at a time; each finishes on the main thread. */
    private void nextLaneSync() {
        if (syncBusy >= 0) return;
        final Integer lane = syncQueue.poll();
        if (lane == null) return;
        syncBusy = lane;
        vst.syncParamsTo(lane, () -> {
            if (lane < laneSynced.length) laneSynced[lane] = true;
            syncBusy = -1;
            nextLaneSync();
            pump();
        });
    }

    /** Status line, mirrored into the foreground notification while a batch runs. */
    private void setStatusProgress(String text) {
        renderStatus.setText(text);
        if (!batchRunning) return;
        Context c = getContext();
        if (c != null) {
            EngineService.update(c.getApplicationContext(), text, (int) (renderPct * 100f));
        }
    }

    // ------------------------------------------------------ reference staging

    /**
     * Returns a local path the guest can open for {@code src}.
     *
     * <p>A real file is returned unchanged. A library reference is streamed into
     * {@code work/in/.refs/<batch>/<n>/<original name>} in 64 KB blocks (the original name is kept,
     * so the rendered output keeps its name and the guest never sees a temp name). The staging
     * directory is world readable for the guest process, and every copy is deleted when its file
     * finishes (or when the batch ends / is stopped).
     */
    private File localize(File src) throws java.io.IOException {
        if (src == null) return null;
        if (src.exists()) return src;
        if (stopping || cancelPrep) throw new java.io.IOException("已取消");
        Context c = getContext();
        if (c == null) throw new java.io.IOException("没有上下文，无法落盘引用");
        LibraryRef.Track track = LibraryRef.get(c.getApplicationContext()).trackByVirtual(src);
        if (track == null) throw new java.io.IOException("不是本地文件，也不是资料库引用：" + src.getName());
        File done = refLocals.get(src);
        if (done != null && done.isFile()) return done;      // same file twice in one batch
        File base = vst.inDir();
        if (base == null) throw new java.io.IOException("工作目录不可用");
        if (refBatchDir == null) {
            refBatchDir = new File(base, ".refs/" + Long.toHexString(System.currentTimeMillis()));
        }
        File dir = new File(refBatchDir, String.valueOf(refLocals.size()));
        if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("无法创建 " + dir);
        // ASCII only: the guest opens this path, and it decodes our command bytes as Latin-1.
        File out = new File(dir, "ref" + refLocals.size() + extOf(src.getName()));
        long bytes = copyTo(out, android.net.Uri.parse(track.uri));
        refLocals.put(src, out);
        Diag.log("ref staged " + track.rel + " -> " + out + " " + bytes + "B");
        return out;
    }

    /** 64 KB streaming copy of a SAF document onto a local path. */
    private long copyTo(File dest, android.net.Uri uri) throws java.io.IOException {
        Context c = getContext();
        if (c == null) throw new java.io.IOException("no context");
        long total = 0;
        try (java.io.InputStream in = c.getContentResolver().openInputStream(uri);
             java.io.FileOutputStream out = new java.io.FileOutputStream(dest)) {
            if (in == null) throw new java.io.IOException("openInputStream returned null");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (stopping || cancelPrep) throw new java.io.IOException("已取消");
                if (n == 0) continue;
                out.write(buf, 0, n);
                total += n;
            }
            out.flush();
        } catch (java.io.IOException e) {
            dest.delete();
            throw e;
        }
        dest.setReadable(true, false);
        return total;
    }

    /** Deletes the staged copy of one entry (called when that file is finished or failed). */
    private void cleanupRef(File localOrVirtual) {
        if (localOrVirtual == null || refLocals.isEmpty()) return;
        java.util.Iterator<java.util.Map.Entry<File, File>> it = refLocals.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<File, File> e = it.next();
            File copy = e.getValue();
            if (!e.getKey().equals(localOrVirtual) && !copy.equals(localOrVirtual)) continue;
            it.remove();
            copy.delete();
            File dir = copy.getParentFile();
            if (dir != null) dir.delete();          // only removes it when empty
        }
    }

    /** Drops every staged reference of the batch; safe to call twice. */
    private void cleanupAllRefs() {
        for (File copy : refLocals.values()) {
            if (copy != null) copy.delete();
            File dir = copy == null ? null : copy.getParentFile();
            if (dir != null) dir.delete();
        }
        refLocals.clear();
        File batch = refBatchDir;
        refBatchDir = null;
        if (batch != null) deleteTree(batch);
        // work/in stays (it is the staging folder), only our temporary tree goes.
        File refsRoot = vst.inDir() == null ? null : new File(vst.inDir(), ".refs");
        if (refsRoot != null) deleteTree(refsRoot);
    }

    private static void deleteTree(File dir) {
        if (dir == null) return;
        File[] arr = dir.listFiles();
        if (arr != null) for (File f : arr) if (f.isDirectory()) deleteTree(f); else f.delete();
        dir.delete();
    }

    /** True when every character is plain ASCII, i.e. a path the guest is able to open. */
    private static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) > 0x7F) return false;
        return true;
    }

    /** Extension of a name when it is ASCII, otherwise nothing (the reader sniffs the content). */
    private static String extOf(String name) {
        int dot = name.lastIndexOf(0x2E);
        if (dot <= 0 || name.length() - dot > 8) return "";
        String ext = name.substring(dot);
        return isAscii(ext) ? ext : "";
    }

    /**
     * Renames a single piece render from its ASCII work name onto the name the user sees.
     *
     * <p>The host cannot create files under a non-ASCII path, so an unsplit render writes
     * {@code o<id>_<nanos>.wav} and gets its display name here. Returns the file to export: the
     * guest copy if the rename fails, because the audio itself is already correct.
     */
    private File promote(FileJob fj) {
        File guest = fj.outs[0];
        if (guest == null || guest.equals(fj.finalOut) || !guest.isFile()) return fj.finalOut;
        if (fj.finalOut.exists() && !fj.finalOut.delete()) {
            Diag.log("promote: cannot replace " + fj.finalOut.getName()
                    + ", exporting " + guest.getName());
            return guest;
        }
        if (guest.renameTo(fj.finalOut)) return fj.finalOut;
        Diag.log("promote failed: " + guest.getName() + " -> " + fj.finalOut.getName());
        return guest;
    }

    /** Decodes and slices one source file on the prep thread; returns null when it is unusable. */
    private FileJob plan(int id, File src, File segDir, int segSec, int preSec, Context app)
            throws Exception {
        long t0 = Diag.now();
        String name = src.getName();
        boolean isWavSrc = Segment.isWav(src);
        boolean nativeFormat = isWavSrc || rendererReads(name);

        // Slicing needs PCM at the source depth. The platform FLAC decoder always hands back
        // 16 bit, so a deeper FLAC is never sliced: it keeps its depth and renders in one piece.
        int flacBits = Segment.isFlac(src) ? Segment.flacBits(src) : 0;
        boolean maySplit = segSec > 0 && flacBits <= 16;
        if (segSec > 0 && flacBits > 16) {
            Diag.log("no split for " + name + ": flac " + flacBits + "bit would decode to 16bit");
        }

        File pcm = src;
        boolean decoded = false;
        if (!isWavSrc && (!nativeFormat || maySplit)) {
            File temp = new File(segDir, "dec" + id + "_" + System.nanoTime() + ".wav");
            Decode.Result res = Decode.decode(app, src, temp, () -> stopping || cancelPrep);
            File made = res.wav;
            if (made == null) {
                if (temp.exists()) temp.delete();
                if (stopping || cancelPrep) throw new java.io.IOException("已取消");
                if (!nativeFormat) {
                    throw new java.io.IOException("无法解码 " + name + "：" + res.error);
                }
                // The renderer reads this container itself, so a failed decode only costs the
                // split: fall back to one piece rather than failing the file.
                Diag.log("decode failed for " + name + ", rendering the original in one piece");
                maySplit = false;
            } else {
                pcm = made;
                decoded = true;
            }
        }

        // A native container the renderer reads itself (flac/ogg/aiff) has no PCM header to read;
        // it renders in one piece without probing, which is also why it keeps its depth.
        Segment.Info in = Segment.isWav(pcm) ? Segment.probe(pcm) : null;
        int dot = name.lastIndexOf(0x2E);
        String base = dot > 0 ? name.substring(0, dot) : name;
        File outDir = batchOutDir != null ? batchOutDir : vst.outDir();
        File finalOut = new File(outDir, base + "_vst.wav");
        long total = in == null ? -1 : in.frames;
        long segFrames = in != null && segSec > 0 ? (long) segSec * in.rate : 0;
        boolean split = in != null && maySplit && segFrames > 0
                && total >= (long) AUTO_SPLIT_MIN_SECONDS * Math.max(1, in.rate);
        int n = split ? (int) ((total + segFrames - 1) / segFrames) : 1;
        long pre = in != null && preSec > 0 ? (long) preSec * in.rate : 0;
        FileJob fj = new FileJob(id, src, finalOut, n);
        if (n == 1) {
            File input = src;
            File tempIn = null;
            if (!nativeFormat) {
                input = pcm;
                tempIn = pcm;
            } else if (decoded) {
                pcm.delete();      // only measured it; the renderer reads the original
            }
            // The host parses our UTF-8 command line as Latin-1, so every path it opens has to be
            // pure ASCII: a Chinese source name or folder arrives as mojibake and the render dies
            // within milliseconds ("cannot read" / "cannot create output"). Stage a plain copy.
            if (nativeFormat && !isAscii(input.getAbsolutePath())) {
                File staged = new File(segDir,
                        "in" + id + "_" + Long.toHexString(System.nanoTime()) + extOf(name));
                Io.copy(new java.io.FileInputStream(input), new java.io.FileOutputStream(staged));
                Diag.log("staged non-ascii source " + name + " -> " + staged.getName());
                input = staged;
                tempIn = staged;
            }
            // Same for the output: the guest creates it, so it writes an ASCII name inside the work
            // tree and promote() renames it to the display name on this side.
            File guestOut = new File(segDir,
                    "o" + id + "_" + Long.toHexString(System.nanoTime()) + ".wav");
            fj.outs[0] = guestOut;
            fj.skips[0] = 0;
            fj.jobs[0] = new Job(id, 0, input, guestOut, tempIn);
        } else {
            for (int s = 0; s < n; s++) {
                long start = s * segFrames;
                long end = Math.min(total, start + segFrames);
                long from = Math.max(0, start - pre);
                File slice = new File(segDir, "seg" + id + "_" + s + ".wav");
                Segment.cut(pcm, in, from, end - from, slice);
                File out = new File(segDir, "seg" + id + "_" + s + "_r.wav");
                fj.outs[s] = out;
                fj.skips[s] = (int) Math.min(Integer.MAX_VALUE, start - from);
                fj.jobs[s] = new Job(id, s, slice, out, null);
            }
            if (decoded) pcm.delete();
        }
        Diag.log("prepared " + name + ": "
                + (in == null ? "native container" : in.channels + "ch/" + in.bits + "bit/" + in.rate
                        + "Hz, " + String.format(java.util.Locale.US, "%.1f", in.seconds()) + "s")
                + " -> " + n + " slice(s)" + (flacBits > 0 ? " flac=" + flacBits + "bit" : "")
                + " +" + (Diag.now() - t0) + "ms");
        return fj;
    }

    private void onPrepared(FileJob fj, String error, File src) {
        if (stopping) return;
        preparedFiles++;
        if (fj == null) {
            markFileDone(idOf(src));
            failedFiles++;
            cleanupRef(src);
            setStatusProgress("失败：" + (error == null ? "无法准备源音频" : error));
            if (retryRound == 0 && src != null && error != null && error.contains("解码")) {
                retryFiles.add(src);
            }
        } else {
            totalSegments += fj.outs.length;
            fileJobs.put(fj.id, fj);
            for (Job j : fj.jobs) if (j != null) pending.add(j);
        }
        if (preparedFiles == totalFiles) prepareDone = true;
        pump();
    }

    /**
     * Hands queued slices to every idle lane. With slicing, one long file can keep all lanes busy,
     * so the lane count no longer maps one-to-one onto files.
     */
    private void pump() {
        if (stopping || !batchRunning) return;
        if (laneCount <= 0) laneCount = 1;
        while (activeRenders < laneCount && !pending.isEmpty()) {
            int lane = -1;
            boolean booting = false;
            for (int i = 0; i < laneCount; i++) {
                if (laneBusy[i] || laneDead[i]) continue;
                if (vst.isReady(i) && laneSynced[i]) {
                    lane = i;
                    break;
                }
                if (vst.isStarting(i)) {
                    booting = true;
                    break;
                }
            }
            if (lane < 0) {
                if (booting || laneBusy[0]) break;
                lane = 0;
            }
            final Job job = pending.remove(0);
            final int laneIdx = lane;
            FileJob fj = fileJobs.get(job.file);
            job.lane = laneIdx;
            laneBusy[lane] = true;
            activeRenders++;
            String label = fj == null ? job.in.getName() : fj.src.getName();
            setStatusProgress(totalSegments > 1
                    ? "分段 " + Math.min(totalSegments, doneSegments + 1) + "/" + totalSegments + " · " + label
                    : "渲染中 · " + label);
            if (resetPerFile) {
                // Reset, then render: a failed reset (old host build) still renders, it just keeps
                // the plugin's state from the previous file.
                vst.resetLane(laneIdx, (resetOk, resetMsg) -> {
                    if (!resetOk) Diag.log("RESET unsupported on lane " + laneIdx + ": " + resetMsg);
                    vst.renderLane(laneIdx, job.in, job.out, (ok, msg) -> onJobDone(job, ok, msg));
                });
            } else {
                vst.renderLane(laneIdx, job.in, job.out, (ok, msg) -> onJobDone(job, ok, msg));
            }
        }
        checkBatchDone();
    }

    private void onJobDone(Job job, boolean ok, String msg) {
        if (stopping) return;
        activeRenders = Math.max(0, activeRenders - 1);
        if (job.lane >= 0 && job.lane < laneBusy.length) laneBusy[job.lane] = false;
        if (job.tempIn != null) job.tempIn.delete();
        FileJob fj = fileJobs.get(job.file);
        doneSegments++;
        if (totalSegments > 0) progress.setValue((float) doneSegments / totalSegments);
        if (fj != null) {
            if (!ok) fj.failed = true;
            fj.done++;
            if (fj.failed) {
                if (fj.done >= fj.outs.length) {
                    cleanupSlices(fj);
                    markFileDone(fj.id);
                    cleanupRef(fj.src);
                    failedFiles++;
                    setStatusProgress("失败：" + msg);
                }
            } else if (fj.direct()) {
                markFileDone(fj.id);
                cleanupRef(fj.src);
                if (ok) exportThenNext(promote(fj));
                else failedFiles++;   // a whole file that never rendered is a lost file
            } else if (fj.done >= fj.outs.length) {
                stitch(fj);
            }
        }
        pump();
    }

    private void cleanupSlices(FileJob fj) {
        for (File f : fj.outs) if (f != null && f != fj.finalOut) f.delete();
    }

    /** Stable per-batch id for a source file; a retried file gets its original id back. */
    private int idOf(File f) {
        Integer id = f == null ? null : fileIds.get(f);
        if (id == null) {
            id = nextFileId++;
            if (f != null) fileIds.put(f, id);
        }
        return id;
    }

    /**
     * Counts one source file as finished exactly once.
     *
     * <p>The decode retry pass re-plans files that already counted as failed; without this the
     * counter would run past the batch total ("完成 9/9" for eight files) which is what the screen
     * and diag.log used to show.
     */
    private void markFileDone(int id) {
        if (doneFileIds.add(id)) finishedFiles++;
    }

    /** Joins the rendered slices (dropping each pre-roll) on the export thread. */
    private void stitch(final FileJob fj) {
        pendingStitches++;
        setStatusProgress("拼接 · " + fj.src.getName());
        final java.lang.ref.WeakReference<VstView> self = new java.lang.ref.WeakReference<>(this);
        EXPORT.execute(() -> {
            String err = null;
            long t0 = Diag.now();
            try {
                Segment.join(fj.outs, fj.skips, fj.finalOut);
                Diag.log("joined " + fj.outs.length + " slices -> " + fj.finalOut.getName()
                        + " +" + (Diag.now() - t0) + "ms");
            } catch (Exception e) {
                Diag.log("join failed: " + e);
                err = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            cleanupSlices(fj);
            final String bad = err;
            ui.post(() -> {
                VstView v = self.get();
                if (v == null || v.stopping) return;
                v.pendingStitches = Math.max(0, v.pendingStitches - 1);
                v.markFileDone(fj.id);
                v.cleanupRef(fj.src);
                if (bad != null) {
                    v.setStatusProgress("拼接失败：" + bad);
                    Toast.makeText(v.getContext(), "拼接失败：" + bad, Toast.LENGTH_LONG).show();
                } else {
                    v.exportThenNext(fj.finalOut);
                }
                v.checkBatchDone();
            });
        });
    }

    private void checkBatchDone() {
        if (!batchRunning) return;
        if (!prepareDone || !pending.isEmpty() || activeRenders > 0 || pendingStitches > 0) return;
        if (finishedFiles < totalFiles) return;
        if (!retryFiles.isEmpty()) { startRetryPass(); return; }
        renderPct = 1f;
        progress.setValue(1f);
        int okFiles = Math.max(0, totalFiles - failedFiles);
        Diag.log("batch done: files=" + totalFiles + " ok=" + okFiles + " failed=" + failedFiles
                + " slices=" + totalSegments + " lanes=" + laneCount
                + " total +" + (Diag.now() - batchStart) + "ms");
        setStatusProgress(failedFiles == 0
                ? "全部完成 · 共 " + totalFiles + " 个"
                : "完成 " + okFiles + "/" + totalFiles + " 个，失败 " + failedFiles);
        batchOutLabel = null;
        batchOutDir = null;
        keepAlive(false, null);
        for (int lane = 1; lane < laneCount; lane++) vst.setOnLaneReady(lane, null);
        vst.stopHelpers();
        laneCount = 0;
        if (stopBtn != null) stopBtn.setVisibility(GONE);
        File dir = segDir();
        File[] left = dir == null ? null : dir.listFiles();
        if (left != null) for (File f : left) f.delete();
        cleanupAllRefs();
    }


    /**
     * Second preparation round for files whose decode died while the whole SoC was rendering.
     *
     * <p>Full-load runs get the platform codec service killed (native crash in media.swcodec), which
     * shows up as an app-level IllegalStateException; the same file decodes fine when the machine is
     * quiet, so those files are decoded again here instead of being reported as unreadable.
     */
    private void startRetryPass() {
        final List<File> again = new ArrayList<>(retryFiles);
        retryFiles.clear();
        if (again.isEmpty() || stopping) return;
        retryRound++;
        Diag.log("retry pass: " + again.size() + " file(s) whose decode failed under load");
        setStatusProgress("重试解码 " + again.size() + " 个文件");
        // prepareDone re-arms only when preparedFiles reaches totalFiles again, so the retried files
        // are discounted first: without this the batch never completes after one decode failure.
        preparedFiles = Math.max(0, totalFiles - again.size());
        // Their earlier failure is being re-examined, so it must not be counted twice.
        failedFiles = Math.max(0, failedFiles - again.size());
        prepareDone = false;
        final Context app = getContext() == null ? null : getContext().getApplicationContext();
        if (app == null) return;
        for (File f : again) {
            final File src = f;
            final int id = idOf(src);
            PREP.execute(() -> {
                FileJob fj = null;
                String err = null;
                try {
                    fj = plan(id, localize(src), curSegDir, curSegSec, curPreSec, app);
                } catch (Exception e) {
                    Diag.log("prepare retry failed " + src.getName() + ": " + e);
                    err = e.getMessage() == null ? e.toString() : e.getMessage();
                }
                final FileJob res = fj;
                final String bad = err;
                ui.post(() -> onPrepared(res, bad, src));
            });
        }
    }

    /** Slice workspace, next to in/ and out/ inside the guest tree. */
    private File segDir() {
        File in = vst.inDir();
        if (in == null) return null;
        File work = in.getParentFile();
        return work == null ? null : new File(work, "seg");
    }

    /** Debug-only string value from files/boot.conf, or null when absent/not debuggable. */
    private String confRaw(String key) {
        try {
            Context c = getContext();
            int flags = c.getApplicationInfo().flags;
            if ((flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) return null;
            File f = new File(c.getFilesDir(), "boot.conf");
            if (!f.isFile()) return null;
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    int eq = line.indexOf('=');
                    if (eq <= 0) continue;
                    if (line.substring(0, eq).trim().equals(key)) return line.substring(eq + 1).trim();
                }
            } finally {
                r.close();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Debug-only knob from files/boot.conf; release builds always use the default. */
    private int confInt(String key, int def, int lo, int hi) {
        try {
            Context c = getContext();
            int flags = c.getApplicationInfo().flags;
            if ((flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) return def;
            File f = new File(c.getFilesDir(), "boot.conf");
            if (!f.isFile()) return def;
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    int eq = line.indexOf('=');
                    if (eq <= 0) continue;
                    if (!line.substring(0, eq).trim().equals(key)) continue;
                    int v = Integer.parseInt(line.substring(eq + 1).trim());
                    return Math.max(lo, Math.min(hi, v));
                }
            } finally {
                r.close();
            }
        } catch (Exception ignored) {
        }
        return def;
    }

    /** Copies the finished render into the picked SAF folder without blocking the UI thread. */
    private void exportThenNext(final File out) {
        final Context ctx = getContext().getApplicationContext();
        if (Export.tree(ctx) == null) {
            checkBatchDone();
            return;
        }
        final String name = out.getName();
        renderStatus.setText("导出中 · " + name);
        final java.lang.ref.WeakReference<VstView> self = new java.lang.ref.WeakReference<>(this);
        EXPORT.execute(() -> {
            String error = null;
            try {
                Export.copy(ctx, out, name, batchOutLabel);
                // The user picked a system folder: keep exactly one copy. Dropping the app-private
                // file here is what turns the export into "the" output instead of a second one.
                final File parent = out.getParentFile();
                if (out.delete()) Diag.log("exported and removed local copy: " + name);
                if (parent != null && parent.getName().endsWith("_rendered")) {
                    File[] left = parent.listFiles();
                    if (left != null && left.length == 0) parent.delete();
                }
            } catch (Exception e) {
                Diag.log("export failed: " + e);
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            final String failure = error;
            ui.post(() -> {
                VstView v = self.get();
                if (v == null || v.stopping) return;
                if (failure != null) {
                    v.renderStatus.setText("导出失败：" + failure);
                    Toast.makeText(v.getContext(), "导出失败：" + failure, Toast.LENGTH_LONG).show();
                }
                v.checkBatchDone();
            });
        });
    }

    @Override protected void onDetachedFromWindow() {
        // A 45 s lane watchdog must not keep a destroyed page (and its Activity context) alive.
        uiDelays.removeCallbacksAndMessages(null);
        super.onDetachedFromWindow();
    }

    // ------------------------------------------------------------------- helpers

    private TextView primary(Context c, String s, OnClickListener l) {
        TextView t = Ui.tv(c, s, 15, 0xFF000000, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(Ui.bg(Ui.ACC, 12, c));
        t.setPadding(0, Ui.dp(c, 13), 0, Ui.dp(c, 13));
        t.setOnClickListener(l);
        return t;
    }

    private TextView secondary(Context c, String s, OnClickListener l) {
        TextView t = Ui.tv(c, s, 14.5f, Ui.TXT, false);
        t.setGravity(Gravity.CENTER);
        t.setBackground(Ui.bg(Ui.CARD, 12, c));
        t.setPadding(Ui.dp(c, 14), Ui.dp(c, 12), Ui.dp(c, 14), Ui.dp(c, 12));
        t.setMaxLines(1);
        t.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        t.setOnClickListener(l);
        return t;
    }

    private TextView label(Context c, String s) {
        TextView t = Ui.tv(c, s, 12, Ui.SUB, true);
        t.setPadding(Ui.dp(c, 2), 0, 0, Ui.dp(c, 6));
        return t;
    }

    private void toast(String s) {
        Toast.makeText(getContext(), s, Toast.LENGTH_SHORT).show();
    }
}
