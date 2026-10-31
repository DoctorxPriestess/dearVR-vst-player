/* nd_common.h - shared helpers for the VST player native decoder.
 *
 * Contains the error code contract, portable logging, a big-endian bit
 * reader (ALAC is an MSB-first bitstream), and small utility helpers.
 * Pure C11, no dynamic allocations outside the callers.
 */
#ifndef ND_COMMON_H
#define ND_COMMON_H

#include <stdint.h>
#include <stddef.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* Public error codes, mirrored in NativeDecode.java. */
#define ND_OK              0
#define ND_ERR_OPEN      (-1)   /* file could not be opened / written   */
#define ND_ERR_UNSUPPORTED (-2) /* container / codec not supported      */
#define ND_ERR_DECODE    (-3)   /* bitstream or sample table is corrupt */
#define ND_ERR_WRITE     (-4)   /* output file write failed             */

#ifdef __ANDROID__
#include <android/log.h>
#define ND_TAG "vstdecode"
#define ND_LOG(...)  __android_log_print(ANDROID_LOG_DEBUG, ND_TAG, __VA_ARGS__)
#define ND_ERR(...)  __android_log_print(ANDROID_LOG_ERROR, ND_TAG, __VA_ARGS__)
#else
#if defined(ND_DEBUG_LOG)
#include <stdio.h>
#define ND_LOG(...)  do { fprintf(stderr, "[nd] " __VA_ARGS__); fputc('\n', stderr); } while (0)
#define ND_ERR(...)  do { fprintf(stderr, "[nd!] " __VA_ARGS__); fputc('\n', stderr); } while (0)
#else
#define ND_LOG(...)  ((void)0)
#define ND_ERR(...)  ((void)0)
#endif
#endif

#if defined(_WIN32)
#include <intrin.h>
#endif

/* number of leading zero bits (0..32) */
static inline int nd_clz32(uint32_t v)
{
#if defined(__GNUC__) || defined(__clang__)
    return v ? __builtin_clz(v) : 32;
#elif defined(_MSC_VER)
    unsigned long idx;
    return _BitScanReverse(&idx, (unsigned long)v) ? (int)(31u - idx) : 32;
#else
    int n = 0;
    if (!v) return 32;
    while (!(v & 0x80000000u)) { v <<= 1; n++; }
    return n;
#endif
}

/* floor(log2(v)) for v>0; 0 for v==0 (matches the reference decoder). */
static inline int nd_av_log2(uint32_t v)
{
    return v ? 31 - nd_clz32(v) : 0;
}

/* ---------------------------------------------------------------- bits */

typedef struct nd_br {
    const uint8_t *data;
    size_t         nbits;
    size_t         bitpos;
} nd_br;

static inline void nd_br_init(nd_br *br, const uint8_t *data, size_t nbytes)
{
    br->data   = data;
    br->nbits  = nbytes * 8u;
    br->bitpos = 0;
}

static inline size_t nd_br_bits_left(const nd_br *br)
{
    return br->bitpos < br->nbits ? br->nbits - br->bitpos : 0;
}

/* Read n (0..32) bits, MSB first. Returns 0 on success, -1 on exhaustion. */
static inline int nd_br_get(nd_br *br, int n, uint32_t *out)
{
    size_t bit, byte, avail;
    uint64_t acc = 0;
    unsigned bitoff, i, take;

    if (n <= 0) { *out = 0; return 0; }
    bit = br->bitpos;
    if (bit > br->nbits || (size_t)n > br->nbits - bit) return -1;
    byte   = bit >> 3;
    bitoff = (unsigned)(bit & 7u);
    avail  = (br->nbits >> 3) - byte;
    take   = avail < 8u ? (unsigned)avail : 8u;
    for (i = 0; i < take; i++)
        acc |= (uint64_t)br->data[byte + i] << (56u - 8u * i);
    *out = (uint32_t)((acc << bitoff) >> (64 - (unsigned)n));
    br->bitpos = bit + (size_t)n;
    return 0;
}

