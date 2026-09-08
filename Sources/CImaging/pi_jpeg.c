/* JPEG decode and encode.
 *
 * This file is the reason the shim exists at all. libjpeg signals errors by longjmp-ing out
 * of the call you made, and Swift has no way to be on the far end of that jump safely: the
 * stack it unwinds past may hold Swift frames with refcounted values. Keeping the setjmp
 * target in C, and handing Swift a plain error code, is the only sound arrangement.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <setjmp.h>

#include <jpeglib.h>
#include <jerror.h>

#include "pi_internal.h"

struct pi_jpeg_err {
    struct jpeg_error_mgr pub;
    jmp_buf jump;
    char message[JMSG_LENGTH_MAX];
};

static void pi_jpeg_error_exit(j_common_ptr cinfo) {
    struct pi_jpeg_err *e = (struct pi_jpeg_err *)cinfo->err;
    (*cinfo->err->format_message)(cinfo, e->message);
    longjmp(e->jump, 1);
}

/* libjpeg's default emit_message prints corrupt-data warnings to stderr. A library has no
 * business writing to a process's stderr, and the library holds files that warn. */
static void pi_jpeg_emit(j_common_ptr cinfo, int msg_level) {
    (void)cinfo; (void)msg_level;
}

/* Shrink-on-load: libjpeg can decode at N/8 scale straight out of the DCT coefficients, for
 * a fraction of the work and a fraction of the memory. Choosing the *smallest* N whose output
 * still covers the largest tier we need is what caps the 164 MP panorama at ~7.7 MB instead
 * of 494 MB, and it is most of why 34k JPEGs are cheap to process at all. */
static void pi_pick_scale(struct jpeg_decompress_struct *cinfo, int max_long_edge) {
    if (max_long_edge <= 0) { cinfo->scale_num = 1; cinfo->scale_denom = 1; return; }

    unsigned int full = cinfo->image_width > cinfo->image_height
                      ? cinfo->image_width : cinfo->image_height;
    cinfo->scale_num = 8;
    cinfo->scale_denom = 8;
    for (int n = 1; n <= 8; n++) {
        /* ceil(full * n / 8) is what libjpeg will actually produce for this ratio. */
        unsigned int scaled = (full * (unsigned)n + 7) / 8;
        if (scaled >= (unsigned)max_long_edge) {
            cinfo->scale_num = (unsigned)n;
            cinfo->scale_denom = 8;
            return;
        }
    }
}

