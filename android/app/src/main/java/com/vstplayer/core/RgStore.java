package com.vstplayer.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tiny persistent cache for ReplayGain measurements, backed by one JSON file.
 *
 * <p>No Android dependency at all: the caller decides where the file lives (on device that is
 * {@code new File(context.getFilesDir(), "replaygain.json")}) and passes it to the constructor.
 * A cache hit requires the same key (the app relative path) and an unchanged size + mtime, so a
 * re-encoded or edited file is measured again.
 *
 * <p>Persistence is best effort: a failed save is reported on stderr but never breaks playback,
 * and a corrupt file is discarded rather than aborting the caller.
 */
public final class RgStore {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int MAX_ENTRIES = 20000;

    /** One cached measurement. */
    public static final class Entry {
        public final String key;
        public final long size;
        public final long mtime;
        public final double lufs;
        public final double peakDb;
        public final int gainDb;
        public final double target;
        /** Wall clock milliseconds when the measurement was stored. */
        public final long at;

        public Entry(String key, long size, long mtime, double lufs, double peakDb, int gainDb,
                     double target, long at) {
            this.key = key;
            this.size = size;
            this.mtime = mtime;
            this.lufs = lufs;
            this.peakDb = peakDb;
            this.gainDb = gainDb;
            this.target = target;
            this.at = at;
        }
    }

    /** Minimum interval between two implicit saves, in milliseconds. */
    private static final long SAVE_MIN_INTERVAL_MS = 30_000L;
    /** An implicit save also needs this many changed entries, so a slow scan still lands. */
    private static final int SAVE_MIN_DIRTY = 100;
    /** Fraction of {@link #MAX_ENTRIES} kept when the cache has to be trimmed. */
    private static final double PRUNE_TO = 0.9;

    private final File file;
    private final Map<String, Entry> entries = new LinkedHashMap<String, Entry>();
    private boolean loaded;
    /** Wall clock of the last successful save, and entries changed since then. */
    private long lastSaveAt;
    private int dirty;

    public RgStore(File json) {
        if (json == null) throw new IllegalArgumentException("json == null");
        this.file = json;
    }

    /** The backing file (may not exist until {@link #save()}). */
    public File file() {
        return file;
    }

    /**
     * Composite identity helper for callers that want one opaque string. The cache itself only
     * needs the relative path as key; size and mtime are validated by {@link #fresh}.
     */
    public static String keyFor(String relativePath, long size, long mtime) {
        String p = relativePath == null ? "" : relativePath.replace('\\', '/');
        return p + '\u0000' + size + '\u0000' + mtime;
    }

    /** True when the entry exists and the file's size and mtime still match. */
    public synchronized boolean fresh(String key, long size, long mtime) {
        ensureLoaded();
        if (key == null) return false;
        Entry e = entries.get(key);
        return e != null && e.size == size && e.mtime == mtime;
    }

    /** Cached entry or null. */
    public synchronized Entry get(String key) {
        ensureLoaded();
        return key == null ? null : entries.get(key);
    }

    /** Store one measurement; call {@link #save()} to persist. */
    public synchronized void put(String key, long size, long mtime, double lufs, double peakDb,
                                int gainDb, double target) {
        if (key == null) return;
        ensureLoaded();
        entries.put(key, new Entry(key, size, mtime, lufs, peakDb, gainDb, target,
                System.currentTimeMillis()));
        dirty++;
        pruneIfNeeded();
    }

    /** Drop one key. */
    public synchronized void remove(String key) {
        ensureLoaded();
        if (key != null && entries.remove(key) != null) dirty++;
    }

    /** Drop every entry (still requires {@link #save()} to hit the disk). */
    public synchronized void clear() {
        ensureLoaded();
        if (!entries.isEmpty()) dirty++;
        entries.clear();
    }

    public synchronized int size() {
        ensureLoaded();
        return entries.size();
    }

    public synchronized List<Entry> all() {
        ensureLoaded();
        return new ArrayList<Entry>(entries.values());
    }

