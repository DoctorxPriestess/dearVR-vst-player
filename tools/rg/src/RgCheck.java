package com.vstplayer.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * PC verification harness for Loudness / RgTags / RgStore.
 * Compiled with plain javac against the three core sources; not part of the Android build.
 */
public final class RgCheck {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: RgCheck <lufs|readtags|writetags|hasrg|floatreject|storetest|coeff> ...");
            System.exit(2);
        }
        String mode = args[0];
        if ("lufs".equals(mode)) lufs(args);
        else if ("readtags".equals(mode)) readTags(args);
        else if ("writetags".equals(mode)) writeTags(args);
        else if ("hasrg".equals(mode)) hasRg(args);
        else if ("floatreject".equals(mode)) floatReject(args);
        else if ("storetest".equals(mode)) storeTest(args);
        else if ("album".equals(mode)) album(args);
        else if ("writeheap".equals(mode)) writeHeap(args);
        else if ("albumkey".equals(mode)) albumKeys(args);
        else if ("coeff".equals(mode)) coefficients();
        else throw new IllegalArgumentException("unknown mode " + mode);
    }

    private static void lufs(String[] a) throws IOException {
        File f = new File(a[1]);
        boolean truePeak = false;
        boolean cancel = false;
        for (int i = 2; i < a.length; i++) {
            if ("--true".equals(a[i])) truePeak = true;
            if ("--cancel".equals(a[i])) cancel = true;
        }
        final boolean doCancel = cancel;
        final boolean[] stop = {cancel};
        Loudness.Progress p = new Loudness.Progress() {
            public void onPercent(int pct) {
                if (doCancel && pct >= 50) stop[0] = true;
            }

            public boolean cancelled() {
                return stop[0];
            }
        };
        Loudness.Result r = Loudness.analyzeWav(f, p, truePeak);
        System.out.println("RESULT lufs=" + d(r.lufs) + " peakDb=" + d(r.peakDbfs)
                + " trueDb=" + d(r.truePeakDbfs) + " frames=" + r.frames
                + " sec=" + String.format(Locale.US, "%.3f", r.seconds)
                + " ch=" + r.channels + " rate=" + r.sampleRate + " bits=" + r.bits
                + " cancelled=" + r.cancelled + " gainDb=" + r.gainDb(Loudness.DEFAULT_TARGET_LUFS));
    }

    private static void readTags(String[] a) throws IOException {
        File f = new File(a[1]);
        Map<String, String> m = RgTags.read(f);
        for (Map.Entry<String, String> e : new TreeMap<String, String>(m).entrySet()) {
            System.out.println("TAG " + e.getKey() + "=" + e.getValue());
        }
        System.out.println("HASRG " + RgTags.hasReplayGain(m));
    }

    private static void writeTags(String[] a) throws IOException {
        File f = new File(a[1]);
        Map<String, String> tags = new TreeMap<String, String>();
        for (int i = 2; i < a.length; i++) {
            int eq = a[i].indexOf('=');
            if (eq <= 0) throw new IllegalArgumentException("bad pair " + a[i]);
            tags.put(a[i].substring(0, eq), a[i].substring(eq + 1));
        }
        if (isFlac(f)) RgTags.writeFlac(f, tags);
        else if (RgTags.isMp4(f)) RgTags.writeMp4(f, tags);
        else RgTags.writeWav(f, tags);
        System.out.println("WROTE " + tags.size());
    }

    private static void hasRg(String[] a) throws IOException {
        File f = new File(a[1]);
        Map<String, String> m = RgTags.read(f);
        System.out.println("HASRG " + RgTags.hasReplayGain(m));
        for (Map.Entry<String, String> e : new TreeMap<String, String>(m).entrySet()) {
            System.out.println("TAG " + e.getKey() + "=" + e.getValue());
        }
    }

    private static void floatReject(String[] a) throws IOException {
        try {
            RgTags.writeWav(new File(a[1]), new TreeMap<String, String>());
            System.out.println("ACCEPTED");
        } catch (IOException e) {
            System.out.println("REJECTED " + e.getMessage());
        }
    }

    private static void storeTest(String[] a) throws IOException {
        File dir = new File(a[1]);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        File json = new File(dir, "rg-cache.json");
        if (json.exists() && !json.delete()) throw new IOException("cannot delete " + json);
        RgStore s = new RgStore(json);
        s.load();
        if (s.size() != 0) throw new IllegalStateException("new store not empty");
        s.put("music/a.flac", 100L, 200L, -14.2, -1.05, 4, -18.0);
        if (!s.fresh("music/a.flac", 100L, 200L)) throw new IllegalStateException("fresh miss");
        if (s.fresh("music/a.flac", 100L, 201L)) throw new IllegalStateException("mtime must invalidate");
        if (s.fresh("music/a.flac", 101L, 200L)) throw new IllegalStateException("size must invalidate");
        s.save();
        if (!json.isFile() || json.length() == 0) throw new IllegalStateException("save produced nothing");
        RgStore t = new RgStore(json);
        t.load();
        RgStore.Entry e = t.get("music/a.flac");
        if (e == null) throw new IllegalStateException("reload lost the entry");
        if (Math.abs(e.lufs + 14.2) > 1e-9 || Math.abs(e.peakDb + 1.05) > 1e-9
                || e.gainDb != 4 || Math.abs(e.target + 18.0) > 1e-9) {
            throw new IllegalStateException("reload values differ: " + e.lufs + " " + e.peakDb
                    + " " + e.gainDb + " " + e.target);
        }
        // a corrupt file must degrade to an empty cache, not throw
        FileOutputStream out = new FileOutputStream(json);
        out.write("{not json".getBytes("UTF-8"));
        out.close();
        RgStore u = new RgStore(json);
        u.load();
        if (u.size() != 0) throw new IllegalStateException("corrupt cache was partly accepted");
        System.out.println("STORETEST ok entries=" + t.size() + " bytes=" + json.length());
    }


    /**
     * Album mode: feed several WAV files into one accumulating analyser and report the result plus
     * the measured heap peak. A sampler thread polls the heap so the number covers the whole run.
     */
    private static void album(String[] a) throws IOException {
        java.util.List<File> files = new java.util.ArrayList<>();
        boolean cancel = false;
        for (int i = 1; i < a.length; i++) {
            if ("--cancel".equals(a[i])) {
                cancel = true;
                continue;
            }
            files.add(new File(a[i]));
        }
        final boolean doCancel = cancel;
        final boolean[] stop = {false};
        Loudness.Progress hook = new Loudness.Progress() {
            public void onPercent(int pct) {
                if (doCancel && pct >= 40) stop[0] = true;
            }

            public boolean cancelled() {
                return stop[0];
            }
        };
        final long[] peak = {0L};
        Thread sampler = new Thread(() -> {
            Runtime rt = Runtime.getRuntime();
            while (!Thread.currentThread().isInterrupted()) {
                long used = rt.totalMemory() - rt.freeMemory();
                if (used > peak[0]) peak[0] = used;
                try {
                    Thread.sleep(10L);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "heap-sampler");
        sampler.setDaemon(true);
        sampler.start();
        long t0 = System.nanoTime();
        Loudness.AlbumAnalyzer an = new Loudness.AlbumAnalyzer();
        for (File f : files) {
            an.add(f, hook);
            if (an.cancelled()) break;
        }
        Loudness.Result r = an.finish();
        long ms = (System.nanoTime() - t0) / 1000000L;
        sampler.interrupt();
        System.out.println("ALBUM lufs=" + d(r.lufs) + " peakDb=" + d(r.peakDbfs)
                + " frames=" + r.frames + " sec=" + String.format(Locale.US, "%.3f", r.seconds)
                + " files=" + an.files() + " rate=" + r.sampleRate + " ch=" + r.channels
                + " bits=" + r.bits + " cancelled=" + r.cancelled + " ms=" + ms
                + " heapPeak=" + peak[0]);
    }


    /** Tag write with a heap sampler, used to prove that a large container stays streaming. */
    private static void writeHeap(String[] a) throws IOException {
        File f = new File(a[1]);
        Map<String, String> tags = new TreeMap<String, String>();
        for (int i = 2; i < a.length; i++) {
            int eq = a[i].indexOf('=');
            if (eq <= 0) throw new IllegalArgumentException("bad pair " + a[i]);
            tags.put(a[i].substring(0, eq), a[i].substring(eq + 1));
        }
        long[] peak = {0L};
        Thread sampler = new Thread(() -> {
            Runtime rt = Runtime.getRuntime();
            while (!Thread.currentThread().isInterrupted()) {
                long used = rt.totalMemory() - rt.freeMemory();
                if (used > peak[0]) peak[0] = used;
                try {
                    Thread.sleep(5L);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "heap-sampler");
        sampler.setDaemon(true);
        sampler.start();
        if (isFlac(f)) RgTags.writeFlac(f, tags);
        else if (RgTags.isMp4(f)) RgTags.writeMp4(f, tags);
        else RgTags.writeWav(f, tags);
        sampler.interrupt();
        System.out.println("WROTE " + tags.size() + " heapPeak=" + peak[0]);
    }

    /** Album grouping keys for the pure helper; the NUL separator is shown as '|'. */
    private static void albumKeys(String[] a) {
        for (int i = 1; i < a.length; i++) {
            int bar = a[i].indexOf('|');
            String artist = bar < 0 ? null : a[i].substring(0, bar);
            String album = bar < 0 ? null : a[i].substring(bar + 1);
            String key = RgTags.albumKey(artist, album);
            boolean unknown = RgTags.UNKNOWN_ALBUM.equals(key);
            System.out.println("KEY " + a[i] + " => " + key.replace('\u0000', '|')
                    + " unknown=" + unknown);
        }
    }

    private static void coefficients() throws Exception {
        Method m = Loudness.class.getDeclaredMethod("kWeighting", int.class, double[].class,
                double[].class);
        m.setAccessible(true);
        int[] rates = {44100, 48000, 88200, 96000, 192000};
        for (int r : rates) {
            double[] s = new double[5];
            double[] h = new double[5];
            m.invoke(null, r, s, h);
            System.out.println("RATE " + r + " shelf=" + e(s[0]) + "," + e(s[1]) + "," + e(s[2])
                    + "," + e(s[3]) + "," + e(s[4]) + " hpf=" + e(h[0]) + "," + e(h[1]) + ","
                    + e(h[2]) + "," + e(h[3]) + "," + e(h[4]));
        }
    }

    private static boolean isFlac(File f) throws IOException {
        java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r");
        try {
            if (raf.length() < 4) return false;
            byte[] m = new byte[4];
            raf.readFully(m);
            return m[0] == 'f' && m[1] == 'L' && m[2] == 'a' && m[3] == 'C';
        } finally {
            raf.close();
        }
    }

    private static String e(double v) {
        return Double.toString(v);
    }

    private static String d(double v) {
        return Double.isNaN(v) ? "NaN" : String.format(Locale.US, "%.4f", v);
    }
}
