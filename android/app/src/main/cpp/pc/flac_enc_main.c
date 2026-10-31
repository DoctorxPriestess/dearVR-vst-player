/* flac_enc_main.c - host side driver for nd_encode_flac.

 * Not part of the Android build. It exists so the encoder can be exercised and
 * compared against ffmpeg on a workstation, without a device:

 *   flacenctest in.wav out.flac [level]
 */
#include "nd_flac_end.h"

#include <stdio.h>
#include <stdlib.h>

int main(int argc, char **argv)
{
    char err[512];
    int  level = 5;
    int  rc;

    if (argc < 3) {
        fprintf(stderr, "usage: %s in.wav out.flac [level]\n", argv[0]);
        return 2;
    }
    if (argc > 3) level = atoi(argv[3]);

    rc = nd_encode_flac(argv[1], argv[2], level, err, (int)sizeof(err));
    if (rc != ND_ENC_OK) {
        fprintf(stderr, "encode failed (%d): %s\n", rc, err);
        return 1;
    }
    printf("ok %s -> %s (level %d)\n", argv[1], argv[2], level);
    return 0;
}
