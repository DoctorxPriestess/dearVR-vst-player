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

/* audit #17: sliding 64-bit window bit reader.
 *
 * Invariant: `win` is left aligned and holds the winbits bits that start at
 * br->bitpos.  A refill reloads up to 8 bytes from br->bitpos (8 byte loads)
 * and happens about once per 64 consumed bits; the old version ran that same
 * 8-byte loop on every single nd_br_get() call. */
typedef struct nd_br {
    const uint8_t *data;
    size_t         nbits;
    size_t         bitpos;
    uint64_t       win;
    unsigned       winbits;
} nd_br;

static inline void nd_br_refill(nd_br *br)
{
    size_t   bit    = br->bitpos;
    size_t   byte   = bit >> 3;
    unsigned bitoff = (unsigned)(bit & 7u);
    size_t   avail  = (br->nbits >> 3) - byte;
    unsigned take   = avail < 8u ? (unsigned)avail : 8u;
    uint64_t acc    = 0;
    size_t   loaded, valid;
    unsigned i;

    for (i = 0; i < take; i++)
        acc |= (uint64_t)br->data[byte + i] << (56u - 8u * i);

    br->win = acc << bitoff;
    loaded  = (size_t)take * 8u;
    loaded  = loaded > bitoff ? loaded - bitoff : 0;
    valid   = br->nbits - bit;
    br->winbits = (unsigned)(loaded < valid ? loaded : valid);
}

static inline void nd_br_init(nd_br *br, const uint8_t *data, size_t nbytes)
{
    br->data    = data;
    br->nbits   = nbytes * 8u;
    br->bitpos  = 0;
    br->win     = 0;
    br->winbits = 0;
}

static inline size_t nd_br_bits_left(const nd_br *br)
{
    return br->bitpos < br->nbits ? br->nbits - br->bitpos : 0;
}

/* Consume n bits already present in the window (n <= winbits). */
static inline void nd_br_drop(nd_br *br, unsigned n)
{
    br->win = (n >= 64u) ? 0u : (br->win << n);   /* n == 64 would be UB */
    br->winbits -= n;
    br->bitpos  += n;
}

/* Read n (0..32) bits, MSB first. Returns 0 on success, -1 on exhaustion. */
static inline int nd_br_get(nd_br *br, int n, uint32_t *out)
{
    const unsigned un = (unsigned)n;

    if (n <= 0) { *out = 0; return 0; }
    if ((size_t)n > nd_br_bits_left(br)) return -1;
    if (br->winbits < un) nd_br_refill(br);
    if (br->winbits < un) return -1;
    *out = (uint32_t)(br->win >> (64u - un));
    nd_br_drop(br, un);
    return 0;
}

/* Peek n (0..32) bits without advancing. */
static inline int nd_br_peek(nd_br *br, int n, uint32_t *out)
{
    const unsigned un = (unsigned)n;

    if (n <= 0) { *out = 0; return 0; }
    if ((size_t)n > nd_br_bits_left(br)) return -1;
    if (br->winbits < un) nd_br_refill(br);
    if (br->winbits < un) return -1;
    *out = (uint32_t)(br->win >> (64u - un));
    return 0;
}

/* Skip n bits (n may be 0). */
static inline int nd_br_skip(nd_br *br, size_t n)
{
    if (br->bitpos > br->nbits || n > br->nbits - br->bitpos) return -1;
    if (n <= (size_t)br->winbits) nd_br_drop(br, (unsigned)n);
    else { br->winbits = 0; br->bitpos += n; }
    return 0;
}

/* Count leading 1 bits, at most 9 (mirrors the reference "unary 0..9"
 * code). A terminating 0 bit is consumed when one is found. */
static inline int nd_br_unary9(nd_br *br, int *out)
{
    int n = 0;
    while (n < 9) {
        uint32_t b;
        if (nd_br_get(br, 1, &b)) return -1;
        if (b == 0) { *out = n; return 0; }
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