/* Peek n (0..32) bits without advancing. */
static inline int nd_br_peek(const nd_br *br, int n, uint32_t *out)
{
    size_t bit, byte, avail;
    uint64_t acc = 0;
    unsigned bitoff, i, take;

    if (n <= 0) { *out = 0; return 0; }
    bit = br->bitpos;
    if (bit > br->nbits || (size_t)n > br->nbits - bit) return -1;
    byte   = bit >> 3;
    bitoff = (unsigned)(bit & 7u);
    avail  = (br->nbits >> 3) - byte;
    take   = avail < 8u ? (unsigned)avail : 8u;
    for (i = 0; i < take; i++)
        acc |= (uint64_t)br->data[byte + i] << (56u - 8u * i);
    *out = (uint32_t)((acc << bitoff) >> (64 - (unsigned)n));
    return 0;
}

/* Skip n bits (n may be 0). */
static inline int nd_br_skip(nd_br *br, size_t n)
{
    if (br->bitpos > br->nbits || n > br->nbits - br->bitpos) return -1;
    br->bitpos += n;
    return 0;
}

/* Count leading 1 bits, at most 9 (mirrors the reference "unary 0..9"
 * code). A terminating 0 bit is consumed when one is found. */
static inline int nd_br_unary9(nd_br *br, int *out)
{
    int n = 0;
    while (n < 9) {
        uint8_t b;
        if (br->bitpos >= br->nbits) return -1;
        b = br->data[br->bitpos >> 3];
        br->bitpos++;
        if (((b >> (7u - (unsigned)((br->bitpos - 1) & 7u))) & 1u) == 0) {
            *out = n;
            return 0;
        }
        n++;
    }
    *out = 9;
    return 0;
}

/* --------------------------------------------------------- byte access */

static inline uint32_t nd_rd_u32be(const uint8_t *p)
{
    return ((uint32_t)p[0] << 24) | ((uint32_t)p[1] << 16) |
           ((uint32_t)p[2] << 8)  |  (uint32_t)p[3];
}

static inline uint32_t nd_rd_u24be(const uint8_t *p)
{
    return ((uint32_t)p[0] << 16) | ((uint32_t)p[1] << 8) | (uint32_t)p[2];
}

static inline uint16_t nd_rd_u16be(const uint8_t *p)
{
    return (uint16_t)(((uint16_t)p[0] << 8) | (uint16_t)p[1]);
}

static inline uint64_t nd_rd_u64be(const uint8_t *p)
{
    return ((uint64_t)nd_rd_u32be(p) << 32) | (uint64_t)nd_rd_u32be(p + 4);
}

/* -------------------------------------------------------- file helpers */

#if defined(_WIN32)
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#include <wchar.h>
#define nd_fseek(f, off) _fseeki64((f), (long long)(off), SEEK_SET)
#define nd_ftell(f)      _ftelli64(f)
#else
#include <sys/types.h>
#define nd_fseek(f, off) fseeko((f), (off_t)(off), SEEK_SET)
#define nd_ftell(f)      ftello(f)
#endif

/* fopen that accepts UTF-8 paths on every supported platform. */
static inline FILE *nd_fopen_utf8(const char *path, int for_write)
{
#if defined(_WIN32)
    wchar_t wpath[1024];
    wchar_t wmode[4];
    int n = MultiByteToWideChar(CP_UTF8, 0, path, -1, wpath, 1024);
    if (n <= 0) return NULL;
    wmode[0] = for_write ? L'w' : L'r';
    wmode[1] = L'b';
    wmode[2] = 0;
    return _wfopen(wpath, wmode);
#else
    return fopen(path, for_write ? "wb" : "rb");
#endif
}

/* Snprintf-style error reporting into the caller supplied buffer. */
static inline void nd_set_err(char *buf, int len, const char *fmt, ...)
{
    if (buf && len > 0) {
        va_list ap;
        va_start(ap, fmt);
        vsnprintf(buf, (size_t)len, fmt, ap);
        va_end(ap);
        buf[len - 1] = 0;
    }
}

#endif /* ND_COMMON_H */
