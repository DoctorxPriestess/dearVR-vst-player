package com.vstplayer;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.vstplayer.core.App;
import com.vstplayer.core.BootConf;
import com.vstplayer.core.Diag;
import com.vstplayer.core.Export;
import com.vstplayer.core.LibraryRef;
import com.vstplayer.core.Player;
import com.vstplayer.core.Playlists;
import com.vstplayer.core.Vst;
import com.vstplayer.ui.DetailPage;
import com.vstplayer.ui.HomeView;
import com.vstplayer.ui.Icon;
import com.vstplayer.ui.LibraryView;
import com.vstplayer.ui.PlaylistPage;
import com.vstplayer.ui.Ui;
import com.vstplayer.ui.VstView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** App shell: three tabs, a full screen detail page and the playlist editor. */
public class MainActivity extends Activity implements LibraryView.Host {

    private static final int REQ_IMPORT_FILES = 43;
    private static final int REQ_IMPORT_TREE = 44;
    private static final int REQ_PRESET_IMPORT = 45;
    private static final int REQ_PRESET_EXPORT = 46;

    /** Receives the references an import actually added to the library index. */
    public interface ImportSink { void onImported(List<Player.PlaybackSource> added); }

    private ImportSink importSink;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private FrameLayout content;
    private LinearLayout nav;
    private FrameLayout overlay;
    private HomeView home;
    private LibraryView library;
    private VstView vstView;
    private DetailPage detail;
    private boolean detailOpen;
    private ValueAnimator detailAnim;
    private final Rect coverWin = new Rect();
    private PlaylistPage playlistPage;
    private View bootOverlay;
    private TextView overlayText;
    private int tab = -1;
    private int navInset = -1;

