/* nd_alac.c - Apple Lossless (ALAC) bitstream decoder, written from the
 * published codec behaviour (ALACSpecificConfig + ALAC frame syntax).
 *
 * Every arithmetic step below is written with explicit uint32/int32 two's
 * complement semantics so the output is bit exact with the reference
 * decoder, including the adaptive FIR coefficient update.
 *
 * Supported: SCE, CPE, LFE elements, prediction order 0..31 (including the
 * order 31 / prediction-type 15 special case), rice partitions with the
 * zero-run escape, "shifted" low bytes (1 or 2 extra bytes) and all
 * left/right/mid-side matrix combinations.
 */
#include "nd_common.h"
#include "nd_alac.h"

#define ND_ALAC_MAX_CHANNELS   8
#define ND_ALAC_MAX_FRAME      65536
#define ND_ALAC_MAX_RICE_LIMIT 24
#define ND_ALAC_MAX_ORDER      31

/* Channel re-ordering for multichannel streams: ALAC stores elements as
 * C, L/R, Ls/Rs, LFE ... while the output is in the conventional
 * FL FR FC LFE BL BR order. */
static const uint8_t nd_alac_chan_offsets[ND_ALAC_MAX_CHANNELS][ND_ALAC_MAX_CHANNELS] = {
    { 0 },
    { 0, 1 },
    { 2, 0, 1 },
    { 2, 0, 1, 3 },
    { 2, 0, 1, 3, 4 },
    { 2, 0, 1, 4, 5, 3 },
    { 2, 0, 1, 4, 5, 6, 3 },
    { 2, 6, 7, 0, 1, 4, 5, 3 }
};

/* ALAC raw data block types */
#define ND_ALAC_ID_SCE 0
#define ND_ALAC_ID_CPE 1
#define ND_ALAC_ID_CCE 2
#define ND_ALAC_ID_LFE 3
#define ND_ALAC_ID_DSE 4
#define ND_ALAC_ID_PCE 5
#define ND_ALAC_ID_FIL 6
#define ND_ALAC_ID_END 7

struct nd_alac_dec {
    nd_alac_config cfg;
    nd_br          br;

    int32_t       *residual[2];   /* rice / prediction error      */
    int32_t       *samples[2];    /* reconstructed samples        */
    int32_t       *shiftbuf[2];   /* low byte "shift" buffer      */
    int16_t        coefs[2][32];
    int            pred_type[2];
    int            quant[2];
    int            order[2];
    int            hist_mult[2];

    int32_t       *chan[ND_ALAC_MAX_CHANNELS];
    int            frames;
    int            have_frames;
};

/* ------------------------------------------------------------ config */

static int nd_alac_config_plausible(const nd_alac_config *c)
{
    if (c->frame_length < 1 || c->frame_length > ND_ALAC_MAX_FRAME) return 0;
    if (c->bit_depth != 16 && c->bit_depth != 20 &&
        c->bit_depth != 24 && c->bit_depth != 32) return 0;
    if (c->channels < 1 || c->channels > ND_ALAC_MAX_CHANNELS) return 0;
    if (c->sample_rate < 8000 || c->sample_rate > 768000) return 0;
    if (c->kb < 1 || c->kb > ND_ALAC_MAX_RICE_LIMIT) return 0;
    return 1;
}

static void nd_alac_config_read(const uint8_t *p, nd_alac_config *out)
{
    out->frame_length    = nd_rd_u32be(p + 0);
    /* p[4] is the compatible version */
    out->bit_depth       = p[5];
    out->pb              = p[6];
    out->mb              = p[7];
    out->kb              = p[8];
    out->channels        = p[9];
    out->max_run         = nd_rd_u16be(p + 10);
    out->max_frame_bytes = nd_rd_u32be(p + 12);
    out->avg_bitrate     = nd_rd_u32be(p + 16);
    out->sample_rate     = nd_rd_u32be(p + 20);
}

