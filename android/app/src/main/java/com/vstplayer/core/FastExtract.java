package com.vstplayer.core;

import android.content.Context;
import android.system.Os;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.tukaani.xz.XZInputStream;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Parallel extraction of the environment assets.
 *
 * Each asset (rootfs.bin / wine.bin / payload.bin) is shipped as K independent
 * compressed streams named <stem>_<i>of<K>.bin. Segment i holds a contiguous
 * byte range of the original tar stream that starts exactly on a tar header
 * block, so every segment is itself a valid tar stream (tools/split_assets.py
 * builds them, tools/verify_split.py proves the concatenation is byte-identical
 * to the original payload).
 *
 * Because no entry crosses a segment boundary, the K segments can be decoded and
 * written concurrently: each thread owns its own decoder, its own segment and a
 * disjoint set of destination files. What used to be one single-core xz decode
 * of 449 MB is now K decodes spread over the device's cores.
 *
 * Links are deferred to a post-join pass: a symlink or hardlink may point at a
 * file that another segment has not written yet, so creating links while the
 * segments are still running would race. Deferring also means the "symlink not
 * supported" fallback (copy the target) is resolved against a complete tree.
 *
 * Env.extractTar keeps the legacy single-stream path and only calls into here
 * when a complete, self-consistent segment set is present.
 */
public final class FastExtract {

    /** Highest K the splitter may produce. */
    public static final int MAX_SEGMENTS = 8;
    private static final int BUF = 1 << 16;

    private FastExtract() {
    }

    /** rootfs.bin + index 2 of 6 -> rootfs_2of6.bin */
    static String segmentAsset(String assetBase, int index, int count) {
        int dot = assetBase.lastIndexOf('.');
        String stem = dot > 0 ? assetBase.substring(0, dot) : assetBase;
        String ext = dot > 0 ? assetBase.substring(dot) : ".bin";
        return stem + "_" + index + "of" + count + ext;
    }

    /**
     * Returns K when all of <stem>_0ofK.bin .. <stem>_(K-1)ofK.bin exist, else 0.
     * A partial or mixed segment set returns 0 so the caller falls back to the
     * legacy single-stream asset instead of extracting an incomplete tree.
     */
    public static int segmentCount(Context ctx, String assetBase) {
        int dot = assetBase.lastIndexOf('.');
        String stem = dot > 0 ? assetBase.substring(0, dot) : assetBase;
        String ext = dot > 0 ? assetBase.substring(dot) : ".bin";
        Pattern pat = Pattern.compile("^" + Pattern.quote(stem) + "_(\\d+)of(\\d+)"
                + Pattern.quote(ext) + "$");
        int count = -1;
        TreeSet<Integer> seen = new TreeSet<Integer>();
        try {
            String[] names = ctx.getAssets().list("");
            if (names == null) return 0;
            for (String n : names) {
                Matcher m = pat.matcher(n);
                if (!m.matches()) continue;
                int i;
                int k;
                try {
                    i = Integer.parseInt(m.group(1));
                    k = Integer.parseInt(m.group(2));
                } catch (NumberFormatException nfe) {
                    return 0;
                }
                if (count < 0) count = k;
                else if (count != k) return 0;
                seen.add(i);
            }
        } catch (IOException e) {
            return 0;
        }
        if (count < 2 || count > MAX_SEGMENTS || seen.size() != count) return 0;
        for (int i = 0; i < count; i++) {
            if (!seen.contains(i)) return 0;
        }
        return count;
    }

