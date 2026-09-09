#include "photos_imaging.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <math.h>

#include <libavutil/display.h>
#include <libavutil/log.h>

#include "pi_internal.h"

/* ffmpeg logs to stderr by default, and the library's Live Photo MOVs carry edit lists that
 * make it complain on every single one. A library has no business writing to a process's
 * stderr -- the same reason libjpeg's emit_message is stubbed out in pi_jpeg.c. Errors still
 * reach the caller through pi_error. */
__attribute__((constructor))
static void pi_quiet_ffmpeg(void) {
    av_log_set_level(AV_LOG_QUIET);
}

void pi_image_init(pi_image *img) {
    if (img) memset(img, 0, sizeof(*img));
}

void pi_image_free(pi_image *img) {
    if (!img) return;
    free(img->pixels);
    free(img->icc);
    memset(img, 0, sizeof(*img));
}

void pi_buffer_init(pi_buffer *buf) {
    if (buf) memset(buf, 0, sizeof(*buf));
}

void pi_buffer_free(pi_buffer *buf) {
    if (!buf) return;
    free(buf->bytes);
    memset(buf, 0, sizeof(*buf));
}

int pi_fail(pi_error *err, int code, const char *fmt, ...) {
    if (err) {
        va_list ap;
        va_start(ap, fmt);
        err->code = code;
        vsnprintf(err->message, sizeof(err->message), fmt, ap);
        va_end(ap);
    }
    return code;
}

void pi_ok(pi_error *err) {
    if (err) { err->code = PI_OK; err->message[0] = '\0'; }
}

int pi_image_alloc(pi_image *img, int width, int height, int channels, pi_error *err) {
    pi_image_init(img);
    if (width <= 0 || height <= 0 || (channels != 3 && channels != 4))
        return pi_fail(err, PI_ERR_INVALID, "bad image geometry %dx%d/%d", width, height, channels);

    /* Overflow matters here: the library holds a 27558x5973 panorama, and a bad scale
     * calculation upstream must not turn into a short allocation and a heap overrun. */
    size_t stride = (size_t)width * (size_t)channels;
    if (stride / (size_t)channels != (size_t)width)
        return pi_fail(err, PI_ERR_MEMORY, "image row overflows");
    size_t total = stride * (size_t)height;
    if (total / stride != (size_t)height)
        return pi_fail(err, PI_ERR_MEMORY, "image size overflows");

    img->pixels = (uint8_t *)malloc(total);
    if (!img->pixels) return pi_fail(err, PI_ERR_MEMORY, "out of memory for %zu bytes", total);
    img->width = width;
    img->height = height;
    img->channels = channels;
    /* Default to the buffer's own size; decoders that used shrink-on-load overwrite this. */
    img->source_width = width;
    img->source_height = height;
    return PI_OK;
}

int pi_image_set_icc(pi_image *img, const uint8_t *icc, size_t len, pi_error *err) {
    free(img->icc);
    img->icc = NULL;
    img->icc_len = 0;
    if (!icc || len == 0) return PI_OK;
    img->icc = (uint8_t *)malloc(len);
    if (!img->icc) return pi_fail(err, PI_ERR_MEMORY, "out of memory for ICC profile");
    memcpy(img->icc, icc, len);
    img->icc_len = len;
    return PI_OK;
}

/* Orientation is baked into the pixels here and nowhere else, so that §3's promise -- that
 * stored width/height are already rotated and no consumer applies orientation -- holds for
 * every format the same way. */
int pi_image_apply_orientation(pi_image *img, int orientation, pi_error *err) {
    if (orientation <= 1 || orientation > 8) return PI_OK;

    int transposed = (orientation >= 5);
    int ow = transposed ? img->height : img->width;
    int oh = transposed ? img->width : img->height;

    pi_image dst;
    if (pi_image_alloc(&dst, ow, oh, img->channels, err) != PI_OK) return err ? err->code : PI_ERR_MEMORY;

    const int c = img->channels;
    for (int y = 0; y < img->height; y++) {
        const uint8_t *srow = img->pixels + (size_t)y * img->width * c;
        for (int x = 0; x < img->width; x++) {
            int dx, dy;
            switch (orientation) {
                case 2: dx = img->width - 1 - x; dy = y; break;              /* mirror H */
                case 3: dx = img->width - 1 - x; dy = img->height - 1 - y; break; /* 180 */
                case 4: dx = x; dy = img->height - 1 - y; break;             /* mirror V */
                case 5: dx = y; dy = x; break;                               /* transpose */
                case 6: dx = img->height - 1 - y; dy = x; break;             /* 90 CW */
                case 7: dx = img->height - 1 - y; dy = img->width - 1 - x; break;
                case 8: dx = y; dy = img->width - 1 - x; break;              /* 90 CCW */
                default: dx = x; dy = y; break;
            }
            memcpy(dst.pixels + ((size_t)dy * ow + dx) * c, srow + (size_t)x * c, (size_t)c);
        }
    }

    /* Carry the profile across; the rotation changed geometry, not colour. */
    dst.icc = img->icc;
    dst.icc_len = img->icc_len;
    dst.source_width = transposed ? img->source_height : img->source_width;
    dst.source_height = transposed ? img->source_width : img->source_height;
    img->icc = NULL;
    img->icc_len = 0;
    pi_image_free(img);
    *img = dst;
    return PI_OK;
}