int nd_alac_config_parse(const uint8_t *cookie, size_t len, nd_alac_config *out)
{
    /* Accept the 36 byte QuickTime "alac" atom, a 28 byte variant with a
     * bare version word, or the plain 24 byte ALACSpecificConfig. */
    static const size_t offsets[] = { 0, 12, 4 };
    size_t i;

    if (!cookie || !out) return ND_ERR_UNSUPPORTED;
    for (i = 0; i < sizeof(offsets) / sizeof(offsets[0]); i++) {
        nd_alac_config tmp;
        size_t off = offsets[i];
        if (off != 0) {
            if (off == 12 && !(len >= 36 && memcmp(cookie + 4, "alac", 4) == 0))
                continue;
        }
        if (len < off + 24) continue;
        nd_alac_config_read(cookie + off, &tmp);
        if (nd_alac_config_plausible(&tmp)) {
            *out = tmp;
            return ND_OK;
        }
    }
    return ND_ERR_UNSUPPORTED;
}

/* --------------------------------------------------- bitstream helpers */

static inline int32_t nd_sign_extend(uint32_t v, int bits)
{
    unsigned sh = (unsigned)(32 - bits);
    return (int32_t)(v << sh) >> sh;
}

static inline int32_t nd_sign_only(int32_t v)
{
    return (v > 0) - (v < 0);
}

/* Decode one adaptive-Golomb value.  k is the current rice parameter,
 * bps the bit width of the escape-coded alternative. */
static int nd_decode_scalar(nd_br *br, int k, int bps, uint32_t *out)
{
    int pre;
    uint32_t x;

    if (nd_br_unary9(br, &pre)) return -1;
    if (pre > 8) return nd_br_get(br, bps, out);   /* escape code */

    x = (uint32_t)pre;
    if (k != 1) {
        uint32_t extra;
        if (k < 1 || k > 31) return -1;
        /* audit #17: peek already pulled the k bits into the window, so drop
         * them directly instead of recomputing the same bits via skip. */
        if (nd_br_peek(br, k, &extra)) return -1;
        x = (x << (unsigned)k) - x;                /* pre * (2^k - 1) */
        if (extra > 1) {
            x += extra - 1;
            nd_br_drop(br, (unsigned)k);
        } else {
            nd_br_drop(br, (unsigned)(k - 1));
        }
    }
    *out = x;
    return 0;
}

/* Rice partition decoding with the adaptive history and the zero-run
 * escape used by ALAC. */
static int nd_rice_decompress(nd_alac_dec *d, int32_t *out, int frames,
                              int bps, int hist_mult)
{
    nd_br   *br = &d->br;
    uint32_t history = d->cfg.mb;
    uint32_t mult    = (uint32_t)hist_mult;
    int32_t  sign_modifier = 0;
    int      i;

    for (i = 0; i < frames; i++) {
        uint32_t x;
        int      k;

        if (nd_br_bits_left(br) == 0) return ND_ERR_DECODE;

        k = nd_av_log2((history >> 9) + 3u);
        if (k > (int)d->cfg.kb) k = (int)d->cfg.kb;
        if (nd_decode_scalar(br, k, bps, &x)) return ND_ERR_DECODE;

        x += (uint32_t)sign_modifier;
        sign_modifier = 0;
        out[i] = (int32_t)((x >> 1) ^ (0u - (x & 1u)));

        if (x > 0xffffu)
            history = 0xffffu;
        else
            history += x * mult - ((history * mult) >> 9);

        if (history < 128u && i + 1 < frames) {
            int      kz;
            uint32_t block;

            kz = 7 - nd_av_log2(history) + (int)((history + 16u) >> 6);
            if (kz > (int)d->cfg.kb) kz = (int)d->cfg.kb;
            if (nd_decode_scalar(br, kz, 16, &block)) return ND_ERR_DECODE;

            if (block > 0) {
                if (block >= (uint32_t)(frames - i))
                    block = (uint32_t)(frames - i - 1);
                memset(&out[i + 1], 0, block * sizeof(int32_t));
                i += (int)block;
            }
            if (block <= 0xffffu) sign_modifier = 1;
            history = 0;
        }
    }
    return ND_OK;
}

