package com.vstplayer.core;

import java.io.File;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

/**
 * Reflection check of the public ReplayGain / RgTags / Loudness API the app is built against.
 * Compiled only in the stub build (see verify.py), never into the Android app.
 */
public final class ApiCheck {

    public static void main(String[] args) throws Exception {
        Method modeScan = ReplayGain.class.getMethod("scan", android.content.Context.class,
                List.class, double.class, ReplayGain.Mode.class, boolean.class,
                ReplayGain.Progress.class, ReplayGain.Cancel.class);
        if (!ReplayGain.Summary.class.equals(modeScan.getReturnType())) {
            throw new IllegalStateException("scan(Mode) must return Summary");
        }
        Method legacy = ReplayGain.class.getMethod("scan", android.content.Context.class,
                List.class, double.class, boolean.class, ReplayGain.Progress.class,
                ReplayGain.Cancel.class);
        if (!ReplayGain.Summary.class.equals(legacy.getReturnType())) {
            throw new IllegalStateException("legacy scan must still exist");
        }
        ReplayGain.class.getMethod("scanAsync", android.content.Context.class, List.class,
                double.class, ReplayGain.Mode.class, boolean.class, ReplayGain.Progress.class,
                ReplayGain.Cancel.class, Runnable.class);
        ReplayGain.Mode.valueOf("TRACK");
        ReplayGain.Mode.valueOf("ALBUM");
        ReplayGain.Mode.valueOf("ALL_AS_ONE_ALBUM");

        RgTags.class.getMethod("read", File.class);
        RgTags.class.getMethod("readMp4", File.class);
        RgTags.class.getMethod("writeMp4", File.class, Map.class);
        RgTags.class.getMethod("isMp4", File.class);
        RgTags.class.getMethod("albumKey", String.class, String.class);

        Loudness.AlbumAnalyzer.class.getMethod("add", File.class);
        Loudness.AlbumAnalyzer.class.getMethod("add", File.class, Loudness.Progress.class);
        Loudness.AlbumAnalyzer.class.getMethod("finish");
        Loudness.AlbumAnalyzer.class.getMethod("files");
        Loudness.AlbumAnalyzer.class.getMethod("cancelled");
        System.out.println("API OK modes=3");
    }
}