pi_format pi_sniff(const char *path) {
    FILE *f = fopen(path, "rb");
    if (!f) return PI_FORMAT_UNKNOWN;

    unsigned char head[64];
    size_t n = fread(head, 1, sizeof(head), f);
    fclose(f);
    if (n < 12) return PI_FORMAT_UNKNOWN;

    if (head[0] == 0xFF && head[1] == 0xD8 && head[2] == 0xFF) return PI_FORMAT_JPEG;
    if (memcmp(head, "\x89PNG\r\n\x1a\n", 8) == 0) return PI_FORMAT_PNG;

    /* ISO-BMFF and classic QuickTime.
     *
     * An `ftyp` box gives a brand, and the brand is the only thing separating a HEIC still
     * from an MP4 video since they share a container. But `ftyp` is an MP4-ism: classic
     * QuickTime files need not have one at all, and 23 Nikon Coolpix videos in this library
     * open with a `pnot` preview atom instead. Recognising only `ftyp` silently dropped every
     * one of them as "unknown format".
     *
     * So walk the first few top-level atoms. Finding `ftyp` decides by brand; finding any
     * other known QuickTime atom means video, because a HEIF file is *required* to carry an
     * `ftyp` and would have been recognised already. */
    size_t offset = 0;
    for (int atom = 0; atom < 8 && offset + 8 <= n; atom++) {
        uint32_t size = ((uint32_t)head[offset] << 24) | ((uint32_t)head[offset + 1] << 16) |
                        ((uint32_t)head[offset + 2] << 8) | (uint32_t)head[offset + 3];
        const char *type = (const char *)head + offset + 4;

        if (!memcmp(type, "ftyp", 4)) {
            if (offset + 12 > n) return PI_FORMAT_VIDEO;
            const char *brand = (const char *)head + offset + 8;
            if (!memcmp(brand, "heic", 4) || !memcmp(brand, "heix", 4) ||
                !memcmp(brand, "heim", 4) || !memcmp(brand, "heis", 4) ||
                !memcmp(brand, "hevc", 4) || !memcmp(brand, "mif1", 4) ||
                !memcmp(brand, "msf1", 4) || !memcmp(brand, "avif", 4))
                return PI_FORMAT_HEIF;
            return PI_FORMAT_VIDEO;
        }
        if (!memcmp(type, "moov", 4) || !memcmp(type, "mdat", 4) ||
            !memcmp(type, "pnot", 4) || !memcmp(type, "wide", 4) ||
            !memcmp(type, "skip", 4) || !memcmp(type, "free", 4) ||
            !memcmp(type, "PICT", 4) || !memcmp(type, "junk", 4))
            return PI_FORMAT_VIDEO;

        /* size 0 means "to end of file" and size 1 means a 64-bit size follows; neither can
         * be stepped over inside a bounded header read, so stop rather than guess. */
        if (size < 8) break;
        offset += size;
    }

    /* TIFF, and CR2 which is a TIFF with a magic at offset 8. Distinguishing them matters:
     * a CR2 is never decoded, only carved. */
    if ((!memcmp(head, "II\x2a\x00", 4)) || (!memcmp(head, "MM\x00\x2a", 4))) {
        if (n >= 11 && head[8] == 'C' && head[9] == 'R' && head[10] == 0x02) return PI_FORMAT_CR2;
        return PI_FORMAT_TIFF;
    }

    if (!memcmp(head, "RIFF", 4) && !memcmp(head + 8, "AVI ", 4)) return PI_FORMAT_VIDEO;
    if (!memcmp(head, "\x00\x00\x01\xba", 4) || !memcmp(head, "\x00\x00\x01\xb3", 4))
        return PI_FORMAT_VIDEO;  /* MPEG program stream / sequence header */
    if (!memcmp(head, "\x1a\x45\xdf\xa3", 4)) return PI_FORMAT_VIDEO;  /* matroska */

    return PI_FORMAT_UNKNOWN;
}

/* Declared in pi_internal.h. Kept in one place because the sign is easy to get wrong and
 * getting it wrong rotates 118 of the library's videos the wrong way -- which still swaps
 * their dimensions, so it looks correct in any test that only checks geometry. */
int pi_display_rotation(const int32_t *matrix) {
    double theta = av_display_rotation_get(matrix);
    if (!(theta == theta)) return 0;   /* NaN: no usable rotation */
    /* lround, not (int)(theta + 0.5): the angle is negative for the common portrait case,
     * and truncation toward zero turns -90 into -89. */
    int ccw = (int)(((long)lround(theta) % 360 + 360) % 360);
    return (360 - ccw) % 360;
}
