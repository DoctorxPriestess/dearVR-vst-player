package com.vstplayer.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.net.Uri;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.vstplayer.core.LibraryRef;
import com.vstplayer.core.Playlists;
import com.vstplayer.core.Player;
import com.vstplayer.core.Vst;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Editor for one saved playlist: add, remove, reorder (persisted).
 *
 * <p>Entries are resolved against three authorities: rendered output ({@code work/out}), the legacy
 * private source folder ({@code work/in}, still used for staged render copies) and the library
 * index for {@link Playlists#isRef(String)} entries. An entry that cannot be resolved is marked as
 * missing instead of being dropped, so a temporarily unavailable reference survives.
 */
public class PlaylistPage extends LinearLayout {

    public interface Host {
        void closePlaylist();

        /** Opens the system picker; the imported references are handed back through {@link #addImported}. */
        void importAudio(PlaylistPage page, boolean folder);

        /** Queues these entries for offline rendering. */
        void renderTracks(List<Player.PlaybackSource> sources, String playlistName);
    }

    private final Playlists.Item item;
    private final Host host;
    private final TrackList list;
    private final TextView title;
    private final List<Playlists.Item> all;

    /** Resolution of {@link Playlists.Item#tracks} in list order. */
    private List<Player.PlaybackSource> current = new ArrayList<>();
    private final Map<File, String> keyByFile = new HashMap<>();
    private final Set<String> missing = new LinkedHashSet<>();

    public PlaylistPage(Context c, Playlists.Item item, List<Playlists.Item> all, Host host) {
        super(c);
        this.item = item;
        this.all = all;
        this.host = host;
        setOrientation(VERTICAL);
        setBackgroundColor(Ui.BG);
        int pad = Ui.dp(c, 20);

        LinearLayout head = Ui.row(c);
        head.setPadding(pad, Ui.dp(c, 30), pad, Ui.dp(c, 10));
        Icon back = new Icon(c).name("back").tint(Ui.TXT);
        back.setRotation(180);
        back.setOnClickListener(v -> host.closePlaylist());
        head.addView(back, new LinearLayout.LayoutParams(Ui.dp(c, 28), Ui.dp(c, 28)));
        title = Ui.tv(c, item.name, 24, Ui.TXT, true);
        title.setPadding(Ui.dp(c, 10), 0, 0, 0);
        title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setOnClickListener(v -> rename());
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Icon add = new Icon(c).name("plus").tint(Ui.TXT);
        add.setOnClickListener(v -> addMenu());
        head.addView(add, new LinearLayout.LayoutParams(Ui.dp(c, 28), Ui.dp(c, 28)));
        Icon del = new Icon(c).name("trash").tint(Ui.SUB);
        del.setPadding(Ui.dp(c, 14), 0, 0, 0);
        del.setOnClickListener(v -> removePlaylist());
        head.addView(del, new LinearLayout.LayoutParams(Ui.dp(c, 46), Ui.dp(c, 28)));
        addView(head);

        TextView hint = Ui.tv(c, "点 + 添加曲目/引用或导入 · 长按拖拽排序 · 左滑移除", 11.5f, Ui.SUB2, false);
        hint.setPadding(pad, 0, pad, Ui.dp(c, 8));
        addView(hint);

        list = new TrackList(c);
        list.setCallback(new TrackList.Cb() {
            @Override public void onPlay(File f) {
                int i = indexOf(f);
                Player.get().playSources(current, Math.max(0, i), PlaylistPage.this.item.name);
            }
            @Override public void onOrderChanged(List<File> files) {
                item.tracks.clear();
                for (File f : files) {
                    String k = keyByFile.get(f);
                    item.tracks.add(k != null ? k : legacyKey(f));
                }
                Playlists.save(getContext(), all);
            }
            @Override public void onSelectionChanged(int count, boolean selectMode) { }
        });
        addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        refresh();
    }

    private File outDir() {
        File d = Vst.get().outDir();
        return d == null ? getContext().getFilesDir() : d;
    }

    private File inDir() {
        File d = Vst.get().inDir();
        return d == null ? getContext().getFilesDir() : d;
    }

    private int indexOf(File f) {
        for (int i = 0; i < current.size(); i++) {
            File o = current.get(i).file;
            if (o != null && o.equals(f)) return i;
        }
        return -1;
    }

    /**
     * Resolves every stored entry: a reference through the library index, a plain path through the
     * render output first and the legacy source folder second.
     */
    private List<Player.PlaybackSource> resolve() {
        List<Player.PlaybackSource> out = new ArrayList<>();
        keyByFile.clear();
        missing.clear();
        File rendered = outDir();
        File source = inDir();
        LibraryRef lib = LibraryRef.get(getContext());
        // One snapshot, one map: the per-entry lookup used to scan the whole index (under the
        // library's lock) for every stored key, which is quadratic for a long playlist.
        java.util.Map<String, LibraryRef.Track> byRef = new HashMap<>();
        for (LibraryRef.Track t : lib.tracks()) {
            String mapKey = t.rootId + "\u0001" + t.rel;
            if (!byRef.containsKey(mapKey)) byRef.put(mapKey, t);
        }
        for (String key : item.tracks) {
            if (key == null || key.isEmpty()) continue;
            String[] ref = Playlists.splitRef(key);
            if (ref != null) {
                LibraryRef.Track t = byRef.get(ref[0] + "\u0001" + ref[1]);
                if (t == null) {
                    missing.add(baseName(ref[1]));
                    continue;
                }
                Player.PlaybackSource s = Player.PlaybackSource.ref(
                        LibraryRef.virtualFile(t), Uri.parse(t.uri), baseName(t.rel));
                out.add(s);
                keyByFile.put(s.file, key);
                continue;
            }
            File f = new File(rendered, key);
            if (!f.exists()) f = new File(source, key);
            if (!f.exists()) {
                missing.add(new File(key).getName());
                continue;
            }
            Player.PlaybackSource s = Player.PlaybackSource.local(f);
            out.add(s);
            keyByFile.put(f, key);
        }
        return out;
    }

    /**
     * Stable playlist entry for a local file: its path relative to the folder it lives in.
     *
     * <p>Imports keep their folder structure, so two albums can both hold "01 - x.flac"; a plain
     * file name would collide. Entries written before folder support existed are bare names and
     * still resolve, because a bare name is just a relative path at the root.
     */
    private String legacyKey(File f) {
        String rel = relTo(outDir(), f);
        if (rel == null) rel = relTo(inDir(), f);
        return rel == null ? f.getName() : rel;
    }

    private static String relTo(File root, File f) {
        if (root == null || f == null) return null;
        String r = root.getAbsolutePath();
        String a = f.getAbsolutePath();
        if (!a.startsWith(r + File.separator)) return null;
        return a.substring(r.length() + 1).replace(File.separatorChar, '/');
    }

    /** Called by the shell after an import finished. */
    public void addImported(List<Player.PlaybackSource> sources) {
        if (sources == null || sources.isEmpty()) return;
        int n = 0;
        for (Player.PlaybackSource s : sources) {
            String k = playlistKey(s);
            if (k == null || item.tracks.contains(k)) continue;
            item.tracks.add(k);
            n++;
        }
        if (n == 0) {
            Toast.makeText(getContext(), "这些曲目已经在播放列表里了", Toast.LENGTH_SHORT).show();
            return;
        }
        Playlists.save(getContext(), all);
        refresh();
        Toast.makeText(getContext(), "已加入 " + n + " 首", Toast.LENGTH_SHORT).show();
    }

    public void refresh() {
        current = resolve();
        List<File> files = new ArrayList<>();
        for (Player.PlaybackSource s : current) files.add(s.file);
        list.setFiles(files, Player.get().track());
        list.setMissing(missing);
        title.setText(item.name + " · " + files.size() + " 首");
    }

    // ------------------------------------------------------------------ keys

    /** Playlist key for a resolved source: a reference key or a path relative to out/in. */
    private String playlistKey(Player.PlaybackSource s) {
        if (s == null) return null;
        if (!s.local()) {
            LibraryRef.Track t = LibraryRef.get(getContext()).trackByVirtual(s.file);
            if (t != null) return Playlists.refKey(t);
        }
        return legacyKey(s.file);
    }

    private void rename() {
        Context c = getContext();
        EditText e = new EditText(c);
        e.setSingleLine(true);
        e.setText(item.name);
        new AlertDialog.Builder(c).setTitle("重命名播放列表").setView(e)
                .setPositiveButton("保存", (d, w) -> {
                    String n = e.getText().toString().trim();
                    if (n.isEmpty()) return;
                    item.name = n;
                    Playlists.save(c, all);
                    refresh();
                })
                .setNegativeButton("取消", null).show();
    }

    private void removePlaylist() {
        Context c = getContext();
        new AlertDialog.Builder(c).setTitle("删除播放列表「" + item.name + "」？")
                .setMessage("只删除列表，不影响音频文件。")
                .setPositiveButton("删除", (d, w) -> {
                    all.remove(item);
                    Playlists.save(c, all);
                    host.closePlaylist();
                })
                .setNegativeButton("取消", null).show();
    }

    private void addMenu() {
        String[] items = {"渲染整个歌单", "从渲染结果添加", "从资料库添加", "导入音频文件…", "导入文件夹…"};
        new AlertDialog.Builder(getContext())
                .setTitle("「" + item.name + "」")
                .setItems(items, (d, w) -> {
                    if (w == 0) renderPlaylist();
                    else if (w == 1) browseRendered(outDir(), outDir());
                    else if (w == 2) pickLibraryRoot();
                    else if (w == 3) host.importAudio(this, false);
                    else host.importAudio(this, true);
                })
                .show();
    }

    /** Hands every still-renderable entry of this playlist to the render queue. */
    private void renderPlaylist() {
        List<Player.PlaybackSource> srcs = resolve();
        if (srcs.isEmpty()) {
            Toast.makeText(getContext(), "歌单里还没有可用的曲目", Toast.LENGTH_SHORT).show();
            return;
        }
        File src = inDir();
        List<Player.PlaybackSource> usable = new ArrayList<>();
        int rendered = 0;
        for (Player.PlaybackSource s : srcs) {
            if (!s.local()) { usable.add(s); continue; }          // a reference always re-renders
            if (com.vstplayer.core.Paths.isUnder(src, s.file)) usable.add(s);
            else rendered++;
        }
        if (usable.isEmpty()) {
            Toast.makeText(getContext(), "曲目都已渲染过，没有需要重渲的源音频", Toast.LENGTH_LONG).show();
            return;
        }
        host.renderTracks(usable, item.name);
    }

    // ------------------------------------------------------------------ add from rendered output

    private void browseRendered(final File root, final File dir) {
        File[] arr = dir.listFiles();
        final List<File> dirs = new ArrayList<>();
        final List<File> files = new ArrayList<>();
        if (arr != null) {
            for (File f : arr) {
                if (f.getName().startsWith(".")) continue;
                if (f.isDirectory()) dirs.add(f);
                else if (isAudioName(f.getName())) files.add(f);
            }
        }
        java.util.Collections.sort(dirs, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        java.util.Collections.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));

        final List<String> rows = new ArrayList<>();
        final List<Runnable> acts = new ArrayList<>();
        if (!dir.equals(root)) {
            rows.add("↑ 上一级");
            final File up = dir.getParentFile();
            acts.add(() -> browseRendered(root, up != null && VstView.isUnder(root, up) ? up : root));
        }
        final List<File> inside = collectAudio(dir);
        if (!inside.isEmpty()) {
            rows.add("＋ 加入本文件夹全部音频（" + inside.size() + " 首）");
            acts.add(() -> addFiles(inside));
        }
        for (final File f : files) {
            boolean have = keyByFile.containsValue(legacyKey(f)) || item.tracks.contains(legacyKey(f));
            rows.add((have ? "✓ " : "· ") + f.getName());
            acts.add(() -> addFiles(java.util.Collections.singletonList(f)));
        }
        for (final File d : dirs) {
            int n = countAudio(d);
            rows.add("▸ " + d.getName() + (n > 0 ? "（" + n + " 首）" : "（空）"));
            acts.add(() -> browseRendered(root, d));
        }
        if (rows.isEmpty()) { Toast.makeText(getContext(), "这个文件夹里没有音频", Toast.LENGTH_SHORT).show(); return; }
        new AlertDialog.Builder(getContext())
                .setTitle("渲染结果 · " + relName(root, dir))
                .setItems(rows.toArray(new String[0]), (d, w) -> acts.get(w).run())
                .setNegativeButton("关闭", null)
                .show();
    }

    private void addFiles(List<File> files) {
        int n = 0;
        for (File f : files) {
            String k = legacyKey(f);
            if (!item.tracks.contains(k)) { item.tracks.add(k); n++; }
        }
        finishAdd(n);
    }

    private void finishAdd(int n) {
        if (n == 0) { Toast.makeText(getContext(), "这些曲目已经在播放列表里了", Toast.LENGTH_SHORT).show(); return; }
        Playlists.save(getContext(), all);
        refresh();
        Toast.makeText(getContext(), "已加入 " + n + " 首", Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ add from the library

    private void pickLibraryRoot() {
        Context c = getContext();
        final LibraryRef lib = LibraryRef.get(c);
        final List<LibraryRef.Root> roots = lib.roots();
        if (roots.isEmpty()) {
            Toast.makeText(c, "资料库还是空的 · 先「导入文件夹」", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] names = new String[roots.size()];
        for (int i = 0; i < roots.size(); i++) {
            names[i] = roots.get(i).name + "（" + lib.tracksOf(roots.get(i).id).size() + " 首）";
        }
        new AlertDialog.Builder(c).setTitle("从资料库添加")
                .setItems(names, (d, w) -> browseLibrary(lib, roots.get(w), ""))
                .setNegativeButton("关闭", null).show();
    }

    private void browseLibrary(final LibraryRef lib, final LibraryRef.Root root, final String relDir) {
        List<LibraryRef.Track> all = lib.tracksOf(root.id);
        final List<String> dirs = new ArrayList<>();
        final List<LibraryRef.Track> files = new ArrayList<>();
        for (LibraryRef.Track t : all) {
            if (!relDir.isEmpty()) {
                if (!t.rel.startsWith(relDir + "/")) continue;
            }
            String rest = relDir.isEmpty() ? t.rel : t.rel.substring(relDir.length() + 1);
            int slash = rest.indexOf('/');
            if (slash > 0) {
                String seg = rest.substring(0, slash);
                if (!dirs.contains(seg)) dirs.add(seg);
            } else {
                files.add(t);
            }
        }
        java.util.Collections.sort(dirs, String::compareToIgnoreCase);
        files.sort((a, b) -> a.rel.compareToIgnoreCase(b.rel));

        final List<String> rows = new ArrayList<>();
        final List<Runnable> acts = new ArrayList<>();
        if (!relDir.isEmpty()) {
            rows.add("↑ 上一级");
            int slash = relDir.lastIndexOf('/');
            final String up = slash > 0 ? relDir.substring(0, slash) : "";
            acts.add(() -> browseLibrary(lib, root, up));
        }
        final List<LibraryRef.Track> inside = new ArrayList<>();
        for (LibraryRef.Track t : all) {
            if (relDir.isEmpty() || t.rel.startsWith(relDir + "/")) inside.add(t);
        }
        if (!inside.isEmpty()) {
            rows.add("＋ 加入本文件夹全部音频（" + inside.size() + " 首）");
            acts.add(() -> addRefs(inside));
        }
        for (final String seg : dirs) {
            rows.add("▸ " + seg);
            acts.add(() -> browseLibrary(lib, root, relDir.isEmpty() ? seg : relDir + "/" + seg));
        }
        for (final LibraryRef.Track t : files) {
            boolean have = item.tracks.contains(Playlists.refKey(t));
            rows.add((have ? "✓ " : "· ") + t.name());
            acts.add(() -> addRefs(java.util.Collections.singletonList(t)));
        }
        if (rows.isEmpty()) { Toast.makeText(getContext(), "这个目录里没有音频", Toast.LENGTH_SHORT).show(); return; }
        new AlertDialog.Builder(getContext())
                .setTitle(root.name + (relDir.isEmpty() ? "" : " · " + relDir))
                .setItems(rows.toArray(new String[0]), (d, w) -> acts.get(w).run())
                .setNegativeButton("关闭", null)
                .show();
    }

    private void addRefs(List<LibraryRef.Track> tracks) {
        int n = 0;
        for (LibraryRef.Track t : tracks) {
            String k = Playlists.refKey(t);
            if (!item.tracks.contains(k)) { item.tracks.add(k); n++; }
        }
        finishAdd(n);
    }

    // ------------------------------------------------------------------ helpers

    private static String baseName(String rel) {
        if (rel == null) return "";
        int slash = rel.lastIndexOf('/');
        return slash < 0 ? rel : rel.substring(slash + 1);
    }

    private static String relName(File root, File dir) {
        if (dir.equals(root)) return "根目录";
        String r = root.getAbsolutePath();
        String a = dir.getAbsolutePath();
        return a.startsWith(r) ? a.substring(Math.min(a.length(), r.length() + 1)) : dir.getName();
    }

    private static boolean isAudioName(String name) {
        String n = name == null ? "" : name.toLowerCase(java.util.Locale.US);
        int dot = n.lastIndexOf('.');
        if (dot < 0) return false;
        String ext = n.substring(dot + 1);
        switch (ext) {
            case "wav": case "wave": case "flac": case "aif": case "aiff": case "aifc":
            case "m4a": case "mp4": case "mp3": case "aac": case "ogg": case "oga":
            case "opus": case "caf":
                return true;
            default:
                return false;
        }
    }

    /** Recursively counts the audio files below {@code dir}. */
    private static int countAudio(File dir) {
        return collectAudio(dir).size();
    }

    private static List<File> collectAudio(File dir) {
        List<File> out = new ArrayList<>();
        collectAudio(dir, out, 0);
        java.util.Collections.sort(out, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return out;
    }

    private static void collectAudio(File dir, List<File> out, int depth) {
        if (dir == null || depth > 8) return;
        File[] arr = dir.listFiles();
        if (arr == null) return;
        for (File f : arr) {
            if (f.isDirectory()) {
                if (!f.getName().startsWith(".")) collectAudio(f, out, depth + 1);
            } else if (!f.getName().startsWith(".") && isAudioName(f.getName())) {
                out.add(f);
            }
        }
    }
}