int pi_decode_jpeg_mem(const uint8_t *data, size_t len, int max_long_edge,
                       pi_image *out, pi_error *err) {
    struct jpeg_decompress_struct cinfo;
    struct pi_jpeg_err jerr;
    pi_image_init(out);

    cinfo.err = jpeg_std_error(&jerr.pub);
    jerr.pub.error_exit = pi_jpeg_error_exit;
    jerr.pub.emit_message = pi_jpeg_emit;
    jerr.message[0] = '\0';

    if (setjmp(jerr.jump)) {
        jpeg_destroy_decompress(&cinfo);
        pi_image_free(out);
        return pi_fail(err, PI_ERR_DECODE, "jpeg: %s", jerr.message);
    }

    jpeg_create_decompress(&cinfo);
    jpeg_mem_src(&cinfo, data, (unsigned long)len);
    /* Ask libjpeg to retain these before reading the header, or they are discarded:
     * APP1 carries EXIF (orientation), APP2 the ICC profile. */
    jpeg_save_markers(&cinfo, JPEG_APP0 + 1, 0xFFFF);
    jpeg_save_markers(&cinfo, JPEG_APP0 + 2, 0xFFFF);
    jpeg_read_header(&cinfo, TRUE);

    /* Captured before scaling: these are the photograph's dimensions, not the buffer's. */
    unsigned int full_width = cinfo.image_width;
    unsigned int full_height = cinfo.image_height;

    pi_pick_scale(&cinfo, max_long_edge);
    cinfo.out_color_space = JCS_RGB;
    cinfo.dct_method = JDCT_ISLOW;
    jpeg_start_decompress(&cinfo);

    if (pi_image_alloc(out, (int)cinfo.output_width, (int)cinfo.output_height, 3, err) != PI_OK) {
        jpeg_destroy_decompress(&cinfo);
        return err ? err->code : PI_ERR_MEMORY;
    }

    while (cinfo.output_scanline < cinfo.output_height) {
        JSAMPROW row = out->pixels + (size_t)cinfo.output_scanline * out->width * 3;
        jpeg_read_scanlines(&cinfo, &row, 1);
    }

    /* An ICC profile can be split across several APP2 markers; they concatenate in order
     * after each one's 14-byte "ICC_PROFILE\0" + sequence header. */
    unsigned char *icc = NULL;
    size_t icc_len = 0;
    for (jpeg_saved_marker_ptr m = cinfo.marker_list; m; m = m->next) {
        if (m->marker != JPEG_APP0 + 2 || m->data_length <= 14) continue;
        if (memcmp(m->data, "ICC_PROFILE", 12) != 0) continue;
        size_t chunk = m->data_length - 14;
        unsigned char *grown = (unsigned char *)realloc(icc, icc_len + chunk);
        if (!grown) { free(icc); icc = NULL; icc_len = 0; break; }
        icc = grown;
        memcpy(icc + icc_len, m->data + 14, chunk);
        icc_len += chunk;
    }
    if (icc) {
        pi_image_set_icc(out, icc, icc_len, err);
        free(icc);
    }

    /* libjpeg does not rotate -- it hands back the stored orientation and expects the caller
     * to know better. §3 promises that stored dimensions are already rotated and that no
     * consumer applies orientation, so this is where that becomes true for the 94% of the
     * library that is JPEG. */
    int orientation = 1;
    for (jpeg_saved_marker_ptr m = cinfo.marker_list; m; m = m->next) {
        if (m->marker != JPEG_APP0 + 1 || m->data_length < 14) continue;
        if (memcmp(m->data, "Exif\0\0", 6) != 0) continue;
        orientation = pi_exif_orientation_in_block(m->data, m->data_length);
        break;
    }

    jpeg_finish_decompress(&cinfo);
    jpeg_destroy_decompress(&cinfo);

    if (orientation >= 5 && orientation <= 8) {
        unsigned int swap = full_width; full_width = full_height; full_height = swap;
    }
    if (pi_image_apply_orientation(out, orientation, err) != PI_OK) {
        pi_image_free(out);
        return err ? err->code : PI_ERR_MEMORY;
    }
    out->source_width = (int)full_width;
    out->source_height = (int)full_height;

    pi_ok(err);
    return PI_OK;
}

int pi_encode_jpeg(const pi_image *img, int quality, int optimize,
                   pi_buffer *out, pi_error *err) {
    struct jpeg_compress_struct cinfo;
    struct pi_jpeg_err jerr;
    unsigned char *buf = NULL;
    unsigned long buf_len = 0;

    pi_buffer_init(out);
    if (!img || !img->pixels)
        return pi_fail(err, PI_ERR_INVALID, "encode: no image");
    if (img->channels != 3)
        return pi_fail(err, PI_ERR_INVALID, "encode: JPEG needs 3 channels, got %d", img->channels);

    cinfo.err = jpeg_std_error(&jerr.pub);
    jerr.pub.error_exit = pi_jpeg_error_exit;
    jerr.pub.emit_message = pi_jpeg_emit;
    jerr.message[0] = '\0';

    if (setjmp(jerr.jump)) {
        jpeg_destroy_compress(&cinfo);
        free(buf);
        return pi_fail(err, PI_ERR_ENCODE, "jpeg: %s", jerr.message);
    }

    jpeg_create_compress(&cinfo);
    jpeg_mem_dest(&cinfo, &buf, &buf_len);
    cinfo.image_width = (JDIMENSION)img->width;
    cinfo.image_height = (JDIMENSION)img->height;
    cinfo.input_components = 3;
    cinfo.in_color_space = JCS_RGB;
    jpeg_set_defaults(&cinfo);
    jpeg_set_quality(&cinfo, quality, TRUE);
    /* Optimized Huffman tables: ~4% smaller for a single extra pass over an image this small.
     * It is the difference between 9.15 KB and 8.78 KB per thumbnail, and it is what makes
     * the measured figure reproduce. */
    cinfo.optimize_coding = optimize ? TRUE : FALSE;

    jpeg_start_compress(&cinfo, TRUE);
    while (cinfo.next_scanline < cinfo.image_height) {
        JSAMPROW row = img->pixels + (size_t)cinfo.next_scanline * img->width * 3;
        jpeg_write_scanlines(&cinfo, &row, 1);
    }
    jpeg_finish_compress(&cinfo);
    jpeg_destroy_compress(&cinfo);

    out->bytes = buf;
    out->len = (size_t)buf_len;
    pi_ok(err);
    return PI_OK;
}
