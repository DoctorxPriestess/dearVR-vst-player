/* nd_mp4.c - minimal MP4/M4A demuxer for ALAC.
 *
 * Reads only what is needed to turn the sample tables into a packet list:
 * moov/trak/mdia/minf/stbl/{stsd,stsz,stsc,stco,co64}, plus the ALAC magic
 * cookie either from the "alac" sample entry child atom or from an esds
 * DecoderSpecificInfo descriptor.
 */
#include "nd_mp4.h"

#define FOURCC(a, b, c, d) ((uint32_t)(uint8_t)(a) << 24 | \
                            (uint32_t)(uint8_t)(b) << 16 | \
                            (uint32_t)(uint8_t)(c) << 8  | \
                            (uint32_t)(uint8_t)(d))

/* audit #9: the whole moov is read into memory, so keep its cap bounded.  It
 * cannot go below the size of a legitimate embedded cover art: the in-repo
 * test asset m4a_bigcover.m4a has an 18.2 MB moov in an 18.27 MB file.
 * 32 MB accepts that with headroom while still halving the old 64 MB cap. */
#define ND_MP4_MAX_MOOV_BYTES ((size_t)32 * 1024 * 1024)
#define ND_MP4_MAX_PACKETS    ((uint32_t)8 * 1000 * 1000)
#define ND_MP4_MAX_STSC       ((uint32_t)1000 * 1000)

struct nd_mp4 {
    FILE     *f;
    int64_t   file_size;
    nd_alac_config cfg;
    uint32_t  packet_count;
    uint32_t  uniform_size;
    uint32_t *sizes;
    uint64_t *offsets;
};

typedef struct nd_track {
    nd_alac_config cfg;
    int            have_cfg;
    uint32_t       sample_count;
    uint32_t       uniform_size;
    uint32_t      *sizes;
    int            have_stsz;
    uint32_t      *stsc_first;
    uint32_t      *stsc_spc;
    uint32_t       stsc_count;
    int            have_stsc;
    uint64_t      *chunks;
    uint32_t       chunk_count;
    int            have_stco;
} nd_track;

static void nd_track_free(nd_track *t)
{
    free(t->sizes);
    free(t->stsc_first);
    free(t->stsc_spc);
    free(t->chunks);
    memset(t, 0, sizeof(*t));
}

/* Iterate the boxes in [*pos, end).  Returns 1 with the next box, 0 at the
 * end of the range and -1 for malformed data.  The range start is implicit in
 * *pos (the old explicit `start` argument was never read, -Wextra). */
static int nd_next_box(const uint8_t *b, size_t end, size_t *pos,
                       uint32_t *type, size_t *payload_off, size_t *payload_len)
{
    size_t   off = *pos;
    uint64_t sz;
    size_t   hdr = 8;

    if (off >= end) return 0;
    if (end - off < 8) return -1;
    sz   = nd_rd_u32be(b + off);
    *type = nd_rd_u32be(b + off + 4);
    if (sz == 1) {
        if (end - off < 16) return -1;
        sz = nd_rd_u64be(b + off + 8);
        hdr = 16;
    } else if (sz == 0) {
        sz = (uint64_t)(end - off);
    }
    if (sz < hdr || sz > (uint64_t)(end - off)) return -1;
    *payload_off = off + hdr;
    *payload_len = (size_t)sz - hdr;
    *pos = off + (size_t)sz;
    return 1;
}

/* ---------------------------------------------------------- esds path */

