package com.vstplayer.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ReplayGain tag reader/writer for FLAC (Vorbis comment) and integer PCM WAV (ID3v2.3 TXXX in a
 * {@code 'id3 '} chunk).
 *
 * <p>Pure Java, no Android APIs, no third party dependencies. Every write goes to a temporary file
 * in the same directory and is then renamed over the original, so a failure (or a dying process)
 * cannot leave a half written file behind. The audio payload is copied verbatim: only container
 * metadata is rebuilt.
 *
 * <p>WAV support is deliberately limited to integer PCM 16/24/32 bit. IEEE float WAV files are
 * rejected with an explicit {@link IOException} instead of being silently mangled.
 */
public final class RgTags {

    public static final String TRACK_GAIN = "REPLAYGAIN_TRACK_GAIN";
    public static final String TRACK_PEAK = "REPLAYGAIN_TRACK_PEAK";
    public static final String ALBUM_GAIN = "REPLAYGAIN_ALBUM_GAIN";
    public static final String ALBUM_PEAK = "REPLAYGAIN_ALBUM_PEAK";

    private static final Charset ASCII = Charset.forName("ISO-8859-1");
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final Charset UTF16LE = Charset.forName("UTF-16LE");
    private static final int MAX_ID3 = 16 * 1024 * 1024;
    private static final int COPY_BUF = 64 * 1024;
    private static final String VENDOR_FALLBACK = "vst3_player";

    private RgTags() {
    }

    // ---------------------------------------------------------------- public API

