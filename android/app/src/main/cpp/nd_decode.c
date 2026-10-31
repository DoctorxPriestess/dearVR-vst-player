/* nd_decode.c - container sniffing, WAV output and the public C API.
 *
 * Both decoders stream: only one packet / chunk of PCM is held in memory
 * at a time, so very long files decode in bounded memory.
 */
#include "nd_common.h"
#include "nd_decode.h"
#include "nd_alac.h"
#include "nd_mp4.h"
#include "dr_flac.h"

#define ND_WAV_IO_FRAMES     4096
#define ND_MAX_PACKET_BYTES  ((uint32_t)64 * 1024 * 1024)

/* ------------------------------------------------------------- WAV out */

typedef struct nd_wav {
    FILE    *f;
    uint32_t channels;
    int      bits;
    int      bytes;
    uint64_t frames;
    uint8_t *iobuf;
    size_t   iobuf_frames;
} nd_wav;

static void nd_wr_u32le(uint8_t *p, uint32_t v)
{
    p[0] = (uint8_t)(v & 0xff);
    p[1] = (uint8_t)((v >> 8) & 0xff);
    p[2] = (uint8_t)((v >> 16) & 0xff);
    p[3] = (uint8_t)((v >> 24) & 0xff);
}

static void nd_wr_u16le(uint8_t *p, uint16_t v)
{
    p[0] = (uint8_t)(v & 0xff);
    p[1] = (uint8_t)((v >> 8) & 0xff);
}

static void nd_remove_file(const char *path)
{
#if defined(_WIN32)
    wchar_t w[1024];
    if (MultiByteToWideChar(CP_UTF8, 0, path, -1, w, 1024) > 0)
        _wremove(w);
#else
    remove(path);
#endif
}

static int nd_wav_begin(nd_wav *w, const char *path, uint32_t channels,
                        uint32_t rate, int bits)
{
    uint8_t  hdr[44];
    uint32_t block;

    memset(w, 0, sizeof(*w));
    if (channels < 1 || channels > 8 || rate == 0) return -1;
    if (bits != 16 && bits != 24 && bits != 32) return -1;

    w->bytes    = bits / 8;
    w->channels = channels;
    w->bits     = bits;
    block       = channels * (uint32_t)w->bytes;
    w->iobuf_frames = ND_WAV_IO_FRAMES;
    w->iobuf = (uint8_t *)malloc(w->iobuf_frames * block);
    if (!w->iobuf) return -1;

    w->f = nd_fopen_utf8(path, 1);
    if (!w->f) { free(w->iobuf); memset(w, 0, sizeof(*w)); return -1; }

    memset(hdr, 0, sizeof(hdr));
    memcpy(hdr + 0, "RIFF", 4);
    memcpy(hdr + 8, "WAVEfmt ", 8);
    nd_wr_u32le(hdr + 16, 16);
    nd_wr_u16le(hdr + 20, 1);              /* PCM */
    nd_wr_u16le(hdr + 22, (uint16_t)channels);
    nd_wr_u32le(hdr + 24, rate);
    nd_wr_u32le(hdr + 28, rate * block);
    nd_wr_u16le(hdr + 32, (uint16_t)block);
    nd_wr_u16le(hdr + 34, (uint16_t)bits);
    memcpy(hdr + 36, "data", 4);

    if (fwrite(hdr, 1, sizeof(hdr), w->f) != sizeof(hdr)) {
        fclose(w->f);
        free(w->iobuf);
        memset(w, 0, sizeof(*w));
        return -1;
    }
    return 0;
}

static void nd_wav_abort(nd_wav *w)
{
    if (!w) return;
    if (w->f) fclose(w->f);
    free(w->iobuf);
    memset(w, 0, sizeof(*w));
}

