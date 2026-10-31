/* nd_alac.h - self contained Apple Lossless (ALAC) decoder.
 *
 * The decoder works on raw ALAC packets (one packet == one ALAC "frame").
 * Container demuxing lives in nd_mp4.c.
 */
#ifndef ND_ALAC_H
#define ND_ALAC_H

#include <stdint.h>
#include <stddef.h>

/* ALACSpecificConfig, either the bare 24 byte form or the 36 byte
 * QuickTime "alac" atom (12 byte box header followed by the config). */
typedef struct nd_alac_config {
    uint32_t frame_length;     /* max samples per frame            */
    uint8_t  bit_depth;        /* 16 / 20 / 24 / 32                */
    uint8_t  pb;               /* rice history multiplier (40)     */
    uint8_t  mb;               /* initial history (10)             */
    uint8_t  kb;               /* rice parameter limit (14)        */
    uint8_t  channels;         /* 1..8                             */
    uint16_t max_run;          /* unused by the decoder            */
    uint32_t max_frame_bytes;  /* unused by the decoder            */
    uint32_t avg_bitrate;      /* informational                    */
    uint32_t sample_rate;
} nd_alac_config;

/* Parse a magic cookie.  Accepts 24, 28 or 36 byte layouts; returns 0 when
 * the result looks like a sane ALAC configuration. */
int nd_alac_config_parse(const uint8_t *cookie, size_t len, nd_alac_config *out);

typedef struct nd_alac_dec nd_alac_dec;

nd_alac_dec *nd_alac_create(const nd_alac_config *cfg);
void         nd_alac_destroy(nd_alac_dec *dec);

/* Decode one packet.  On success returns 0, sets *frames and points
 * *channels_out at the internal per-channel int32 buffers (channel count
 * fixed at creation time).  Returns a negative ND_* code on any bitstream
 * error; no partial packet is ever reported as success. */
int nd_alac_decode(nd_alac_dec *dec, const uint8_t *packet, size_t len,
                   int32_t *const **channels_out, int *frames);

#endif /* ND_ALAC_H */
