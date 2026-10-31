package com.vstplayer.core;

import java.io.File;
import java.nio.charset.Charset;

/**
 * Reads the four iTunes-style metadata fields out of an MP4/M4A container.
 *
 * <p>Only the atom chain that matters is walked (moov -> udta -> meta -> ilst); the audio payload
 * (mdat, which is most of the file) is skipped with a seek, so this stays cheap enough to run over a
 * whole library — including a library that is referenced through SAF, where every read is a binder
 * call. See {@link Seekable}.
 */
public final class Mp4Tags {

    private static final Charset LATIN = Charset.forName("ISO-8859-1");
    private static final Charset UTF8 = Charset.forName("UTF-8");

    public String title, artist, album, albumArtist;
    public boolean any;

    private Mp4Tags() { }

    /** Convenience overload for a local file. */
    public static Mp4Tags read(File f) {
        Mp4Tags out = new Mp4Tags();
        if (f == null) return out;
        try (Seekable s = Seekable.of(f)) {
            return read(s);
        } catch (Throwable t) {
            Diag.log("mp4 tags failed " + f.getName() + ": " + t);
            return out;
        }
    }

    /** Reads the tags from any seekable source (plain file or SAF document). Never throws. */
    public static Mp4Tags read(Seekable s) {
        Mp4Tags out = new Mp4Tags();
        if (s == null) return out;
        try {
            long len = s.size();
            long moov = findAtom(s, 0, len, "moov");
            if (moov < 0) return out;
            long end = Math.min(len, moov + atomSize(s, moov));
            long udta = findAtom(s, moov + 8, end, "udta");
            if (udta < 0) return out;
            long udtaEnd = Math.min(end, udta + atomSize(s, udta));
            long meta = findAtom(s, udta + 8, udtaEnd, "meta");
            if (meta < 0) return out;
            long metaEnd = Math.min(udtaEnd, meta + atomSize(s, meta));
            // "meta" is a full atom: 4 bytes of version/flags before its children.
            long ilst = findAtom(s, meta + 12, metaEnd, "ilst");
            if (ilst < 0) return out;
            long ilstEnd = Math.min(metaEnd, ilst + atomSize(s, ilst));
            long p = ilst + 8;
            for (int guard = 0; p + 8 <= ilstEnd && guard < 4096; guard++) {
                long size = atomSize(s, p);
                if (size < 8) break;
                s.seek(p + 4);
                byte[] name = new byte[4];
                s.readFully(name);
                String key = new String(name, LATIN);
                String value = readItem(s, p + 8, p + size);
                if (value != null && !value.isEmpty()) {
                    if ("\u00A9nam".equals(key) && out.title == null) out.title = value;
                    else if ("\u00A9ART".equals(key) && out.artist == null) out.artist = value;
                    else if ("\u00A9alb".equals(key) && out.album == null) out.album = value;
                    else if ("aART".equals(key) && out.albumArtist == null) out.albumArtist = value;
                }
                p += size;
            }
            out.any = out.title != null || out.artist != null || out.album != null || out.albumArtist != null;
        } catch (Throwable t) {
            Diag.log("mp4 tags failed: " + t);
        }
        return out;
    }

    /** The payload of an ilst item: its "data" child holds 4 bytes of type, 4 of locale, then text. */
    private static String readItem(Seekable s, long from, long to) {
        try {
            long p = from;
            for (int guard = 0; p + 8 <= to && guard < 16; guard++) {
                long size = atomSize(s, p);
                if (size < 8) return null;
                s.seek(p + 4);
                byte[] name = new byte[4];
                s.readFully(name);
                if ("data".equals(new String(name, LATIN))) {
                    long payload = p + 16;                 // 4 size + 4 "data" + 4 type + 4 locale
                    long payloadLen = Math.min(size - 16, to - payload);
                    if (payloadLen <= 0 || payloadLen > (1 << 20)) return null;
                    s.seek(payload);
                    return new String(s.read((int) payloadLen), UTF8).trim();
                }
                p += size;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static long atomSize(Seekable s, long at) {
        try {
            s.seek(at);
            byte[] head = new byte[8];
            s.readFully(head);
            long size = ((long) (head[0] & 0xFF) << 24) | ((long) (head[1] & 0xFF) << 16)
                    | ((long) (head[2] & 0xFF) << 8) | (long) (head[3] & 0xFF);
            size &= 0xFFFFFFFFL;
            if (size == 1) {                     // 64 bit extended size
                size = ((long) (head[4] & 0xFF) << 56) | ((long) (head[5] & 0xFF) << 48)
                        | ((long) (head[6] & 0xFF) << 40) | ((long) (head[7] & 0xFF) << 32);
                byte[] rest = new byte[8];
                s.readFully(rest);
                size |= ((long) (rest[0] & 0xFF) << 24) | ((long) (rest[1] & 0xFF) << 16)
                        | ((long) (rest[2] & 0xFF) << 8) | (long) (rest[3] & 0xFF);
            }
            return size < 8 ? 8 : size;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** First direct child atom with this name in [from, to), or -1. */
    private static long findAtom(Seekable s, long from, long to, String name) {
        try {
            long p = from;
            for (int guard = 0; p + 8 <= to && guard < 4096; guard++) {
                long size = atomSize(s, p);
                if (size < 8) return -1;
                s.seek(p + 4);
                byte[] b = new byte[4];
                s.readFully(b);
                if (name.equals(new String(b, LATIN))) return p;
                p += size;
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    static String stem(String name) {
        int dot = name == null ? -1 : name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : (name == null ? "" : name);
    }
}
