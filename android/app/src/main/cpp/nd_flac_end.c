/* nd_flac_end.c - PCM WAV to FLAC, on top of the vendored libFLAC encoder.

 * Why native: Android ships no FLAC encoder at all, and the only lossless codec
 * the platform can mux is none.  libFLAC is BSD-3-Clause and small enough to
 * vendor (see THIRD-PARTY-NOTICES.md).
 *
 * The input is always what the render host wrote, i.e. canonical little endian
 * integer PCM, but the parser still walks the chunk list because a 24 bit source
 * is written by JUCE with a "fact"-less plain header whose data size may be
 * unknown (0xFFFFFFFF) for streamed output.
 */
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "nd_flac_end.h"
/* The vendored tree is not on the include path on purpose: its assert.h would shadow the
   system one for the whole library. */
#include "third_party/flac/stream_encoder.h"

#define ND_ENC_BLOCK_FRAMES 4096
#define ND_ENC_MAX_CHANNELS 8

typedef struct {
    int      channels;
    int      bits;
    uint32_t rate;
    long     data_offset;
    int64_t  frames;
} wav_info;

static int nd_fail(char *err, int errlen, int code, const char *fmt, ...)
{
    va_list ap;
    if (err && errlen > 0) {
        va_start(ap, fmt);
        vsnprintf(err, (size_t)errlen, fmt, ap);
        va_end(ap);
    }
    return code;
}

static uint16_t nd_rd16(const unsigned char *p)
{
    return (uint16_t)((uint16_t)p[0] | ((uint16_t)p[1] << 8));
}

static uint32_t nd_rd32(const unsigned char *p)
{
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16)
           | ((uint32_t)p[3] << 24);
}

/* Walks RIFF chunks and fills the info struct; leaves the file positioned at the
   first sample byte. */