static int nd_scan_descriptors(const uint8_t *p, size_t n,
                               nd_alac_config *cfg, int depth)
{
    size_t off = 0;

    if (depth > 8) return 0;
    while (off + 2 <= n) {
        uint8_t tag = p[off++];
        size_t  len = 0;
        int     i;
        const uint8_t *d;

        for (i = 0; i < 4 && off < n; i++) {
            uint8_t c = p[off++];
            len = (len << 7) | (size_t)(c & 0x7fu);
            if (!(c & 0x80u)) break;
        }
        if (off + len > n) return 0;
        d = p + off;
        if (tag == 0x03) {              /* ES_Descriptor */
            size_t skip = 3;
            if (len < 3) return 0;
            if (d[2] & 0x80u) skip += 2;                        /* dependsOn */
            if (d[2] & 0x40u) {                                 /* URL       */
                if (skip >= len) return 0;
                skip += 1 + (size_t)d[skip];
            }
            if (d[2] & 0x20u) skip += 2;                        /* OCR       */
            if (skip <= len && nd_scan_descriptors(d + skip, len - skip, cfg, depth + 1))
                return 1;
        } else if (tag == 0x04) {       /* DecoderConfigDescriptor */
            if (len >= 13 && nd_scan_descriptors(d + 13, len - 13, cfg, depth + 1))
                return 1;
        } else if (tag == 0x05) {       /* DecoderSpecificInfo */
            if (nd_alac_config_parse(d, len, cfg) == ND_OK) return 1;
        }
        off += len;
    }
    return 0;
}

/* ------------------------------------------------------- sample entry */

static int nd_walk_sample_entry_children(const uint8_t *b, size_t start,
                                         size_t end, nd_alac_config *cfg)
{
    size_t pos = start;

    while (1) {
        uint32_t type;
        size_t   po, pl;
        int      r = nd_next_box(b, end, &pos, &type, &po, &pl);

        if (r == 0) break;
        if (r < 0) return 0;

        if (type == FOURCC('a', 'l', 'a', 'c')) {
            if (nd_alac_config_parse(b + po, pl, cfg) == ND_OK) return 1;
        } else if (type == FOURCC('e', 's', 'd', 's')) {
            if (pl > 4 && nd_scan_descriptors(b + po + 4, pl - 4, cfg, 0))
                return 1;
        } else if (type == FOURCC('w', 'a', 'v', 'e')) {
            if (nd_walk_sample_entry_children(b, po, po + pl, cfg)) return 1;
        }
    }
    return 0;
}

static int nd_parse_sample_entry(const uint8_t *b, size_t off, size_t len,
                                 nd_alac_config *cfg)
{
    uint32_t fmt;
    uint16_t version;
    size_t   child;

    if (len < 36) return ND_ERR_UNSUPPORTED;
    fmt     = nd_rd_u32be(b + off + 4);
    if (fmt != FOURCC('a', 'l', 'a', 'c') && fmt != FOURCC('m', 'p', '4', 'a'))
        return ND_ERR_UNSUPPORTED;

    version = nd_rd_u16be(b + off + 16);
    child   = off + 36;
    if (version == 1)      child += 16;
    else if (version == 2) child += 36;
    if (child > off + len) return ND_ERR_UNSUPPORTED;

    if (nd_walk_sample_entry_children(b, child, off + len, cfg)) return ND_OK;
    return ND_ERR_UNSUPPORTED;
}

/* -------------------------------------------------------- sample tables */

static int nd_parse_stsd(nd_track *t, const uint8_t *b, size_t po, size_t pl)
{
    uint32_t count, i;
    size_t   off;

    if (pl < 8) return -1;
    count = nd_rd_u32be(b + po + 4);
    off   = po + 8;
    for (i = 0; i < count; i++) {
        uint32_t esize;
        if (po + pl - off < 8) return -1;
        esize = nd_rd_u32be(b + off);
        if (esize < 8 || esize > po + pl - off) return -1;
        if (!t->have_cfg &&
            nd_parse_sample_entry(b, off, esize, &t->cfg) == ND_OK)
            t->have_cfg = 1;
        off += esize;
        if (t->have_cfg) return 0;
    }
    return t->have_cfg ? 0 : -1;
}

