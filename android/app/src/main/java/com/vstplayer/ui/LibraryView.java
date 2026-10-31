package com.vstplayer.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.net.Uri;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.vstplayer.core.LibraryRef;
import com.vstplayer.core.Paths;
import com.vstplayer.core.Player;
import com.vstplayer.core.Playlists;
import com.vstplayer.core.Tags;
import com.vstplayer.core.Vst;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Tab 1: playlists plus the audio library.
 *
 * <p>Two independent axes. The <b>address</b> axis is built from the library index: every imported
 * root is one top level folder and a track is addressed by its path relative to that root, so the
 * tree mirrors the user's own folder structure without the files ever being copied. Rendered output
 * ({@code work/out}) is still a real directory tree and is listed as before.
 *
 * <p>The <b>tags</b> axis (artist / album) reads metadata over SAF through
 * {@link LibraryRef#tagsOf}; those reads are warmed on the scan thread and persisted, so a warm
 * start does not touch 100+ documents again.
 */
public class LibraryView extends LinearLayout {

    public interface Host {
        /** Plays a mixed queue; {@code index} is the entry to start with. */
        void onPlaySources(List<Player.PlaybackSource> queue, int index, String label);

        void onImport();

        void onImportFolder();

        /** Renders one entry (a local file or a SAF reference). */
        void onRenderSource(Player.PlaybackSource source);

        void onRenderSources(List<Player.PlaybackSource> sources, String label);

        void onOpenPlaylist(Playlists.Item item);

        /** ReplayGain scanning needs a local file, so only rendered output is offered. */
        void onRgScan(List<File> files);

        /** ReplayGain for library references: staged per track, tags written back to the document. */
        void onRgScanRefs(List<LibraryRef.Track> tracks, String label);
    }

    private static final String PREF = "vstp";
    private static final String KEY_TREE = "treeExpanded";
    private static final int MODE_ADDRESS = 0;
    private static final int MODE_TAGS = 1;

    private final Host host;
    private final LinearLayout list;
    private final TextView count;
    private final TextView modeAddress, modeTags, filterChip;
    private final EditText search;

    /** Expanded folders / groups: "sec:…", "l:&lt;rootId&gt;/&lt;rel&gt;", "g:a|artist". */
    private final Set<String> expanded = new LinkedHashSet<>();
    /** Parsed tags of rendered files, keyed by absolute path. */
    private final Map<String, Tags.Info> tags = new HashMap<>();
    private int mode = MODE_ADDRESS;
    /** Scope of the rendered-output tree. */
    private File filterDir;
    /** Scope of the library tree: a root id plus an optional directory prefix inside it. */
    private String filterRootId, filterRel;
    private String filterArtist, filterAlbum;
    private String query = "";
    private Snapshot last;
    private String rendered;

    public LibraryView(Context c, Host host) {
        super(c);
        this.host = host;
        setOrientation(VERTICAL);
        setBackgroundColor(Ui.BG);
        int pad = Ui.dp(c, 20);

        android.content.SharedPreferences sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String saved = sp.getString(KEY_TREE, "");
        if (!saved.isEmpty()) {
            for (String s : saved.split("\n")) if (!s.trim().isEmpty()) expanded.add(s.trim());
        }

        LinearLayout head = Ui.row(c);
        head.addView(Ui.tv(c, "资料库", 27, Ui.TXT, true),
                new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        FrameLayout add = new FrameLayout(c);
        add.setBackground(Ui.circle(Ui.CARD2, c));
        Icon ic = new Icon(c).name("plus").tint(Ui.TXT).stroke(1.9f);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(Ui.dp(c, 22), Ui.dp(c, 22));
        ilp.gravity = Gravity.CENTER;
        add.addView(ic, ilp);
        add.setOnClickListener(v -> new AlertDialog.Builder(c)
                .setItems(new String[]{"新建播放列表", "导入音频文件…", "导入文件夹…", "ReplayGain 扫描…"},
                        (d, w) -> {
                            if (w == 0) newPlaylist();
                            else if (w == 1) host.onImport();
                            else if (w == 2) host.onImportFolder();
                            else host.onRgScan(renderedFiles());
                        })
                .show());
        head.addView(add, new LinearLayout.LayoutParams(Ui.dp(c, 36), Ui.dp(c, 36)));
        head.setPadding(pad, Ui.dp(c, 18), pad, Ui.dp(c, 8));
        addView(head);

        count = Ui.tv(c, "", 12, Ui.SUB, false);
        count.setPadding(pad, 0, pad, Ui.dp(c, 6));
        addView(count);

        search = new EditText(c);
        search.setSingleLine(true);
        search.setHint("搜索曲名 / 文件名");
        search.setTextSize(13);
        search.setTextColor(Ui.TXT);
        search.setHintTextColor(Ui.SUB2);
        search.setBackground(Ui.bg(Ui.CARD, 12, c));
        search.setPadding(Ui.dp(c, 14), Ui.dp(c, 8), Ui.dp(c, 14), Ui.dp(c, 8));
        LinearLayout searchWrap = Ui.col(c);
        searchWrap.setPadding(pad, 0, pad, Ui.dp(c, 8));
        searchWrap.addView(search);
        addView(searchWrap);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                query = s.toString().trim().toLowerCase(Locale.US);
                rerender();
            }
        });

        LinearLayout modes = Ui.row(c);
        modes.setPadding(pad, 0, pad, Ui.dp(c, 6));
        modeAddress = chip(c, "按地址");
        modeTags = chip(c, "按标签");
        filterChip = chip(c, "筛选");
        modes.addView(modeAddress, new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        gap.leftMargin = Ui.dp(c, 8);
        modes.addView(modeTags, gap);
        LinearLayout.LayoutParams gap2 = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        gap2.leftMargin = Ui.dp(c, 8);
        modes.addView(filterChip, gap2);
        modeAddress.setOnClickListener(v -> { mode = MODE_ADDRESS; styleModes(); rerender(); });
        modeTags.setOnClickListener(v -> { mode = MODE_TAGS; styleModes(); rerender(); });
        filterChip.setOnClickListener(v -> openFilters());
        addView(modes);
        styleModes();

        ScrollView sc = new ScrollView(c);
        sc.setVerticalScrollBarEnabled(false);
        list = Ui.col(c);
        list.setPadding(pad, 0, pad, Ui.dp(c, 24));
        sc.addView(list);
        addView(sc, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));
    }

    private TextView chip(Context c, String s) {
        TextView t = Ui.tv(c, s, 12.5f, Ui.SUB, false);
        t.setBackground(Ui.bg(Ui.CARD, 14, c));
        t.setPadding(Ui.dp(c, 14), Ui.dp(c, 7), Ui.dp(c, 14), Ui.dp(c, 7));
        return t;
    }

    private void styleModes() {
        modeAddress.setTextColor(mode == MODE_ADDRESS ? 0xFF000000 : Ui.SUB);
        modeAddress.setBackground(mode == MODE_ADDRESS ? Ui.bg(Ui.ACC, 14, getContext())
                : Ui.bg(Ui.CARD, 14, getContext()));
        modeTags.setTextColor(mode == MODE_TAGS ? 0xFF000000 : Ui.SUB);
        modeTags.setBackground(mode == MODE_TAGS ? Ui.bg(Ui.ACC, 14, getContext())
                : Ui.bg(Ui.CARD, 14, getContext()));
        StringBuilder sb = new StringBuilder("筛选");
        int n = 0;
        if (filterDir != null) n++;
        if (filterRootId != null) n++;
        if (filterArtist != null) n++;
        if (filterAlbum != null) n++;
        if (n > 0) sb.append(" · ").append(n);
        filterChip.setText(sb.toString());
        filterChip.setTextColor(n > 0 ? Ui.ACC : Ui.SUB);
    }

    // ------------------------------------------------------------------ data

    private final java.util.concurrent.ExecutorService io =
            java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                Thread t = new Thread(runnable, "library-scan");
                t.setDaemon(true);
                return t;
            });
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private volatile int generation;

    /** Everything the rows are built from, captured on the scan thread. */
    private static final class Snapshot {
        final List<Playlists.Item> playlists;
        final boolean corrupt;
        final List<LibraryRef.Root> roots;
        final List<LibraryRef.Track> tracks;
        final Map<String, Tags.Info> trackTags;   // by uri
        final Set<String> broken;                 // uris whose document is gone / not permitted
        final File outDir;
        final File[] outs;
        final int renderedCount;

        Snapshot(List<Playlists.Item> playlists, boolean corrupt, List<LibraryRef.Root> roots,
                 List<LibraryRef.Track> tracks, Map<String, Tags.Info> trackTags, Set<String> broken,
                 File outDir, File[] outs, int renderedCount) {
            this.playlists = playlists;
            this.corrupt = corrupt;
            this.roots = roots;
            this.tracks = tracks;
            this.trackTags = trackTags;
            this.broken = broken;
            this.outDir = outDir;
            this.outs = outs;
            this.renderedCount = renderedCount;
        }

        String signature() {
            StringBuilder sb = new StringBuilder();
            sb.append(corrupt ? 'C' : '-');
            for (Playlists.Item it : playlists) {
                sb.append('|').append(it.id).append('\u0001').append(it.name)
                        .append('\u0001').append(it.tracks.size());
            }
            sb.append('#');
            for (LibraryRef.Root r : roots) sb.append(r.id).append('\u0002').append(r.name).append(';');
            sb.append('#');
            for (LibraryRef.Track t : tracks) {
                sb.append(t.rootId).append('/').append(t.rel).append('\u0002')
                        .append(t.mtime).append('\u0002').append(t.size).append(';');
            }
            sb.append('#').append(renderedCount);
            appendTree(sb, outs, 0);
            return sb.toString();
        }

        private static void appendTree(StringBuilder sb, File[] files, int depth) {
            if (files == null) { sb.append("null"); return; }
            for (File f : files) {
                sb.append(f.getName()).append('\u0001').append(f.length())
                        .append('\u0001').append(f.lastModified()).append(';');
                if (f.isDirectory() && depth < 3) appendTree(sb, f.listFiles(), depth + 1);
            }
        }

        Tags.Info tagOf(LibraryRef.Track t) {
            return t == null ? null : trackTags.get(t.uri);
        }

        List<LibraryRef.Track> tracksOf(String rootId) {
            List<LibraryRef.Track> out = new ArrayList<>();
            for (LibraryRef.Track t : tracks) if (t.rootId.equals(rootId)) out.add(t);
            return out;
        }

        boolean isBroken(LibraryRef.Track t) {
            return t != null && broken.contains(t.uri);
        }
    }

    public void refresh() {
        final int gen = ++generation;
        final Context app = getContext().getApplicationContext();
        final File out = Vst.get().outDir();
        io.execute(() -> {
            if (gen != generation) return;
            LibraryRef lib = LibraryRef.get(app);
            List<Playlists.Item> pls = Playlists.load(app);
            boolean corrupt = Playlists.corrupt();
            List<LibraryRef.Root> roots = lib.roots();
            List<LibraryRef.Track> tracks = lib.tracks();
            List<File> renderedFiles = out == null ? new ArrayList<>() : Paths.audioFiles(out);
            File[] outs = out == null ? null : out.listFiles();
            Map<String, Tags.Info> localTags = new HashMap<>();
            for (File f : renderedFiles) {
                if (gen != generation) return;
                localTags.put(f.getAbsolutePath(), Tags.read(f));
            }

            // First paint: only what is already cached (the persisted slim cache), so the tab shows
            // up instantly even with hundreds of references.
            Map<String, Tags.Info> cached = new HashMap<>();
            Set<String> broken = new LinkedHashSet<>();
            for (LibraryRef.Track t : tracks) {
                Tags.Info known = lib.cachedTags(t);
                if (known != null) cached.put(t.uri, known);
                if (lib.isBroken(t)) broken.add(t.uri);
            }
            publish(gen, new Snapshot(pls, corrupt, roots, tracks, cached, broken,
                    out, outs, renderedFiles.size()), localTags);
            if (cached.size() >= tracks.size()) return;

            // Second pass, still off the UI thread: read whatever is missing once, then persist.
            Map<String, Tags.Info> full = new HashMap<>(cached);
            for (LibraryRef.Track t : tracks) {
                if (gen != generation) return;
                if (full.containsKey(t.uri)) continue;
                full.put(t.uri, lib.tagsOf(t));
                if (lib.isBroken(t)) broken.add(t.uri);
            }
            lib.flushTags();
            publish(gen, new Snapshot(pls, corrupt, roots, tracks, full, broken,
                    out, outs, renderedFiles.size()), localTags);
        });
    }

    private void publish(int gen, Snapshot snap, Map<String, Tags.Info> localTags) {
        ui.post(() -> {
            if (gen != generation) return;
            tags.clear();
            tags.putAll(localTags);
            render(snap);
        });
    }

    private void render(Snapshot snap) {
        last = snap;
        String signature = snap.signature();
        if (signature.equals(rendered)) { paint(); return; }
        rendered = signature;
        paint();
    }

    /** Rebuilds every row from the last scan plus the current mode and filters. */
    private void paint() {
        if (last == null) return;
        final Snapshot snap = last;
        Context c = getContext();
        list.removeAllViews();
        int total = snap.tracks.size() + snap.renderedCount;
        count.setText(total == 0 ? "还没有音频"
                : total + " 个音频 · 引用 " + snap.tracks.size()
                + (snap.broken.isEmpty() ? "" : " · " + snap.broken.size() + " 个失效"));

        section("播放列表");
        if (snap.playlists.isEmpty()) {
            empty(snap.corrupt ? "播放列表文件无法读取 · 原文件已保留，未做覆盖"
                    : "点标题右边 + 新建播放列表");
        } else {
            for (Playlists.Item it : snap.playlists) list.addView(playlistRow(c, it));
        }

        List<LibraryRef.Track> visibleTracks = visibleTracks();
        List<File> visibleRendered = visibleRendered();
        addressSection("渲染结果", snap.outDir, snap.outs, visibleRendered,
                "暂无渲染结果 · 在「VST → 渲染」里生成");
        if (mode == MODE_TAGS) tagSection(visibleTracks);
        else refSection(visibleTracks);
    }

    // ------------------------------------------------------------------ visible set

    private List<LibraryRef.Track> visibleTracks() {
        List<LibraryRef.Track> out = new ArrayList<>();
        if (last == null) return out;
        for (LibraryRef.Track t : last.tracks) if (matches(t)) out.add(t);
        return out;
    }

    private boolean matches(LibraryRef.Track t) {
        if (t == null) return false;
        if (filterRootId != null) {
            if (!filterRootId.equals(t.rootId)) return false;
            if (filterRel != null && !filterRel.isEmpty()) {
                if (!t.rel.equals(filterRel) && !t.rel.startsWith(filterRel + "/")) return false;
            }
        }
        Tags.Info info = last == null ? null : last.tagOf(t);
        if (filterArtist != null && !filterArtist.equals(artistOf(info))) return false;
        if (filterAlbum != null && !albumOf(info).equals(filterAlbum)) return false;
        if (!query.isEmpty()) {
            String name = baseName(t.rel).toLowerCase(Locale.US);
            String title = info == null || info.title == null ? "" : info.title.toLowerCase(Locale.US);
            if (!name.contains(query) && !title.contains(query)) return false;
        }
        return true;
    }

    /** Rendered files (real files under work/out) that pass the current filters. */
    private List<File> visibleRendered() {
        List<File> out = new ArrayList<>();
        File dir = last == null ? null : last.outDir;
        if (dir == null) return out;
        for (File f : Paths.audioFiles(dir)) {
            if (filterDir != null && !Paths.isUnder(filterDir, f)) continue;
            if (filterRootId != null) continue;   // a library scope hides rendered output
            if (filterArtist != null || filterAlbum != null) {
                Tags.Info t = tags(f);
                if (filterArtist != null && !filterArtist.equals(artistOf(t))) continue;
                if (filterAlbum != null && !albumOf(t).equals(filterAlbum)) continue;
            }
            if (!query.isEmpty()) {
                String name = f.getName().toLowerCase(Locale.US);
                Tags.Info t = tags(f);
                String title = t.title == null ? "" : t.title.toLowerCase(Locale.US);
                if (!name.contains(query) && !title.contains(query)) continue;
            }
            out.add(f);
        }
        return out;
    }

    private List<File> renderedFiles() {
        File dir = Vst.get().outDir();
        return dir == null ? new ArrayList<>() : Paths.audioFiles(dir);
    }

    private Tags.Info tags(File f) {
        String key = f.getAbsolutePath();
        Tags.Info t = tags.get(key);
        if (t == null) {
            t = Tags.read(f);
            tags.put(key, t);
        }
        return t;
    }

    private static final String UNKNOWN_ARTIST = "未知艺术家";
    private static final String UNKNOWN_ALBUM = "未知专辑";

    private String artistOf(Tags.Info t) {
        if (t == null) return UNKNOWN_ARTIST;
        String a = t.albumArtist != null && !t.albumArtist.trim().isEmpty() ? t.albumArtist : t.artist;
        return a == null || a.trim().isEmpty() ? UNKNOWN_ARTIST : a.trim();
    }

    private String albumOf(Tags.Info t) {
        if (t == null || t.album == null || t.album.trim().isEmpty()) return UNKNOWN_ALBUM;
        return t.album.trim();
    }

    // ------------------------------------------------------------------ library axis

    /** One directory of the address tree, built from the tracks' rel paths. */
    private static final class Node {
        final String name;
        final String rel;
        final Map<String, Node> dirs = new LinkedHashMap<>();
        final List<LibraryRef.Track> files = new ArrayList<>();

        Node(String name, String rel) {
            this.name = name;
            this.rel = rel;
        }
    }

    /** Builds the directory tree of one root from the tracks it currently shows. */
    private static Node buildTree(List<LibraryRef.Track> tracks) {
        Node root = new Node("", "");
        for (LibraryRef.Track t : tracks) {
            String[] parts = t.rel.split("/");
            Node n = root;
            for (int i = 0; i < parts.length - 1; i++) {
                String seg = parts[i];
                Node next = n.dirs.get(seg);
                if (next == null) {
                    next = new Node(seg, n.rel.isEmpty() ? seg : n.rel + "/" + seg);
                    n.dirs.put(seg, next);
                }
                n = next;
            }
            n.files.add(t);
        }
        return root;
    }

    private static int countFiles(Node n) {
        int total = n.files.size();
        for (Node d : n.dirs.values()) total += countFiles(d);
        return total;
    }

    private void refSection(List<LibraryRef.Track> visible) {
        Context c = getContext();
        final String secKey = "sec:lib";
        boolean open = expanded.contains(secKey);
        LinearLayout head = Ui.row(c);
        head.setPadding(0, Ui.dp(c, 16), 0, Ui.dp(c, 8));
        head.addView(Ui.tv(c, (open ? "⌄ " : "› ") + "源音频 · 引用（不复制）", 13, Ui.SUB, true),
                new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        head.addView(Ui.tv(c, visible.isEmpty() ? "" : visible.size() + " 首", 11.5f, Ui.SUB2, false));
        head.setOnClickListener(v -> { toggle(secKey); rerender(); });
        list.addView(head);
        if (!open) return;
        if (last.roots.isEmpty()) {
            empty("点右上角 + 导入文件夹 / 导入音频文件（只建立引用，文件留在原处）");
            return;
        }
        Set<String> allow = new LinkedHashSet<>();
        for (LibraryRef.Track t : visible) allow.add(t.uri);
        boolean any = false;
        for (LibraryRef.Root r : last.roots) {
            List<LibraryRef.Track> mine = new ArrayList<>();
            for (LibraryRef.Track t : visible) if (r.id.equals(t.rootId)) mine.add(t);
            if (mine.isEmpty()) continue;
            any = true;
            Node tree = buildTree(mine);
            dirRow(r, tree, 0, true);
        }
        if (!any) empty(filterRootId != null ? "当前筛选下没有曲目 · 点「筛选」可清除" : "没有匹配的曲目");
    }

    /** One row of the address tree: a root when {@code isRoot}, otherwise a sub-directory. */
    private void dirRow(final LibraryRef.Root root, final Node node, int depth, boolean isRoot) {
        final Context c = getContext();
        String key = "l:" + root.id + "/" + node.rel;
        boolean open = expanded.contains(key);
        int total = countFiles(node);

        LinearLayout row = Ui.row(c);
        row.setBackground(Ui.bg(Ui.CARD, 14, c));
        int pad = Ui.dp(c, 12) + Ui.dp(c, 14) * Math.min(depth, 6);
        row.setPadding(pad, Ui.dp(c, 11), Ui.dp(c, 12), Ui.dp(c, 11));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = Ui.dp(c, 6);
        row.setLayoutParams(rlp);

        FrameLayout badge = new FrameLayout(c);
        badge.setBackground(Ui.circle(Ui.withAlpha(0xFFFFFFFF, 0.08f), c));
        Icon ic = new Icon(c).name("folder").tint(Ui.TXT).stroke(1.7f);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(Ui.dp(c, 20), Ui.dp(c, 20));
        ilp.gravity = Gravity.CENTER;
        badge.addView(ic, ilp);
        row.addView(badge, new LinearLayout.LayoutParams(Ui.dp(c, 38), Ui.dp(c, 38)));

        LinearLayout body = Ui.col(c);
        body.setPadding(Ui.dp(c, 12), 0, Ui.dp(c, 8), 0);
        String name = isRoot ? (root.name == null || root.name.isEmpty() ? root.id : root.name)
                : node.name;
        TextView nameView = Ui.tv(c, name, 14.5f, Ui.TXT, false);
        nameView.setMaxLines(1);
        nameView.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        body.addView(nameView);
        body.addView(Ui.space(c, 0, Ui.dp(c, 3)));
        int sub = node.dirs.size();
        String kind = LibraryRef.KIND_TREE.equals(root.kind) ? "文件夹引用" : "文件导入";
        body.addView(Ui.tv(c, (sub > 0 ? sub + " 个子目录 · " : "") + total + " 首 · " + kind,
                11.5f, Ui.SUB, false));
        row.addView(body, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        row.addView(Ui.tv(c, open ? "⌄" : "›", 13, Ui.SUB2, false));

        row.setOnClickListener(v -> { toggle(key); rerender(); });
        row.setOnLongClickListener(v -> {
            if (isRoot) rootMenu(root); else dirMenu(root, node);
            return true;
        });
        list.addView(row);
        if (!open) return;

        List<Node> dirs = new ArrayList<>(node.dirs.values());
        dirs.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        for (Node d : dirs) dirRow(root, d, depth + 1, false);
        List<LibraryRef.Track> files = new ArrayList<>(node.files);
        files.sort((a, b) -> a.rel.compareToIgnoreCase(b.rel));
        for (LibraryRef.Track t : files) list.addView(refRow(t, depth + 1));
    }

    private View refRow(final LibraryRef.Track track, int depth) {
        final Context c = getContext();
        boolean broken = last != null && last.isBroken(track);
        LinearLayout row = Ui.row(c);
        row.setBackground(Ui.bg(Ui.CARD, 14, c));
        int pad = Ui.dp(c, 12) + Ui.dp(c, 14) * Math.min(depth, 6);
        row.setPadding(pad, Ui.dp(c, 11), Ui.dp(c, 12), Ui.dp(c, 11));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = Ui.dp(c, 6);
        row.setLayoutParams(rlp);

        FrameLayout badge = new FrameLayout(c);
        badge.setBackground(Ui.circle(Ui.withAlpha(0xFFFFFFFF, 0.08f), c));
        Icon ic = new Icon(c).name("music").tint(broken ? Ui.SUB2 : Ui.TXT).stroke(1.7f);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(Ui.dp(c, 19), Ui.dp(c, 19));
        ilp.gravity = Gravity.CENTER;
        badge.addView(ic, ilp);
        row.addView(badge, new LinearLayout.LayoutParams(Ui.dp(c, 36), Ui.dp(c, 36)));

        LinearLayout body = Ui.col(c);
        body.setPadding(Ui.dp(c, 12), 0, Ui.dp(c, 8), 0);
        Tags.Info t = last == null ? null : last.tagOf(track);
        String title = t == null || t.title == null || t.title.trim().isEmpty()
                ? baseName(track.rel) : t.title.trim();
        TextView name = Ui.tv(c, title, 14.5f, broken ? Ui.SUB : Ui.TXT, false);
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        body.addView(name);
        body.addView(Ui.space(c, 0, Ui.dp(c, 3)));
        String sub = sizeText(track.size);
        if (t != null && t.artist != null && !t.artist.trim().isEmpty()) {
            sub = t.artist.trim() + " · " + sub;
        }
        if (broken) sub = "失效 · " + sub;
        body.addView(Ui.tv(c, sub, 11.5f, broken ? Ui.ACC : Ui.SUB, false));
        row.addView(body, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        row.setOnClickListener(v -> {
            if (broken) { toast("这个引用已失效：文件被移动、删除或不再授权"); return; }
            host.onRenderSource(source(track));
        });
        row.setOnLongClickListener(v -> { trackMenu(track); return true; });
        return row;
    }

    // ------------------------------------------------------------------ tag axis

    /** Artist → album → tracks, built from the references that pass the current filters. */
    private void tagSection(List<LibraryRef.Track> visible) {
        Context c = getContext();
        final String secKey = "sec:tags";
        boolean open = expanded.contains(secKey);
        LinearLayout head = Ui.row(c);
        head.setPadding(0, Ui.dp(c, 16), 0, Ui.dp(c, 8));
        head.addView(Ui.tv(c, (open ? "⌄ " : "› ") + "源音频 · 按标签", 13, Ui.SUB, true),
                new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        head.addView(Ui.tv(c, visible.isEmpty() ? "" : visible.size() + " 首", 11.5f, Ui.SUB2, false));
        head.setOnClickListener(v -> { toggle(secKey); rerender(); });
        list.addView(head);
        if (!open) return;
        if (visible.isEmpty()) { empty("没有匹配的曲目"); return; }

        Map<String, Map<String, List<LibraryRef.Track>>> tree = new LinkedHashMap<>();
        for (LibraryRef.Track t : visible) {
            Tags.Info info = last == null ? null : last.tagOf(t);
            String a = artistOf(info);
            String b = albumOf(info);
            Map<String, List<LibraryRef.Track>> albums = tree.get(a);
            if (albums == null) { albums = new LinkedHashMap<>(); tree.put(a, albums); }
            List<LibraryRef.Track> ts = albums.get(b);
            if (ts == null) { ts = new ArrayList<>(); albums.put(b, ts); }
            ts.add(t);
        }
        List<String> artists = new ArrayList<>(tree.keySet());
        java.util.Collections.sort(artists, String::compareToIgnoreCase);
        for (String a : artists) {
            Map<String, List<LibraryRef.Track>> albums = tree.get(a);
            int n = 0;
            for (List<LibraryRef.Track> ts : albums.values()) n += ts.size();
            String key = "g:a|" + a;
            boolean aOpen = expanded.contains(key);
            list.addView(groupRow(a, albums.size() + " 张专辑 · " + n + " 首", aOpen, () -> {
                toggle(key);
                rerender();
            }));
            if (!aOpen) continue;
            List<String> names = new ArrayList<>(albums.keySet());
            java.util.Collections.sort(names, String::compareToIgnoreCase);
            for (String b : names) {
                List<LibraryRef.Track> ts = albums.get(b);
                ts.sort((x, y) -> x.rel.compareToIgnoreCase(y.rel));
                String bKey = "g:b|" + a + "|" + b;
                boolean bOpen = expanded.contains(bKey);
                list.addView(groupRow("　" + b, ts.size() + " 首", bOpen, () -> {
                    toggle(bKey);
                    rerender();
                }));
                if (!bOpen) continue;
                for (LibraryRef.Track t : ts) list.addView(refRow(t, 2));
            }
        }
    }

    private View groupRow(String title, String meta, boolean open, Runnable onClick) {
        Context c = getContext();
        LinearLayout row = Ui.row(c);
        row.setBackground(Ui.bg(Ui.CARD, 14, c));
        row.setPadding(Ui.dp(c, 14), Ui.dp(c, 12), Ui.dp(c, 14), Ui.dp(c, 12));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = Ui.dp(c, 6);
        row.setLayoutParams(rlp);
        LinearLayout body = Ui.col(c);
        TextView name = Ui.tv(c, title, 14.5f, Ui.TXT, false);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        body.addView(name);
        body.addView(Ui.space(c, 0, Ui.dp(c, 3)));
        body.addView(Ui.tv(c, meta, 11.5f, Ui.SUB, false));
        row.addView(body, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        row.addView(Ui.tv(c, open ? "⌄" : "›", 13, Ui.SUB2, false));
        row.setOnClickListener(v -> onClick.run());
        return row;
    }

    // ------------------------------------------------------------------ filters

    private void openFilters() {
        final Context c = getContext();
        new AlertDialog.Builder(c)
                .setTitle("筛选")
                .setItems(new String[]{"清除全部筛选", "目录…", "艺术家…", "专辑…"},
                        (d, w) -> {
                            if (w == 0) {
                                filterDir = null;
                                filterRootId = null;
                                filterRel = null;
                                filterArtist = null;
                                filterAlbum = null;
                                styleModes();
                                rerender();
                            } else if (w == 1) pickScope();
                            else if (w == 2) pickArtist();
                            else if (w == 3) pickAlbum();
                        })
                .show();
    }

    /** Scope picker over the address model: every root, plus the rendered output folder. */
    private void pickScope() {
        final Context c = getContext();
        if (last == null) return;
        final List<String> rows = new ArrayList<>();
        final List<Runnable> acts = new ArrayList<>();
        rows.add("✓ 全部音频（" + last.tracks.size() + " 首）");
        acts.add(() -> {
            filterRootId = null;
            filterRel = null;
            filterDir = null;
            styleModes();
            rerender();
        });
        File out = last.outDir;
        if (out != null && !Paths.audioFiles(out).isEmpty()) {
            rows.add("▸ 渲染结果目录（" + Paths.audioFiles(out).size() + " 首）");
            acts.add(() -> {
                filterRootId = null;
                filterRel = null;
                filterDir = out;
                styleModes();
                rerender();
            });
        }
        for (final LibraryRef.Root r : last.roots) {
            final int n = last.tracksOf(r.id).size();
            rows.add("▸ " + r.name + "（" + n + " 首）");
            acts.add(() -> {
                filterDir = null;
                filterRootId = r.id;
                filterRel = null;
                styleModes();
                rerender();
            });
        }
        new AlertDialog.Builder(c).setTitle("选择目录")
                .setItems(rows.toArray(new String[0]), (d, w) -> acts.get(w).run())
                .setNegativeButton("取消", null).show();
    }

    private List<String> artists() {
        Set<String> out = new LinkedHashSet<>();
        if (last == null) return new ArrayList<>(out);
        for (LibraryRef.Track t : last.tracks) out.add(artistOf(last.tagOf(t)));
        for (File f : renderedFiles()) out.add(artistOf(tags(f)));
        List<String> l = new ArrayList<>(out);
        java.util.Collections.sort(l, String::compareToIgnoreCase);
        return l;
    }

    private void pickArtist() {
        final Context c = getContext();
        final List<String> all = artists();
        if (all.isEmpty()) { toast("还没有可用的艺术家标签"); return; }
        new AlertDialog.Builder(c).setTitle("选择艺术家")
                .setItems(all.toArray(new String[0]), (d, w) -> {
                    filterArtist = all.get(w);
                    styleModes();
                    rerender();
                })
                .setNegativeButton("取消", null).show();
    }

    private void pickAlbum() {
        final Context c = getContext();
        Set<String> out = new LinkedHashSet<>();
        if (last != null) {
            for (LibraryRef.Track t : last.tracks) {
                Tags.Info info = last.tagOf(t);
                if (filterArtist != null && !filterArtist.equals(artistOf(info))) continue;
                out.add(albumOf(info));
            }
        }
        for (File f : renderedFiles()) {
            Tags.Info info = tags(f);
            if (filterArtist != null && !filterArtist.equals(artistOf(info))) continue;
            out.add(albumOf(info));
        }
        final List<String> all = new ArrayList<>(out);
        java.util.Collections.sort(all, String::compareToIgnoreCase);
        if (all.isEmpty()) { toast("没有可用的专辑标签"); return; }
        new AlertDialog.Builder(c).setTitle("选择专辑")
                .setItems(all.toArray(new String[0]), (d, w) -> {
                    filterAlbum = all.get(w);
                    styleModes();
                    rerender();
                })
                .setNegativeButton("取消", null).show();
    }

    // ------------------------------------------------------------------ rows

    private View playlistRow(Context c, final Playlists.Item it) {
        LinearLayout row = Ui.row(c);
        row.setBackground(Ui.bg(Ui.CARD, 14, c));
        row.setPadding(Ui.dp(c, 12), Ui.dp(c, 12), Ui.dp(c, 12), Ui.dp(c, 12));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = Ui.dp(c, 8);
        row.setLayoutParams(rlp);
        FrameLayout badge = new FrameLayout(c);
        badge.setBackground(Ui.circle(Ui.withAlpha(0xFFFFFFFF, 0.08f), c));
        Icon ic = new Icon(c).name("library").tint(Ui.TXT);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(Ui.dp(c, 20), Ui.dp(c, 20));
        ilp.gravity = Gravity.CENTER;
        badge.addView(ic, ilp);
        row.addView(badge, new LinearLayout.LayoutParams(Ui.dp(c, 40), Ui.dp(c, 40)));
        LinearLayout body = Ui.col(c);
        body.setPadding(Ui.dp(c, 12), 0, 0, 0);
        body.addView(Ui.tv(c, it.name, 14.5f, Ui.TXT, false));
        body.addView(Ui.space(c, 0, Ui.dp(c, 3)));
        int refs = it.refCount();
        body.addView(Ui.tv(c, it.tracks.size() + " 首"
                + (refs > 0 ? " · " + refs + " 个引用" : ""), 11.5f, Ui.SUB, false));
        row.addView(body, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        row.setOnClickListener(v -> host.onOpenPlaylist(it));
        row.setOnLongClickListener(v -> {
            new AlertDialog.Builder(c).setTitle(it.name)
                    .setItems(new String[]{"重命名", "删除"}, (d, w) -> {
                        List<Playlists.Item> all = Playlists.load(c);
                        if (w == 0) {
                            renamePlaylist(c, all, it.id);
                        } else {
                            for (Playlists.Item x : all) {
                                if (x.id.equals(it.id)) { all.remove(x); break; }
                            }
                            Playlists.save(c, all);
                            refresh();
                        }
                    })
                    .setNegativeButton("取消", null).show();
            return true;
        });
        return row;
    }

    private View fileRow(Context c, final File f, int depth) {
        LinearLayout row = Ui.row(c);
        row.setBackground(Ui.bg(Ui.CARD, 14, c));
        int pad = Ui.dp(c, 12) + Ui.dp(c, 14) * Math.min(depth, 6);
        row.setPadding(pad, Ui.dp(c, 11), Ui.dp(c, 12), Ui.dp(c, 11));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = Ui.dp(c, 6);
        row.setLayoutParams(rlp);

        FrameLayout badge = new FrameLayout(c);
        badge.setBackground(Ui.circle(Ui.withAlpha(0xFFFFFFFF, 0.08f), c));
        Icon ic = new Icon(c).name("music").tint(Ui.TXT).stroke(1.7f);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(Ui.dp(c, 19), Ui.dp(c, 19));
        ilp.gravity = Gravity.CENTER;
        badge.addView(ic, ilp);
        row.addView(badge, new LinearLayout.LayoutParams(Ui.dp(c, 36), Ui.dp(c, 36)));

        LinearLayout body = Ui.col(c);
        body.setPadding(Ui.dp(c, 12), 0, Ui.dp(c, 8), 0);
        Tags.Info t = tags(f);
        String title = t.title == null || t.title.trim().isEmpty() ? f.getName() : t.title.trim();
        TextView name = Ui.tv(c, title, 14.5f, Ui.TXT, false);
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        body.addView(name);
        body.addView(Ui.space(c, 0, Ui.dp(c, 3)));
        String sub = meta(f);
        if (t.artist != null && !t.artist.trim().isEmpty()) sub = t.artist.trim() + " · " + sub;
        body.addView(Ui.tv(c, sub, 11.5f, Ui.SUB, false));
        row.addView(body, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        row.setOnClickListener(v -> playRendered(f));
        row.setOnLongClickListener(v -> {
            fileMenu(f);
            return true;
        });
        return row;
    }

    // ------------------------------------------------------------------ address axis (rendered output)

    private void addressSection(String title, File root, File[] entries, List<File> visible,
                                String whenEmpty) {
        Context c = getContext();
        final String secKey = "sec:out";
        boolean open = expanded.contains(secKey);
        LinearLayout head = Ui.row(c);
        head.setPadding(0, Ui.dp(c, 16), 0, Ui.dp(c, 8));
        head.addView(Ui.tv(c, (open ? "⌄ " : "› ") + title, 13, Ui.SUB, true),
                new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        int shown = visible == null ? audioCount(entries) : visible.size();
        head.addView(Ui.tv(c, shown > 0 ? shown + " 首" : "", 11.5f, Ui.SUB2, false));
        head.setOnClickListener(v -> { toggle(secKey); rerender(); });
        list.addView(head);
        if (!open) return;
        if (root == null || !root.isDirectory()) { empty(whenEmpty); return; }

        Set<String> allow = visible == null ? null : new LinkedHashSet<>();
        if (allow != null) {
            for (File f : visible) allow.add(f.getAbsolutePath());
        }
        List<File> dirs = new ArrayList<>();
        List<File> files = new ArrayList<>();
        File[] arr = root.listFiles();
        if (arr != null) {
            for (File f : arr) {
                if (f.getName().startsWith(".")) continue;
                if (f.isDirectory()) {
                    if (allow == null || hasVisibleBelow(f, allow)) dirs.add(f);
                } else if (Paths.isAudioName(f.getName()) && (allow == null || allow.contains(f.getAbsolutePath()))) {
                    files.add(f);
                }
            }
        }
        dirs.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        files.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        if (dirs.isEmpty() && files.isEmpty()) { empty(whenEmpty); return; }
        if (filterDir != null) empty("只显示 " + filterDir.getName() + " 里的文件 · 点「筛选」可清除");
        for (File d : dirs) addFolder(d, 0, allow);
        for (File f : files) list.addView(fileRow(c, f, 0));
    }

    private static boolean hasVisibleBelow(File dir, Set<String> allow) {
        for (String a : allow) {
            if (a.startsWith(dir.getAbsolutePath() + File.separator)) return true;
        }
        return false;
    }

    private void addFolder(final File dir, final int depth, final Set<String> allow) {
        Context c = getContext();
        String path = dir.getAbsolutePath();
        boolean open = expanded.contains(path);

        LinearLayout row = Ui.row(c);
        row.setBackground(Ui.bg(Ui.CARD, 14, c));
        int pad = Ui.dp(c, 12) + Ui.dp(c, 14) * Math.min(depth, 6);
        row.setPadding(pad, Ui.dp(c, 11), Ui.dp(c, 12), Ui.dp(c, 11));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = Ui.dp(c, 6);
        row.setLayoutParams(rlp);

        FrameLayout badge = new FrameLayout(c);
        badge.setBackground(Ui.circle(Ui.withAlpha(0xFFFFFFFF, 0.08f), c));
        Icon ic = new Icon(c).name("folder").tint(Ui.TXT).stroke(1.7f);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(Ui.dp(c, 20), Ui.dp(c, 20));
        ilp.gravity = Gravity.CENTER;
        badge.addView(ic, ilp);
        row.addView(badge, new LinearLayout.LayoutParams(Ui.dp(c, 38), Ui.dp(c, 38)));

        LinearLayout body = Ui.col(c);
        body.setPadding(Ui.dp(c, 12), 0, Ui.dp(c, 8), 0);
        TextView name = Ui.tv(c, dir.getName(), 14.5f, Ui.TXT, false);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        body.addView(name);
        body.addView(Ui.space(c, 0, Ui.dp(c, 3)));
        int sub = countSubdirs(dir);
        int audio = Paths.audioFiles(dir).size();
        body.addView(Ui.tv(c, (sub > 0 ? sub + " 个子目录 · " : "") + audio + " 首", 11.5f, Ui.SUB, false));
        row.addView(body, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        row.addView(Ui.tv(c, open ? "⌄" : "›", 13, Ui.SUB2, false));

        row.setOnClickListener(v -> { toggle(path); rerender(); });
        row.setOnLongClickListener(v -> { folderMenu(dir); return true; });
        list.addView(row);
        if (!open) return;

        List<File> dirs = new ArrayList<>();
        List<File> files = new ArrayList<>();
        File[] arr = dir.listFiles();
        if (arr != null) {
            for (File f : arr) {
                if (f.getName().startsWith(".")) continue;
                if (f.isDirectory()) {
                    if (allow == null || hasVisibleBelow(f, allow)) dirs.add(f);
                } else if (Paths.isAudioName(f.getName()) && (allow == null || allow.contains(f.getAbsolutePath()))) {
                    files.add(f);
                }
            }
        }
        dirs.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        files.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File d : dirs) addFolder(d, depth + 1, allow);
        for (File f : files) list.addView(fileRow(c, f, depth + 1));
    }

    // ------------------------------------------------------------------ menus

    private void rootMenu(final LibraryRef.Root root) {
        final Context c = getContext();
        final List<LibraryRef.Track> mine = last == null ? new ArrayList<>() : last.tracksOf(root.id);
        String[] items = {"加入播放列表…", "渲染全部（" + mine.size() + " 首）",
                "ReplayGain 扫描（" + mine.size() + " 首）", "只看这个目录",
                "复制地址", "从资料库移除（不删除文件）"};
        new AlertDialog.Builder(c).setTitle(root.name).setItems(items, (d, w) -> {
            if (w == 0) addToPlaylist(trackKeys(mine));
            else if (w == 1) {
                if (mine.isEmpty()) { toast("这个目录里没有音频"); return; }
                host.onRenderSources(sourcesOf(mine), root.name);
            } else if (w == 2) {
                if (mine.isEmpty()) { toast("这个目录里没有音频"); return; }
                host.onRgScanRefs(mine, root.name);
            } else if (w == 3) {
                filterDir = null;
                filterRootId = root.id;
                filterRel = null;
                styleModes();
                rerender();
            } else if (w == 4) {
                copyPath(root.uri == null || root.uri.isEmpty() ? root.name : root.uri);
            } else {
                LibraryRef lib = LibraryRef.get(c);
                if (lib.removeRoot(root)) { toast("已从资料库移除"); refresh(); }
                else toast("移除失败");
            }
        }).setNegativeButton("取消", null).show();
    }

    private void dirMenu(final LibraryRef.Root root, final Node node) {
        final Context c = getContext();
        final List<LibraryRef.Track> mine = new ArrayList<>();
        collect(node, mine);
        mine.sort((a, b) -> a.rel.compareToIgnoreCase(b.rel));
        String[] items = {"加入播放列表…", "渲染此文件夹（" + mine.size() + " 首）",
                "ReplayGain 扫描（" + mine.size() + " 首）", "只看这个目录", "复制地址"};
        new AlertDialog.Builder(c).setTitle(node.name).setItems(items, (d, w) -> {
            if (w == 0) addToPlaylist(trackKeys(mine));
            else if (w == 1) {
                if (mine.isEmpty()) { toast("这个文件夹里没有音频"); return; }
                host.onRenderSources(sourcesOf(mine), node.name);
            } else if (w == 2) {
                if (mine.isEmpty()) { toast("这个文件夹里没有音频"); return; }
                host.onRgScanRefs(mine, node.name);
            } else if (w == 3) {
                filterDir = null;
                filterRootId = root.id;
                filterRel = node.rel;
                styleModes();
                rerender();
            } else {
                copyPath(root.name + "/" + node.rel);
            }
        }).setNegativeButton("取消", null).show();
    }

    private static void collect(Node n, List<LibraryRef.Track> out) {
        out.addAll(n.files);
        for (Node d : n.dirs.values()) collect(d, out);
    }

    private void trackMenu(final LibraryRef.Track track) {
        final Context c = getContext();
        boolean broken = last != null && last.isBroken(track);
        String[] items = {"加入播放列表…", "渲染此曲", "只看所在目录", "复制地址",
                broken ? "地址失效 · 从资料库移除" : "从资料库移除（不删除文件）"};
        new AlertDialog.Builder(c).setTitle(baseName(track.rel)).setItems(items, (d, w) -> {
            if (w == 0) {
                List<String> keys = new ArrayList<>();
                keys.add(Playlists.refKey(track));
                addToPlaylist(keys);
            } else if (w == 1) {
                if (broken) { toast("这个引用已失效"); return; }
                host.onRenderSource(source(track));
            } else if (w == 2) {
                int slash = track.rel.lastIndexOf('/');
                filterDir = null;
                filterRootId = track.rootId;
                filterRel = slash > 0 ? track.rel.substring(0, slash) : null;
                styleModes();
                rerender();
            } else if (w == 3) {
                copyPath(track.uri);
            } else {
                LibraryRef lib = LibraryRef.get(c);
                if (lib.removeTrack(track)) { toast("已从资料库移除"); refresh(); }
                else toast("移除失败");
            }
        }).setNegativeButton("取消", null).show();
    }

    private void folderMenu(final File dir) {
        final Context c = getContext();
        final List<File> files = Paths.audioFiles(dir);
        String[] items = {"加入播放列表…", "全部播放", "ReplayGain 扫描此文件夹（" + files.size() + " 首）",
                "只看这个目录", "复制路径", "删除文件夹"};
        new AlertDialog.Builder(c).setTitle(dir.getName()).setItems(items, (d, w) -> {
            if (w == 0) { addToPlaylist(fileKeys(files)); return; }
            if (w == 1) {
                if (files.isEmpty()) { toast("这个文件夹里没有音频"); return; }
                List<Player.PlaybackSource> queue = new ArrayList<>();
                for (File f : files) queue.add(Player.PlaybackSource.local(f));
                host.onPlaySources(queue, 0, dir.getName());
                return;
            }
            if (w == 2) {
                if (files.isEmpty()) { toast("这个文件夹里没有音频"); return; }
                host.onRgScan(files);
                return;
            }
            if (w == 3) {
                filterRootId = null;
                filterRel = null;
                filterDir = dir;
                styleModes();
                rerender();
                return;
            }
            if (w == 4) { copyPath(dir.getAbsolutePath()); return; }
            new AlertDialog.Builder(c).setTitle("删除文件夹「" + dir.getName() + "」？")
                    .setMessage("会删除里面全部 " + files.size() + " 个渲染文件，不可恢复。")
                    .setPositiveButton("删除", (dd, ww) -> {
                        if (deleteTree(dir)) { toast("已删除"); refresh(); }
                        else toast("删除失败");
                    })
                    .setNegativeButton("取消", null).show();
        }).setNegativeButton("取消", null).show();
    }

    private void fileMenu(final File f) {
        final Context c = getContext();
        String[] items = {"加入播放列表…", "播放", "只看所在目录", "复制路径", "删除"};
        new AlertDialog.Builder(c).setTitle(f.getName()).setItems(items, (d, w) -> {
            if (w == 0) addToPlaylist(fileKeys(java.util.Collections.singletonList(f)));
            else if (w == 1) playRendered(f);
            else if (w == 2) {
                filterRootId = null;
                filterRel = null;
                filterDir = f.getParentFile();
                styleModes();
                rerender();
            } else if (w == 3) {
                copyPath(f.getAbsolutePath());
            } else {
                if (f.delete()) { toast("已删除"); refresh(); }
                else toast("删除失败");
            }
        }).setNegativeButton("取消", null).show();
    }

    /** Plays one rendered file inside its folder, as before. */
    private void playRendered(File f) {
        File dir = f.getParentFile();
        List<Player.PlaybackSource> queue = new ArrayList<>();
        List<File> files = new ArrayList<>();
        File[] arr = dir == null ? null : dir.listFiles();
        if (arr != null) {
            List<File> sorted = new ArrayList<>();
            for (File x : arr) if (x.isFile() && Paths.isAudioName(x.getName())) sorted.add(x);
            sorted.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            files.addAll(sorted);
        }
        if (files.isEmpty()) files.add(f);
        int idx = 0;
        for (int i = 0; i < files.size(); i++) {
            if (files.get(i).equals(f)) idx = i;
            queue.add(Player.PlaybackSource.local(files.get(i)));
        }
        host.onPlaySources(queue, idx, dir == null ? "" : dir.getName());
    }

    private void copyPath(String path) {
        Object svc = getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (svc instanceof android.content.ClipboardManager) {
            ((android.content.ClipboardManager) svc)
                    .setPrimaryClip(android.content.ClipData.newPlainText("路径", path));
            toast("已复制路径");
        }
    }

    private void addToPlaylist(final List<String> keys) {
        if (keys == null || keys.isEmpty()) { toast("没有可加入的音频"); return; }
        final Context c = getContext();
        final List<Playlists.Item> all = Playlists.load(c);
        String[] names = new String[all.size() + 1];
        for (int i = 0; i < all.size(); i++) {
            names[i] = all.get(i).name + " · " + all.get(i).tracks.size() + " 首";
        }
        names[all.size()] = "＋ 新建播放列表…";
        new AlertDialog.Builder(c)
                .setTitle("加入播放列表（" + keys.size() + " 首）")
                .setItems(names, (d, w) -> {
                    if (w >= all.size()) { newPlaylistWith(c, keys); return; }
                    Playlists.Item it = all.get(w);
                    int added = 0;
                    for (String k : keys) {
                        if (k != null && !it.tracks.contains(k)) { it.tracks.add(k); added++; }
                    }
                    Playlists.save(c, all);
                    rerender();
                    toast(added == 0 ? "这些曲目都已经在里面了" : "已加入 " + added + " 首");
                })
                .setNegativeButton("取消", null).show();
    }

    /** Creates a playlist from this dialog and drops the selected tracks straight into it. */
    private void newPlaylistWith(final Context c, final List<String> keys) {
        EditText e = new EditText(c);
        e.setSingleLine(true);
        e.setHint("播放列表名称");
        new AlertDialog.Builder(c).setTitle("新建播放列表").setView(e)
                .setPositiveButton("创建", (d, w) -> {
                    String n = e.getText().toString().trim();
                    if (n.isEmpty()) return;
                    List<Playlists.Item> all = Playlists.load(c);
                    Playlists.Item it = new Playlists.Item();
                    it.name = n;
                    for (String k : keys) if (k != null && !it.tracks.contains(k)) it.tracks.add(k);
                    all.add(it);
                    Playlists.save(c, all);
                    refresh();
                    toast("已新建「" + n + "」并加入 " + it.tracks.size() + " 首");
                })
                .setNegativeButton("取消", null).show();
    }

    // ------------------------------------------------------------------ helpers

    private static List<Player.PlaybackSource> sourcesOf(List<LibraryRef.Track> tracks) {
        List<Player.PlaybackSource> out = new ArrayList<>();
        for (LibraryRef.Track t : tracks) out.add(source(t));
        return out;
    }

    private static Player.PlaybackSource source(LibraryRef.Track t) {
        return Player.PlaybackSource.ref(LibraryRef.virtualFile(t), Uri.parse(t.uri), baseName(t.rel));
    }

    private static List<String> trackKeys(List<LibraryRef.Track> tracks) {
        List<String> out = new ArrayList<>();
        for (LibraryRef.Track t : tracks) out.add(Playlists.refKey(t));
        return out;
    }

    private List<String> fileKeys(List<File> files) {
        List<String> out = new ArrayList<>();
        for (File f : files) out.add(keyOf(f));
        return out;
    }

    /** Playlist key of a rendered / legacy local file. */
    private String keyOf(File f) {
        String rel = Paths.relTo(Vst.get().outDir(), f);
        if (rel == null) rel = Paths.relTo(Vst.get().inDir(), f);
        return rel == null ? f.getName() : rel;
    }

    private static String baseName(String rel) {
        if (rel == null) return "";
        int slash = rel.lastIndexOf('/');
        return slash < 0 ? rel : rel.substring(slash + 1);
    }

    private static String sizeText(long bytes) {
        long kb = Math.max(1, bytes / 1024);
        return kb < 1024 ? kb + " KB" : String.format(Locale.US, "%.1f MB", kb / 1024f);
    }

    private static int countSubdirs(File dir) {
        File[] arr = dir.listFiles();
        int n = 0;
        if (arr != null) for (File f : arr) if (f.isDirectory() && !f.getName().startsWith(".")) n++;
        return n;
    }

    private static int audioCount(File[] entries) {
        if (entries == null) return 0;
        int n = 0;
        for (File f : entries) {
            if (f.isDirectory()) n += Paths.audioFiles(f).size();
            else if (Paths.isAudioName(f.getName())) n++;
        }
        return n;
    }

    private static boolean deleteTree(File dir) {
        File[] arr = dir.listFiles();
        boolean ok = true;
        if (arr != null) for (File f : arr) ok &= f.isDirectory() ? deleteTree(f) : f.delete();
        return dir.delete() && ok;
    }

    private void toggle(String key) {
        if (!expanded.remove(key)) expanded.add(key);
        android.content.SharedPreferences sp =
                getContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
        StringBuilder sb = new StringBuilder();
        for (String s : expanded) sb.append(s).append('\n');
        sp.edit().putString(KEY_TREE, sb.toString()).apply();
    }

    private void rerender() {
        rendered = null;
        paint();
    }

    private void renamePlaylist(Context c, final List<Playlists.Item> all, final String id) {
        EditText e = new EditText(c);
        e.setSingleLine(true);
        for (Playlists.Item x : all) if (x.id.equals(id)) e.setText(x.name);
        new AlertDialog.Builder(c).setTitle("重命名播放列表").setView(e)
                .setPositiveButton("保存", (d, w) -> {
                    String n = e.getText().toString().trim();
                    if (n.isEmpty()) return;
                    for (Playlists.Item x : all) if (x.id.equals(id)) x.name = n;
                    Playlists.save(c, all);
                    refresh();
                })
                .setNegativeButton("取消", null).show();
    }

    private void newPlaylist() {
        final Context c = getContext();
        EditText e = new EditText(c);
        e.setSingleLine(true);
        e.setHint("播放列表名称");
        new AlertDialog.Builder(c).setTitle("新建播放列表").setView(e)
                .setPositiveButton("创建", (d, w) -> {
                    String n = e.getText().toString().trim();
                    if (n.isEmpty()) return;
                    List<Playlists.Item> all = Playlists.load(c);
                    Playlists.Item it = new Playlists.Item();
                    it.name = n;
                    all.add(it);
                    Playlists.save(c, all);
                    refresh();
                    host.onOpenPlaylist(it);
                })
                .setNegativeButton("取消", null).show();
    }

    private void section(String s) {
        TextView t = Ui.tv(getContext(), s, 13, Ui.SUB, true);
        t.setPadding(0, Ui.dp(getContext(), 14), 0, Ui.dp(getContext(), 8));
        list.addView(t);
    }

    private void empty(String s) {
        TextView t = Ui.tv(getContext(), s, 13, Ui.SUB2, false);
        t.setPadding(Ui.dp(getContext(), 4), Ui.dp(getContext(), 4), 0, Ui.dp(getContext(), 12));
        list.addView(t);
    }

    private void toast(String s) {
        Toast.makeText(getContext(), s, Toast.LENGTH_SHORT).show();
    }

    private static final ThreadLocal<java.text.SimpleDateFormat> SDF =
            new ThreadLocal<java.text.SimpleDateFormat>() {
                @Override protected java.text.SimpleDateFormat initialValue() {
                    return new java.text.SimpleDateFormat("MM-dd HH:mm", Locale.US);
                }
            };

    private static String meta(File f) {
        return sizeText(f.length()) + " · " + SDF.get().format(new java.util.Date(f.lastModified()));
    }
}
