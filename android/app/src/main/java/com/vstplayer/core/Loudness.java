package com.vstplayer.core;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;

/**
 * EBU R128 / ITU-R BS.1770-4 integrated loudness and peak meter for PCM WAV files.
 *
 * <p>Pure Java, no Android APIs: the same class runs on the device and in the PC verification
 * harness under {@code tools/rg}. Only {@code java.io} is used, so it is safe on minSdk 24.
 *
 * <p>The meter is streaming: it reads the {@code data} chunk in blocks, applies the two stage
 * K-weighting filter (high shelf then RLB high pass), accumulates 100 ms sub blocks of weighted
 * energy and turns every four consecutive sub blocks into one 400 ms block with 75 % overlap.
 * Gating follows BS.1770-4: an absolute gate at -70 LUFS and a relative gate 10 LU below the
 * loudness of the blocks that survived the absolute gate.
 *
 * <p>Buffer sizes come from the WAV header but are never trusted: a chunk whose declared size runs
 * past EOF is clamped to the real file length, so a truncated or lying RIFF header cannot produce
 * out of bounds reads. 24 bit samples are unpacked from their 3 packed bytes; the rarer 24 bit in
 * a 4 byte container layout is normalised by the container width, which is the same scaling a
 * sign extended 24 bit value gets.
 */
public final class Loudness {

    /** Default ReplayGain target used by this app. */
    public static final double DEFAULT_TARGET_LUFS = -18.0;
    /** BS.1770-4 absolute gate. */
    public static final double ABSOLUTE_GATE_LUFS = -70.0;
    /** BS.1770-4 relative gate, in LU below the absolutely gated loudness. */
    public static final double RELATIVE_GATE_LU = -10.0;
    /** Oversampling factor for the optional true peak meter. */
    public static final int TRUE_PEAK_FACTOR = 4;
    /** gainDb() never suggests more than this much boost or cut. */
    public static final int MAX_GAIN_DB = 30;

    /** Offset that turns BS.1770 weighted energy into LUFS. */
    private static final double LUFS_OFFSET = -0.691;
    /** 400 ms block = four 100 ms sub blocks. */
    private static final int SUB_BLOCKS = 4;
    private static final double SUB_BLOCK_SEC = 0.1;
    private static final int READ_FRAMES = 8192;
    /** 12 taps per phase, 48 tap prototype, Blackman windowed sinc. */
    private static final int TP_TAPS = 12;

    private static final Charset ASCII = Charset.forName("ISO-8859-1");

    // BS.1770-4 analog prototypes: the published 48 kHz coefficient table is the bilinear
    // transform of exactly these numbers, so re-deriving per sample rate is legitimate and the
    // 48 kHz result reproduces the table bit for bit (checked in tools/rg).
    private static final double SHELF_F0 = 1681.974450955533;
    private static final double SHELF_GAIN_DB = 3.999843853973347;
    private static final double SHELF_Q = 0.7071752369554196;
    private static final double SHELF_VB_EXP = 0.4996667741545416;
    private static final double HPF_F0 = 38.13547087602444;
    private static final double HPF_Q = 0.5003270373238773;

    private Loudness() {
    }

    /** Cooperative progress/cancel hook. Called from the analysing thread. */
    public interface Progress {
        /** @param pct 0..100, only invoked when the integer percentage changes. */
        void onPercent(int pct);

        /** Polled once per read block; returning true stops the analysis early. */
        boolean cancelled();
    }

    /** Analysis result. {@code lufs} is NaN when no block passed the gates. */
    public static final class Result {
        /** Integrated loudness in LUFS, or NaN when the file is too short or below the gate. */
        public final double lufs;
        /** Sample peak in dBFS (0 dBFS = full scale of the container). */
        public final double peakDbfs;
        /** 4x oversampled true peak in dBFS, NaN when it was not requested. */
        public final double truePeakDbfs;
        public final long frames;
        public final double seconds;
        public final int sampleRate;
        public final int channels;
        public final int bits;
        /** True when the {@link Progress} hook aborted the run; lufs is NaN then. */
        public final boolean cancelled;

