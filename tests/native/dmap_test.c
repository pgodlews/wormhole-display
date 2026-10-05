/* Host tests for the DMAP now-playing parser (app/src/main/cpp/dmap.c). */
#include <assert.h>
#include <stdio.h>
#include <string.h>
#include "dmap.h"

static size_t put_item(unsigned char *dst, const char *code, const void *payload, unsigned int len) {
    memcpy(dst, code, 4);
    dst[4] = (unsigned char) (len >> 24);
    dst[5] = (unsigned char) (len >> 16);
    dst[6] = (unsigned char) (len >> 8);
    dst[7] = (unsigned char) len;
    if (len) memcpy(dst + 8, payload, len);
    return 8 + len;
}

static size_t put_str(unsigned char *dst, const char *code, const char *s) {
    return put_item(dst, code, s, (unsigned int) strlen(s));
}

static void test_nested_item(void) {
    unsigned char inner[256], buf[300];
    size_t n = 0;
    unsigned char kind = 2, year[2] = { 0x07, 0xE8 };   /* 2024 */
    n += put_item(inner + n, "mikd", &kind, 1);
    n += put_str(inner + n, "minm", "Song");
    n += put_str(inner + n, "asar", "Artist");
    n += put_str(inner + n, "asal", "Album");
    n += put_item(inner + n, "asyr", year, 2);
    size_t total = put_item(buf, "mlit", inner, (unsigned int) n);

    dmap_meta_t m;
    dmap_parse(buf, total, &m);
    assert(m.have_title && !strcmp(m.title, "Song"));
    assert(m.have_artist && !strcmp(m.artist, "Artist"));
    assert(m.have_album && !strcmp(m.album, "Album"));
    assert(m.year == 2024);
}

static void test_missing_fields_and_truncated_input(void) {
    unsigned char buf[64];
    size_t n = put_str(buf, "minm", "Only title");
    dmap_meta_t m;
    dmap_parse(buf, n, &m);
    assert(m.have_title && !m.have_artist && !m.have_album && m.year == 0);

    /* Declared length runs past the buffer: ignored, never read out of bounds. */
    dmap_parse(buf, n - 3, &m);
    assert(!m.have_title);
    dmap_parse(NULL, 0, &m);
    assert(!m.have_title);
}

static void test_truncates_on_utf8_boundary(void) {
    /* 510 ASCII bytes then a 4-byte emoji: the 511-byte limit falls inside it. */
    static char title[600];
    static unsigned char buf[700];
    memset(title, 'a', 510);
    memcpy(title + 510, "\xF0\x9F\x8E\xB5", 4);
    title[514] = '\0';
    size_t n = put_str(buf, "minm", title);

    dmap_meta_t m;
    dmap_parse(buf, n, &m);
    assert(m.have_title);
    assert(strlen(m.title) == 510);

    /* A title that fits keeps its multi-byte characters intact. */
    n = put_str(buf, "minm", "Caf\xC3\xA9 \xF0\x9F\x8E\xB5");
    dmap_parse(buf, n, &m);
    assert(!strcmp(m.title, "Caf\xC3\xA9 \xF0\x9F\x8E\xB5"));
}

int main(void) {
    test_nested_item();
    test_missing_fields_and_truncated_input();
    test_truncates_on_utf8_boundary();
    puts("dmap: ok");
    return 0;
}
