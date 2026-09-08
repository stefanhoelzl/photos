/* Colour handling: alpha flattening and the one sRGB conversion the pipeline performs.
 *
 * Decision 8 splits the two tiers deliberately. Previews are never converted -- they carry
 * their source profile through, because ~8-9% of the library is Display P3 and fullscreen is
 * where gamut is on show. Thumbnails are converted and stripped, because embedding a profile
 * costs 4.6-26.5% of an 11.6 KB thumbnail to fix something invisible in a 96pt tile.
 */
#include <stdlib.h>
#include <string.h>

#include <lcms2.h>

#include "pi_internal.h"

int pi_flatten_alpha(pi_image *img, uint8_t r, uint8_t g, uint8_t b, pi_error *err) {
    if (!img || !img->pixels) return pi_fail(err, PI_ERR_INVALID, "flatten: no image");
    if (img->channels == 3) { pi_ok(err); return PI_OK; }
    if (img->channels != 4) return pi_fail(err, PI_ERR_INVALID, "flatten: %d channels", img->channels);

    size_t count = (size_t)img->width * (size_t)img->height;
    uint8_t *rgb = (uint8_t *)malloc(count * 3);
    if (!rgb) return pi_fail(err, PI_ERR_MEMORY, "flatten: out of memory");

    const uint8_t bg[3] = { r, g, b };
    for (size_t i = 0; i < count; i++) {
        const uint8_t *s = img->pixels + i * 4;
        unsigned a = s[3];
        for (int ch = 0; ch < 3; ch++) {
            /* Rounded source-over. +127 rather than truncation, so a fully opaque pixel
             * survives the round trip unchanged. */
            unsigned v = s[ch] * a + bg[ch] * (255u - a);
            rgb[i * 3 + ch] = (uint8_t)((v + 127u) / 255u);
        }
    }

    free(img->pixels);
    img->pixels = rgb;
    img->channels = 3;
    pi_ok(err);
    return PI_OK;
}

int pi_convert_to_srgb(pi_image *img, pi_error *err) {
    if (!img || !img->pixels) return pi_fail(err, PI_ERR_INVALID, "srgb: no image");
    if (img->channels != 3) return pi_fail(err, PI_ERR_INVALID, "srgb: needs 3 channels");

    /* Untagged means sRGB by universal convention, so there is nothing to do -- and 56% of
     * the library's JPEGs are untagged. */
    if (!img->icc || img->icc_len == 0) { pi_ok(err); return PI_OK; }

    cmsHPROFILE src = cmsOpenProfileFromMem(img->icc, (cmsUInt32Number)img->icc_len);
    if (!src) {
        /* A profile lcms2 cannot parse is not worth failing an ingest over: drop it and let
         * the pixels be read as sRGB, which is what would have happened anyway. */
        free(img->icc);
        img->icc = NULL;
        img->icc_len = 0;
        pi_ok(err);
        return PI_OK;
    }

    cmsHPROFILE dst = cmsCreate_sRGBProfile();
    if (!dst) {
        cmsCloseProfile(src);
        return pi_fail(err, PI_ERR_MEMORY, "srgb: cannot create sRGB profile");
    }

    cmsHTRANSFORM xform = cmsCreateTransform(src, TYPE_RGB_8, dst, TYPE_RGB_8,
                                             INTENT_PERCEPTUAL, 0);
    cmsCloseProfile(src);
    cmsCloseProfile(dst);
    if (!xform) {
        free(img->icc);
        img->icc = NULL;
        img->icc_len = 0;
        pi_ok(err);
        return PI_OK;
    }

    /* In place: same format in and out, and lcms2 permits src == dst for that case. */
    cmsDoTransform(xform, img->pixels, img->pixels,
                   (cmsUInt32Number)((size_t)img->width * (size_t)img->height));
    cmsDeleteTransform(xform);

    /* The pixels are sRGB now, so carrying the old profile would misdescribe them. */
    free(img->icc);
    img->icc = NULL;
    img->icc_len = 0;
    pi_ok(err);
    return PI_OK;
}
