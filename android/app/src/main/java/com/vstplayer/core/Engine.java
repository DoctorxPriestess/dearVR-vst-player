package com.vstplayer.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Drives the wine+box64 renderer through proot and speaks its line protocol. */
public class Engine {
    public interface Listener {
        void onLog(String line);
        void onReady();
        void onExit(int code);
    }

    private final Env env;
    private Process proc;
    private OutputStream stdin;
    private Thread reader;
    private volatile Listener listener;
    private volatile boolean ready;
    private long tSpawn;
    private long tBoot;
    private final int affinityRank;

    /** Every engine instance that may be holding guest processes; used to avoid reaping a peer. */
    private static final java.util.Set<Engine> LIVE =
            java.util.Collections.synchronizedSet(new java.util.HashSet<Engine>());
    private boolean sawFirstLine;

    /** proot seccomp acceleration; measured on device before changing the default. */
    static boolean PROOT_SECCOMP = true;

    /** Debug override for the affinity mask: 0 = never pin, >0 = force that mask. */
    static int AFFINITY_OVERRIDE = -1;
    /** -1 = not yet verified, 1 = this device honours our mask, 0 = pinning unsupported. */
    private static volatile int AFFINITY_MODE = -1;
    /** Minimum interval between two automatic /proc sweeps, in milliseconds. */
    private static final long REAP_MIN_INTERVAL_MS = 2000L;
    /** When the last automatic sweep ran ({@link Diag#now()}). */
    private static long lastReapAt;
    /**
     * True between "about to fork" and "process running". A peer's stray sweep must treat such an
     * engine as live, or it kills the guest that is still exec'ing.
     */
    private volatile boolean spawning;
    /** Mask handed to taskset for the current process, 0 when no pin was requested. */
    private static volatile int AFFINITY_PENDING;

    public Engine(Env env) {
        this(env, 0);
    }

    /**
     * @param affinityRank 0 pins to the fastest core, 1 to the next one — two render engines must
     *                     not share a core or the batch stops scaling.
     */
    public Engine(Env env, int affinityRank) {
        this.env = env;
        this.affinityRank = affinityRank;
    }

    public boolean isRunning() {
        return alive(proc);
    }

    /**
     * {@link Process#isAlive()} is API 26 while minSdk is 24: on Android 7.x the direct call
     * throws {@code NoSuchMethodError}. Polling {@code exitValue()} answers the same question on
     * every version, at the price of one exception on the "still running" path.
     */
    private static boolean alive(Process p) {
        if (p == null) return false;
        try {
            p.exitValue();
            return false;
        } catch (IllegalThreadStateException running) {
            return true;
        }
    }

    /** True once the engine has written anything to stdout. */
    public boolean sawOutput() {
        return sawFirstLine;
    }

    /**
     * Wine's helpers (wineserver, services.exe, rpcss, ...) double-fork and escape the app's
     * process group, so they outlive force-stop as PPID=1 orphans: they keep burning CPU and
     * make the next boot look artificially warm. Kill the ones that belong to no live engine.
     */
    public synchronized void reapStrays() {
        Process p = proc;
        if (alive(p)) return;
        synchronized (LIVE) {
            for (Engine other : LIVE) {
                // "Owns guests" covers a lane that is still between fork and exec: its process
                // exists but isRunning() cannot see it yet.
                if (other != this && other.ownsGuests()) return;
            }
        }
        killStrayGuests();
    }

    /** True when this engine has or is about to have a guest process of its own. */
    private boolean ownsGuests() {
        return spawning || isRunning();
    }

    /**
     * Kills every wine/proot/box64 process owned by this app that is no longer part of a live
     * engine. Also used before a re-extraction, which would otherwise fail with ETXTBSY while an
     * exiting guest still has the old proot-loader mapped.
     */
    public static void killStrayGuests() {
        killStrayGuests(false);
    }

