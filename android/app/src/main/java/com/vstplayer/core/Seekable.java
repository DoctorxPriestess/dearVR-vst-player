package com.vstplayer.core;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;

/**
 * Random access byte source shared by every tag reader.
 *
 * <p>The library no longer copies imported audio into the app's private storage, so a metadata
 * reader can be handed either a plain {@link File} or a SAF document. Both are expressed as this
 * interface: an absolute position plus a buffered read. Implementations:
 *
 * <ul>
 * <li>{@link FileSeekable} — a {@link RandomAccessFile} for app-local / rendered files.</li>
 * <li>{@link SafSeekable} — a {@code ContentResolver.openFileDescriptor} descriptor driven through
 * {@code FileInputStream.getChannel().position()}, so a 200 MB FLAC is never read into memory.</li>
 * </ul>
 *
 * <p>A provider that hands back a pipe (some cloud providers) cannot seek. {@link #randomAccess()}
 * reports that, and the metadata readers degrade to "no tags" instead of reading the whole file.
 */
public interface Seekable extends Closeable {

    /** Total length in bytes, or 0 when the provider cannot report it. */
    long size() throws IOException;

    /** Current absolute read position. */
    long position() throws IOException;

    /** Moves the read position; sequential-only streams can only move forward. */
    void seek(long pos) throws IOException;

    /** Reads up to {@code len} bytes at the current position; -1 at end of file. */
    int read(byte[] dst, int off, int len) throws IOException;

    /** False when the source is a sequential stream (a pipe) rather than a seekable file. */
    boolean randomAccess();

    default int read(byte[] dst) throws IOException {
        return read(dst, 0, dst.length);
    }

    default void readFully(byte[] dst) throws IOException {
        readFully(dst, 0, dst.length);
    }

    /**
     * Reads exactly {@code len} bytes.
     *
     * <p>{@code read} may return 0: a pipe backed provider or a descriptor that is momentarily
     * empty. A loop that only treats -1 as end of stream then spins at 100% CPU forever without
     * making progress, so a bounded run of empty reads turns into an IOException instead.
     */
    default void readFully(byte[] dst, int off, int len) throws IOException {
        int done = 0;
        int idle = 0;
        while (done < len) {
            int n = read(dst, off + done, len - done);
            if (n < 0) throw new java.io.EOFException("eof at " + done + "/" + len + " bytes");
            if (n == 0) {
                if (++idle > Io.MAX_ZERO_READS) {
                    throw new IOException("no progress at " + done + "/" + len + " bytes");
                }
                continue;
            }
            idle = 0;
            done += n;
        }
    }

    /** Reads up to {@code n} bytes; the result is shorter only at end of file. */
    default byte[] read(int n) throws IOException {
        if (n <= 0) return new byte[0];
        byte[] buf = new byte[n];
        int done = 0;
        int idle = 0;
        while (done < n) {
            int r = read(buf, done, n - done);
            if (r < 0) break;
            if (r == 0) {
                if (++idle > Io.MAX_ZERO_READS) {
                    throw new IOException("no progress at " + done + "/" + n + " bytes");
                }
                continue;
            }
            idle = 0;
            done += r;
        }
        return done == n ? buf : Arrays.copyOf(buf, done);
    }

    /** Opens a plain file. */
    static Seekable of(File f) throws IOException {
        return new FileSeekable(f);
    }

    /** Opens a SAF document for reading. */
    static Seekable of(Context c, Uri uri) throws IOException {
        if (c == null) throw new IOException("no context to open " + uri);
        ContentResolver cr = c.getApplicationContext().getContentResolver();
        return new SafSeekable(cr, uri);
    }
}

/** {@link RandomAccessFile} backed source used for app-local and rendered files. */
final class FileSeekable implements Seekable {

    private final RandomAccessFile raf;
    private final long size;

    FileSeekable(File f) throws IOException {
        if (f == null) throw new IOException("null file");
        raf = new RandomAccessFile(f, "r");
        long len;
        try {
            len = raf.length();
        } catch (IOException e) {
            raf.close();
            throw e;
        }
        size = len;
    }

    @Override public long size() { return size; }

    @Override public long position() throws IOException { return raf.getFilePointer(); }

