/*
 * Minimal Apple Lossless (ALAC) decoder for AirPlay audio. See alac_decoder.h.
 *
 * Bitstream layout of one packet (per channel element):
 *   3  bits  element tag (0 = single channel, 1 = channel pair, 7 = end)
 *   4  bits  element instance
 *   12 bits  reserved (0)
 *   1  bit   partial frame (explicit sample count follows)
 *   2  bits  bytes shifted (low-order bytes stored raw; not used by AirPlay)
 *   1  bit   escape (frame is stored verbatim)
 *   32 bits  sample count, only when the partial-frame bit is set
 *   compressed frames continue with:
 *     8 bits mix shift, 8 bits mix weight (stereo only)
 *     per channel: 4 bits predictor mode, 4 bits quantisation shift,
 *                  3 bits rice parameter factor, 5 bits coefficient count,
 *                  16 bits per coefficient
 *     per channel: adaptive Golomb-Rice coded prediction residuals
 *
 * Part of Wormhole Display (GPLv3).
 */

#include <stdlib.h>
#include <string.h>

#include "alac_decoder.h"

#define MAX_CHANNELS 2
#define MAX_COEFS 32
#define RICE_ESCAPE_PREFIX 9   /* nine 1-bits announce a raw value */

struct alac_decoder {
    int frame_length;
    int bit_depth;
    int channels;
    int pb;      /* rice history multiplier */
    int mb;      /* rice initial history */
    int kb;      /* rice parameter limit */
    int32_t *residual;
    int32_t *chan[MAX_CHANNELS];
};

typedef struct {
    const uint8_t *data;
    size_t len;      /* bytes */
    size_t pos;      /* bits consumed */
    int overrun;
} bitreader_t;

static uint32_t
br_read(bitreader_t *br, int nbits)
{
    uint32_t value = 0;
    if (nbits <= 0) {
        return 0;
    }
    if (br->pos + (size_t) nbits > br->len * 8) {
        br->overrun = 1;
        br->pos = br->len * 8;
        return 0;
    }
    while (nbits > 0) {
        size_t byte = br->pos >> 3;
        int bit_off = (int) (br->pos & 7);
        int take = 8 - bit_off;
        if (take > nbits) {
            take = nbits;
        }
        uint32_t chunk = ((uint32_t) br->data[byte] >> (8 - bit_off - take)) & ((1u << take) - 1);
        value = (value << take) | chunk;
        br->pos += (size_t) take;
        nbits -= take;
    }
    return value;
}

static int
br_peek_bit(const bitreader_t *br)
{
    if (br->pos >= br->len * 8) {
        return -1;
    }
    return (br->data[br->pos >> 3] >> (7 - (br->pos & 7))) & 1;
}

static int32_t
sign_extend(int32_t value, int bits)
{
    if (bits <= 0 || bits >= 32) {
        return value;
    }
    uint32_t shift = (uint32_t) (32 - bits);
    return (int32_t) ((uint32_t) value << shift) >> shift;
}

static int
count_leading_zeros(uint32_t value)
{
    if (value == 0) {
        return 32;
    }
    int n = 0;
    while (!(value & 0x80000000u)) {
        value <<= 1;
        n++;
    }
    return n;
}

/* One adaptive Golomb-Rice code word: unary prefix, then k extra bits unless
 * the prefix escapes to a raw value of raw_bits. */
static int32_t
rice_read(bitreader_t *br, int k, int raw_bits, uint32_t m_mask)
{
    int prefix = 0;
    while (prefix <= RICE_ESCAPE_PREFIX - 1) {
        int bit = br_peek_bit(br);
        if (bit < 0) {
            br->overrun = 1;
            return 0;
        }
        br->pos++;
        if (!bit) {
            break;
        }
        prefix++;
    }
    if (prefix > RICE_ESCAPE_PREFIX - 1) {
        return (int32_t) br_read(br, raw_bits);
    }
    if (k == 1) {
        return prefix;   /* m = 1: the extra bit would always be redundant */
    }
    uint32_t m = ((1u << k) - 1) & m_mask;
    int32_t value = prefix * (int32_t) m;
    uint32_t extra = br_read(br, k);
    if (extra >= 2) {
        value += (int32_t) extra - 1;
    } else {
        br->pos--;   /* only k-1 bits were needed */
    }
    return value;
}

