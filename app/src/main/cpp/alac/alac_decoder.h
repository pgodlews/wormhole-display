/*
 * Minimal Apple Lossless (ALAC) decoder for AirPlay audio.
 *
 * Decodes one ALAC packet (as carried in an RTP payload after AES decryption)
 * into interleaved signed 16-bit PCM. Only the AirPlay profile is supported:
 * 16-bit, 1 or 2 channels, no shifted low-order bytes. Malformed input is
 * rejected instead of trusted (bit reads are bounds checked).
 *
 * Part of Wormhole Display (GPLv3).
 */

#ifndef WORMHOLE_ALAC_DECODER_H
#define WORMHOLE_ALAC_DECODER_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct alac_decoder alac_decoder_t;

/* fmtp holds the 12 values of the SDP "a=fmtp:96 ..." line (or the equivalent
 * AirPlay defaults): payload type, frame length, compatible version, bit depth,
 * pb, mb, kb, channels, max run, max frame bytes, average bit rate, sample rate. */
alac_decoder_t *alac_decoder_create(const unsigned int fmtp[12]);
void alac_decoder_destroy(alac_decoder_t *decoder);

int alac_decoder_channels(const alac_decoder_t *decoder);
int alac_decoder_frame_length(const alac_decoder_t *decoder);

/* Decodes one packet. out must hold frame_length * channels samples.
 * Returns the number of frames (samples per channel) written, or a negative
 * error code. */
int alac_decoder_decode(alac_decoder_t *decoder, const uint8_t *in, size_t in_len,
                        int16_t *out, size_t out_samples);

#define ALAC_ERR_ARGS        -1
#define ALAC_ERR_TRUNCATED   -2
#define ALAC_ERR_UNSUPPORTED -3
#define ALAC_ERR_CORRUPT     -4

#ifdef __cplusplus
}
#endif

#endif