    // getSystemWindowInset*() is deprecated in favour of WindowInsetsCompat, which would pull in
    // androidx.core; minSdk 24 makes the old accessors the portable choice.
    @SuppressWarnings("deprecation")
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Diag.init(this);
        Diag.log("shell onCreate pid=" + android.os.Process.myPid());
        // Debug only: encodes files/selftest once when the marker file is present. No-op
        // otherwise, and it never touches the engine (see core/EncodeSmoke).
        com.vstplayer.core.EncodeSmoke.runIfRequested(this);
        Player.get().attach(this);
        // Loads the reference index once per process and binds every track to the virtual path the
        // player, the cover loader and the notification use (so a SAF track keeps its metadata even
        // after the activity is recreated while it plays).
        LibraryRef.get(this);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);

        content = new FrameLayout(this);
        root.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        nav = Ui.row(this);
        nav.setBackgroundColor(0xF01C1C1E);
        FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(this, 66));
        nlp.gravity = Gravity.BOTTOM;
        root.addView(nav, nlp);

        addTab("home", "主页");
        addTab("library", "资料库");
        addTab("sliders", "VST");

        overlay = new FrameLayout(this);
        root.addView(overlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        bootOverlay = buildOverlay();
        root.addView(bootOverlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        // Only the one-time extraction needs a blocking mask; a warm install goes straight to
        // the library and the engine keeps booting behind a status pill in the VST tab.
        if (App.env(this).isReady()) bootOverlay.setVisibility(View.GONE);

        setContentView(root);

        final FrameLayout.LayoutParams navLp = nlp;
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int bottom = insets.getSystemWindowInsetBottom();
            if (bottom != navInset) {
                navInset = bottom;
                navLp.height = Ui.dp(this, 66) + bottom;
                nav.setLayoutParams(navLp);
                nav.setPadding(0, 0, 0, bottom);
                content.setPadding(0, 0, 0, Ui.dp(this, 66) + bottom);
                overlay.setPadding(0, 0, 0, bottom);
            }
            return insets;
        });
        root.requestApplyInsets();
        content.setPadding(0, 0, 0, Ui.dp(this, 66));

        Player.get().setListener(new Player.Listener() {
            @Override public void onTrack(File f, int index, int total) {
                if (home != null) home.onTrack(f, index, total);
                if (detail != null && detailOpen) detail.onTrack(f, index, total);
            }
            @Override public void onState(boolean playing) {
                if (home != null) home.onState(playing);
                if (detail != null && detailOpen) detail.onState(playing);
            }
            @Override public void onProgress(float p, int pos, int dur) {
                if (home != null) home.onProgress(p, pos, dur);
                if (detail != null && detailOpen) detail.onProgress(p, pos, dur);
            }
            @Override public void onQueue() {
                if (home != null) home.onQueue();
                if (detail != null && detailOpen) detail.onQueue();
            }
            @Override public void onMode(int mode) {
                if (detail != null && detailOpen) detail.onMode(mode);
            }
        });

        Vst.get().start(this, new Vst.StateListener() {
            @Override public void onState(int state, String label) {
                updateOverlay(state, label);
                if (vstView != null) vstView.onState(state, label);
            }
            @Override public void onRenderProgress(int percent) {
                if (vstView != null) vstView.onRenderProgress(percent);
            }
            @Override public void onParams() {
                if (vstView != null) vstView.onParams();
            }
        });

        show(0);
        // A scripted run (files/boot.conf ui.tab=N) opens the page the hooks live on: the render
        // page is built lazily, so without this the autorun hook is unreachable from a launch
        // intent alone.
        int bootTab = BootConf.intValue(this, "ui.tab", 0, 0, 2);
        if (bootTab != 0) show(bootTab);
    }

    @Override
    protected void onStart() {
        super.onStart();
        // The transport tick only needs 4 Hz while the UI is on screen; in the background it
        // backs off to 1 Hz and stops waking the main thread for a progress bar nobody sees.
        Player.get().setUiVisible(true);
    }

    @Override
    protected void onStop() {
        // Dropping the blurred backdrop ladder (~5.8 MB of bitmaps) here instead of on every
        // overlay close keeps re-opening the detail page pixel identical: the ladder survives
        // as long as the app is in front, and the memory comes back when it is not.
        if (detail != null) detail.releaseArt();
        Player.get().setUiVisible(false);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        // Only a real close frees the engine (~400 MB of wine); a HOME press keeps it warm.
        if (isFinishing() && !Player.get().isPlaying() && !Vst.get().isRendering()) {
            Vst.get().stopEngine();
        }
        // Both singletons outlive the activity; dropping their callbacks keeps a destroyed
        // activity from being retained across a recreate.
        Player.get().setListener(null);
        Vst.get().setListener(null);
        super.onDestroy();
    }

    private void addTab(String icon, String label) {
        LinearLayout item = Ui.col(this);
        item.setGravity(Gravity.CENTER);
        Icon ic = new Icon(this).name(icon).tint(Ui.SUB);
        TextView t = Ui.tv(this, label, 10.5f, Ui.SUB, false);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
        item.addView(ic, new LinearLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24)));
        item.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        final int idx = nav.getChildCount();
        item.setOnClickListener(v -> show(idx));
        nav.addView(item, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
    }

    private void show(int idx) {
        if (idx == tab) return;
        Diag.log("tab " + idx);
        tab = idx;
        final View page;
        switch (idx) {
            case 0:
                if (home == null) {
                    home = new HomeView(this);
                    home.setOnOpenDetail(this::openDetail);
                }
                File t = Player.get().track();
                if (t != null) {
                    home.onTrack(t, Player.get().index(), Player.get().queue().size());
                    home.onState(Player.get().isPlaying());
                }
                home.setHeader(Player.get().label().isEmpty() ? "现在播放" : "现在播放 · " + Player.get().label());
                page = home;
                break;
            case 1:
                if (library == null) library = new LibraryView(this, this);
                page = library;
                break;
            default:
                if (vstView == null) {
                    vstView = new VstView(this);
                    vstView.onState(Vst.get().state(), Vst.get().stateLabel());
                    vstView.onParams();
                }
                page = vstView;
                break;
        }
        // Pages stay attached and are only hidden. Re-adding them made every tab switch measure and
        // lay out the whole page again, even though nothing about it had changed.
        if (page.getParent() != content) content.addView(page);
        page.setVisibility(View.VISIBLE);
        hidePage(home, page);
        hidePage(library, page);
        hidePage(vstView, page);
        if (idx == 1) library.refresh();
        for (int i = 0; i < nav.getChildCount(); i++) {
            LinearLayout item = (LinearLayout) nav.getChildAt(i);
            Icon ic = (Icon) item.getChildAt(0);
            TextView t = (TextView) item.getChildAt(1);
            boolean on = i == idx;
            ic.tint(on ? Ui.ACC : Ui.SUB);
            t.setTextColor(on ? Ui.ACC : Ui.SUB);
        }
    }

    /** Hides one inactive page; focus is dropped first so the soft keyboard does not outlive it. */
    private static void hidePage(View candidate, View shown) {
        if (candidate == null || candidate == shown) return;
        clearFocusDeep(candidate);
        candidate.setVisibility(View.GONE);
    }

    private static void clearFocusDeep(View v) {
        if (v == null) return;
        if (v.isFocused()) v.clearFocus();
        if (!(v instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) v;
        for (int i = 0; i < group.getChildCount(); i++) clearFocusDeep(group.getChildAt(i));
    }

    // ------------------------------------------------------------ overlays

    private void openDetail() {
        if (detailAnim != null) { detailAnim.cancel(); detailAnim = null; }
        if (detailOpen) return;
        if (detail == null) detail = new DetailPage(this, this::closeDetail);
        detailOpen = true;
        overlay.removeAllViews();
        overlay.addView(detail, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        overlay.setVisibility(View.VISIBLE);
        detail.bind();
        Rect from = coverLocal();
        if (from == null) {
            int w = getResources().getDisplayMetrics().widthPixels;
            int h = getResources().getDisplayMetrics().heightPixels;
            int s = w / 2;
            from = new Rect((w - s) / 2, h / 4, (w + s) / 2, h / 4 + s);
        }
        detail.beginEnter(from);
        if (home != null) home.setCoverVisible(false);
        animateDetail(detail, true, 430);
    }

    private void closeDetail() {
        if (!detailOpen) return;
        detailOpen = false;
        if (detailAnim != null) { detailAnim.cancel(); detailAnim = null; }
        if (detail == null) { finishClose(); return; }
        detail.beginExit();
        animateDetail(detail, false, 330);
        if (home != null) home.onState(Player.get().isPlaying());
    }

    /** Home artwork rectangle in detail page coordinates, null when unknown. */
    private Rect coverLocal() {
        if (home == null || detail == null || !home.coverRectInWindow(coverWin)) return null;
        int[] d = new int[2];
        detail.getLocationInWindow(d);
        return new Rect(coverWin.left - d[0], coverWin.top - d[1],
                coverWin.right - d[0], coverWin.bottom - d[1]);
    }

    /** Grows or shrinks the artwork from the home square to the full bleed backdrop. */
    private void animateDetail(final DetailPage d, final boolean entering, long ms) {
        final ValueAnimator anim = ValueAnimator.ofFloat(entering ? 0f : 1f, entering ? 1f : 0f);
        anim.setDuration(ms * slowFactor());
        anim.setInterpolator(entering
                ? new android.view.animation.DecelerateInterpolator(1.5f)
                : new android.view.animation.AccelerateInterpolator(1.3f));
        anim.addUpdateListener(a -> d.setEnterProgress((float) a.getAnimatedValue()));
        anim.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;
            @Override public void onAnimationCancel(Animator a) { cancelled = true; }
            @Override public void onAnimationEnd(Animator a) {
                if (detailAnim == anim) detailAnim = null;
                if (cancelled) return;
                if (entering) d.endEnter(); else finishClose();
            }
        });
        detailAnim = anim;
        anim.start();
    }

    private void finishClose() {
        detailAnim = null;
        overlay.removeAllViews();
        overlay.setVisibility(View.GONE);
        if (home != null) home.setCoverVisible(true);
    }

    /** Called from a playlist: closes it, switches to the VST tab and queues the tracks. */
    private void renderPlaylistTracks(List<Player.PlaybackSource> sources, String playlistName) {
        overlay.removeAllViews();
        overlay.setVisibility(View.GONE);
        playlistPage = null;
        if (library != null) library.refresh();
        if (tab != 2) show(2);
        if (vstView != null) {
            vstView.setTab(2);
            // A playlist renders into its own folder so two playlists never overwrite each other.
            // References are staged into work/in per file and removed as soon as they are done.
            vstView.renderSources(sources, playlistName == null ? null : playlistName + "_rendered");
        }
    }

    /** A files/anim_slow marker slows the artwork transition down for frame capture. */
    private long slowFactor() {
        return new File(getFilesDir(), "anim_slow").exists() ? 8 : 1;
    }

    private void openPlaylist(Playlists.Item item) {
        List<Playlists.Item> all = Playlists.load(this);
        Playlists.Item found = item;
        for (Playlists.Item it : all) if (it.id.equals(item.id)) found = it;
        playlistPage = new PlaylistPage(this, found, all, new PlaylistPage.Host() {
            @Override public void closePlaylist() {
                overlay.removeAllViews();
                overlay.setVisibility(View.GONE);
                playlistPage = null;
                if (library != null) library.refresh();
            }
            @Override public void importAudio(PlaylistPage page, boolean folder) {
                MainActivity.this.importAudio(folder, page::addImported);
            }
            @Override public void renderTracks(List<Player.PlaybackSource> sources, String name) {
                MainActivity.this.renderPlaylistTracks(sources, name);
            }
        });
        overlay.removeAllViews();
        overlay.addView(playlistPage, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        overlay.setVisibility(View.VISIBLE);
    }

    @Override public void onOpenPlaylist(Playlists.Item item) { openPlaylist(item); }

    @SuppressWarnings("deprecation") // OnBackPressedDispatcher lives in androidx.activity
    @Override public void onBackPressed() {
        if (playlistPage != null) {
            overlay.removeAllViews();
            overlay.setVisibility(View.GONE);
            playlistPage = null;
            if (library != null) library.refresh();
            return;
        }
        if (detailOpen) { closeDetail(); return; }
        super.onBackPressed();
    }

    // ------------------------------------------------------------ library host

    @Override public void onPlaySources(List<Player.PlaybackSource> queue, int index, String label) {
        Player.get().playSources(queue, Math.max(0, index), label);
        show(0);
    }

    @Override public void onImport() { importAudio(false, null); }

    @Override public void onImportFolder() { importAudio(true, null); }

    /**
     * Opens the system picker. Nothing is copied: the picked folder or documents are only
     * <em>indexed</em> (a persistable read grant plus one entry per audio file).
     */
    public void importAudio(boolean folder, ImportSink sink) {
        importSink = sink;
        Intent i = folder ? new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                : new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        if (!folder) {
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("audio/*");
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        }
        try {
            startActivityForResult(i, folder ? REQ_IMPORT_TREE : REQ_IMPORT_FILES);
        } catch (Exception e) {
            importSink = null;
            toast("没有可用的文件选择器");
        }
    }

    @Override public void onRenderSource(Player.PlaybackSource source) {
        show(2);
        if (vstView != null) {
            vstView.setTab(2);
            vstView.useSource(source);
        }
    }

    @Override public void onRenderSources(List<Player.PlaybackSource> sources, String label) {
        show(2);
        if (vstView != null) {
            vstView.setTab(2);
            vstView.renderSources(sources, null);
        }
    }

    @Override public void onRgScan(java.util.List<File> files) {
        rgScanDialog(files, null, null);
    }

    @Override public void onRgScanRefs(java.util.List<com.vstplayer.core.LibraryRef.Track> tracks, String label) {
        rgScanDialog(null, tracks, label);
    }

    /**
     * ReplayGain scan dialog: target loudness is configurable, and the result goes to the file tag
     * (WAV / FLAC) plus the local database. Files that already carry a gain tag are skipped.
     */
    private void rgScanDialog(java.util.List<File> given,
                              java.util.List<com.vstplayer.core.LibraryRef.Track> refs, String label) {
        final java.util.List<File> files = new ArrayList<>();
        final java.util.List<com.vstplayer.core.LibraryRef.Track> tracks =
                refs == null ? null : new ArrayList<>(refs);
        if (refs != null) {
            if (tracks.isEmpty()) { toast("这个目录里没有音频"); return; }
        } else if (given != null && !given.isEmpty()) {
            files.addAll(given);
        } else {
            // Local audio only; references arrive through the library's own menu entry.
            files.addAll(com.vstplayer.core.Paths.audioFiles(Vst.get().outDir()));
        }
        if (refs == null && files.isEmpty()) { toast("没有可扫描的本地音频（引用文件请先渲染）"); return; }
        final int count = refs != null ? tracks.size() : files.size();

        LinearLayout box = Ui.col(this);
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), 0);
        box.addView(Ui.tv(this, "目标响度（LUFS）", 12.5f, Ui.SUB, false));
        final android.widget.EditText targetIn = new android.widget.EditText(this);
        targetIn.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
        targetIn.setText(String.valueOf(com.vstplayer.core.ReplayGain.targetLufs(this)));
        box.addView(targetIn);
        box.addView(Ui.tv(this, "参考：流媒体 -14，古典/室内乐常用 -18 ~ -20。共 " + count + " 个音频"
                        + (refs != null ? "（引用文件" + (label == null ? "" : " · " + label) + "）" : "") + "。",
                11.5f, Ui.SUB2, false));
        box.addView(Ui.tv(this, "扫描方式", 12.5f, Ui.SUB, false));
        final android.widget.RadioGroup modes = new android.widget.RadioGroup(this);
        modes.setOrientation(android.widget.LinearLayout.VERTICAL);
        String[] labels = {"每首单独（TRACK）", "按专辑（专辑艺术家 + 专辑名）", "全部当作一张专辑"};
        com.vstplayer.core.ReplayGain.Mode[] values = {
                com.vstplayer.core.ReplayGain.Mode.TRACK,
                com.vstplayer.core.ReplayGain.Mode.ALBUM,
                com.vstplayer.core.ReplayGain.Mode.ALL_AS_ONE_ALBUM};
        com.vstplayer.core.ReplayGain.Mode cur = com.vstplayer.core.ReplayGain.mode(this);
        for (int i = 0; i < labels.length; i++) {
            android.widget.RadioButton rb = new android.widget.RadioButton(this);
            rb.setId(1000 + i);
            rb.setText(labels[i]);
            rb.setTextSize(13f);
            rb.setChecked(values[i] == cur);
            modes.addView(rb);
        }
        box.addView(modes);
        box.addView(Ui.tv(this, "专辑方式会给每首同时写入 TRACK 与 ALBUM 增益（按整张专辑的响度，不是各曲平均）。",
                11.5f, Ui.SUB2, false));
        final android.widget.CheckBox write =
                new android.widget.CheckBox(this);
        write.setText("写入文件标签（WAV / FLAC / M4A）");
        write.setChecked(com.vstplayer.core.ReplayGain.writeTags(this));
        box.addView(write);
        final android.widget.CheckBox apply = new android.widget.CheckBox(this);
        apply.setText("播放时应用增益（防止过载，最大 0 dB）");
        apply.setChecked(com.vstplayer.core.ReplayGain.applyEnabled(this));
        box.addView(apply);

        new AlertDialog.Builder(this)
                .setTitle("ReplayGain 扫描")
                .setView(box)
                .setPositiveButton("开始扫描", (d, w) -> {
                    double target;
                    try {
                        target = Double.parseDouble(targetIn.getText().toString().trim());
                    } catch (Exception e) {
                        toast("目标响度填写不正确");
                        return;
                    }
                    target = Math.max(-30, Math.min(-5, target));
                    com.vstplayer.core.ReplayGain.setTargetLufs(this, target);
                    com.vstplayer.core.ReplayGain.setWriteTags(this, write.isChecked());
                    com.vstplayer.core.ReplayGain.setApplyEnabled(this, apply.isChecked());
                    int checked = modes.getCheckedRadioButtonId();
                    com.vstplayer.core.ReplayGain.Mode mode =
                            checked >= 0 ? values[checked - 1000] : com.vstplayer.core.ReplayGain.Mode.TRACK;
                    com.vstplayer.core.ReplayGain.setMode(this, mode);
                    startRgScan(files, tracks, target, write.isChecked(), mode);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void startRgScan(final java.util.List<File> files,
                             final java.util.List<com.vstplayer.core.LibraryRef.Track> refs,
                             final double target, final boolean writeTags,
                             final com.vstplayer.core.ReplayGain.Mode mode) {
        final android.widget.TextView text = Ui.tv(this, "准备中…", 13, Ui.TXT, false);
        text.setPadding(Ui.dp(this, 20), Ui.dp(this, 16), Ui.dp(this, 20), Ui.dp(this, 8));
        final boolean[] cancelled = {false};
        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("扫描 ReplayGain")
                .setView(text)
                .setNegativeButton("停止", (d, w) -> cancelled[0] = true)
                .setCancelable(false)
                .create();
        dlg.show();
        Runnable done = () -> {
            if (!isFinishing()) dlg.dismiss();
            if (library != null) library.refresh();
        };
        com.vstplayer.core.ReplayGain.Progress cb = (f, d, total, status) -> {
            String name = f == null ? "" : f.getName();
            text.setText(f == null ? status : (d + "/" + total + " · " + name + "\n" + status));
            if (d >= total) text.setText(status);
        };
        if (refs != null) {
            com.vstplayer.core.ReplayGain.scanReferencesAsync(this, refs, target, mode, writeTags,
                    cb, () -> cancelled[0], done);
        } else {
            com.vstplayer.core.ReplayGain.scanAsync(this, files, target, mode, writeTags,
                    cb, () -> cancelled[0], done);
        }
    }

    /** Preset import: the picked .vstpreset files are copied into the guest preset folder. */
    public void pickPresets() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(i, REQ_PRESET_IMPORT);
        } catch (Exception e) {
            toast("没有可用的文件选择器");
        }
    }

    /** Preset export: copies every preset into a folder the user picks. */
    public void pickPresetExport() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_PRESET_EXPORT);
        } catch (Exception e) {
            toast("没有可用的目录选择器");
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------ boot overlay

    private View buildOverlay() {
        LinearLayout box = Ui.col(this);
        box.setBackgroundColor(Ui.BG);
        box.setGravity(Gravity.CENTER);
        FrameLayout badge = new FrameLayout(this);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF3A0E16, 0xFF12100F});
        g.setShape(GradientDrawable.OVAL);
        badge.setBackground(g);
        Icon ic = new Icon(this).name("music").tint(Ui.withAlpha(0xFFFFFFFF, 0.75f));
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(Ui.dp(this, 54), Ui.dp(this, 54));
        ilp.gravity = Gravity.CENTER;
        badge.addView(ic, ilp);
        box.addView(badge, new LinearLayout.LayoutParams(Ui.dp(this, 96), Ui.dp(this, 96)));
        TextView t = Ui.tv(this, "正在准备音频引擎", 17, Ui.TXT, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, Ui.dp(this, 22), 0, 0);
        box.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        overlayText = Ui.tv(this, "首次启动需要解压运行环境，约 1 分钟", 12.5f, Ui.SUB, false);
        overlayText.setPadding(Ui.dp(this, 30), Ui.dp(this, 8), Ui.dp(this, 30), 0);
        overlayText.setGravity(Gravity.CENTER);
        box.addView(overlayText);
        return box;
    }

    private void updateOverlay(int state, String label) {
        boolean blocking = state == Vst.FAILED || (state == Vst.STARTING && Vst.get().isPreparing());
        if (!blocking) {
            // Shell stays interactive while the engine boots; fade the mask only if it is up.
            if (bootOverlay.getVisibility() == View.VISIBLE) {
                bootOverlay.animate().cancel();
                bootOverlay.animate().alpha(0f)
                        .setDuration(state == Vst.READY ? 260 : 160)
                        .withEndAction(() -> bootOverlay.setVisibility(View.GONE)).start();
            }
            return;
        }
        bootOverlay.animate().cancel();
        bootOverlay.setVisibility(View.VISIBLE);
        bootOverlay.setAlpha(1f);
        overlayText.setText(state == Vst.FAILED ? "启动失败：" + label : label);
    }

    // ------------------------------------------------------------ import

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == Export.REQ_TREE) {
            if (res == RESULT_OK && data != null && data.getData() != null) {
                Uri u = data.getData();
                try {
                    getContentResolver().takePersistableUriPermission(u,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                } catch (Exception e) {
                    Diag.log("tree permission not persisted: " + e);
                }
                Export.setTree(this, u);
                toast("输出目录：" + Export.label(this));
                if (vstView != null) vstView.refreshOutDir();
            }
            return;
        }
        if (req == REQ_PRESET_IMPORT) {
            if (res != RESULT_OK || data == null) return;
            final List<Uri> uris = new ArrayList<>();
            if (data.getClipData() != null) {
                for (int k = 0; k < data.getClipData().getItemCount(); k++) {
                    uris.add(data.getClipData().getItemAt(k).getUri());
                }
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            if (uris.isEmpty()) return;
            final File dir = Vst.get().presetsDir();
            if (dir == null) { toast("引擎未就绪"); return; }
            dir.mkdirs();
            new Thread(() -> {
                int n = 0;
                for (Uri u : uris) {
                    String name = displayName(u);
                    if (!name.toLowerCase(java.util.Locale.US).endsWith(".vstpreset")) {
                        Diag.log("preset import: skipped " + name + " (not .vstpreset)");
                        continue;
                    }
                    if (copyFile(new File(dir, name), u)) n++;
                }
                final int added = n;
                Diag.log("preset import: " + added + "/" + uris.size() + " -> " + dir);
                ui.post(() -> {
                    toast(added == 0 ? "没有导入预设（只接受 .vstpreset）" : "已导入 " + added + " 个预设");
                    if (vstView != null) vstView.refreshPresets();
                });
            }, "preset-import").start();
            return;
        }
        if (req == REQ_PRESET_EXPORT) {
            if (res != RESULT_OK || data == null || data.getData() == null) return;
            final Uri tree = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(tree,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception e) {
                Diag.log("preset export permission: " + e);
            }
            final File dir = Vst.get().presetsDir();
            if (dir == null) { toast("引擎未就绪"); return; }
            new Thread(() -> {
                File[] files = dir.listFiles();
                int n = 0, bad = 0;
                if (files != null) {
                    for (File f : files) {
                        if (!f.isFile()) continue;
                        if (!f.getName().toLowerCase(java.util.Locale.US).endsWith(".vstpreset")) continue;
                        try {
                            Export.copyToTree(this, tree, f, f.getName(), "application/octet-stream", null);
                            n++;
                        } catch (Exception e) {
                            bad++;
                            Diag.log("preset export failed " + f.getName() + ": " + e);
                        }
                    }
                }
                final int done = n, failed = bad;
                ui.post(() -> toast("已导出 " + done + " 个预设" + (failed > 0 ? "，" + failed + " 个失败" : "")));
            }, "preset-export").start();
            return;
        }
        if (req != REQ_IMPORT_FILES && req != REQ_IMPORT_TREE) return;
        final ImportSink sink = importSink;
        importSink = null;
        if (res != RESULT_OK || data == null) return;

        final Uri tree = req == REQ_IMPORT_TREE ? data.getData() : null;
        final List<Uri> uris = new ArrayList<>();
        if (tree != null) {
            // The grant must outlive this activity: without it every later read (playback, tags,
            // rendering) would fail as soon as the process restarts.
            try {
                getContentResolver().takePersistableUriPermission(tree,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception e) {
                Diag.log("tree permission not persisted: " + e);
            }
        } else {
            if (data.getClipData() != null) {
                for (int k = 0; k < data.getClipData().getItemCount(); k++) {
                    Uri u = data.getClipData().getItemAt(k).getUri();
                    if (u != null) uris.add(u);
                }
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            for (Uri u : uris) {
                try {
                    getContentResolver().takePersistableUriPermission(u,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception e) {
                    Diag.log("document permission not persisted: " + e);
                }
            }
        }
        if (tree == null && uris.isEmpty()) { toast("没有可导入的音频"); return; }

        new Thread(() -> {
            final LibraryRef lib = LibraryRef.get(MainActivity.this);
            final List<Player.PlaybackSource> added = new ArrayList<>();
            int count;
            if (tree != null) {
                count = lib.addTree(MainActivity.this, tree, null);
                LibraryRef.Root root = lib.rootByUri(tree.toString());
                if (root != null && count > 0) {
                    for (LibraryRef.Track t : lib.tracksOf(root.id)) added.add(sourceOf(t));
                }
                Diag.log("import tree: " + count + " track(s) indexed from " + tree);
            } else {
                count = lib.addDocuments(MainActivity.this, uris);
                for (Uri u : uris) {
                    LibraryRef.Track t = lib.trackByUri(u.toString());
                    if (t != null) added.add(sourceOf(t));
                }
                Diag.log("import documents: " + count + "/" + uris.size() + " reference(s) added");
            }
            final int total = count;
            ui.post(() -> {
                toast(total <= 0 ? "没有可导入的音频" : "已建立 " + total + " 个引用（未复制文件）");
                if (library != null) library.refresh();
                if (vstView != null) vstView.refreshSources();
                if (sink != null && !added.isEmpty()) sink.onImported(added);
            });
        }, "import-index").start();
    }

    /** Playback entry for a library track. */
    private static Player.PlaybackSource sourceOf(LibraryRef.Track t) {
        String rel = t.rel == null ? "" : t.rel;
        int slash = rel.lastIndexOf('/');
        String name = slash < 0 ? rel : rel.substring(slash + 1);
        return Player.PlaybackSource.ref(LibraryRef.virtualFile(t), Uri.parse(t.uri), name);
    }

    /** Copies a picked preset document onto an exact path (overwrites). */
    private boolean copyFile(File dest, Uri uri) {
        try (InputStream in = getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(dest)) {
            if (in == null) throw new IllegalStateException("openInputStream returned null");
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) >= 0) { if (n > 0) out.write(buf, 0, n); }
            return true;
        } catch (Exception e) {
            Diag.log("copy failed " + dest.getName() + ": " + e);
            dest.delete();
            return false;
        }
    }

    private String displayName(Uri uri) {
        String n = null;
        try (android.database.Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (i >= 0) n = c.getString(i);
            }
        } catch (Exception ignored) { }
        if (n == null) n = "import_" + System.currentTimeMillis() + ".wav";
        StringBuilder sb = new StringBuilder();
        for (char ch : n.toCharArray()) {
            sb.append(Character.isLetterOrDigit(ch) || ch == '.' || ch == '-' || ch == '_' || ch == ' ' ? ch : '_');
        }
        return sb.toString();
    }
}