    /**
     * @param force true for the callers that cannot tolerate a skipped sweep (re-extraction, the
     *              boot retry); otherwise repeated calls within {@link #REAP_MIN_INTERVAL_MS} are
     *              dropped, because the sweep opens every {@code /proc} entry and eight lanes
     *              starting at once each used to run it
     */
    public static synchronized void killStrayGuests(boolean force) {
        long now = Diag.now();
        if (!force && now - lastReapAt < REAP_MIN_INTERVAL_MS) return;
        lastReapAt = now;
        synchronized (LIVE) {
            for (Engine e : LIVE) {
                if (e.spawning) return;   // a lane is being born; its guest is not a stray
            }
        }
        File[] entries = new File("/proc").listFiles();
        if (entries == null) return;
        final int me = android.os.Process.myPid();
        final int uid = android.os.Process.myUid();
        int killed = 0;
        for (File d : entries) {
            String name = d.getName();
            if (name.isEmpty() || !Character.isDigit(name.charAt(0))) continue;
            final int pid;
            try {
                pid = Integer.parseInt(name);
            } catch (NumberFormatException e) {
                continue;
            }
            if (pid == me) continue;
            if (!isOwnedGuest(pid, uid)) continue;
            try {
                android.system.Os.kill(pid, 9);
                killed++;
            } catch (Exception ignored) {
            }
        }
        if (killed > 0) Diag.log("reaped " + killed + " stray guest processes");
    }

