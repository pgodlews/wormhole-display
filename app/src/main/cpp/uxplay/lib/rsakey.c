/**
 *  Android port addition (Wormhole Display): legacy RAOP ("AirTunes") RSA support.
 *  See rsakey.h. The private key below is the widely published AirPort Express
 *  key that every third-party AirPlay 1 audio receiver (shairport, shairplay,
 *  forked-daapd, ...) uses; iTunes/Music refuse to stream audio without it.
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public
 *  License as published by the Free Software Foundation; either
 *  version 2.1 of the License, or (at your option) any later version.
 */

#include <stdlib.h>
#include <string.h>

#include <openssl/bio.h>
#include <openssl/evp.h>
#include <openssl/pem.h>
#include <openssl/rsa.h>

#include "rsakey.h"

static const char airport_express_private_key[] =
    "-----BEGIN RSA PRIVATE KEY-----\n"
    "MIIEpQIBAAKCAQEA59dE8qLieItsH1WgjrcFRKj6eUWqi+bGLOX1HL3U3GhC/j0Qg90u3sG/1CUt\n"
    "wC5vOYvfDmFI6oSFXi5ELabWJmT2dKHzBJKa3k9ok+8t9ucRqMd6DZHJ2YCCLlDRKSKv6kDqnw4U\n"
    "wPdpOMXziC/AMj3Z/lUVX1G7WSHCAWKf1zNS1eLvqr+boEjXuBOitnZ/bDzPHrTOZz0Dew0uowxf\n"
    "/+sG+NCK3eQJVxqcaJ/vEHKIVd2M+5qL71yJQ+87X6oV3eaYvt3zWZYD6z5vYTcrtij2VZ9Zmni/\n"
    "UAaHqn9JdsBWLUEpVviYnhimNVvYFZeCXg/IdTQ+x4IRdiXNv5hEewIDAQABAoIBAQDl8Axy9XfW\n"
    "BLmkzkEiqoSwF0PsmVrPzH9KsnwLGH+QZlvjWd8SWYGN7u1507HvhF5N3drJoVU3O14nDY4TFQAa\n"
    "LlJ9VM35AApXaLyY1ERrN7u9ALKd2LUwYhM7Km539O4yUFYikE2nIPscEsA5ltpxOgUGCY7b7ez5\n"
    "NtD6nL1ZKauw7aNXmVAvmJTcuPxWmoktF3gDJKK2wxZuNGcJE0uFQEG4Z3BrWP7yoNuSK3dii2jm\n"
    "lpPHr0O/KnPQtzI3eguhe0TwUem/eYSdyzMyVx/YpwkzwtYL3sR5k0o9rKQLtvLzfAqdBxBurciz\n"
    "aaA/L0HIgAmOit1GJA2saMxTVPNhAoGBAPfgv1oeZxgxmotiCcMXFEQEWflzhWYTsXrhUIuz5jFu\n"
    "a39GLS99ZEErhLdrwj8rDDViRVJ5skOp9zFvlYAHs0xh92ji1E7V/ysnKBfsMrPkk5KSKPrnjndM\n"
    "oPdevWnVkgJ5jxFuNgxkOLMuG9i53B4yMvDTCRiIPMQ++N2iLDaRAoGBAO9v//mU8eVkQaoANf0Z\n"
    "oMjW8CN4xwWA2cSEIHkd9AfFkftuv8oyLDCG3ZAf0vrhrrtkrfa7ef+AUb69DNggq4mHQAYBp7L+\n"
    "k5DKzJrKuO0r+R0YbY9pZD1+/g9dVt91d6LQNepUE/yY2PP5CNoFmjedpLHMOPFdVgqDzDFxU8hL\n"
    "AoGBANDrr7xAJbqBjHVwIzQ4To9pb4BNeqDndk5Qe7fT3+/H1njGaC0/rXE0Qb7q5ySgnsCb3DvA\n"
    "cJyRM9SJ7OKlGt0FMSdJD5KG0XPIpAVNwgpXXH5MDJg09KHeh0kXo+QA6viFBi21y340NonnEfdf\n"
    "54PX4ZGS/Xac1UK+pLkBB+zRAoGAf0AY3H3qKS2lMEI4bzEFoHeK3G895pDaK3TFBVmD7fV0Zhov\n"
    "17fegFPMwOII8MisYm9ZfT2Z0s5Ro3s5rkt+nvLAdfC/PYPKzTLalpGSwomSNYJcB9HNMlmhkGzc\n"
    "1JnLYT4iyUyx6pcZBmCd8bD0iwY/FzcgNDaUmbX9+XDvRA0CgYEAkE7pIPlE71qvfJQgoA9em0gI\n"
    "LAuE4Pu13aKiJnfft7hIjbK+5kyb3TysZvoyDnb3HOKvInK7vXbKuU4ISgxB2bB3HcYzQMGsz1qJ\n"
    "2gG0N5hvJpzwwhbhXqFKA4zaaSrw622wDniAK5MlIE0tIAKKP4yxNGjoD2QYjhBGuhvkWKY=\n"
    "-----END RSA PRIVATE KEY-----\n";

