/* nd_pc_main.c - PC side test driver for the shared decoder sources.
 * Not part of the Android build. */
#include "nd_decode.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#if defined(_WIN32)
#include <windows.h>
#include <psapi.h>
#endif

static double nd_now_seconds(void)
{
#if defined(_WIN32)
    return (double)GetTickCount64() / 1000.0;
#else
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (double)ts.tv_sec + (double)ts.tv_nsec / 1e9;
#endif
}

static unsigned long long nd_peak_rss_bytes(void)
{
#if defined(_WIN32)
    PROCESS_MEMORY_COUNTERS pmc;
    if (GetProcessMemoryInfo(GetCurrentProcess(), &pmc, sizeof(pmc)))
        return (unsigned long long)pmc.PeakWorkingSetSize;
    return 0;
#else
    return 0;
#endif
}

/* Read the WAV header written by nd_decode_to_wav to report the decoded
 * duration (used for the realtime factor). */
static double nd_wav_seconds(const char *path)
{
    FILE *f = fopen(path, "rb");
    unsigned char h[44];
    unsigned long long data;
    unsigned long rate, block;
    double seconds = 0.0;

    if (!f) return 0.0;
    if (fread(h, 1, sizeof(h), f) == sizeof(h) &&
        memcmp(h, "RIFF", 4) == 0 && memcmp(h + 8, "WAVE", 4) == 0) {
        rate  = (unsigned long)h[24] | ((unsigned long)h[25] << 8) |
                ((unsigned long)h[26] << 16) | ((unsigned long)h[27] << 24);
        block = (unsigned long)h[32] | ((unsigned long)h[33] << 8);
        data  = (unsigned long long)h[40] | ((unsigned long long)h[41] << 8) |
                ((unsigned long long)h[42] << 16) |
                ((unsigned long long)h[43] << 24);
        if (rate && block) seconds = (double)data / (double)block / (double)rate;
    }
    fclose(f);
    return seconds;
}

int main(int argc, char **argv)
{
    char err[1024];
    int  max_seconds = 0;
    int  rc;
    double t0, t1;
    double audio_seconds;

    if (argc < 3) {
        fprintf(stderr, "usage: %s <input.m4a|input.flac> <output.wav> [maxSeconds]\n",
                argv[0]);
        return 2;
    }
    if (argc > 3) max_seconds = atoi(argv[3]);

    err[0] = 0;
    t0 = nd_now_seconds();
    rc = nd_decode_to_wav(argv[1], argv[2], max_seconds, err, (int)sizeof(err));
    t1 = nd_now_seconds();

    audio_seconds = rc == 0 ? nd_wav_seconds(argv[2]) : 0.0;
    printf("RC=%d\n", rc);
    printf("ERROR=%s\n", err);
    printf("SECONDS=%.3f\n", audio_seconds);
    printf("DECODE_MS=%.1f\n", (t1 - t0) * 1000.0);
    if (audio_seconds > 0.0 && t1 > t0)
        printf("REALTIME_X=%.1f\n", audio_seconds / (t1 - t0));
    printf("PEAK_RSS_MB=%.1f\n", (double)nd_peak_rss_bytes() / (1024.0 * 1024.0));
    return rc == 0 ? 0 : 1;
}
