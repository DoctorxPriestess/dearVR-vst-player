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
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

/**
 * Owns the on-device environment.
 *
 * Layout (all under filesDir):
 *   bin/                 proot + helper libs
 *   tmp/                 proot temp dir
 *   glibc/               the Mobox glibc rootfs, also the proot -r root
 *     bin/bash, bin/box64
 *     lib/ld-linux-aarch64.so.1
 *     wine-9.3-vanilla-wow64/
 *     opt/vstenv/{payload,work,wineprefix,run.sh}
 */
public class Env {
    public static final String VERSION = "4";
    private static final int MAX_INTERP = 63;

    public interface Progress {
        void onStage(String label, int percent);
        void onLog(String msg);
    }

    private final Context ctx;
    public final File root;
    public final File bin;
    public final File glibc;
    public final File tmp;
    public final File payload;
    public final File winePrefix;

    public Env(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        root = this.ctx.getFilesDir();
        bin = new File(root, "bin");
        glibc = new File(root, "glibc");
        tmp = new File(root, "tmp");
        payload = new File(glibc, "opt/vstenv/payload");
        winePrefix = new File(glibc, "opt/vstenv/wineprefix");
    }

    public File glbcDir() {
        return glibc;
    }

    /** Debug builds accept launch overrides from files/boot.conf. */
    public boolean isDebuggable() {
        return (ctx.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    /** Guest work directories, created once and re-created after {@link #invalidateDirs()}. */
    private File workCache;
    private File inCache;
    private File outCache;

    /**
     * The guest work directory, created on first use and then cached.
     *
     * <p>It used to create the same directory on every call, and the render queue asks for these
     * paths once per track (twice through ReplayGain's cache key), while a ReplayGain scan asks for
     * them once per file. They only change when the environment is rebuilt, which invalidates them.
     */
    public File workDir() {
        synchronized (this) {
            if (workCache == null) workCache = ensure(new File(glibc, "opt/vstenv/work"));
            return workCache;
        }
    }

    /** Guest input directory handed to the renderer (decoded or staged WAVs). */
    public File inDir() {
        synchronized (this) {
            if (inCache == null) inCache = ensure(new File(workDir(), "in"));
            return inCache;
        }
    }

    /** Guest output directory the renderer writes into. */
    public File outDir() {
        synchronized (this) {
            if (outCache == null) outCache = ensure(new File(workDir(), "out"));
            return outCache;
        }
    }

    /** Drops the cached directories so the next access re-creates them. */
    public void invalidateDirs() {
        synchronized (this) {
            workCache = null;
            inCache = null;
            outCache = null;
        }
    }

    private static File ensure(File f) {
        if (!f.isDirectory()) f.mkdirs();
        return f;
    }

    public boolean isReady() {
        return new File(root, ".ready-" + VERSION).exists()
                && new File(bin, "proot").exists()
                && new File(glibc, "bin/bash").exists()
                && new File(glibc, "bin/box64").exists();
    }

    public void markReady() throws IOException {
        File marker = new File(root, ".ready-" + VERSION);
        marker.createNewFile();
    }

    public void extract(Progress p) throws Exception {
        Diag.log("env.extract start root=" + root);
        root.mkdirs();
        bin.mkdirs();
        tmp.mkdirs();

        p.onStage("基础工具", 1);
        copyAsset("proot", new File(bin, "proot"));
        copyAsset("proot-loader", new File(bin, "proot-loader"));
        copyAsset("libtalloc.so.2", new File(bin, "libtalloc.so.2"));
        copyAsset("libandroid-shmem.so", new File(bin, "libandroid-shmem.so"));

        p.onStage("Linux 运行环境", 6);
        extractTar("rootfs.bin", true, root, 6, 40, p);

        p.onStage("Wine 运行时", 40);
        extractTar("wine.bin", true, root, 40, 78, p);

        p.onStage("插件与预设", 78);
        File vstenv = new File(glibc, "opt/vstenv");
        vstenv.mkdirs();
        extractTar("payload.bin", false, vstenv, 78, 90, p);

        p.onStage("配置运行环境", 90);
        copyAsset("box64", new File(glibc, "bin/box64"));
        File wineUnix = new File(glibc, "wine-9.3-vanilla-wow64/lib/wine/x86_64-unix");
        wineUnix.mkdirs();
        copyAsset("libunwind8.bin", new File(wineUnix, "libunwind.so.8"));
        copyAsset("libgcc_s1.bin", new File(wineUnix, "libgcc_s.so.1"));

        String interp = new File(glibc, "lib/ld-linux-aarch64.so.1").getAbsolutePath();
        if (interp.length() > MAX_INTERP) {
            throw new IOException("interpreter path too long (" + interp.length() + "): " + interp);
        }
        Diag.log("patch interp -> " + interp);
        int patched = patchInterp(new File(glibc, "bin"), interp);
        Diag.log("patched " + patched + " binaries");

        // The interpreter path must resolve twice: on the host (kernel opens it
        // before proot can translate) and inside the guest. Mirror it with a
        // symlink back to the guest root.
        String prefix = glibc.getAbsolutePath();
        File mirror = new File(glibc, prefix.substring(1));
        mirror.getParentFile().mkdirs();
        if (!mirror.exists()) {
            try {
                Os.symlink("/", mirror.getAbsolutePath());
                Diag.log("mirror link " + mirror + " -> /");
            } catch (Exception e) {
                Diag.log("mirror link failed: " + e);
            }
        }
        new File(glibc, "data/data/com.termux/files/usr/tmp").mkdirs();

        writeRunScript();
        ensurePluginData();
        chmod(new File(bin, "proot"));
        chmod(new File(bin, "proot-loader"));
        chmod(new File(glibc, "bin/box64"));
        p.onStage("准备就绪", 100);
        markReady();
    }

    /**
     * dearVR PRO 2 loads its virtual-acoustics data from C:\ProgramData\DearReality\dearVRPRO2,
     * not from the plugin bundle. Without it the plugin still instantiates and reports all of its
     * parameters, but every render comes out silent. The tree is pre-seeded here (wineboot keeps
     * it) and hard-linked to the payload copy so the 92 MB are not stored twice.
     */
    void ensurePluginData() {
        File src = new File(payload, "dearVRPRO2");
        File[] files = src.listFiles();
        if (files == null || files.length == 0) return;
        File dir = new File(winePrefix, "drive_c/ProgramData/DearReality/dearVRPRO2");
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            Diag.log("plugin data: cannot create " + dir);
            return;
        }
        long t0 = Diag.now();
        int linked = 0, copied = 0, kept = 0;
        for (File f : files) {
            if (!f.isFile()) continue;
            File t = new File(dir, f.getName());
            if (t.isFile() && t.length() == f.length()) { kept++; continue; }
            t.delete();
            try {
                Os.link(f.getAbsolutePath(), t.getAbsolutePath());
                linked++;
            } catch (Exception e) {
                if (copyFile(f, t)) copied++;
                else { Diag.log("plugin data: " + f.getName() + " failed: " + e); return; }
            }
        }
        Diag.log("plugin data: linked=" + linked + " copied=" + copied + " kept=" + kept
                + " +" + (Diag.now() - t0) + "ms");
    }

    /** Rewrites PT_INTERP of every 64-bit ELF so the kernel can resolve it on the host. */
    private int patchInterp(File dir, String interp) {
        File[] files = dir.listFiles();
        if (files == null) return 0;
        int n = 0;
        for (File f : files) {
            if (f.isFile() && patchOne(f, interp)) n++;
        }
        return n;
    }

    private boolean patchOne(File f, String interp) {
        byte[] want = (interp + "\u0000").getBytes(StandardCharsets.UTF_8);
        try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
            if (raf.length() < 64) return false;
            byte[] hdr = new byte[64];
            raf.readFully(hdr);
            if (hdr[0] != 0x7f || hdr[1] != 'E' || hdr[2] != 'L' || hdr[3] != 'F') return false;
            if (hdr[4] != 2) return false;

            long phoff = u64(hdr, 0x20);
            int phentsize = u16(hdr, 0x36);
            int phnum = u16(hdr, 0x38);
            if (phentsize < 56 || phnum <= 0 || phnum > 512) return false;

            byte[] ph = new byte[phentsize * phnum];
            raf.seek(phoff);
            raf.readFully(ph);
            for (int i = 0; i < phnum; i++) {
                int off = i * phentsize;
                int type = (int) u32(ph, off);
                if (type != 3) continue;
                long pOffset = u64(ph, off + 8);
                long pFilesz = u64(ph, off + 32);
                if (pFilesz < want.length) return false;
                byte[] data = new byte[(int) pFilesz];
                raf.seek(pOffset);
                raf.readFully(data);
                String cur = new String(data, 0, (int) Math.max(0, pFilesz - 1), StandardCharsets.UTF_8);
                int z = cur.indexOf(0);
                cur = z >= 0 ? cur.substring(0, z) : cur;
                if (cur.equals(interp)) return false;
                byte[] out = new byte[(int) pFilesz];
                System.arraycopy(want, 0, out, 0, want.length);
                raf.seek(pOffset);
                raf.write(out);
                return true;
            }
        } catch (Exception e) {
            Diag.log("patch fail " + f.getName() + ": " + e);
        }
        return false;
    }