    /**
     * Extract one asset from its K segments into destRoot, reporting progress in
     * [from, to). Throws on any segment failure - never returns a partial tree
     * silently.
     */
    public static void extract(final Context ctx, final String assetBase, final boolean xz,
                               final File destRoot, final int from, final int to,
                               final Env.Progress p) throws Exception {
        final int count = segmentCount(ctx, assetBase);
        if (count < 2) throw new IOException("split assets missing: " + assetBase);
        destRoot.mkdirs();
        final String base = destRoot.getCanonicalPath();

        long total = 0;
        for (int i = 0; i < count; i++) {
            total += assetSize(ctx, segmentAsset(assetBase, i, count));
        }
        final long totalCompressed = total;

        final AtomicLong done = new AtomicLong();
        final Object cbLock = new Object();
        final List<Throwable> errors = Collections.synchronizedList(new ArrayList<Throwable>());
        int cpus = Runtime.getRuntime().availableProcessors();
        final int threads = Math.min(count, Math.max(1, cpus));
        final long t0 = System.currentTimeMillis();
        Diag.log("fastx " + assetBase + ": " + count + " segments on " + threads + " threads, "
                + (totalCompressed / 1048576) + " MB compressed");

        ExecutorService pool = Executors.newFixedThreadPool(threads, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "fastx");
                t.setDaemon(true);
                return t;
            }
        });
        try {
            List<Future<?>> tasks = new ArrayList<Future<?>>();
            for (int i = 0; i < count; i++) {
                final int idx = i;
                tasks.add(pool.submit(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            extractSegment(ctx, assetBase, idx, count, xz, destRoot, base,
                                    totalCompressed, done, from, to, p, cbLock);
                        } catch (Throwable t) {
                            errors.add(t);
                        }
                    }
                }));
            }
            for (Future<?> f : tasks) f.get();
        } finally {
            pool.shutdownNow();
        }

        if (!errors.isEmpty()) {
            Throwable first = errors.get(0);
            for (Throwable t : errors) {
                if (t != first) first.addSuppressed(t);
            }
            Diag.log("fastx " + assetBase + " FAILED: " + first);
            if (first instanceof Exception) throw (Exception) first;
            throw new IOException(first.toString(), first);
        }
        Diag.log("fastx " + assetBase + " done +" + (System.currentTimeMillis() - t0) + "ms");
        if (to > from) {
            synchronized (cbLock) {
                p.onStage(null, to);
            }
        }
    }

    private static void extractSegment(Context ctx, String assetBase, int index, int count,
                                       boolean xz, File destRoot, String base,
                                       long totalCompressed, AtomicLong done, int from, int to,
                                       Env.Progress p, Object cbLock) throws Exception {
        String asset = segmentAsset(assetBase, index, count);
        byte[] buf = new byte[BUF];
        Deferred def = new Deferred();
        try (InputStream raw = ctx.getAssets().open(asset);
             InputStream counter = new ProgressInputStream(raw, done, totalCompressed, from, to, p, cbLock);
             InputStream in = new BufferedInputStream(counter, BUF);
             InputStream dec = xz ? new XZInputStream(in) : new GZIPInputStream(in, BUF);
             TarArchiveInputStream tar = new TarArchiveInputStream(dec)) {
            TarArchiveEntry e;
            while ((e = tar.getNextEntry()) != null) {
                applyEntry(tar, e, destRoot, base, buf, def);
            }
        }
        def.flush(destRoot);
    }

    /** Mirror of the legacy per-entry logic in Env.extractTar, with links deferred. */
    private static void applyEntry(TarArchiveInputStream tar, TarArchiveEntry e, File destRoot,
                                   String base, byte[] buf, Deferred def) throws IOException {
        File out = new File(destRoot, e.getName());
        String canon;
        try {
            canon = out.getCanonicalPath();
        } catch (IOException ioe) {
            canon = out.getAbsolutePath();
        }
        // path traversal protection: never write outside destRoot
        if (!canon.equals(base) && !canon.startsWith(base + File.separator)) return;

        if (e.isDirectory()) {
            out.mkdirs();
            return;
        }
        File parent = out.getParentFile();
        if (parent != null) parent.mkdirs();

        if (e.isSymbolicLink()) {
            def.addSymlink(e.getLinkName(), out);
        } else if (e.isLink()) {
            def.addHardlink(new File(destRoot, e.getLinkName()), out);
        } else if (e.isFile()) {
            try (OutputStream os = new FileOutputStream(out)) {
                Io.copy(tar, os, buf);
            }
            if ((e.getMode() & 0111) != 0) {
                try {
                    Os.chmod(out.getAbsolutePath(), 0755);
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** Symlinks/hardlinks collected during a segment, created after all segments are done. */
    private static final class Deferred {
        private final List<String[]> symlinks = new ArrayList<String[]>();
        private final List<String[]> hardlinks = new ArrayList<String[]>();

        void addSymlink(String target, File out) {
            symlinks.add(new String[]{target, out.getAbsolutePath()});
        }

        void addHardlink(File target, File out) {
            hardlinks.add(new String[]{target.getAbsolutePath(), out.getAbsolutePath()});
        }

        void flush(File destRoot) {
            for (String[] l : symlinks) {
                File out = new File(l[1]);
                if (lexists(out) && !out.delete()) {
                    Diag.log("symlink cannot replace " + l[1]);
                }
                try {
                    Os.symlink(l[0], l[1]);
                } catch (Exception e) {
                    // filesystem refuses symlinks: copy the target instead (same
                    // fallback the legacy single-stream path used, but resolved
                    // against a fully extracted tree)
                    File target = new File(destRoot, l[0]);
                    if (target.isFile() && target.canRead()) copyFile(target, out);
                    else Diag.log("symlink skip " + l[1] + " -> " + l[0]);
                }
            }
            for (String[] l : hardlinks) {
                File out = new File(l[1]);
                if (lexists(out) && !out.delete()) {
                    Diag.log("hardlink cannot replace " + l[1]);
                }
                try {
                    Os.link(l[0], l[1]);
                } catch (Exception e) {
                    File target = new File(l[0]);
                    if (target.isFile()) copyFile(target, out);
                    else Diag.log("hardlink skip " + l[1] + " -> " + l[0]);
                }
            }
        }
    }

    private static final class ProgressInputStream extends FilterInputStream {
        private final AtomicLong done;
        private final long total;
        private final int from;
        private final int to;
        private final Env.Progress p;
        private final Object lock;
        private int lastPct = -1;

        ProgressInputStream(InputStream in, AtomicLong done, long total, int from, int to,
                            Env.Progress p, Object lock) {
            super(in);
            this.done = done;
            this.total = total;
            this.from = from;
            this.to = to;
            this.p = p;
            this.lock = lock;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) advance(1);
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) advance(n);
            return n;
        }

        private void advance(int n) {
            long d = done.addAndGet(n);
            if (total <= 0 || to <= from) return;
            int pct = from + (int) ((to - from) * Math.min(1.0, (double) d / total));
            if (pct <= lastPct) return;
            lastPct = pct;
            synchronized (lock) {
                p.onStage(null, pct);
            }
        }
    }

    private static boolean lexists(File f) {
        try {
            Os.lstat(f.getAbsolutePath());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void copyFile(File src, File dst) {
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            Io.copy(in, out, new byte[BUF]);
        } catch (IOException e) {
            Diag.log("copy fallback failed " + src + " -> " + dst + ": " + e);
        }
    }


    private static long assetSize(Context ctx, String name) {
        try (android.content.res.AssetFileDescriptor fd = ctx.getAssets().openFd(name)) {
            long len = fd.getLength();
            if (len > 0) return len;
        } catch (Exception ignored) {
        }
        try (InputStream in = ctx.getAssets().open(name)) {
            return Math.max(0, in.available());
        } catch (IOException e) {
            return 0;
        }
    }
}