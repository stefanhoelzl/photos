/* EXIF extraction.
 *
 * §7 splits this deliberately: the *extraction* is platform-native, because both platforms
 * ship a library that reads EXIF and a hand-written container parser would only add a third
 * opinion. The *interpretation* -- what "2013:07:04 18:22:11" means, which way GPSLatitudeRef
 * 'S' points -- is Swift's ExifMapper, shared, so a phone and a laptop cannot derive
 * different dates from the same file.
 *
 * So this file's whole job is to hand over tags in one vocabulary, losing nothing. Values are
 * rendered in a canonical text form rather than through exif_entry_get_value, which formats
 * GPS coordinates for humans and throws away the precision the mapper needs:
 *
 *     ASCII     -> the string
 *     integers  -> decimal, space separated when there are several
 *     rationals -> "num/den", space separated -- unreduced, as EXIF stored them
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <libexif/exif-data.h>
#include <libexif/exif-loader.h>
#include <libheif/heif.h>

#include "pi_internal.h"

struct pi_exif_ctx {
    pi_exif_tag_fn fn;
    void *user;
};

static void pi_append(char *buf, size_t cap, size_t *used, const char *fmt, ...) {
    if (*used >= cap - 1) return;
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(buf + *used, cap - *used, fmt, ap);
    va_end(ap);
    if (n > 0) *used += (size_t)n;
    if (*used >= cap) *used = cap - 1;
}

static void pi_render_entry(ExifEntry *e, ExifByteOrder order, char *out, size_t cap) {
    size_t used = 0;
    out[0] = '\0';

    if (e->format == EXIF_FORMAT_ASCII) {
        size_t n = e->size;
        while (n > 0 && (e->data[n - 1] == '\0' || e->data[n - 1] == ' ')) n--;
        if (n >= cap) n = cap - 1;
        memcpy(out, e->data, n);
        out[n] = '\0';
        return;
    }

    unsigned long components = e->components;
    /* A malformed file can claim more components than its data holds; trust the byte count. */
    unsigned long size_per = exif_format_get_size(e->format);
    if (size_per > 0 && components > e->size / size_per) components = e->size / size_per;
    if (components > 64) components = 64;

    for (unsigned long i = 0; i < components; i++) {
        const unsigned char *p = e->data + i * size_per;
        if (i) pi_append(out, cap, &used, " ");
        switch (e->format) {
            case EXIF_FORMAT_SHORT:
                pi_append(out, cap, &used, "%u", exif_get_short(p, order)); break;
            case EXIF_FORMAT_SSHORT:
                pi_append(out, cap, &used, "%d", exif_get_sshort(p, order)); break;
            case EXIF_FORMAT_LONG:
                pi_append(out, cap, &used, "%lu", (unsigned long)exif_get_long(p, order)); break;
            case EXIF_FORMAT_SLONG:
                pi_append(out, cap, &used, "%ld", (long)exif_get_slong(p, order)); break;
            case EXIF_FORMAT_BYTE:
            case EXIF_FORMAT_UNDEFINED:
                pi_append(out, cap, &used, "%u", (unsigned)p[0]); break;
            case EXIF_FORMAT_RATIONAL: {
                ExifRational r = exif_get_rational(p, order);
                pi_append(out, cap, &used, "%lu/%lu",
                          (unsigned long)r.numerator, (unsigned long)r.denominator);
                break;
            }
            case EXIF_FORMAT_SRATIONAL: {
                ExifSRational r = exif_get_srational(p, order);
                pi_append(out, cap, &used, "%ld/%ld", (long)r.numerator, (long)r.denominator);
                break;
            }
            default:
                return;
        }
    }
}

/* Apple stores a Live Photo's pairing id in maker note tag 0x0011.
 *
 * libexif has no Apple maker note support, but it does hand back the raw bytes, and the
 * format is a documented TIFF IFD behind a 14-byte header: "Apple iOS\0" + 0x0001 + a
 * two-byte order mark, then the IFD, with offsets relative to the maker note's own start.
 * Reading one ASCII tag out of that is a bounded walk, not the container parser §7 warns
 * about -- and it is what keeps exiv2 (C++, GPL, and a whole extra cross-build) out of the
 * stack for the sake of 187 files.
 */
