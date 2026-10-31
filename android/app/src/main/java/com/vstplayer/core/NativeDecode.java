package com.vstplayer.core;

import java.io.File;

/**
 * Self contained native ALAC / FLAC decoder.
 *
 * <p>The decoder is a small C library ({@code libvstdecode.so}) that demuxes the
 * MP4 container itself (or reads FLAC through dr_flac) and reconstructs PCM
 * without going through the platform codecs.  This avoids the SIGSEGV seen in
 * the system ALAC/FLAC decoders when the codec service is saturated and keeps
 * the full 16 / 20 / 24 / 32 bit precision of the source.
 *
 * <p>Decoded output is a little endian PCM WAV file with a plain 44 byte
 * header, the source sample rate, the source channel count and a container
 * depth of 16, 24 or 32 bits (20 bit ALAC is widened to 24 bits by a 4 bit
 * left shift so no information is lost).
 *
 * <p>All methods are allocation-light and never throw for ordinary failures:
 * {@link #decodeToWav(File, File, int)} returns a negative error code and
 * {@link #lastError()} carries a human readable reason.
 */
public final class NativeDecode {

    /** Native library name, see cpp/CMakeLists.txt. */
    private static final String LIBRARY = "vstdecode";

    private static final boolean LOADED;
    private static final String LOAD_ERROR;

    static {
        boolean ok = false;
        String err = null;
        try {
            System.loadLibrary(LIBRARY);
            ok = true;
        } catch (Throwable t) {
            // Missing library or an ABI mismatch: keep available() == false
            // instead of letting class initialisation throw.
            err = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        LOADED = ok;
        LOAD_ERROR = err;
    }

    private NativeDecode() {
    }

    /** True when the native decoder library was loaded successfully. */
    public static boolean available() {
        return LOADED;
    }

    /**
     * Decodes {@code src} (.m4a/.mp4 with ALAC, or .flac) to a PCM WAV file.
     *
     * @param maxSeconds stop after this many seconds, {@code <= 0} decodes all
     * @return 0 on success, or -1 (open), -2 (unsupported container/codec),
     *         -3 (corrupt/truncated bitstream), -4 (write failure)
     */
    public static int decodeToWav(File src, File dst, int maxSeconds) {
        if (src == null || dst == null) {
            return -1;
        }
        if (!LOADED) {
            return -1;
        }
        try {
            return decodeToWav(src.getAbsolutePath(), dst.getAbsolutePath(), maxSeconds);
        } catch (Throwable t) {
            // UnsatisfiedLinkError / OOM: report as an open failure.
            return -1;
        }
    }

    /** Native entry point: the JNI symbol is Java_com_vstplayer_core_NativeDecode_decodeToWav. */
    public static native int decodeToWav(String inPath, String outPath, int maxSeconds);

    /**
     * Error text for the most recent decode call, or the library load error
     * when the native library is unavailable. May return {@code null} before
     * the first call.
     */
    public static String lastError() {
        if (!LOADED) {
            return LOAD_ERROR;
        }
        try {
            return getLastError0();
        } catch (Throwable t) {
            return LOAD_ERROR;
        }
    }

    private static native String getLastError0();
}
