/* nd_decode.h - C API used by the JNI bridge (and by the PC test tool). */
#ifndef ND_DECODE_H
#define ND_DECODE_H

#include "nd_common.h"

#ifdef __cplusplus
extern "C" {
#endif

/* Decode an ALAC (.m4a/.mp4) or FLAC file to a little-endian PCM WAV file.
 *
 * in_path / out_path : UTF-8 file names (wide-char converted on Windows).
 * max_seconds        : stop after this many seconds; <= 0 decodes the file
 *                      in full.
 * err_buf / err_len  : optional human readable error text.
 *
 * Returns ND_OK (0) on success, or one of the negative ND_ERR_* codes:
 *   -1 input/output open failure
 *   -2 unsupported container, codec or configuration
 *   -3 bitstream / sample table corruption (includes truncated files)
 *   -4 output write failure
 */
int nd_decode_to_wav(const char *in_path, const char *out_path, int max_seconds,
                     char *err_buf, int err_len);

#ifdef __cplusplus
}
#endif

#endif /* ND_DECODE_H */