static void pi_emit_apple_content_id(ExifData *data, struct pi_exif_ctx *ctx) {
    ExifEntry *mn = exif_content_get_entry(data->ifd[EXIF_IFD_EXIF], EXIF_TAG_MAKER_NOTE);
    if (!mn || mn->size < 16) return;
    if (memcmp(mn->data, "Apple iOS", 9) != 0) return;

    const unsigned char *base = mn->data;
    size_t len = mn->size;
    ExifByteOrder order = (base[12] == 'I' && base[13] == 'I')
                        ? EXIF_BYTE_ORDER_INTEL : EXIF_BYTE_ORDER_MOTOROLA;

    size_t ifd = 14;
    if (ifd + 2 > len) return;
    unsigned count = exif_get_short(base + ifd, order);
    if (count > 256) return;

    for (unsigned i = 0; i < count; i++) {
        size_t e = ifd + 2 + (size_t)i * 12;
        if (e + 12 > len) return;
        unsigned tag = exif_get_short(base + e, order);
        unsigned fmt = exif_get_short(base + e + 2, order);
        unsigned long n = (unsigned long)exif_get_long(base + e + 4, order);
        if (tag != 0x0011 || fmt != EXIF_FORMAT_ASCII || n == 0 || n > 128) continue;

        const unsigned char *value;
        if (n <= 4) {
            value = base + e + 8;
        } else {
            unsigned long off = (unsigned long)exif_get_long(base + e + 8, order);
            if (off + n > len) return;
            value = base + off;
        }
        char buf[129];
        size_t copy = n < sizeof(buf) ? n : sizeof(buf) - 1;
        memcpy(buf, value, copy);
        buf[copy] = '\0';
        /* Trim the trailing NUL EXIF ASCII counts as part of the value. */
        size_t l = strlen(buf);
        while (l > 0 && (buf[l - 1] == '\0' || buf[l - 1] == ' ')) buf[--l] = '\0';
        if (l) ctx->fn(ctx->user, "AppleContentIdentifier", buf);
        return;
    }
}

static void pi_walk_content(ExifContent *content, void *user) {
    struct pi_exif_ctx *ctx = (struct pi_exif_ctx *)user;
    ExifIfd ifd = exif_content_get_ifd(content);
    ExifByteOrder order = exif_data_get_byte_order(content->parent);

    for (unsigned i = 0; i < content->count; i++) {
        ExifEntry *e = content->entries[i];
        if (!e || !e->data) continue;
        const char *name = exif_tag_get_name_in_ifd(e->tag, ifd);
        if (!name) continue;
        /* The maker note is handled separately and is megabytes of binary otherwise. */
        if (e->tag == EXIF_TAG_MAKER_NOTE) continue;

        char value[512];
        pi_render_entry(e, order, value, sizeof(value));
        if (value[0]) ctx->fn(ctx->user, name, value);
    }
}

static int pi_exif_from_blob(const unsigned char *blob, size_t len,
                             struct pi_exif_ctx *ctx, pi_error *err) {
    ExifData *data = exif_data_new_from_data(blob, (unsigned int)len);
    if (!data) return pi_fail(err, PI_ERR_DECODE, "exif: cannot parse block");
    exif_data_foreach_content(data, pi_walk_content, ctx);
    pi_emit_apple_content_id(data, ctx);
    exif_data_unref(data);
    pi_ok(err);
    return PI_OK;
}

/* TIFF-rooted files (TIFF proper, and CR2) are an APP1 payload minus its "Exif\0\0"
 * introducer. Offsets inside are relative to the TIFF header, so prepending those six bytes
 * and handing libexif the whole thing parses correctly. */