static int nd_parse_stsz(nd_track *t, const uint8_t *b, size_t po, size_t pl)
{
    uint32_t uniform, count, i;

    if (pl < 12) return -1;
    uniform = nd_rd_u32be(b + po + 4);
    count   = nd_rd_u32be(b + po + 8);
    if (count == 0 || count > ND_MP4_MAX_PACKETS) return -1;
    t->sample_count = count;
    t->uniform_size = uniform;
    if (uniform == 0) {
        if ((size_t)count > (pl - 12) / 4) return -1;
        /* audit #19: release a previous table if a duplicate stsz appears. */
        free(t->sizes);
        t->sizes = (uint32_t *)malloc((size_t)count * sizeof(uint32_t));
        if (!t->sizes) return -1;
        for (i = 0; i < count; i++)
            t->sizes[i] = nd_rd_u32be(b + po + 12 + (size_t)i * 4);
    }
    t->have_stsz = 1;
    return 0;
}

static int nd_parse_stz2(nd_track *t, const uint8_t *b, size_t po, size_t pl)
{
    uint32_t field_size, count, i;

    if (pl < 12) return -1;
    field_size = b[po + 7];
    count      = nd_rd_u32be(b + po + 8);
    if (count == 0 || count > ND_MP4_MAX_PACKETS) return -1;
    t->sample_count = count;
    t->uniform_size = 0;
    /* audit #19: release a previous table if stz2/stsz is duplicated. */
    free(t->sizes);
    t->sizes = (uint32_t *)calloc(count, sizeof(uint32_t));
    if (!t->sizes) return -1;

    if (field_size == 16) {
        if ((size_t)count > (pl - 12) / 2) return -1;
        for (i = 0; i < count; i++)
            t->sizes[i] = nd_rd_u16be(b + po + 12 + (size_t)i * 2);
    } else if (field_size == 8) {
        if ((size_t)count > pl - 12) return -1;
        for (i = 0; i < count; i++)
            t->sizes[i] = b[po + 12 + i];
    } else if (field_size == 4) {
        if ((size_t)count > (pl - 12) * 2) return -1;
        for (i = 0; i < count; i++) {
            uint8_t v = b[po + 12 + i / 2];
            t->sizes[i] = (i & 1) ? (uint32_t)(v & 0x0f) : (uint32_t)(v >> 4);
        }
    } else {
        return -1;
    }
    t->have_stsz = 1;
    return 0;
}

static int nd_parse_stsc(nd_track *t, const uint8_t *b, size_t po, size_t pl)
{
    uint32_t count, i;

    if (pl < 8) return -1;
    count = nd_rd_u32be(b + po + 4);
    if (count == 0 || count > ND_MP4_MAX_STSC) return -1;
    if ((size_t)count > (pl - 8) / 12) return -1;
    /* audit #19: release a previous table if stsc is duplicated. */
    free(t->stsc_first);
    free(t->stsc_spc);
    t->stsc_first = (uint32_t *)malloc((size_t)count * sizeof(uint32_t));
    t->stsc_spc   = (uint32_t *)malloc((size_t)count * sizeof(uint32_t));
    if (!t->stsc_first || !t->stsc_spc) return -1;
    for (i = 0; i < count; i++) {
        t->stsc_first[i] = nd_rd_u32be(b + po + 8 + (size_t)i * 12);
        t->stsc_spc[i]   = nd_rd_u32be(b + po + 8 + (size_t)i * 12 + 4);
        if (t->stsc_first[i] == 0 || t->stsc_spc[i] == 0) return -1;
        if (i && t->stsc_first[i] <= t->stsc_first[i - 1]) return -1;
    }
    /* audit #19: the spec requires the first chunk index to be 1; otherwise
     * chunk 1 gets the wrong samples-per-chunk and every packet boundary is
     * shifted (wrong audio), so reject instead of decoding silently wrong. */
    if (t->stsc_first[0] != 1) return -1;
    t->stsc_count = count;
    t->have_stsc   = 1;
    return 0;
}

