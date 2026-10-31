package com.vstplayer.core;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Reads the header of a PCM WAV file.
 *
 * <p>Kept free of android.* so the host test suite ({@code tools/tests}) can pin the
 * parsing rules: the render host writes plain 44 byte headers for 16 and 24 bit
 * sources, but a streamed or extensible header is legal input too, and a wrong
 * data offset would silently compress the (text) header into the FLAC stream.
 *
 * <p>Only integer PCM (format tag 1, also through WAVE_FORMAT_EXTENSIBLE) is
 * accepted; a float WAV returns null and the caller keeps the WAV.
 */
public final class Wav {

    private Wav() { }

    /** The parts of the header the encoders need. */
    public static final class Info {
        public final int channels;
        public final int rate;
        public final int bits;
        public final long dataOffset;
        public final long frames;

        Info(int channels, int rate, int bits, long dataOffset, long frames) {
            this.channels = channels;
            this.rate = rate;
            this.bits = bits;
            this.dataOffset = dataOffset;
            this.frames = frames;
        }

        public long bytesPerSample() {
            return bits / 8;
        }
    }

    /** Parses the header, or returns null when this is not integer PCM. */
    public static Info probe(File f) {
        if (f == null || !f.isFile()) return null;
        RandomAccessFile r;
        try {
            r = new RandomAccessFile(f, "r");
        } catch (IOException e) {
            return null;
        }
        try {
            long size = r.length();
            if (size < 44) return null;
            byte[] h = new byte[12];
            r.readFully(h);
            if (h[0] != 'R' || h[1] != 'I' || h[2] != 'F' || h[3] != 'F') return null;
            if (h[8] != 'W' || h[9] != 'A' || h[10] != 'V' || h[11] != 'E') return null;
            int format = 0;
            int channels = 0;
            int rate = 0;
            int bits = 0;
            while (r.getFilePointer() + 8 <= size) {
                byte[] ck = new byte[8];
                r.readFully(ck);
                long len = le32(ck, 4) & 0xFFFFFFFFL;
                if (ck[0] == 'f' && ck[1] == 'm' && ck[2] == 't' && ck[3] == ' ') {
                    byte[] fmt = new byte[(int) Math.min(len, 40)];
                    r.readFully(fmt);
                    if (fmt.length < 16) return null;
                    format = le16(fmt, 0);
                    channels = le16(fmt, 2);
                    rate = (int) le32(fmt, 4);
                    bits = le16(fmt, 14);
                    if (format == 0xFFFE && fmt.length >= 26) format = le16(fmt, 24);
                    if (len > fmt.length) r.seek(r.getFilePointer() + (len - fmt.length));
                } else if (ck[0] == 'd' && ck[1] == 'a' && ck[2] == 't' && ck[3] == 'a') {
                    if (format != 1) return null;
                    if (channels < 1 || rate < 1 || bits < 8) return null;
                    long offset = r.getFilePointer();
                    long avail = size - offset;
                    long bytes = (len == 0 || len > avail) ? avail : len;
                    long frames = bytes / ((long) channels * (bits / 8));
                    return frames > 0 ? new Info(channels, rate, bits, offset, frames) : null;
                } else {
                    r.seek(r.getFilePointer() + len + (len & 1));
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        } finally {
            try { r.close(); } catch (IOException ignored) { }
        }
    }

    private static int le16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static long le32(byte[] b, int off) {
        return (b[off] & 0xFFL) | ((b[off + 1] & 0xFFL) << 8)
                | ((b[off + 2] & 0xFFL) << 16) | ((b[off + 3] & 0xFFL) << 24);
    }
}
