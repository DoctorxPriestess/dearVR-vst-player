package android.content;

/** Signature mirror for the PC compile check only. */
public interface SharedPreferences {
    float getFloat(String key, float def);

    boolean getBoolean(String key, boolean def);

    Editor edit();

    interface Editor {
        Editor putFloat(String key, float value);

        Editor putBoolean(String key, boolean value);

        void apply();
    }
}
