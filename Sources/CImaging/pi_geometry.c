/* Resampling, via swscale.
 *
 * The same resampler serves stills and video frames. That is deliberate: a poster frame and a
 * photograph are the same kind of thing by the time they reach the encoder, and two different
 * scalers would be two different answers to "what does downscaling mean".
 */
#include <stdlib.h>
#include <string.h>

#include <libswscale/swscale.h>
#include <libavutil/pixfmt.h>

#include "pi_internal.h"

static int pi_scale_rgb(const pi_image *src, int dw, int dh, pi_image *out, pi_error *err) {
    if (pi_image_alloc(out, dw, dh, src->channels, err) != PI_OK)
        return err ? err->code : PI_ERR_MEMORY;

    enum AVPixelFormat fmt = src->channels == 4 ? AV_PIX_FMT_RGBA : AV_PIX_FMT_RGB24;

    /* Lanczos rather than bilinear: at the ratios this pipeline uses -- a 3456px photo down
     * to 256px -- bilinear visibly softens, and the thumbnail is the app's primary surface. */
    struct SwsContext *sws = sws_getContext(src->width, src->height, fmt,
                                            dw, dh, fmt,
                                            SWS_LANCZOS | SWS_ACCURATE_RND, NULL, NULL, NULL);
    if (!sws) {
        pi_image_free(out);
        return pi_fail(err, PI_ERR_MEMORY, "swscale: context for %dx%d -> %dx%d",
                       src->width, src->height, dw, dh);
    }

    const uint8_t *src_planes[4] = { src->pixels, NULL, NULL, NULL };
    int src_stride[4] = { src->width * src->channels, 0, 0, 0 };
    uint8_t *dst_planes[4] = { out->pixels, NULL, NULL, NULL };
    int dst_stride[4] = { dw * out->channels, 0, 0, 0 };

    sws_scale(sws, src_planes, src_stride, 0, src->height, dst_planes, dst_stride);
    sws_freeContext(sws);

    if (src->icc && src->icc_len) pi_image_set_icc(out, src->icc, src->icc_len, err);
    out->source_width = src->source_width;
    out->source_height = src->source_height;
    pi_ok(err);
    return PI_OK;
}

int pi_resize_fit(const pi_image *src, int long_edge, int allow_upscale,
                  pi_image *out, pi_error *err) {
    pi_image_init(out);
    if (!src || !src->pixels) return pi_fail(err, PI_ERR_INVALID, "resize: no image");
    if (long_edge <= 0) return pi_fail(err, PI_ERR_INVALID, "resize: bad long edge");

    int cur = src->width > src->height ? src->width : src->height;

    /* Never upscale unless asked. 25 of 120 sampled photos are already under 2048px on the
     * long edge, and inventing pixels for them would only inflate the preview tier. */
    if (cur <= long_edge && !allow_upscale) {
        if (pi_image_alloc(out, src->width, src->height, src->channels, err) != PI_OK)
            return err ? err->code : PI_ERR_MEMORY;
        memcpy(out->pixels, src->pixels, (size_t)src->width * src->height * src->channels);
        if (src->icc && src->icc_len) pi_image_set_icc(out, src->icc, src->icc_len, err);
        out->source_width = src->source_width;
        out->source_height = src->source_height;
        pi_ok(err);
        return PI_OK;
    }

    double factor = (double)long_edge / (double)cur;
    int dw = (int)(src->width * factor + 0.5);
    int dh = (int)(src->height * factor + 0.5);
    if (dw < 1) dw = 1;
    if (dh < 1) dh = 1;
    return pi_scale_rgb(src, dw, dh, out, err);
}

int pi_resize_square_crop(const pi_image *src, int edge, pi_image *out, pi_error *err) {
    pi_image_init(out);
    if (!src || !src->pixels) return pi_fail(err, PI_ERR_INVALID, "crop: no image");
    if (edge <= 0) return pi_fail(err, PI_ERR_INVALID, "crop: bad edge");

    /* Crop to the centre square first, then scale. Doing it in this order means the resampler
     * never reads pixels that are about to be discarded -- which for the library's 27558x5973
     * panorama is 78% of the frame. */
    int side = src->width < src->height ? src->width : src->height;
    int x0 = (src->width - side) / 2;
    int y0 = (src->height - side) / 2;

    pi_image cropped;
    if (pi_image_alloc(&cropped, side, side, src->channels, err) != PI_OK)
        return err ? err->code : PI_ERR_MEMORY;

    const int c = src->channels;
    for (int y = 0; y < side; y++) {
        memcpy(cropped.pixels + (size_t)y * side * c,
               src->pixels + ((size_t)(y + y0) * src->width + x0) * c,
               (size_t)side * c);
    }
    if (src->icc && src->icc_len) pi_image_set_icc(&cropped, src->icc, src->icc_len, err);
    cropped.source_width = src->source_width;
    cropped.source_height = src->source_height;

    if (side == edge) { *out = cropped; pi_ok(err); return PI_OK; }

    int rc = pi_scale_rgb(&cropped, edge, edge, out, err);
    pi_image_free(&cropped);
    return rc;
}
