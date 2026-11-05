package com.vstplayer.core;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** One plugin parameter as reported by PARAMS|. */
public class Param {
    /** What the host reports for a parameter with no finite step count (0x7FFFFFFF). */
    private static final int UNBOUNDED = 1_000_000;
    private static final Pattern NUMBER = Pattern.compile("[-+]?\\d+(?:[.,]\\d+)?");

    public final int index;
    public final String id;
    public final String name;
    public final String label;
    public final int steps;
    public float norm;
    public String text;

    public Param(int index, String id, String name, String label, int steps, float norm, String text) {
        this.index = index;
        this.id = id;
        this.name = name;
        this.label = label;
        this.steps = steps;
        this.norm = norm;
        this.text = text;
    }

    /**
     * The plugin's value text, with the unit appended only when the plugin did not already print it.
     *
     * <p>dearVR prints "0.0 dB" for its gains but reports {@code label="dB"} as well, which used to
     * come out as "0.0 dB dB".
     */
    public String valueText() {
        if (text == null || text.isEmpty()) return String.format(Locale.US, "%.2f", norm);
        if (label == null || label.isEmpty()) return text;
        String lower = text.toLowerCase(Locale.US);
        if (lower.endsWith(label.toLowerCase(Locale.US))) return text;
        return text + " " + label;
    }

    /** A knob with no practical step count: rendered as a slider. */
    public boolean continuous() {
        return steps <= 0 || steps >= UNBOUNDED;
    }

    /** Two positions only (on/off): the host reports steps == 2. */
    public boolean isSwitch() {
        return steps == 2;
    }

    /** A finite list the plugin names itself (3..N positions), e.g. a room or a mode. */
    public boolean isChoice() {
        return !continuous() && steps > 2;
    }

    /**
     * The number inside the plugin's own value text, i.e. -3.5 for "-3.5 dB".
     *
     * <p>Falls back to the normalised value when the text carries no number (an on/off
     * parameter says "ON"): such a row never asks for numeric input, so the fallback is only
     * ever used by the dialog's prefill in a case the dialog does not get offered.
     */
    public double numericValue() {
        Double v = number(text);
        return v == null ? norm : v;
    }

    /** Everything after the number in the value text, e.g. "dB" or "%", or "" when there is none. */
    public String unit() {
        if (text == null) return label == null ? "" : label;
        Matcher m = NUMBER.matcher(text);
        if (!m.find()) return label == null ? "" : label;
        String rest = text.substring(m.end()).trim();
        if (!rest.isEmpty()) return rest;
        return label == null ? "" : label;
    }

    /** First number in {@code s}, or null when there is none. */
    public static Double number(String s) {
        if (s == null) return null;
        Matcher m = NUMBER.matcher(s);
        if (!m.find()) return null;
        try {
            return Double.parseDouble(m.group().replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Normalised position of a discrete step, used by the option list and the probe. */
    public float normOfStep(int step) {
        if (steps <= 1) return 0f;
        return Math.max(0f, Math.min(1f, step / (float) (steps - 1)));
    }

    @Override public String toString() {
        return String.format(Locale.US, "%s(%s) steps=%d norm=%.3f text=%s", name, id, steps, norm, text);
    }
}
