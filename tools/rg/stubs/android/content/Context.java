package android.content;

import java.io.File;

/** Signature mirror for the PC compile check only; never part of the Android build. */
public class Context {
    public static final int MODE_PRIVATE = 0;

    public SharedPreferences getSharedPreferences(String name, int mode) {
        return null;
    }

    public File getFilesDir() {
        return null;
    }

    public File getCacheDir() {
        return null;
    }
}