static int nd_wav_open(FILE *f, wav_info *w, char *err, int errlen)
{
    unsigned char hdr[12];
    unsigned char ck[8];
    unsigned char fmt[40];
    long          file_size;
    int           format = 0;
    int           have_fmt = 0;

    if (fseek(f, 0, SEEK_END) != 0) return nd_fail(err, errlen, ND_ENC_ERR_OPEN, "seek failed");
    file_size = ftell(f);
    if (file_size < 44) return nd_fail(err, errlen, ND_ENC_ERR_WAV, "file too small");
    if (fseek(f, 0, SEEK_SET) != 0) return nd_fail(err, errlen, ND_ENC_ERR_OPEN, "seek failed");
    if (fread(hdr, 1, sizeof(hdr), f) != sizeof(hdr)) {
        return nd_fail(err, errlen, ND_ENC_ERR_OPEN, "truncated header");
    }
    if (memcmp(hdr, "RIFF", 4) != 0 || memcmp(hdr + 8, "WAVE", 4) != 0) {
        return nd_fail(err, errlen, ND_ENC_ERR_WAV, "not a RIFF/WAVE file");
    }

    for (;;) {
        long total;
        long payload;
        if (fread(ck, 1, sizeof(ck), f) != sizeof(ck)) break;
        total = (long)nd_rd32(ck + 4);

        if (memcmp(ck, "fmt ", 4) == 0) {
            size_t want = total < (long)sizeof(fmt) ? (size_t)total : sizeof(fmt);
            if (want < 16) return nd_fail(err, errlen, ND_ENC_ERR_WAV, "short fmt chunk");
            if (fread(fmt, 1, want, f) != want) {
                return nd_fail(err, errlen, ND_ENC_ERR_OPEN, "truncated fmt chunk");
            }
            format = nd_rd16(fmt);
            w->channels = nd_rd16(fmt + 2);
            w->rate = nd_rd32(fmt + 4);
            w->bits = nd_rd16(fmt + 14);
            /* WAVE_FORMAT_EXTENSIBLE: the real tag is the first two bytes of the
               sub-format GUID, which sits 24 bytes into the chunk. */
            if (format == 0xFFFE) {
                if (want < 26) return nd_fail(err, errlen, ND_ENC_ERR_WAV, "short extensible fmt");
                format = nd_rd16(fmt + 24);
            }
            have_fmt = 1;
            if (total > (long)want && fseek(f, (long)(total - (long)want), SEEK_CUR) != 0) {
                return nd_fail(err, errlen, ND_ENC_ERR_OPEN, "seek failed");
            }
            continue;
        }

        if (memcmp(ck, "data", 4) == 0) {
            int      frame_bytes;
            int64_t  avail;
            int64_t  bytes;
            if (!have_fmt) return nd_fail(err, errlen, ND_ENC_ERR_WAV, "data before fmt");
            if (format != 1) {
                return nd_fail(err, errlen, ND_ENC_ERR_WAV,
                               "WAV is not integer PCM (format tag %d)", format);
            }
            if (w->channels < 1 || w->channels > ND_ENC_MAX_CHANNELS) {
                return nd_fail(err, errlen, ND_ENC_ERR_WAV, "unsupported channel count %d",
                               w->channels);
            }
            if (w->bits != 16 && w->bits != 24 && w->bits != 32) {
                return nd_fail(err, errlen, ND_ENC_ERR_WAV, "unsupported bit depth %d", w->bits);
            }
            if (w->rate < 1 || w->rate > 2822400u) {
                return nd_fail(err, errlen, ND_ENC_ERR_WAV, "unsupported sample rate %u", w->rate);
            }
            w->data_offset = ftell(f);
            if (w->data_offset < 0) return nd_fail(err, errlen, ND_ENC_ERR_OPEN, "tell failed");
            avail = (int64_t)file_size - (int64_t)w->data_offset;
            bytes = (total < 0 || (int64_t)total > avail) ? avail : (int64_t)total;
            frame_bytes = w->channels * (w->bits / 8);
            w->frames = bytes / frame_bytes;
            if (w->frames <= 0) return nd_fail(err, errlen, ND_ENC_ERR_WAV, "no PCM frames");
            return ND_ENC_OK;
        }

        /* Unknown chunk: skip it, honouring the even-byte padding rule. */
        if (total < 0) return nd_fail(err, errlen, ND_ENC_ERR_WAV, "bad chunk size");
        if (fseek(f, (long)total + (total & 1), SEEK_CUR) != 0) {
            return nd_fail(err, errlen, ND_ENC_ERR_OPEN, "seek failed");
        }
    }
    return nd_fail(err, errlen, ND_ENC_ERR_WAV, "no data chunk");
}

/* Reads one sample as a sign extended 32 bit integer. */
static FLAC__int32 nd_sample(const unsigned char *p, int bytes)
{
    if (bytes == 2) return (FLAC__int32)(int16_t)nd_rd16(p);
    if (bytes == 3) {
        int32_t v = (int32_t)((uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16));
        return (FLAC__int32)(v << 8 >> 8);          /* sign extend from 24 bits */
    }
    return (FLAC__int32)(int32_t)nd_rd32(p);
}