static int nd_parse_stco(nd_track *t, const uint8_t *b, size_t po, size_t pl,
                         int wide)
{
    uint32_t count, i;
    size_t   stride = wide ? 8u : 4u;

    if (pl < 8) return -1;
    count = nd_rd_u32be(b + po + 4);
    if (count == 0 || count > ND_MP4_MAX_PACKETS) return -1;
    if ((size_t)count > (pl - 8) / stride) return -1;
    /* audit #19: release a previous table if stco/co64 is duplicated. */
    free(t->chunks);
    t->chunks = (uint64_t *)malloc((size_t)count * sizeof(uint64_t));
    if (!t->chunks) return -1;
    for (i = 0; i < count; i++) {
        const uint8_t *p = b + po + 8 + (size_t)i * stride;
        t->chunks[i] = wide ? nd_rd_u64be(p) : (uint64_t)nd_rd_u32be(p);
    }
    t->chunk_count = count;
    t->have_stco   = 1;
    return 0;
}

static int nd_parse_stbl(nd_track *t, const uint8_t *b, size_t off, size_t len)
{
    size_t   pos = off, end = off + len;
    uint32_t type;
    size_t   po, pl;

    while (1) {
        int r = nd_next_box(b, end, &pos, &type, &po, &pl);
        if (r == 0) break;
        if (r < 0) return -1;
        if (type == FOURCC('s', 't', 's', 'd')) {
            if (nd_parse_stsd(t, b, po, pl) != 0) return -1;
        } else if (type == FOURCC('s', 't', 's', 'z')) {
            if (nd_parse_stsz(t, b, po, pl) != 0) return -1;
        } else if (type == FOURCC('s', 't', 'z', '2')) {
            if (nd_parse_stz2(t, b, po, pl) != 0) return -1;
        } else if (type == FOURCC('s', 't', 's', 'c')) {
            if (nd_parse_stsc(t, b, po, pl) != 0) return -1;
        } else if (type == FOURCC('s', 't', 'c', 'o')) {
            if (nd_parse_stco(t, b, po, pl, 0) != 0) return -1;
        } else if (type == FOURCC('c', 'o', '6', '4')) {
            if (nd_parse_stco(t, b, po, pl, 1) != 0) return -1;
        }
    }
    return 0;
}

static int nd_parse_minf(nd_track *t, const uint8_t *b, size_t off, size_t len)
{
    size_t   pos = off, end = off + len;
    uint32_t type;
    size_t   po, pl;

    while (1) {
        int r = nd_next_box(b, end, &pos, &type, &po, &pl);
        if (r == 0) break;
        if (r < 0) return -1;
        if (type == FOURCC('s', 't', 'b', 'l'))
            return nd_parse_stbl(t, b, po, pl);
    }
    return -1;
}

static int nd_parse_mdia(nd_track *t, const uint8_t *b, size_t off, size_t len)
{
    size_t   pos = off, end = off + len;
    uint32_t type;
    size_t   po, pl;

    while (1) {
        int r = nd_next_box(b, end, &pos, &type, &po, &pl);
        if (r == 0) break;
        if (r < 0) return -1;
        if (type == FOURCC('m', 'i', 'n', 'f'))
            return nd_parse_minf(t, b, po, pl);
    }
    return -1;
}

static int nd_parse_trak(nd_track *t, const uint8_t *b, size_t off, size_t len)
{
    size_t   pos = off, end = off + len;
    uint32_t type;
    size_t   po, pl;

    memset(t, 0, sizeof(*t));
    while (1) {
        int r = nd_next_box(b, end, &pos, &type, &po, &pl);
        if (r == 0) break;
        if (r < 0) return -1;
        if (type == FOURCC('m', 'd', 'i', 'a')) {
            if (nd_parse_mdia(t, b, po, pl) != 0) return -1;
        }
    }
    if (!t->have_cfg || !t->have_stsz || !t->have_stsc || !t->have_stco)
        return -1;
    return 0;
}