    private static boolean isOwnedGuest(int pid, int uid) {
        String comm = firstLine("/proc/" + pid + "/comm");
        if (comm == null) return false;
        boolean known = comm.startsWith("wine") || comm.startsWith("proot")
                || comm.startsWith("box64") || comm.startsWith("vstrender");
        if (!known) return false;
        try (BufferedReader br = new BufferedReader(new java.io.FileReader("/proc/" + pid + "/status"))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("Uid:")) {
                    String[] f = line.substring(4).trim().split("\s+");
                    return f.length > 0 && Integer.parseInt(f[0]) == uid;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static String firstLine(String path) {
        try (BufferedReader br = new BufferedReader(new java.io.FileReader(path))) {
            return br.readLine();
        } catch (Exception e) {
            return null;
        }
    }

    public boolean isReady() {
        return isRunning() && ready;
    }

    /** Guest work / in / out directories; {@link Env} owns the layout and caches them. */
    public File workDir() {
        return env.workDir();
    }

    public File inDir() {
        return env.inDir();
    }

    public File outDir() {
        return env.outDir();
    }

    public synchronized void start(Listener l) throws IOException {
        if (isRunning()) return;
        listener = l;
        ready = false;
        tBoot = Diag.now();

        fixWineDrives();
        env.writeRunScript();
        String proot = new File(env.bin, "proot").getAbsolutePath();
        List<String> cmd = new ArrayList<>();
        final int affinity = AFFINITY_MODE == 0 ? 0 : engineAffinity(affinityRank);
        AFFINITY_PENDING = affinity;
        if (affinity > 0) {
            // One core measured 15% faster than all eight for a 60 s render (8.34 s vs 9.84 s)
            // and left boot time unchanged. The mask digits must be hexadecimal because that is
            // how toybox' taskset parses them; verifyAffinity() disables pinning if a device
            // disagrees, so a foreign SoC can never end up silently pinned to a little core.
            cmd.add("/system/bin/taskset");
            cmd.add(Integer.toHexString(affinity));
            Diag.log("engine affinity cpu" + Integer.numberOfTrailingZeros(affinity));
        }
        cmd.add(proot);
        cmd.add("-r");
        cmd.add(env.glibc.getAbsolutePath());
        cmd.add("-0");
        cmd.add("-b");
        cmd.add("/dev");
        cmd.add("-b");
        cmd.add("/proc");
        cmd.add("-b");
        cmd.add("/sys");
        cmd.add("-b");
        cmd.add(env.tmp.getAbsolutePath() + ":/tmp");
        // wine was built against the Termux tmp path
        cmd.add("-b");
        cmd.add(env.tmp.getAbsolutePath() + ":/data/data/com.termux/files/usr/tmp");
        cmd.add("-w");
        cmd.add("/");
        cmd.add("/lib/ld-linux-aarch64.so.1");
        cmd.add("--library-path");
        cmd.add("/lib:/usr/lib");
        cmd.add("/bin/bash");
        cmd.add("/opt/vstenv/run.sh");

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.directory(env.bin);
        Map<String, String> e = pb.environment();
        applyBootConf(e);
        // proot's seccomp filter is the fast path for syscall translation; it is only
        // turned off for kernels that reject the filter (kept as a switch for A/B runs).
        if (!PROOT_SECCOMP) e.put("PROOT_NO_SECCOMP", "1");
        e.put("PROOT_LOADER", new File(env.bin, "proot-loader").getAbsolutePath());
        e.put("PROOT_TMP_DIR", env.tmp.getAbsolutePath());
        e.put("LD_LIBRARY_PATH", env.bin.getAbsolutePath());
        e.put("HOME", "/root");
        e.put("TERM", "dumb");

        reapStrays();
        tSpawn = Diag.now();
        sawFirstLine = false;
        // Join LIVE before the fork, not after: between pb.start() and LIVE.add(this) a peer's
        // stray sweep saw no owner for this guest and killed it, so a lane failing at random also
        // tripped the "no engine output" retry that turns proot's seccomp path off for the process.
        synchronized (LIVE) {
            spawning = true;
            LIVE.add(this);
        }
        try {
            proc = pb.start();
        } catch (IOException | RuntimeException ex) {
            synchronized (LIVE) {
                LIVE.remove(this);
            }
            spawning = false;
            throw ex;
        }
        spawning = false;
        Diag.log("engine spawn +" + (Diag.now() - tBoot) + "ms");
        stdin = proc.getOutputStream();
        reader = new Thread(this::pump, "engine-reader");
        reader.setDaemon(true);
        reader.start();
    }

    /**
     * Debug-only escape hatch: {@code files/boot.conf} lines of {@code KEY=VALUE} are applied to
     * the engine environment, so launch-tuning can be A/B tested with adb push instead of a
     * 368 MB reinstall. Ignored in non-debuggable builds.
     */
    private void applyBootConf(Map<String, String> e) {
        if (!env.isDebuggable()) return;
        File f = new File(env.root, "boot.conf");
        if (!f.isFile()) return;
        try (BufferedReader br = new BufferedReader(new java.io.FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int i = line.indexOf('=');
                if (i <= 0) continue;
                String k = line.substring(0, i).trim();
                String v = line.substring(i + 1).trim();
                if ("engine.affinity".equals(k)) {
                    try {
                        AFFINITY_OVERRIDE = Integer.parseInt(v);
                        Diag.log("boot.conf affinity override " + AFFINITY_OVERRIDE);
                    } catch (NumberFormatException nfe) {
                        Diag.log("boot.conf affinity ignored: " + v);
                    }
                    continue;
                }
                if (v.isEmpty()) e.remove(k); else e.put(k, v);
            }
            Diag.log("boot.conf applied");
        } catch (Exception ex) {
            Diag.log("boot.conf read failed: " + ex);
        }
    }

    /**
     * CPU mask for the guest tree, or 0 for "do not pin".
     *
     * Pinning one core measured 15% faster than using all eight for a 60 s render, and two
     * engines pinned to different cores render a batch at 1.9x. Ranks are ordered by
     * (max frequency desc, core index desc) so rank 0 and rank 1 never share a prime core;
     * a topology without readable frequencies simply goes unpinned.
     */
    static int engineAffinity(int rank) {
        if (AFFINITY_OVERRIDE >= 0) return rank == 0 ? AFFINITY_OVERRIDE : 0;
        if (rank < 0) return 0;
        int cpus = Runtime.getRuntime().availableProcessors();
        if (cpus < 2 || cpus > 32) return 0;
        int[] order = new int[cpus];
        int n = 0;
        for (int cpu = 0; cpu < cpus; cpu++) {
            if (maxFreqKhz(cpu) > 0) order[n++] = cpu;
        }
        if (rank >= n) return 0;
        long[] freq = new long[cpus];
        for (int i = 0; i < n; i++) freq[order[i]] = maxFreqKhz(order[i]);
        for (int i = 0; i < n; i++) {
            int best = i;
            for (int j = i + 1; j < n; j++) {
                int a = order[j], b = order[best];
                if (freq[a] > freq[b] || (freq[a] == freq[b] && a > b)) best = j;
            }
            int t = order[i];
            order[i] = order[best];
            order[best] = t;
        }
        return 1 << order[rank];
    }

    /**
     * Confirms that this kernel parsed our hexadecimal mask the way we meant it. A device whose
     * taskset expects decimal would land on a different core set, so pinning is switched off for
     * the rest of the process instead of silently running slower.
     */
    private void verifyAffinity() {
        int mask = AFFINITY_PENDING;
        if (affinityRank != 0 || mask <= 0 || AFFINITY_MODE != -1 || AFFINITY_OVERRIDE >= 0) return;
        int core = Integer.numberOfTrailingZeros(mask);
        int pid = findGuestPid("proot");
        if (pid <= 0) {
            Diag.log("affinity not verified (no engine pid)");
            return;
        }
        String got = statusField(pid, "Cpus_allowed_list");
        if (got == null) {
            Diag.log("affinity not verified (no status)");
            return;
        }
        got = got.trim();
        if (got.equals(String.valueOf(core))) {
            AFFINITY_MODE = 1;
            Diag.log("affinity verified: cpu" + core);
        } else {
            AFFINITY_MODE = 0;
            Diag.log("affinity rejected (allowed=" + got + "), pinning disabled");
        }
    }

    /** First process of ours whose comm starts with the prefix (the guest runs as our uid). */
    private static int findGuestPid(String commPrefix) {
        final String uid = String.valueOf(android.os.Process.myUid());
        File[] entries = new File("/proc").listFiles();
        if (entries == null) return -1;
        for (File d : entries) {
            String name = d.getName();
            if (name.isEmpty() || !Character.isDigit(name.charAt(0))) continue;
            final int pid;
            try {
                pid = Integer.parseInt(name);
            } catch (NumberFormatException e) {
                continue;
            }
            String comm = firstLine("/proc/" + pid + "/comm");
            if (comm == null || !comm.startsWith(commPrefix)) continue;
            String uidLine = statusField(pid, "Uid");
            if (uidLine == null) continue;
            String t = uidLine.trim();
            int end = 0;
            while (end < t.length() && !Character.isWhitespace(t.charAt(end))) end++;
            if (uid.equals(t.substring(0, end))) return pid;
        }
        return -1;
    }

    private static String statusField(int pid, String key) {
        try (BufferedReader br = new BufferedReader(new java.io.FileReader("/proc/" + pid + "/status"))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith(key + ":")) return line.substring(key.length() + 1);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Number of the single core we pin to, or -1 when pinning is off/unsupported. */
    static int pinnedCore() {
        int mask = AFFINITY_PENDING;
        return mask > 0 ? Integer.numberOfTrailingZeros(mask) : -1;
    }

    private static long maxFreqKhz(int cpu) {
        try (BufferedReader br = new BufferedReader(new java.io.FileReader(
                "/sys/devices/system/cpu/cpu" + cpu + "/cpufreq/cpuinfo_max_freq"))) {
            return Long.parseLong(br.readLine().trim());
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Re-run the Z: repair on demand. A brand-new wine prefix does not exist yet when the
     * engine first starts, so the very first boot runs with a wrong Z: and the plugin path
     * cannot resolve; the caller retries once after wine has created the prefix.
     */
    public void repairDrives() {
        fixWineDrives();
    }

    /** The bundled wine maps Z: to the Termux root; point it at the guest root instead. */
    private void fixWineDrives() {
        File dev = new File(env.winePrefix, "dosdevices");
        // A prefix that does not exist yet is built by wine itself; seeding dosdevices early
        // makes wineboot skip drive_c and the first boot then never becomes usable. The
        // caller instead repairs the mapping after that first boot and retries once.
        if (!dev.isDirectory()) return;
        File z = new File(dev, "z:");
        try {
            String target = android.system.Os.readlink(z.getAbsolutePath());
            if (!"/".equals(target)) {
                z.delete();
                android.system.Os.symlink("/", z.getAbsolutePath());
                Diag.log("wine z: -> / (was " + target + ")");
            }
        } catch (Exception e) {
            Diag.log("wine drive fix skipped: " + e);
        }
    }

    private void pump() {
        Process p = proc;
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (!sawFirstLine) {
                    sawFirstLine = true;
                    Diag.log("engine first out +" + (Diag.now() - tSpawn) + "ms: " + line);
                    // The guest has definitely exec'd by now, so taskset has applied its mask.
                    verifyAffinity();
                }
                if (line.trim().equals("READY")) {
                    ready = true;
                    Diag.log("engine READY +" + (Diag.now() - tSpawn) + "ms (boot "
                            + (Diag.now() - tBoot) + "ms)");
                    Listener l = listener;
                    if (l != null) l.onReady();
                }
                Listener l = listener;
                if (l != null) l.onLog(line);
            }
        } catch (IOException ignored) {
        }
        int code = -1;
        try {
            code = p.waitFor();
        } catch (InterruptedException ignored) {
        }
        synchronized (this) {
            // A restart may already have installed a newer process; its reader owns the state.
            if (proc != p) return;
            ready = false;
            LIVE.remove(this);
        }
        reapStrays();
        Listener l = listener;
        if (l != null) l.onExit(code);
    }

    public synchronized void send(String line) throws IOException {
        if (stdin == null) throw new IOException("engine not started");
        stdin.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        stdin.flush();
    }

    public synchronized void stop() {
        try {
            if (stdin != null) send("QUIT");
        } catch (IOException ignored) {
        }
        Process p = proc;
        proc = null;
        stdin = null;
        if (p != null) p.destroy();
    }
}