    /** Load the JSON cache; a missing or unreadable file simply starts empty. */
    public synchronized void load() {
        loaded = true;
        entries.clear();
        if (!file.isFile()) return;
        FileInputStream in = null;
        try {
            in = new FileInputStream(file);
            byte[] data = readAll(in, 16 * 1024 * 1024);
            Object root = new Json(new String(data, UTF8)).parse();
            if (!(root instanceof Map)) throw new IOException("root is not an object");
            Object list = ((Map<?, ?>) root).get("entries");
            if (!(list instanceof Map)) return;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) list).entrySet()) {
                String key = String.valueOf(e.getKey());
                if (!(e.getValue() instanceof Map)) continue;
                Map<?, ?> m = (Map<?, ?>) e.getValue();
                entries.put(key, new Entry(key,
                        asLong(m.get("size")), asLong(m.get("mtime")),
                        asDouble(m.get("lufs")), asDouble(m.get("peakDb")),
                        (int) asLong(m.get("gainDb")), asDouble(m.get("target")),
                        asLong(m.get("at"))));
            }
        } catch (Throwable t) {
            entries.clear();
            System.err.println("RgStore: discarding unreadable cache " + file + ": " + t);
        } finally {
            if (in != null) try {
                in.close();
            } catch (IOException ignored) {
                // read only, nothing to recover
            }
        }
    }

    /** Persist now, atomically (temp file + rename). Failures are logged, never thrown. */
    public synchronized void save() {
        saveSerialised();
    }

    /**
     * Persist only when it is worth it: at least {@link #SAVE_MIN_DIRTY} new entries or
     * {@link #SAVE_MIN_INTERVAL_MS} since the last save.
     *
     * <p>A scan used to rewrite (and fsync) the whole JSON every five tracks. With the cache at
     * {@link #MAX_ENTRIES} entries that is a few megabytes per write, and a 1000 track scan wrote
     * that a couple of hundred times for no benefit: the file is a cache, not a journal.
     */
    public synchronized void saveIfDue() {
        if (dirty == 0) return;
        long now = System.currentTimeMillis();
        if (dirty >= SAVE_MIN_DIRTY || now - lastSaveAt >= SAVE_MIN_INTERVAL_MS) saveSerialised();
    }

    private void saveSerialised() {
        ensureLoaded();
        StringBuilder sb = new StringBuilder(entries.size() * 160 + 64);
        sb.append("{\"version\":1,\"entries\":{");
        boolean first = true;
        for (Entry e : entries.values()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(quote(e.key)).append(":{");
            sb.append("\"size\":").append(e.size);
            sb.append(",\"mtime\":").append(e.mtime);
            sb.append(",\"lufs\":").append(num(e.lufs));
            sb.append(",\"peakDb\":").append(num(e.peakDb));
            sb.append(",\"gainDb\":").append(e.gainDb);
            sb.append(",\"target\":").append(num(e.target));
            sb.append(",\"at\":").append(e.at);
            sb.append('}');
        }
        sb.append("}}");
        byte[] data = sb.toString().getBytes(UTF8);
        File dir = file.getAbsoluteFile().getParentFile();
        File tmp = null;
        FileOutputStream out = null;
        boolean ok = false;
        try {
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
                throw new IOException("cannot create " + dir);
            }
            tmp = File.createTempFile(file.getName() + ".", ".tmp", dir);
            out = new FileOutputStream(tmp);
            out.write(data, 0, data.length);
            out.flush();
            out.getFD().sync();
            out.close();
            out = null;
            if (!tmp.renameTo(file)) {
                if (!file.delete() || !tmp.renameTo(file)) {
                    throw new IOException("rename failed for " + file);
                }
            }
            ok = true;
            lastSaveAt = System.currentTimeMillis();
            dirty = 0;
        } catch (Throwable t) {
            System.err.println("RgStore: save failed: " + t);
        } finally {
            if (out != null) try {
                out.close();
            } catch (IOException ignored) {
                // the temp file is removed below
            }
            if (!ok && tmp != null && tmp.exists()) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }
    }

    // ---------------------------------------------------------------- internals

    private void ensureLoaded() {
        if (!loaded) load();
    }

    /**
     * Trims the cache back to {@link #PRUNE_TO} of {@link #MAX_ENTRIES} in one pass.
     *
     * <p>It used to drop exactly one entry per insert, and to find it, it scanned every entry: once
     * the cache was full, each new measurement cost a full 20000 entry sweep.
     */
    private void pruneIfNeeded() {
        if (entries.size() <= MAX_ENTRIES) return;
        int keep = (int) (MAX_ENTRIES * PRUNE_TO);
        int drop = entries.size() - keep;
        if (drop <= 0) return;
        List<Entry> all = new ArrayList<Entry>(entries.values());
        all.sort((a, b) -> Long.compare(a.at, b.at));
        for (int i = 0; i < drop && i < all.size(); i++) {
            entries.remove(all.get(i).key);
            dirty++;
        }
    }

    private static byte[] readAll(InputStream in, int cap) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        byte[] buf = new byte[8192];
        int idle = 0;
        int n;
        while ((n = in.read(buf)) >= 0) {
            if (n == 0) {
                if (++idle > Io.MAX_ZERO_READS) throw new IOException("cache read made no progress");
                continue;
            }
            idle = 0;
            if (out.size() + n > cap) throw new IOException("cache file too large");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static long asLong(Object o) {
        if (o instanceof Number) return ((Number) o).longValue();
        if (o instanceof String) try {
            return Long.parseLong((String) o);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
        return 0L;
    }

    private static double asDouble(Object o) {
        if (o instanceof Number) return ((Number) o).doubleValue();
        if (o instanceof String) try {
            return Double.parseDouble((String) o);
        } catch (NumberFormatException ignored) {
            return Double.NaN;
        }
        return Double.NaN;
    }

    private static String num(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "null";
        // keep it compact but always round trip exact
        String s = Double.toString(v);
        return s;
    }

    private static String quote(String s) {
        StringBuilder b = new StringBuilder(s.length() + 2);
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    b.append("\\\"");
                    break;
                case '\\':
                    b.append("\\\\");
                    break;
                case '\n':
                    b.append("\\n");
                    break;
                case '\r':
                    b.append("\\r");
                    break;
                case '\t':
                    b.append("\\t");
                    break;
                default:
                    if (c < 0x20) b.append("\\u").append(String.format("%04x", (int) c));
                    else b.append(c);
            }
        }
        b.append('"');
        return b.toString();
    }

    /** Minimal JSON reader: objects, arrays, strings, numbers, true/false/null. */
    private static final class Json {
        private final String s;
        private int i;

        Json(String s) {
            this.s = s;
        }

        Object parse() {
            Object v = value();
            ws();
            if (i != s.length()) throw new IllegalArgumentException("trailing data at " + i);
            return v;
        }

        private void ws() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
                else break;
            }
        }

        private Object value() {
            ws();
            if (i >= s.length()) throw new IllegalArgumentException("unexpected end");
            char c = s.charAt(i);
            switch (c) {
                case '{':
                    return object();
                case '[':
                    return array();
                case '"':
                    return string();
                case 't':
                    expect("true");
                    return Boolean.TRUE;
                case 'f':
                    expect("false");
                    return Boolean.FALSE;
                case 'n':
                    expect("null");
                    return null;
                default:
                    return number();
            }
        }

        private Map<String, Object> object() {
            Map<String, Object> m = new HashMap<String, Object>();
            i++; // {
            ws();
            if (i < s.length() && s.charAt(i) == '}') {
                i++;
                return m;
            }
            while (true) {
                ws();
                String k = string();
                ws();
                need(':');
                Object v = value();
                m.put(k, v);
                ws();
                char c = next();
                if (c == ',') continue;
                if (c == '}') return m;
                throw new IllegalArgumentException("expected , or } at " + i);
            }
        }

        private List<Object> array() {
            List<Object> l = new ArrayList<Object>();
            i++;
            ws();
            if (i < s.length() && s.charAt(i) == ']') {
                i++;
                return l;
            }
            while (true) {
                l.add(value());
                ws();
                char c = next();
                if (c == ',') continue;
                if (c == ']') return l;
                throw new IllegalArgumentException("expected , or ] at " + i);
            }
        }

        private String string() {
            need('"');
            StringBuilder b = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c != '\\') {
                    b.append(c);
                    continue;
                }
                char e = s.charAt(i++);
                switch (e) {
                    case '"':
                    case '\\':
                    case '/':
                        b.append(e);
                        break;
                    case 'b':
                        b.append('\b');
                        break;
                    case 'f':
                        b.append('\f');
                        break;
                    case 'n':
                        b.append('\n');
                        break;
                    case 'r':
                        b.append('\r');
                        break;
                    case 't':
                        b.append('\t');
                        break;
                    case 'u':
                        if (i + 4 > s.length()) throw new IllegalArgumentException("bad \\u escape");
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                        break;
                    default:
                        throw new IllegalArgumentException("bad escape \\" + e);
                }
            }
            throw new IllegalArgumentException("unterminated string");
        }

        private Double number() {
            int start = i;
            while (i < s.length()) {
                char c = s.charAt(i);
                if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') i++;
                else break;
            }
            if (start == i) throw new IllegalArgumentException("expected a value at " + i);
            return Double.valueOf(s.substring(start, i));
        }

        private void expect(String word) {
            if (!s.startsWith(word, i)) throw new IllegalArgumentException("expected " + word);
            i += word.length();
        }

        private void need(char c) {
            ws();
            if (i >= s.length() || s.charAt(i) != c) {
                throw new IllegalArgumentException("expected '" + c + "' at " + i);
            }
            i++;
        }

        private char next() {
            if (i >= s.length()) throw new IllegalArgumentException("unexpected end");
            return s.charAt(i++);
        }
    }
}
