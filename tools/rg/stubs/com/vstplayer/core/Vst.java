package com.vstplayer.core;

import java.io.File;

/** Signature mirror for the PC compile check only. */
public final class Vst {
    public static Vst get() {
        return new Vst();
    }

    public File inDir() {
        return null;
    }

    public File outDir() {
        return null;
    }
}