static int
rice_decode(const alac_decoder_t *d, bitreader_t *br, int32_t *out, int count, int raw_bits, int pb)
{
    uint32_t history = (uint32_t) d->mb;
    int zero_run_pending = 0;
    uint32_t wb = (1u << d->kb) - 1;
    int i = 0;

    while (i < count) {
        int k = 31 - count_leading_zeros((history >> 9) + 3);
        if (k > d->kb) {
            k = d->kb;
        }
        int32_t n = rice_read(br, k, raw_bits, wb);
        if (br->overrun) {
            return ALAC_ERR_TRUNCATED;
        }
        int32_t coded = n + zero_run_pending;
        /* fold the sign back in: even codes are positive, odd negative */
        int32_t value = (coded + 1) >> 1;
        if (coded & 1) {
            value = -value;
        }
        out[i++] = value;
        zero_run_pending = 0;

        history += (uint32_t) (coded * pb) - ((history * (uint32_t) pb) >> 9);
        if (n > 0xffff) {
            history = 0xffff;
        }

        /* a very small history means silence: a run length of zeros follows */
        if (history < 128 && i < count) {
            int zk = count_leading_zeros(history) - 24 + (int) ((history + 16) >> 6);
            int32_t run = rice_read(br, zk, 16, wb);
            if (br->overrun) {
                return ALAC_ERR_TRUNCATED;
            }
            if (run < 0 || run > count - i) {
                return ALAC_ERR_CORRUPT;
            }
            if (run > 0) {
                memset(out + i, 0, (size_t) run * sizeof(int32_t));
                i += run;
            }
            zero_run_pending = (run >= 0xffff) ? 0 : 1;
            history = 0;
        }
    }
    return 0;
}

/* Adaptive FIR prediction; coefficients adapt by the sign of the residual.
 * coefs[0] applies to the most recent sample. */
static void
predict(const int32_t *residual, int32_t *out, int count, int bits, int16_t *coefs, int order, int quant)
{
    if (count <= 0) {
        return;
    }
    out[0] = residual[0];
    if (count == 1) {
        return;
    }
    if (order == 0) {
        memcpy(out + 1, residual + 1, (size_t) (count - 1) * sizeof(int32_t));
        return;
    }
    if (order == 31) {
        for (int i = 1; i < count; i++) {
            out[i] = sign_extend(out[i - 1] + residual[i], bits);
        }
        return;
    }
    int i;
    for (i = 1; i <= order && i < count; i++) {
        out[i] = sign_extend(out[i - 1] + residual[i], bits);
    }
    for (; i < count; i++) {
        int32_t base = out[i - order - 1];
        const int32_t *window = out + i - 1;   /* window[-j] is the j-th most recent sample */
        int64_t sum = 0;
        for (int j = 0; j < order; j++) {
            sum += (int64_t) (window[-j] - base) * coefs[j];
        }
        int32_t err = residual[i];
        int32_t value = (int32_t) ((sum + (1 << (quant - 1))) >> quant) + base + err;
        out[i] = sign_extend(value, bits);

        if (err > 0) {
            for (int j = order - 1; j >= 0 && err > 0; j--) {
                int32_t diff = base - window[-j];
                int32_t sgn = (diff > 0) - (diff < 0);
                coefs[j] -= (int16_t) sgn;
                err -= ((sgn * diff) >> quant) * (order - j);
            }
        } else if (err < 0) {
            for (int j = order - 1; j >= 0 && err < 0; j--) {
                int32_t diff = base - window[-j];
                int32_t sgn = (diff > 0) - (diff < 0);
                coefs[j] += (int16_t) sgn;
                err -= ((-sgn * diff) >> quant) * (order - j);
            }
        }
    }
}

alac_decoder_t *
alac_decoder_create(const unsigned int fmtp[12])
{
    if (!fmtp) {
        return NULL;
    }
    int frame_length = (int) fmtp[1];
    int bit_depth = (int) fmtp[3];
    int channels = (int) fmtp[7];
    if (frame_length <= 0 || frame_length > 16384 || bit_depth != 16 ||
        channels < 1 || channels > MAX_CHANNELS || fmtp[4] == 0 || fmtp[6] == 0 || fmtp[6] > 30) {
        return NULL;
    }
    alac_decoder_t *d = calloc(1, sizeof(*d));
    if (!d) {
        return NULL;
    }
    d->frame_length = frame_length;
    d->bit_depth = bit_depth;
    d->channels = channels;
    d->pb = (int) fmtp[4];
    d->mb = (int) fmtp[5];
    d->kb = (int) fmtp[6];
    d->residual = calloc((size_t) frame_length, sizeof(int32_t));
    for (int c = 0; c < channels; c++) {
        d->chan[c] = calloc((size_t) frame_length, sizeof(int32_t));
    }
    if (!d->residual || !d->chan[0] || (channels > 1 && !d->chan[1])) {
        alac_decoder_destroy(d);
        return NULL;
    }
    return d;
}

void
alac_decoder_destroy(alac_decoder_t *d)
{
    if (!d) {
        return;
    }
    free(d->residual);
    for (int c = 0; c < MAX_CHANNELS; c++) {
        free(d->chan[c]);
    }
    free(d);
}

