package com.vstplayer.core;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Named playlists, stored as JSON in the app's private directory.
 *
 * <p>A playlist entry is a string that expresses one of three things:
 *
 * <ul>
 * <li>a rendered track — its path relative to {@code work/out} ("Album/x_vst.wav");</li>
 * <li>a legacy import — its path relative to {@code work/in} (bare file names from before folder
 * support are just paths at the root and keep resolving);</li>
 * <li>a SAF reference — {@code "saf:" + rootId + "/" + rel}, see {@link #refKey}. The index in
 * {@code files/library.json} is the authority for what that address currently points at.</li>
 * </ul>
 *
 * <p>The JSON shape is unchanged ({@code tracks} is an array of strings), only the version number
 * moved to 2, so an older build still reads the file and just shows the reference rows as missing.
 */
public final class Playlists {

    /** Prefix that marks a playlist entry as a library reference rather than a local path. */
    public static final String REF_PREFIX = "saf:";

    public static final class Item {
        public String id = UUID.randomUUID().toString();
        public String name = "";
        public final List<String> tracks = new ArrayList<>();

        /** Number of entries that point at a library reference. */
        public int refCount() {
            int n = 0;
            for (String s : tracks) if (isRef(s)) n++;
            return n;
        }
    }

    /** Set when the file exists but cannot be parsed: it is kept on disk untouched and saving is refused. */
    private static boolean corrupt;
    private static String corruptReason;

    private Playlists() { }

    // ------------------------------------------------------------ reference keys

    /** Stable playlist key for a referenced track. */
    public static String refKey(String rootId, String rel) {
        return REF_PREFIX + (rootId == null ? "" : rootId) + "/" + (rel == null ? "" : rel);
    }

    /** True when this entry addresses a library reference. */
    public static boolean isRef(String key) {
        return key != null && key.startsWith(REF_PREFIX);
    }

    /** {rootId, rel} of a reference entry, or null when it is a plain path. */
    public static String[] splitRef(String key) {
        if (!isRef(key)) return null;
        String rest = key.substring(REF_PREFIX.length());
        int slash = rest.indexOf('/');
        if (slash <= 0 || slash + 1 >= rest.length()) return null;
        return new String[]{rest.substring(0, slash), rest.substring(slash + 1)};
    }

    /** Reference key of a track in the index. */
    public static String refKey(LibraryRef.Track t) {
        return refKey(t == null ? "" : t.rootId, t == null ? "" : t.rel);
    }

    public static File file(Context c) {
        return new File(c.getFilesDir(), "playlists.json");
    }

    /** True when the last {@link #load(Context)} found a file it could not parse (the file is kept). */
    public static synchronized boolean corrupt() { return corrupt; }

    /** Human readable reason for {@link #corrupt()}, or null. */
    public static synchronized String corruptReason() { return corruptReason; }

    public static synchronized List<Item> load(Context c) {
        List<Item> out = new ArrayList<>();
        File f = file(c);
        if (!f.exists()) {
            // Nothing on disk: an empty list is the correct answer, not a failure.
            corrupt = false;
            corruptReason = null;
            return out;
        }
        List<Item> parsed = new ArrayList<>();
        try {
            String s = read(f);
            JSONObject root = new JSONObject(s);
            JSONArray arr = root.optJSONArray("playlists");
            if (arr == null) throw new IllegalStateException("no \"playlists\" array");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Item it = new Item();
                it.id = o.optString("id", it.id);
                it.name = o.optString("name", "未命名");
                JSONArray t = o.optJSONArray("tracks");
                if (t != null) for (int k = 0; k < t.length(); k++) {
                    String v = t.optString(k, null);
                    if (v != null && !v.isEmpty()) it.tracks.add(v);
                }
                parsed.add(it);
            }
            corrupt = false;
            corruptReason = null;
            out = parsed;
        } catch (Throwable e) {
            // Keep the damaged file byte for byte; never let a later save replace it with this empty list.
            corrupt = true;
            corruptReason = e.getClass().getSimpleName() + ": " + e.getMessage();
            Diag.log("playlists load failed (" + f.getName() + " kept): " + corruptReason);
        }
        return out;
    }

    public static synchronized void save(Context c, List<Item> items) {
        if (corrupt) {
            // Writing here would destroy the only copy of a playlist file we failed to read.
            Diag.log("playlists save refused: existing file is unreadable (" + corruptReason + ")");
            return;
        }
        try {
            JSONArray arr = new JSONArray();
            for (Item it : items) {
                JSONObject o = new JSONObject();
                o.put("id", it.id);
                o.put("name", it.name);
                JSONArray t = new JSONArray();
                for (String s : it.tracks) t.put(s);
                o.put("tracks", t);
                arr.put(o);
            }
            JSONObject root = new JSONObject();
            root.put("version", 2);
            root.put("playlists", arr);
            LibraryRef.writeAtomic(file(c), root.toString().getBytes("UTF-8"));
            Diag.log("playlists saved: " + items.size());
        } catch (Throwable e) {
            Diag.log("playlists save failed: " + e);
        }
    }

    private static String read(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int off = 0, n;
            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
            return new String(buf, 0, off, "UTF-8");
        }
    }
}
