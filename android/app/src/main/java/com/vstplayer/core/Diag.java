package com.vstplayer.core;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Append-only diagnostic log. One writer, one timestamp format, no per-line open/close. */
public class Diag {
    private static final Object LOCK = new Object();
    private static final long MAX_BYTES = 4L * 1024 * 1024;

    private static File file;
    private static FileOutputStream out;
    private static final StringBuilder SB = new StringBuilder(128);
    private static final SimpleDateFormat FMT = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private static final Date NOW = new Date();

    public static void init(Context ctx) {
        synchronized (LOCK) {
            file = new File(ctx.getFilesDir(), "diag.log");
            close();
            if (file.length() > MAX_BYTES) {
                File old = new File(file.getParentFile(), "diag.1.log");
                old.delete();
                file.renameTo(old);
            }
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
                out.write(SB.toString().getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (Exception ignored) {
                close();
            }
        }
    }

    private static void close() {
        if (out != null) {
            try { out.close(); } catch (Exception ignored) { }
            out = null;
        }
    }
}
