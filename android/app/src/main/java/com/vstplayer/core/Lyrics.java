package com.vstplayer.core;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/** NetEase lyric lookup: strict match, embedded fallback, local cache. */
public final class Lyrics {

    public static final class Line {
        public final int ms;
        public final String text;
        Line(int ms, String text) { this.ms = ms; this.text = text; }
    }

    public static final class Doc {
        public final List<Line> lines;
        public final String note;
        final String raw;
        Doc(List<Line> lines, String note, String raw) {
            this.lines = lines; this.note = note; this.raw = raw;
        }
        public boolean ok() { return lines != null && !lines.isEmpty(); }
    }

    public interface Cb { void done(Doc doc); }

    private static final Pattern TAG = Pattern.compile("\\[(\\d+):(\\d+)(?:[.:](\\d+))?(?:-\\d+)?]");
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static final String UA = "Mozilla/5.0 (Linux; Android 14) VSTPlayer/1.0";

    private Lyrics() { }

    // ------------------------------------------------------------------ public

    public static void load(final Context ctx, final File audio, final String title, final String artist,
                            final int durationMs, final Cb cb) {
        new Thread(() -> {
            Doc doc = null;
            try {
                File cache = cacheFile(ctx, title, artist);
                String cached = cache.exists() ? readFile(cache) : null;
                if (cached != null && !cached.trim().isEmpty()) {
                    List<Line> lines = parse(cached);
                    if (!lines.isEmpty()) doc = new Doc(lines, "缓存", cached);
                }
                if (doc == null) doc = fetchNetEase(title, artist, durationMs);
                if ((doc == null || !doc.ok()) && audio != null) {
                    Tags.Info info = Tags.read(audio);
                    if (info.lyrics != null) {
                        List<Line> lines = parse(info.lyrics);
                        if (!lines.isEmpty()) doc = new Doc(lines, "元数据", info.lyrics);
                    }
                }
                if (doc != null && doc.ok() && doc.raw != null) {
                    try {
                        File f = cacheFile(ctx, title, artist);
                        File parent = f.getParentFile();
                        if (parent != null) parent.mkdirs();
                        FileOutputStream out = new FileOutputStream(f);
                        try { out.write(doc.raw.getBytes("UTF-8")); } finally { out.close(); }
                    } catch (Throwable ignored) { }
                }
            } catch (Throwable t) {
                Diag.log("lyrics failed: " + t);
            }
            final Doc result = doc;
            UI.post(() -> cb.done(result));
        }, "lyrics").start();
    }

    /** Manual search: takes the first candidate that actually returns lyric lines. */
    public static void search(final String query, final Cb cb) {
        new Thread(() -> {
            Doc doc = null;
            try {
                List<Cand> cands = searchCandidates(query.trim());
                for (int i = 0; i < Math.min(5, cands.size()) && doc == null; i++) {
                    String raw = fetchLyric(cands.get(i).id);
                    List<Line> lines = parse(raw);
                    if (!lines.isEmpty()) doc = new Doc(lines, cands.get(i).name + " - " + cands.get(i).artist, raw);
                }
            } catch (Throwable t) {
                Diag.log("lyrics search failed: " + t);
            }
            final Doc result = doc;
            UI.post(() -> cb.done(result));
        }, "lyrics-search").start();
    }

    // ------------------------------------------------------------------ remote

    private static final class Cand {
        long id;
        String name = "";
        String artist = "";
        long ms;
    }

    private static List<Cand> searchCandidates(String q) throws Exception {
        String url = "https://music.163.com/api/cloudsearch/pc?s="
                + URLEncoder.encode(q, "UTF-8").replace("+", "%20") + "&type=1&limit=100";
        JSONObject root = new JSONObject(get(url));
        if (root.optInt("code", 0) != 200) throw new Exception("search code " + root.optInt("code"));
        JSONObject result = root.optJSONObject("result");
        List<Cand> out = new ArrayList<>();
        if (result == null) return out;
        JSONArray songs = result.optJSONArray("songs");
        if (songs == null) return out;
        for (int i = 0; i < songs.length(); i++) {
            JSONObject s = songs.optJSONObject(i);
            if (s == null) continue;
            Cand c = new Cand();
            c.id = s.optLong("id");
            c.name = s.optString("name", "");
            JSONArray ar = s.optJSONArray("ar");
            StringBuilder sb = new StringBuilder();
            if (ar != null) for (int k = 0; k < ar.length(); k++) {
                JSONObject a = ar.optJSONObject(k);
                if (a != null) {
                    if (sb.length() > 0) sb.append('/');
                    sb.append(a.optString("name", ""));
                }
            }
            c.artist = sb.toString();
            c.ms = s.optLong("dt");
            out.add(c);
        }
        return out;
    }

