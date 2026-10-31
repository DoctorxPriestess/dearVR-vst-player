package com.vstplayer.core;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The library index: <b>references</b> to the user's own audio files instead of copies.
 *
 * <p>Two kinds of root are stored:
 * <ul>
 * <li>{@link #KIND_TREE} — a folder the user granted with {@code ACTION_OPEN_DOCUMENT_TREE}. It is
 * indexed recursively and every track keeps its path relative to that folder.</li>
 * <li>{@link #KIND_DIR} — a synthetic root for single documents picked with
 * {@code ACTION_OPEN_DOCUMENT}. All documents of one provider-relative directory land in the same
 * synthetic root, named after that directory, so multi-select imports stay organised.</li>
 * </ul>
 *
 * <p>The index lives in {@code files/library.json} and is written atomically (sibling temp file,
 * fsync, rename). A file that cannot be parsed is never overwritten: {@link #corrupt()} reports it
 * and {@link #save()} refuses to write.
 *
 * <p>Persisted tag cache: {@code files/tags-cache.json}, keyed by document URI and invalidated by
 * the document's size and mtime, so a warm start does not re-read metadata over SAF.
 */
public final class LibraryRef {

    public static final String KIND_TREE = "tree";
    public static final String KIND_DIR = "dir";

    /** JSON schema version of library.json. */
    private static final int VERSION = 1;
    /** JSON schema version of tags-cache.json. */
    private static final int TAG_VERSION = 1;

    /** Prefix of the virtual paths that identify a referenced track inside the process. */
    private static final File VIRTUAL_ROOT = new File("/vstplayer-ref");

    private static final int MAX_TREE_DEPTH = 10;
    private static final int MAX_TREE_FILES = 5000;

    /** One imported root (a granted folder or a synthetic document directory). */
    public static final class Root {
        public String id = "";
        public String uri = "";
        public String name = "";
        public String kind = KIND_TREE;

        @Override public String toString() {
            return name;
        }
    }

    /** One referenced audio document. */
    public static final class Track {
        public String rootId = "";
        /** Path inside the root, '/'-separated; for {@link #KIND_DIR} roots this is the file name. */
        public String rel = "";
        public String uri = "";
        public long size;
        public long mtime;

        /** Display / file name of this track. */
        public String name() {
            int slash = rel.lastIndexOf('/');
            return slash < 0 ? rel : rel.substring(slash + 1);
        }

        @Override public String toString() {
            return rel;
        }
    }

    /** Parsed tags plus the stamp they were read at. */
    private static final class CachedTag {
        final long size, mtime;
        final Tags.Info info;

        CachedTag(long size, long mtime, Tags.Info info) {
            this.size = size;
            this.mtime = mtime;
            this.info = info;
        }
    }

    private static LibraryRef instance;

    private final Context app;
    private final File file;
    private final File tagsFile;

    private final List<Root> roots = new ArrayList<>();
    private final List<Track> tracks = new ArrayList<>();

    private final Map<String, CachedTag> tagCache = new LinkedHashMap<>();
    private final Set<String> broken = new LinkedHashSet<>();
    private volatile boolean tagsDirty;

    private boolean corrupt;
    private String corruptReason;
    private boolean loaded;

    private LibraryRef(Context c) {
        app = c.getApplicationContext();
        file = new File(app.getFilesDir(), "library.json");
        tagsFile = new File(app.getFilesDir(), "tags-cache.json");
    }

    /** The process-wide index, loaded from disk on first use. */
    public static synchronized LibraryRef get(Context c) {
        if (instance == null) {
            LibraryRef lib = new LibraryRef(c);
            lib.load();
            instance = lib;
        }
        return instance;
    }

    /** Drops the cached instance (used by tests / after a restore). */
    public static synchronized void forget() {
        instance = null;
    }

    public File file() {
        return file;
    }

    // ------------------------------------------------------------ virtual paths

    /** The in-process {@link File} that stands for a referenced track (never exists on disk). */
    public static File virtualFile(Track t) {
        return virtualFile(t == null ? "" : t.rootId, t == null ? "" : t.rel);
    }

    public static File virtualFile(String rootId, String rel) {
        File dir = new File(VIRTUAL_ROOT, rootId == null ? "" : rootId);
        return new File(dir, rel == null ? "" : rel);
    }

    /** True when {@code f} is one of the virtual reference paths. */
    public static boolean isVirtual(File f) {
        if (f == null) return false;
        String p = f.getAbsolutePath();
        return p.startsWith(VIRTUAL_ROOT.getAbsolutePath() + "/");
    }

    /** {rootId, rel} of a virtual path, or null. */
    public static String[] splitVirtual(File f) {
        if (!isVirtual(f)) return null;
        String p = f.getAbsolutePath().substring(VIRTUAL_ROOT.getAbsolutePath().length() + 1);
        int slash = p.indexOf('/');
        if (slash <= 0) return null;
        return new String[]{p.substring(0, slash), p.substring(slash + 1)};
    }

    // ------------------------------------------------------------ read access

    public synchronized List<Root> roots() {
        return new ArrayList<>(roots);
    }

    public synchronized List<Track> tracks() {
        return new ArrayList<>(tracks);
    }

    public synchronized int trackCount() {
        return tracks.size();
    }

    public synchronized Root root(String id) {
        for (Root r : roots) if (r.id.equals(id)) return r;
        return null;
    }

    public synchronized Track track(String rootId, String rel) {
        for (Track t : tracks) if (t.rootId.equals(rootId) && t.rel.equals(rel)) return t;
        return null;
    }

    public synchronized Track trackByVirtual(File f) {
        String[] parts = splitVirtual(f);
        return parts == null ? null : track(parts[0], parts[1]);
    }

    /** The root that was created for this tree URI, or null. */
    public synchronized Root rootByUri(String uri) {
        if (uri == null) return null;
        for (Root r : roots) if (uri.equals(r.uri)) return r;
        return null;
    }

    /** The track that references this document, or null. */
    public synchronized Track trackByUri(String uri) {
        if (uri == null) return null;
        for (Track t : tracks) if (uri.equals(t.uri)) return t;
        return null;
    }

    public synchronized List<Track> tracksOf(String rootId) {
        List<Track> out = new ArrayList<>();
        for (Track t : tracks) if (t.rootId.equals(rootId)) out.add(t);
        return out;
    }

    public synchronized boolean isBroken(Track t) {
        return t != null && broken.contains(t.uri);
    }

    public synchronized boolean corrupt() {
        return corrupt;
    }

    public synchronized String corruptReason() {
        return corruptReason;
    }

    // ------------------------------------------------------------ load / save

    /** Reads the index; a damaged file is kept untouched and reported through {@link #corrupt()}. */
    public synchronized void load() {
        loaded = true;
        roots.clear();
        tracks.clear();
        corrupt = false;
        corruptReason = null;
        Tags.attach(app);
        Tags.clearVirtual();
        readTags();

        if (!file.exists()) {
            rebindTags();
            return;
        }
        try {
            JSONObject root = new JSONObject(readText(file, 8 << 20));
            JSONArray rs = root.optJSONArray("roots");
            if (rs == null) throw new IllegalStateException("no \"roots\" array");
            List<Track> parsed = new ArrayList<>();
            for (int i = 0; i < rs.length(); i++) {
                JSONObject o = rs.optJSONObject(i);
                if (o == null) continue;
                Root r = new Root();
                r.id = o.optString("id", "");
                r.uri = o.optString("uri", "");
                r.name = o.optString("name", "");
                r.kind = o.optString("kind", KIND_TREE);
                if (r.id.isEmpty()) continue;
                roots.add(r);
            }
            JSONArray ts = root.optJSONArray("tracks");
            if (ts != null) {
                for (int i = 0; i < ts.length(); i++) {
                    JSONObject o = ts.optJSONObject(i);
                    if (o == null) continue;
                    Track t = new Track();
                    t.rootId = o.optString("rootId", "");
                    t.rel = o.optString("rel", "");
                    t.uri = o.optString("uri", "");
                    t.size = o.optLong("size", 0);
                    t.mtime = o.optLong("mtime", 0);
                    if (t.rootId.isEmpty() || t.rel.isEmpty() || t.uri.isEmpty()) continue;
                    parsed.add(t);
                }
            }
            tracks.addAll(parsed);
            rebindTags();
        } catch (Throwable e) {
            // Keep the damaged file byte for byte: a later save must not replace it with this state.
            roots.clear();
            tracks.clear();
            corrupt = true;
            corruptReason = e.getClass().getSimpleName() + ": " + e.getMessage();
            Diag.log("library index unreadable (" + file.getName() + " kept): " + corruptReason);
        }
    }

    /** Writes the index atomically; refused while the on-disk file is unreadable. */
    public synchronized boolean save() {
        if (corrupt) {
            Diag.log("library save refused: existing index is unreadable (" + corruptReason + ")");
            return false;
        }
        try {
            JSONObject root = new JSONObject();
            root.put("version", VERSION);
            JSONArray rs = new JSONArray();
            for (Root r : roots) {
                JSONObject o = new JSONObject();
                o.put("id", r.id);
                o.put("uri", r.uri);
                o.put("name", r.name);
                o.put("kind", r.kind);
                rs.put(o);
            }
            JSONArray ts = new JSONArray();
            for (Track t : tracks) {
                JSONObject o = new JSONObject();
                o.put("rootId", t.rootId);
                o.put("rel", t.rel);
                o.put("uri", t.uri);
                o.put("size", t.size);
                o.put("mtime", t.mtime);
                ts.put(o);
            }
            root.put("roots", rs);
            root.put("tracks", ts);
            writeAtomic(file, root.toString().getBytes("UTF-8"));
            Diag.log("library saved: " + roots.size() + " root(s), " + tracks.size() + " track(s)");
            return true;
        } catch (Throwable e) {
            Diag.log("library save failed: " + e);
            return false;
        }
    }

    private void rebindTags() {
        for (Track t : tracks) {
            try {
                Tags.bindVirtual(virtualFile(t), Uri.parse(t.uri));
            } catch (Throwable e) {
                Diag.log("bind failed " + t.rel + ": " + e);
            }
        }
        // The index is the authority for a virtual path, so a caller holding only a File (cover
        // loader, notification, player) still reaches the document after a rebind.
        Tags.setResolver(this::uriForVirtual);
    }

    /** Document URI behind a virtual path ("/vstplayer-ref/<rootId>/<rel>"), or null. */
    private Uri uriForVirtual(String virtualPath) {
        File f = new File(virtualPath == null ? "" : virtualPath);
        synchronized (this) {
            Track t = trackByVirtual(f);
            if (t == null || t.uri == null || t.uri.isEmpty()) return null;
            return Uri.parse(t.uri);
        }
    }

    // ------------------------------------------------------------ indexing

    /**
     * Indexes (or re-indexes) a folder the user granted with {@code ACTION_OPEN_DOCUMENT_TREE}.
     *
     * @param tree   the tree URI (the caller must already hold the persistable permission)
     * @param name   display name of the folder; when null/empty it is read from the provider
     * @return number of audio documents indexed below the tree, or -1 when the tree is unusable
     */
    public synchronized int addTree(Context c, Uri tree, String name) {
        if (corrupt) return -1;
        if (tree == null) return -1;
        String treeId;
        try {
            treeId = DocumentsContract.getTreeDocumentId(tree);
        } catch (Throwable e) {
            Diag.log("tree id failed: " + e);
            return -1;
        }
        if (treeId == null) return -1;

        // The display name is the first level of the address tree; read it before the id is built.
        String label = resolveTreeName(c, tree, treeId);
        Root root = null;
        for (Root r : roots) {
            if (KIND_TREE.equals(r.kind) && tree.toString().equals(r.uri)) { root = r; break; }
        }
        if (root == null) {
            root = new Root();
            root.kind = KIND_TREE;
            root.uri = tree.toString();
            root.name = cleanName(name);
            if (root.name.isEmpty()) root.name = label;
            if (root.name.isEmpty()) root.name = "已导入文件夹";
            root.id = newRootId(root.name, tree.toString());
            roots.add(root);
        } else {
            String cleaned = cleanName(name);
            if (!cleaned.isEmpty()) root.name = cleaned;
        }

        List<Track> found = new ArrayList<>();
        try {
            walk(c, tree, treeId, "", root.id, found, 0);
        } catch (Throwable e) {
            Diag.log("tree walk failed: " + e);
        }
        if (found.isEmpty()) {
            // Nothing found: keep a previously indexed root intact instead of wiping it.
            if (tracksOf(root.id).isEmpty()) return 0;
            return tracksOf(root.id).size();
        }
        // Re-index replaces this root's entries; playlist keys (rootId + "/" + rel) stay valid
        // for every file that still exists.
        final String rid = root.id;
        tracks.removeIf(t -> t.rootId.equals(rid));
        tracks.addAll(found);
        save();
        return found.size();
    }

    private void walk(Context c, Uri tree, String dirId, String relDir, String rootId,
                      List<Track> out, int depth) {
        if (depth > MAX_TREE_DEPTH || out.size() >= MAX_TREE_FILES) return;
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, dirId);
        String[] cols = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED};
        ContentResolver cr = c.getContentResolver();
        try (Cursor cur = cr.query(children, cols, null, null, null)) {
            if (cur == null) return;
            while (cur.moveToNext() && out.size() < MAX_TREE_FILES) {
                String id = cur.getString(0);
                String display = cur.getString(1);
                String mime = cur.getString(2);
                long size = cur.isNull(3) ? 0 : cur.getLong(3);
                long mtime = cur.isNull(4) ? 0 : cur.getLong(4);
                String seg = cleanSegment(display);
                if (seg.isEmpty()) continue;
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    walk(c, tree, id, relDir.isEmpty() ? seg : relDir + "/" + seg, rootId, out, depth + 1);
                } else if ((mime != null && mime.startsWith("audio/")) || Paths.isAudioName(seg)) {
                    Track t = new Track();
                    t.rootId = rootId;
                    t.rel = relDir.isEmpty() ? seg : relDir + "/" + seg;
                    t.uri = DocumentsContract.buildDocumentUriUsingTree(tree, id).toString();
                    t.size = size;
                    t.mtime = mtime;
                    out.add(t);
                }
            }
        } catch (Throwable e) {
            Diag.log("tree walk " + dirId + ": " + e);
        }
    }

    private String resolveTreeName(Context c, Uri tree, String treeId) {
        try {
            Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, treeId);
            try (Cursor cur = c.getContentResolver().query(doc,
                    new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
                if (cur != null && cur.moveToFirst() && cur.getString(0) != null) {
                    return cleanSegment(cur.getString(0));
                }
            }
        } catch (Throwable e) {
            Diag.log("tree name: " + e);
        }
        return "";
    }

    /**
     * Adds picked documents (multi-select) as references, grouping them by provider-relative
     * directory: the external storage provider's document id is the real path
     * ({@code primary:Music/Album/x.flac}), so the directory becomes the synthetic root's name.
     *
     * @return number of new tracks (duplicates are skipped)
     */
    public synchronized int addDocuments(Context c, List<Uri> uris) {
        if (corrupt || uris == null || uris.isEmpty()) return 0;
        ContentResolver cr = c.getContentResolver();
        int added = 0;
        for (Uri uri : uris) {
            if (uri == null) continue;
            if (existingByUri(uri.toString()) != null) continue;   // same document imported twice
            String path = documentPath(uri);                       // "Music/Album/x.flac" or null
            String dir;
            String rel;
            if (path != null) {
                int slash = path.lastIndexOf('/');
                dir = slash > 0 ? path.substring(0, slash) : "";
                rel = slash >= 0 ? path.substring(slash + 1) : path;
            } else {
                dir = "";
                rel = "";
            }
            String display = displayName(cr, uri);
            if (rel.isEmpty()) rel = cleanSegment(display);
            if (rel.isEmpty()) rel = "audio-" + Integer.toHexString(uri.hashCode());
            Root root = syntheticRoot(dir);
            if (track(root.id, rel) != null) continue;              // same root + rel
            long[] stamp = stat(cr, uri);
            Track t = new Track();
            t.rootId = root.id;
            t.rel = rel;
            t.uri = uri.toString();
            t.size = stamp[0];
            t.mtime = stamp[1];
            tracks.add(t);
            added++;
        }
        if (added > 0) save();
        return added;
    }

    /** The synthetic (document directory) root for {@code dir}, created on demand. */
    private Root syntheticRoot(String dir) {
        String name = dir.isEmpty() ? "已导入文件" : dir;
        String id = newRootId(name, "dir:" + name);
        for (Root r : roots) {
            if (KIND_DIR.equals(r.kind) && r.id.equals(id)) return r;
        }
        Root r = new Root();
        r.id = id;
        r.kind = KIND_DIR;
        r.uri = "";
        r.name = name;
        roots.add(r);
        return r;
    }

    private Track existingByUri(String uri) {
        for (Track t : tracks) if (t.uri.equals(uri)) return t;
        return null;
    }

    // ------------------------------------------------------------ mutation

    public synchronized boolean removeTrack(Track t) {
        if (t == null || corrupt) return false;
        boolean removed = tracks.removeIf(x -> x.rootId.equals(t.rootId) && x.rel.equals(t.rel));
        if (removed) save();
        return removed;
    }

    /** Removes a root and every reference below it; the user's files are untouched. */
    public synchronized boolean removeRoot(Root r) {
        if (r == null || corrupt) return false;
        for (Track t : tracksOf(r.id)) {
            broken.remove(t.uri);
            tagCache.remove(t.uri);
        }
        boolean removed = roots.removeIf(x -> x.id.equals(r.id));
        tracks.removeIf(t -> t.rootId.equals(r.id));
        if (removed) save();
        return removed;
    }

    /** Re-reads size / mtime of one reference (used before a tag read and by the copy step). */
    public synchronized boolean refresh(Track t) {
        if (t == null) return false;
        long[] stamp = stat(app.getContentResolver(), Uri.parse(t.uri));
        t.size = stamp[0];
        t.mtime = stamp[1];
        return true;
    }

    // ------------------------------------------------------------ tags

    /**
     * Tags for one reference: memory cache, then the persisted slim cache (validated by
     * size + mtime), then a real SAF read. A failed open marks the entry as broken.
     */
    public Tags.Info tagsOf(Track t) {
        if (t == null) return new Tags.Info();
        CachedTag hit;
        synchronized (this) {
            hit = tagCache.get(t.uri);
            if (hit != null && hit.size == t.size && hit.mtime == t.mtime) return hit.info;
            if (broken.contains(t.uri) && hit == null) return new Tags.Info();
        }
        Tags.Info info;
        Uri uri = Uri.parse(t.uri);
        if (hit != null) {
            // The document changed since it was cached: drop the process-wide copy as well so the
            // persistent entry cannot be refreshed from a stale in-memory one.
            Tags.invalidate(uri);
        }
        try {
            info = Tags.read(app, uri);
        } catch (Throwable e) {
            synchronized (this) {
                broken.add(t.uri);
            }
            Diag.log("tag read failed (marked invalid) " + t.rel + ": " + e);
            return new Tags.Info();
        }
        synchronized (this) {
            tagCache.put(t.uri, new CachedTag(t.size, t.mtime, info));
            broken.remove(t.uri);
            tagsDirty = true;
        }
        return info;
    }

    /**
     * Tags already known for this reference (memory + persisted cache, validated by size/mtime),
     * or null when they still have to be read. Lets the UI paint immediately and fill in the rest.
     */
    public synchronized Tags.Info cachedTags(Track t) {
        if (t == null) return null;
        CachedTag hit = tagCache.get(t.uri);
        return hit != null && hit.size == t.size && hit.mtime == t.mtime ? hit.info : null;
    }

    /** Number of references whose tags are already cached. */
    public synchronized int cachedTagCount() {
        return tagCache.size();
    }

    /** True when at least one tag read failed since the last {@link #clearBroken()} call. */
    public synchronized boolean hasBroken() {
        return !broken.isEmpty();
    }

    public synchronized void clearBroken() {
        broken.clear();
    }

    private void readTags() {
        tagCache.clear();
        if (!tagsFile.exists()) return;
        try {
            JSONObject root = new JSONObject(readText(tagsFile, 16 << 20));
            JSONObject entries = root.optJSONObject("entries");
            if (entries == null) return;
            java.util.Iterator<String> it = entries.keys();
            while (it.hasNext()) {
                String uri = it.next();
                JSONObject o = entries.optJSONObject(uri);
                if (o == null) continue;
                Tags.Info info = new Tags.Info();
                info.title = optString(o, "title");
                info.artist = optString(o, "artist");
                info.album = optString(o, "album");
                info.albumArtist = optString(o, "albumArtist");
                info.sampleRate = o.optInt("sampleRate", 0);
                info.channels = o.optInt("channels", 0);
                info.bits = o.optInt("bits", 0);
                info.frames = o.optLong("frames", 0);
                tagCache.put(uri, new CachedTag(o.optLong("size", 0), o.optLong("mtime", 0), info));
            }
        } catch (Throwable e) {
            // The cache is only an optimisation: a damaged one is ignored, not fatal.
            Diag.log("tags cache unreadable, ignored: " + e);
            tagCache.clear();
        }
    }

    private static String optString(JSONObject o, String key) {
        String v = o.optString(key, null);
        return v == null || v.isEmpty() ? null : v;
    }

    /** Persists the slim tag cache (title/artist/album + stream info, never cover art). */
    public synchronized void flushTags() {
        if (!tagsDirty) return;
        tagsDirty = false;
        try {
            JSONObject root = new JSONObject();
            root.put("version", TAG_VERSION);
            JSONObject entries = new JSONObject();
            for (Map.Entry<String, CachedTag> e : tagCache.entrySet()) {
                CachedTag c = e.getValue();
                JSONObject o = new JSONObject();
                o.put("size", c.size);
                o.put("mtime", c.mtime);
                if (c.info.title != null) o.put("title", c.info.title);
                if (c.info.artist != null) o.put("artist", c.info.artist);
                if (c.info.album != null) o.put("album", c.info.album);
                if (c.info.albumArtist != null) o.put("albumArtist", c.info.albumArtist);
                o.put("sampleRate", c.info.sampleRate);
                o.put("channels", c.info.channels);
                o.put("bits", c.info.bits);
                o.put("frames", c.info.frames);
                entries.put(e.getKey(), o);
            }
            root.put("entries", entries);
            writeAtomic(tagsFile, root.toString().getBytes("UTF-8"));
        } catch (Throwable e) {
            Diag.log("tags cache save failed: " + e);
        }
    }

    // ------------------------------------------------------------ platform helpers

    /** {size, lastModified} of a document; {0, 0} when the provider does not report them. */
    private static long[] stat(ContentResolver cr, Uri uri) {
        long[] out = {0, 0};
        String[] cols = {OpenableColumns.SIZE};
        try (Cursor cur = cr.query(uri, cols, null, null, null)) {
            if (cur != null && cur.moveToFirst() && !cur.isNull(0)) out[0] = cur.getLong(0);
        } catch (Throwable ignored) {
        }
        String[] cols2 = {DocumentsContract.Document.COLUMN_LAST_MODIFIED};
        try (Cursor cur = cr.query(uri, cols2, null, null, null)) {
            if (cur != null && cur.moveToFirst() && !cur.isNull(0)) out[1] = cur.getLong(0);
        } catch (Throwable ignored) {
        }
        if (out[1] == 0) {
            // DocumentsContract's column is not exposed by every provider; the generic one often is.
            String[] cols3 = {"last_modified"};
            try (Cursor cur = cr.query(uri, cols3, null, null, null)) {
                if (cur != null && cur.moveToFirst() && !cur.isNull(0)) out[1] = cur.getLong(0);
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    private static String displayName(ContentResolver cr, Uri uri) {
        try (Cursor cur = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cur != null && cur.moveToFirst() && cur.getString(0) != null) return cur.getString(0);
        } catch (Throwable ignored) {
        }
        return "";
    }

    /**
     * Provider-relative path of a document id ("primary:Music/Album/x.flac" -> "Music/Album/x.flac"),
     * or null for a provider whose ids are opaque.
     */
    private static String documentPath(Uri uri) {
        if (uri == null) return null;
        // Only the external storage provider's id is a real path ("primary:Music/Album/x.flac").
        // Other providers use opaque ids (downloads: "msf:1234"), which must not become a path.
        String auth = uri.getAuthority();
        if (auth == null || !auth.equals("com.android.externalstorage.documents")) return null;
        try {
            String id = DocumentsContract.getDocumentId(uri);
            if (id == null) return null;
            int colon = id.indexOf(':');
            if (colon < 0) return null;
            String path = id.substring(colon + 1);
            if (path.isEmpty()) return null;
            return path.startsWith("/") ? path.substring(1) : path;
        } catch (Throwable e) {
            return null;
        }
    }

    /** Root id that is safe as one path segment and stable for the same address. */
    private static String newRootId(String name, String seed) {
        String base = cleanSegment(name == null || name.isEmpty() ? "root" : name).replace('/', '_');
        if (base.length() > 48) base = base.substring(0, 48);
        int hash = seed == null ? 0 : seed.hashCode();
        return base + "-" + Integer.toHexString(hash & 0x7fffffff);
    }

    private static String cleanName(String name) {
        if (name == null) return "";
        String n = name.trim();
        return n.replace('/', '_');
    }

    /** One path segment of a SAF display name, safe for JSON keys and virtual paths. */
    public static String cleanSegment(String seg) {
        if (seg == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char ch : seg.toCharArray()) {
            if (ch < 32 || ch == '/' || ch == '\\' || ch == ':' || ch == '*' || ch == '?'
                    || ch == '"' || ch == '<' || ch == '>' || ch == '|') sb.append('_');
            else sb.append(ch);
        }
        String s = sb.toString().trim();
        while (s.startsWith(".")) s = s.substring(1);
        if (s.length() > 80) s = s.substring(0, 80);
        return s;
    }

    // ------------------------------------------------------------ io helpers

    private static String readText(File f, int max) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) Math.min(Math.max(64, f.length()), max)];
            int off = 0, n;
            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
            return new String(buf, 0, off, "UTF-8");
        }
    }

    /** Sibling temp file, fsync, rename: a crash leaves either the old or the new file. */
    public static void writeAtomic(File target, byte[] data) throws IOException {
        File dir = target.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
        File tmp = new File(dir, target.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
            out.flush();
            out.getFD().sync();
        }
        if (!tmp.renameTo(target)) {
            tmp.delete();
            throw new IOException("rename failed: " + tmp + " -> " + target);
        }
    }
}