struct rsakey_s {
    EVP_PKEY *pkey;
};

rsakey_t *
rsakey_init(void)
{
    rsakey_t *rsakey = calloc(1, sizeof(rsakey_t));
    if (!rsakey) {
        return NULL;
    }
    BIO *bio = BIO_new_mem_buf(airport_express_private_key, -1);
    if (bio) {
        rsakey->pkey = PEM_read_bio_PrivateKey(bio, NULL, NULL, NULL);
        BIO_free(bio);
    }
    if (!rsakey->pkey) {
        free(rsakey);
        return NULL;
    }
    return rsakey;
}

void
rsakey_destroy(rsakey_t *rsakey)
{
    if (rsakey) {
        EVP_PKEY_free(rsakey->pkey);
        free(rsakey);
    }
}

static int
b64_value(char c)
{
    if (c >= 'A' && c <= 'Z') return c - 'A';
    if (c >= 'a' && c <= 'z') return c - 'a' + 26;
    if (c >= '0' && c <= '9') return c - '0' + 52;
    if (c == '+' || c == '-') return 62;
    if (c == '/' || c == '_') return 63;
    return -1;
}

unsigned char *
rsakey_base64_decode(const char *input, int *outlen)
{
    if (!input || !outlen) {
        return NULL;
    }
    size_t inlen = strlen(input);
    unsigned char *out = malloc(inlen * 3 / 4 + 4);
    if (!out) {
        return NULL;
    }
    unsigned int acc = 0;
    int bits = 0, n = 0;
    for (size_t i = 0; i < inlen; i++) {
        char c = input[i];
        if (c == '=' || c == '\r' || c == '\n' || c == ' ') {
            continue;
        }
        int v = b64_value(c);
        if (v < 0) {
            free(out);
            return NULL;
        }
        acc = (acc << 6) | (unsigned int) v;
        bits += 6;
        if (bits >= 8) {
            bits -= 8;
            out[n++] = (unsigned char) ((acc >> bits) & 0xff);
        }
    }
    *outlen = n;
    return out;
}