/* Adaptive FIR (LPC) reconstruction, bit exact with the reference. */
static void nd_lpc_prediction(int32_t *residual, int32_t *out, int frames,
                              int bps, int16_t *coefs, int order, int quant)
{
    int       i;
    /* audit #10: no more (uint32_t *) punning of the int32_t buffer (strict
     * aliasing UB); 32-bit wraparound is expressed with explicit casts. */

    out[0] = residual[0];
    if (frames <= 1) return;

    if (order <= 0) {
        memmove(&out[1], &residual[1], (size_t)(frames - 1) * sizeof(int32_t));
        return;
    }
    if (order == ND_ALAC_MAX_ORDER) {
        for (i = 1; i < frames; i++)
            out[i] = nd_sign_extend((uint32_t)out[i - 1] + (uint32_t)residual[i], bps);
        return;
    }

    for (i = 1; i <= order && i < frames; i++)
        out[i] = nd_sign_extend((uint32_t)out[i - 1] + (uint32_t)residual[i], bps);

    for (; i < frames; i++) {
        uint32_t d     = (uint32_t)out[i - 1 - order];
        uint32_t error = (uint32_t)residual[i];
        uint32_t acc   = 0;
        int64_t  val;
        int32_t  value;
        int32_t  error_sign;
        int      j;

        for (j = 0; j < order; j++)
            acc += (uint32_t)(int32_t)((uint32_t)out[i - order + j] - d) *
                   (uint32_t)(int32_t)coefs[j];

        val = ((int64_t)(int32_t)acc + ((int64_t)1 << (quant - 1))) >> quant;
        value = (int32_t)(uint32_t)val;
        value = (int32_t)((uint32_t)value + d + error);
        out[i] = nd_sign_extend((uint32_t)value, bps);

        error_sign = nd_sign_only((int32_t)error);
        if (error_sign) {
            for (j = 0; j < order &&
                        (int32_t)(error * (uint32_t)error_sign) > 0; j++) {
                int32_t b    = (int32_t)(d - (uint32_t)out[i - order + j]);
                int32_t sgn  = nd_sign_only(b) * error_sign;

                coefs[j] = (int16_t)((int32_t)coefs[j] - sgn);
                b = (int32_t)((uint32_t)b * (uint32_t)sgn);
                error -= (uint32_t)(b >> quant) * (uint32_t)(j + 1);
            }
        }
    }
}

/* Undo the stereo decorrelation matrix.  The first channel carries the
 * "side" signal, the second one the mid/side combination. */
static void nd_decorrelate_stereo(int32_t *buf[2], int frames,
                                  int shift, int weight)
{
    int32_t *left  = buf[0];
    int32_t *right = buf[1];
    int      i;

    /* audit #10: same as above - no (uint32_t *) punning, explicit casts. */
    for (i = 0; i < frames; i++) {
        uint32_t a = (uint32_t)left[i];
        uint32_t b = (uint32_t)right[i];

        a -= (uint32_t)((int32_t)(b * (uint32_t)weight) >> shift);
        b += a;

        left[i]  = (int32_t)b;
        right[i] = (int32_t)a;
    }
}

static void nd_append_extra_bits(int32_t *buf[2], int32_t *extra_buf[2],
                                 int extra_bits, int channels, int frames)
{
    int ch, i;

    for (ch = 0; ch < channels; ch++)
        for (i = 0; i < frames; i++)
            buf[ch][i] = (int32_t)(((uint32_t)buf[ch][i] << (unsigned)extra_bits) |
                                   (uint32_t)extra_buf[ch][i]);
}

/* --------------------------------------------------------- elements */