    /** Read the REPLAYGAIN_* fields of a FLAC file; empty when the file carries none. */
    public static Map<String, String> readFlac(File f) throws IOException {
        if (f == null || !f.isFile()) throw new IOException("no such file: " + f);
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            List<FlacBlock> blocks = flacBlocks(raf, raf.length());
            for (int i = 0; i < blocks.size(); i++) {
                FlacBlock b = blocks.get(i);
                if (b.type != 4) continue;
                return replayGainOfMap(parseVorbisMap(readRange(raf, b.offset, b.length)));
            }
        }
        return new LinkedHashMap<String, String>();
    }

    /** Read the ReplayGain TXXX frames of a WAV file; empty when the file carries none. */
    public static Map<String, String> readWav(File f) throws IOException {
        if (f == null || !f.isFile()) throw new IOException("no such file: " + f);
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            WavChunks chunks = scanWav(raf, raf.length(), f, false);
            for (int i = 0; i < chunks.chunks.size(); i++) {
                WavChunk c = chunks.chunks.get(i);
                if (!c.isId3()) continue;
                long size = Math.min(c.size, MAX_ID3);
                byte[] tag = readRange(raf, c.dataOffset, size);
                return id3ReplayGain(tag);
            }
        }
        return new LinkedHashMap<String, String>();
    }

    /** Read ReplayGain tags from a FLAC or WAV file, dispatched on the file signature. */
    public static Map<String, String> read(File f) throws IOException {
        if (f == null || !f.isFile()) throw new IOException("no such file: " + f);
        if (isFlac(f)) return readFlac(f);
        if (isMp4(f)) return readMp4(f);
        return readWav(f);
    }

    /** True when a non empty track gain and peak are present. */
    public static boolean hasReplayGain(Map<String, String> tags) {
        return tags != null && notEmpty(tags.get(TRACK_GAIN)) && notEmpty(tags.get(TRACK_PEAK));
    }

    /**
     * Merge {@code tags} into the single VORBIS_COMMENT block of a FLAC file, keeping every other
     * metadata block and the audio frames byte for byte. A null value removes that key.
     *
     * @param tags key/value pairs; keys are matched case insensitively and stored upper case
     */
    public static void writeFlac(File f, Map<String, String> tags) throws IOException {
        if (f == null || !f.isFile()) throw new IOException("no such file: " + f);
        if (tags == null) throw new IOException("tags == null");
        long len = f.length();
        byte[] vendor;
        List<String[]> comments;
        long audioStart;
        List<FlacBlock> blocks = new ArrayList<FlacBlock>();
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            blocks = flacBlocks(raf, len);
            FlacBlock vc = null;
            for (int i = 0; i < blocks.size(); i++) {
                if (blocks.get(i).type == 4) {
                    vc = blocks.get(i);
                    break;
                }
            }
            if (vc != null) {
                byte[] body = readRange(raf, vc.offset, vc.length);
                vendor = vorbisVendor(body);
                comments = vorbisComments(body);
            } else {
                vendor = VENDOR_FALLBACK.getBytes(UTF8);
                comments = new ArrayList<String[]>();
            }
            FlacBlock last = blocks.get(blocks.size() - 1);
            audioStart = last.offset + last.length;
        }

        List<String[]> merged = merge(comments, tags);
        byte[] vorbis = buildVorbis(vendor, merged);
        if (vorbis.length > 0xFFFFFF) throw new IOException("vorbis comment block too large");

        // Rebuild the metadata section: every block in order, the first VORBIS_COMMENT replaced,
        // later duplicates dropped, a fresh one appended when the file had none.
        ByteArrayOutputStream head = new ByteArrayOutputStream(4096);
        head.write(new byte[]{'f', 'L', 'a', 'C'}, 0, 4);
        boolean wroteVorbis = false;
        int outCount = 0;
        List<byte[]> blockBodies = new ArrayList<byte[]>();
        List<Integer> blockTypes = new ArrayList<Integer>();
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            for (int i = 0; i < blocks.size(); i++) {
                FlacBlock b = blocks.get(i);
                if (b.type == 4) {
                    if (wroteVorbis) continue;
                    blockTypes.add(4);
                    blockBodies.add(vorbis);
                    wroteVorbis = true;
                } else {
                    blockTypes.add(b.type);
                    blockBodies.add(readRange(raf, b.offset, b.length));
                }
            }
        }
        if (!wroteVorbis) {
            blockTypes.add(4);
            blockBodies.add(vorbis);
        }
        outCount = blockTypes.size();
        for (int i = 0; i < outCount; i++) {
            int type = blockTypes.get(i);
            byte[] body = blockBodies.get(i);
            int lastFlag = i == outCount - 1 ? 0x80 : 0x00;
            head.write((lastFlag | (type & 0x7F)) & 0xFF);
            head.write((body.length >>> 16) & 0xFF);
            head.write((body.length >>> 8) & 0xFF);
            head.write(body.length & 0xFF);
            head.write(body, 0, body.length);
        }

        byte[] header = head.toByteArray();
        rewrite(f, len, header, audioStart);
    }

    /**
     * Write {@code tags} into the {@code 'id3 '} chunk of an integer PCM WAV file, replacing an
     * existing chunk in place (or appending one). All other chunks, in particular {@code data},
     * are copied byte for byte.
     */
    public static void writeWav(File f, Map<String, String> tags) throws IOException {
        if (f == null || !f.isFile()) throw new IOException("no such file: " + f);
        if (tags == null) throw new IOException("tags == null");
        long len = f.length();
        WavChunks chunks;
        byte[] existing = null;
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            chunks = scanWav(raf, len, f, true);
            for (int i = 0; i < chunks.chunks.size(); i++) {
                WavChunk c = chunks.chunks.get(i);
                if (c.isId3()) {
                    existing = readRange(raf, c.dataOffset, Math.min(c.size, MAX_ID3));
                    break;
                }
            }
        }
        byte[] id3 = buildId3(tags, existing);

        long newSize = 4; // "WAVE"
        for (int i = 0; i < chunks.chunks.size(); i++) {
            WavChunk c = chunks.chunks.get(i);
            if (c.isId3()) continue;
            newSize += 8 + c.size + (c.size & 1);
        }
        newSize += 8 + id3.length + (id3.length & 1);
        newSize += chunks.tailBytes;
        if (newSize + 8 > 0xFFFFFFFFL) throw new IOException("WAV too large for a 32 bit RIFF size");

        // Stream everything: a WAV can be far larger than the heap, so the payload is copied
        // chunk by chunk into the temporary file and never buffered as a whole.
        File dir = f.getAbsoluteFile().getParentFile();
        File tmp = tempFile(f, dir);
        boolean installed = false;
        try {
            FileOutputStream fos = new FileOutputStream(tmp);
            try {
                fos.write(new byte[]{'R', 'I', 'F', 'F'}, 0, 4);
                writeU32(fos, newSize);
                fos.write(new byte[]{'W', 'A', 'V', 'E'}, 0, 4);
                try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
                    boolean wrote = false;
                    for (int i = 0; i < chunks.chunks.size(); i++) {
                        WavChunk c = chunks.chunks.get(i);
                        if (c.isId3()) {
                            if (wrote) continue;
                            writeChunk(fos, "id3 ", id3);
                            wrote = true;
                        } else {
                            copyChunk(fos, raf, c);
                        }
                    }
                    if (!wrote) writeChunk(fos, "id3 ", id3);
                    if (chunks.tailBytes > 0) {
                        raf.seek(len - chunks.tailBytes);
                        copyRange(fos, raf, chunks.tailBytes);
                    }
                }
                fos.flush();
                fos.getFD().sync();
            } finally {
                fos.close();
            }
            replace(tmp, f);
            installed = true;
        } finally {
            if (!installed && tmp.exists()) {
                // Never delete the only complete copy: the user can recover it by hand.
                System.err.println("RgTags: could not install " + f + ", complete copy left at " + tmp);
            }
        }
    }

    // ---------------------------------------------------------------- FLAC

    private static final class FlacBlock {
        int type;
        long offset;
        long length;
    }

    private static boolean isFlac(File f) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            if (raf.length() < 4) return false;
            byte[] m = new byte[4];
            raf.readFully(m);
            return m[0] == 'f' && m[1] == 'L' && m[2] == 'a' && m[3] == 'C';
        }
    }

    private static List<FlacBlock> flacBlocks(RandomAccessFile raf, long len) throws IOException {
        if (len < 8) throw new IOException("too short for FLAC");
        raf.seek(0);
        byte[] magic = new byte[4];
        raf.readFully(magic);
        if (!(magic[0] == 'f' && magic[1] == 'L' && magic[2] == 'a' && magic[3] == 'C')) {
            throw new IOException("not a FLAC file");
        }
        List<FlacBlock> out = new ArrayList<FlacBlock>();
        long pos = 4;
        while (pos + 4 <= len) {
            raf.seek(pos);
            byte[] h = new byte[4];
            raf.readFully(h);
            int last = (h[0] & 0x80) != 0 ? 1 : 0;
            int type = h[0] & 0x7F;
            long size = ((long) (h[1] & 0xFF) << 16) | ((long) (h[2] & 0xFF) << 8) | (h[3] & 0xFF);
            long body = pos + 4;
            if (body + size > len) throw new IOException("truncated FLAC metadata block at " + pos);
            FlacBlock b = new FlacBlock();
            b.type = type;
            b.offset = body;
            b.length = size;
            out.add(b);
            pos = body + size;
            if (last != 0) return out;
        }
        throw new IOException("FLAC metadata without a last block");
    }

    private static byte[] vorbisVendor(byte[] body) throws IOException {
        if (body.length < 8) throw new IOException("bad vorbis comment block");
        long n = u32le(body, 0);
        if (n < 0 || 4 + n > body.length) throw new IOException("bad vorbis vendor length");
        byte[] v = new byte[(int) n];
        System.arraycopy(body, 4, v, 0, (int) n);
        return v;
    }

    private static List<String[]> vorbisComments(byte[] body) throws IOException {
        List<String[]> out = new ArrayList<String[]>();
        if (body.length < 8) return out;
        long vlen = u32le(body, 0);
        int p = (int) (4 + vlen);
        if (p < 0 || p + 4 > body.length) return out;
        long count = u32le(body, p);
        p += 4;
        for (long i = 0; i < count && p + 4 <= body.length; i++) {
            long n = u32le(body, p);
            p += 4;
            if (n < 0 || p + n > body.length) break;
            String kv = new String(body, p, (int) n, UTF8);
            p += n;
            int eq = kv.indexOf('=');
            if (eq > 0) out.add(new String[]{kv.substring(0, eq), kv.substring(eq + 1)});
        }
        return out;
    }

    private static Map<String, String> parseVorbisMap(byte[] body) throws IOException {
        Map<String, String> out = new LinkedHashMap<String, String>();
        for (String[] kv : vorbisComments(body)) out.put(kv[0].toUpperCase(Locale.US), kv[1]);
        return out;
    }

    private static byte[] buildVorbis(byte[] vendor, List<String[]> comments) {
        int size = 4 + vendor.length + 4;
        for (int i = 0; i < comments.size(); i++) {
            String[] kv = comments.get(i);
            size += 4 + kv[0].length() + 1 + kv[1].length();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(size + 64);
        putU32(out, vendor.length);
        out.write(vendor, 0, vendor.length);
        putU32(out, comments.size());
        for (int i = 0; i < comments.size(); i++) {
            String[] kv = comments.get(i);
            byte[] line = (kv[0] + "=" + kv[1]).getBytes(UTF8);
            putU32(out, line.length);
            out.write(line, 0, line.length);
        }
        return out.toByteArray();
    }

    // ---------------------------------------------------------------- WAV

    private static final class WavChunk {
        String id;
        long dataOffset;
        long size;

        boolean isId3() {
            return id.equals("id3 ") || id.equals("ID3 ") || id.equals("id3\0") || id.equals("ID3\0");
        }
    }

    private static final class WavChunks {
        final List<WavChunk> chunks = new ArrayList<WavChunk>();
        long tailBytes;
        int formatTag;
        int bits;
        boolean haveData;
    }

    private static WavChunks scanWav(RandomAccessFile raf, long len, File f, boolean forWrite)
            throws IOException {
        if (len < 12) throw new IOException("too short for RIFF: " + f);
        raf.seek(0);
        byte[] h = new byte[12];
        raf.readFully(h);
        if (!(h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F'
                && h[8] == 'W' && h[9] == 'A' && h[10] == 'V' && h[11] == 'E')) {
            throw new IOException("not a RIFF/WAVE file: " + f.getName());
        }
        WavChunks out = new WavChunks();
        long pos = 12;
        while (pos + 8 <= len) {
            raf.seek(pos);
            byte[] ch = new byte[8];
            if (raf.read(ch) != 8) break;
            WavChunk c = new WavChunk();
            c.id = new String(ch, 0, 4, ASCII);
            long declared = u32le(ch, 4);
            long body = pos + 8;
            long avail = len - body;
            if (declared > avail) {
                if (forWrite) {
                    throw new IOException("truncated WAV chunk '" + c.id.trim() + "' (declared "
                            + declared + " bytes, only " + avail + " present); refusing to rewrite");
                }
                declared = avail;
            }
            c.dataOffset = body;
            c.size = declared;
            out.chunks.add(c);
            if ("fmt ".equals(c.id)) {
                byte[] fmt = readRange(raf, body, Math.min(declared, 40));
                if (fmt.length >= 16) {
                    out.formatTag = (fmt[0] & 0xFF) | ((fmt[1] & 0xFF) << 8);
                    out.bits = (fmt[14] & 0xFF) | ((fmt[15] & 0xFF) << 8);
                    if (out.formatTag == 0xFFFE && fmt.length >= 26) {
                        out.formatTag = (fmt[24] & 0xFF) | ((fmt[25] & 0xFF) << 8);
                    }
                }
            } else if ("data".equals(c.id)) {
                out.haveData = true;
            }
            long next = body + declared;
            if ((declared & 1L) != 0L) next++;
            if (next <= pos) break;
            pos = next;
        }
        if (pos < len) out.tailBytes = len - pos;
        if (forWrite) {
            if (!out.haveData) throw new IOException("no data chunk: " + f.getName());
            if (out.formatTag != 1) {
                throw new IOException("unsupported WAV sample format " + out.formatTag
                        + (out.formatTag == 3 ? " (IEEE float)" : "")
                        + "; only integer PCM WAV can carry ReplayGain tags");
            }
            if (out.bits != 16 && out.bits != 24 && out.bits != 32) {
                throw new IOException("unsupported WAV bit depth: " + out.bits);
            }
        }
        return out;
    }

    private static void writeChunk(OutputStream out, String id, byte[] data) throws IOException {
        out.write(id.getBytes(ASCII), 0, 4);
        writeU32(out, data.length);
        out.write(data, 0, data.length);
        if ((data.length & 1) != 0) out.write(0);
    }

    private static void copyChunk(OutputStream out, RandomAccessFile raf, WavChunk c)
            throws IOException {
        out.write(c.id.getBytes(ASCII), 0, 4);
        writeU32(out, c.size);
        raf.seek(c.dataOffset);
        copyRange(out, raf, c.size);
        if ((c.size & 1) != 0) out.write(0);
    }

    private static void copyRange(OutputStream out, RandomAccessFile raf, long bytes)
            throws IOException {
        long left = bytes;
        byte[] buf = new byte[COPY_BUF];
        while (left > 0) {
            int want = (int) Math.min(buf.length, left);
            int got = raf.read(buf, 0, want);
            if (got <= 0) throw new IOException("unexpected EOF while copying " + left + " bytes");
            out.write(buf, 0, got);
            left -= got;
        }
    }

    // ---------------------------------------------------------------- ID3v2

    /** One ID3 frame normalised to a v2.3 layout: id, flags, payload. */
    private static final class Id3Frame {
        String id;
        int flags;
        byte[] payload;
    }

    /**
     * Build an ID3v2.3 tag. Frames of the existing tag (title, artist, cover art...) are kept
     * verbatim apart from a normalised header, and existing ReplayGain TXXX frames are merged
     * into {@code tags} (the caller's values win) instead of being duplicated. Only v2.3/v2.4
     * tags can be carried over; an obsolete v2.2 tag is replaced.
     */
    private static byte[] buildId3(Map<String, String> tags, byte[] existing) throws IOException {
        List<Id3Frame> keep = new ArrayList<Id3Frame>();
        Map<String, String> merged = new LinkedHashMap<String, String>();
        if (existing != null) {
            List<Id3Frame> frames = id3Frames(existing);
            for (int i = 0; i < frames.size(); i++) {
                Id3Frame fr = frames.get(i);
                if ("TXXX".equals(fr.id)) {
                    String[] kv = txxxFields(fr.payload);
                    if (kv != null && kv[0].toUpperCase(Locale.US).startsWith("REPLAYGAIN_")) {
                        merged.put(kv[0].toUpperCase(Locale.US), kv[1]);
                        continue;
                    }
                }
                if ((fr.flags & 0x00C0) != 0) continue; // compressed/encrypted frame: cannot travel
                keep.add(fr);
            }
        }
        for (Map.Entry<String, String> e : tags.entrySet()) {
            if (e.getKey() == null) continue;
            String up = e.getKey().toUpperCase(Locale.US);
            if (e.getValue() == null) merged.remove(up);
            else merged.put(up, e.getValue());
        }

        ByteArrayOutputStream frames = new ByteArrayOutputStream(1024);
        for (int i = 0; i < keep.size(); i++) {
            Id3Frame fr = keep.get(i);
            writeFrame(frames, fr.id, fr.flags, fr.payload);
        }
        List<String> keys = new ArrayList<String>(merged.keySet());
        Collections.sort(keys);
        for (int i = 0; i < keys.size(); i++) {
            String k = keys.get(i);
            writeFrame(frames, "TXXX", 0, txxxPayload(k, merged.get(k)));
        }
        byte[] body = frames.toByteArray();
        if (body.length + 10 > MAX_ID3) throw new IOException("ID3 tag would exceed 16 MiB");
        ByteArrayOutputStream out = new ByteArrayOutputStream(body.length + 10);
        out.write('I');
        out.write('D');
        out.write('3');
        out.write(3);       // v2.3
        out.write(0);       // revision
        out.write(0);       // flags (no unsynchronisation: ours is never needed)
        writeSynchsafe(out, body.length);
        out.write(body, 0, body.length);
        return out.toByteArray();
    }

    private static void writeFrame(ByteArrayOutputStream out, String id, int flags, byte[] payload) {
        byte[] idb = id.getBytes(ASCII);
        for (int i = 0; i < 4; i++) out.write(i < idb.length ? idb[i] : ' ');
        out.write((payload.length >>> 24) & 0xFF);
        out.write((payload.length >>> 16) & 0xFF);
        out.write((payload.length >>> 8) & 0xFF);
        out.write(payload.length & 0xFF);
        out.write((flags >>> 8) & 0xFF);
        out.write(flags & 0xFF);
        out.write(payload, 0, payload.length);
    }

    private static byte[] txxxPayload(String desc, String value) {
        boolean wide = !latin1(desc) || !latin1(value);
        ByteArrayOutputStream p = new ByteArrayOutputStream(64);
        if (!wide) {
            p.write(0); // ISO-8859-1
            byte[] d = desc.getBytes(ASCII);
            p.write(d, 0, d.length);
            p.write(0);
            byte[] v = value.getBytes(ASCII);
            p.write(v, 0, v.length);
        } else {
            p.write(1); // UTF-16 with BOM
            writeBomAnd(p, desc);
            p.write(0);
            p.write(0);
            writeBomAnd(p, value);
        }
        return p.toByteArray();
    }

    private static void writeBomAnd(ByteArrayOutputStream p, String s) {
        byte[] b = s.getBytes(UTF16LE);
        p.write(0xFF);
        p.write(0xFE);
        p.write(b, 0, b.length);
    }

    private static boolean latin1(String s) {
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) > 0xFF) return false;
        return true;
    }

    /** Parse an ID3v2.3/2.4 tag into frames; an unreadable tag yields an empty list. */
    private static List<Id3Frame> id3Frames(byte[] b) {
        List<Id3Frame> out = new ArrayList<Id3Frame>();
        if (b == null || b.length < 10) return out;
        if (!(b[0] == 'I' && b[1] == 'D' && b[2] == '3')) return out;
        int ver = b[3] & 0xFF;
        if (ver != 3 && ver != 4) return out; // v2.2 frames cannot be re-emitted as v2.3
        int flags = b[5] & 0xFF;
        int size = synchsafe(b, 6);
        int end = Math.min(b.length, 10 + size);
        int start = 10;
        if ((flags & 0x40) != 0) {
            if (start + 4 > end) return out;
            int ext = ver == 3
                    ? ((b[start] & 0xFF) << 24) | ((b[start + 1] & 0xFF) << 16)
                    | ((b[start + 2] & 0xFF) << 8) | (b[start + 3] & 0xFF)
                    : synchsafe(b, start);
            start += 4 + Math.max(0, ext);
            if (start > end) return out;
        }
        byte[] src = b;
        int from = start;
        int to = end;
        if ((flags & 0x80) != 0) {
            byte[] un = new byte[to - from];
            int n = 0;
            for (int i = from; i < to; i++) {
                un[n++] = b[i];
                if ((b[i] & 0xFF) == 0xFF && i + 1 < to && b[i + 1] == 0) i++;
            }
            src = un;
            from = 0;
            to = n;
        }
        int p = from;
        while (p + 10 <= to) {
            String id = new String(src, p, 4, ASCII);
            if (id.charAt(0) == 0) break;
            int fsize = ver == 4
                    ? synchsafe(src, p + 4)
                    : ((src[p + 4] & 0xFF) << 24) | ((src[p + 5] & 0xFF) << 16)
                    | ((src[p + 6] & 0xFF) << 8) | (src[p + 7] & 0xFF);
            int d = p + 10;
            if (fsize <= 0 || d + fsize > to) break;
            Id3Frame f = new Id3Frame();
            f.id = id;
            f.flags = ((src[p + 8] & 0xFF) << 8) | (src[p + 9] & 0xFF);
            f.payload = new byte[fsize];
            System.arraycopy(src, d, f.payload, 0, fsize);
            out.add(f);
            p = d + fsize;
        }
        return out;
    }

    /** TXXX payload -> {description, value}, or null when the frame is too short. */
    private static String[] txxxFields(byte[] b) {
        if (b == null || b.length < 2) return null;
        int enc = b[0] & 0xFF;
        int p = 1;
        int end = b.length;
        if (enc == 1 || enc == 2) {
            if (enc == 1 && (p & 1) == 0 && p + 1 < end
                    && ((b[p] & 0xFF) == 0xFF || (b[p] & 0xFF) == 0xFE)) {
                p += 2; // BOM
            }
            int q = p;
            while (q + 1 < end && !(b[q] == 0 && b[q + 1] == 0)) q += 2;
            Charset cs = enc == 1 ? UTF16LE : Charset.forName("UTF-16BE");
            String desc = new String(b, p, q - p, cs);
            q = Math.min(q + 2, end);
            String value = new String(b, q, Math.max(0, end - q), cs);
            int z = value.indexOf('\u0000');
            if (z >= 0) value = value.substring(0, z);
            return new String[]{desc, value};
        }
        Charset cs = enc == 3 ? UTF8 : ASCII;
        int q = p;
        while (q < end && b[q] != 0) q++;
        String desc = new String(b, p, q - p, cs);
        int v = Math.min(q + 1, end);
        String value = new String(b, v, Math.max(0, end - v), cs);
        int z = value.indexOf('\u0000');
        if (z >= 0) value = value.substring(0, z);
        return new String[]{desc, value};
    }

    /** ReplayGain TXXX frames of an ID3v2.3/2.4 tag. */
    private static Map<String, String> id3ReplayGain(byte[] tag) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        List<Id3Frame> frames = id3Frames(tag);
        for (int i = 0; i < frames.size(); i++) {
            Id3Frame f = frames.get(i);
            if (!"TXXX".equals(f.id)) continue;
            String[] kv = txxxFields(f.payload);
            if (kv == null) continue;
            String key = kv[0].toUpperCase(Locale.US);
            if (key.startsWith("REPLAYGAIN_")) out.put(key, kv[1]);
        }
        return out;
    }

    // ---------------------------------------------------------------- MP4 / M4A

    /** Free form ("----") mean atom used by foobar2000, MP3TAG and iTunes for ReplayGain. */
    public static final String MP4_MEAN = "com.apple.iTunes";
    /** Group key for files with neither an album artist nor an album tag. */
    public static final String UNKNOWN_ALBUM = "\u0000unknown-album";
    private static final int MAX_MP4_TEXT = 64 * 1024;

    /**
     * True when the file starts with an ISO base media {@code ftyp} box, i.e. is an MP4/M4A/M4B.
     * Same test the rest of the app uses, so a file accepted here is accepted everywhere.
     */
    public static boolean isMp4(File f) throws IOException {
        if (f == null || !f.isFile()) return false;
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            if (raf.length() < 12) return false;
            byte[] head = new byte[12];
            raf.readFully(head);
            return head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p';
        }
    }

    /**
     * Read the REPLAYGAIN_* free form atoms of an MP4/M4A file.
     *
     * <p>Only {@code moov/udta/meta/ilst} is walked, one {@code ----} item at a time, and the audio
     * payload is never touched: mdat is skipped with seeks, so a whole library can be read quickly.
     */
    public static Map<String, String> readMp4(File f) throws IOException {
        if (f == null || !f.isFile()) throw new IOException("no such file: " + f);
        Map<String, String> out = new LinkedHashMap<String, String>();
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            long len = raf.length();
            Box moov = first(boxes(raf, 0, len, 4096), "moov");
            if (moov == null) return out;
            Box udta = first(boxes(raf, moov.body(), moov.end(), 4096), "udta");
            if (udta == null) return out;
            Box meta = first(boxes(raf, udta.body(), udta.end(), 4096), "meta");
            if (meta == null || meta.size - meta.header < 4) return out;
            Box ilst = first(boxes(raf, meta.body() + 4, meta.end(), 4096), "ilst");
            if (ilst == null) return out;
            for (Box item : boxes(raf, ilst.body(), ilst.end(), 8192)) {
                if (!"----".equals(item.type)) continue;
                String[] id = freeformIdentity(raf, item);
                if (id == null || !MP4_MEAN.equalsIgnoreCase(id[0])) continue;
                String key = id[1].toUpperCase(Locale.US);
                if (!key.startsWith("REPLAYGAIN_") || out.containsKey(key)) continue;
                String value = freeformValue(raf, item);
                if (value != null) out.put(key, value);
            }
        }
        return out;
    }

    /**
     * Merge {@code tags} into the free form atoms of an MP4/M4A file, creating
     * {@code moov/udta/meta/ilst} when the file has no metadata at all.
     *
     * <p>The layout follows the foobar2000 / MP3TAG convention: one {@code ----} item per key with
     * {@code mean = com.apple.iTunes}, {@code name = <KEY>} and one {@code data} child typed UTF-8
     * (type 1, locale 0). A null value removes that key; ReplayGain keys the caller did not mention
     * are left alone, and unrelated items (cover art, copyright, MusicBrainz ids,...) are copied
     * verbatim.
     *
     * <p>Only the {@code moov} atom is rebuilt, and every other byte of the file - in particular
     * mdat and the sample tables - is copied unchanged. When moov sits in front of the media data
     * (faststart layout) its size is kept exactly by padding, so no chunk offset has to move; when
     * the new metadata does not fit, moov is relocated to the end of the file and its old slot is
     * filled with a {@code free} atom of the same size, which also leaves every offset intact. The
     * write goes to a temporary file that is renamed over the original only once it is complete.
     */
    public static void writeMp4(File f, Map<String, String> tags) throws IOException {
        if (f == null || !f.isFile()) throw new IOException("no such file: " + f);
        if (tags == null) throw new IOException("tags == null");
        long len = f.length();
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            List<Box> top = boxes(raf, 0, len, 8192);
            Box moov = null;
            boolean shiftSensitive = false;
            for (int i = 0; i < top.size(); i++) {
                Box b = top.get(i);
                if ("moov".equals(b.type)) {
                    if (moov != null) throw new IOException("more than one moov atom: " + f.getName());
                    moov = b;
                }
            }
            if (moov == null) throw new IOException("no moov atom: " + f.getName());
            for (int i = 0; i < top.size(); i++) {
                Box b = top.get(i);
                if ("mdat".equals(b.type) && b.start > moov.start) shiftSensitive = true;
            }

            List<Range> parts = buildMoov(raf, moov, tags, 0);
            long newSize = size(parts);
            if (shiftSensitive) {
                if (newSize > moov.size) {
                    // Grow by moving moov behind the media data: every absolute chunk offset stays
                    // valid because the old moov slot becomes an equally sized free atom.
                    List<Range> out = new ArrayList<Range>();
                    out.add(new Range(0, moov.start));
                    addFree(out, moov.size);                // same size as the old moov
                    out.add(new Range(moov.end(), len - moov.end()));
                    out.addAll(parts);
                    installParts(f, out);
                    return;
                }
                long pad = moov.size - newSize;
                if (pad > 0) {
                    parts = buildMoov(raf, moov, tags, pad);
                    if (size(parts) != moov.size) {
                        throw new IOException("internal error: moov padding mismatch for " + f.getName());
                    }
                }
            }
            List<Range> out = new ArrayList<Range>();
            out.add(new Range(0, moov.start));
            out.addAll(parts);
            out.add(new Range(moov.end(), len - moov.end()));
            installParts(f, out);
        }
    }

    /**
     * Album grouping key derived from tags. Empty album artist falls back to the album alone; when
     * both are empty the {@link #UNKNOWN_ALBUM} key is returned and every such file lands in one
     * "unknown album" group.
     */
    public static String albumKey(String albumArtist, String album) {
        String aa = albumArtist == null ? "" : albumArtist.trim();
        String al = album == null ? "" : album.trim();
        if (aa.length() == 0 && al.length() == 0) return UNKNOWN_ALBUM;
        return aa + '\u0000' + al;
    }

    // ---------------------------------------------------------------- MP4 atoms

    /** One ISO base media box: its type, start offset, header length and total size. */
    private static final class Box {
        String type;
        long start;
        long header;
        long size;

        long body() {
            return start + header;
        }

        long end() {
            return start + size;
        }
    }

    /**
     * A piece of the output file: a byte range copied from the source, literal bytes, or a run of
     * zeros. Rebuilding moov as ranges keeps a large cover art out of the heap.
     */
    private static final class Range {
        final long from;
        final long length;
        final byte[] data;
        final boolean zeros;

        Range(long from, long length) {
            this.from = from;
            this.length = length;
            this.data = null;
            this.zeros = false;
        }

        Range(byte[] data) {
            this.from = 0;
            this.length = data.length;
            this.data = data;
            this.zeros = false;
        }

        /** A run of {@code count} zero bytes. */
        Range(long count) {
            this.from = 0;
            this.length = count;
            this.data = null;
            this.zeros = true;
        }

        long size() {
            return length;
        }
    }

    private static List<Box> boxes(RandomAccessFile raf, long from, long to, int max) throws IOException {
        List<Box> out = new ArrayList<Box>();
        long p = from;
        while (p + 8 <= to && out.size() < max) {
            raf.seek(p);
            long size = raf.readInt() & 0xFFFFFFFFL;
            byte[] nb = new byte[4];
            raf.readFully(nb);
            String type = new String(nb, ASCII);
            long header = 8;
            if (size == 1) {
                if (p + 16 > to) break;
                size = raf.readLong();
                header = 16;
            } else if (size == 0) {
                size = to - p;
            }
            if (size < header || p + size > to) break;     // truncated box: stop, do not guess
            Box b = new Box();
            b.type = type;
            b.start = p;
            b.header = header;
            b.size = size;
            out.add(b);
            p += size;
        }
        return out;
    }

    /**
     * A rebuild must never silently drop bytes: if the box list does not tile its parent exactly,
     * the file uses a layout this writer does not understand and the write is refused instead.
     */
    private static void requireFull(List<Box> list, long from, long to, String what)
            throws IOException {
        long p = from;
        for (int i = 0; i < list.size(); i++) p = list.get(i).end();
        if (p != to) {
            throw new IOException("unsupported " + what + " layout (covered " + p + " of " + to + ")");
        }
    }

    private static Box first(List<Box> list, String type) {
        for (int i = 0; i < list.size(); i++) {
            if (type.equals(list.get(i).type)) return list.get(i);
        }
        return null;
    }

    private static long size(List<Range> parts) {
        long total = 0;
        for (int i = 0; i < parts.size(); i++) total += parts.get(i).size();
        if (total < 0) throw new IllegalStateException("negative box size");
        return total;
    }

    /** Fill the 8 byte placeholder at {@code index} with a 32 bit box header of the current size. */
    private static void patchHeader(List<Range> parts, int index, String type) throws IOException {
        long total = size(parts);
        if (total > 0xFFFFFFFFL) throw new IOException(type + " box would exceed 4 GiB");
        byte[] h = new byte[8];
        h[0] = (byte) ((total >>> 24) & 0xFF);
        h[1] = (byte) ((total >>> 16) & 0xFF);
        h[2] = (byte) ((total >>> 8) & 0xFF);
        h[3] = (byte) (total & 0xFF);
        byte[] t = type.getBytes(ASCII);
        System.arraycopy(t, 0, h, 4, 4);
        parts.set(index, new Range(h));
    }

    /** "size + type" header for a fully built box. */
    private static byte[] header(String type, long total) throws IOException {
        if (total > 0xFFFFFFFFL) throw new IOException(type + " box would exceed 4 GiB");
        byte[] h = new byte[8];
        h[0] = (byte) ((total >>> 24) & 0xFF);
        h[1] = (byte) ((total >>> 16) & 0xFF);
        h[2] = (byte) ((total >>> 8) & 0xFF);
        h[3] = (byte) (total & 0xFF);
        System.arraycopy(type.getBytes(ASCII), 0, h, 4, 4);
        return h;
    }

    private static List<Range> buildMoov(RandomAccessFile raf, Box moov, Map<String, String> tags,
                                         long pad) throws IOException {
        boolean hdlrPad = pad > 0 && pad < 8;
        List<Range> out = new ArrayList<Range>();
        out.add(new Range(header("moov", 0)));
        List<Box> kids = boxes(raf, moov.body(), moov.end(), 8192);
        requireFull(kids, moov.body(), moov.end(), "moov");
        boolean wroteUdta = false;
        for (int i = 0; i < kids.size(); i++) {
            Box k = kids.get(i);
            if ("udta".equals(k.type)) {
                if (wroteUdta) {
                    out.add(new Range(k.start, k.size));   // a second udta is copied verbatim
                } else {
                    out.addAll(buildUdta(raf, k, tags, hdlrPad ? (int) pad : 0));
                    wroteUdta = true;
                }
            } else {
                out.add(new Range(k.start, k.size));
            }
        }
        if (!wroteUdta) out.addAll(buildUdta(raf, null, tags, hdlrPad ? (int) pad : 0));
        if (pad >= 8) addFree(out, pad);                 // trailing free child of exact size
        out.set(0, new Range(header("moov", size(out))));
        return out;
    }

    private static List<Range> buildUdta(RandomAccessFile raf, Box udta, Map<String, String> tags,
                                         int hdlrPad) throws IOException {
        List<Range> out = new ArrayList<Range>();
        out.add(new Range(header("udta", 0)));
        boolean wroteMeta = false;
        if (udta != null) {
            List<Box> kids = boxes(raf, udta.body(), udta.end(), 4096);
            requireFull(kids, udta.body(), udta.end(), "udta");
            for (int i = 0; i < kids.size(); i++) {
                Box k = kids.get(i);
                if ("meta".equals(k.type)) {
                    out.addAll(buildMeta(raf, k, tags, hdlrPad));
                    wroteMeta = true;
                } else {
                    out.add(new Range(k.start, k.size));
                }
            }
        }
        if (!wroteMeta) out.addAll(buildMeta(raf, null, tags, hdlrPad));
        out.set(0, new Range(header("udta", size(out))));
        return out;
    }

    private static List<Range> buildMeta(RandomAccessFile raf, Box meta, Map<String, String> tags,
                                         int hdlrPad) throws IOException {
        List<Range> out = new ArrayList<Range>();
        out.add(new Range(header("meta", 0)));
        byte[] versionFlags = new byte[4];
        List<Range> kids = new ArrayList<Range>();
        Box hdlrBox = null;
        if (meta != null) {
            if (meta.size - meta.header < 4) throw new IOException("meta box too small");
            raf.seek(meta.body());
            raf.readFully(versionFlags);
            List<Box> children = boxes(raf, meta.body() + 4, meta.end(), 4096);
            requireFull(children, meta.body() + 4, meta.end(), "meta");
            for (int i = 0; i < children.size(); i++) {
                Box k = children.get(i);
                if ("ilst".equals(k.type)) continue;             // rebuilt from scratch
                if ("hdlr".equals(k.type)) {
                    hdlrBox = k;
                    continue;                                   // re-emitted (possibly padded)
                }
                kids.add(new Range(k.start, k.size));
            }
        }
        out.add(new Range(versionFlags));
        out.addAll(kids);
        if (hdlrBox != null && hdlrPad > 0) {
            long len = hdlrBox.size - hdlrBox.header;
            if (len > MAX_MP4_TEXT) throw new IOException("hdlr box too large");
            out.add(new Range(hdlr(readRange(raf, hdlrBox.body(), len), hdlrPad)));
        } else if (hdlrBox != null) {
            out.add(new Range(hdlrBox.start, hdlrBox.size));
        } else {
            out.add(new Range(hdlr(null, hdlrPad)));
        }
        out.addAll(buildIlst(raf, meta, tags));
        out.set(0, new Range(header("meta", size(out))));
        return out;
    }

    /**
     * Re-emit a handler box. Its name field is the only metadata string we can lengthen by an
     * arbitrary number of bytes, which is what makes an exact (non multiple of 8) padding possible
     * when moov has to keep its size in front of the media data.
     */
    private static byte[] hdlr(byte[] payload, int pad) throws IOException {
        byte[] body;
        if (payload != null && payload.length >= 24) {
            body = new byte[payload.length + pad];
            System.arraycopy(payload, 0, body, 0, payload.length);
        } else {
            body = new byte[24 + 1 + pad];
            body[8] = 'm';                                       // handler_type = 'mdir'
            body[9] = 'd';
            body[10] = 'i';
            body[11] = 'r';
            body[24] = 0;                                        // empty, NUL terminated name
        }
        long total = 8L + body.length;
        byte[] out = new byte[(int) total];
        System.arraycopy(header("hdlr", total), 0, out, 0, 8);
        System.arraycopy(body, 0, out, 8, body.length);
        return out;
    }

    private static List<Range> buildIlst(RandomAccessFile raf, Box meta, Map<String, String> tags)
            throws IOException {
        List<Range> out = new ArrayList<Range>();
        out.add(new Range(header("ilst", 0)));
        Box ilst = null;
        if (meta != null) {
            List<Box> kids = boxes(raf, meta.body() + 4, meta.end(), 4096);
            for (int i = 0; i < kids.size(); i++) {
                if ("ilst".equals(kids.get(i).type)) {
                    ilst = kids.get(i);
                    break;
                }
            }
        }
        if (ilst != null) {
            List<Box> items = boxes(raf, ilst.body(), ilst.end(), 8192);
            requireFull(items, ilst.body(), ilst.end(), "ilst");
            for (int i = 0; i < items.size(); i++) {
                Box item = items.get(i);
                if ("----".equals(item.type)) {
                    String[] id = freeformIdentity(raf, item);
                    if (id != null && MP4_MEAN.equalsIgnoreCase(id[0])) {
                        String key = id[1].toUpperCase(Locale.US);
                        if (tags.containsKey(key) || tags.containsKey(id[1])) continue; // replaced
                        if (key.startsWith("REPLAYGAIN_")) {
                            out.add(new Range(item.start, item.size));                  // untouched
                            continue;
                        }
                    }
                }
                out.add(new Range(item.start, item.size));
            }
        }
        for (Map.Entry<String, String> e : tags.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            out.add(new Range(freeform(e.getKey().toUpperCase(Locale.US), e.getValue())));
        }
        out.set(0, new Range(header("ilst", size(out))));
        return out;
    }

    /** mean/name pair of a free form item, or null when it is not a well formed '----'. */
    private static String[] freeformIdentity(RandomAccessFile raf, Box item) throws IOException {
        String mean = null;
        String name = null;
        List<Box> kids = boxes(raf, item.body(), item.end(), 32);
        for (int i = 0; i < kids.size(); i++) {
            Box k = kids.get(i);
            if ("mean".equals(k.type)) mean = text(raf, k);
            else if ("name".equals(k.type)) name = text(raf, k);
        }
        if (mean == null || name == null) return null;
        return new String[]{mean, name};
    }

    private static String freeformValue(RandomAccessFile raf, Box item) throws IOException {
        List<Box> kids = boxes(raf, item.body(), item.end(), 32);
        for (int i = 0; i < kids.size(); i++) {
            if (!"data".equals(kids.get(i).type)) continue;
            Box k = kids.get(i);
            if (k.size - k.header < 8) return null;
            raf.seek(k.body());
            long type = raf.readInt() & 0xFFFFFFFFL;
            raf.readInt();                                       // locale, always 0 for text
            long kind = type & 0xFFFFFFL;
            if (kind != 1L && kind != 0L) return null;           // only UTF-8 / implicit text
            long len = Math.min(k.end() - (k.body() + 8), MAX_MP4_TEXT);
            if (len <= 0) return "";
            byte[] raw = readRange(raf, k.body() + 8, len);
            int n = raw.length;
            while (n > 0 && raw[n - 1] == 0) n--;
            return new String(raw, 0, n, UTF8).trim();
        }
        return null;
    }

    /** mean/name text: 4 bytes of version/flags, then UTF-8. */
    private static String text(RandomAccessFile raf, Box k) throws IOException {
        long len = Math.min(k.end() - (k.body() + 4), MAX_MP4_TEXT);
        if (len <= 0) return "";
        byte[] raw = readRange(raf, k.body() + 4, len);
        int n = raw.length;
        while (n > 0 && raw[n - 1] == 0) n--;
        return new String(raw, 0, n, UTF8).trim();
    }

    /** A complete '----' item with mean/name/data children. */
    private static byte[] freeform(String name, String value) throws IOException {
        byte[] mean = MP4_MEAN.getBytes(UTF8);
        byte[] nm = name.getBytes(UTF8);
        byte[] val = value.getBytes(UTF8);
        int meanLen = 12 + mean.length;      // 4 size + 4 type + 4 version/flags + text
        int nameLen = 12 + nm.length;
        int dataLen = 16 + val.length;      // 4 size + 4 type + 4 type/locale + 4 locale... see below
        long total = 8L + meanLen + nameLen + dataLen;
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) total);
        out.write(header("----", total), 0, 8);
        putBox(out, "mean", mean);
        putBox(out, "name", nm);
        // data: 4 size + "data" + 4 byte type (1 = UTF-8) + 4 byte locale (0) + payload
        putU32be(out, dataLen);
        out.write('d');
        out.write('a');
        out.write('t');
        out.write('a');
        putU32be(out, 1);                   // type 1: UTF-8
        putU32be(out, 0);                   // locale
        out.write(val, 0, val.length);
        return out.toByteArray();
    }

    private static void putBox(ByteArrayOutputStream out, String type, byte[] text) {
        long total = 12L + text.length;
        putU32be(out, total);
        byte[] t = type.getBytes(ASCII);
        out.write(t, 0, 4);
        putU32be(out, 0);                   // version / flags
        out.write(text, 0, text.length);
    }

    private static void putU32be(ByteArrayOutputStream out, long v) {
        out.write((int) ((v >>> 24) & 0xFF));
        out.write((int) ((v >>> 16) & 0xFF));
        out.write((int) ((v >>> 8) & 0xFF));
        out.write((int) (v & 0xFF));
    }

    /** Append a {@code free} box of exactly {@code size} bytes (header + zeros). */
    private static void addFree(List<Range> out, long size) throws IOException {
        if (size < 8) throw new IOException("free box too small: " + size);
        out.add(new Range(header("free", size)));
        if (size > 8) out.add(new Range(size - 8));
    }

    /** Stream the parts into a temporary file, then install it over {@code target}. */
    private static void installParts(File target, List<Range> parts) throws IOException {
        File dir = target.getAbsoluteFile().getParentFile();
        File tmp = tempFile(target, dir);
        boolean installed = false;
        try {
            FileOutputStream fos = new FileOutputStream(tmp);
            try {
                try (RandomAccessFile src = new RandomAccessFile(target, "r")) {
                    byte[] zeros = null;
                    for (int i = 0; i < parts.size(); i++) {
                        Range r = parts.get(i);
                        if (r.data != null) {
                            fos.write(r.data, 0, r.data.length);
                        } else if (r.zeros) {
                            if (zeros == null) zeros = new byte[COPY_BUF];
                            long left = r.length;
                            while (left > 0) {
                                int n = (int) Math.min(zeros.length, left);
                                fos.write(zeros, 0, n);
                                left -= n;
                            }
                        } else if (r.length > 0) {
                            src.seek(r.from);
                            copyRange(fos, src, r.length);
                        }
                    }
                }
                fos.flush();
                fos.getFD().sync();
            } finally {
                fos.close();
            }
            replace(tmp, target);
            installed = true;
        } finally {
            if (!installed && tmp.exists()) {
                System.err.println("RgTags: could not install " + target
                        + ", complete copy left at " + tmp);
            }
        }
    }

    // ---------------------------------------------------------------- shared bits

    private static List<String[]> merge(List<String[]> base, Map<String, String> tags) {
        List<String[]> out = new ArrayList<String[]>();
        List<String> replaced = new ArrayList<String>();
        for (int i = 0; i < base.size(); i++) {
            String rawKey = base.get(i)[0];
            String up = rawKey.toUpperCase(Locale.US);
            if (tags.containsKey(up) || tags.containsKey(rawKey)) {
                if (replaced.contains(up)) continue;
                String v = tags.containsKey(up) ? tags.get(up) : tags.get(rawKey);
                if (v != null) out.add(new String[]{rawKey, v});
                replaced.add(up);
            } else {
                out.add(base.get(i));
            }
        }
        for (Map.Entry<String, String> e : tags.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            String up = e.getKey().toUpperCase(Locale.US);
            if (replaced.contains(up)) continue;
            out.add(new String[]{up, e.getValue()});
        }
        return out;
    }

    private static Map<String, String> replayGainOfMap(Map<String, String> all) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> e : all.entrySet()) {
            if (e.getKey().startsWith("REPLAYGAIN_")) out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    private static byte[] readRange(RandomAccessFile raf, long offset, long size) throws IOException {
        if (size < 0 || size > Integer.MAX_VALUE) throw new IOException("block too large: " + size);
        byte[] b = new byte[(int) size];
        raf.seek(offset);
        raf.readFully(b);
        return b;
    }

    /** Write {@code header} at the start of {@code target}, then copy {@code copyFrom}..EOF. */
    private static void rewrite(File target, long originalLength, byte[] header, long copyFrom)
            throws IOException {
        File dir = target.getAbsoluteFile().getParentFile();
        File tmp = tempFile(target, dir);
        boolean installed = false;
        try {
            FileOutputStream fos = new FileOutputStream(tmp);
            try {
                fos.write(header, 0, header.length);
                if (copyFrom < originalLength) {
                    FileInputStream in = new FileInputStream(target);
                    try {
                        skipFully(in, copyFrom);
                        byte[] buf = new byte[COPY_BUF];
                        int got;
                        while ((got = in.read(buf)) > 0) fos.write(buf, 0, got);
                    } finally {
                        in.close();
                    }
                }
                fos.flush();
                fos.getFD().sync();
            } finally {
                fos.close();
            }
            replace(tmp, target);
            installed = true;
        } finally {
            if (!installed && tmp.exists()) {
                System.err.println("RgTags: could not install " + target
                        + ", complete copy left at " + tmp);
            }
        }
    }

    /** Temporary file in the target directory so the final rename stays on one filesystem. */
    private static File tempFile(File target, File dir) throws IOException {
        String name = target.getName();
        String prefix = name.length() >= 3 ? name + "." : "rg-" + name + ".";
        return File.createTempFile(prefix, ".rg.tmp", dir);
    }

    /**
     * Install {@code tmp} as {@code target} without ever destroying the only complete copy.
     * renameTo replaces on POSIX, Android and Windows NTFS; when a filesystem refuses, the
     * finished temporary file is copied over the target instead of deleting the target first.
     */
    private static void replace(File tmp, File target) throws IOException {
        if (tmp.renameTo(target)) return;
        FileInputStream in = new FileInputStream(tmp);
        try {
            FileOutputStream out = new FileOutputStream(target);
            try {
                byte[] buf = new byte[COPY_BUF];
                int got;
                while ((got = in.read(buf)) > 0) out.write(buf, 0, got);
                out.flush();
                out.getFD().sync();
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
        if (!tmp.delete()) tmp.deleteOnExit();
    }

    private static void skipFully(FileInputStream in, long bytes) throws IOException {
        long left = bytes;
        while (left > 0) {
            long s = in.skip(left);
            if (s <= 0) {
                if (in.read() < 0) throw new IOException("unexpected EOF");
                s = 1;
            }
            left -= s;
        }
    }

    private static void writeU32(OutputStream out, long v) throws IOException {
        out.write((int) (v & 0xFF));
        out.write((int) ((v >>> 8) & 0xFF));
        out.write((int) ((v >>> 16) & 0xFF));
        out.write((int) ((v >>> 24) & 0xFF));
    }

    private static void putU32(ByteArrayOutputStream out, long v) {
        out.write((int) (v & 0xFF));
        out.write((int) ((v >>> 8) & 0xFF));
        out.write((int) ((v >>> 16) & 0xFF));
        out.write((int) ((v >>> 24) & 0xFF));
    }

    private static void writeSynchsafe(ByteArrayOutputStream out, int v) {
        out.write((v >>> 21) & 0x7F);
        out.write((v >>> 14) & 0x7F);
        out.write((v >>> 7) & 0x7F);
        out.write(v & 0x7F);
    }

    private static int synchsafe(byte[] b, int o) {
        if (o + 4 > b.length) return 0;
        return ((b[o] & 0x7F) << 21) | ((b[o + 1] & 0x7F) << 14)
                | ((b[o + 2] & 0x7F) << 7) | (b[o + 3] & 0x7F);
    }

    private static long u32le(byte[] b, int o) {
        if (o + 4 > b.length) return 0;
        return (long) (b[o] & 0xFF) | ((long) (b[o + 1] & 0xFF) << 8)
                | ((long) (b[o + 2] & 0xFF) << 16) | ((long) (b[o + 3] & 0xFF) << 24);
    }

    private static boolean notEmpty(String s) {
        return s != null && s.trim().length() > 0;
    }
}
