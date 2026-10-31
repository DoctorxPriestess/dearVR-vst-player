package com.vstplayer.core;

import android.content.Context;
import android.content.pm.ApplicationInfo;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;

/**
 * Debug-only launch overrides from {@code files/boot.conf}, one {@code KEY=VALUE} per line.
 *
 * <p>Scripted device runs need knobs the UI cannot reach by tapping: which page to open, and
 * whether a render batch starts on its own. Every reader here returns the caller's default
 * unless the installed build is debuggable, so a release build cannot be steered by a file.
 * The file is read on each call; it is a few bytes and only debug builds ever read it.
 */
public final class BootConf {

    private BootConf() { }

    /** True when this build accepts overrides at all. */
    public static boolean enabled(Context c) {
        if (c == null) return false;
        ApplicationInfo ai = c.getApplicationInfo();
        return ai != null && (ai.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    /** Raw value for {@code key}, or null when the file, the key, or the debug flag is absent. */
    public static String raw(Context c, String key) {
        if (!enabled(c)) return null;
        File f = new File(c.getFilesDir(), "boot.conf");
        if (!f.isFile()) return null;
        try {
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(new FileInputStream(f), "UTF-8"));
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    int eq = line.indexOf('=');
                    if (eq <= 0) continue;
                    if (!line.substring(0, eq).trim().equals(key)) continue;
                    return line.substring(eq + 1).trim();
                }
            } finally {
                r.close();
            }
        } catch (Exception e) {
            Diag.log("boot.conf: " + key + ": " + e);
        }
        return null;
    }

    /** Clamped integer for {@code key}, or {@code def} when absent or unparseable. */
    public static int intValue(Context c, String key, int def, int lo, int hi) {
        String v = raw(c, key);
        if (v == null) return def;
        try {
            return Math.max(lo, Math.min(hi, Integer.parseInt(v)));
        } catch (NumberFormatException e) {
            Diag.log("boot.conf: " + key + " is not a number: " + v);
            return def;
        }
    }
}
