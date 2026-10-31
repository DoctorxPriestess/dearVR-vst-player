/* nd_flac_end.h - PCM WAV to FLAC encoding on top of the vendored libFLAC.

   The render pipeline always produces PCM (the plugin host writes a WAV), and the
   export step wants a lossless compressed file for a lossless source.  Encoding is
   done natively because the platform has no FLAC encoder at all.

   The functions here never throw: they return 0 (or a negative code) and fill the
   caller supplied error buffer with a human readable reason.
*/
#ifndef ND_FLAC_END_H
#define ND_FLAC_END_H

#ifdef __cplusplus
extern "C" {
#endif

/** Result codes, mirrored by com.vstplayer.core.NativeDecode.encodeFlac. */
#define ND_ENC_OK          0
#define ND_ENC_ERR_OPEN   (-1)   /* input or output could not be opened */
#define ND_ENC_ERR_WAV    (-2)   /* container is not integer PCM, or is malformed */
#define ND_ENC_ERR_INIT   (-3)   /* libFLAC rejected the stream settings */
#define ND_ENC_ERR_WRITE  (-4)   /* encoder failed while writing samples */
#define ND_ENC_ERR_OOM    (-5)   /* buffer allocation failed */

/**
 * Encodes an integer PCM WAV file (16, 24 or 32 bit, 1..8 channels) into FLAC.
 *
 * @param wav_path   source PCM file
 * @param flac_path  destination; created or truncated
 * @param level      libFLAC compression level, 0..8 (clamped)
 * @param err        error buffer, may be NULL
 * @param errlen     size of err
 * @return ND_ENC_OK on success, another ND_ENC_* code on failure
 */
int nd_encode_flac(const char *wav_path, const char *flac_path, int level,
                   char *err, int errlen);

#ifdef __cplusplus
}
#endif

#endif
