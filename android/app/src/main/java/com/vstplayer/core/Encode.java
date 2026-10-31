package com.vstplayer.core;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.Locale;

/**
 * Compresses a finished render for the way it is delivered.
 *
 * <p>The plugin host always writes PCM, so a batch of ten files used to leave ten
 * 24 bit WAVs in the export folder - dozens of gigabytes for a playlist. What the
 * user asked for is "give the audio back in the format I handed in":
 *
 * <ul>
 *   <li>a lossless source (wav, flac, alac, aiff) becomes FLAC, which is bit exact;</li>
 *   <li>a lossy source (mp3, aac/m4a, ogg, opus) becomes AAC at the source bitrate,
 *       because re-encoding to a lossless container would only waste space;</li>
 *   <li>anything else (unknown container, or a depth FLAC cannot carry here) stays
 *       WAV, which is never wrong, just large.</li>
 * </ul>
 *
 * <p>Every failure path returns the WAV it was given: a broken encoder must cost
 * space, never audio.
 */
public final class Encode {

    /** libFLAC compression level (0..8); 5 is the default of the reference encoder. */
    private static final int FLAC_LEVEL = 5;

    /** Used when the source bitrate cannot be read at all. */
    private static final int AAC_FALLBACK = 192000;
    /** Range the platform AAC encoder accepts for the profiles we use. */
    private static final int AAC_MIN = 32000;
    private static final int AAC_MAX = 512000;

    /** Highest bit depth libFLAC writes here; deeper PCM stays WAV (see {@link #compress}). */
    private static final int FLAC_MAX_BITS = 24;

    private Encode() { }

    /** What {@link #compress} should produce for one source file. */
    public enum Kind { WAV, FLAC, AAC }