static int nd_wav_finish(nd_wav *w)
{
    uint64_t data;
    uint8_t  buf[4];

    if (!w || !w->f) return -1;
    data = w->frames * (uint64_t)w->channels * (uint64_t)w->bytes;
    if (data > 0xFFFFFFFFull - 36u) {
        nd_wav_abort(w);
        return -1;
    }
    if (fflush(w->f) != 0) goto fail;
    if (nd_fseek(w->f, 4) != 0) goto fail;
    nd_wr_u32le(buf, (uint32_t)(36u + data));
    if (fwrite(buf, 1, 4, w->f) != 4) goto fail;
    if (nd_fseek(w->f, 40) != 0) goto fail;
    nd_wr_u32le(buf, (uint32_t)data);
    if (fwrite(buf, 1, 4, w->f) != 4) goto fail;
    if (fclose(w->f) != 0) {
        w->f = NULL;
        free(w->iobuf);
        w->iobuf = NULL;
        return -1;
    }
    w->f = NULL;
    free(w->iobuf);
    w->iobuf = NULL;
    return 0;

fail:
    fclose(w->f);
    w->f = NULL;
    free(w->iobuf);
    w->iobuf = NULL;
    return -1;
}

static void nd_store_sample(uint8_t *p, int32_t v, int bytes)
{
    p[0] = (uint8_t)(v & 0xff);
    if (bytes > 1) p[1] = (uint8_t)((v >> 8) & 0xff);
    if (bytes > 2) p[2] = (uint8_t)((v >> 16) & 0xff);
    if (bytes > 3) p[3] = (uint8_t)((v >> 24) & 0xff);
}

/* Planar int32 samples, left shifted by lshift, written at w->bits. */
static int nd_wav_write_planar(nd_wav *w, int32_t *const *planes, int frames,
                               int lshift)
{
    int offset = 0;

    while (offset < frames) {
        int      n = frames - offset;
        uint8_t *p = w->iobuf;
        size_t   chunk;
        int      i, ch;

        if ((size_t)n > w->iobuf_frames) n = (int)w->iobuf_frames;
        for (i = 0; i < n; i++) {
            for (ch = 0; ch < (int)w->channels; ch++) {
                int32_t v = (int32_t)((uint32_t)planes[ch][offset + i] <<
                                      (unsigned)lshift);
                nd_store_sample(p, v, w->bytes);
                p += w->bytes;
            }
        }
        chunk = (size_t)n * w->channels * (size_t)w->bytes;
        if (fwrite(w->iobuf, 1, chunk, w->f) != chunk) return -1;
        w->frames += (uint64_t)n;
        offset += n;
    }
    return 0;
}

/* Interleaved int32 samples, right shifted by rshift (dr_flac's 32-bit
 * left-justified output is reduced to the destination container width). */
static int nd_wav_write_interleaved(nd_wav *w, const int32_t *data, int frames,
                                    int rshift)
{
    int offset = 0;

    while (offset < frames) {
        int      n = frames - offset;
        uint8_t *p = w->iobuf;
        size_t   chunk;
        int      i, ch;

        if ((size_t)n > w->iobuf_frames) n = (int)w->iobuf_frames;
        for (i = 0; i < n; i++) {
            for (ch = 0; ch < (int)w->channels; ch++) {
                int32_t v = data[(size_t)(offset + i) * w->channels + ch] >> rshift;
                nd_store_sample(p, v, w->bytes);
                p += w->bytes;
            }
        }
        chunk = (size_t)n * w->channels * (size_t)w->bytes;
        if (fwrite(w->iobuf, 1, chunk, w->f) != chunk) return -1;
        w->frames += (uint64_t)n;
        offset += n;
    }
    return 0;
}

/* ---------------------------------------------------------------- FLAC */

static drflac *nd_flac_open(const char *path)
{
#if defined(_WIN32)
    wchar_t w[1024];
    if (MultiByteToWideChar(CP_UTF8, 0, path, -1, w, 1024) <= 0) return NULL;
    return drflac_open_file_w(w, NULL);
#else
    return drflac_open_file(path, NULL);
#endif
}