static int pi_exif_from_tiff_file(const char *path, struct pi_exif_ctx *ctx, pi_error *err) {
    FILE *f = fopen(path, "rb");
    if (!f) return pi_fail(err, PI_ERR_OPEN, "cannot open %s", path);
    if (fseek(f, 0, SEEK_END) != 0) { fclose(f); return pi_fail(err, PI_ERR_OPEN, "seek"); }
    long size = ftell(f);
    rewind(f);
    if (size <= 8) { fclose(f); return pi_fail(err, PI_ERR_DECODE, "tiff too small"); }

    unsigned char *buf = (unsigned char *)malloc((size_t)size + 6);
    if (!buf) { fclose(f); return pi_fail(err, PI_ERR_MEMORY, "out of memory"); }
    memcpy(buf, "Exif\0\0", 6);
    size_t got = fread(buf + 6, 1, (size_t)size, f);
    fclose(f);

    int rc = pi_exif_from_blob(buf, got + 6, ctx, err);
    free(buf);
    return rc;
}

static int pi_exif_from_heif(const char *path, struct pi_exif_ctx *ctx, pi_error *err) {
    struct heif_context *hctx = heif_context_alloc();
    if (!hctx) return pi_fail(err, PI_ERR_MEMORY, "heif: context");
    if (heif_context_read_from_file(hctx, path, NULL).code != heif_error_Ok) {
        heif_context_free(hctx);
        return pi_fail(err, PI_ERR_DECODE, "heif: cannot read %s", path);
    }
    struct heif_image_handle *handle = NULL;
    if (heif_context_get_primary_image_handle(hctx, &handle).code != heif_error_Ok) {
        heif_context_free(hctx);
        return pi_fail(err, PI_ERR_DECODE, "heif: no primary image");
    }

    heif_item_id id = 0;
    int n = heif_image_handle_get_list_of_metadata_block_IDs(handle, "Exif", &id, 1);
    int rc = PI_OK;
    if (n == 1) {
        size_t size = heif_image_handle_get_metadata_size(handle, id);
        if (size > 4) {
            unsigned char *raw = (unsigned char *)malloc(size);
            if (raw) {
                if (heif_image_handle_get_metadata(handle, id, raw).code == heif_error_Ok) {
                    /* A HEIF Exif block is a 4-byte big-endian offset followed by the payload.
                     * The offset locates the TIFF header *within* that payload -- on an iPhone
                     * it is 6, because the payload begins with the "Exif\0\0" introducer.
                     *
                     * libexif wants the payload *including* that introducer, not the TIFF
                     * header it points at: handed a bare "MM\0*" it returns an empty ExifData
                     * rather than an error, which is exactly how this went unnoticed. So skip
                     * only the 4-byte offset, and synthesise the introducer when a file does
                     * not carry one. */
                    const unsigned char *payload = raw + 4;
                    size_t payload_len = size - 4;
                    if (payload_len > 6 && memcmp(payload, "Exif\0\0", 6) == 0) {
                        rc = pi_exif_from_blob(payload, payload_len, ctx, err);
                    } else {
                        unsigned char *wrapped = (unsigned char *)malloc(payload_len + 6);
                        if (wrapped) {
                            memcpy(wrapped, "Exif\0\0", 6);
                            memcpy(wrapped + 6, payload, payload_len);
                            rc = pi_exif_from_blob(wrapped, payload_len + 6, ctx, err);
                            free(wrapped);
                        }
                    }
                }
                free(raw);
            }
        }
    }
    heif_image_handle_release(handle);
    heif_context_free(hctx);
    if (rc == PI_OK) pi_ok(err);
    return rc;
}