    private static long u64(byte[] b, int o) {
        long v = 0;
        for (int i = 7; i >= 0; i--) v = (v << 8) | (b[o + i] & 0xffL);
        return v;
    }

    private static long u32(byte[] b, int o) {
        return (b[o] & 0xffL) | ((b[o + 1] & 0xffL) << 8) | ((b[o + 2] & 0xffL) << 16) | ((b[o + 3] & 0xffL) << 24);
    }

    private static int u16(byte[] b, int o) {
        return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8);
    }

    private void chmod(File f) {
        if (!f.exists()) return;
        try {
            Os.chmod(f.getAbsolutePath(), 0755);
        } catch (Exception e) {
            f.setExecutable(true, false);
            f.setReadable(true, false);
        }
    }

    /**
     * Writes /opt/vstenv/run.sh. Called on every engine start, not just on extraction, so an
     * environment that is already on disk picks up launcher changes without a 1.6 GB re-extract.
     */
    void writeRunScript() throws IOException {
        File dir = new File(glibc, "opt/vstenv");
        dir.mkdirs();
        ensureFontconfig();
        StringBuilder sb = new StringBuilder();
        sb.append("#!/bin/bash\n");
        // The bundled libfontconfig was built with a Termux sysconfdir
        // (/data/data/com.termux/files/usr/glibc/etc/fonts) that does not exist here, so it never
        // finds its default config and logs "Cannot load default config file" five times per boot.
        // Point it at the config the rootfs actually ships.
        sb.append("export FONTCONFIG_FILE=/etc/fonts/fonts.conf\n");
        sb.append("export FONTCONFIG_PATH=/etc/fonts\n");
        sb.append("export PATH=/bin:/usr/bin:/wine-9.3-vanilla-wow64/bin\n");
        sb.append("export WINEPREFIX=/opt/vstenv/wineprefix\n");
        sb.append("export WINEDEBUG=-all\n");
        sb.append("export WINEDLLOVERRIDES='mscoree,mshtml='\n");
        sb.append("export LD_LIBRARY_PATH=/lib:/usr/lib"
                + ":/wine-9.3-vanilla-wow64/lib/wine/x86_64-unix:/wine-9.3-vanilla-wow64/lib\n");
        sb.append("export BOX64_DYNAREC_BIGBLOCK=3\n");
        sb.append("export BOX64_DYNAREC_SAFEFLAGS=0\n");
        sb.append("export BOX64_DYNAREC_CALLRET=1\n");
        sb.append("export BOX64_DYNAREC_FASTNAN=1\n");
        sb.append("export BOX64_DYNAREC_FASTROUND=1\n");
        sb.append("cd /opt/vstenv/payload || exit 3\n");
        sb.append("exec /bin/box64 /wine-9.3-vanilla-wow64/bin/wine ");
        sb.append("'/opt/vstenv/payload/vstrender_juce.exe' ");
        sb.append("-plugin 'Z:\\opt\\vstenv\\payload\\dearVR PRO 2.vst3' -serve\n");
        try (FileOutputStream os = new FileOutputStream(new File(dir, "run.sh"))) {
            os.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
        chmod(new File(dir, "run.sh"));
    }

    /** Minimal config for rootfs builds whose tar happens to omit /etc/fonts. */
    private void ensureFontconfig() {
        File conf = new File(glibc, "etc/fonts/fonts.conf");
        if (conf.isFile()) return;
        File dir = conf.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return;
        String xml = "<?xml version=\"1.0\"?>\n"
                + "<!DOCTYPE fontconfig SYSTEM \"urn:fontconfig:fonts.dtd\">\n"
                + "<fontconfig>\n"
                + "  <cachedir>/opt/vstenv/fontcache</cachedir>\n"
                + "  <dir>/usr/share/fonts</dir>\n"
                + "  <dir>/opt/vstenv/fonts</dir>\n"
                + "</fontconfig>\n";
        try (FileOutputStream os = new FileOutputStream(conf)) {
            os.write(xml.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Diag.log("fontconfig: " + e);
        }
    }

    private void copyAsset(String name, File dest) throws IOException {
        dest.getParentFile().mkdirs();
        // A guest process that has not fully exited keeps the old binary mapped, and the
        // write then fails with ETXTBSY. Retry briefly instead of failing the whole boot.
        for (int attempt = 0; ; attempt++) {
            try {
                copyAssetOnce(name, dest);
                return;
            } catch (IOException e) {
                String msg = String.valueOf(e.getMessage());
                if (attempt >= 6 || !msg.contains("ETXTBSY") && !msg.contains("Text file busy")) throw e;
                Diag.log("copyAsset " + name + " busy, retry " + (attempt + 1));
                try {
                    Thread.sleep(250);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    private void copyAssetOnce(String name, File dest) throws IOException {
        // Replace via rename: overwriting a binary that a dying guest still has mapped fails
        // with ETXTBSY, while rename(2) is always allowed and is atomic for readers.
        File tmp = new File(dest.getParentFile(), dest.getName() + ".new");
        try (InputStream in = ctx.getAssets().open(name);
             OutputStream out = new FileOutputStream(tmp)) {
            Io.copy(in, out, new byte[Io.BUF]);
        }
        if (!tmp.renameTo(dest)) {
            // rename cannot cross devices and can fail on some FUSE back-ends; fall back.
            try (InputStream in = ctx.getAssets().open(name);
                 OutputStream out = new FileOutputStream(dest)) {
                Io.copy(in, out, new byte[Io.BUF]);
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    private void extractTar(String assetName, boolean xz, File destRoot,
                            int from, int to, Progress p) throws Exception {
        // Fast path: when the split assets (<stem>_<i>of<K>.bin) are present,
        // decode/write them in parallel. Falls back to the single-stream path
        // below when they are absent, incomplete or inconsistent.
        if (FastExtract.segmentCount(ctx, assetName) >= 2) {
            FastExtract.extract(ctx, assetName, xz, destRoot, from, to, p);
            return;
        }
        long total = assetSize(assetName);
        long done = 0;
        int lastPct = -1;
        try (InputStream raw = ctx.getAssets().open(assetName);
             BufferedInputStream in = new BufferedInputStream(raw, 1 << 16);
             InputStream dec = xz ? new XZInputStream(in) : new GZIPInputStream(in, 1 << 16);
             TarArchiveInputStream tar = new TarArchiveInputStream(dec)) {
            TarArchiveEntry e;
            byte[] buf = new byte[1 << 16];
            String base = destRoot.getCanonicalPath();
            while ((e = tar.getNextEntry()) != null) {
                File out = new File(destRoot, e.getName());
                String canon = out.getCanonicalPath();
                if (!canon.equals(base) && !canon.startsWith(base + File.separator)) continue;

                if (e.isDirectory()) {
                    out.mkdirs();
                } else if (e.isSymbolicLink()) {
                    out.getParentFile().mkdirs();
                    if (out.exists()) out.delete();
                    try {
                        Os.symlink(e.getLinkName(), out.getAbsolutePath());
                    } catch (Exception ignored) {
                        File target = new File(destRoot, e.getLinkName());
                        if (target.isFile() && target.canRead()) {
                            try (InputStream ti = new java.io.FileInputStream(target);
                                 OutputStream os2 = new FileOutputStream(out)) {
                                Io.copy(ti, os2, new byte[Io.BUF]);
                            }
                        }
                    }
                } else if (e.isLink()) {
                    out.getParentFile().mkdirs();
                    File target = new File(destRoot, e.getLinkName());
                    if (out.exists()) out.delete();
                    try {
                        Os.link(target.getAbsolutePath(), out.getAbsolutePath());
                    } catch (Exception ex) {
                        if (target.isFile()) {
                            try (InputStream ti = new java.io.FileInputStream(target);
                                 OutputStream os2 = new FileOutputStream(out)) {
                                Io.copy(ti, os2, new byte[Io.BUF]);
                            }
                        }
                    }
                } else if (e.isFile()) {
                    out.getParentFile().mkdirs();
                    try (OutputStream os = new FileOutputStream(out)) {
                        Io.copy(tar, os, buf);
                    }
                    if ((e.getMode() & 0111) != 0) {
                        try {
                            Os.chmod(out.getAbsolutePath(), 0755);
                        } catch (Exception ignored) {
                        }
                    }
                    done += e.getSize();
                    if (total > 0) {
                        int pct = from + (int) ((to - from) * Math.min(1.0, (double) done / total));
                        if (pct != lastPct) {
                            lastPct = pct;
                            p.onStage(null, pct);
                        }
                    }
                }
            }
        }
    }

    private long assetSize(String name) {
        try {
            android.content.res.AssetFileDescriptor fd = ctx.getAssets().openFd(name);
            long len = fd.getLength();
            fd.close();
            if (len > 0) return len;
        } catch (Exception ignored) {
        }
        try (InputStream in = ctx.getAssets().open(name)) {
            return Math.max(0, in.available());
        } catch (Exception e) {
            return 0;
        }
    }

    /** Whole-file copy used only when the hard link is not possible (different mount). */
    private static boolean copyFile(File from, File to) {
        byte[] buf = new byte[256 * 1024];
        try (InputStream in = new FileInputStream(from);
             OutputStream out = new FileOutputStream(to)) {
            Io.copy(in, out, buf);
        } catch (Exception e) {
            Diag.log("copyFile " + from.getName() + " failed: " + e);
            return false;
        }
        return to.length() == from.length();
    }
}