static int nd_decode_flac(const char *in, const char *out, int max_seconds,
                          char *err, int errlen)
{
    drflac  *flac;
    nd_wav   wav;
    int32_t *pcm = NULL;
    uint32_t channels, rate;
    int      bits, container, rshift;
    uint64_t total, limit, done = 0;
    const uint32_t chunk = 4096;
    int      rc = ND_OK;

    flac = nd_flac_open(in);
    if (!flac) {
        nd_set_err(err, errlen, "cannot open FLAC stream: %s", in);
        return ND_ERR_OPEN;
    }
    channels = flac->channels;
    rate     = flac->sampleRate;
    bits     = flac->bitsPerSample;
    if (channels < 1 || channels > 8 || rate == 0 || bits < 4 || bits > 32) {
        nd_set_err(err, errlen, "unsupported FLAC stream (%u ch, %u Hz, %d bit)",
                   channels, rate, bits);
        drflac_close(flac);
        return ND_ERR_UNSUPPORTED;
    }
    container = bits <= 16 ? 16 : (bits <= 24 ? 24 : 32);
    rshift    = 32 - container;
    total     = flac->totalPCMFrameCount;
    limit     = max_seconds > 0 ? (uint64_t)max_seconds * (uint64_t)rate
                                : ~(uint64_t)0;

    memset(&wav, 0, sizeof(wav));
    if (nd_wav_begin(&wav, out, channels, rate, container) != 0) {
        nd_set_err(err, errlen, "cannot create output file: %s", out);
        drflac_close(flac);
        return ND_ERR_OPEN;
    }
    pcm = (int32_t *)malloc((size_t)chunk * channels * sizeof(int32_t));
    if (!pcm) {
        rc = ND_ERR_WRITE;
        nd_set_err(err, errlen, "out of memory");
        goto done;
    }

    while (done < limit) {
        uint64_t want = chunk;
        uint64_t got;

        if (want > limit - done) want = limit - done;
        got = drflac_read_pcm_frames_s32(flac, want, pcm);
        if (got == 0) break;
        if (nd_wav_write_interleaved(&wav, pcm, (int)got, rshift) != 0) {
            rc = ND_ERR_WRITE;
            nd_set_err(err, errlen, "write failed");
            goto done;
        }
        done += got;
        if (got < want) break;
    }

    if (total > 0 && done < total && done < limit) {
        rc = ND_ERR_DECODE;
        nd_set_err(err, errlen,
                   "FLAC stream is truncated (%llu of %llu frames decoded)",
                   (unsigned long long)done, (unsigned long long)total);
        goto done;
    }
    if (done == 0) {
        rc = ND_ERR_DECODE;
        nd_set_err(err, errlen, "no audio frames decoded");
        goto done;
    }
    if (nd_wav_finish(&wav) != 0) {
        rc = ND_ERR_WRITE;
        nd_set_err(err, errlen, "write failed while finalising output");
        goto done;
    }

done:
    free(pcm);
    drflac_close(flac);
    if (rc != ND_OK) {
        nd_wav_abort(&wav);
        nd_remove_file(out);
    }
    return rc;
}

/* ---------------------------------------------------------------- ALAC */