    /**
     * Decides the delivery format for a source file.
     *
     * <p>The container mime type is what the platform extractor reports, because an
     * .m4a can hold either ALAC (lossless) or AAC (lossy) and the extension cannot
     * tell them apart. The extension is only a fallback for files the extractor
     * refuses, e.g. a container whose codec this device has no parser for.
     */
    public static Kind kindOf(File src) {
        if (src == null) return Kind.WAV;
        String mime = mimeOf(src);
        if (mime != null) {
            if (mime.startsWith("audio/alac") || mime.startsWith("audio/flac")
                    || mime.startsWith("audio/x-flac") || mime.startsWith("audio/raw")
                    || mime.startsWith("audio/wav") || mime.startsWith("audio/x-wav")
                    || mime.startsWith("audio/aiff") || mime.startsWith("audio/x-aiff")) {
                return Kind.FLAC;
            }
            if (mime.startsWith("audio/mpeg") || mime.startsWith("audio/mp4a")
                    || mime.startsWith("audio/aac") || mime.startsWith("audio/3gpp")
                    || mime.startsWith("audio/ogg") || mime.startsWith("audio/opus")
                    || mime.startsWith("audio/vorbis") || mime.startsWith("audio/amr")) {
                return Kind.AAC;
            }
        }
        String name = src.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".wav") || name.endsWith(".wave") || name.endsWith(".flac")
                || name.endsWith(".aif") || name.endsWith(".aiff") || name.endsWith(".alac")) {
            return Kind.FLAC;
        }
        if (name.endsWith(".mp3") || name.endsWith(".mp2") || name.endsWith(".m4a")
                || name.endsWith(".aac") || name.endsWith(".ogg") || name.endsWith(".oga")
                || name.endsWith(".opus") || name.endsWith(".amr")) {
            return Kind.AAC;
        }
        return Kind.WAV;
    }

    /**
     * Compresses {@code wav} according to {@code src} and returns the file to export.
     *
     * <p>Blocking; call it on the export thread. Never throws: on any failure the
     * argument is returned unchanged so the render is still delivered.
     */
    public static File compress(Context ctx, File src, File wav) {
        if (wav == null || !wav.isFile()) return wav;
        Kind kind = kindOf(src);
        if (kind == Kind.WAV) return wav;

        Wav.Info pcm = Wav.probe(wav);
        if (pcm == null) {
            Diag.log("compress: cannot read " + wav.getName() + ", keeping the wav");
            return wav;
        }

        if (kind == Kind.FLAC) {
            if (pcm.bits > FLAC_MAX_BITS) {
                // libFLAC can write 32 bit, but nothing on Android decodes it and the
                // platform FLAC decoder stops at 24, so a 32 bit source keeps its WAV.
                Diag.log("compress: " + pcm.bits + " bit source stays wav");
                return wav;
            }
            if (!NativeDecode.available()) {
                Diag.log("compress: native library missing, keeping the wav");
                return wav;
            }
            File out = sibling(wav, ".flac");
            int rc = NativeDecode.encodeFlac(wav, out, FLAC_LEVEL);
            if (rc != 0) {
                Diag.log("compress: flac failed (" + rc + ") " + NativeDecode.lastError());
                out.delete();
                return wav;
            }
            Diag.log("compress: " + wav.getName() + " -> " + out.getName()
                    + " " + wav.length() + "B -> " + out.length() + "B");
            return out;
        }

        int bitrate = clamp(bitrateOf(src, pcm), AAC_MIN, AAC_MAX);
        File out = sibling(wav, ".m4a");
        try {
            aac(pcm, wav, out, bitrate);
            Diag.log("compress: " + wav.getName() + " -> " + out.getName() + " "
                    + bitrate / 1000 + "k " + wav.length() + "B -> " + out.length() + "B");
            return out;
        } catch (Exception e) {
            Diag.log("compress: aac failed for " + wav.getName() + ": " + e);
            out.delete();
            return wav;
        }
    }

    /** Bitrate of a lossy source in bits per second, or 0 when unknown. */
    public static int bitrateOf(File src, Wav.Info pcm) {
        int bps = 0;
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(src.getAbsolutePath());
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String m = f.getString(MediaFormat.KEY_MIME);
                if (m == null || !m.startsWith("audio/")) continue;
                if (f.containsKey(MediaFormat.KEY_BIT_RATE)) bps = f.getInteger(MediaFormat.KEY_BIT_RATE);
                if (bps <= 0 && f.containsKey(MediaFormat.KEY_DURATION) && pcm != null) {
                    // Size over duration: correct for CBR, an average for VBR, which is
                    // exactly what an AAC encoder wants to be told anyway.
                    long us = f.getLong(MediaFormat.KEY_DURATION);
                    if (us > 0) bps = (int) Math.min(Integer.MAX_VALUE, src.length() * 8L * 1000000L / us);
                }
                break;
            }
        } catch (Exception e) {
            Diag.log("bitrate: " + src.getName() + ": " + e);
        } finally {
            ex.release();
        }
        return bps;
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static File sibling(File wav, String ext) {
        String name = wav.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return new File(wav.getParentFile(), base + ext);
    }

    private static String mimeOf(File src) {
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(src.getAbsolutePath());
            for (int i = 0; i < ex.getTrackCount(); i++) {
                String m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (m != null && m.startsWith("audio/")) return m;
            }
        } catch (Exception ignored) {
            // Not a container the platform knows: the caller falls back to the extension.
        } finally {
            ex.release();
        }
        return null;
    }

    // ------------------------------------------------------------------ AAC muxing

    /**
     * Encodes the PCM in {@code wav} to AAC-LC and muxes it into an MPEG-4 file.
     *
     * <p>Android ships an AAC encoder on every device since API 16 and MediaMuxer writes
     * the container, so what is left is the pump between them: feed 1024 sample frames,
     * drain encoded access units, and hand them to the muxer only after the first output
     * buffer announced the format (that buffer carries the decoder specific info the
     * muxer needs).
     */
    private static void aac(Wav.Info pcm, File wav, File out, int bitrate) throws IOException {
        if (out.exists() && !out.delete()) throw new IOException("cannot replace " + out.getName());

        int channels = Math.min(2, Math.max(1, pcm.channels));
        MediaFormat fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,
                pcm.rate, channels);
        fmt.setInteger(MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024);

        MediaCodec codec = null;
        MediaMuxer muxer = null;
        RandomAccessFile in = null;
        try {
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();

            muxer = new MediaMuxer(out.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            in = new RandomAccessFile(wav, "r");
            in.seek(pcm.dataOffset);

            final int frameSamples = 1024;                       // one AAC frame
            final int bytesPerSample = pcm.bits / 8;
            byte[] chunk = new byte[frameSamples * channels * bytesPerSample];
            short[] samples = new short[frameSamples * channels];
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int track = -1;
            long fedFrames = 0;
            long presentationUs = 0;
            boolean inputDone = false;
            boolean outputDone = false;

            while (!outputDone) {
                if (!inputDone) {
                    int idx = codec.dequeueInputBuffer(10000);
                    if (idx >= 0) {
                        ByteBuffer buf = codec.getInputBuffer(idx);
                        int frames = 0;
                        if (fedFrames < pcm.frames && buf != null) {
                            frames = readSamples(in, chunk, samples, frameSamples, channels,
                                    bytesPerSample, pcm.frames - fedFrames);
                        }
                        if (frames > 0) {
                            int n = frames * channels;
                            buf.clear();
                            // Bulk copy: asShortBuffer().put beats a per sample loop on
                            // multi minute files, and the array is already interleaved.
                            buf.asShortBuffer().put(samples, 0, n);
                            buf.position(0);
                            buf.limit(n * 2);
                            codec.queueInputBuffer(idx, 0, n * 2, presentationUs, 0);
                            presentationUs += frames * 1000000L / pcm.rate;
                            fedFrames += frames;
                        } else {
                            codec.queueInputBuffer(idx, 0, 0, presentationUs,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        }
                    }
                }

                int idx = codec.dequeueOutputBuffer(info, 10000);
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (track < 0) {
                        track = muxer.addTrack(codec.getOutputFormat());
                        muxer.start();
                    }
                } else if (idx >= 0) {
                    ByteBuffer buf = codec.getOutputBuffer(idx);
                    if (buf != null && info.size > 0 && track >= 0
                            && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        buf.position(info.offset);
                        buf.limit(info.offset + info.size);
                        muxer.writeSampleData(track, buf, info);
                    }
                    boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    codec.releaseOutputBuffer(idx, false);
                    if (eos) outputDone = true;
                }
            }
            Diag.log("aac: " + wav.getName() + " in=" + fedFrames + " frames, out="
                    + out.length() + "B");
        } catch (Exception e) {
            out.delete();
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException("AAC 编码失败: " + e, e);
        } finally {
            if (codec != null) {
                try { codec.stop(); } catch (Exception ignored) { }
                try { codec.release(); } catch (Exception ignored) { }
            }
            if (muxer != null) {
                try { muxer.stop(); } catch (Exception ignored) { }
                try { muxer.release(); } catch (Exception ignored) { }
            }
            if (in != null) {
                try { in.close(); } catch (Exception ignored) { }
            }
        }
    }

    /**
     * Reads up to {@code frames} frames as 16 bit interleaved samples.
     *
     * <p>Deeper PCM is narrowed by keeping its most significant 16 bits, which is what
     * the AAC encoder takes in: it is a lossy target either way, and this avoids a
     * float round trip. Returns the number of frames produced.
     */
    private static int readSamples(RandomAccessFile src, byte[] chunk, short[] samples,
                                   int frames, int channels, int bytesPerSample, long left)
            throws IOException {
        int want = (int) Math.min(frames, left);
        int need = want * channels * bytesPerSample;
        int got = 0;
        while (got < need) {
            int n = src.read(chunk, got, need - got);
            if (n <= 0) break;
            got += n;
        }
        int have = got / (channels * bytesPerSample);
        int n = have * channels;
        if (bytesPerSample == 2) {
            for (int i = 0; i < n; i++) {
                samples[i] = (short) ((chunk[2 * i] & 0xFF) | (chunk[2 * i + 1] << 8));
            }
        } else if (bytesPerSample == 3) {
            for (int i = 0; i < n; i++) {
                samples[i] = (short) ((chunk[3 * i + 1] & 0xFF) | (chunk[3 * i + 2] << 8));
            }
        } else {
            for (int i = 0; i < n; i++) {
                samples[i] = (short) ((chunk[4 * i + 2] & 0xFF) | (chunk[4 * i + 3] << 8));
            }
        }
        return have;
    }
}
