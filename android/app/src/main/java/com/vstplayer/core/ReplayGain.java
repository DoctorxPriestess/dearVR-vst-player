package com.vstplayer.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ReplayGain for the library: EBU R128 scanning, tag writing and the playback gain.
 *
 * <p>Three sources of truth, cheapest first: the in-memory cache, the {@link RgStore} JSON, and the
 * file's own tags. A file that already carries REPLAYGAIN_TRACK_GAIN is never scanned again — its
 * tag wins, which is what other players expect too.
 */
public final class ReplayGain {

    public static final double DEFAULT_TARGET = Loudness.DEFAULT_TARGET_LUFS;
    private static final String PREF = "vstp";
    private static final String K_TARGET = "rgTarget";
    private static final String K_APPLY = "rgApply";
    private static final String K_WRITE = "rgWrite";
    private static final String K_MODE = "rgMode";

    private ReplayGain() { }

    // ------------------------------------------------------------------ settings

    public static double targetLufs(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        float def = (float) DEFAULT_TARGET;
        return p.getFloat(K_TARGET, def);
    }

    public static void setTargetLufs(Context c, double v) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putFloat(K_TARGET, (float) v).apply();
        cache.clear();
    }

    public static boolean applyEnabled(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(K_APPLY, true);
    }

    public static void setApplyEnabled(Context c, boolean on) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(K_APPLY, on).apply();
    }

    public static boolean writeTags(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(K_WRITE, true);
    }

    public static void setWriteTags(Context c, boolean on) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(K_WRITE, on).apply();
    }

    /** Last scan mode the user picked (track / album / everything as one album). */
    public static Mode mode(Context c) {
        String s = c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(K_MODE, Mode.TRACK.name());
        try {
            return s == null ? Mode.TRACK : Mode.valueOf(s);
        } catch (IllegalArgumentException e) {
            return Mode.TRACK;
        }
    }

    public static void setMode(Context c, Mode m) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putString(K_MODE, (m == null ? Mode.TRACK : m).name()).apply();
    }

    // ------------------------------------------------------------------ storage

    private static final Map<String, Integer> cache = new HashMap<>();
    private static RgStore store;

    public static synchronized RgStore store(Context c) {
        if (store == null) {
            store = new RgStore(new File(c.getFilesDir(), "replaygain.json"));
            store.load();
        }
        return store;
    }

    /** Cache key: path relative to the guest work folder, so it survives a reinstall of the payload. */
    public static String keyOf(File f) {
        if (f == null) return "";
        File work = null;
        File in = Vst.get().inDir();
        File out = Vst.get().outDir();
        if (in != null) work = in.getParentFile();
        String rel = Paths.relTo(work, f);
        if (rel == null) rel = Paths.relTo(out, f);
        if (rel == null) rel = Paths.relTo(in, f);
        return rel == null ? f.getAbsolutePath() : rel;
    }

    /**
     * Track gain in dB for playback, or null when the file has none.
     *
     * <p>Reads the file tag only once per file: a miss is cached as "no gain" so the player does not
     * reopen the container on every track start.
     */
    public static Integer gainDb(Context c, File f) {
        if (c == null || f == null) return null;
        String key = keyOf(f);
        synchronized (cache) {
            if (cache.containsKey(key)) return cache.get(key);
        }
        Integer gain = null;
        try {
            RgStore.Entry e = store(c).get(key);
            if (e != null && e.size == f.length() && e.mtime == f.lastModified()) {
                gain = e.gainDb;
            } else {
                Map<String, String> tags = RgTags.read(f);
                gain = parseGainDb(tags.get(RgTags.TRACK_GAIN));
            }
        } catch (Exception ex) {
            Diag.log("replaygain lookup failed for " + f.getName() + ": " + ex);
        }
        synchronized (cache) {
            cache.put(key, gain);
        }
        return gain;
    }

    private static Integer parseGainDb(String tag) {
        if (tag == null) return null;
        String s = tag.trim().toLowerCase(java.util.Locale.US);
        if (s.endsWith("db")) s = s.substring(0, s.length() - 2).trim();
        try {
            return Math.round(Float.parseFloat(s));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Applies the track gain to a prepared MediaPlayer; silently does nothing when disabled. */
    public static void applyTo(Context c, MediaPlayer m, File f) {
        if (m == null || f == null || c == null) return;
        if (!applyEnabled(c)) return;
        Integer db = gainDb(c, f);
        float vol = 1f;
        if (db != null) {
            double lin = Math.pow(10.0, db / 20.0);
            vol = (float) Math.max(0.0, Math.min(1.0, lin));   // no headroom beyond full scale
        }
        try {
            m.setVolume(vol, vol);
        } catch (IllegalStateException ignored) {
        }
    }

    /** Drops cached gains after a scan so the next lookup sees the fresh values. */
    public static void invalidate() {
        synchronized (cache) {
            cache.clear();
        }
        synchronized (ReplayGain.class) {
            if (store != null) store.load();
        }
    }

    // ------------------------------------------------------------------ scanning

    public interface Progress {
        /** @param file the file that just finished, or null while a file is being decoded. */
        void onFile(File file, int done, int total, String status);
    }

    public interface Cancel {
        boolean cancelled();
    }

    public static final class Summary {
        public int total, scanned, tagged, cached, skipped, failed;
        /** Number of album groups processed (0 for a plain TRACK scan). */
        public int groups;
        public long startedAt, endedAt;

        public String text() {
            StringBuilder sb = new StringBuilder();
            sb.append("共 ").append(total).append(" 个：扫描 ").append(scanned)
                    .append(" · 已有标签 ").append(tagged).append(" · 已缓存 ").append(cached);
            if (skipped > 0) sb.append(" · 跳过 ").append(skipped);
            if (failed > 0) sb.append(" · 失败 ").append(failed);
            if (groups > 0) sb.append(" · 专辑 ").append(groups).append(" 组");
            sb.append(" · 用时 ").append((endedAt - startedAt) / 1000).append(" 秒");
            return sb.toString();
        }
    }

    /** Scan modes: one gain per file, or one shared album gain for groups of files. */
    public enum Mode {
        /** Every file on its own; writes REPLAYGAIN_TRACK_*. */
        TRACK,
        /** One gain per AlbumArtist+Album group; writes TRACK_* and ALBUM_* per file. */
        ALBUM,
        /** The whole list is treated as a single album; writes the same ALBUM_* to every file. */
        ALL_AS_ONE_ALBUM
    }

    /**
     * Backward compatible entry point: scans every file on its own ({@link Mode#TRACK}).
     *
     * <p>Decoding reuses {@link Decode}, i.e. the same platform decoder the renderer uses; one file
     * at a time, so the scan never competes with a render batch for the codec service.
     */
    public static Summary scan(Context app, List<File> files, double targetLufs, boolean writeTags,
                               Progress progress, Cancel cancel) {
        return scan(app, files, targetLufs, Mode.TRACK, writeTags, progress, cancel);
    }

    /**
     * Scans these files with the given mode and stores the result; WAV, FLAC and MP4/M4A also get
     * REPLAYGAIN_* tags (other containers land in the JSON cache only).
     *
     * <p>TRACK measures each file on its own. ALBUM groups the list by album artist + album and
     * measures the concatenated audio of each group once (BS.1770-4 gating over the whole group, not
     * an average of the per track values); every file of the group gets both its own TRACK_* and the
     * shared ALBUM_* values. ALL_AS_ONE_ALBUM does the same with the entire list as one group.
     *
     * <p>Album groups are always measured from the audio: an existing TRACK_GAIN tag or a warm cache
     * is not enough, because the album value depends on the whole group.
     */
    public static Summary scan(Context app, List<File> files, double targetLufs, Mode mode,
                               boolean writeTags, Progress progress, Cancel cancel) {
        Mode m = mode == null ? Mode.TRACK : mode;
        if (m == Mode.TRACK) return scanTracks(app, files, targetLufs, writeTags, progress, cancel);
        return scanAlbums(app, files, targetLufs, m, writeTags, progress, cancel);
    }

    private static Summary scanTracks(Context app, List<File> files, double targetLufs,
                                      boolean writeTags, Progress progress, Cancel cancel) {
        Summary sum = new Summary();
        sum.total = files == null ? 0 : files.size();
        sum.startedAt = System.currentTimeMillis();
        if (files == null) return sum;
        File tmpDir = new File(app.getCacheDir(), "rg");
        tmpDir.mkdirs();
        RgStore st = store(app);
        for (int i = 0; i < files.size(); i++) {
            File f = files.get(i);
            if (cancel != null && cancel.cancelled()) break;
            if (f == null || !f.isFile()) { sum.skipped++; continue; }
            String key = keyOf(f);
            File temp = null;
            try {
                Map<String, String> tags = RgTags.read(f);
                if (RgTags.hasReplayGain(tags)) {
                    Integer db = parseGainDb(tags.get(RgTags.TRACK_GAIN));
                    st.put(key, f.length(), f.lastModified(), Double.NaN, Double.NaN,
                            db == null ? 0 : db, targetLufs);
                    sum.tagged++;
                    if (progress != null) progress.onFile(f, i + 1, sum.total, "已有标签");
                    continue;
                }
                if (st.fresh(key, f.length(), f.lastModified())) {
                    sum.cached++;
                    if (progress != null) progress.onFile(f, i + 1, sum.total, "已缓存");
                    continue;
                }
                File pcm = f;
                if (!Segment.isWav(f)) {
                    temp = new File(tmpDir, "rg" + i + "_" + System.nanoTime() + ".wav");
                    if (progress != null) progress.onFile(f, i, sum.total, "解码");
                    Decode.Result r = Decode.decode(app, f, temp, null);
                    if (r.wav == null) {
                        sum.failed++;
                        Diag.log("rg: decode failed for " + f.getName() + ": " + r.error);
                        if (progress != null) progress.onFile(f, i + 1, sum.total, "解码失败");
                        continue;
                    }
                    pcm = r.wav;
                }
                Loudness.Result res = Loudness.analyzeWav(pcm);
                if (res.silent()) {
                    sum.skipped++;
                    if (progress != null) progress.onFile(f, i + 1, sum.total, "无声");
                    continue;
                }
                int gain = res.gainDb(targetLufs);
                if (writeTags) {
                    Map<String, String> out = new java.util.LinkedHashMap<>();
                    out.put(RgTags.TRACK_GAIN, String.format(java.util.Locale.US, "%+.2f dB", (double) gain));
                    out.put(RgTags.TRACK_PEAK, String.format(java.util.Locale.US, "%.6f",
                            Math.pow(10.0, res.peakDbfs / 20.0)));
                    try {
                        writeContainer(f, out);
                    } catch (Exception e) {
                        Diag.log("rg: tag write failed for " + f.getName() + ": " + e);
                    }
                }
                st.put(key, f.length(), f.lastModified(), res.lufs, res.peakDbfs, gain, targetLufs);
                sum.scanned++;
                if (progress != null) {
                    progress.onFile(f, i + 1, sum.total,
                            String.format(java.util.Locale.US, "%.1f LUFS → %+d dB", res.lufs, gain));
                }
                // Throttled: the store decides (100 entries or 30 s), the final save() below is
                // forced so nothing measured is lost at the end of the scan.
                if (sum.scanned % 5 == 0) st.saveIfDue();
            } catch (Exception e) {
                sum.failed++;
                Diag.log("rg: " + f.getName() + " failed: " + e);
                if (progress != null) progress.onFile(f, i + 1, sum.total, "失败");
            } finally {
                // Only this track's staging file. Deleting the whole directory also removed the
                // decoded WAV of a concurrent reference scan (it runs on its own thread) and made
                // that analysis fail halfway.
                if (temp != null) temp.delete();
            }
        }
        st.save();
        invalidate();
        sum.endedAt = System.currentTimeMillis();
        Diag.log("rg scan: " + sum.text());
        return sum;
    }

    // ------------------------------------------------------------------ album scanning

    private static Summary scanAlbums(Context app, List<File> files, double targetLufs, Mode mode,
                                      boolean writeTags, Progress progress, Cancel cancel) {
        Summary sum = new Summary();
        sum.total = files == null ? 0 : files.size();
        sum.startedAt = System.currentTimeMillis();
        if (files == null) return sum;

        Map<String, List<File>> groups = group(files, mode, sum);
        sum.groups = groups.size();
        File tmpDir = new File(app.getCacheDir(), "rg");
        tmpDir.mkdirs();
        RgStore st = store(app);
        int base = 0;
        for (List<File> group : groups.values()) {
            if (cancel != null && cancel.cancelled()) break;
            if (!scanAlbum(app, group, targetLufs, writeTags, progress, cancel, st, sum, tmpDir, base)) {
                break;
            }
            base += group.size();
            st.saveIfDue();
        }
        st.save();
        invalidate();
        sum.endedAt = System.currentTimeMillis();
        Diag.log("rg scan: " + sum.text());
        return sum;
    }

    /**
     * Group files for album measuring. ALBUM uses {@link RgTags#albumKey(String, String)}: album
     * artist + album, falling back to the album alone when the album artist is empty and to one
     * shared "unknown album" group when both tags are missing. ALL_AS_ONE_ALBUM puts everything
     * into one group. Invalid entries are counted as skipped here.
     */
    private static Map<String, List<File>> group(List<File> files, Mode mode, Summary sum) {
        Map<String, List<File>> groups = new LinkedHashMap<>();
        for (int i = 0; i < files.size(); i++) {
            File f = files.get(i);
            if (f == null || !f.isFile()) {
                sum.skipped++;
                continue;
            }
            String key;
            if (mode == Mode.ALL_AS_ONE_ALBUM) {
                key = "\u0000all-as-one-album";
            } else {
                Tags.Info info = Tags.read(f);
                key = RgTags.albumKey(info.albumArtist, info.album);
            }
            List<File> g = groups.get(key);
            if (g == null) {
                g = new ArrayList<File>();
                groups.put(key, g);
            }
            g.add(f);
        }
        return groups;
    }

    /**
     * Measure one album group and write its tags.
     *
     * <p>Per file: decode to WAV if needed, run the single file analyser for the TRACK_* values,
     * then feed the same PCM into the accumulating {@link Loudness.AlbumAnalyzer}. Only the small
     * per track results are kept between files; the decoded audio is released immediately.
     *
     * @return false when the scan was cancelled and the caller must stop
     */
    private static boolean scanAlbum(Context app, List<File> group, double targetLufs,
                                     boolean writeTags, Progress progress, Cancel cancel,
                                     RgStore st, Summary sum, File tmpDir, int base) {
        Loudness.AlbumAnalyzer album = new Loudness.AlbumAnalyzer();
        Loudness.Result[] tracks = new Loudness.Result[group.size()];
        for (int i = 0; i < group.size(); i++) {
            File f = group.get(i);
            if (cancel != null && cancel.cancelled()) return false;
            File temp = null;
            try {
                File pcm = f;
                if (!Segment.isWav(f)) {
                    temp = new File(tmpDir, "rg" + i + "_" + System.nanoTime() + ".wav");
                    if (progress != null) progress.onFile(f, base + i, sum.total, "解码");
                    Decode.Result r = Decode.decode(app, f, temp, null);
                    if (r.wav == null) {
                        sum.failed++;
                        Diag.log("rg: decode failed for " + f.getName() + ": " + r.error);
                        if (progress != null) progress.onFile(f, base + i + 1, sum.total, "解码失败");
                        continue;
                    }
                    pcm = r.wav;
                }
                Loudness.Result tr = Loudness.analyzeWav(pcm, loudnessHook(cancel));
                tracks[i] = tr;
                album.add(pcm, loudnessHook(cancel));
                if (album.cancelled()) return false;
                if (progress != null) {
                    progress.onFile(f, base + i + 1, sum.total,
                            String.format(java.util.Locale.US, "曲目 %.1f LUFS", tr.lufs));
                }
            } catch (Exception e) {
                sum.failed++;
                Diag.log("rg: album track " + f.getName() + " failed: " + e);
                if (progress != null) progress.onFile(f, base + i + 1, sum.total, "失败");
            } finally {
                if (temp != null) temp.delete();
            }
        }
        if (album.cancelled()) return false;

        Loudness.Result ar = album.finish();
        if (ar.silent()) {
            for (int i = 0; i < group.size(); i++) {
                if (tracks[i] != null && tracks[i].silent()) sum.skipped++;
            }
            if (progress != null) progress.onFile(null, base + group.size(), sum.total, "无声");
            return true;
        }
        final int albumGain = ar.gainDb(targetLufs);
        final String albumGainTag = String.format(java.util.Locale.US, "%+.2f dB", (double) albumGain);
        final String albumPeak = peakTag(ar.peakDbfs);
        for (int i = 0; i < group.size(); i++) {
            Loudness.Result tr = tracks[i];
            if (tr == null) continue;
            File f = group.get(i);
            if (cancel != null && cancel.cancelled()) return false;
            if (tr.silent()) {
                sum.skipped++;
                continue;
            }
            final int gain = tr.gainDb(targetLufs);
            Map<String, String> out = new LinkedHashMap<>();
            out.put(RgTags.TRACK_GAIN, String.format(java.util.Locale.US, "%+.2f dB", (double) gain));
            out.put(RgTags.TRACK_PEAK, peakTag(tr.peakDbfs));
            out.put(RgTags.ALBUM_GAIN, albumGainTag);
            out.put(RgTags.ALBUM_PEAK, albumPeak);
            if (writeTags) {
                try {
                    writeContainer(f, out);
                } catch (Exception e) {
                    Diag.log("rg: tag write failed for " + f.getName() + ": " + e);
                }
            }
            st.put(keyOf(f), f.length(), f.lastModified(), tr.lufs, tr.peakDbfs, gain, targetLufs);
            sum.scanned++;
            if (progress != null) {
                progress.onFile(f, base + i + 1, sum.total,
                        String.format(java.util.Locale.US,
                                "%.1f LUFS → %+d dB · 专辑 %.1f LUFS → %+d dB",
                                tr.lufs, gain, ar.lufs, albumGain));
            }
        }
        return true;
    }

    /** Linear peak for a ReplayGain tag; -inf dBFS (no sample) becomes 0. */
    private static String peakTag(double dbfs) {
        return String.format(java.util.Locale.US, "%.6f", Math.pow(10.0, dbfs / 20.0));
    }

    /** Loudness hook that only forwards cancellation; album progress is reported per file. */
    private static Loudness.Progress loudnessHook(final Cancel cancel) {
        return new Loudness.Progress() {
            @Override
            public void onPercent(int pct) {
            }

            @Override
            public boolean cancelled() {
                return cancel != null && cancel.cancelled();
            }
        };
    }

    /** Tag writer per container; MP4/M4A gets foobar2000 style free form atoms. */
    private static void writeContainer(File f, Map<String, String> tags) throws IOException {
        if (Segment.isFlac(f)) {
            RgTags.writeFlac(f, tags);
        } else if (Segment.isWav(f)) {
            RgTags.writeWav(f, tags);
        } else if (RgTags.isMp4(f)) {
            RgTags.writeMp4(f, tags);
        } else {
            throw new IOException("no ReplayGain tag writer for this container: " + f.getName());
        }
    }

    // ------------------------------------------------------------------ references

    /**
     * ReplayGain for library references (SAF documents).
     *
     * <p>A document cannot be decoded or rewritten in place: every track is streamed into a staging
     * copy under the app cache, scanned/written there, and — when tags were written — copied back
     * over the document (truncating rewrite). The staging copy is always deleted, and a track that
     * the scan skipped (already tagged) is never copied back.
     */
    public static Summary scanReferences(Context app, List<LibraryRef.Track> tracks, double targetLufs,
                                         Mode mode, boolean writeTags, Progress progress, Cancel cancel) {
        long t0 = System.currentTimeMillis();
        if (app == null || tracks == null || tracks.isEmpty()) {
            Summary s = new Summary();
            s.startedAt = t0;
            s.endedAt = t0;
            return s;
        }
        File stage = new File(app.getCacheDir(), "rgstage/" + Long.toHexString(System.nanoTime()));
        if (!stage.isDirectory() && !stage.mkdirs()) {
            Diag.log("rg stage dir not creatable: " + stage);
            Summary s = new Summary();
            s.total = tracks.size();
            s.failed = tracks.size();
            s.startedAt = t0;
            s.endedAt = System.currentTimeMillis();
            return s;
        }
        final List<File> locals = new ArrayList<>();
        final List<int[]> map = new ArrayList<>();      // {trackIndex, localIndex or -1}
        final List<long[]> before = new ArrayList<>();  // {length, mtime} of the staged copy
        final List<String> names = new ArrayList<>();
        int doomed = 0;
        for (int i = 0; i < tracks.size(); i++) {
            LibraryRef.Track t = tracks.get(i);
            if (cancel != null && cancel.cancelled()) {
                doomed += tracks.size() - i;
                break;
            }
            String name = t.name() == null ? "track" : t.name();
            File dst = new File(stage, i + "_" + Paths.sanitize(name));
            try {
                copyDocument(app, Uri.parse(t.uri), dst);
                map.add(new int[]{i, locals.size()});
                locals.add(dst);
                before.add(new long[]{dst.length(), dst.lastModified()});
                names.add(name);
                if (progress != null) progress.onFile(new File(name), i, tracks.size(), "已载入");
            } catch (Throwable e) {
                Diag.log("rg materialise failed " + t.rel + ": " + e);
                map.add(new int[]{i, -1});
                if (progress != null) progress.onFile(new File(name), i + 1, tracks.size(), "无法读取");
            }
        }
        Summary inner = new Summary();
        try {
            if (!locals.isEmpty()) {
                inner = scan(app, locals, targetLufs, mode, writeTags,
                        (f, d, total, status) -> {
                            if (progress == null) return;
                            String label = f == null ? null : f.getName();
                            progress.onFile(label == null ? null : new File(label), d, total, status);
                        },
                        cancel);
            }
            Summary sum = inner;
            sum.total = tracks.size();
            sum.failed += map.stream().mapToInt(a -> a[1] < 0 ? 1 : 0).sum() + doomed;
            if (writeTags) sum.tagged = Math.max(sum.tagged, writeBack(app, tracks, map, locals, before));
            sum.startedAt = t0;
            sum.endedAt = System.currentTimeMillis();
            return sum;
        } finally {
            for (File f : locals) f.delete();
            deleteTree(stage);
        }
    }

    /** Mode aware async wrapper for {@link #scanReferences}. */
    public static void scanReferencesAsync(final Context app, final List<LibraryRef.Track> tracks,
                                           final double target, final Mode mode, final boolean writeTags,
                                           final Progress cb, final Cancel cancel, final Runnable done) {
        final Handler ui = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            final Summary sum = scanReferences(app, tracks, target, mode, writeTags,
                    (f, d, t, s) -> { if (cb != null) ui.post(() -> cb.onFile(f, d, t, s)); }, cancel);
            ui.post(() -> {
                if (cb != null) cb.onFile(null, sum.total, sum.total, sum.text());
                if (done != null) done.run();
            });
        }, "rg-scan-ref").start();
    }

    /** Copies every staged file whose bytes changed back over its document; returns how many. */
    private static int writeBack(Context app, List<LibraryRef.Track> tracks, List<int[]> map,
                                 List<File> locals, List<long[]> before) {
        int written = 0;
        for (int k = 0; k < map.size(); k++) {
            int[] pair = map.get(k);
            if (pair[1] < 0 || pair[1] >= locals.size()) continue;
            File local = locals.get(pair[1]);
            LibraryRef.Track t = tracks.get(pair[0]);
            if (!local.isFile() || local.length() == 0) continue;
            long[] was = pair[1] < before.size() ? before.get(pair[1]) : null;
            // The scan skipped this track (it already carried a gain tag): nothing was rewritten,
            // so the document must not be touched.
            if (was != null && local.length() == was[0] && local.lastModified() == was[1]) continue;
            Uri uri = Uri.parse(t.uri);
            try (java.io.InputStream in = new java.io.FileInputStream(local);
                 java.io.OutputStream out = app.getContentResolver().openOutputStream(uri, "rwt")) {
                if (out == null) throw new IOException("openOutputStream returned null");
                Io.copy(in, out, new byte[Io.BUF]);
                out.flush();
                written++;
                Diag.log("rg tags written back: " + t.rel + " (" + local.length() + "B)");
            } catch (Throwable e) {
                Diag.log("rg write back failed " + t.rel + ": " + e);
            }
        }
        return written;
    }

    private static void copyDocument(Context app, Uri uri, File dst) throws IOException {
        try (java.io.InputStream in = app.getContentResolver().openInputStream(uri);
             java.io.OutputStream out = new java.io.FileOutputStream(dst)) {
            if (in == null) throw new IOException("openInputStream returned null");
            Io.copy(in, out, new byte[Io.BUF]);
            out.flush();
        }
    }

    private static void deleteTree(File dir) {
        if (dir == null) return;
        File[] kids = dir.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        dir.delete();
    }

    /** Convenience for the UI: runs a scan off the UI thread and reports on the main thread. */
    public static void scanAsync(final Context app, final List<File> files, final double target,
                                 final boolean writeTags, final Progress cb, final Cancel cancel,
                                 final Runnable done) {
        scanAsync(app, files, target, Mode.TRACK, writeTags, cb, cancel, done);
    }

    /** Mode aware variant of {@link #scanAsync(Context, List, double, boolean, Progress, Cancel, Runnable)}. */
    public static void scanAsync(final Context app, final List<File> files, final double target,
                                 final Mode mode, final boolean writeTags, final Progress cb,
                                 final Cancel cancel, final Runnable done) {
        final Handler ui = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            final Summary sum = scan(app, files, target, mode, writeTags,
                    (f, d, t, s) -> { if (cb != null) ui.post(() -> cb.onFile(f, d, t, s)); }, cancel);
            ui.post(() -> {
                if (cb != null) cb.onFile(null, sum.total, sum.total, sum.text());
                if (done != null) done.run();
            });
        }, "rg-scan").start();
    }
}
