/*
 * Minimal DMAP ("x-dmap-tagged") parser for AirPlay now-playing metadata.
 * Extracts track title / artist / album / year from the SET_PARAMETER bundle
 * that iTunes, Music, and Music Assistant send. Part of Wormhole Display (GPLv3).
 */
#ifndef WORMHOLE_DMAP_H
#define WORMHOLE_DMAP_H

#include <stddef.h>

typedef struct {
    char title[512];
    char artist[512];
    char album[512];
    int  year;     /* 0 if absent */
    int  have_title, have_artist, have_album;
} dmap_meta_t;

/* Parse a DMAP-tagged buffer; fills whatever fields are present. Bounds-checked. */
void dmap_parse(const unsigned char *data, size_t len, dmap_meta_t *out);

#endif