        Result(double lufs, double peakDbfs, double truePeakDbfs, long frames, double seconds,
               int sampleRate, int channels, int bits, boolean cancelled) {
            this.lufs = lufs;
            this.peakDbfs = peakDbfs;
            this.truePeakDbfs = truePeakDbfs;
            this.frames = frames;
            this.seconds = seconds;
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.bits = bits;
            this.cancelled = cancelled;
        }

        /** No gated block: silence, an aborted run, or less than 400 ms of audio. */
        public boolean silent() {
            return Double.isNaN(lufs);
        }

        /**
         * ReplayGain correction in whole dB: {@code target - integrated}, clamped to
         * +/-{@link #MAX_GAIN_DB}. Returns 0 when there is no usable measurement.
         */
        public int gainDb(double target) {
            if (Double.isNaN(lufs) || Double.isInfinite(lufs)) return 0;
            double g = target - lufs;
            if (g > MAX_GAIN_DB) g = MAX_GAIN_DB;
            if (g < -MAX_GAIN_DB) g = -MAX_GAIN_DB;
            return (int) Math.round(g);
        }
    }

    /** Convenience: analyse with sample peak only and no progress reporting. */
    public static Result analyzeWav(File f) throws IOException {
        return analyzeWav(f, null, false);
    }

    /** Analyse with the usual API shape; sample peak only, no true peak. */
    public static Result analyzeWav(File f, Progress p) throws IOException {
        return analyzeWav(f, p, false);
    }

