package com.vstplayer.core;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal WAV / FLAC / ID3 metadata reader: title, artist, embedded lyrics, cover art, stream info.
 *
 * <p>Every read goes through {@link Seekable}, so the same code reads an app-local file and a file
 * that lives in the user's own storage and is only referenced through SAF. A URI reference is bound
 * to the virtual path the library UI uses for its rows ({@link #bindVirtual}); that keeps every
 * existing {@code Tags.read(File)} caller (cover loader, lyrics, notification) working without
 * changing them.
 */
public final class Tags {

    public static final class Info {
        public String title, artist, album, albumArtist, lyrics;
        public byte[] cover;
        public int sampleRate, channels, bits;
        public long frames;

        public int durationMs() {
            return sampleRate > 0 ? (int) (frames * 1000L / sampleRate) : 0;
        }
    }

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final Charset LATIN = Charset.forName("ISO-8859-1");

    /** Max URIs whose parsed tags stay in memory for this process. */
    private static final int MEMORY_CACHE = 512;

    /** Virtual path ("/vstplayer-ref/...") -> SAF document. Guarded by {@code Tags.class}. */
    private static final Map<String, Uri> VIRTUAL = new HashMap<>();

    /**
     * Fallback lookup for a virtual path that was never bound in this process.
     *
     * <p>The library index owns the mapping; installing it here means a caller that only has a
     * {@code File} (the notification, the cover loader, the player) still reaches the document after
     * a rebind, instead of falling back to a filesystem open that is guaranteed to fail.
     */
    public interface Resolver {
        Uri uriFor(String virtualPath);
    }

    private static volatile Resolver resolver;
    /** Application context used to open references from a plain {@code Tags.read(File)} call. */
    private static Context app;

    private static final Map<String, Info> URI_CACHE =
            new LinkedHashMap<String, Info>(64, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Info> eldest) {
                    return size() > MEMORY_CACHE;
                }
            };

    private Tags() { }

    // ------------------------------------------------------------ virtual refs

    /** Binds the virtual path the library uses for a reference to its SAF document. */
    public static synchronized void bindVirtual(File virtual, Uri uri) {
        if (virtual == null || uri == null) return;
        VIRTUAL.put(virtual.getAbsolutePath(), uri);
    }

    /** Installs the index-backed fallback resolver (see {@link Resolver}); null clears it. */
    public static void setResolver(Resolver r) {
        resolver = r;
    }

    /** Remembers the application context so {@link #read(File)} can open a bound reference. */
    public static synchronized void attach(Context c) {
        if (c != null) app = c.getApplicationContext();
    }

    /** Drops the parsed tags of one document (the file changed on disk). */
    public static synchronized void invalidate(Uri uri) {
        if (uri != null) URI_CACHE.remove(uri.toString());
    }

    /** Drops every binding (and the parsed-tag cache) before the library index is rebound. */
    public static synchronized void clearVirtual() {
        VIRTUAL.clear();
        URI_CACHE.clear();
    }

    /** The SAF document behind a virtual path, or null for a normal file. */
    public static synchronized Uri uriOf(File f) {
        return f == null ? null : VIRTUAL.get(f.getAbsolutePath());
    }

    // ------------------------------------------------------------------ entry

    /**
     * Reads the tags of a file that is either local or a bound SAF reference.
     *
     * <p>Never throws: an unreadable or expired reference yields an empty {@link Info}, which is what
     * the UI shows as "no tags". Use {@link #read(Context, Uri)} when the failure has to be visible.
     */
    public static Info read(File f) {
        return read(f, true);
    }

    /**
     * Same as {@link #read(File)}, but the caller decides whether embedded cover art is loaded.
     *
     * <p>Cover bytes are the only unbounded payload in {@link Info}: one library entry per track
     * multiplied by a multi megabyte picture is how a metadata cache turns into an OOM. Callers
     * that only need text (the library index, lyrics) pass {@code false}; only the cover loader
     * asks for {@code true}.
     */
    public static Info read(File f, boolean withCover) {
        if (f == null) return new Info();
        Uri uri = uriOf(f);
        if (uri == null && resolver != null) {
            // Self-heal: the index knows this virtual path even when the binding was dropped by a
            // reload, so tag lookups never fall through to a filesystem open of a path that cannot
            // exist on disk.
            try {
                uri = resolver.uriFor(f.getAbsolutePath());
            } catch (Throwable ignored) {
                uri = null;
            }
        }
        if (uri == null) {
            try (Seekable s = Seekable.of(f)) {
                return read(s, withCover);
            } catch (Throwable t) {
                Diag.log("tags failed " + f.getName() + ": " + t);
                return new Info();
            }
        }
        try {
            return read(app, uri, withCover);
        } catch (Throwable t) {
            Diag.log("tags failed (ref) " + f.getName() + ": " + t);
            return new Info();
        }
    }

    /**
     * Reads the tags of a SAF document and caches the result for this process.
     *
     * @throws IOException when the document cannot be opened at all (deleted file, revoked
     *                     permission): the caller can mark the entry as broken.
     */
    public static Info read(Context c, Uri uri) throws IOException {
        return read(c, uri, true);
    }

    /**
     * Reads the tags of a SAF document, optionally without embedded cover art.
     *
     * <p>Only the cover carrying form is cached process wide: a coverless read is served straight
     * from the document and never replaces a cached entry, so a later cover request still sees the
     * artwork. That also keeps the shared cache exactly as small in entry count as before.
     */
    public static Info read(Context c, Uri uri, boolean withCover) throws IOException {
        if (uri == null) throw new IOException("null uri");
        String key = uri.toString();
        if (withCover) {
            synchronized (Tags.class) {
                Info hit = URI_CACHE.get(key);
                if (hit != null) return hit;
            }
        }
        if (c == null) throw new IOException("no context for " + uri);
        Info info;
        try (Seekable s = Seekable.of(c, uri)) {
            info = read(s, withCover);
        }
        if (withCover) {
            synchronized (Tags.class) {
                URI_CACHE.put(key, info);
            }
        }
        return info;
    }

    // ------------------------------------------------------------------ parse

    /** Parses the metadata of any seekable source. Never throws. */
    public static Info read(Seekable s) {
        return read(s, true);
    }

    /** Parses the metadata of any seekable source; {@code withCover} also loads embedded art. */
    public static Info read(Seekable s, boolean withCover) {
        Info info = new Info();
        if (s == null) return info;
        if (!s.randomAccess()) {
            // A pipe cannot seek and every reader here seeks; the degradation is documented on
            // Seekable and the reason is logged where the descriptor was opened.
            return info;
        }
        try {
            byte[] magic = new byte[12];
            int n = 0;
            while (n < magic.length) {
                int r = s.read(magic, n, magic.length - n);
                if (r < 0) break;
                n += r;
            }
            if (n < 8) return info;
            String head = new String(magic, 0, 4, LATIN);
            long len = s.size();
            if ("RIFF".equals(head) || "RF64".equals(head)) readWav(s, len, info, withCover);
            else if ("fLaC".equals(head)) readFlac(s, info, withCover);
            else if ("ftyp".equals(new String(magic, 4, 4, LATIN))) {
                // MP4/M4A keeps its tags in atoms; the audio payload is skipped by seeking.
                Mp4Tags m = Mp4Tags.read(s);
                info.title = m.title;
                info.artist = m.artist;
                info.album = m.album;
                info.albumArtist = m.albumArtist;
            }
        } catch (Throwable t) {
            Diag.log("tags failed: " + t);
        }
        return info;
    }

    // ------------------------------------------------------------------ WAV

    private static void readWav(Seekable s, long len, Info info, boolean withCover) throws Exception {
        s.seek(12);
        byte[] hdr = new byte[8];
        while (s.position() + 8 <= len) {
            s.readFully(hdr);
            String id = new String(hdr, 0, 4, LATIN);
            long size = u32(hdr, 4);
            long next = s.position() + size + (size & 1);
            if ("fmt ".equals(id)) {
                byte[] b = new byte[(int) Math.min(size, 40)];
                s.readFully(b);
                info.channels = u16(b, 2);
                info.sampleRate = (int) u32(b, 4);
                info.bits = u16(b, 14);
                if (info.bits == 0 && b.length >= 16) info.bits = 16;
            } else if ("data".equals(id)) {
                if (info.sampleRate > 0 && info.channels > 0 && info.bits > 0) {
                    long bytesPerFrame = (long) info.channels * ((info.bits + 7) / 8);
                    info.frames = size / Math.max(1, bytesPerFrame);
                }
            } else if ("LIST".equals(id)) {
                if (info.title == null || info.artist == null) readListInfo(s, size, info);
            } else if ("id3 ".equals(id) || "ID3 ".equals(id)) {
                byte[] tag = new byte[(int) Math.min(size, 1 << 20)];
                s.readFully(tag);
                readId3(tag, 0, tag.length, info, withCover);
            }
            s.seek(Math.min(next, len));
        }
    }

    private static void readListInfo(Seekable s, long size, Info info) throws Exception {
        byte[] b = new byte[(int) Math.min(size, 1 << 16)];
        s.readFully(b);
        int p = 4; // skip "INFO"
        while (p + 8 <= b.length) {
            String id = new String(b, p, 4, LATIN);
            int n = (int) Math.min(u32(b, p + 4), b.length - p - 8);
            String v = new String(b, p + 8, Math.max(0, n), UTF8).trim();
            int z = v.indexOf('\0');
            if (z >= 0) v = v.substring(0, z).trim();
            if ("INAM".equals(id) && info.title == null) info.title = v;
            if ("IART".equals(id) && info.artist == null) info.artist = v;
            p += 8 + n + (n & 1);
        }
    }

    // ------------------------------------------------------------------ FLAC

    private static void readFlac(Seekable s, Info info, boolean withCover) throws Exception {
        s.seek(4);
        boolean last = false;
        byte[] h = new byte[4];
        while (!last) {
            s.readFully(h);
            last = (h[0] & 0x80) != 0;
            int type = h[0] & 0x7F;
            int size = ((h[1] & 0xFF) << 16) | ((h[2] & 0xFF) << 8) | (h[3] & 0xFF);
            long next = s.position() + size;
            if (type == 0) {
                byte[] b = new byte[18];
                s.readFully(b);
                int packed = ((b[10] & 0xFF) << 24) | ((b[11] & 0xFF) << 16) | ((b[12] & 0xFF) << 8) | (b[13] & 0xFF);
                info.sampleRate = packed >>> 12;
                info.channels = ((packed >>> 9) & 0x7) + 1;
                info.bits = ((packed >>> 4) & 0x1F) + 1;
                long total = ((long) (b[13] & 0x0F) << 32)
                        | ((long) (b[14] & 0xFF) << 24) | ((long) (b[15] & 0xFF) << 16)
                        | ((long) (b[16] & 0xFF) << 8) | (long) (b[17] & 0xFF);
                info.frames = total;
            } else if (type == 4) {
                byte[] b = new byte[Math.min(size, 1 << 18)];
                s.readFully(b);
                Map<String, String> v = parseVorbis(b);
                info.title = v.get("title");
                info.artist = v.get("artist");
                info.lyrics = firstNonEmpty(v.get("lyrics"), v.get("unsyncedlyrics"), v.get("syncedlyrics"));
            } else if (type == 6) {
                // A PICTURE block is read only for the cover loader: a 8 MiB block plus its copy
                // used to be allocated for every track the library merely listed.
                if (!withCover) {
                    s.seek(Math.min(next, s.size() > 0 ? s.size() : next));
                    continue;
                }
                byte[] b = new byte[Math.min(size, 8 << 20)];
                s.readFully(b);
                if (b.length > 32) {
                    int mime = be32(b, 4);
                    int at = 8 + mime;
                    int desc = be32(b, at);
                    at += 4 + desc + 16;
                    int dataLen = be32(b, at);
                    at += 4;
                    if (dataLen > 0 && at + dataLen <= b.length) {
                        info.cover = new byte[dataLen];
                        System.arraycopy(b, at, info.cover, 0, dataLen);
                    }
                }
            }
            s.seek(Math.min(next, s.size() > 0 ? s.size() : next));
        }
    }

    private static Map<String, String> parseVorbis(byte[] b) {
        Map<String, String> map = new HashMap<>();
        int p = 4 + 4; // vendor length + vendor
        int vlen = (int) u32(b, 4);
        p = 8 + vlen;
        if (p + 4 > b.length) return map;
        int count = (int) u32(b, p);
        p += 4;
        for (int i = 0; i < count && p + 4 <= b.length; i++) {
            int n = (int) u32(b, p);
            p += 4;
            if (n < 0 || p + n > b.length) break;
            String kv = new String(b, p, n, UTF8);
            p += n;
            int eq = kv.indexOf('=');
            if (eq > 0) map.put(kv.substring(0, eq).toLowerCase(), kv.substring(eq + 1));
        }
        return map;
    }

    // ------------------------------------------------------------------ ID3v2

    private static void readId3(byte[] b, int off, int len, Info info, boolean withCover) {
        if (len < 10) return;
        if (b[off] != 'I' || b[off + 1] != 'D' || b[off + 2] != '3') return;
        int ver = b[off + 3];
        int size = synchsafe32(b, off + 6);
        int end = Math.min(len, off + 10 + size);
        int p = off + 10;
        if ((b[off + 5] & 0x40) != 0) p += 4; // extended header
        while (p + 10 <= end) {
            String id = new String(b, p, 4, LATIN);
            if (id.charAt(0) == 0) break;
            int fsize = ver >= 4 ? synchsafe32(b, p + 4) : be32(b, p + 4);
            int d = p + 10;
            if (fsize <= 0 || d + fsize > end) break;
            try {
                if ("TIT2".equals(id)) info.title = text(b, d, fsize);
                else if ("TPE1".equals(id)) info.artist = text(b, d, fsize);
                else if ("USLT".equals(id) || "SYLT".equals(id)) info.lyrics = text(b, d, fsize);
                else if (withCover && "APIC".equals(id)) info.cover = apic(b, d, fsize);
            } catch (Throwable ignored) {
            }
            p = d + fsize;
        }
    }

    private static String text(byte[] b, int off, int len) {
        if (len < 2) return null;
        int enc = b[off] & 0xFF;
        Charset cs = enc == 1 ? Charset.forName("UTF-16") : enc == 2 ? Charset.forName("UTF-16BE")
                : enc == 3 ? UTF8 : LATIN;
        String s = new String(b, off + 1, len - 1, cs);
        int z = s.indexOf('\0');
        return z >= 0 ? s.substring(0, z) : s.trim();
    }

    private static byte[] apic(byte[] b, int off, int len) {
        int p = off + 1;
        int mimeEnd = p;
        while (mimeEnd < off + len && b[mimeEnd] != 0) mimeEnd++;
        int q = mimeEnd + 1;
        q++; // picture type
        while (q < off + len && b[q] != 0) q++;
        q++; // description terminator
        if (q >= off + len) return null;
        byte[] out = new byte[off + len - q];
        System.arraycopy(b, q, out, 0, out.length);
        return out;
    }

    private static String firstNonEmpty(String... vs) {
        for (String v : vs) if (v != null && !v.trim().isEmpty()) return v;
        return null;
    }

    /** Big-endian 32-bit word, used by the MP4 and ID3 frame headers. */
    private static int be32(byte[] b, int o) {
        return ((b[o] & 0xFF) << 24) | ((b[o + 1] & 0xFF) << 16)
                | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF);
    }

    /** ID3 size field: 7 significant bits per byte. */
    private static int synchsafe32(byte[] b, int o) {
        return ((b[o] & 0x7F) << 21) | ((b[o + 1] & 0x7F) << 14)
                | ((b[o + 2] & 0x7F) << 7) | (b[o + 3] & 0x7F);
    }

    private static long u32(byte[] b, int o) {
        return ((long) (b[o] & 0xFF))
                | ((long) (b[o + 1] & 0xFF) << 8)
                | ((long) (b[o + 2] & 0xFF) << 16)
                | ((long) (b[o + 3] & 0xFF) << 24);
    }

    private static int u16(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
    }
}