char *
rsakey_base64_encode(const unsigned char *input, int inlen)
{
    static const char alphabet[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    if (!input || inlen < 0) {
        return NULL;
    }
    char *out = malloc(((size_t) inlen + 2) / 3 * 4 + 1);
    if (!out) {
        return NULL;
    }
    int i, n = 0;
    for (i = 0; i + 2 < inlen; i += 3) {
        unsigned int v = ((unsigned int) input[i] << 16) | ((unsigned int) input[i + 1] << 8) | input[i + 2];
        out[n++] = alphabet[(v >> 18) & 63];
        out[n++] = alphabet[(v >> 12) & 63];
        out[n++] = alphabet[(v >> 6) & 63];
        out[n++] = alphabet[v & 63];
    }
    if (i < inlen) {
        unsigned int v = (unsigned int) input[i] << 16;
        if (i + 1 < inlen) {
            v |= (unsigned int) input[i + 1] << 8;
        }
        out[n++] = alphabet[(v >> 18) & 63];
        out[n++] = alphabet[(v >> 12) & 63];
        out[n++] = (i + 1 < inlen) ? alphabet[(v >> 6) & 63] : '=';
        out[n++] = '=';
    }
    out[n] = '\0';
    return out;
}

char *
rsakey_sign_challenge(rsakey_t *rsakey, const char *challenge_b64,
                      const unsigned char *local_addr, int local_addr_len,
                      const unsigned char *hwaddr, int hwaddr_len)
{
    if (!rsakey || !challenge_b64) {
        return NULL;
    }
    int challenge_len = 0;
    unsigned char *challenge = rsakey_base64_decode(challenge_b64, &challenge_len);
    if (!challenge) {
        return NULL;
    }
    /* challenge (16) + IPv4 (4) or IPv6 (16) + MAC (6), zero padded to at least 32 bytes */
    unsigned char message[16 + 16 + 6] = { 0 };
    size_t pos = 0;
    if (challenge_len > 16 || local_addr_len > 16 || hwaddr_len > 6 ||
        local_addr_len < 0 || hwaddr_len < 0) {
        free(challenge);
        return NULL;
    }
    memcpy(message, challenge, (size_t) challenge_len);
    pos += (size_t) challenge_len;
    free(challenge);
    if (local_addr && local_addr_len > 0) {
        memcpy(message + pos, local_addr, (size_t) local_addr_len);
        pos += (size_t) local_addr_len;
    }
    if (hwaddr && hwaddr_len > 0) {
        memcpy(message + pos, hwaddr, (size_t) hwaddr_len);
        pos += (size_t) hwaddr_len;
    }
    if (pos < 32) {
        pos = 32;
    }

    char *result = NULL;
    unsigned char *signature = NULL;
    size_t signature_len = 0;
    EVP_PKEY_CTX *ctx = EVP_PKEY_CTX_new(rsakey->pkey, NULL);
    if (!ctx) {
        return NULL;
    }
    /* No digest: PKCS#1 v1.5 type-1 padding of the raw message, as the AirPort Express does. */
    if (EVP_PKEY_sign_init(ctx) <= 0 ||
        EVP_PKEY_CTX_set_rsa_padding(ctx, RSA_PKCS1_PADDING) <= 0 ||
        EVP_PKEY_sign(ctx, NULL, &signature_len, message, pos) <= 0) {
        goto done;
    }
    signature = malloc(signature_len);
    if (!signature || EVP_PKEY_sign(ctx, signature, &signature_len, message, pos) <= 0) {
        goto done;
    }
    result = rsakey_base64_encode(signature, (int) signature_len);
    if (result) {
        /* iTunes expects the response without '=' padding */
        char *pad = strchr(result, '=');
        if (pad) {
            *pad = '\0';
        }
    }
done:
    free(signature);
    EVP_PKEY_CTX_free(ctx);
    return result;
}

int
rsakey_decrypt_aeskey(rsakey_t *rsakey, const char *rsaaeskey_b64, unsigned char aeskey[16])
{
    if (!rsakey || !rsaaeskey_b64 || !aeskey) {
        return -1;
    }
    int enc_len = 0;
    unsigned char *enc = rsakey_base64_decode(rsaaeskey_b64, &enc_len);
    if (!enc) {
        return -1;
    }
    int ret = -1;
    unsigned char out[512];
    size_t out_len = sizeof(out);
    EVP_PKEY_CTX *ctx = EVP_PKEY_CTX_new(rsakey->pkey, NULL);
    if (ctx &&
        EVP_PKEY_decrypt_init(ctx) > 0 &&
        EVP_PKEY_CTX_set_rsa_padding(ctx, RSA_PKCS1_OAEP_PADDING) > 0 &&
        EVP_PKEY_decrypt(ctx, out, &out_len, enc, (size_t) enc_len) > 0 &&
        out_len == 16) {
        memcpy(aeskey, out, 16);
        ret = 0;
    }
    EVP_PKEY_CTX_free(ctx);
    free(enc);
    return ret;
}