static int nd_decode_element(nd_alac_dec *d, int ch_base, int channels)
{
    nd_br   *br = &d->br;
    uint32_t v;
    int      has_size, extra, compressed, frames, bps;
    int      decorr_shift = 0, decorr_weight = 0;
    int      ch, i;

    if (nd_br_skip(br, 4)) return ND_ERR_DECODE;   /* element instance tag */
    if (nd_br_skip(br, 12)) return ND_ERR_DECODE;  /* unused header bits   */
    if (nd_br_get(br, 1, &v)) return ND_ERR_DECODE;
    has_size = (int)v;
    if (nd_br_get(br, 2, &v)) return ND_ERR_DECODE;
    extra = (int)v << 3;
    if (extra == 24) return ND_ERR_DECODE;         /* 3 shifted bytes: invalid */
    if (nd_br_get(br, 1, &v)) return ND_ERR_DECODE;
    compressed = (v == 0);

    if (has_size) {
        if (nd_br_get(br, 32, &v)) return ND_ERR_DECODE;
        frames = (int)v;
    } else {
        frames = (int)d->cfg.frame_length;
    }
    if (frames <= 0 || frames > (int)d->cfg.frame_length) return ND_ERR_DECODE;
    if (d->have_frames && frames != d->frames) return ND_ERR_DECODE;
    d->frames      = frames;
    d->have_frames = 1;

    bps = (int)d->cfg.bit_depth - extra + channels - 1;
    if (bps < 1 || bps > 32) return ND_ERR_DECODE;

    if (compressed) {
        if (d->cfg.kb == 0) return ND_ERR_DECODE;

        if (nd_br_get(br, 8, &v)) return ND_ERR_DECODE;
        decorr_shift = (int)v;
        if (nd_br_get(br, 8, &v)) return ND_ERR_DECODE;
        decorr_weight = (int)v;
        if (channels == 2 && decorr_weight && decorr_shift > 31)
            return ND_ERR_DECODE;

        for (ch = 0; ch < channels; ch++) {
            int pred_type, quant, hist, order;

            if (nd_br_get(br, 4, &v)) return ND_ERR_DECODE;
            pred_type = (int)v;
            if (nd_br_get(br, 4, &v)) return ND_ERR_DECODE;
            quant = (int)v;
            if (nd_br_get(br, 3, &v)) return ND_ERR_DECODE;
            hist = (int)v;
            if (nd_br_get(br, 5, &v)) return ND_ERR_DECODE;
            order = (int)v;

            if (order >= (int)d->cfg.frame_length || quant == 0)
                return ND_ERR_DECODE;

            for (i = order - 1; i >= 0; i--) {
                uint32_t c;
                if (nd_br_get(br, 16, &c)) return ND_ERR_DECODE;
                d->coefs[ch][i] = (int16_t)c;
            }
            d->pred_type[ch] = pred_type;
            d->quant[ch]     = quant;
            d->order[ch]     = order;
            d->hist_mult[ch] = hist * (int)d->cfg.pb / 4;
        }

        if (extra) {
            size_t need = (size_t)frames * (size_t)channels * (size_t)extra;
            if (nd_br_bits_left(br) < need) return ND_ERR_DECODE;
            for (i = 0; i < frames; i++) {
                for (ch = 0; ch < channels; ch++) {
                    uint32_t xb;
                    if (nd_br_get(br, extra, &xb)) return ND_ERR_DECODE;
                    d->shiftbuf[ch][i] = (int32_t)xb;
                }
            }
        }

        for (ch = 0; ch < channels; ch++) {
            int rc = nd_rice_decompress(d, d->residual[ch], frames, bps,
                                        d->hist_mult[ch]);
            if (rc != ND_OK) return rc;
            if (d->pred_type[ch] == 15)
                nd_lpc_prediction(d->residual[ch], d->residual[ch], frames,
                                  bps, NULL, ND_ALAC_MAX_ORDER, 0);
            nd_lpc_prediction(d->residual[ch], d->samples[ch], frames, bps,
                              d->coefs[ch], d->order[ch], d->quant[ch]);
        }
    } else {
        size_t need = (size_t)frames * (size_t)channels * (size_t)d->cfg.bit_depth;
        if (nd_br_bits_left(br) < need) return ND_ERR_DECODE;
        for (i = 0; i < frames; i++) {
            for (ch = 0; ch < channels; ch++) {
                uint32_t s;
                if (nd_br_get(br, (int)d->cfg.bit_depth, &s)) return ND_ERR_DECODE;
                d->samples[ch][i] = nd_sign_extend(s, (int)d->cfg.bit_depth);
            }
        }
        extra = 0;
        decorr_shift = 0;
        decorr_weight = 0;
    }

    if (channels == 2 && decorr_weight)
        nd_decorrelate_stereo(d->samples, frames, decorr_shift, decorr_weight);

    if (extra)
        nd_append_extra_bits(d->samples, d->shiftbuf, extra, channels, frames);

    for (ch = 0; ch < channels; ch++)
        memcpy(d->chan[ch_base + ch], d->samples[ch],
               (size_t)frames * sizeof(int32_t));

    return ND_OK;
}