/* Turn chunk offsets plus the sample-to-chunk table into per packet file
 * offsets and reject any packet that falls outside the file. */
static int nd_build_offsets(nd_mp4 *m, nd_track *t)
{
    uint32_t i;
    size_t   s = 0;
    uint32_t e = 0;

    /* audit #9: an ALAC packet is at least 3 bytes (header + residual + END),
     * so cap the packet count by the file size instead of allocating a 64 MB
     * offsets table on behalf of a few-MB malformed file. */
    if ((uint64_t)t->sample_count > (uint64_t)m->file_size / 3u) return ND_ERR_DECODE;

    m->offsets = (uint64_t *)malloc((size_t)t->sample_count * sizeof(uint64_t));
    if (!m->offsets) return ND_ERR_DECODE;

    for (i = 0; i < t->chunk_count && s < t->sample_count; i++) {
        uint64_t off = t->chunks[i];
        uint32_t spc, j;

        while (e + 1 < t->stsc_count && t->stsc_first[e + 1] <= i + 1) e++;
        spc = t->stsc_spc[e];

        for (j = 0; j < spc && s < t->sample_count; j++) {
            uint32_t size = t->uniform_size ? t->uniform_size : t->sizes[s];
            if (size == 0 || off > (uint64_t)m->file_size ||
                size > (uint64_t)m->file_size - off)
                return ND_ERR_DECODE;
            m->offsets[s] = off;
            off += size;
            s++;
        }
    }
    if (s != t->sample_count) return ND_ERR_DECODE;

    m->cfg          = t->cfg;
    m->packet_count = t->sample_count;
    m->uniform_size = t->uniform_size;
    if (!t->uniform_size) {
        m->sizes = t->sizes;
        t->sizes = NULL;
    }
    return ND_OK;
}

/* ------------------------------------------------------------ file I/O */

static int nd_load_moov(FILE *f, int64_t fsize, uint8_t **out, size_t *out_len)
{
    int64_t off = 0;

    *out = NULL;
    *out_len = 0;
    while (off + 8 <= fsize) {
        uint8_t  hdr[16];
        uint64_t sz;
        uint32_t type;
        size_t   hdrsz = 8;

        if (nd_fseek(f, off) != 0) return ND_ERR_OPEN;
        if (fread(hdr, 1, 8, f) != 8) return ND_ERR_OPEN;
        sz   = nd_rd_u32be(hdr);
        type = nd_rd_u32be(hdr + 4);
        if (sz == 1) {
            if (fread(hdr + 8, 1, 8, f) != 8) return ND_ERR_DECODE;
            sz = nd_rd_u64be(hdr + 8);
            hdrsz = 16;
        } else if (sz == 0) {
            sz = (uint64_t)(fsize - off);
        }
        if (sz < hdrsz || (uint64_t)off + sz > (uint64_t)fsize)
            return ND_ERR_DECODE;
        if (type == FOURCC('m', 'o', 'o', 'v')) {
            size_t len = (size_t)(sz - hdrsz);
            if (len > ND_MP4_MAX_MOOV_BYTES) return ND_ERR_UNSUPPORTED;
            *out = (uint8_t *)malloc(len ? len : 1);
            if (!*out) return ND_ERR_OPEN;
            if (nd_fseek(f, off + (int64_t)hdrsz) != 0 ||
                fread(*out, 1, len, f) != len) {
                free(*out);
                *out = NULL;
                return ND_ERR_DECODE;
            }
            *out_len = len;
            return ND_OK;
        }
        if (type == FOURCC('m', 'd', 'a', 't') && sz == 0)
            break;
        off += (int64_t)sz;
    }
    return ND_ERR_UNSUPPORTED;
}