static int nd_decode_alac_file(const char *in, const char *out, int max_seconds,
                               char *err, int errlen)
{
    nd_mp4        *m = NULL;
    nd_alac_dec   *dec = NULL;
    nd_wav         wav;
    uint8_t       *pkt = NULL;
    uint32_t       count, maxpkt = 0, i;
    const nd_alac_config *cfg;
    int            rc, depth, container, lshift;
    uint64_t       limit, done = 0;
    /* audit #3: only remove the output file if this call created it. */
    int            out_created = 0;

    memset(&wav, 0, sizeof(wav));
    rc = nd_mp4_open(&m, in, err, errlen);
    if (rc != ND_OK) return rc;

    cfg   = nd_mp4_config(m);
    count = nd_mp4_packet_count(m);
    depth     = (int)cfg->bit_depth;
    container = depth == 20 ? 24 : depth;
    lshift    = container - depth;

    for (i = 0; i < count; i++) {
        uint64_t off;
        uint32_t sz;
        if (nd_mp4_packet_info(m, i, &off, &sz) != ND_OK) {
            rc = ND_ERR_DECODE;
            nd_set_err(err, errlen, "ALAC packet table is corrupt");
            goto done;
        }
        if (sz > maxpkt) maxpkt = sz;
    }
    if (maxpkt == 0 || maxpkt > ND_MAX_PACKET_BYTES) {
        rc = ND_ERR_UNSUPPORTED;
        nd_set_err(err, errlen, "implausible ALAC packet size (%u bytes)", maxpkt);
        goto done;
    }

    dec = nd_alac_create(cfg);
    if (!dec) {
        rc = ND_ERR_DECODE;
        nd_set_err(err, errlen, "cannot initialise ALAC decoder");
        goto done;
    }
    pkt = (uint8_t *)malloc(maxpkt);
    if (!pkt) {
        rc = ND_ERR_WRITE;
        nd_set_err(err, errlen, "out of memory");
        goto done;
    }

    if (nd_wav_begin(&wav, out, cfg->channels, cfg->sample_rate, container) != 0) {
        rc = ND_ERR_OPEN;
        nd_set_err(err, errlen, "cannot create output file: %s", out);
        goto done;
    }
    out_created = 1;

    limit = max_seconds > 0
                ? (uint64_t)max_seconds * (uint64_t)cfg->sample_rate
                : ~(uint64_t)0;

    for (i = 0; i < count && done < limit; i++) {
        uint64_t off;
        uint32_t sz;
        int32_t *const *planes = NULL;
        int      frames = 0, take;

        if (nd_mp4_packet_info(m, i, &off, &sz) != ND_OK || sz > maxpkt) {
            rc = ND_ERR_DECODE;
            nd_set_err(err, errlen, "ALAC packet table is corrupt at packet %u", i);
            goto done;
        }
        rc = nd_mp4_read_packet(m, i, pkt, sz);
        if (rc != ND_OK) {
            nd_set_err(err, errlen, "short read at ALAC packet %u", i);
            goto done;
        }
        rc = nd_alac_decode(dec, pkt, sz, &planes, &frames);
        if (rc != ND_OK) {
            nd_set_err(err, errlen, "corrupt ALAC bitstream at packet %u", i);
            goto done;
        }
        take = frames;
        if ((uint64_t)take > limit - done) take = (int)(limit - done);
        if (nd_wav_write_planar(&wav, planes, take, lshift) != 0) {
            rc = ND_ERR_WRITE;
            nd_set_err(err, errlen, "write failed");
            goto done;
        }
        done += (uint64_t)take;
    }

    if (done == 0) {
        rc = ND_ERR_DECODE;
        nd_set_err(err, errlen, "no audio frames decoded");
        goto done;
    }
    if (nd_wav_finish(&wav) != 0) {
        rc = ND_ERR_WRITE;
        nd_set_err(err, errlen, "write failed while finalising output");
        goto done;
    }
    rc = ND_OK;

done:
    free(pkt);
    if (dec) nd_alac_destroy(dec);
    if (m) nd_mp4_close(m);
    if (rc != ND_OK) {
        nd_wav_abort(&wav);
        if (out_created) nd_remove_file(out);
    }
    return rc;
}

/* ------------------------------------------------------------ entry API */

enum { ND_FMT_UNKNOWN = 0, ND_FMT_FLAC, ND_FMT_MP4 };

static int nd_sniff(const char *path, char *err, int errlen)
{
    FILE   *f;
    uint8_t h[12];
    size_t  n;

    f = nd_fopen_utf8(path, 0);
    if (!f) {
        nd_set_err(err, errlen, "cannot open input: %s", path);
        return ND_ERR_OPEN;
    }
    n = fread(h, 1, sizeof(h), f);
    fclose(f);
    if (n >= 4 && memcmp(h, "fLaC", 4) == 0) return ND_FMT_FLAC;
    if (n >= 4 && memcmp(h, "OggS", 4) == 0) return ND_FMT_FLAC;
    if (n >= 8) {
        uint32_t t = nd_rd_u32be(h + 4);
        if (t == 0x66747970u /* ftyp */ || t == 0x6d6f6f76u /* moov */ ||
            t == 0x6d646174u /* mdat */ || t == 0x77696465u /* wide */ ||
            t == 0x66726565u /* free */ || t == 0x736b6970u /* skip */)
            return ND_FMT_MP4;
    }
    nd_set_err(err, errlen, "unrecognised container (not M4A/MP4 or FLAC)");
    return ND_ERR_UNSUPPORTED;
}

int nd_decode_to_wav(const char *in_path, const char *out_path, int max_seconds,
                     char *err_buf, int err_len)
{
    int fmt;

    if (err_buf && err_len > 0) err_buf[0] = 0;
    if (!in_path || !out_path || !in_path[0] || !out_path[0]) {
        nd_set_err(err_buf, err_len, "empty path");
        return ND_ERR_OPEN;
    }

    fmt = nd_sniff(in_path, err_buf, err_len);
    if (fmt < 0) return fmt;

    if (fmt == ND_FMT_FLAC)
        return nd_decode_flac(in_path, out_path, max_seconds, err_buf, err_len);
    return nd_decode_alac_file(in_path, out_path, max_seconds, err_buf, err_len);
}