int
alac_decoder_channels(const alac_decoder_t *d)
{
    return d ? d->channels : 0;
}

int
alac_decoder_frame_length(const alac_decoder_t *d)
{
    return d ? d->frame_length : 0;
}

int
alac_decoder_decode(alac_decoder_t *d, const uint8_t *in, size_t in_len, int16_t *out, size_t out_samples)
{
    if (!d || !in || !out || in_len == 0) {
        return ALAC_ERR_ARGS;
    }
    bitreader_t br = { in, in_len, 0, 0 };

    int tag = (int) br_read(&br, 3);
    int element_channels;
    if (tag == 0) {          /* SCE */
        element_channels = 1;
    } else if (tag == 1) {   /* CPE */
        element_channels = 2;
    } else {
        return ALAC_ERR_UNSUPPORTED;
    }
    if (element_channels != d->channels) {
        return ALAC_ERR_UNSUPPORTED;
    }
    br_read(&br, 4);    /* element instance */
    if (br_read(&br, 12) != 0) {
        return ALAC_ERR_CORRUPT;
    }
    int partial = (int) br_read(&br, 1);
    int bytes_shifted = (int) br_read(&br, 2);
    int escape = (int) br_read(&br, 1);
    if (bytes_shifted != 0) {
        return ALAC_ERR_UNSUPPORTED;   /* only meaningful above 16 bits */
    }
    int count = d->frame_length;
    if (partial) {
        count = (int) br_read(&br, 32);
        if (count <= 0 || count > d->frame_length) {
            return ALAC_ERR_CORRUPT;
        }
    }
    if (br.overrun) {
        return ALAC_ERR_TRUNCATED;
    }
    if (out_samples < (size_t) count * (size_t) d->channels) {
        return ALAC_ERR_ARGS;
    }

    int mix_bits = 0, mix_res = 0;
    if (!escape) {
        int chan_bits = d->bit_depth + (d->channels - 1);   /* stereo residuals carry one bit of headroom */
        if (d->channels == 2) {
            mix_bits = (int) br_read(&br, 8);
            mix_res = sign_extend((int32_t) br_read(&br, 8), 8);
            if (mix_res != 0 && mix_bits > 30) {
                return ALAC_ERR_CORRUPT;
            }
        }
        int mode[MAX_CHANNELS], quant[MAX_CHANNELS], pb_factor[MAX_CHANNELS], order[MAX_CHANNELS];
        int16_t coefs[MAX_CHANNELS][MAX_COEFS];
        for (int c = 0; c < d->channels; c++) {
            mode[c] = (int) br_read(&br, 4);
            quant[c] = (int) br_read(&br, 4);
            pb_factor[c] = (int) br_read(&br, 3);
            order[c] = (int) br_read(&br, 5);
            for (int j = 0; j < order[c]; j++) {
                coefs[c][j] = (int16_t) br_read(&br, 16);
            }
            if (mode[c] != 0 && mode[c] != 15) {
                return ALAC_ERR_UNSUPPORTED;
            }
            if (order[c] > 0 && order[c] != 31 && quant[c] == 0) {
                return ALAC_ERR_CORRUPT;
            }
        }
        if (br.overrun) {
            return ALAC_ERR_TRUNCATED;
        }
        for (int c = 0; c < d->channels; c++) {
            int rc = rice_decode(d, &br, d->residual, count, chan_bits, (d->pb * pb_factor[c]) / 4);
            if (rc < 0) {
                return rc;
            }
            if (mode[c] == 15) {
                /* two-stage prediction: a first-order pass feeds the adaptive filter */
                predict(d->residual, d->residual, count, chan_bits, NULL, 31, 0);
            }
            predict(d->residual, d->chan[c], count, chan_bits, coefs[c], order[c], quant[c]);
        }
    } else {
        for (int i = 0; i < count; i++) {
            for (int c = 0; c < d->channels; c++) {
                d->chan[c][i] = sign_extend((int32_t) br_read(&br, d->bit_depth), d->bit_depth);
            }
        }
        if (br.overrun) {
            return ALAC_ERR_TRUNCATED;
        }
    }

    if (d->channels == 2) {
        const int32_t *u = d->chan[0];
        const int32_t *v = d->chan[1];
        if (mix_res != 0) {
            for (int i = 0; i < count; i++) {
                int32_t l = u[i] + v[i] - ((mix_res * v[i]) >> mix_bits);
                out[2 * i] = (int16_t) l;
                out[2 * i + 1] = (int16_t) (l - v[i]);
            }
        } else {
            for (int i = 0; i < count; i++) {
                out[2 * i] = (int16_t) u[i];
                out[2 * i + 1] = (int16_t) v[i];
            }
        }
    } else {
        for (int i = 0; i < count; i++) {
            out[i] = (int16_t) d->chan[0][i];
        }
    }
    return count;
}
