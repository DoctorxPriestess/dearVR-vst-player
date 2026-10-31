package com.vstplayer.core;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.MediaMetadataRetriever;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Keeps audio playback running while the app is in the background and publishes a standard
 * media surface: a framework {@link MediaSession} plus a {@link Notification.MediaStyle}
 * notification with previous / play-pause / next controls.
 *
 * Without a foreground service the system (especially vivo's power manager) freezes the package
 * as soon as the activity is not visible, which mutes or kills MediaPlayer. The service holds the
 * process foreground with a media-playback notification, so playback and a warm render engine
 * both survive backgrounding.
 *
 * Everything here is framework API only (no androidx). The service shares the process with the
 * activity, so it talks to the {@link Player} singleton directly.
 */
public class PlaybackService extends Service {
    private static final String CH = "playback";
    private static final int ID = 2;
    private static final String EXTRA_TITLE = "title";
    private static final String EXTRA_TEXT = "text";

    private static final String ACT_OPEN = "com.vstplayer.action.OPEN";
    private static final String ACT_PLAY = "com.vstplayer.action.PLAY";
    private static final String ACT_PAUSE = "com.vstplayer.action.PAUSE";
    private static final String ACT_TOGGLE = "com.vstplayer.action.TOGGLE";
    private static final String ACT_NEXT = "com.vstplayer.action.NEXT";
    private static final String ACT_PREV = "com.vstplayer.action.PREV";
    private static final String ACT_STOP = "com.vstplayer.action.STOP";

    /** Transport actions advertised on the session / notification. */
    private static final long ACTIONS = PlaybackState.ACTION_PLAY
            | PlaybackState.ACTION_PAUSE
            | PlaybackState.ACTION_PLAY_PAUSE
            | PlaybackState.ACTION_SKIP_TO_NEXT
            | PlaybackState.ACTION_SKIP_TO_PREVIOUS
            | PlaybackState.ACTION_STOP
            | PlaybackState.ACTION_SEEK_TO;

    private static final int COVER_MAX = 256;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Player.MediaHook hook = this::onMediaChanged;

    private MediaSession session;
    private Player player;
    private ExecutorService coverExec;

    /** Path whose embedded picture has already been probed (loaded or found missing). */
    private String coverFor;
    private Bitmap cover;

    private String fallbackTitle;
    private String fallbackText;
    private boolean stopping;
    /** Content signatures of the last published metadata / notification (skip no-op rebuilds). */
    private String lastMetaSig;
    private String lastNotifSig;

    // ---------------------------------------------------------------- lifecycle entry points

    /** Ensures the service runs and mirrors the player's current (playing) state. */
    public static void start(Context ctx, String title, String text) {
        if (ctx == null) return;
        Intent i = new Intent(ctx, PlaybackService.class);
        i.setAction(ACT_OPEN);
        i.putExtra(EXTRA_TITLE, title);
        i.putExtra(EXTRA_TEXT, text);
        try {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
            else ctx.startService(i);
        } catch (Exception e) {
            Diag.log("playback service start failed: " + e);
        }
    }

    /**
     * Keeps the service (and the MediaSession) alive in the paused state so the notification and
     * the system media panel can resume playback. Only {@link #stop(Context)} tears it down.
     */
    public static void pause(Context ctx) {
        send(ctx, ACT_PAUSE);
    }

    public static void stop(Context ctx) {
        if (ctx == null) return;
        try {
            ctx.stopService(new Intent(ctx, PlaybackService.class));
        } catch (Exception e) {
            Diag.log("playback service stop failed: " + e);
        }
    }

    private static void send(Context ctx, String action) {
        if (ctx == null) return;
        try {
            ctx.startService(new Intent(ctx, PlaybackService.class).setAction(action));
        } catch (Exception e) {
            // Background-start restriction when the service is not running: nothing to update.
            Diag.log("playback service action " + action + " failed: " + e);
        }
    }

    // ---------------------------------------------------------------- service

    @Override
    public void onCreate() {
        super.onCreate();
        player = Player.get();
        createChannel();
        coverExec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "vst-cover");
            t.setDaemon(true);
            return t;
        });
        initSession();
        player.setMediaHook(hook);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (player == null) player = Player.get();
        if (session == null) initSession();
        String action = intent == null ? null : intent.getAction();
        if (intent != null) {
            String t = intent.getStringExtra(EXTRA_TITLE);
            String x = intent.getStringExtra(EXTRA_TEXT);
            if (t != null && !t.isEmpty()) fallbackTitle = t;
            if (x != null && !x.isEmpty()) fallbackText = x;
        }
        if (ACT_STOP.equals(action)) {
            // Full stop: release the player, which also stops this service.
            if (player.hasMedia() || player.isPlaying()) player.stop();
            else shutdown();
            return START_NOT_STICKY;
        }
        if (!hasContent()) {
            // Nothing to advertise (stray intent after teardown): do not hold the process foreground.
            shutdown();
            return START_NOT_STICKY;
        }
        applyPlayerState();
        if (action != null) handle(action);
        if (!stopping && session != null) applyPlayerState();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        stopping = true;
        if (player != null) player.setMediaHook(null);
        if (coverExec != null) coverExec.shutdownNow();
        if (session != null) {
            try {
                session.setActive(false);
                session.release();
            } catch (Exception e) {
                Diag.log("media session release failed: " + e);
            }
            session = null;
        }
        cover = null;
        stopForeground(Service.STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---------------------------------------------------------------- session

    private void initSession() {
        session = new MediaSession(this, "VSTPlayer");
        if (Build.VERSION.SDK_INT < 26) {
            // Only meaningful before android 8, where these flags gate transport callbacks.
            session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                    | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        }
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() {
                if (player != null && !player.isPlaying()) player.toggle();
            }

            @Override public void onPause() {
                if (player != null && player.isPlaying()) player.toggle();
            }

            @Override public void onStop() {
                if (player != null) player.stop();
            }

            @Override public void onSkipToNext() {
                if (player != null) player.next();
            }

            @Override public void onSkipToPrevious() {
                if (player != null) player.prev();
            }

            @Override public void onSeekTo(long pos) {
                if (player != null) player.seekTo((int) Math.max(0, Math.min(Integer.MAX_VALUE, pos)));
            }
        }, main);
        session.setActive(false);
    }

    /** Called on the main thread whenever the player's track, state or position changes. */
    private void onMediaChanged() {
        if (stopping || session == null || player == null) return;
        if (!hasContent()) {
            shutdown();
            return;
        }
        applyPlayerState();
    }

    private boolean hasContent() {
        return player != null && player.hasMedia();
    }

    private void handle(String action) {
        if (player == null) return;
        switch (action) {
            case ACT_PLAY:
                if (!player.isPlaying()) player.toggle();
                break;
            case ACT_PAUSE:
                if (player.isPlaying()) player.toggle();
                break;
            case ACT_TOGGLE:
                player.toggle();
                break;
            case ACT_NEXT:
                player.next();
                break;
            case ACT_PREV:
                player.prev();
                break;
            default:
                break;
        }
    }

    // ---------------------------------------------------------------- state / notification

    /**
     * Mirrors the player into the session and the notification. Dragging the seek bar fires a
     * change per touch event, so metadata and the notification are only rebuilt when their
     * content signature actually changes; position-only updates cost a single binder call.
     */
    private void applyPlayerState() {
        if (stopping || session == null || player == null) return;
        syncCover();
        File t = player.track();
        boolean playing = player.isPlaying();
        String metaSig = (t == null ? "-" : t.getAbsolutePath())
                + "|" + System.identityHashCode(cover)
                + "|" + player.label()
                + "|" + player.durationMs()
                + "|" + player.index()
                + "|" + player.queueSize();
        if (!metaSig.equals(lastMetaSig)) {
            lastMetaSig = metaSig;
            updateMetadata(t);
        }
        updatePlaybackState(t, playing);
        String notifSig = metaSig + "|" + playing;
        if (notifSig.equals(lastNotifSig)) return;
        lastNotifSig = notifSig;
        try {
            startForeground(ID, buildNotification());
        } catch (Exception e) {
            Diag.log("foreground notification failed: " + e);
            shutdown();
        }
    }

    private void updateMetadata(File t) {
        if (session == null || player == null) return;
        int dur = player.durationMs();
        MediaMetadata.Builder md = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, titleFor(t))
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artistFor(t))
                .putLong(MediaMetadata.METADATA_KEY_DURATION, Math.max(0, dur))
                .putLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER, Math.max(0, player.index()) + 1)
                .putLong(MediaMetadata.METADATA_KEY_NUM_TRACKS, Math.max(0, player.queueSize()));
        String label = player.label();
        if (!label.isEmpty()) md.putString(MediaMetadata.METADATA_KEY_ALBUM, label);
        if (t != null) md.putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, titleFor(t));
        Bitmap art = cover;
        if (art != null) {
            md.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
            md.putBitmap(MediaMetadata.METADATA_KEY_ART, art);
        }
        session.setMetadata(md.build());
    }

    private void updatePlaybackState(File t, boolean playing) {
        if (session == null || player == null) return;
        int state = playing ? PlaybackState.STATE_PLAYING
                : (t == null ? PlaybackState.STATE_NONE : PlaybackState.STATE_PAUSED);
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(ACTIONS)
                .setState(state, Math.max(0, player.positionMs()), playing ? 1f : 0f,
                        SystemClock.elapsedRealtime())
                .build());
        if (!playing && t == null) {
            session.setActive(false);
        } else {
            session.setActive(true);
        }
    }

    private Notification buildNotification() {
        File t = player == null ? null : player.track();
        boolean playing = player != null && player.isPlaying();
        String title = titleFor(t);
        String text = artistFor(t);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CH)
                : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title.isEmpty() ? "VST Player" : title)
                .setContentText(text)
                .setContentIntent(PendingIntent.getActivity(this, 0, openIntent(),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                .setDeleteIntent(serviceIntent(ACT_STOP, 5))
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(playing)
                .setOnlyAlertOnce(true)
                .setShowWhen(false);
        String label = player == null ? "" : player.label();
        if (!label.isEmpty()) b.setSubText(label);
        if (cover != null) b.setLargeIcon(cover);

        b.addAction(action(android.R.drawable.ic_media_previous, "上一首", ACT_PREV, 1));
        b.addAction(action(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                playing ? "暂停" : "播放", ACT_TOGGLE, 2));
        b.addAction(action(android.R.drawable.ic_media_next, "下一首", ACT_NEXT, 3));

        if (session != null) {
            b.setStyle(new Notification.MediaStyle()
                    .setMediaSession(session.getSessionToken())
                    .setShowActionsInCompactView(0, 1, 2));
        }
        return b.build();
    }

    private Notification.Action action(int icon, String title, String act, int code) {
        return new Notification.Action.Builder(
                Icon.createWithResource(this, icon), title, serviceIntent(act, code)).build();
    }

    private PendingIntent serviceIntent(String act, int code) {
        Intent i = new Intent(this, PlaybackService.class).setAction(act);
        return PendingIntent.getService(this, code, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Intent openIntent() {
        Intent open = new Intent(this, com.vstplayer.MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return open;
    }

    private void shutdown() {
        if (session != null) {
            try { session.setActive(false); } catch (Exception ignored) { }
        }
        stopForeground(Service.STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    // ---------------------------------------------------------------- text / cover

    private String titleFor(File t) {
        if (t != null) return stem(t.getName());
        return fallbackTitle == null ? "" : stem(fallbackTitle);
    }

    private String artistFor(File t) {
        if (player != null) {
            String label = player.label();
            if (!label.isEmpty()) return label;
        }
        if (t != null) {
            File parent = t.getParentFile();
            if (parent != null && parent.getName() != null && !parent.getName().isEmpty()) {
                return parent.getName();
            }
        }
        return fallbackText != null && !fallbackText.isEmpty() ? fallbackText : "VST Player";
    }

    private static String stem(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /**
     * Loads the embedded picture for the current track once per track. Runs off the main thread
     * and degrades silently: a missing or undecodable cover just leaves the notification without
     * artwork.
     */
    private void syncCover() {
        // A library reference has no file on disk: its identity is a virtual path, and only the SAF
        // document behind it can be opened for artwork.
        Player.PlaybackSource src = player == null ? null : player.source();
        File t = src != null ? src.file : (player == null ? null : player.track());
        String path = t == null ? null : t.getAbsolutePath();
        if (path == null) {
            cover = null;
            coverFor = null;
            return;
        }
        if (path.equals(coverFor)) return;   // already loaded or already found missing
        coverFor = path;
        cover = null;
        if (coverExec == null) return;
        final String want = path;
        final android.net.Uri doc = src == null ? null : src.uri;
        try {
            coverExec.execute(() -> {
                Bitmap bmp = null;
                MediaMetadataRetriever r = new MediaMetadataRetriever();
                try {
                    if (doc != null) r.setDataSource(PlaybackService.this, doc);
                    else r.setDataSource(want);
                    byte[] art = r.getEmbeddedPicture();
                    if (art != null && art.length > 0) bmp = decodeCover(art);
                } catch (Throwable e) {
                    Diag.log("cover read failed: " + e);
                } finally {
                    try { r.release(); } catch (Throwable ignored) { }
                }
                final Bitmap out = bmp;
                main.post(() -> {
                    if (stopping || session == null || player == null) return;
                    File cur = player.track();
                    if (cur == null || !want.equals(cur.getAbsolutePath())) return;
                    if (out == null) return;   // no artwork: keep the placeholder, no error path
                    cover = out;
                    lastMetaSig = null;        // force a metadata/notification rebuild with art
                    lastNotifSig = null;
                    applyPlayerState();
                });
            });
        } catch (RejectedExecutionException e) {
            Diag.log("cover worker rejected: " + e);
        }
    }

    /** Decodes embedded art, downsampling to at most {@link #COVER_MAX}px on the long edge. */
    private static Bitmap decodeCover(byte[] raw) {
        try {
            BitmapFactory.Options probe = new BitmapFactory.Options();
            probe.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(raw, 0, raw.length, probe);
            int w = probe.outWidth, h = probe.outHeight;
            if (w <= 0 || h <= 0) return null;
            int sample = 1;
            while (w / (sample * 2) > COVER_MAX || h / (sample * 2) > COVER_MAX) sample *= 2;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            Bitmap b = BitmapFactory.decodeByteArray(raw, 0, raw.length, o);
            if (b == null) return null;
            if (b.getWidth() > COVER_MAX || b.getHeight() > COVER_MAX) {
                float s = Math.min(COVER_MAX / (float) b.getWidth(), COVER_MAX / (float) b.getHeight());
                Bitmap scaled = Bitmap.createScaledBitmap(b,
                        Math.max(1, Math.round(b.getWidth() * s)),
                        Math.max(1, Math.round(b.getHeight() * s)), true);
                if (scaled != b) b.recycle();
                b = scaled;
            }
            return b;
        } catch (Throwable e) {
            // Includes OutOfMemoryError from pathological artwork: never crash the service.
            Diag.log("cover decode failed: " + e);
            return null;
        }
    }

    // ---------------------------------------------------------------- channel

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                NotificationChannel c = new NotificationChannel(CH, "播放", NotificationManager.IMPORTANCE_LOW);
                c.setShowBadge(false);
                c.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
                nm.createNotificationChannel(c);
            }
        }
    }
}
