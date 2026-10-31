/* nd_mp4.h - minimal MP4/M4A demuxer for ALAC sample tables. */
#ifndef ND_MP4_H
#define ND_MP4_H

#include "nd_common.h"
#include "nd_alac.h"

typedef struct nd_mp4 nd_mp4;

/* Opens the file, locates the first ALAC audio track and materialises the
 * packet offset table.  Returns ND_OK, ND_ERR_OPEN, ND_ERR_UNSUPPORTED or
 * ND_ERR_DECODE; err receives a human readable reason. */
int  nd_mp4_open(nd_mp4 **out, const char *path, char *err, int errlen);
void nd_mp4_close(nd_mp4 *m);

const nd_alac_config *nd_mp4_config(const nd_mp4 *m);
uint32_t              nd_mp4_packet_count(const nd_mp4 *m);

/* Packet byte range inside the file. */
int nd_mp4_packet_info(const nd_mp4 *m, uint32_t index,
                       uint64_t *offset, uint32_t *size);

/* Reads one packet into buf. */
int nd_mp4_read_packet(nd_mp4 *m, uint32_t index, uint8_t *buf, uint32_t size);

#endif /* ND_MP4_H */