    @Override public void seek(long pos) throws IOException {
        if (pos < 0) throw new IOException("negative seek " + pos);
        raf.seek(pos);
    }

    @Override public int read(byte[] dst, int off, int len) throws IOException {
        return raf.read(dst, off, len);
    }

    @Override public boolean randomAccess() { return true; }

    @Override public void close() throws IOException { raf.close(); }
}

/**
 * SAF document backed source.
 *
 * <p>The descriptor's {@link FileChannel} is used for positioning, which is what makes the tag
 * readers cheap: an MP4 atom chain seeks over the audio payload instead of streaming it. When the
 * provider returns a pipe the channel cannot seek, {@link #randomAccess()} turns false and only
 * forward reads work.
 */
final class SafSeekable implements Seekable {

    private static final int SCAN_CHUNK = 64 * 1024;

    private final ParcelFileDescriptor pfd;
    private final FileInputStream in;
    private final FileChannel channel;
    private final long size;
    /** True when the descriptor is a sequential stream: seeks may only move forward. */
    private final boolean pipe;
    private long pos;

    SafSeekable(ContentResolver cr, Uri uri) throws IOException {
        if (cr == null) throw new IOException("no resolver for " + uri);
        ParcelFileDescriptor p = cr.openFileDescriptor(uri, "r");
        if (p == null) throw new IOException("openFileDescriptor returned null: " + uri);
        this.pfd = p;
        FileInputStream stream;
        try {
            stream = new FileInputStream(p.getFileDescriptor());
        } catch (RuntimeException e) {
            quietly(p);
            throw new IOException("no descriptor stream: " + e, e);
        }
        this.in = stream;
        this.channel = stream.getChannel();

        long len;
        try {
            len = channel.size();
        } catch (IOException e) {
            len = -1;
        }
        if (len <= 0) {
            try {
                len = p.getStatSize();
            } catch (Throwable ignored) {
                // stat unreliable; keep the channel answer
            }
        }
        this.size = Math.max(0, len);

        boolean sequential = false;
        try {
            pos = channel.position();
            channel.position(pos);
        } catch (IOException e) {
            sequential = true;
            pos = 0;
        }
        this.pipe = sequential;
        if (pipe) {
            // See randomAccess(): the tag readers refuse this source, so say once why.
            Diag.log("seekable: pipe source, metadata readers report no tags: " + uri);
        }
    }

    private static void quietly(ParcelFileDescriptor p) {
        try {
            p.close();
        } catch (Throwable ignored) {
        }
    }

    @Override public long size() { return size; }

    /**
     * False for a pipe backed descriptor. Only the pipe matters here: a provider that reports no
     * size but still seeks is perfectly usable, and it used to be refused as well.
     */
    @Override public boolean randomAccess() { return !pipe; }

    @Override public long position() throws IOException {
        return pipe ? pos : channel.position();
    }

    @Override public void seek(long target) throws IOException {
        if (target < 0) throw new IOException("negative seek " + target);
        if (!pipe) {
            channel.position(target);
            return;
        }
        if (target == pos) return;
        if (target < pos) throw new IOException("stream is not seekable (would rewind to " + target + ")");
        byte[] scratch = new byte[SCAN_CHUNK];
        int idle = 0;
        while (pos < target) {
            int want = (int) Math.min(SCAN_CHUNK, target - pos);
            int n = channel.read(ByteBuffer.wrap(scratch, 0, want));
            if (n < 0) throw new java.io.EOFException("eof while skipping to " + target);
            // A pipe can report 0 without being at end of stream; never advance blindly and
            // never loop on it without a bound.
            if (n == 0) {
                if (++idle > Io.MAX_ZERO_READS) {
                    throw new IOException("no progress skipping to " + target + " (" + pos + ")");
                }
                continue;
            }
            idle = 0;
            pos += n;
        }
    }

    @Override public int read(byte[] dst, int off, int len) throws IOException {
        if (len == 0) return 0;
        int n = channel.read(ByteBuffer.wrap(dst, off, len));
        if (n > 0) pos += n;
        return n;
    }

    @Override public void close() {
        try {
            channel.close();
        } catch (Throwable ignored) {
        }
        try {
            in.close();
        } catch (Throwable ignored) {
        }
        quietly(pfd);
    }
}