int pi_exif_read(const char *path, pi_exif_tag_fn fn, void *user, pi_error *err) {
    if (!fn) return pi_fail(err, PI_ERR_INVALID, "exif: no callback");
    struct pi_exif_ctx ctx = { fn, user };

    switch (pi_sniff(path)) {
        case PI_FORMAT_JPEG: {
            ExifData *data = exif_data_new_from_file(path);
            if (!data) { pi_ok(err); return PI_OK; }  /* no EXIF is normal, not an error */
            exif_data_foreach_content(data, pi_walk_content, &ctx);
            pi_emit_apple_content_id(data, &ctx);
            exif_data_unref(data);
            pi_ok(err);
            return PI_OK;
        }
        case PI_FORMAT_HEIF:
            return pi_exif_from_heif(path, &ctx, err);
        case PI_FORMAT_TIFF:
        case PI_FORMAT_CR2:
            return pi_exif_from_tiff_file(path, &ctx, err);
        default:
            pi_ok(err);
            return PI_OK;
    }
}

int pi_exif_app1_from_tiff(const uint8_t *tiff, size_t len, pi_buffer *out, pi_error *err) {
    pi_buffer_init(out);
    if (!tiff || len < 8) return pi_fail(err, PI_ERR_INVALID, "app1: tiff too small");

    unsigned char *blob = (unsigned char *)malloc(len + 6);
    if (!blob) return pi_fail(err, PI_ERR_MEMORY, "out of memory");
    memcpy(blob, "Exif\0\0", 6);
    memcpy(blob + 6, tiff, len);

    ExifData *data = exif_data_new_from_data(blob, (unsigned int)(len + 6));
    free(blob);
    if (!data) return pi_fail(err, PI_ERR_DECODE, "app1: cannot parse tiff");

    /* The maker note references offsets in the original file that will not survive being
     * re-serialised into a much smaller block, and nothing downstream reads it. */
    exif_content_remove_entry(data->ifd[EXIF_IFD_EXIF],
        exif_content_get_entry(data->ifd[EXIF_IFD_EXIF], EXIF_TAG_MAKER_NOTE));
    /* Likewise the embedded thumbnail: it would be dead weight in a file that has its own. */
    exif_data_set_data_type(data, EXIF_DATA_TYPE_UNCOMPRESSED_CHUNKY);
    free(data->data);
    data->data = NULL;
    data->size = 0;

    unsigned char *saved = NULL;
    unsigned int saved_len = 0;
    exif_data_save_data(data, &saved, &saved_len);
    exif_data_unref(data);

    if (!saved || saved_len == 0) {
        free(saved);
        return pi_fail(err, PI_ERR_ENCODE, "app1: nothing to save");
    }
    out->bytes = saved;
    out->len = saved_len;
    pi_ok(err);
    return PI_OK;
}

/* Declared in pi_internal.h. Returns 1 for "no usable orientation", so a caller can apply the
 * result unconditionally and get the identity when a file says nothing. */
int pi_exif_orientation_in_block(const uint8_t *block, size_t len) {
    if (!block || len < 14) return 1;
    ExifData *data = exif_data_new_from_data(block, (unsigned int)len);
    if (!data) return 1;
    int orientation = 1;
    ExifEntry *entry = exif_content_get_entry(data->ifd[EXIF_IFD_0], EXIF_TAG_ORIENTATION);
    if (entry && entry->data && entry->size >= 2) {
        int value = (int)exif_get_short(entry->data, exif_data_get_byte_order(data));
        if (value >= 1 && value <= 8) orientation = value;
    }
    exif_data_unref(data);
    return orientation;
}

int pi_exif_orientation_of_file(const char *path) {
    FILE *f = fopen(path, "rb");
    if (!f) return 1;
    if (fseek(f, 0, SEEK_END) != 0) { fclose(f); return 1; }
    long size = ftell(f);
    rewind(f);
    if (size <= 8) { fclose(f); return 1; }
    /* Cap the read: orientation lives in IFD0, near the front, and this is called on TIFFs
     * that can be large. */
    if (size > 1 << 20) size = 1 << 20;

    unsigned char *buf = (unsigned char *)malloc((size_t)size + 6);
    if (!buf) { fclose(f); return 1; }
    memcpy(buf, "Exif\0\0", 6);
    size_t got = fread(buf + 6, 1, (size_t)size, f);
    fclose(f);

    int orientation = pi_exif_orientation_in_block(buf, got + 6);
    free(buf);
    return orientation;
}