int nd_encode_flac(const char *wav_path, const char *flac_path, int level,
                   char *err, int errlen)
{
    FILE               *in = NULL;
    FILE               *out = NULL;
    FLAC__StreamEncoder *enc = NULL;
    FLAC__int32        *buf = NULL;
    unsigned char      *raw = NULL;
    wav_info            w;
    int                 bytes_per_sample;
    int                 rc = ND_ENC_OK;
    int64_t             left;

    if (err && errlen > 0) err[0] = 0;
    if (!wav_path || !flac_path) {
        return nd_fail(err, errlen, ND_ENC_ERR_OPEN, "missing path");
    }
    memset(&w, 0, sizeof(w));

    in = fopen(wav_path, "rb");
    if (!in) return nd_fail(err, errlen, ND_ENC_ERR_OPEN, "cannot open %s", wav_path);

    rc = nd_wav_open(in, &w, err, errlen);
    if (rc != ND_ENC_OK) {
        fclose(in);
        return rc;
    }
    bytes_per_sample = w.bits / 8;

    if (fseek(in, w.data_offset, SEEK_SET) != 0) {
        fclose(in);
        return nd_fail(err, errlen, ND_ENC_ERR_OPEN, "seek failed");
    }

    buf = (FLAC__int32 *)malloc((size_t)ND_ENC_BLOCK_FRAMES * (size_t)w.channels
                                * sizeof(FLAC__int32));
    raw = (unsigned char *)malloc((size_t)ND_ENC_BLOCK_FRAMES * (size_t)w.channels * 4u);
    if (!buf || !raw) {
        free(buf);
        free(raw);
        fclose(in);
        return nd_fail(err, errlen, ND_ENC_ERR_OOM, "out of memory");
    }

    enc = FLAC__stream_encoder_new();
    if (!enc) {
        free(buf);
        free(raw);
        fclose(in);
        return nd_fail(err, errlen, ND_ENC_ERR_OOM, "cannot create encoder");
    }

    if (level < 0) level = 0;
    if (level > 8) level = 8;
    FLAC__stream_encoder_set_channels(enc, (uint32_t)w.channels);
    FLAC__stream_encoder_set_bits_per_sample(enc, (uint32_t)w.bits);
    FLAC__stream_encoder_set_sample_rate(enc, w.rate);
    FLAC__stream_encoder_set_compression_level(enc, (uint32_t)level);
    FLAC__stream_encoder_set_total_samples_estimate(enc, (FLAC__uint64)w.frames);

    out = fopen(flac_path, "wb");
    if (!out) {
        FLAC__stream_encoder_delete(enc);
        free(buf);
        free(raw);
        fclose(in);
        return nd_fail(err, errlen, ND_ENC_ERR_OPEN, "cannot create %s", flac_path);
    }

    {
        FLAC__StreamEncoderInitStatus st =
            FLAC__stream_encoder_init_FILE(enc, out, NULL, NULL);
        if (st != FLAC__STREAM_ENCODER_INIT_STATUS_OK) {
            rc = nd_fail(err, errlen, ND_ENC_ERR_INIT, "libFLAC init failed: %s",
                         FLAC__StreamEncoderInitStatusString[(int)st]);
            goto done;
        }
    }

    for (left = w.frames; left > 0;) {
        int     want = left < ND_ENC_BLOCK_FRAMES ? (int)left : ND_ENC_BLOCK_FRAMES;
        size_t  bytes = (size_t)want * (size_t)w.channels * (size_t)bytes_per_sample;
        size_t  got = fread(raw, 1, bytes, in);
        int     frames;
        int     i;

        frames = (int)(got / ((size_t)w.channels * (size_t)bytes_per_sample));
        if (frames <= 0) break;
        for (i = 0; i < frames * w.channels; i++) {
            buf[i] = nd_sample(raw + (size_t)i * (size_t)bytes_per_sample, bytes_per_sample);
        }
        if (!FLAC__stream_encoder_process_interleaved(enc, buf, (uint32_t)frames)) {
            rc = nd_fail(err, errlen, ND_ENC_ERR_WRITE, "libFLAC rejected %d frames at %lld",
                         frames, (long long)(w.frames - left));
            goto done;
        }
        left -= frames;
        if (frames < want) break;                /* short read: the data chunk lied */
    }

    if (!FLAC__stream_encoder_finish(enc)) {
        rc = nd_fail(err, errlen, ND_ENC_ERR_WRITE, "libFLAC finish failed (%s)",
                     FLAC__StreamEncoderStateString[(int)FLAC__stream_encoder_get_state(enc)]);
    }

done:
    FLAC__stream_encoder_delete(enc);
    if (out) fclose(out);
    fclose(in);
    free(buf);
    free(raw);
    return rc;
}
