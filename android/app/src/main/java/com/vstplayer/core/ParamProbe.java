package com.vstplayer.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Turns a parameter's own value text into a normalised position and back, by asking the plugin.
 *
 * <p>The host exposes only three things per parameter: the normalised value, the step count and the
 * plugin's formatted text for the *current* value. It does not expose a range, so "-3.5 dB" cannot be
 * converted arithmetically. Every conversion therefore goes through the guest: {@code SET|id|norm} is
 * answered with the text at that position, and a binary search finds the position that displays the
 * number the user typed. The same trick enumerates the named positions of a discrete parameter.
 *
 * <p>All work runs on one background thread: the line protocol is serialised anyway, and a probe
 * must not interleave with itself. Nothing here touches the UI thread.
 */
public final class ParamProbe {

    /** A probe round trip is a local pipe on a warm guest; anything past this is a stuck lane. */
    private static final long TIMEOUT_MS = 4000;
    /** Enough halvings to reach 1e-5 of the range for any sane parameter. */
    private static final int SEARCH_STEPS = 18;
    /** Option lists longer than this are not enumerable in a usable time. */
    private static final int MAX_STEPS = 256;

    private static final ExecutorService EX =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "param-probe");
                t.setDaemon(true);
                return t;
            });

    /** Discrete labels, keyed by "id/steps" so a plugin reload invalidates them. */
    private static final Map<String, String[]> STEP_CACHE = new ConcurrentHashMap<>();

    private ParamProbe() { }

    /** Cached labels for a discrete parameter, or null when it has not been enumerated yet. */
    public static String[] cachedSteps(Param p) {
        return p == null ? null : STEP_CACHE.get(key(p));
    }

    /**
     * Position whose text shows {@code target}.
     *
     * <p>The search assumes the value grows (or shrinks) monotonically with the normalised position,
     * which holds for every host-reported range this app supports. A plugin that steps instead of
     * sliding still lands on the closest displayed value because the comparison uses the text.
     */
    public static void normForText(Vst vst, Param p, double target, Consumer<Float> cb) {
        EX.execute(() -> {
            if (!vst.paramsIdle()) { cb.accept(null); return; }
            float lo = 0f, hi = 1f, best = p.norm;
            Double loV = textValue(vst, p.id, lo);
            Double hiV = textValue(vst, p.id, hi);
            if (loV == null || hiV == null) { cb.accept(null); return; }
            boolean decreasing = loV > hiV;
            for (int i = 0; i < SEARCH_STEPS; i++) {
                float mid = (lo + hi) / 2f;
                Double v = textValue(vst, p.id, mid);
                if (v == null) break;
                best = mid;
                if (Math.abs(v - target) <= tolerance(v, target)) break;
                // For a parameter whose value shrinks as the position grows, a value above the
                // target means the position has to grow, not shrink.
                boolean posMustGrow = decreasing ? (v > target) : (v < target);
                if (posMustGrow) lo = mid; else hi = mid;
                if (hi - lo < 1e-5f) break;
            }
            final float out = best;
            vst.setParamNow(p.id, out, t -> cb.accept(out));
        });
    }

    /** Every named position of a discrete parameter, restoring the value the plugin had before. */
    public static void stepTexts(Vst vst, Param p, Consumer<String[]> cb) {
        String[] cached = cachedSteps(p);
        if (cached != null) { cb.accept(cached); return; }
        EX.execute(() -> {
            if (!vst.paramsIdle() || p.steps <= 1 || p.steps > MAX_STEPS) { cb.accept(null); return; }
            String[] out = new String[p.steps];
            float before = p.norm;
            boolean ok = true;
            for (int i = 0; i < p.steps && ok; i++) {
                String t = textAt(vst, p.id, p.normOfStep(i));
                if (t == null) ok = false; else out[i] = t;
            }
            vst.setParamNow(p.id, before, null);
            if (ok) {
                STEP_CACHE.put(key(p), out);
                cb.accept(out);
            } else {
                cb.accept(null);
            }
        });
    }

    /** Value text at one position, for callers that only display it. */
    public static void textAt(Vst vst, String id, float norm, Consumer<String> cb) {
        EX.execute(() -> cb.accept(textAt(vst, id, norm)));
    }

    // ------------------------------------------------------------------ internals

    private static String textAt(Vst vst, String id, float norm) {
        if (!vst.paramsIdle()) return null;
        final CountDownLatch latch = new CountDownLatch(1);
        final String[] got = new String[1];
        vst.setParamProbe(id, norm, text -> { got[0] = text; latch.countDown(); });
        try {
            if (!latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return got[0];
    }

    private static Double textValue(Vst vst, String id, float norm) {
        return Param.number(textAt(vst, id, norm));
    }

    private static double tolerance(double a, double b) {
        return Math.max(1e-6, Math.max(Math.abs(a), Math.abs(b)) * 1e-4);
    }

    private static String key(Param p) {
        return p.id + "/" + p.steps;
    }
}