    private static Doc fetchNetEase(String title, String artist, int durationMs) {
        try {
            String q = (artist == null ? "" : artist) + " " + (title == null ? "" : title);
            List<Cand> cands = searchCandidates(q.trim());
            List<Cand> ok = new ArrayList<>();
            for (Cand c : cands) {
                if (!nameLike(title, c.name)) continue;
                if (!artistLike(artist, c.artist)) continue;
                if (durationMs > 0 && Math.abs(c.ms - durationMs) > 6000) continue;
                ok.add(c);
            }
            ok.sort((a, b) -> {
                int d = Integer.compare(Math.abs((int) a.ms - durationMs), Math.abs((int) b.ms - durationMs));
                boolean ea = norm(a.name).equals(norm(title)), eb = norm(b.name).equals(norm(title));
                if (ea != eb) return ea ? -1 : 1;
                return d;
            });
            for (int i = 0; i < Math.min(3, ok.size()); i++) {
                String raw = fetchLyric(ok.get(i).id);
                List<Line> lines = parse(raw);
                if (lines.isEmpty()) continue;
                if (lines.get(0).ms > 10000) continue;   // truncated lyric, try the next candidate
                return new Doc(lines, "", raw);
            }
        } catch (Throwable t) {
            Diag.log("netease lookup failed: " + t);
        }
        return null;
    }

    private static String fetchLyric(long id) throws Exception {
        String url = "https://music.163.com/api/song/lyric?id=" + id + "&lv=-1&tv=-1&kv=-1";
        JSONObject root = new JSONObject(get(url));
        if (root.optInt("code", 0) != 200) return null;
        if (root.optBoolean("pureMusic") || root.optBoolean("uncollected")) return null;
        JSONObject lrc = root.optJSONObject("lrc");
        String text = lrc == null ? null : lrc.optString("lyric", null);
        if (text == null || text.trim().isEmpty()) return null;
        if (text.contains("纯音乐，请欣赏") || text.contains("暂无歌词")) return null;
        return text;
    }

    private static String get(String urlStr) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(12000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept-Encoding", "identity");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new Exception("http " + code);
            String enc = c.getContentEncoding();
            InputStream in = c.getInputStream();
            if (enc != null && enc.toLowerCase(Locale.US).contains("gzip")) in = new GZIPInputStream(in);
            else if (enc != null && enc.toLowerCase(Locale.US).contains("br"))
                throw new Exception("brotli not supported");
            String body;
            try {
                body = new String(Io.readAll(in), "UTF-8");
            } finally {
                in.close();
            }
            return body;
        } finally {
            c.disconnect();
        }
    }

    // ------------------------------------------------------------------ parse

    public static List<Line> parse(String lrc) {
        List<Line> out = new ArrayList<>();
        if (lrc == null) return out;
        for (String raw : lrc.split("\\n")) {
            String s = raw.trim();
            if (s.isEmpty()) continue;
            Matcher m = TAG.matcher(s);
            int lastEnd = -1;
            List<Integer> times = new ArrayList<>();
            while (m.find()) {
                int min = Integer.parseInt(m.group(1));
                int sec = Integer.parseInt(m.group(2));
                String frac = m.group(3);
                int ms = 0;
                if (frac != null) {
                    ms = frac.length() == 1 ? Integer.parseInt(frac) * 100
                            : frac.length() == 2 ? Integer.parseInt(frac) * 10
                            : Integer.parseInt(frac.substring(0, 3));
                }
                times.add(min * 60000 + sec * 1000 + ms);
                lastEnd = m.end();
            }
            if (lastEnd < 0) continue;
            String text = s.substring(lastEnd).trim();
            if (text.isEmpty()) continue;
            if (text.startsWith("//")) continue;
            if (text.matches("^(作词|作曲|编曲|制作人|混音|母带|录音|监制|发行|出品|策划|统筹|吉他|贝斯|鼓|键盘|和声|弦乐|封面|OP|SP)\\s*[:：].*")) continue;
            for (int t : times) out.add(new Line(t, text));
        }
        out.sort((a, b) -> Integer.compare(a.ms, b.ms));
        return out;
    }

    // ------------------------------------------------------------------ utils

    private static String norm(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.US).replaceAll("[\\s\\(\\)\\[\\]【】\\-_.·,，。!！?？'\"“”‘’]", "");
    }

    private static boolean nameLike(String want, String got) {
        String a = norm(want), b = norm(got);
        if (a.isEmpty() || b.isEmpty()) return false;
        return a.equals(b) || b.contains(a) || a.contains(b);
    }

    private static boolean artistLike(String want, String got) {
        if (want == null || want.trim().isEmpty()) return true;
        String[] parts = want.split("[/&,、]");
        String g = norm(got);
        for (String p : parts) {
            String n = norm(p);
            if (n.length() >= 2 && g.contains(n)) return true;
        }
        return false;
    }

    public static File cacheFile(Context c, String title, String artist) {
        String key = norm(title) + "|" + norm(artist);
        return new File(new File(c.getFilesDir(), "lyrics"), Integer.toHexString(key.hashCode()) + ".lrc");
    }

    private static String readFile(File f) {
        try {
            InputStream in = new java.io.FileInputStream(f);
            try {
                return new String(Io.readAll(in), "UTF-8");
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            return null;
        }
    }
}