/* -------------------------------------------------------------- API */

nd_alac_dec *nd_alac_create(const nd_alac_config *cfg)
{
    nd_alac_dec *d;
    size_t       n;
    int          i;

    if (!cfg || !nd_alac_config_plausible(cfg)) return NULL;
    d = (nd_alac_dec *)calloc(1, sizeof(*d));
    if (!d) return NULL;
    d->cfg = *cfg;
    n = (size_t)cfg->frame_length * sizeof(int32_t);

    for (i = 0; i < 2; i++) {
        d->residual[i] = (int32_t *)malloc(n);
        d->samples[i]  = (int32_t *)malloc(n);
        d->shiftbuf[i] = (int32_t *)malloc(n);
        if (!d->residual[i] || !d->samples[i] || !d->shiftbuf[i])
            goto fail;
    }
    for (i = 0; i < cfg->channels; i++) {
        d->chan[i] = (int32_t *)malloc(n);
        if (!d->chan[i]) goto fail;
    }
    return d;

fail:
    nd_alac_destroy(d);
    return NULL;
}

void nd_alac_destroy(nd_alac_dec *d)
{
    int i;
    if (!d) return;
    for (i = 0; i < 2; i++) {
        free(d->residual[i]);
        free(d->samples[i]);
        free(d->shiftbuf[i]);
    }
    for (i = 0; i < ND_ALAC_MAX_CHANNELS; i++)
        free(d->chan[i]);
    free(d);
}

int nd_alac_decode(nd_alac_dec *d, const uint8_t *packet, size_t len,
                   int32_t *const **channels_out, int *frames)
{
    int ch = 0, got_end = 0;

    if (!d || !packet || !channels_out || !frames) return ND_ERR_DECODE;
    nd_br_init(&d->br, packet, len);
    d->frames      = 0;
    d->have_frames = 0;

    while (nd_br_bits_left(&d->br) >= 3) {
        uint32_t tag;
        int      element_channels, base;

        if (nd_br_get(&d->br, 3, &tag)) break;
        if (tag == ND_ALAC_ID_END) { got_end = 1; break; }

        switch (tag) {
        case ND_ALAC_ID_SCE:
        case ND_ALAC_ID_LFE:
            element_channels = 1;
            break;
        case ND_ALAC_ID_CPE:
            element_channels = 2;
            break;
        case ND_ALAC_ID_CCE:
        case ND_ALAC_ID_DSE:
        case ND_ALAC_ID_PCE:
        case ND_ALAC_ID_FIL:
        default:
            /* Channel coupling / data / program-config / fill elements are
             * not emitted by any known ALAC encoder.  Refuse the packet
             * rather than emitting wrong audio. */
            ND_ERR("alac: unsupported element %u", (unsigned)tag);
            return ND_ERR_UNSUPPORTED;
        }

        if (ch + element_channels > (int)d->cfg.channels) return ND_ERR_DECODE;
        base = (int)nd_alac_chan_offsets[d->cfg.channels - 1][ch];
        if (base + element_channels > (int)d->cfg.channels) return ND_ERR_DECODE;

        {
            int rc = nd_decode_element(d, base, element_channels);
            if (rc != ND_OK) return rc;
        }
        ch += element_channels;
    }

    if (!got_end) return ND_ERR_DECODE;
    if (ch != (int)d->cfg.channels || d->frames <= 0) return ND_ERR_DECODE;

    *channels_out = d->chan;
    *frames       = d->frames;
    return ND_OK;
}
