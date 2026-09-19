/* See dmap.h. */
#include <string.h>
#include "dmap.h"

/* DMAP is a flat/nested sequence of items: 4-byte ASCII code, 4-byte big-endian
 * length, then that many payload bytes. Containers hold nested items; we detect
 * them heuristically (payload begins with a plausible nested item) so we don't
 * need a hardcoded container table. Leaf codes we care about:
 *   minm = item name (track title), asar = artist, asal = album, asyr = year. */

static unsigned int be32(const unsigned char *p) {
    return ((unsigned int) p[0] << 24) | ((unsigned int) p[1] << 16) |
           ((unsigned int) p[2] << 8) | (unsigned int) p[3];
}

static void copy_str(char *dst, size_t cap, const unsigned char *src, unsigned int len, int *have) {
    if (len >= cap) {
        len = (unsigned int) cap - 1;
    }
    memcpy(dst, src, len);
    dst[len] = '\0';
    *have = 1;
}

static int printable_code(const unsigned char *p) {
    for (int i = 0; i < 4; i++) {
        if (p[i] < 0x20 || p[i] > 0x7e) {
            return 0;
        }
    }
    return 1;
}

static void parse(const unsigned char *data, size_t len, dmap_meta_t *out, int depth) {
    if (depth > 6) {
        return;
    }
    size_t pos = 0;
    while (pos + 8 <= len) {
        const unsigned char *code = data + pos;
        unsigned int ilen = be32(data + pos + 4);
        const unsigned char *payload = data + pos + 8;
        if (ilen > len - pos - 8) {
            break;   /* truncated / not really DMAP */
        }
        if (!memcmp(code, "minm", 4)) {
            copy_str(out->title, sizeof(out->title), payload, ilen, &out->have_title);
        } else if (!memcmp(code, "asar", 4)) {
            copy_str(out->artist, sizeof(out->artist), payload, ilen, &out->have_artist);
        } else if (!memcmp(code, "asal", 4)) {
            copy_str(out->album, sizeof(out->album), payload, ilen, &out->have_album);
        } else if (!memcmp(code, "asyr", 4)) {
            if (ilen == 2) {
                out->year = (payload[0] << 8) | payload[1];
            } else if (ilen == 4) {
                out->year = (int) be32(payload);
            }
        } else if (ilen >= 8 && printable_code(payload) && be32(payload + 4) <= ilen - 8) {
            /* looks like a nested container (e.g. mlit) */
            parse(payload, ilen, out, depth + 1);
        }
        pos += 8 + ilen;
    }
}

void dmap_parse(const unsigned char *data, size_t len, dmap_meta_t *out) {
    memset(out, 0, sizeof(*out));
    if (data && len >= 8) {
        parse(data, len, out, 0);
    }
}
