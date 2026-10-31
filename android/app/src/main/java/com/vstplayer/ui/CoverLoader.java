package com.vstplayer.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import com.vstplayer.core.Diag;
import com.vstplayer.core.Tags;

import java.io.File;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Shared off-thread artwork/metadata pipeline.
 *
 * <p>Tags are read once per file, artwork is decoded once per (file, target width) and both are
 * cached, so the home screen, the detail page and the letterboxed placeholder agree on the same
 * data instead of each decoding the same JPEG on the UI thread. Callbacks always arrive on the UI
 * thread; use {@link Request#cancel()} to drop a result that belongs to a track the user already
 * swiped past.
 */
public final class CoverLoader {

    public interface Callback<T> {
        void onResult(File file, T value);
    }

    /** Handle for one load; cancel it when a newer request replaces it. */
    public static final class Request {
        private volatile boolean cancelled;

        public void cancel() { cancelled = true; }

        public boolean cancelled() { return cancelled; }
    }

    private static final Handler UI = new Handler(Looper.getMainLooper());

    /** One lane for metadata and decoding: sequential, so a stale request cannot overtake a fresh one. */
    private static final ExecutorService BG = Executors.newSingleThreadExecutor(runnable -> {
        Thread t = new Thread(runnable, "cover-loader");
        t.setDaemon(true);
        return t;
    });

    private static final LruCache<String, Tags.Info> TAG_CACHE = new LruCache<String, Tags.Info>(4096) {
        @Override protected int sizeOf(String key, Tags.Info v) {
            int bytes = v == null || v.cover == null ? 0 : v.cover.length;
            return Math.max(1, (bytes + 4096) / 1024);
        }
    };

    private static final LruCache<String, Bitmap> ART_CACHE = new LruCache<String, Bitmap>(artCacheKb()) {
        @Override protected int sizeOf(String key, Bitmap v) {
            return v == null || v.isRecycled() ? 1 : Math.max(1, v.getByteCount() / 1024);
        }
    };

    private CoverLoader() { }

    /** Reads (and caches) the tags of {@code f} off the UI thread. */
    public static Request tags(File f, Callback<Tags.Info> cb) {
        return submit(f, () -> tagsNow(f), cb);
    }

    /** Decodes the embedded artwork at most {@code px} wide, or a generated placeholder. */
    public static Request cover(Context c, File f, final int px, Callback<Bitmap> cb) {
        final Context app = c.getApplicationContext();
        return submit(f, () -> artNow(app, f, px), cb);
    }

    /** Decodes the artwork and pre-blurs the whole ladder; install with {@link CoverMorph#setPrepared}. */
    public static Request backdrop(Context c, File f, final int px, final int refWidth,
                                   Callback<CoverMorph.Prepared> cb) {
        final Context app = c.getApplicationContext();
        return submit(f, () -> CoverMorph.prepare(artNow(app, f, px), refWidth), cb);
    }

    // ------------------------------------------------------------------ internals

    private static <T> Request submit(final File f, final Callable<T> work, final Callback<T> cb) {
        final Request req = new Request();
        BG.execute(() -> {
            if (req.cancelled) return;
            T value;
            try {
                value = work.call();
            } catch (Throwable t) {
                // A broken or unsupported file must show up in the log instead of vanishing.
                Diag.log("cover load failed " + f.getName() + ": " + t);
                return;
            }
            if (req.cancelled) return;
            final T result = value;
            UI.post(() -> {
                if (!req.cancelled) cb.onResult(f, result);
            });
        });
        return req;
    }

    private static Tags.Info tagsNow(File f) {
        String key = f.getAbsolutePath();
        Tags.Info cached = TAG_CACHE.get(key);
        if (cached != null) return cached;
        Tags.Info info = Tags.read(f);
        TAG_CACHE.put(key, info);
        return info;
    }

    private static Bitmap artNow(Context app, File f, int px) {
        String key = f.getAbsolutePath() + "@" + px;
        Bitmap cached = ART_CACHE.get(key);
        if (cached != null && !cached.isRecycled()) return cached;
        Tags.Info info = tagsNow(f);
        Bitmap bitmap = CoverMorph.decode(info == null ? null : info.cover, px);
        if (bitmap == null) bitmap = CoverMorph.placeholder(app, f.getAbsolutePath(), px);
        ART_CACHE.put(key, bitmap);
        return bitmap;
    }

    private static int artCacheKb() {
        return (int) Math.max(4 * 1024, Runtime.getRuntime().maxMemory() / 1024 / 8);
    }
}
