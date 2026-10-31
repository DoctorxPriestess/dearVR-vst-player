package com.vstplayer.core;

import android.content.Context;

import java.io.File;

/** Signature mirror for the PC compile check only. */
public final class Decode {

    public interface Cancel {
        boolean stopped();
    }

    public static final class Result {
        public final File wav;
        public final String error;

        private Result(File wav, String error) {
            this.wav = wav;
            this.error = error;
        }

        public boolean ok() {
            return wav != null;
        }
    }

    public static Result decode(Context ctx, File src, File dst, Cancel cancel) {
        return new Result(null, "stub");
    }
}
