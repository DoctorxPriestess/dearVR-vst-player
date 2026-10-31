package com.vstplayer.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;

/**
 * Frame-exact WAV cutting and joining, used to render one long file on several engines at once.
 *
 * <p>The renderer is a stateful plugin (convolution reverb, HRTF filters) whose tail was measured
 * at ~1.35 s to -120 dBFS, so a slice that starts mid-file must carry a pre-roll of real audio in
 * front of it; the renderer's output for those pre-roll frames is thrown away afterwards. With that
 * pre-roll every slice starts from an equivalent internal state and the joined result matches a
 * single-shot render to roughly -94 dBFS RMS (the plugin's reverb diffusion is noise based, so the
 * match is audible-quality, not bit-exact).
 *
 * <p>Everything here works on raw frame bytes: slicing copies frames verbatim and joining rewrites
 * only the RIFF/data sizes, so any PCM WAV layout (16/24/32-bit, extra chunks like the renderer's
 * JUNK header) survives the round trip.
 */
public final class Segment {

    private static final int BUF = 1 << 20;

    /** Parsed WAV layout. {@code fmt} keeps the 16 raw fmt payload bytes so a new header can be
     *  written without re-deriving the sample format. */
    public static final class Info {
        public final int channels, rate, bits, blockAlign, fmtTag;
        public final long frames, dataOffset, dataLen;
        final byte[] fmt;

        Info(int channels, int rate, int bits, int blockAlign, int fmtTag,
             long frames, long dataOffset, long dataLen, byte[] fmt) {
            this.channels = channels;
            this.rate = rate;
            this.bits = bits;
            this.blockAlign = blockAlign;
            this.fmtTag = fmtTag;
            this.frames = frames;
            this.dataOffset = dataOffset;
            this.dataLen = dataLen;
            this.fmt = fmt;
        }

        public double seconds() {
            return rate <= 0 ? 0 : (double) frames / rate;
        }
    }

    private Segment() {
    }

