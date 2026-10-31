package com.vstplayer.core;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Small path helpers shared by the library tree, the playlist editor and the importer. */
public final class Paths {

    private Paths() { }

    /** True when {@code f} sits anywhere below {@code dir}. */
    public static boolean isUnder(File dir, File f) {
        if (dir == null || f == null) return false;
        File p = f.getParentFile();
        for (int guard = 0; p != null && guard < 32; guard++) {
            if (p.equals(dir)) return true;
            p = p.getParentFile();
        }
        return false;
    }

    /** '/'-separated path of {@code f} relative to {@code root}, or null when it is outside. */
    public static String relTo(File root, File f) {
        if (root == null || f == null) return null;
        String r = root.getAbsolutePath();
        String a = f.getAbsolutePath();
        if (!a.startsWith(r + File.separator)) return null;
        return a.substring(r.length() + 1).replace(File.separatorChar, '/');
    }

    /** Makes one path segment safe for the guest file system and for SAF display names. */
    public static String sanitize(String seg) {
        if (seg == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char ch : seg.toCharArray()) {
            if (ch < 32 || ch == '/' || ch == '\\' || ch == ':' || ch == '*'
                    || ch == '?' || ch == '"' || ch == '<' || ch == '>' || ch == '|') {
                sb.append('_');
            } else {
                sb.append(ch);
            }
        }
        String s = sb.toString().trim();
        while (s.startsWith(".")) s = s.substring(1);
        if (s.length() > 80) s = s.substring(0, 80);
        return s;
    }

    public static boolean isAudioName(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.US);
        int dot = n.lastIndexOf('.');
        if (dot < 0) return false;
        switch (n.substring(dot + 1)) {
            case "wav": case "wave": case "flac": case "aif": case "aiff": case "aifc":
            case "m4a": case "mp4": case "mp3": case "aac": case "ogg": case "oga":
            case "opus": case "caf":
                return true;
            default:
                return false;
        }
    }

    /** Default recursion depth for {@link #audioFiles(File)}; deep enough for any album tree. */
    public static final int DEFAULT_DEPTH = 8;

    /** Every audio file below {@code dir} (recursive, name sorted, hidden entries skipped). */
    public static List<File> audioFiles(File dir) {
        return audioFiles(dir, DEFAULT_DEPTH, true);
    }

    /**
     * Every audio file below {@code dir}, with the depth and the ordering chosen by the caller.
     *
     * <p>Callers that only want a count or a single level pay for what they ask: the recursive
     * default used to walk (and sort) an entire tree to answer "is this folder empty".
     *
     * @param maxDepth how many directory levels to descend; 0 means {@code dir} itself only,
     *                 negative means {@link #DEFAULT_DEPTH}
     * @param sorted   true for the name-sorted result callers rely on today; false skips the sort
     */
    public static List<File> audioFiles(File dir, int maxDepth, boolean sorted) {
        List<File> out = new ArrayList<>();
        collect(dir, out, 0, maxDepth < 0 ? DEFAULT_DEPTH : maxDepth);
        if (sorted) {
            java.util.Collections.sort(out, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        }
        return out;
    }

    private static void collect(File dir, List<File> out, int depth, int maxDepth) {
        if (dir == null || depth > maxDepth) return;
        File[] arr = dir.listFiles();
        if (arr == null) return;
        for (File f : arr) {
            if (f.isDirectory()) {
                if (!f.getName().startsWith(".")) collect(f, out, depth + 1, maxDepth);
            } else if (!f.getName().startsWith(".") && isAudioName(f.getName())) {
                out.add(f);
            }
        }
    }

    /**
     * A file name without its extension ({@code "a.flac" -> "a"}); the whole name when it has no
     * dot, and "" for null. One copy, used by the notification service.
     */
    public static String stem(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
