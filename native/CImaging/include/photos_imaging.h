/* The narrow C surface milestone C's pipeline sits on.
 *
 * Nothing from libjpeg, libheif, ffmpeg, lcms2 or libexif appears in this header. That is the
 * point of it: libjpeg reports errors through setjmp/longjmp, which no managed runtime can
 * cross safely, so an error handler has to live in C regardless -- and once C is in the
 * picture, giving the caller one flat API over five libraries costs nothing more.
 *
 * Ownership rule throughout: any pi_image or pi_buffer an out-parameter comes back filled is
 * owned by the caller and freed with the matching pi_*_free. On a nonzero return nothing is
 * allocated and nothing needs freeing.
 */
#ifndef PHOTOS_IMAGING_H
#define PHOTOS_IMAGING_H

#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

enum {
    PI_OK = 0,
    PI_ERR_OPEN = 1,
    PI_ERR_DECODE = 2,
    PI_ERR_ENCODE = 3,
    PI_ERR_UNSUPPORTED = 4,
    PI_ERR_MEMORY = 5,
    PI_ERR_INVALID = 6
};

typedef struct {
    int code;
    char message[256];
} pi_error;

/* Interleaved 8-bit RGB (channels == 3) or RGBA (channels == 4), top-down,
 * stride == width * channels. Orientation is always already applied. */
typedef struct {
    uint8_t *pixels;
    int width;
    int height;
    int channels;
    /* The source's own display dimensions, orientation applied -- which is NOT width/height
     * whenever shrink-on-load was used. §3 stores the photograph's dimensions, and the grid
     * lays out from them, so they must describe the photo rather than whichever buffer the
     * decoder found it cheapest to produce. */
    int source_width;
    int source_height;
    uint8_t *icc;   /* NULL when the source carried no profile */
    size_t icc_len;
} pi_image;

void pi_image_init(pi_image *img);
void pi_image_free(pi_image *img);
/* Allocates an uninitialised buffer of the given geometry. Overflow-checked, because the
 * library holds a 27558x5973 panorama and a bad size calculation must not become a short
 * allocation. Used to build images from pixels the caller already has. */
int pi_image_alloc(pi_image *img, int width, int height, int channels, pi_error *err);

typedef struct {
    uint8_t *bytes;
    size_t len;
} pi_buffer;

void pi_buffer_init(pi_buffer *buf);
void pi_buffer_free(pi_buffer *buf);

/* ------------------------------------------------------------------ format detection */
typedef enum {
    PI_FORMAT_UNKNOWN = 0,
    PI_FORMAT_JPEG,
    PI_FORMAT_HEIF,
    PI_FORMAT_PNG,
    PI_FORMAT_TIFF,
    PI_FORMAT_CR2,
    PI_FORMAT_VIDEO
} pi_format;

/* Reads a bounded header prefix, never the whole file -- which is what makes the denylist
 * safe to point at a 2.66 GB SQLite database sitting in the library root. */
pi_format pi_sniff(const char *path);

/* ------------------------------------------------------------------ decode */
/* max_long_edge is the largest output tier the caller will ask for. JPEG uses it to choose
 * libjpeg's shrink-on-load denominator, which is what bounds decode memory: the library's
 * 164 MP panorama decodes at 1/8 into ~7.7 MB rather than 494 MB. 0 means full resolution. */
int pi_decode(const char *path, int max_long_edge, pi_image *out, pi_error *err);
int pi_decode_memory(const uint8_t *data, size_t len, int max_long_edge,
                     pi_image *out, pi_error *err);

/* ------------------------------------------------------------------ geometry */
/* Lanczos via swscale -- the same resampler the video path uses, so stills and video frames
 * cannot disagree about what downscaling means. */
int pi_resize_fit(const pi_image *src, int long_edge, int allow_upscale,
                  pi_image *out, pi_error *err);
/* Centre-crops to square, then resizes. The order matters: cropping first means the resize
 * never touches pixels that are about to be thrown away. */
int pi_resize_square_crop(const pi_image *src, int edge, pi_image *out, pi_error *err);

/* ------------------------------------------------------------------ colour */
/* Composites RGBA over an opaque background and drops the alpha channel. */
int pi_flatten_alpha(pi_image *img, uint8_t r, uint8_t g, uint8_t b, pi_error *err);
/* Converts through the image's own ICC profile to sRGB and drops the profile. A no-op when
 * the image carries none, since untagged is interpreted as sRGB everywhere. */
int pi_convert_to_srgb(pi_image *img, pi_error *err);

/* ------------------------------------------------------------------ encode */
int pi_encode_jpeg(const pi_image *img, int quality, int optimize,
                   pi_buffer *out, pi_error *err);
/* Embeds img->icc when present -- previews pass their profile through untouched.
 *
 * threads bounds x265's own pool. It matters: the caller runs one worker per core, and x265
 * defaults to a full pool per encoder, so 16 workers otherwise mean 16 full pools. Pass 1
 * when the caller is already parallel, 0 to leave x265's default alone. */
int pi_encode_heic(const pi_image *img, int quality, int threads,
                   pi_buffer *out, pi_error *err);

/* ------------------------------------------------------------------ EXIF */
/* Called once per tag, with EXIF/TIFF tag names unprefixed: DateTimeOriginal, GPSLatitude,
 * Orientation, and the synthesised AppleContentIdentifier. Values are rendered as text; the
 * caller re-parses them into ExifValue, so both platforms share one vocabulary. */
typedef void (*pi_exif_tag_fn)(void *ctx, const char *tag, const char *value);
int pi_exif_read(const char *path, pi_exif_tag_fn fn, void *ctx, pi_error *err);

/* Builds a JPEG APP1 segment payload ("Exif\0\0" + TIFF) from a bare TIFF header, so the
 * JPEG extracted out of a CR2 can carry the CR2's own metadata. Without this the only
 * synthesised original in the bucket would be the one with no date, no GPS and no
 * orientation, while every other original is uploaded byte-for-byte with its metadata. */
int pi_exif_app1_from_tiff(const uint8_t *tiff, size_t len, pi_buffer *out, pi_error *err);

/* ------------------------------------------------------------------ video */
typedef struct {
    int width;
    int height;
    double duration;
    int rotation;        /* degrees from the display matrix: 0, 90, 180, 270 */
    int has_audio;
    int interlaced;
    char content_identifier[64];  /* com.apple.quicktime.content.identifier, "" when absent */
} pi_video_info;

int pi_video_probe(const char *path, pi_video_info *out, pi_error *err);
int pi_video_poster(const char *path, double at, pi_image *out, pi_error *err);
/* max_height is a ceiling, never a target: 137 files in the library are 640x480 or smaller
 * and scaling those up would be 20x the pixels for no added detail. */
int pi_video_transcode(const char *in_path, const char *out_path,
                       int max_height, int quality, int threads, pi_error *err);

#ifdef __cplusplus
}
#endif

#endif /* PHOTOS_IMAGING_H */