    /**
     * Full entry point.
     *
     * @param truePeak also run the 4x oversampling true peak meter (about 3x the CPU cost)
     */
    public static Result analyzeWav(File f, Progress p, boolean truePeak) throws IOException {
        if (f == null || !f.isFile()) throw new IOException("no such file: " + f);
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            Wav wav = parseWav(raf, raf.length(), f);
            Meter meter = new Meter();
            meter.configure(wav.sampleRate, wav.channels, wav.containerBytes, wav.blockAlign,
                    wav.channelMask, truePeak);
            meter.feed(raf, wav.dataOffset, wav.dataBytes, p);
            return meter.result(wav.bits);
        }
    }

    // ---------------------------------------------------------------- WAV container

    private static final class Wav {
        int channels;
        int sampleRate;
        int bits;
        /** Bytes actually occupied by one sample (may exceed bits/8 for padded 24 bit). */
        int containerBytes;
        int blockAlign;
        /** WAVE_FORMAT_EXTENSIBLE channel mask, 0 when absent. */
        long channelMask;
        long dataOffset;
        long dataBytes;
    }

    private static Wav parseWav(RandomAccessFile raf, long len, File f) throws IOException {
        if (len < 12) throw new IOException("too short for RIFF: " + f);
        byte[] head = new byte[12];
        raf.seek(0);
        raf.readFully(head);
        if (!tag(head, 0, "RIFF") || !tag(head, 8, "WAVE")) {
            throw new IOException("not a RIFF/WAVE file: " + f.getName());
        }
        Wav wav = new Wav();
        boolean haveFmt = false;
        long pos = 12;
        while (pos + 8 <= len) {
            raf.seek(pos);
            byte[] ch = new byte[8];
            if (raf.read(ch) != 8) break;
            String id = new String(ch, 0, 4, ASCII);
            long declared = u32(ch, 4);
            long body = pos + 8;
            long avail = len - body;
            if (avail < 0) break;
            // RIFF sizes are written by any number of broken tools; clamp instead of trusting.
            long size = Math.min(declared, avail);
            if ("fmt ".equals(id)) {
                byte[] fmt = new byte[(int) Math.min(size, 64)];
                raf.seek(body);
                raf.readFully(fmt);
                readFmt(fmt, wav);
                haveFmt = true;
            } else if ("data".equals(id)) {
                if (!haveFmt) throw new IOException("data chunk before fmt chunk: " + f.getName());
                wav.dataOffset = body;
                wav.dataBytes = size;
                return checked(wav, f);
            }
            long next = body + size;
            if ((size & 1L) != 0L) next++; // RIFF word alignment
            if (next <= pos) break;        // defensive: never spin on a zero length chunk
            pos = next;
        }
        throw new IOException("no data chunk: " + f.getName());
    }

    private static Wav checked(Wav wav, File f) throws IOException {
        if (wav.channels <= 0 || wav.sampleRate <= 0) {
            throw new IOException("bad fmt chunk: " + f.getName());
        }
        if (wav.containerBytes <= 0 || wav.blockAlign < wav.channels * wav.containerBytes) {
            throw new IOException("bad block alignment: " + f.getName());
        }
        if (wav.dataBytes <= 0) throw new IOException("empty data chunk: " + f.getName());
        return wav;
    }

    private static void readFmt(byte[] b, Wav wav) throws IOException {
        if (b.length < 16) throw new IOException("fmt chunk too small");
        int tag = u16(b, 0);
        int channels = u16(b, 2);
        long rate = u32(b, 4);
        int blockAlign = u16(b, 12);
        int bits = u16(b, 14);
        int sub = tag;
        long mask = 0;
        if (tag == 0xFFFE) {
            if (b.length < 40) throw new IOException("truncated WAVE_FORMAT_EXTENSIBLE fmt chunk");
            mask = u32(b, 20);
            sub = u16(b, 24);
        }
        if (sub != 1) {
            throw new IOException("unsupported WAV sample format " + sub
                    + (sub == 3 ? " (IEEE float)" : "") + "; only integer PCM is analysable");
        }
        if (bits != 16 && bits != 24 && bits != 32) {
            throw new IOException("unsupported WAV bit depth: " + bits);
        }
        if (blockAlign <= 0 || blockAlign % channels != 0) {
            throw new IOException("inconsistent block align " + blockAlign + " for " + channels + " channels");
        }
        wav.channels = channels;
        wav.sampleRate = (int) Math.min(rate, Integer.MAX_VALUE);
        wav.bits = bits;
        wav.blockAlign = blockAlign;
        wav.containerBytes = blockAlign / channels;
        wav.channelMask = mask;
        if (wav.containerBytes < 2 || wav.containerBytes > 4) {
            throw new IOException("unsupported sample container: " + wav.containerBytes + " bytes");
        }
        if (wav.containerBytes * 8 < bits) {
            throw new IOException("bit depth " + bits + " does not fit " + wav.containerBytes + " bytes");
        }
    }

    // ---------------------------------------------------------------- analysis

    /**
     * Streaming K-weighting meter core. One instance is one continuous filter run: a single WAV
     * feeds it once, an album feeds several files in a row so the 100 ms sub blocks - and with them
     * the overlapping 400 ms gating blocks - run straight through the file boundaries. No silence
     * and no filter reset is inserted at a boundary.
     *
     * <p>{@link #configure} starts a new run (new rate/layout, fresh filter state); the accumulated
     * 400 ms block energies and the sample peak survive it, so a group of files with different
     * sample rates still contributes one combined gating measurement. Only the sub block window
     * that is still open at such a format change is dropped, which is the same thing that happens
     * at the end of a single file.
     */
    private static final class Meter {
        int channels;
        int containerBytes;
        int frameBytes;
        int sampleRate;
        long hop;
        double blockSamples;
        double fullScale;
        boolean truePeak;
        boolean configured;

        double[] shelf;
        double[] hpf;
        double[] weights;
        double[] state;
        double[] chunkSum;
        double[][] ring;
        int ringPos;
        int seenChunks;
        long chunkPos;

        double[] blocks = new double[256];
        int blockCount;

        double maxAbs;
        double maxInterp;
        long framesRead;
        boolean cancelled;

        final int tpHist = TP_TAPS;
        double[][] tpCoef;
        double[][] tpBuf;

        /** Start a new run without losing the accumulated block energies and peak. */
        void configure(int sampleRate, int channels, int containerBytes, int blockAlign,
                       long channelMask, boolean truePeak) {
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.containerBytes = containerBytes;
            this.frameBytes = blockAlign;
            this.hop = Math.max(1L, Math.round(sampleRate * SUB_BLOCK_SEC));
            this.blockSamples = (double) hop * SUB_BLOCKS;
            this.fullScale = Math.pow(2.0, containerBytes * 8 - 1);
            this.truePeak = truePeak;

            this.shelf = new double[5];
            this.hpf = new double[5];
            kWeighting(sampleRate, shelf, hpf);
            this.weights = channelWeights(channels, channelMask);

            this.state = new double[channels * 4];
            this.chunkSum = new double[channels];
            this.ring = new double[SUB_BLOCKS][channels];
            this.ringPos = 0;
            this.seenChunks = 0;
            this.chunkPos = 0;

            if (truePeak) {
                this.tpCoef = truePeakCoefficients();
                this.tpBuf = new double[channels][tpHist];
            } else {
                this.tpCoef = null;
                this.tpBuf = null;
            }
            this.configured = true;
        }

        /** Consume one WAV data range; the run state carries over to the next call. */
        void feed(RandomAccessFile raf, long dataOffset, long dataBytes, Progress p)
                throws IOException {
            final int ch = channels;
            final int bps = containerBytes;
            final int frameBytes = this.frameBytes;
            final long totalFrames = dataBytes / frameBytes;

            double[] blocks = this.blocks;
            long framesReadLocal = 0;
            int lastPct = -1;

            final double b0 = shelf[0], b1 = shelf[1], b2 = shelf[2], a1 = shelf[3], a2 = shelf[4];
            final double c0 = hpf[0], c1 = hpf[1], c2 = hpf[2], d1 = hpf[3], d2 = hpf[4];
            final int tpHist = this.tpHist;
            final double[][] tpCoef = this.tpCoef;
            final double[][] tpBuf = this.tpBuf;

            byte[] buf = new byte[READ_FRAMES * frameBytes];
            raf.seek(dataOffset);
            long remaining = dataBytes;

            if (p != null) {
                p.onPercent(0);
                lastPct = 0;
            }

            while (remaining > 0) {
                int want = (int) Math.min(buf.length, remaining);
                want -= want % frameBytes;
                if (want <= 0) break;
                int got = raf.read(buf, 0, want);
                if (got <= 0) break;
                got -= got % frameBytes;
                int n = got / frameBytes;

                for (int i = 0; i < n; i++) {
                    int off = i * frameBytes;
                    for (int c = 0; c < ch; c++) {
                        int o = off + c * bps;
                        double x;
                        if (bps == 2) {
                            x = (short) ((buf[o] & 0xFF) | ((buf[o + 1] & 0xFF) << 8)) / fullScale;
                        } else if (bps == 3) {
                            int v = (buf[o] & 0xFF) | ((buf[o + 1] & 0xFF) << 8)
                                    | ((buf[o + 2] & 0xFF) << 16);
                            if ((v & 0x800000) != 0) v |= 0xFF000000;
                            x = v / fullScale;
                        } else {
                            int v = (buf[o] & 0xFF) | ((buf[o + 1] & 0xFF) << 8)
                                    | ((buf[o + 2] & 0xFF) << 16) | ((buf[o + 3] & 0xFF) << 24);
                            x = v / fullScale;
                        }
                        double ax = x < 0 ? -x : x;
                        if (ax > maxAbs) maxAbs = ax;

                        if (truePeak) {
                            double[] h = tpBuf[c];
                            for (int k = 1; k < tpHist; k++) h[k - 1] = h[k];
                            h[tpHist - 1] = x;
                            for (int phase = 1; phase < TRUE_PEAK_FACTOR; phase++) {
                                double[] co = tpCoef[phase];
                                double acc = 0;
                                for (int k = 0; k < tpHist; k++) acc += co[k] * h[k];
                                double aa = acc < 0 ? -acc : acc;
                                if (aa > maxInterp) maxInterp = aa;
                            }
                        }

                        // stage 1: high shelf, transposed direct form II
                        int si = c * 4;
                        double y1 = b0 * x + state[si];
                        state[si] = b1 * x - a1 * y1 + state[si + 1];
                        state[si + 1] = b2 * x - a2 * y1;
                        // stage 2: RLB high pass
                        double y2 = c0 * y1 + state[si + 2];
                        state[si + 2] = c1 * y1 - d1 * y2 + state[si + 3];
                        state[si + 3] = c2 * y1 - d2 * y2;
                        chunkSum[c] += y2 * y2;
                    }

                    chunkPos++;
                    if (chunkPos >= hop) {
                        chunkPos = 0;
                        for (int c = 0; c < ch; c++) {
                            ring[ringPos][c] = chunkSum[c];
                            chunkSum[c] = 0.0;
                        }
                        ringPos = (ringPos + 1) % SUB_BLOCKS;
                        seenChunks++;
                        if (seenChunks >= SUB_BLOCKS) {
                            double z = 0.0;
                            for (int c = 0; c < ch; c++) {
                                if (weights[c] == 0.0) continue;
                                double sum = 0.0;
                                for (int k = 0; k < SUB_BLOCKS; k++) sum += ring[k][c];
                                z += weights[c] * (sum / blockSamples);
                            }
                            if (z > 0.0) {
                                if (blockCount == blocks.length) {
                                    double[] bigger = new double[blocks.length * 2];
                                    System.arraycopy(blocks, 0, bigger, 0, blocks.length);
                                    blocks = bigger;
                                    this.blocks = bigger;
                                }
                                blocks[blockCount++] = z;
                            }
                        }
                    }
                }

                framesReadLocal += n;
                this.framesRead += n;
                remaining -= got;

                if (p != null) {
                    int pct = totalFrames > 0
                            ? (int) Math.min(100L, (framesReadLocal * 100L) / totalFrames) : 100;
                    if (pct != lastPct) {
                        lastPct = pct;
                        p.onPercent(pct);
                    }
                    if (p.cancelled()) {
                        cancelled = true;
                        break;
                    }
                }
            }
        }

        /** BS.1770-4 integrated loudness of everything fed so far, NaN when nothing passes. */
        double lufs() {
            return cancelled ? Double.NaN : integrated(blocks, blockCount);
        }

        /** Sample peak in dBFS over everything fed so far. */
        double peakDb() {
            return maxAbs > 0.0 ? 20.0 * Math.log10(maxAbs) : Double.NEGATIVE_INFINITY;
        }

        Result result(int bits) {
            double trueDb = Double.NaN;
            if (truePeak) {
                double m = Math.max(maxAbs, maxInterp);
                trueDb = m > 0.0 ? 20.0 * Math.log10(m) : Double.NEGATIVE_INFINITY;
            }
            double seconds = sampleRate > 0 ? (double) framesRead / sampleRate : 0.0;
            return new Result(lufs(), peakDb(), trueDb, framesRead, seconds, sampleRate, channels,
                    bits, cancelled);
        }
    }

    /**
     * Accumulates several WAV files into one BS.1770-4 "album" measurement.
     *
     * <p>The K-weighting 100 ms sub block accumulator is deliberately not reset between files and
     * no silence is inserted, so the result is the loudness of the concatenated audio: 400 ms
     * gating blocks may span a track boundary, and both gates are applied once to the whole group.
     * The trailing partial block of the group is dropped exactly like it is for a single file.
     *
     * <p>The analyser is streaming: it keeps only the 400 ms block energies (one double per landed
     * block, i.e. 10 per second of audio) plus the current filter state, never a decoded file.
     *
     * <p>Album material normally shares one sample rate and channel layout. When it does not, the
     * filter is restarted for the new layout and the still open sub block window is dropped; that
     * loses at most 100 ms at the boundary and keeps the measurement continuous (no inserted
     * silence). The reported {@code sampleRate}/{@code channels}/{@code bits} are those of the
     * first file.
     */
    public static final class AlbumAnalyzer {

        private final Meter meter = new Meter();
        private long frames;
        private double seconds;
        private int sampleRate;
        private int channels;
        private int bits;
        private boolean cancelled;
        private int files;
        private int runRate;
        private int runChannels;
        private int runContainer;
        private int runAlign;

        /** Add one WAV file to the group; the next add continues the same filter run. */
        public void add(File wav) throws IOException {
            add(wav, null);
        }

        /** Same as {@link #add(File)} with per file progress (0..100, relative to that file). */
        public void add(File wav, Progress p) throws IOException {
            if (wav == null || !wav.isFile()) throw new IOException("no such file: " + wav);
            if (cancelled) return;
            try (RandomAccessFile raf = new RandomAccessFile(wav, "r")) {
                Wav w = parseWav(raf, raf.length(), wav);
                boolean sameFormat = meter.configured
                        && runRate == w.sampleRate && runChannels == w.channels
                        && runContainer == w.containerBytes && runAlign == w.blockAlign;
                if (!sameFormat) {
                    meter.configure(w.sampleRate, w.channels, w.containerBytes, w.blockAlign,
                            w.channelMask, false);
                    runRate = w.sampleRate;
                    runChannels = w.channels;
                    runContainer = w.containerBytes;
                    runAlign = w.blockAlign;
                }
                meter.feed(raf, w.dataOffset, w.dataBytes, p);
                long n = w.dataBytes / w.blockAlign;
                frames += n;
                seconds += (double) n / w.sampleRate;
                if (files == 0) {
                    sampleRate = w.sampleRate;
                    channels = w.channels;
                    bits = w.bits;
                }
                files++;
                if (meter.cancelled) cancelled = true;
            }
        }

        /** Number of files accepted so far. */
        public int files() {
            return files;
        }

        /** True once a {@link Progress} hook asked to stop; {@link #finish()} is then NaN. */
        public boolean cancelled() {
            return cancelled;
        }

        /**
         * Result over everything added so far. {@code truePeakDbfs} is NaN: album scans do not run
         * the oversampling true peak meter.
         */
        public Result finish() {
            return new Result(meter.lufs(), meter.peakDb(), Double.NaN, frames, seconds,
                    sampleRate, channels, bits, cancelled || meter.cancelled);
        }
    }

    /** BS.1770-4 gating over the per block weighted energies. */
    private static double integrated(double[] blocks, int count) {
        if (count <= 0) return Double.NaN;
        double sum = 0.0;
        int kept = 0;
        for (int i = 0; i < count; i++) {
            double l = LUFS_OFFSET + 10.0 * Math.log10(blocks[i]);
            if (l >= ABSOLUTE_GATE_LUFS) {
                sum += blocks[i];
                kept++;
            }
        }
        if (kept == 0 || sum <= 0.0) return Double.NaN;
        double relativeGate = LUFS_OFFSET + 10.0 * Math.log10(sum / kept) + RELATIVE_GATE_LU;
        double sum2 = 0.0;
        int kept2 = 0;
        for (int i = 0; i < count; i++) {
            double l = LUFS_OFFSET + 10.0 * Math.log10(blocks[i]);
            if (l >= ABSOLUTE_GATE_LUFS && l >= relativeGate) {
                sum2 += blocks[i];
                kept2++;
            }
        }
        if (kept2 == 0 || sum2 <= 0.0) return Double.NaN;
        return LUFS_OFFSET + 10.0 * Math.log10(sum2 / kept2);
    }

    /**
     * BS.1770-4 channel weights: front left/right/centre 1.0, surrounds 1.41, LFE excluded.
     * WAVE_FORMAT_EXTENSIBLE masks are honoured when present; otherwise the usual channel count
     * layouts are assumed and anything unusual falls back to 1.0 with a note on stderr.
     */
    private static double[] channelWeights(int ch, long mask) {
        double[] w = new double[ch];
        if (mask != 0 && Long.bitCount(mask) == ch) {
            // WAV speaker bit order (KS_DATAFORMAT): FL FR FC LFE BL BR FLC FRC BC SL SR ...
            int[] positions = speakerOrder(mask);
            if (positions.length == ch) {
                for (int i = 0; i < ch; i++) {
                    int pos = positions[i];
                    w[i] = weightForSpeaker(pos);
                }
                return w;
            }
            // fall through to count based layout
        }
        switch (ch) {
            case 1:
                w[0] = 1.0;
                break;
            case 2:
                w[0] = 1.0;
                w[1] = 1.0;
                break;
            case 3: // L R C
                w[0] = 1.0;
                w[1] = 1.0;
                w[2] = 1.0;
                break;
            case 4: // L R Ls Rs
                w[0] = 1.0;
                w[1] = 1.0;
                w[2] = 1.41;
                w[3] = 1.41;
                break;
            case 5: // L R C Ls Rs
                w[0] = 1.0;
                w[1] = 1.0;
                w[2] = 1.0;
                w[3] = 1.41;
                w[4] = 1.41;
                break;
            case 6: // L R C LFE Ls Rs
                w[0] = 1.0;
                w[1] = 1.0;
                w[2] = 1.0;
                w[3] = 0.0;
                w[4] = 1.41;
                w[5] = 1.41;
                break;
            case 8: // L R C LFE Ls Rs Lb Rb (7.1)
                for (int i = 0; i < ch; i++) w[i] = 1.0;
                w[3] = 0.0;
                w[4] = 1.41;
                w[5] = 1.41;
                w[6] = 1.41;
                w[7] = 1.41;
                break;
            default:
                for (int i = 0; i < ch; i++) w[i] = 1.0;
                System.err.println("Loudness: unknown channel layout for " + ch
                        + " channels, weighting every channel 1.0");
                break;
        }
        return w;
    }

    /** Speaker positions present in the mask, in WAV mask bit order. */
    private static int[] speakerOrder(long mask) {
        int[] all = new int[18];
        int n = 0;
        long m = mask;
        for (int i = 0; i < 18; i++) {
            if ((m & (1L << i)) != 0L) all[n++] = i;
        }
        int[] out = new int[n];
        System.arraycopy(all, 0, out, 0, n);
        return out;
    }

    private static double weightForSpeaker(int pos) {
        switch (pos) {
            case 0: // FL
            case 1: // FR
            case 2: // FC
            case 6: // FLC
            case 7: // FRC
                return 1.0;
            case 3: // LFE
                return 0.0;
            case 4: // BL
            case 5: // BR
            case 8: // BC (back centre, not in BS.1770-4 but a surround channel in practice)
            case 9: // SL
            case 10: // SR
                return 1.41;
            default:
                // other surround positions (rear centre, top, ...) are not in BS.1770-4
                return 1.0;
        }
    }

    /** Biquad coefficients for the K-weighting filter at any sample rate. */
    private static void kWeighting(int fs, double[] shelf, double[] hpf) {
        double k = Math.tan(Math.PI * SHELF_F0 / fs);
        double vh = Math.pow(10.0, SHELF_GAIN_DB / 20.0);
        double vb = Math.pow(vh, SHELF_VB_EXP);
        double a0 = 1.0 + k / SHELF_Q + k * k;
        shelf[0] = (vh + vb * k / SHELF_Q + k * k) / a0;
        shelf[1] = 2.0 * (k * k - vh) / a0;
        shelf[2] = (vh - vb * k / SHELF_Q + k * k) / a0;
        shelf[3] = 2.0 * (k * k - 1.0) / a0;
        shelf[4] = (1.0 - k / SHELF_Q + k * k) / a0;

        double k2 = Math.tan(Math.PI * HPF_F0 / fs);
        double d = 1.0 + k2 / HPF_Q + k2 * k2;
        hpf[0] = 1.0;
        hpf[1] = -2.0;
        hpf[2] = 1.0;
        hpf[3] = 2.0 * (k2 * k2 - 1.0) / d;
        hpf[4] = (1.0 - k2 / HPF_Q + k2 * k2) / d;
    }

    /** Per phase interpolation coefficients, DC normalised to unity gain. */
    private static double[][] truePeakCoefficients() {
        final int len = TRUE_PEAK_FACTOR * TP_TAPS;
        double[] proto = new double[len];
        double centre = (len - 1) / 2.0;
        for (int i = 0; i < len; i++) {
            double t = (i - centre) / TRUE_PEAK_FACTOR;
            double sinc = t == 0.0 ? 1.0 : Math.sin(Math.PI * t) / (Math.PI * t);
            double w = 0.42 - 0.5 * Math.cos(2.0 * Math.PI * i / (len - 1))
                    + 0.08 * Math.cos(4.0 * Math.PI * i / (len - 1));
            proto[i] = sinc * w;
        }
        double[][] co = new double[TRUE_PEAK_FACTOR][TP_TAPS];
        for (int ph = 0; ph < TRUE_PEAK_FACTOR; ph++) {
            double sum = 0.0;
            for (int j = 0; j < TP_TAPS; j++) {
                co[ph][j] = proto[ph + TRUE_PEAK_FACTOR * j];
                sum += co[ph][j];
            }
            if (Math.abs(sum) > 1e-12) {
                for (int j = 0; j < TP_TAPS; j++) co[ph][j] /= sum;
            }
        }
        return co;
    }

    // ---------------------------------------------------------------- little helpers

    private static boolean tag(byte[] b, int off, String s) {
        for (int i = 0; i < s.length(); i++) {
            if ((b[off + i] & 0xFF) != s.charAt(i)) return false;
        }
        return true;
    }

    private static int u16(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
    }

    private static long u32(byte[] b, int o) {
        return (long) (b[o] & 0xFF) | ((long) (b[o + 1] & 0xFF) << 8)
                | ((long) (b[o + 2] & 0xFF) << 16) | ((long) (b[o + 3] & 0xFF) << 24);
    }
}
