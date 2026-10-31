package com.vstplayer.core;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Off-device hook for the delivery encoders.
 *
 * <p>Both encoders are hard to exercise without running the real pipeline, and the AAC
 * one cannot be tested on a workstation at all because MediaCodec only exists on
 * Android. Pushing an empty file named {@code encode-selftest} into the app's files
 * directory therefore makes the next start encode every {@code *.wav} in
 * {@code files/selftest} once, write {@code files/encode-selftest.txt} and remove the
 * marker. Debug builds only, no UI, no engine.
 *
 * <p>For a {@code name.wav} it produces {@code name.flac} (lossless source). When a
 * {@code name.mp3} sits next to it, that pair also produces {@code name.m4a} at the mp3's
 * bitrate (lossy source), which is the path that needs a real MediaCodec.
 */
public final class EncodeSmoke {

    private static final String MARKER = "encode-selftest";
    private static final String REPORT = "encode-selftest.txt";

    private EncodeSmoke() { }

    /** Runs the encode pass when the marker file is present. Blocking; call from onCreate. */
    public static void runIfRequested(Context ctx) {
        File files = ctx.getFilesDir();
        if (files == null) return;
        File marker = new File(files, MARKER);
        if (!marker.isFile()) return;
        marker.delete();

        StringBuilder report = new StringBuilder();
        File dir = new File(files, "selftest");
        File[] wavs = dir.listFiles((d, n) -> n.toLowerCase(java.util.Locale.ROOT).endsWith(".wav"));
        if (wavs == null || wavs.length == 0) {
            report.append("no .wav in ").append(dir).append('\n');
        } else {
            for (File wav : wavs) {
                report.append(line("flac", wav, Encode.compress(ctx, wav, wav)));
                String base = wav.getName().substring(0, wav.getName().length() - 4);
                File mp3 = new File(dir, base + ".mp3");
                if (mp3.isFile()) {
                    report.append(line("aac", mp3, Encode.compress(ctx, mp3, wav)));
                }
            }
        }
        try (FileOutputStream out = new FileOutputStream(new File(files, REPORT))) {
            out.write(report.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Diag.log("encode smoke: cannot write report: " + e);
        }
        Diag.log("encode smoke done:\n" + report);
    }

    private static String line(String how, File src, File out) {
        boolean changed = out != null && !out.equals(src);
        return how + " " + src.getName() + " -> "
                + (out == null ? "null" : out.getName())
                + (changed ? " " + out.length() + "B" : " (fallback)")
                + "\n";
    }
}
