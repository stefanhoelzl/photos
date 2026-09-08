/* Shared between the shim's own translation units. Not part of the Swift-facing contract. */
#ifndef PI_INTERNAL_H
#define PI_INTERNAL_H

#include "photos_imaging.h"
#include <stdarg.h>

#if defined(__GNUC__)
#define PI_PRINTF(a, b) __attribute__((format(printf, a, b)))
#else
#define PI_PRINTF(a, b)
#endif

int pi_fail(pi_error *err, int code, const char *fmt, ...) PI_PRINTF(3, 4);
void pi_ok(pi_error *err);

int pi_image_set_icc(pi_image *img, const uint8_t *icc, size_t len, pi_error *err);
int pi_image_apply_orientation(pi_image *img, int orientation, pi_error *err);

/* The clockwise rotation that must be applied to display a frame upright, in degrees.
 *
 * av_display_rotation_get reports the *counter-clockwise* angle the matrix encodes, so a
 * portrait iPhone video -- whose tkhd matrix reads 90 -- comes back as 270. Every caller
 * wants the clockwise angle to apply, so the negation happens once, here. */
int pi_display_rotation(const int32_t *matrix);

struct AVFrame;
/* Converts a decoded frame to RGB/RGBA. Alpha survives here and is flattened later, so the
 * caller picks the background rather than the decoder. */
int pi_frame_to_image(struct AVFrame *frame, pi_image *out, pi_error *err);

/* Reads EXIF_TAG_ORIENTATION out of an APP1 block ("Exif\0\0" + TIFF). Returns 1 when the
 * block carries no usable orientation, so callers can apply the result unconditionally. */
int pi_exif_orientation_in_block(const uint8_t *block, size_t len);
/* Same, for a TIFF-rooted file on disk. */
int pi_exif_orientation_of_file(const char *path);

/* Per-format decoders, dispatched by pi_decode. */
int pi_decode_jpeg_mem(const uint8_t *data, size_t len, int max_long_edge,
                       pi_image *out, pi_error *err);
int pi_decode_heif(const char *path, pi_image *out, pi_error *err);
int pi_decode_via_ffmpeg(const char *path, pi_image *out, pi_error *err);

#endif /* PI_INTERNAL_H */
