package com.vstplayer.core;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Decodes whatever the platform can demux and decode into a plain PCM WAV.
 *
 * <p>The bundled renderer is a Windows host fed a file path: it only understands WAV/AIFF/FLAC/OGG
 * and mis-reads everything else, so an ALAC/AAC/MP3 source has to be turned into a temporary WAV
 * here before VstView hands it over. Only framework APIs are used (MediaExtractor + MediaCodec in
 * synchronous mode, available since the app's minSdk 24), no androidx, no third-party decoder.
 *
 * <p>Bit depth is never invented. The container's KEY_BITS_PER_SAMPLE decides what we ask the
 * decoder for (16/24/32-bit), then the depth that the decoder actually reports in its output format
 * is what gets written, and 16-bit is the fallback when nothing reports a depth (AAC). Not every
 * codec honours a 24-bit request: when one silently hands back 16-bit samples the header says
 * 16-bit too.
 */
public final class Decode {

    /** MediaFormat.KEY_PCM_ENCODING values; the numbers are stable public constants. */
    private static final int ENC_PCM_8 = 0x03;
    private static final int ENC_PCM_16 = 0x02;
    private static final int ENC_PCM_FLOAT = 0x04;
    private static final int ENC_PCM_24 = 0x15;
    private static final int ENC_PCM_32 = 0x1d;

    private static final long TIMEOUT_US = 10000L;
    /** Consecutive empty output polls after input EOS before the decoder is declared stuck. */
    private static final int IDLE_LIMIT = 500;
    /** MediaFormat key for the sample depth; the public constant is @hide, the string is stable. */
    private static final String KEY_BITS_PER_SAMPLE = "bits-per-sample";
    /** RIFF sizes are 32-bit: data + 36 must stay inside an unsigned 32-bit field. */
    private static final long MAX_DATA = 0xFFFFFFFFL - 36L;
    private static final int BUF = 1 << 16;

    private Decode() {
    }

    /** True when the file starts with a RIFF/WAVE signature; everything else needs decoding first. */
    public static boolean isWav(File f) {
        if (f == null || !f.isFile()) return false;
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            byte[] h = new byte[12];
            if (r.read(h) != 12) return false;
            return h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F'
                    && h[8] == 'W' && h[9] == 'A' && h[10] == 'V' && h[11] == 'E';
        } catch (IOException e) {
            return false;
        }
    }

    /** Cooperative cancellation; the render queue hands in one flag per batch. */
    public interface Cancel {
        boolean stopped();
    }

    /** Outcome of a decode: exactly one of {@code wav} / {@code error} is non-null. */
    public static final class Result {
        public final File wav;
        public final String error;

        Result(File wav, String error) {
            this.wav = wav;
            this.error = error;
        }

        public boolean ok() {
            return wav != null;
        }
    }

    /**
     * Decodes {@code src} (any container the platform can demux) into a 44-byte-header PCM WAV at
     * {@code dst} and keeps the source's depth.
     *
     * @return {@code dst} on success, or {@code null} after logging why it failed. Never throws.
     */
    public static File toWav(Context ctx, File src, File dst) {
        return decode(ctx, src, dst, null).wav;
    }

    /**
     * Decodes with one automatic retry at the decoder's default depth.
     *
     * <p>The platform ALAC decoder (media.swcodec) has been observed to die with a native SIGSEGV
     * when asked for 24 bit output; once that service is gone every later MediaCodec call hangs
     * forever instead of failing, which is exactly how a batch render turned into a three hour
     * stall. Both safeguards are therefore mandatory: a wall clock deadline inside the decode loop
     * (so a dead codec service can never block a queue) and a second attempt without the depth
     * request, which the same file often survives.
     */
    public static Result decode(Context ctx, File src, File dst, Cancel cancel) {
        // The bundled native decoder comes first: it never goes through the platform codec service,
        // which used to die with a SIGSEGV under full render load, and it keeps 24 bit exactness.
        // Anything it does not understand falls through to MediaCodec below.
        if (nativeDecoderHandles(src)) {
            final long t0 = Diag.now();
            final int rc = NativeDecode.decodeToWav(src, dst, 0);
            if (rc == 0 && dst.isFile() && dst.length() > 44) {
                Diag.log("decode: " + src.getName() + " -> native +" + (Diag.now() - t0) + "ms "
                        + dst.length() + "B");
                return new Result(dst, null);
            }
            Diag.log("decode: native failed for " + src.getName() + " (" + rc + " "
                    + NativeDecode.lastError() + "), falling back to MediaCodec");
            if (dst.exists()) dst.delete();
        }
        String[] why = new String[1];
        File first = once(ctx, src, dst, cancel, false, why);
        if (first != null) return new Result(first, null);
        if (cancel != null && cancel.stopped()) return new Result(null, "已取消");
        final String name = src == null ? "?" : src.getName();
        final String reason1 = why[0] == null ? "unknown" : why[0];
        Diag.log("decode: " + name + " attempt 1 failed (" + reason1
                + "), retrying at the decoder's default depth");
        try {
            // The codec service is restarted by the framework after a native crash; give it a beat.
            Thread.sleep(700);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return new Result(null, "已取消");
        }
        why[0] = null;
        File second = once(ctx, src, dst, cancel, true, why);
        if (second != null) {
            Diag.log("decode: " + name + " decoded on the default-depth retry");
            return new Result(second, null);
        }
        final String reason2 = why[0] == null ? reason1 : why[0];
        Diag.log("decode: " + name + " failed twice: " + reason2);
        return new Result(null, reason2);
    }

    /** True when the native library is loaded and the container is one it decodes (FLAC / ALAC). */
    private static boolean nativeDecoderHandles(File src) {
        if (src == null || !src.isFile()) return false;
        if (!NativeDecode.available()) return false;
        String n = src.getName().toLowerCase(java.util.Locale.US);
        return n.endsWith(".flac") || n.endsWith(".m4a") || n.endsWith(".mp4")
                || n.endsWith(".alac") || n.endsWith(".caf");
    }

    /**
     * @param defaultDepth when true the codec is never asked for a specific PCM depth, which is the
     *                     retry path for decoders that crash on the 24 bit request
     * @param why          receives a human readable reason when this returns null
     */
    private static File once(Context ctx, File src, File dst, Cancel cancel, boolean defaultDepth,
                             String[] why) {
        if (src == null || dst == null || !src.isFile()) {
            if (why != null) why[0] = "源文件不可读";
            Diag.log("decode: missing source " + (src == null ? "null" : src.getName()));
            return null;
        }
        final long t0 = Diag.now();
        final String name = src.getName();
        MediaExtractor ex = null;
        MediaCodec codec = null;
        RandomAccessFile out = null;
        boolean ok = false;
        try {
            ex = new MediaExtractor();
            ex.setDataSource(src.getAbsolutePath());
            int track = -1;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String m = f.getString(MediaFormat.KEY_MIME);
                if (m != null && m.startsWith("audio/")) {
                    track = i;
                    break;
                }
            }
            if (track < 0) {
                if (why != null) why[0] = "没有音频轨道";
                Diag.log("decode: " + name + " has no audio track");
                return null;
            }
            ex.selectTrack(track);

            MediaFormat in = ex.getTrackFormat(track);
            final String mime = in.getString(MediaFormat.KEY_MIME);
            final int srcCh = intOr(in, MediaFormat.KEY_CHANNEL_COUNT, 0);
            final int srcRate = intOr(in, MediaFormat.KEY_SAMPLE_RATE, 0);
            final int srcBits = intOr(in, KEY_BITS_PER_SAMPLE, 0);
            final long durUs = longOr(in, MediaFormat.KEY_DURATION, 0);
            // 24- and 32-bit sources need an explicit request; 16-bit is the codec default. The
            // retry path passes defaultDepth, because the 24-bit request is what killed the ALAC
            // decoder on one real file and a 16-bit decode still beats no output at all.
            final int want = defaultDepth ? 0
                    : srcBits >= 32 ? ENC_PCM_32 : srcBits >= 24 ? ENC_PCM_24 : 0;
            if (want != 0) {
                try {
                    in.setInteger(MediaFormat.KEY_PCM_ENCODING, want);
                } catch (RuntimeException e) {
                    Diag.log("decode: " + name + " cannot request pcm-encoding 0x"
                            + Integer.toHexString(want) + ": " + e);
                }
            }
            Diag.log("decode: " + name + " mime=" + mime + " src=" + srcBits + "bit/"
                    + srcRate + "Hz/" + srcCh + "ch want="
                    + (want == ENC_PCM_32 ? 32 : want == ENC_PCM_24 ? 24 : 16) + "bit");

            codec = MediaCodec.createDecoderByType(mime);
            try {
                codec.configure(in, null, null, 0);
            } catch (RuntimeException e) {
                // Some decoders reject an unknown pcm-encoding outright. Re-create a pristine
                // format from the extractor (its copy still carries the csd-0/csd-1 bytes the
                // decoder needs) and let the codec pick its own depth.
                Diag.log("decode: " + name + " rejects the depth request, retrying default: " + e);
                release(codec);
                codec = MediaCodec.createDecoderByType(mime);
                codec.configure(ex.getTrackFormat(track), null, null, 0);
            }
            codec.start();

            File parent = dst.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
                throw new IOException("cannot create " + parent);
            }
            out = new RandomAccessFile(dst, "rw");
            out.setLength(0);
            out.write(new byte[44]);

            // A dead codec service never returns from dequeueOutputBuffer, so the loop needs its
            // own wall clock deadline: one duration is ~50x the real decode cost and is capped so a
            // wedged file cannot hold the queue for hours.
            final long budgetMs = durUs > 0
                    ? Math.min(15 * 60_000L, Math.max(60_000L, durUs / 1000L))
                    : 5 * 60_000L;
            final long deadline = Diag.now() + budgetMs;

            final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            final byte[] buf = new byte[BUF];
            boolean inputDone = false;
            boolean outputDone = false;
            int idle = 0;
            int ch = srcCh;
            int rate = srcRate;
            int enc = 0;
            int inBps = 0;   // bytes per sample the decoder hands us
            int outBits = 0; // bit depth the WAV declares (may differ: 8-bit widens to 16)
            int frameBytes = 0;
            long dataBytes = 0;
            long lastPtsUs = -1;  // start of the last output buffer, for an independent length check
            int lastBytes = 0;

            while (!outputDone) {
                if (cancel != null && cancel.stopped()) throw new IOException("已取消");
                if (Diag.now() > deadline) {
                    throw new IOException("解码超时（" + (budgetMs / 1000) + "s 未完成）");
                }
                if (!inputDone) {
                    int idx = codec.dequeueInputBuffer(TIMEOUT_US);
                    if (idx >= 0) {
                        ByteBuffer ib = codec.getInputBuffer(idx);
                        int n = ib == null ? -1 : ex.readSampleData(ib, 0);
                        if (n < 0) {
                            codec.queueInputBuffer(idx, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(idx, 0, n, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }

                int idx = codec.dequeueOutputBuffer(info, TIMEOUT_US);
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = codec.getOutputFormat();
                    ch = positive(intOr(of, MediaFormat.KEY_CHANNEL_COUNT, 0), ch);
                    rate = positive(intOr(of, MediaFormat.KEY_SAMPLE_RATE, 0), rate);
                    enc = intOr(of, MediaFormat.KEY_PCM_ENCODING, 0);
                    inBps = bpsFor(enc);
                    if (inBps == 0) inBps = bpsForDepth(intOr(of, KEY_BITS_PER_SAMPLE, 0));
                    if (inBps > 0 && ch > 0) {
                        outBits = outBitsFor(inBps);
                        frameBytes = ch * (outBits / 8);
                    }
                    Diag.log("decode: " + name + " out=" + (outBits == 0 ? "?" : outBits + "bit")
                            + "/" + rate + "Hz/" + ch + "ch enc=0x"
                            + Integer.toHexString(enc) + " inBps=" + inBps);
                    idle = 0;
                } else if (idx >= 0) {
                    if (info.size > 0) {
                        ByteBuffer ob = codec.getOutputBuffer(idx);
                        if (ob == null) {
                            Diag.log("decode: " + name + " null output buffer at " + dataBytes);
                        } else {
                            int off = Math.max(0, info.offset);
                            int end = Math.min(ob.capacity(), off + info.size);
                            if (end > off) {
                                ob.position(off);
                                ob.limit(end);
                                if (frameBytes == 0) {
                                    ch = positive(ch, srcCh);
                                    inBps = probeBytesPerSample(end - off, ch, want == ENC_PCM_24);
                                    if (inBps == 0 || ch <= 0) {
                                        throw new IOException("cannot infer PCM layout from "
                                                + (end - off) + "B buffer, " + ch + "ch");
                                    }
                                    outBits = outBitsFor(inBps);
                                    frameBytes = ch * (outBits / 8);
                                    Diag.log("decode: " + name
                                            + " depth not reported, inferred " + outBits
                                            + "bit from " + (end - off) + "B/" + ch + "ch");
                                }
                                lastPtsUs = Math.max(lastPtsUs, info.presentationTimeUs);
                                lastBytes = end - off;
                                dataBytes += (enc == ENC_PCM_FLOAT && inBps == 4)
                                        ? writeFloat(ob, out)
                                        : write(ob, out, inBps, ch, buf);
                                if (dataBytes > MAX_DATA) {
                                    throw new IOException("more than 4 GiB of PCM");
                                }
                            }
                        }
                    }
                    codec.releaseOutputBuffer(idx, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
                    idle = 0;
                } else if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (inputDone && ++idle > IDLE_LIMIT) {
                        throw new IOException("decoder stalled after EOS");
                    }
                }
            }

            if (frameBytes <= 0) throw new IOException("no PCM layout");
            if (dataBytes < frameBytes) throw new IOException("no PCM output");
            // A codec may report the depth we asked for while handing back samples of another
            // width: the FLAC extractor decodes a 24-bit stream to 16-bit PCM yet still advertises
            // bits-per-sample=24, and the pass-through "audio/raw" decoder echoes that back. The
            // track duration and the decoder's own timestamps are independent evidence of how many
            // frames were actually delivered, so they decide the depth when they contradict it.
            final boolean raw = enc != ENC_PCM_FLOAT && inBps >= 2 && inBps <= 4
                    && outBits == inBps * 8;
            if (raw && rate > 0 && ch > 0) {
                // Two independent length estimates: the container's duration and the decoder's
                // last presentation timestamp. Either alone can be approximate, so the depth is
                // only rewritten when they agree on a whole number of bytes per frame.
                final long ptsEndUs = lastPtsUs >= 0
                        ? lastPtsUs + Math.round(lastBytes * 1000000.0
                        / ((double) rate * ch * inBps))
                        : 0;
                final double rPts = ptsEndUs > 0
                        ? dataBytes / ((double) ptsEndUs * rate / 1000000.0 * ch) : -1;
                final double rDur = durUs > 0
                        ? dataBytes / ((double) durUs * rate / 1000000.0 * ch) : -1;
                final int a = rPts > 0 ? (int) Math.round(rPts) : -1;
                final int b = rDur > 0 ? (int) Math.round(rDur) : -1;
                // The container duration is the trusted estimate (it is what the device run was
                // verified against); the timestamp estimate only fills in when no duration exists.
                int pick = -1;
                if (b >= 2 && b <= 4 && Math.abs(rDur - b) <= 0.12) pick = b;
                else if (a >= 2 && a <= 4 && Math.abs(rPts - a) <= 0.12) pick = a;
                if (pick > 0 && pick != inBps) {
                    Diag.log("decode: " + name + " declared " + inBps * 8 + "bit but the stream"
                            + " is " + pick * 8 + "bit (bytes/frame " + rPts + "/" + rDur
                            + "), writing " + pick * 8);
                    inBps = pick;
                    outBits = pick * 8;
                    frameBytes = ch * pick;
                } else if (pick < 0) {
                    Diag.log("decode: " + name + " depth unverified: " + rPts + "/" + rDur
                            + " bytes/frame");
                }
            }
            // A codec may end a buffer mid-frame; drop the tail so the WAV stays frame aligned.
            final long frames = dataBytes / frameBytes;
            final long data = frames * frameBytes;
            out.seek(0);
            out.write(header(ch, rate, outBits, data));
            out.setLength(44 + data);
            ok = true;
            Diag.log("decode: " + name + " -> " + outBits + "bit/" + rate + "Hz/" + ch + "ch "
                    + data + "B " + frames + "f in " + (Diag.now() - t0) + "ms");
            return dst;
        } catch (Throwable t) {
            final String msg = t instanceof IllegalStateException
                    ? t + "（系统解码器崩溃或已失效）" : String.valueOf(t);
            if (why != null) why[0] = msg;
            Diag.log("decode: " + name + " failed: " + msg);
            return null;
        } finally {
            release(codec);
            if (ex != null) {
                try {
                    ex.release();
                } catch (RuntimeException ignored) {
                    // the extractor holds no writer state, nothing to repair
                }
            }
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                    // a real write failure would have surfaced in the decode loop already
                }
            }
            if (!ok) dst.delete();
        }
    }

    /**
     * Decodes every file into the cache dir and logs depth/rate/channels/size/time, then deletes the
     * temporary WAVs. Hooked from the UI as a one-shot device check; the log is the result.
     */
    public static void selfTest(Context ctx, File[] files) {
        if (ctx == null || files == null) return;
        File cache = ctx.getCacheDir();
        if (cache == null) {
            Diag.log("selftest: no cache dir");
            return;
        }
        File dir = new File(cache, "decode-selftest");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            Diag.log("selftest: cannot create " + dir);
            return;
        }
        for (File f : files) {
            if (f == null) continue;
            File dst = new File(dir, f.getName() + ".wav");
            Result res = decode(ctx, f, dst, null);
            File r = res.wav;
            if (r == null) {
                Diag.log("selftest: " + f.getName() + " FAILED: " + res.error);
                continue;
            }
            Diag.log("selftest: " + f.getName() + " -> " + header(r) + " " + r.length() + "B");
            if (!r.delete()) Diag.log("selftest: cannot delete " + r);
        }
        Diag.log("selftest: done");
    }

    /** Reads the written WAV's fmt chunk back, so a report quotes the file, not a variable. */
    private static String header(File wav) {
        try (RandomAccessFile r = new RandomAccessFile(wav, "r")) {
            byte[] h = new byte[44];
            if (r.read(h) != 44) return "short header";
            int ch = u16(h, 22);
            int rate = (int) u32(h, 24);
            int bits = u16(h, 34);
            long len = Math.max(0, r.length() - 44);
            return bits + "bit/" + rate + "Hz/" + ch + "ch " + len + "B";
        } catch (IOException e) {
            return "unreadable: " + e;
        }
    }

    /**
     * Appends one output buffer, converting when the input layout differs from the WAV layout
     * (8-bit signed -> 16-bit).
     *
     * @return the number of bytes appended
     */
    private static long write(ByteBuffer b, RandomAccessFile out, int inBps, int ch, byte[] buf)
            throws IOException {
        if (inBps == 2 || inBps == 3 || inBps == 4) {
            long n = 0;
            while (b.hasRemaining()) {
                int k = Math.min(b.remaining(), buf.length);
                b.get(buf, 0, k);
                out.write(buf, 0, k);
                n += k;
            }
            return n;
        }
        if (inBps == 1) {
            int frames = Math.max(1, buf.length / 2 / Math.max(1, ch));
            long n = 0;
            while (b.hasRemaining()) {
                int k = Math.min(b.remaining(), frames * Math.max(1, ch));
                if (k <= 0) throw new IOException("buffer too small for " + ch + " channels");
                b.get(buf, 0, k);
                for (int i = 0; i < k; i++) {
                    int s = (buf[i] & 0xFF) << 8;
                    buf[i * 2] = (byte) s;
                    buf[i * 2 + 1] = (byte) (s >> 8);
                }
                out.write(buf, 0, k * 2);
                n += k * 2L;
            }
            return n;
        }
        throw new IOException("unsupported PCM layout, " + inBps + "B/sample");
    }

    /** float PCM -> 32-bit int, so the WAV stays an integer format the renderer accepts. */
    private static long writeFloat(ByteBuffer b, RandomAccessFile out) throws IOException {
        final ByteBuffer le = b.order(ByteOrder.LITTLE_ENDIAN);
        byte[] ob = new byte[4 * 1024];
        long n = 0;
        int o = 0;
        while (le.remaining() >= 4) {
            float v = le.getFloat();
            int s = v >= 1f ? Integer.MAX_VALUE : v <= -1f ? Integer.MIN_VALUE
                    : (int) Math.rint(v * 2147483647.0);
            ob[o] = (byte) s;
            ob[o + 1] = (byte) (s >> 8);
            ob[o + 2] = (byte) (s >> 16);
            ob[o + 3] = (byte) (s >> 24);
            o += 4;
            n += 4;
            if (o == ob.length) {
                out.write(ob, 0, o);
                o = 0;
            }
        }
        if (o > 0) out.write(ob, 0, o);
        return n;
    }

    /** Bytes per input sample for a KEY_PCM_ENCODING value, or 0 when the value is unknown. */
    private static int bpsFor(int enc) {
        switch (enc) {
            case ENC_PCM_8:
                return 1;
            case ENC_PCM_16:
                return 2;
            case ENC_PCM_24:
                return 3;
            case ENC_PCM_32:
            case ENC_PCM_FLOAT:
                return 4;
            default:
                return 0;
        }
    }

    /** Bytes per sample for a KEY_BITS_PER_SAMPLE value, or 0 when the value is unknown. */
    private static int bpsForDepth(int bits) {
        return bits == 8 || bits == 16 || bits == 24 || bits == 32 ? bits / 8 : 0;
    }

    /** Bit depth the WAV declares for a given input sample width (8-bit is widened to 16). */
    private static int outBitsFor(int inBps) {
        return inBps == 1 ? 16 : inBps * 8;
    }

    /** Bytes per sample guessed from the first output buffer when the decoder reports no depth. */
    private static int probeBytesPerSample(int size, int channels, boolean prefer24) {
        if (size <= 0 || channels <= 0) return 0;
        int[] order = prefer24 ? new int[]{3, 2, 4} : new int[]{2, 3, 4};
        for (int bps : order) {
            if (size % (channels * bps) == 0) return bps;
        }
        return 0;
    }

    private static byte[] header(int channels, int rate, int bits, long data) {
        byte[] h = new byte[44];
        ascii(h, 0, "RIFF");
        put32(h, 4, data + 36);
        ascii(h, 8, "WAVE");
        ascii(h, 12, "fmt ");
        put32(h, 16, 16);
        put16(h, 20, 1);                        // PCM
        put16(h, 22, channels);
        put32(h, 24, rate);
        int blockAlign = channels * (bits / 8);
        put32(h, 28, (long) rate * blockAlign); // byte rate
        put16(h, 32, blockAlign);
        put16(h, 34, bits);
        ascii(h, 36, "data");
        put32(h, 40, data);
        return h;
    }

    private static void ascii(byte[] b, int off, String s) {
        for (int i = 0; i < s.length(); i++) b[off + i] = (byte) s.charAt(i);
    }

    private static void put16(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >> 8);
    }

    private static void put32(byte[] b, int off, long v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >> 8);
        b[off + 2] = (byte) (v >> 16);
        b[off + 3] = (byte) (v >> 24);
    }

    private static int u16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static long u32(byte[] b, int off) {
        return (b[off] & 0xFFL) | ((b[off + 1] & 0xFFL) << 8)
                | ((b[off + 2] & 0xFFL) << 16) | ((b[off + 3] & 0xFFL) << 24);
    }

    private static long longOr(MediaFormat f, String key, long def) {
        try {
            return f.containsKey(key) ? f.getLong(key) : def;
        } catch (RuntimeException e) {
            return def;
        }
    }

    private static int intOr(MediaFormat f, String key, int def) {
        try {
            return f.containsKey(key) ? f.getInteger(key) : def;
        } catch (RuntimeException e) {
            return def;
        }
    }

    private static int positive(int v, int def) {
        return v > 0 ? v : def;
    }

    private static void release(MediaCodec c) {
        if (c == null) return;
        try {
            c.stop();
        } catch (RuntimeException ignored) {
            // already stopped or never started
        }
        try {
            c.release();
        } catch (RuntimeException ignored) {
            // nothing left to free
        }
    }
}
