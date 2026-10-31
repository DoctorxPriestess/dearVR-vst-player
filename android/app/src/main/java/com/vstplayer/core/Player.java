package com.vstplayer.core;

import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Queue based player. The queue can be edited temporarily (reorder / remove);
 * {@link #restore()} brings back the original order.
 *
 * <p>A queue entry is a {@link PlaybackSource}: either an app-local file (rendered output, legacy
 * import) or a SAF reference to a file in the user's own storage. Both play through the same
 * {@link MediaPlayer}; the reference is handed to {@code setDataSource(Context, Uri, null)}.
 *
 * <p>The public {@code File} API is kept for the existing UI: a reference is represented by its
 * virtual path ({@link LibraryRef#virtualFile}), so track identity, titles and the queue list keep
 * working without any change in the detail page or the notification service.
 */
public class Player {

    public static final int MODE_SINGLE = 0, MODE_LIST = 1, MODE_SHUFFLE = 2;

    /**
     * One playable queue entry.
     *
     * <p>{@code file} is always set: the real file when {@code uri == null}, otherwise the virtual
     * path that identifies the reference inside the app.
     */
    public static final class PlaybackSource {

        public final File file;
        public final Uri uri;
        /** Stable identity: absolute path for a local file, virtual path for a reference. */
        public final String key;
        private final String label;

        private PlaybackSource(File file, Uri uri, String key, String label) {
            this.file = file;
            this.uri = uri;
            this.key = key == null ? (file == null ? "" : file.getAbsolutePath()) : key;
            this.label = label;
        }

        /** An app-local file. */
        public static PlaybackSource local(File f) {
            if (f == null) throw new IllegalArgumentException("null file");
            return new PlaybackSource(f, null, f.getAbsolutePath(), f.getName());
        }

        /**
         * A SAF reference.
         *
         * @param virtual the in-process identity of the track (see {@link LibraryRef#virtualFile})
         * @param uri     the document to play
         * @param label   display name, usually the file name inside the tree
         */
        public static PlaybackSource ref(File virtual, Uri uri, String label) {
            if (virtual == null) throw new IllegalArgumentException("null virtual path");
            if (uri == null) throw new IllegalArgumentException("null uri");
            return new PlaybackSource(virtual, uri, virtual.getAbsolutePath(), label);
        }

        /** True when this entry is a plain local file. */
        public boolean local() {
            return uri == null;
        }

        /** Display name (never null). */
        public String name() {
            if (label != null && !label.isEmpty()) return label;
            if (file != null) return file.getName();
            if (uri != null) return uri.getLastPathSegment();
            return "";
        }

        /** The resolvable absolute path (real file, or the virtual path of a reference). */
        public String path() {
            return file == null ? "" : file.getAbsolutePath();
        }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PlaybackSource)) return false;
            PlaybackSource other = (PlaybackSource) o;
            return key.equals(other.key);
        }

        @Override public int hashCode() {
            return key.hashCode();
        }

        @Override public String toString() {
            return name();
        }
    }

    public interface Listener {
        void onTrack(File f, int index, int total);
        void onState(boolean playing);
        void onProgress(float fraction, int posMs, int durMs);
        void onQueue();
        void onMode(int mode);
    }

    /**
     * Second, independent change feed used by {@link PlaybackService} to drive the media
     * notification and the MediaSession. It is a separate slot on purpose: {@link Listener} is
     * owned by the activity, which clears it on destroy.
     */
    public interface MediaHook {
        void onMediaChanged();
    }

    private static Player instance;

    public static synchronized Player get() {
        if (instance == null) instance = new Player();
        return instance;
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            MediaPlayer m = mp;
            if (m != null) {
                try {
                    int pos = m.getCurrentPosition(), dur = m.getDuration();
                    if (dur > 0 && listener != null) listener.onProgress(pos / (float) dur, pos, dur);
                } catch (IllegalStateException ignored) { }
            }
            if (playing) ui.postDelayed(this, 250);
        }
    };

    private final List<PlaybackSource> order = new ArrayList<>();
    private final List<PlaybackSource> pristine = new ArrayList<>();
    private final Random random = new Random();

    private MediaPlayer mp;
    private Listener listener;
    private int index = -1;
    private int mode = MODE_LIST;
    private String label = "";
    private boolean modified;
    private volatile boolean playing;
    private volatile MediaHook mediaHook;
    private android.content.Context appCtx;

    // ------------------------------------------------------------------ queue

    public void setListener(Listener l) { listener = l; }

    /** Registers the playback-service feed; independent of {@link #setListener}. */
    public void setMediaHook(MediaHook h) { mediaHook = h; }

    /** Application context used for the playback service and the audio wake lock. */
    public void attach(android.content.Context c) {
        if (c != null) appCtx = c.getApplicationContext();
    }

    /** Queue entries as plain files (references come back as their virtual path). */
    public List<File> queue() {
        List<File> out = new ArrayList<>(order.size());
        for (PlaybackSource s : order) if (s.file != null) out.add(s.file);
        return out;
    }

    /** The queue with its references intact (playlist / render hand-off). */
    public List<PlaybackSource> sources() {
        return new ArrayList<>(order);
    }

    public String label() { return label; }

    public int mode() { return mode; }

    public int index() { return index; }

    public boolean modified() { return modified; }

    public boolean isPlaying() { return playing; }

    /** Current entry as a file (the virtual path for a reference), or null. */
    public File track() {
        PlaybackSource s = source();
        return s == null ? null : s.file;
    }

    /** Current entry with its reference intact, or null. */
    public PlaybackSource source() {
        return index >= 0 && index < order.size() ? order.get(index) : null;
    }

    /** True while a prepared MediaPlayer exists (playing or paused), false after {@link #stop()}. */
    public boolean hasMedia() { return mp != null; }

    public int positionMs() {
        MediaPlayer m = mp;
        try { return m == null ? 0 : m.getCurrentPosition(); } catch (IllegalStateException e) { return 0; }
    }

    public int queueSize() { return order.size(); }

    public void playQueue(List<File> files, int start, String label) {
        List<PlaybackSource> srcs = new ArrayList<>();
        if (files != null) for (File f : files) if (f != null) srcs.add(PlaybackSource.local(f));
        playSources(srcs, start, label);
    }

    /** Plays a queue that may mix local files and SAF references. */
    public void playSources(List<PlaybackSource> srcs, int start, String label) {
        order.clear();
        pristine.clear();
        if (srcs != null) {
            order.addAll(srcs);
            pristine.addAll(srcs);
        }
        this.label = label == null ? "" : label;
        modified = false;
        playAt(start);
        notifyQueue();
    }

    public void play(File f) {
        play(PlaybackSource.local(f));
    }

    /** Plays exactly one entry (local file or reference). */
    public void play(PlaybackSource s) {
        List<PlaybackSource> one = new ArrayList<>();
        if (s != null) one.add(s);
        playSources(one, 0, "");
    }

    public void playAt(int i) {
        if (i < 0 || i >= order.size()) return;
        index = i;
        open(order.get(i));
    }

    public void playTrack(File f) {
        int i = indexOf(f);
        if (i >= 0) playAt(i);
        else if (f != null) play(f);
    }

    public void playSource(PlaybackSource s) {
        int i = s == null ? -1 : order.indexOf(s);
        if (i >= 0) playAt(i);
        else if (s != null) play(s);
    }

    /** Index of the queue entry that carries {@code f}, or -1. */
    private int indexOf(File f) {
        if (f == null) return -1;
        for (int i = 0; i < order.size(); i++) {
            File o = order.get(i).file;
            if (o != null && o.equals(f)) return i;
        }
        return -1;
    }

    /** Replaces the queue contents (temporary edit from the detail page). */
    public void setOrder(List<File> files, boolean markModified) {
        PlaybackSource current = source();
        List<PlaybackSource> next = new ArrayList<>();
        if (files != null) {
            for (File f : files) {
                if (f == null) continue;
                PlaybackSource keep = find(f);
                next.add(keep != null ? keep : PlaybackSource.local(f));
            }
        }
        order.clear();
        order.addAll(next);
        if (markModified) modified = true;
        int found = current == null ? -1 : order.indexOf(current);
        if (found >= 0) index = found;
        else if (index >= order.size()) index = order.size() - 1;
        notifyQueue();
        if (found < 0 && !order.isEmpty()) {
            PlaybackSource now = source();
            if (now != null) open(now);
        }
    }

    /** Keeps the reference of an entry that is currently in the queue. */
    private PlaybackSource find(File f) {
        for (PlaybackSource s : pristine) if (s.file != null && s.file.equals(f)) return s;
        for (PlaybackSource s : order) if (s.file != null && s.file.equals(f)) return s;
        return null;
    }

    public void restore() {
        if (pristine.isEmpty()) return;
        PlaybackSource current = source();
        order.clear();
        order.addAll(pristine);
        modified = false;
        int found = current == null ? -1 : order.indexOf(current);
        index = found >= 0 ? found : (index >= 0 && index < order.size() ? index : 0);
        notifyQueue();
        if (listener != null) ui.post(() -> listener.onTrack(track(), index, order.size()));
    }

    public void setMode(int m) {
        mode = m;
        if (listener != null) ui.post(() -> listener.onMode(m));
    }

    public void cycleMode() {
        setMode(mode == MODE_LIST ? MODE_SINGLE : mode == MODE_SINGLE ? MODE_SHUFFLE : MODE_LIST);
    }

    // ------------------------------------------------------------------ media

    private void open(PlaybackSource s) {
        // Only the media handle is recycled here: stopping the foreground service on every track
        // change would tear down / recreate the MediaSession and flash the notification.
        releaseMedia();
        if (s == null) return;
        MediaPlayer m = new MediaPlayer();
        try {
            // Keep the CPU awake while playing; the playback service keeps the process alive.
            if (appCtx != null) {
                m.setWakeMode(appCtx, android.os.PowerManager.PARTIAL_WAKE_LOCK);
            }
            // USAGE_MEDIA + CONTENT_TYPE_MUSIC is what makes the system (and vendor ROMs that
            // follow the output sample rate) treat this stream as music playback.
            m.setAudioAttributes(new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            if (s.local()) {
                m.setDataSource(s.file.getAbsolutePath());
            } else {
                if (appCtx == null) throw new IllegalStateException("no context for " + s.uri);
                // A SAF reference plays straight from the document: no copy, no staging file.
                m.setDataSource(appCtx, s.uri, null);
            }
            m.prepare();
        } catch (Exception e) {
            Diag.log("player open failed " + s.name() + ": " + e);
            m.release();
            if (listener != null) ui.post(() -> listener.onTrack(s.file, index, order.size()));
            fireMediaHook();
            return;
        }
        m.setOnCompletionListener(this::onComplete);
        // ReplayGain: a scanned track plays at the loudness it was normalised to. Clamped to 0 dB
        // so a positive gain can never clip the phone's output. A reference has no local tag to
        // read, so only local files are adjusted.
        if (s.local()) ReplayGain.applyTo(appCtx, m, s.file);
        m.start();
        mp = m;
        playing = true;
        PlaybackService.start(appCtx, s.name(), label.isEmpty() ? "VST Player" : label);
        if (listener != null) {
            ui.post(() -> {
                listener.onTrack(s.file, index, order.size());
                listener.onState(true);
            });
        }
        ui.post(tick);
        fireMediaHook();
    }

    private void onComplete(MediaPlayer m) {
        playing = false;
        if (listener != null) ui.post(() -> listener.onState(false));
        switch (mode) {
            case MODE_SINGLE:
                open(order.get(Math.max(0, Math.min(index, order.size() - 1))));
                break;
            case MODE_SHUFFLE:
                if (order.size() > 1) {
                    int n;
                    do { n = random.nextInt(order.size()); } while (n == index);
                    playAt(n);
                } else open(order.get(0));
                break;
            default:
                playAt((index + 1) % Math.max(1, order.size()));
                break;
        }
    }

    public void toggle() {
        MediaPlayer m = mp;
        if (m == null) {
            if (!order.isEmpty()) playAt(Math.max(0, index));
            return;
        }
        try {
            if (m.isPlaying()) {
                m.pause();
                playing = false;
                PlaybackService.pause(appCtx);
            } else {
                m.start();
                playing = true;
                ui.post(tick);
                PlaybackSource t = source();
                PlaybackService.start(appCtx, t == null ? null : t.name(),
                        label.isEmpty() ? "VST Player" : label);
            }
        } catch (IllegalStateException ignored) { }
        if (listener != null) ui.post(() -> listener.onState(playing));
        fireMediaHook();
    }

    public void next() {
        if (order.isEmpty()) return;
        if (mode == MODE_SHUFFLE && order.size() > 1) {
            int n;
            do { n = random.nextInt(order.size()); } while (n == index);
            playAt(n);
        } else {
            playAt((index + 1) % order.size());
        }
    }

    public void prev() {
        if (order.isEmpty()) return;
        playAt((index - 1 + order.size()) % order.size());
    }

    public void seek(float fraction) {
        MediaPlayer m = mp;
        if (m == null) return;
        try {
            m.seekTo((int) (m.getDuration() * Math.max(0, Math.min(1, fraction))));
        } catch (IllegalStateException ignored) { }
        fireMediaHook();
    }

    public void seekTo(int ms) {
        MediaPlayer m = mp;
        if (m == null) return;
        try { m.seekTo(Math.max(0, ms)); } catch (IllegalStateException ignored) { }
        fireMediaHook();
    }

    public int durationMs() {
        MediaPlayer m = mp;
        try { return m == null ? 0 : m.getDuration(); } catch (IllegalStateException e) { return 0; }
    }

    public void stop() {
        releaseMedia();
        PlaybackService.stop(appCtx);
        if (listener != null) ui.post(() -> listener.onState(false));
        fireMediaHook();
    }

    /** Drops the MediaPlayer only; the playback service / notification is left untouched. */
    private void releaseMedia() {
        playing = false;
        ui.removeCallbacks(tick);
        MediaPlayer m = mp;
        mp = null;
        if (m != null) {
            try { m.stop(); } catch (IllegalStateException ignored) { }
            m.release();
        }
    }

    private void fireMediaHook() {
        MediaHook h = mediaHook;
        if (h != null) ui.post(h::onMediaChanged);
    }

    private void notifyQueue() {
        if (listener != null) ui.post(listener::onQueue);
    }
}
