/**
 *  Android port addition (Wormhole Display): legacy RAOP ("AirTunes") RSA support.
 *
 *  iTunes / macOS Music stream audio-only with the AirPort Express protocol:
 *  the sender proves the receiver is genuine with an Apple-Challenge header
 *  (RSA PKCS#1 v1.5 signature) and ships the AES session key RSA-OAEP
 *  encrypted in the ANNOUNCE SDP (a=rsaaeskey). Both use the well-known
 *  AirPort Express private key.
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public
 *  License as published by the Free Software Foundation; either
 *  version 2.1 of the License, or (at your option) any later version.
 */

#ifndef RSAKEY_H
#define RSAKEY_H

#include <stddef.h>

typedef struct rsakey_s rsakey_t;

rsakey_t *rsakey_init(void);
void rsakey_destroy(rsakey_t *rsakey);

/* Answer an Apple-Challenge header: returns a malloc'd, unpadded base64
 * Apple-Response string, or NULL on failure. */
char *rsakey_sign_challenge(rsakey_t *rsakey, const char *challenge_b64,
                            const unsigned char *local_addr, int local_addr_len,
                            const unsigned char *hwaddr, int hwaddr_len);

/* RSA-OAEP decrypt a base64 a=rsaaeskey value into a 16 byte AES key.
 * Returns 0 on success. */
int rsakey_decrypt_aeskey(rsakey_t *rsakey, const char *rsaaeskey_b64, unsigned char aeskey[16]);

/* Base64 helpers tolerant of missing '=' padding (as sent by iTunes). */
unsigned char *rsakey_base64_decode(const char *input, int *outlen);
char *rsakey_base64_encode(const unsigned char *input, int inlen);

#endif