int nd_mp4_open(nd_mp4 **out, const char *path, char *err, int errlen)
{
    nd_mp4  *m;
    uint8_t *moov = NULL;
    size_t   moov_len = 0;
    int      rc;

    *out = NULL;
    m = (nd_mp4 *)calloc(1, sizeof(*m));
    if (!m) return ND_ERR_OPEN;
    m->f = nd_fopen_utf8(path, 0);
    if (!m->f) {
        nd_set_err(err, errlen, "cannot open input: %s", path);
        free(m);
        return ND_ERR_OPEN;
    }
    {
        long long sz;
        if (nd_fseek(m->f, 0) != 0) { rc = ND_ERR_OPEN; goto fail; }
#if defined(_WIN32)
        _fseeki64(m->f, 0, SEEK_END);
        sz = _ftelli64(m->f);
#else
        fseeko(m->f, 0, SEEK_END);
        sz = (long long)ftello(m->f);
#endif
        if (sz <= 0) {
            nd_set_err(err, errlen, "empty or unreadable input");
            rc = ND_ERR_OPEN;
            goto fail;
        }
        m->file_size = (int64_t)sz;
    }

    rc = nd_load_moov(m->f, m->file_size, &moov, &moov_len);
    if (rc != ND_OK) {
        nd_set_err(err, errlen, "no usable moov box (unsupported or corrupt MP4)");
        goto fail;
    }

    {
        size_t   pos = 0, end = moov_len;
        uint32_t type;
        size_t   po, pl;
        int      found = 0;

        while (1) {
            int r = nd_next_box(moov, end, &pos, &type, &po, &pl);
            nd_track t;
            if (r == 0) break;
            if (r < 0) break;
            if (type != FOURCC('t', 'r', 'a', 'k')) continue;
            if (nd_parse_trak(&t, moov, po, pl) != 0) {
                nd_track_free(&t);
                continue;
            }
            rc = nd_build_offsets(m, &t);
            nd_track_free(&t);
            if (rc == ND_OK) { found = 1; break; }
            nd_set_err(err, errlen, "ALAC sample table is corrupt or truncated");
            free(moov);
            nd_mp4_close(m);
            return rc;
        }
        free(moov);
        if (!found) {
            nd_set_err(err, errlen, "no ALAC track found in MP4 container");
            nd_mp4_close(m);
            return ND_ERR_UNSUPPORTED;
        }
    }
    *out = m;
    return ND_OK;

fail:
    free(moov);
    if (m->f) fclose(m->f);
    free(m);
    return rc;
}

void nd_mp4_close(nd_mp4 *m)
{
    if (!m) return;
    if (m->f) fclose(m->f);
    free(m->sizes);
    free(m->offsets);
    free(m);
}

const nd_alac_config *nd_mp4_config(const nd_mp4 *m)
{
    return m ? &m->cfg : NULL;
}

uint32_t nd_mp4_packet_count(const nd_mp4 *m)
{
    return m ? m->packet_count : 0;
}

int nd_mp4_packet_info(const nd_mp4 *m, uint32_t index,
                       uint64_t *offset, uint32_t *size)
{
    if (!m || index >= m->packet_count) return ND_ERR_DECODE;
    *offset = m->offsets[index];
    *size   = m->uniform_size ? m->uniform_size : m->sizes[index];
    return ND_OK;
}

int nd_mp4_read_packet(nd_mp4 *m, uint32_t index, uint8_t *buf, uint32_t size)
{
    uint64_t off;
    uint32_t want;

    if (nd_mp4_packet_info(m, index, &off, &want) != ND_OK) return ND_ERR_DECODE;
    if (want != size) return ND_ERR_DECODE;
    if (nd_fseek(m->f, (int64_t)off) != 0) return ND_ERR_DECODE;
    if (fread(buf, 1, size, m->f) != size) return ND_ERR_DECODE;
    return ND_OK;
}
