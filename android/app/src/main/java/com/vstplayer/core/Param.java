package com.vstplayer.core;

/** One plugin parameter as reported by PARAMS|. */
public class Param {
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

    public String valueText() {
        if (text == null || text.isEmpty()) return String.format(java.util.Locale.US, "%.2f", norm);
        if (label == null || label.isEmpty()) return text;
        return text + " " + label;
    }
}
