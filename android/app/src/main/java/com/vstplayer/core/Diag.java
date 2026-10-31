package com.vstplayer.core;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Append-only diagnostic log. One writer, one timestamp format, no per-line open/close.
 *
 * <p>The file rolls at {@link #MAX_BYTES} while the app runs, not only on the next start, so a
 * long session cannot grow it without bound. Writes are buffered by the stream and flushed on a
 * schedule; the one exception is the boot window and engine ({@code E|}) lines, which stay
 * immediate because they are what a failed start is diagnosed from. The very last lines before a
 * crash can always be lost with a buffered writer; every line also goes to {@code logcat}
 * ({@code adb logcat -s VSTP}), which is the crash-safe copy.
 */
public class Diag {
    private static final Object LOCK = new Object();
    private static final long MAX_BYTES = 4L * 1024 * 1024;
    /** Below this many bytes in the current file every line is flushed at once (boot window). */
    private static final long FLUSH_ALWAYS_BYTES = 256L * 1024;
    /** Otherwise flush at most this often, in milliseconds. */
    private static final long FLUSH_INTERVAL_MS = 1000L;

    private static File file;
    private static FileOutputStream out;
    private static final StringBuilder SB = new StringBuilder(128);
    private static final SimpleDateFormat FMT = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private static final Date NOW = new Date();
    /** Bytes in the current file, and when it was last flushed (elapsedRealtime). */
    private static long written;
    private static long flushedAt;

    public static void init(Context ctx) {
        synchronized (LOCK) {
            file = new File(ctx.getFilesDir(), "diag.log");
            close();
            written = file.length();
            flushedAt = 0;
            if (written > MAX_BYTES) rotate();
        }
    }

    /** Milliseconds since boot — lock-free clock for stage timing. */
    public static long now() {
        return android.os.SystemClock.elapsedRealtime();
    }

    public static void log(String msg) {
        android.util.Log.i("VSTP", msg);
        if (file == null) return;
        synchronized (LOCK) {
            try {
                if (out == null) out = new FileOutputStream(file, true);
                NOW.setTime(System.currentTimeMillis());
                SB.setLength(0);
                SB.append(FMT.format(NOW)).append(' ').append(msg).append('\n');
                byte[] bytes = SB.toString().getBytes(StandardCharsets.UTF_8);
                out.write(bytes);
                written += bytes.length;
                long now = now();
                boolean immediate = written < FLUSH_ALWAYS_BYTES
                        || (msg != null && msg.startsWith("E|"))
                        || now - flushedAt >= FLUSH_INTERVAL_MS;
                if (immediate) {
                    out.flush();
                    flushedAt = now;
                }
                if (written > MAX_BYTES) rotate();
            } catch (Exception ignored) {
                close();
            }
        }
    }

    /** Rolls diag.log -> diag.1.log and starts a fresh file. Caller holds {@link #LOCK}. */
    private static void rotate() {
        close();
        File old = new File(file.getParentFile(), "diag.1.log");
        //noinspection ResultOfMethodCallIgnored
        old.delete();
        //noinspection ResultOfMethodCallIgnored
        file.renameTo(old);
        written = 0;
        flushedAt = 0;
    }

    private static void close() {
        if (out != null) {
            try { out.close(); } catch (Exception ignored) { }
            out = null;
        }
    }
}
