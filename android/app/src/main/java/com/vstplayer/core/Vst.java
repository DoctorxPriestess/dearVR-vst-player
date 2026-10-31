package com.vstplayer.core;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Session layer over {@link Engine}: brings the engine up, exposes the plugin
 * parameter table, presets and rendering, one protocol command at a time.
 */
public class Vst implements Engine.Listener {

    public static final int OFF = 0, STARTING = 1, READY = 2, FAILED = 3;

    /** Result callback, always delivered on the main thread. */
    public interface Cb {
        void done(boolean ok, String msg);
    }

    public interface StateListener {
        void onState(int state, String label);
        void onRenderProgress(int percent);
        void onParams();
    }

    private static final class Cmd {
        final String line;
        final Cb cb;
        Cmd(String line, Cb cb) { this.line = line; this.cb = cb; }
    }

    private static Vst instance;

    public static synchronized Vst get() {
        if (instance == null) instance = new Vst();
        return instance;
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    /**
     * Off-main-thread work for this class. One daemon thread: the only user is parameter table
     * parsing, which is a file read plus JSON, and a single thread keeps the results ordered.
     */
    private static final java.util.concurrent.ExecutorService BG =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "vst-bg");
                t.setDaemon(true);
                return t;
            });
    private final Object lock = new Object();
    private final ArrayDeque<Cmd> queue = new ArrayDeque<>();
    private final Map<String, Float> pending = new LinkedHashMap<>();
    private final List<Param> params = new ArrayList<>();

    private final java.util.Map<String, Float> applied = new java.util.LinkedHashMap<>();
    private volatile Engine engine;
    private volatile Env env;
    private volatile Cmd current;
    private volatile StateListener listener;
    private volatile int state = OFF;
    private volatile String stateLabel = "";
    private volatile boolean flushing;
    private volatile boolean fetchAgain;
    /** True only while the one-time rootfs extraction runs — the only phase that blocks the UI. */
    private volatile boolean preparing;
    private volatile android.content.Context appCtx;
    private boolean seccompFallback;
    private boolean driveFixRetry;
    private volatile boolean pluginMissing;
    private String stageLabel = "准备环境";
    private int renderPct = -1;

    private Vst() { }

    // ---------------------------------------------------------------- lifecycle

    public int state() { return state; }

    public boolean isPreparing() { return preparing; }

    public String stateLabel() { return stateLabel; }

    public void setListener(StateListener l) { listener = l; }

    public Env env() { return env; }

    public Engine engine() { return engine; }

    /** Extract (once) and boot the engine. Safe to call repeatedly. */
    public synchronized void start(Context ctx, final StateListener l) {
        if (l != null) listener = l;
        if (state == STARTING || state == READY) return;
        final Context app = ctx.getApplicationContext();
        appCtx = app;
        env = App.env(app);
        setState(STARTING, "准备环境");
        final long tBoot = Diag.now();
        new Thread(() -> {
            try {
                if (!env.isReady()) {
                    // A guest that is still exiting keeps the old loader mapped; re-extraction
                    // would fail with ETXTBSY, so drop any leftover guest processes first. Forced:
                    // a rate limited skip would hand the extraction a still mapped binary.
                    Engine.killStrayGuests(true);
                    preparing = true;
                    Diag.log("env extraction begin");
                    extractWithRetry(env);
                    preparing = false;
                    Diag.log("env extraction done +" + (Diag.now() - tBoot) + "ms");
                }
                // A missing drive_c means wine has to build the whole prefix first (~1 min).
                setState(STARTING, new File(env.winePrefix, "drive_c").exists()
                        ? "启动引擎" : "初始化 Wine 前缀（首次约 1 分钟）");
                Engine e = App.engine(app);
                engine = e;
                e.start(this);
            } catch (Throwable t) {
                Diag.log("boot failed: " + t);
                setState(FAILED, String.valueOf(t));
            }
        }, "vst-boot").start();
    }

    /** True while a render is in flight. */
    public boolean isRendering() {
        return renderPct >= 0 || lanesBusy();
    }

    // --------------------------------------------------------- helper engines

    /**
     * Extra render lanes; lane 0 is the primary engine owned by this class. Each lane is its own
     * guest pinned to its own core, so on an 8 core phone a playlist batch can drive 8 lanes; the
     * caller decides how many are actually worth spawning from free memory.
     */
    private static final int MAX_HELPERS = 7;
    private final Helper[] helpers = new Helper[MAX_HELPERS];

    /**
     * A render lane backed by its own guest process. Two lanes measured 1.9x the throughput of a
     * single queue; a third lane takes the next fastest core. Each lane owns its queue and its own
     * progress counter, so the UI keeps one bar.
     */
    private final class Helper {
        final int lane;              // 1..MAX_HELPERS, also the affinity rank
        volatile Engine engine;
        volatile boolean starting;
        final Object lock = new Object();
        final ArrayDeque<Cmd> queue = new ArrayDeque<>();
        volatile Cmd current;
        volatile int pct = -1;
        volatile Runnable onReady;

        Helper(int lane) {
            this.lane = lane;
        }

        boolean ready() {
            Engine e = engine;
            return e != null && e.isReady();
        }

        void start() {
            if (engine != null || starting) return;
            final Env e0 = env;
            if (e0 == null) return;
            starting = true;
            final Helper self = this;
            new Thread(() -> {
                try {
                    Engine e = new Engine(e0, lane);
                    e.start(new Engine.Listener() {
                        @Override public void onLog(String line) { self.onLog(line); }
                        @Override public void onReady() {
                            Diag.log("engine" + lane + " ready");
                            starting = false;
                            ui.post(() -> {
                                Runnable r = onReady;
                                if (r != null) r.run();
                            });
                        }
                        @Override public void onExit(int code) {
                            Diag.log("engine" + lane + " exit " + code);
                            starting = false;
                        }
                    });
                    engine = e;
                } catch (Throwable t) {
                    Diag.log("engine" + lane + " start failed: " + t);
                    starting = false;
                }
            }, "engine" + lane + "-boot").start();
        }

        void stop() {
            Engine e = engine;
            engine = null;
            starting = false;
            current = null;
            pct = -1;
            synchronized (lock) {
                queue.clear();
            }
            if (e != null) e.stop();
        }

        void render(File in, File out, Cb cb) {
            Engine e = engine;
            if (e == null || !e.isReady()) {
                ui.post(() -> cb.done(false, "辅助引擎未就绪"));
                return;
            }
            pct = 0;
            Diag.log("render" + lane + " " + gp(in));
            long t0 = Diag.now();
            exec(gp(in) + "|" + gp(out), (ok, msg) -> {
                pct = -1;
                Diag.log("render" + lane + " done ok=" + ok + " +" + (Diag.now() - t0) + "ms " + out.getName());
                cb.done(ok, ok ? out.getName() : msg);
            });
        }

        void exec(String line, Cb cb) {
            Cmd c = new Cmd(line, cb);
            boolean go;
            synchronized (lock) {
                queue.add(c);
                go = current == null;
            }
            if (go) sendNext();
        }

        boolean sendNext() {
            final Cmd c;
            synchronized (lock) {
                // Never steal the slot from a command that is already in flight: finish() clears
                // current and then calls sendNext() while exec() may concurrently drain the queue,
                // and the loser of that race used to poll an empty queue and null a just-sent
                // command, whose ack then matched nothing and stalled the lane forever.
                if (current != null) return false;
                c = queue.poll();
                current = c;
            }
            if (c == null) return false;
            Engine e = engine;
            try {
                Diag.log("cmd" + lane + " " + c.line);
                if (e == null) throw new IOException("engine" + lane + " gone");
                e.send(c.line);
            } catch (IOException ex) {
                finish(c, false, "send failed: " + ex.getMessage());
                return false;
            }
            return true;
        }

        void finish(Cmd c, boolean ok, String msg) {
            synchronized (lock) {
                if (current == c) current = null;
            }
            final Cb cb = c.cb;
            if (cb != null) ui.post(() -> cb.done(ok, msg));
            sendNext();
        }

        void onLog(String line) {
            String t = line == null ? "" : line.trim();
            Cmd c = current;
            String tag = "E" + (lane + 1) + "| ";
            if (c == null) {
                if (!t.isEmpty()) Diag.log(tag + t);
                return;
            }
            if (t.startsWith("PROG ")) {
                try {
                    pct = Integer.parseInt(t.substring(5).trim());
                } catch (Exception ignored) {
                }
                return;
            }
            if (t.equals("OK") || t.startsWith("OK ")) {
                finish(c, true, t.length() > 3 ? t.substring(3) : "");
            } else if (t.equals("ERR") || t.startsWith("ERR ")) {
                Diag.log("ERR" + (lane + 1) + "| " + t);
                finish(c, false, t.length() > 4 ? t.substring(4) : "错误");
            } else {
                Diag.log(tag + t);
            }
        }
    }

    private boolean lanesBusy() {
        synchronized (helpers) {
            for (int i = 0; i < MAX_HELPERS; i++) {
                Helper h = helpers[i];
                if (h != null && (h.current != null || h.pct >= 0)) return true;
            }
        }
        return false;
    }

    /** Total lanes available to the caller, including the primary engine. */
    public int lanes() {
        return MAX_HELPERS + 1;
    }

    private Helper helper(int lane) {
        int i = lane - 1;
        if (i < 0 || i >= MAX_HELPERS) return null;
        synchronized (helpers) {
            if (helpers[i] == null) helpers[i] = new Helper(lane);
            return helpers[i];
        }
    }

    public boolean isReady(int lane) {
        if (lane <= 0) return state == READY;
        Helper h = helper(lane);
        return h != null && h.ready();
    }

    public boolean isStarting(int lane) {
        Helper h = helper(lane);
        return h != null && h.starting;
    }

    public void setOnLaneReady(int lane, Runnable r) {
        Helper h = helper(lane);
        if (h != null) h.onReady = r;
    }

    /** Boots one helper lane; the ready callback runs on the main thread. */
    public void startHelper(int lane) {
        Helper h = helper(lane);
        if (h != null) h.start();
    }

    public void stopHelpers() {
        synchronized (helpers) {
            for (int i = 0; i < MAX_HELPERS; i++) {
                if (helpers[i] != null) helpers[i].stop();
            }
        }
    }

    /**
     * Drops the plugin's internal state on one lane so the next render starts as if the host had
     * just booted.
     *
     * <p>The hosted plugin keeps its delay lines and reverb tails across renders: rendering file B
     * right after file A lets A's tail bleed into B's first samples, and re-rendering the same file
     * in the same process produces different bytes. The host's RESET command rebuilds the plugin
     * instance (~19 ms, versus ~3.5 s for a guest reboot) and restores the current parameter state.
     * An engine that predates the command answers ERR; the caller renders anyway.
     */
    public void resetLane(int lane, Cb cb) {
        if (lane <= 0) {
            exec("RESET", cb);
            return;
        }
        Helper h = helper(lane);
        if (h == null) {
            if (cb != null) ui.post(() -> cb.done(false, "没有第 " + lane + " 条渲染车道"));
            return;
        }
        h.exec("RESET", cb);
    }

    /** Renders one file on the given lane; lane 0 is the primary engine. */
    public void renderLane(int lane, File in, File out, Cb cb) {
        if (lane <= 0) {
            render(in, out, cb);
            return;
        }
        Helper h = helper(lane);
        if (h == null) {
            ui.post(() -> cb.done(false, "没有第 " + lane + " 条渲染车道"));
            return;
        }
        h.render(in, out, cb);
    }

    /**
     * Stops the engine and reaps its guest processes. Called when the user really closes the app
     * (back / task removed) and nothing is playing or rendering, so ~400 MB of wine does not stay
     * resident. Backgrounding with HOME keeps the warm engine for an instant return.
     */
    public synchronized void stopEngine() {
        Engine e = engine;
        engine = null;
        state = OFF;
        setState(OFF, "引擎已停止");
        stopHelpers();
        if (e != null) e.stop();
        final Env env0 = env;
        if (env0 != null) env0.invalidateDirs();
        // The guest sweep opens every /proc entry; this method is called from onDestroy, i.e. on
        // the main thread, so it must not run there.
        Thread t = new Thread(() -> Engine.killStrayGuests(true), "engine-reap");
        t.setDaemon(true);
        t.start();
        Diag.log("engine stopped (app closing)");
    }

    public void restart(Context ctx, StateListener l) {
        Engine e = engine;
        if (e != null) e.stop();
        state = OFF;
        start(ctx, l);
    }

    /** Runs the one-time extraction; a guest that is still dying can hold files busy, so retry once. */
    private void extractWithRetry(Env env) throws Exception {
        for (int attempt = 0; ; attempt++) {
            try {
                env.extract(new Env.Progress() {
                    @Override public void onStage(String label, int percent) {
                        // Percent-only ticks pass a null label; keep the last stage name so the
                        // mask never renders "null 47%".
                        if (label != null) stageLabel = label;
                        setState(STARTING, stageLabel + " " + percent + "%");
                    }
                    @Override public void onLog(String msg) {
                        Diag.log("E| " + msg);
                    }
                });
                return;
            } catch (Exception e) {
                String m = String.valueOf(e.getMessage());
                boolean busy = m.contains("ETXTBSY") || m.contains("Text file busy")
                        || m.contains("Device or resource busy");
                if (attempt >= 1 || !busy) throw e;
                Diag.log("extraction blocked by a dying guest, retrying");
                Engine.killStrayGuests(true);
                try {
                    Thread.sleep(800);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    private void setState(int s, String label) {
        state = s;
        stateLabel = label;
        final StateListener l = listener;
        if (l != null) ui.post(() -> l.onState(s, label));
    }

    // ---------------------------------------------------------------- protocol

    public boolean busy() {
        return current != null;
    }

    private boolean sendNext() {
        Cmd c;
        synchronized (lock) {
            // Same guard as Helper.sendNext: one command owns the slot, so a concurrent finish()
            // cannot null a command that was just written to the engine.
            if (current != null) return false;
            c = queue.poll();
            current = c;
        }
        if (c == null) return false;
        try {
            Diag.log("cmd " + c.line);
            engine.send(c.line);
        } catch (IOException e) {
            finish(c, false, "send failed: " + e.getMessage());
            return false;
        }
        return true;
    }

    /** Queue one raw protocol line; the callback fires when OK/ERR arrives. */
    public void exec(String line, Cb cb) {
        if (engine == null || !engine.isReady()) {
            if (cb != null) ui.post(() -> cb.done(false, "引擎未就绪"));
            return;
        }
        boolean go;
        synchronized (lock) {
            queue.add(new Cmd(line, cb));
            go = current == null;
        }
        if (go) sendNext();
    }

    private void finish(Cmd c, boolean ok, String msg) {
        synchronized (lock) {
            if (current == c) current = null;
        }
        if (ok && c.cb != null) ui.post(() -> c.cb.done(true, msg));
        else if (!ok && c.cb != null) ui.post(() -> c.cb.done(false, msg));
        sendNext();
    }

    @Override public void onLog(String line) {
        String t = line == null ? "" : line.trim();
        if (t.contains("plugin not found")) pluginMissing = true;
        if (t.startsWith("READY")) {
            setState(READY, "就绪");
            fetchParams(0);
            return;
        }
        Cmd c = current;
        if (c == null) { Diag.log("E| " + line); return; }
        if (t.startsWith("PROG ")) {
            try { renderPct = Integer.parseInt(t.substring(5).trim()); } catch (Exception ignored) { }
            final StateListener l = listener;
            if (l != null) ui.post(() -> l.onRenderProgress(renderPct));
            return;
        }
        if (t.equals("OK") || t.startsWith("OK ")) {
            finish(c, true, t.length() > 3 ? t.substring(3) : "");
        } else if (t.equals("ERR") || t.startsWith("ERR ")) {
            Diag.log("ERR| " + t);
            finish(c, false, t.length() > 4 ? t.substring(4) : "错误");
        } else {
            Diag.log("E| " + line);
        }
    }

    @Override public void onReady() { }

    @Override public void onExit(int code) {
        Diag.log("engine exit " + code);
        stopHelpers();
        Engine e = engine;
        // Some kernels reject proot's seccomp filter: proot then dies instantly with no output.
        // Fall back once to the ptrace path so the app still works (at ~4x the syscall cost).
        // Fresh install: wine creates the prefix with a Termux-mapped Z: on first boot, so the
        // plugin path cannot resolve. Repair the mapping and boot again instead of failing.
        if (e != null && pluginMissing && !driveFixRetry && appCtx != null) {
            driveFixRetry = true;
            pluginMissing = false;
            Diag.log("plugin path unresolved; repairing wine drives and retrying once");
            e.repairDrives();
            state = OFF;
            start(appCtx, null);
            return;
        }
        if (e != null && !e.sawOutput() && Engine.PROOT_SECCOMP && !seccompFallback && appCtx != null) {
            seccompFallback = true;
            Engine.PROOT_SECCOMP = false;
            Diag.log("no engine output; retrying with PROOT_NO_SECCOMP=1");
            state = OFF;
            start(appCtx, null);
            return;
        }
        setState(OFF, "引擎已停止");
    }

    // ---------------------------------------------------------------- params

    public List<Param> params() {
        synchronized (params) { return new ArrayList<>(params); }
    }

    public File paramsFile() {
        return new File(env.glibc, "opt/vstenv/work/params.json");
    }

    public void fetchParams(int attempt) {
        exec("PARAMS|" + gp(paramsFile()), (ok, msg) -> {
            if (!ok) {
                Diag.log("PARAMS failed: " + msg);
                if (attempt < 2) ui.postDelayed(() -> fetchParams(attempt + 1), 800);
                return;
            }
            parseParams();
        });
    }

    /**
     * Reads and parses params.json off the main thread, then publishes the table on it.
     *
     * <p>The PARAMS ack arrives on the UI thread, and a plugin with a few hundred parameters makes
     * the file read plus {@code JSONObject} parse a visible stall. The table itself is still
     * swapped on the main thread, so readers of {@link #params()} keep the same ordering.
     */
    private void parseParams() {
        BG.execute(() -> {
            final List<Param> out;
            try {
                out = readParams();
            } catch (Exception e) {
                Diag.log("params parse failed: " + e);
                return;
            }
            ui.post(() -> applyParams(out));
        });
    }

    /** Installs a freshly parsed parameter table (main thread). */
    private void applyParams(List<Param> out) {
        synchronized (params) {
            params.clear();
            params.addAll(out);
        }
        synchronized (applied) {
            applied.clear();
            for (Param q : out) applied.put(q.id, q.norm);
        }
        Diag.log("params loaded: " + out.size());
        final StateListener l = listener;
        if (l != null) ui.post(l::onParams);
    }

    private static String readText(File f) throws IOException {
        try (java.io.InputStream in = new java.io.FileInputStream(f)) {
            return new String(Io.readAll(in), "UTF-8");
        }
    }

    public void setParam(String id, float norm) {
        if (state != READY) return;
        if (renderPct >= 0) return;             // never interleave a SET with a running render
        synchronized (applied) { applied.put(id, norm); }
        synchronized (lock) { pending.put(id, norm); }
        if (flushing) return;
        flushing = true;
        flushParams();
    }

    private void flushParams() {
        String id;
        float val;
        synchronized (lock) {
            if (current != null || pending.isEmpty()) { flushing = false; return; }
            Map.Entry<String, Float> e = pending.entrySet().iterator().next();
            id = e.getKey();
            val = e.getValue();
            pending.remove(id);
        }
        exec("SET|" + id + "|" + String.format(Locale.US, "%.6f", val), (ok, msg) -> {
            if (ok) applyText(id, msg);
            else Diag.log("SET " + id + " failed: " + msg);
            ui.postDelayed(this::flushParams, 40);
        });
    }

    private void applyText(String id, String text) {
        synchronized (params) {
            for (Param p : params) if (p.id.equals(id)) { p.text = text; break; }
        }
        final StateListener l = listener;
        if (l != null) ui.post(l::onParams);
    }

    // -------------------------------------------------- helper state replication

    /**
     * Copies the primary engine's live parameter values onto a helper lane. A helper starts from the
     * plugin defaults, so without this a split file would be rendered with two different sounds on
     * either side of a seam. The primary engine is the source of truth: asking it for PARAMS| dumps
     * its current state, which is then replayed as SET| on the helper. The commands are queued, so
     * the caller may hand the lane work immediately afterwards.
     */
    public void syncParamsTo(int lane, Runnable done) {
        final Helper h = helper(lane);
        if (h == null || !h.ready()) {
            Diag.log("param sync: lane " + lane + " not ready");
            if (done != null) ui.post(done);
            return;
        }
        if (state != READY) {
            if (done != null) ui.post(done);
            return;
        }
        // The live parameter table is the source of truth: it is what the UI shows and what the
        // primary engine holds. Never ask the primary for a PARAMS dump here — that command sits in
        // the primary's command queue *behind its current render job*, so with long files every
        // helper waited ~45 s to be synced and the watchdog dropped them (measured: 8 lanes, only
        // 2 actually rendering). Reading the table locally keeps the sync at a few hundred ms.
        List<Param> all;
        synchronized (params) {
            all = new ArrayList<>(params);
        }
        if (all.isEmpty()) {
            try {
                all = readParams();          // last dump on disk, still better than defaults
            } catch (Exception e) {
                Diag.log("param sync: cannot read params.json: " + e);
            }
        }
        if (all.isEmpty()) {
            Diag.log("param sync: no parameter values known, lane " + lane + " keeps the defaults");
            if (done != null) ui.post(done);
            return;
        }
        replayParams(h, lane, all, 0, done);
    }

    private void replayParams(Helper h, int lane, List<Param> all, int i, Runnable done) {
        if (i >= all.size()) {
            Diag.log("engine" + lane + " params synced: " + all.size());
            if (done != null) ui.post(done);
            return;
        }
        Param p = all.get(i);
        h.exec("SET|" + p.id + "|" + String.format(Locale.US, "%.6f", p.norm), (ok, msg) -> {
            if (!ok) Diag.log("param sync: SET " + p.id + " failed: " + msg);
            replayParams(h, lane, all, i + 1, done);
        });
    }

    /** Parses params.json into a list without touching the UI copy. */
    private List<Param> readParams() throws Exception {
        String s = readText(paramsFile());
        JSONObject root = new JSONObject(s);
        JSONArray arr = root.optJSONArray("params");
        List<Param> out = new ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                out.add(new Param(o.optInt("index"), o.optString("id"), o.optString("name"),
                        o.optString("label"), o.optInt("steps"), (float) o.optDouble("norm", 0),
                        o.optString("text")));
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- presets

    public File presetsDir() {
        File f = new File(env.glibc, "opt/vstenv/presets");
        f.mkdirs();
        return f;
    }

    public File[] presets() {
        File[] f = presetsDir().listFiles((d, n) -> n.toLowerCase(Locale.US).endsWith(".vstpreset"));
        if (f == null) return new File[0];
        java.util.Arrays.sort(f, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return f;
    }

    public void savePreset(String name, Cb cb) {
        String clean = sanitize(name);
        if (clean.isEmpty()) { cb.done(false, "名称不能为空"); return; }
        File f = new File(presetsDir(), clean + ".vstpreset");
        exec("SAVE|" + gp(f), (ok, msg) -> {
            Diag.log("save preset " + f + " -> " + ok + " " + msg);
            cb.done(ok, ok ? f.getName() : msg);
        });
    }

    public void loadPreset(File f, Cb cb) {
        exec("PRESET|" + gp(f), (ok, msg) -> {
            if (ok) fetchParams(0);
            cb.done(ok, ok ? f.getName() : msg);
        });
    }

    private static String sanitize(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == ' ') b.append(c);
        }
        return b.toString().trim();
    }

    // ---------------------------------------------------------------- render

    public File inDir() { return engine == null ? null : engine.inDir(); }

    public File outDir() { return engine == null ? null : engine.outDir(); }

    public void render(File in, File out, Cb cb) {
        renderPct = 0;
        String line = gp(in) + "|" + gp(out);
        Diag.log("render " + line);
        long t0 = Diag.now();
        exec(line, (ok, msg) -> {
            renderPct = -1;
            Diag.log("render done ok=" + ok + " +" + (Diag.now() - t0) + "ms " + msg);
            cb.done(ok, ok ? out.getName() : msg);
        });
    }

    /** Host file under the glibc root -> the same file as a wine "Z:" path. */
    public String gp(File f) {
        String root = env.glibc.getAbsolutePath();
        String p = f.getAbsolutePath();
        if (p.startsWith(root)) p = p.substring(root.length());
        if (!p.startsWith("/")) p = "/" + p;
        return "Z:" + p.replace('/', '\\');
    }
}
