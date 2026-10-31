package com.vstplayer.core;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * Foreground service that keeps a running render batch alive while the app is in the background
 * or the screen is off. Android freezes cached processes, and a frozen host takes its guest render
 * engine (proot/wine) down with it, so the batch is only safe while a foreground service and a
 * partial wake lock are held. It shows one low-importance notification with the progress text.
 */
public class EngineService extends Service {
    private static final String CH = "vstplayer";
    private static final int ID = 3;
    private static volatile boolean running;

    public static void start(Context ctx, String text) {
        if (ctx == null) return;
        try {
            Intent i = new Intent(ctx, EngineService.class);
            i.putExtra("text", text == null ? "渲染中" : text);
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i); else ctx.startService(i);
        } catch (Throwable t) {
            Diag.log("render service start failed: " + t);
        }
    }

    /** Updates the notification in place; never starts the service by itself. */
    public static void update(Context ctx, String text, int percent) {
        if (ctx == null || !running) return;
        try {
            Intent i = new Intent(ctx, EngineService.class);
            i.putExtra("text", text == null ? "渲染中" : text);
            i.putExtra("pct", percent);
            ctx.startService(i);
        } catch (Throwable t) {
            Diag.log("render service update failed: " + t);
        }
    }

    public static void stop(Context ctx) {
        running = false;
        if (ctx == null) return;
        try {
            ctx.stopService(new Intent(ctx, EngineService.class));
        } catch (Throwable t) {
            Diag.log("render service stop failed: " + t);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(CH, "渲染", NotificationManager.IMPORTANCE_LOW);
            c.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(c);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String text = intent == null ? "渲染中" : intent.getStringExtra("text");
        int pct = intent == null ? -1 : intent.getIntExtra("pct", -1);
        if (text == null) text = "渲染中";
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CH)
                : new Notification.Builder(this);
        b.setContentTitle("VST Player").setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setVisibility(Notification.VISIBILITY_PUBLIC);
        if (pct >= 0) b.setProgress(100, Math.max(0, Math.min(100, pct)), false);
        Intent open = new Intent(this, com.vstplayer.MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        b.setContentIntent(PendingIntent.getActivity(this, 0, open,
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        // mediaPlayback is the type OEM power managers treat like a music app: a plain dataSync
        // foreground service was still frozen about 45 s after backgrounding on OriginOS, which
        // stalls the render engine behind a full stdout pipe.
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID, b.build(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(ID, b.build());
        }
        running = true;
        // A batch survives a task swipe; it stops when the batch finishes or the app is killed.
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        try {
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
            else stopForeground(true);
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
