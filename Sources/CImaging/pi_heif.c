/* HEIC decode and encode via libheif (libde265 in, x265 out).
 *
 * Both are statically linked rather than dlopen-ed plugins -- see ENABLE_PLUGIN_LOADING=OFF
 * in the build script. A single self-contained binary cannot load plugins.
 */
#include <stdlib.h>
#include <string.h>
#include <stdio.h>

#include <libheif/heif.h>

#include "pi_internal.h"

int pi_decode_heif(const char *path, pi_image *out, pi_error *err) {
    pi_image_init(out);

    struct heif_context *ctx = heif_context_alloc();
    if (!ctx) return pi_fail(err, PI_ERR_MEMORY, "heif: context");

    struct heif_error e = heif_context_read_from_file(ctx, path, NULL);
    if (e.code != heif_error_Ok) {
        heif_context_free(ctx);
        return pi_fail(err, PI_ERR_DECODE, "heif: %s", e.message ? e.message : "read failed");
    }

    struct heif_image_handle *handle = NULL;
    e = heif_context_get_primary_image_handle(ctx, &handle);
    if (e.code != heif_error_Ok) {
        heif_context_free(ctx);
        return pi_fail(err, PI_ERR_DECODE, "heif: %s", e.message ? e.message : "no primary image");
    }

    struct heif_image *img = NULL;
    e = heif_decode_image(handle, &img, heif_colorspace_RGB, heif_chroma_interleaved_RGB, NULL);
    if (e.code != heif_error_Ok) {
        heif_image_handle_release(handle);
        heif_context_free(ctx);
        return pi_fail(err, PI_ERR_DECODE, "heif: %s", e.message ? e.message : "decode failed");
    }

    int w = heif_image_get_primary_width(img);
    int h = heif_image_get_primary_height(img);
    int stride = 0;
    const uint8_t *plane = heif_image_get_plane_readonly(img, heif_channel_interleaved, &stride);
    if (!plane || pi_image_alloc(out, w, h, 3, err) != PI_OK) {
        heif_image_release(img);
        heif_image_handle_release(handle);
        heif_context_free(ctx);
        if (!plane) return pi_fail(err, PI_ERR_DECODE, "heif: no interleaved plane");
        return err ? err->code : PI_ERR_MEMORY;
    }
    for (int y = 0; y < h; y++)
        memcpy(out->pixels + (size_t)y * w * 3, plane + (size_t)y * stride, (size_t)w * 3);

    /* libheif applies the irot/imir transforms itself, so what comes back is already
     * display-oriented and no EXIF orientation must be applied on top. */
    size_t icc_len = heif_image_handle_get_raw_color_profile_size(handle);
    if (icc_len > 0) {
        uint8_t *icc = (uint8_t *)malloc(icc_len);
        if (icc) {
            if (heif_image_handle_get_raw_color_profile(handle, icc).code == heif_error_Ok)
                pi_image_set_icc(out, icc, icc_len, err);
            free(icc);
        }
    }

    heif_image_release(img);
    heif_image_handle_release(handle);
    heif_context_free(ctx);
    pi_ok(err);
    return PI_OK;
}

struct pi_heif_sink {
    struct heif_writer writer;
    uint8_t *bytes;
    size_t len;
    int failed;
};

static struct heif_error pi_heif_write(struct heif_context *ctx, const void *data,
                                       size_t size, void *userdata) {
    (void)ctx;
    struct pi_heif_sink *sink = (struct pi_heif_sink *)userdata;
    struct heif_error ok = { heif_error_Ok, heif_suberror_Unspecified, "" };
    struct heif_error oom = { heif_error_Memory_allocation_error, heif_suberror_Unspecified,
                              "out of memory" };
    uint8_t *grown = (uint8_t *)realloc(sink->bytes, sink->len + size);
    if (!grown) { sink->failed = 1; return oom; }
    sink->bytes = grown;
    memcpy(sink->bytes + sink->len, data, size);
    sink->len += size;
    return ok;
}

int pi_encode_heic(const pi_image *img, int quality, int threads,
                   pi_buffer *out, pi_error *err) {
    pi_buffer_init(out);
    if (!img || !img->pixels || img->channels != 3)
        return pi_fail(err, PI_ERR_INVALID, "heif encode: needs a 3-channel image");

    struct heif_context *ctx = heif_context_alloc();
    struct heif_image *himg = NULL;
    struct heif_encoder *enc = NULL;
    struct pi_heif_sink sink;
    memset(&sink, 0, sizeof(sink));
    sink.writer.writer_api_version = 1;
    sink.writer.write = pi_heif_write;

    struct heif_error e = heif_image_create(img->width, img->height, heif_colorspace_RGB,
                                            heif_chroma_interleaved_RGB, &himg);
    if (e.code != heif_error_Ok) {
        heif_context_free(ctx);
        return pi_fail(err, PI_ERR_ENCODE, "heif: %s", e.message ? e.message : "image create");
    }
    e = heif_image_add_plane(himg, heif_channel_interleaved, img->width, img->height, 8);
    if (e.code != heif_error_Ok) {
        heif_image_release(himg);
        heif_context_free(ctx);
        return pi_fail(err, PI_ERR_ENCODE, "heif: %s", e.message ? e.message : "add plane");
    }

    int stride = 0;
    uint8_t *plane = heif_image_get_plane(himg, heif_channel_interleaved, &stride);
    for (int y = 0; y < img->height; y++)
        memcpy(plane + (size_t)y * stride, img->pixels + (size_t)y * img->width * 3,
               (size_t)img->width * 3);

    /* Decision 8: previews are never colour-converted, so whatever profile the source had
     * rides along untouched and a Display P3 photo stays Display P3 at fullscreen. */
    if (img->icc && img->icc_len)
        heif_image_set_raw_color_profile(himg, "prof", img->icc, img->icc_len);

    e = heif_context_get_encoder_for_format(ctx, heif_compression_HEVC, &enc);
    if (e.code != heif_error_Ok) {
        heif_image_release(himg);
        heif_context_free(ctx);
        return pi_fail(err, PI_ERR_ENCODE, "heif: no HEVC encoder (%s)",
                       e.message ? e.message : "");
    }
    heif_encoder_set_lossy_quality(enc, quality);
    if (threads > 0) {
        /* libheif passes x265:-prefixed parameters straight through. Without this, sixteen
         * concurrent encodes each open a full x265 thread pool -- measured at 7.2 GB resident
         * and 1100% CPU on a 16-core machine, for work that is already parallel one level up. */
        char value[16];
        snprintf(value, sizeof(value), "%d", threads);
        heif_encoder_set_parameter_string(enc, "x265:pools", value);
        heif_encoder_set_parameter_string(enc, "x265:frame-threads", value);
    }

    struct heif_encoding_options *opts = heif_encoding_options_alloc();
    e = heif_context_encode_image(ctx, himg, enc, opts, NULL);
    heif_encoding_options_free(opts);
    heif_encoder_release(enc);
    heif_image_release(himg);

    if (e.code != heif_error_Ok) {
        heif_context_free(ctx);
        free(sink.bytes);
        return pi_fail(err, PI_ERR_ENCODE, "heif: %s", e.message ? e.message : "encode failed");
    }

    e = heif_context_write(ctx, &sink.writer, &sink);
    heif_context_free(ctx);
    if (e.code != heif_error_Ok || sink.failed) {
        free(sink.bytes);
        return pi_fail(err, PI_ERR_ENCODE, "heif: %s", e.message ? e.message : "write failed");
    }

    out->bytes = sink.bytes;
    out->len = sink.len;
    pi_ok(err);
    return PI_OK;
}
