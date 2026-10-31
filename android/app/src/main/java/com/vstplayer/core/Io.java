package com.vstplayer.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * One copy loop for the whole core layer.
 *
 * <p>{@link InputStream#read} may legally return {@code 0}: a non-blocking or pipe backed
 * provider, a decompressor that is waiting for more input, a tar reader between entries. Every
 * hand written loop that spelled the termination test as {@code while ((n = in.read(buf)) > 0)}
 * therefore treated one zero length read as end of stream and silently truncated the
 * destination. That is how a partially extracted rootfs or a half copied media file used to look
 * exactly like a successful one, with the real failure surfacing much later as an exec error or
 * a corrupt render.
 *
 * <p>Only {@code -1} ends a copy here. A run of zero length reads that never makes progress is
 * bounded so a wedged provider cannot spin a thread at 100% CPU; the bound is high enough that a
 * healthy decompressor never reaches it.
 */
public final class Io {

    /** Default copy buffer size, 64 KiB. */
    public static final int BUF = 1 << 16;

    /** Consecutive zero length reads tolerated before the stream is declared stuck. */
    public static final int MAX_ZERO_READS = 8;

    private Io() {
    }

    /** Copies {@code in} to {@code out} with the default buffer until the stream ends. */
    public static long copy(InputStream in, OutputStream out) throws IOException {
        return copy(in, out, new byte[BUF]);
    }

    /**
     * Copies {@code in} to {@code out} with a caller supplied buffer until the stream ends.
     *
     * @return number of bytes written
     * @throws IOException when the stream ends prematurely with an exception, or when
     *                     {@link #MAX_ZERO_READS} consecutive reads made no progress
     */
    public static long copy(InputStream in, OutputStream out, byte[] buf) throws IOException {
        long total = 0;
        int idle = 0;
        int n;
        while ((n = in.read(buf)) >= 0) {
            if (n == 0) {
                if (++idle > MAX_ZERO_READS) throw new IOException("stream made no progress");
                continue;
            }
            idle = 0;
            out.write(buf, 0, n);
            total += n;
        }
        return total;
    }

    /** Copies {@code in} into a byte array until the stream ends. */
    public static byte[] readAll(InputStream in) throws IOException {
        int hint = 8192;
        try {
            // A file stream reports its whole length here; never trust it for an allocation.
            hint = Math.max(256, Math.min(in.available(), 1 << 20));
        } catch (IOException ignored) {
            // fall through to the default size
        }
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream(hint);
        copy(in, bo);
        return bo.toByteArray();
    }
}