    /** True when the file starts with a RIFF/WAVE signature; everything else needs decoding first. */
    public static boolean isWav(File f) {
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            byte[] h = new byte[12];
            if (r.read(h) != 12) return false;
            return h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F'
                    && h[8] == 'W' && h[9] == 'A' && h[10] == 'V' && h[11] == 'E';
        } catch (IOException e) {
            return false;
        }
    }

    /** Walks the chunk list and returns the layout of the first {@code data} chunk. */
    public static Info probe(File wav) throws IOException {
        try (RandomAccessFile r = new RandomAccessFile(wav, "r")) {
            byte[] head = new byte[12];
            if (r.read(head) != 12) throw new IOException("not a wav: short header");
            if (head[0] != 'R' || head[1] != 'I' || head[2] != 'F' || head[3] != 'F') {
                throw new IOException("not a wav: missing RIFF");
            }
            long pos = 12;
            byte[] chunk = new byte[8];
            int channels = 0, rate = 0, bits = 0, blockAlign = 0, fmtTag = 1;
            byte[] fmt = null;
            while (pos + 8 <= r.length()) {
                r.seek(pos);
                if (r.read(chunk) != 8) break;
                String id = new String(chunk, 0, 4, java.nio.charset.StandardCharsets.US_ASCII);
                long size = u32(chunk, 4);
                if (id.equals("fmt ") && size >= 16) {
                    byte[] fb = new byte[16];
                    if (r.read(fb) != 16) throw new IOException("bad fmt chunk");
                    fmt = fb;
                    fmtTag = u16(fb, 0);
                    channels = u16(fb, 2);
                    rate = (int) u32(fb, 4);
                    blockAlign = u16(fb, 12);
                    bits = u16(fb, 14);
                } else if (id.equals("data")) {
                    if (fmt == null) throw new IOException("data before fmt");
                    long off = pos + 8;
                    long avail = r.length() - off;
                    long len = size <= 0 || size > avail ? avail : size;
                    if (blockAlign <= 0) blockAlign = Math.max(1, channels * bits / 8);
                    long frames = len / Math.max(1, blockAlign);
                    return new Info(channels, rate, bits, blockAlign, fmtTag, frames, off,
                            frames * blockAlign, fmt);
                }
                pos += 8 + size + (size & 1);
            }
            throw new IOException("no data chunk");
        }
    }

    /**
     * Writes {@code frames} frames starting at {@code startFrame} of {@code src} into a new
     * canonical WAV. The sample format is copied verbatim, so bit depth and channel count are
     * preserved exactly.
     */
    public static void cut(File src, Info in, long startFrame, long frames, File dst)
            throws IOException {
        if (startFrame < 0 || frames < 0 || startFrame + frames > in.frames) {
            throw new IOException("slice out of range: " + startFrame + "+" + frames
                    + " of " + in.frames);
        }
        byte[] hdr = header(in, frames * in.blockAlign);
        try (RandomAccessFile r = new RandomAccessFile(src, "r");
             OutputStream out = new FileOutputStream(dst)) {
            out.write(hdr);
            r.seek(in.dataOffset + startFrame * in.blockAlign);
            copy(r, out, frames * in.blockAlign);
        }
    }

    /**
     * Concatenates rendered slices, dropping {@code skipFrames[i]} frames at the start of slice i
     * (the pre-roll) and rewriting the header for the combined length.
     */
    public static void join(File[] parts, int[] skipFrames, File dst) throws IOException {
        Info first = probe(parts[0]);
        long total = 0;
        Info[] infos = new Info[parts.length];
        for (int i = 0; i < parts.length; i++) {
            Info p = probe(parts[i]);
            if (p.channels != first.channels || p.rate != first.rate || p.bits != first.bits
                    || p.blockAlign != first.blockAlign) {
                throw new IOException("slice format mismatch at " + i + ": " + p.channels + "ch/"
                        + p.bits + "bit vs " + first.channels + "ch/" + first.bits + "bit");
            }
            int skip = i < skipFrames.length ? Math.max(0, skipFrames[i]) : 0;
            if (skip > p.frames) skip = (int) p.frames;
            infos[i] = p;
            total += p.frames - skip;
        }
        byte[] hdr = header(first, total * first.blockAlign);
        try (OutputStream out = new FileOutputStream(dst)) {
            out.write(hdr);
            for (int i = 0; i < parts.length; i++) {
                Info p = infos[i];
                int skip = i < skipFrames.length ? Math.max(0, skipFrames[i]) : 0;
                if (skip > p.frames) skip = (int) p.frames;
                long keep = (p.frames - skip) * p.blockAlign;
                if (keep <= 0) continue;
                try (RandomAccessFile r = new RandomAccessFile(parts[i], "r")) {
                    r.seek(p.dataOffset + (long) skip * p.blockAlign);
                    copy(r, out, keep);
                }
            }
        }
    }

    private static byte[] header(Info in, long dataBytes) {
        byte[] h = new byte[44];
        ascii(h, 0, "RIFF");
        put32(h, 4, dataBytes + 36);
        ascii(h, 8, "WAVE");
        ascii(h, 12, "fmt ");
        put32(h, 16, 16);
        System.arraycopy(in.fmt, 0, h, 20, 16);
        ascii(h, 36, "data");
        put32(h, 40, dataBytes);
        return h;
    }

    private static void copy(RandomAccessFile in, OutputStream out, long bytes) throws IOException {
        byte[] buf = new byte[BUF];
        long left = bytes;
        while (left > 0) {
            int n = in.read(buf, 0, (int) Math.min(BUF, left));
            if (n < 0) throw new IOException("unexpected end of slice");
            out.write(buf, 0, n);
            left -= n;
        }
    }

    private static void ascii(byte[] b, int off, String s) {
        for (int i = 0; i < s.length(); i++) b[off + i] = (byte) s.charAt(i);
    }

    private static void put32(byte[] b, int off, long v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >> 8);
        b[off + 2] = (byte) (v >> 16);
        b[off + 3] = (byte) (v >> 24);
    }

    private static int u16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static long u32(byte[] b, int off) {
        return (b[off] & 0xFFL) | ((b[off + 1] & 0xFFL) << 8)
                | ((b[off + 2] & 0xFFL) << 16) | ((b[off + 3] & 0xFFL) << 24);
    }

    /** True when the file starts with the FLAC stream marker. */
    public static boolean isFlac(File f) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] b = new byte[4];
            if (in.read(b) != 4) return false;
            return b[0] == 0x66 && b[1] == 0x4C && b[2] == 0x61 && b[3] == 0x43;   // "fLaC"
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Bits per sample from the FLAC STREAMINFO block, or 0 when it cannot be read. The platform
     * decoder ignores this and always hands back 16 bit, so a deeper file must not be sliced.
     */
    public static int flacBits(File f) {
        try (java.io.RandomAccessFile r = new java.io.RandomAccessFile(f, "r")) {
            byte[] head = new byte[4];
            r.readFully(head);
            if (head[0] != 0x66 || head[1] != 0x4C || head[2] != 0x61 || head[3] != 0x43) return 0;
            int type = 0;
            while (type != 0x7F) {
                int flags = r.readUnsignedByte();
                type = flags & 0x7F;
                int len = (r.readUnsignedByte() << 16) | (r.readUnsignedByte() << 8) | r.readUnsignedByte();
                if (type == 0) {
                    byte[] si = new byte[Math.min(len, 34)];
                    r.readFully(si);
                    if (si.length < 18) return 0;
                    // 20 bit sample rate, 3 bit channels-1, 5 bit bits-per-sample-1, 36 bit frames
                    long v = 0;
                    for (int i = 10; i < 18; i++) v = (v << 8) | (si[i] & 0xFFL);
                    int bps = (int) ((v >>> 36) & 0x1F) + 1;
                    return bps;
                }
                if (flags == 0x7F) return 0;
                if (r.skipBytes(len) != len) return 0;
            }
        } catch (Exception e) {
            return 0;
        }
        return 0;
    }
}
