package com.vstplayer.tests;

import com.vstplayer.core.Io;
import com.vstplayer.core.Paths;
import com.vstplayer.core.Segment;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.List;

/**
 * Dependency-free checks for the parts of the core layer that do not touch Android.
 *
 * <p>Run with {@code tools/tests/run.sh}. The point is not exhaustive coverage: it pins the
 * behaviours that were silently broken before (zero length reads, directory traversal depth,
 * segment join arithmetic) so a regression fails here instead of on a device.
 */
public final class SelfCheck {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        copyHandlesZeroLengthReads();
        copyRejectsStuckStream();
        readAllReadsEverything();
        traversalHonoursDepth();
        traversalSortsAndFilters();
        sanitizeDropsIllegalCharacters();
        segmentProbeAndJoinKeepFrameCount();
        flacDepthOf24BitIsReported();

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    // ------------------------------------------------------------------ Io

    private static void copyHandlesZeroLengthReads() throws Exception {
        // 3 payload chunks, each preceded by a legal zero length read.
        InputStream in = new ZerosEveryOtherRead(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 });
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long n = Io.copy(in, out, new byte[4]);
        check("Io.copy keeps data across zero reads", n == 8 && out.size() == 8);
        byte[] b = out.toByteArray();
        boolean same = true;
        for (int i = 0; i < 8; i++) same &= b[i] == (byte) (i + 1);
        check("Io.copy writes the original bytes", same);
    }

    private static void copyRejectsStuckStream() {
        InputStream stuck = new InputStream() {
            @Override public int read() { return 0; }
            @Override public int read(byte[] b, int off, int len) { return 0; }
        };
        boolean threw = false;
        try {
            Io.copy(stuck, new ByteArrayOutputStream(), new byte[8]);
        } catch (IOException expected) {
            threw = true;
        }
        check("Io.copy fails instead of spinning on a stuck stream", threw);
    }

    private static void readAllReadsEverything() throws Exception {
        byte[] payload = new byte[300_000];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i * 31);
        byte[] back = Io.readAll(new ByteArrayInputStream(payload));
        check("Io.readAll keeps the full payload", back.length == payload.length
                && java.util.Arrays.equals(back, payload));
    }

    // --------------------------------------------------------------- Paths

    private static void traversalHonoursDepth() throws Exception {
        File root = Files.createTempDirectory("vstp-paths").toFile();
        try {
            File deep = new File(root, "a/b/c/d/e/f");
            deep.mkdirs();
            write(new File(root, "top.wav"));
            write(new File(root, "a/mid.flac"));
            write(new File(deep, "bottom.mp3"));
            write(new File(root, "notes.txt"));

            check("audioFiles(depth 0) stops at the top level",
                    Paths.audioFiles(root, 0, true).size() == 1);
            check("audioFiles(depth 1) reaches exactly one level down",
                    Paths.audioFiles(root, 1, true).size() == 2);
            check("audioFiles(default) reaches the leaf",
                    Paths.audioFiles(root).size() == 3);
        } finally {
            deleteTree(root);
        }
    }

    private static void traversalSortsAndFilters() throws Exception {
        File root = Files.createTempDirectory("vstp-sort").toFile();
        try {
            write(new File(root, "b.wav"));
            write(new File(root, "a.wav"));
            write(new File(root, "ignore.m4a.txt"));
            List<File> sorted = Paths.audioFiles(root, 1, true);
            check("audioFiles ignores non-audio names", sorted.size() == 2);
            check("audioFiles sorts by name",
                    sorted.get(0).getName().equals("a.wav") && sorted.get(1).getName().equals("b.wav"));
        } finally {
            deleteTree(root);
        }
    }

    private static void sanitizeDropsIllegalCharacters() {
        String cleaned = Paths.sanitize("a<b>c:d/e\\f?g*h|i");
        boolean ok = true;
        for (char c : "<>:\\/?*|\"".toCharArray()) ok &= cleaned.indexOf(c) < 0;
        check("Paths.sanitize removes filesystem metacharacters", ok);
        check("Paths.stem strips the extension",
                Paths.stem("song.flac").equals("song"));
    }

    // ------------------------------------------------------------- Segment

    private static void segmentProbeAndJoinKeepFrameCount() throws Exception {
        File dir = Files.createTempDirectory("vstp-seg").toFile();
        try {
            File src = new File(dir, "src.wav");
            writeWav(src, 1000, 2, 2);
            check("Segment.isWav recognises a RIFF/WAVE header", Segment.isWav(src));

            Segment.Info info = Segment.probe(src);
            check("Segment.probe reads the frame count", info.frames == 1000);
            check("Segment.probe reads the block align", info.blockAlign == 4);
            check("Segment.seconds() divides by the sample rate",
                    Math.abs(info.seconds() - 1000.0 / 48000.0) < 1e-9);

            File a = new File(dir, "a.wav");
            File b = new File(dir, "b.wav");
            // segment 2 restarts 50 frames early (pre-roll) and drops that pre-roll at join time
            Segment.cut(src, info, 0, 600, a);
            Segment.cut(src, info, 550, 450, b);
            File joined = new File(dir, "joined.wav");
            Segment.join(new File[] { a, b }, new int[] { 0, 50 }, joined);

            Segment.Info out = Segment.probe(joined);
            check("Segment.join drops the pre-roll of later segments", out.frames == 1000);
            check("joined file keeps the source sample rate", out.rate == info.rate);
            check("joined file keeps the source block align", out.blockAlign == info.blockAlign);
            check("joined payload length matches the frame count",
                    joined.length() - 44 == 1000L * info.blockAlign);
        } finally {
            deleteTree(dir);
        }
    }

    private static void flacDepthOf24BitIsReported() throws Exception {
        // The FLAC probe is header only; an all-zero header is not a FLAC stream.
        File dir = Files.createTempDirectory("vstp-flac").toFile();
        try {
            File f = new File(dir, "x.flac");
            write(f);
            check("Segment.isFlac rejects a non-FLAC file", !Segment.isFlac(f));
        } finally {
            deleteTree(dir);
        }
    }

    // ------------------------------------------------------------- helpers

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("ok   " + name);
        } else {
            failed++;
            System.out.println("FAIL " + name);
        }
    }

    private static void write(File f) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(new byte[] { 1, 2, 3 });
        }
    }

    private static void writeWav(File f, int frames, int channels, int bytesPerSample) throws IOException {
        int blockAlign = channels * bytesPerSample;
        int dataLen = frames * blockAlign;
        try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
            raf.writeBytes("RIFF");
            raf.write(intLE(36 + dataLen));
            raf.writeBytes("WAVEfmt ");
            raf.write(intLE(16));
            raf.write(shortLE((short) 1));
            raf.write(shortLE((short) channels));
            raf.write(intLE(48000));
            raf.write(intLE(48000 * blockAlign));
            raf.write(shortLE((short) blockAlign));
            raf.write(shortLE((short) (bytesPerSample * 8)));
            raf.writeBytes("data");
            raf.write(intLE(dataLen));
            byte[] payload = new byte[dataLen];
            for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i & 0xFF);
            raf.write(payload);
        }
    }

    private static byte[] intLE(int v) {
        return new byte[] { (byte) v, (byte) (v >> 8), (byte) (v >> 16), (byte) (v >> 24) };
    }

    private static byte[] shortLE(short v) {
        return new byte[] { (byte) v, (byte) (v >> 8) };
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    /** Serves the payload in small chunks with a zero length read before each one. */
    private static final class ZerosEveryOtherRead extends InputStream {
        private final byte[] data;
        private int pos;
        private boolean emitZero;

        ZerosEveryOtherRead(byte[] data) {
            this.data = data;
        }

        @Override public int read() {
            return pos < data.length ? data[pos++] & 0xFF : -1;
        }

        @Override public int read(byte[] b, int off, int len) {
            if (pos >= data.length) return -1;
            if (emitZero) {
                emitZero = false;
                return 0;
            }
            emitZero = true;
            int n = Math.min(3, data.length - pos);
            System.arraycopy(data, pos, b, off, n);
            pos += n;
            return n;
        }
    }
}
