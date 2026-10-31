package android.os;

/** Signature mirror for the PC compile check only. */
public class Handler {
    public Handler(Looper looper) {
    }

    public boolean post(Runnable r) {
        return true;
    }
}
