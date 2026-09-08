/* Decode dispatch, plus the ffmpeg-backed path for the formats that are not worth a
 * dedicated library.
 *
 * The library holds 31 PNGs, 5 TIFFs and one MPO against 33.5k JPEGs and HEICs. Routing that
 * tail through ffmpeg -- which is linked for video regardless -- is what kept libpng and
 * libtiff out of the build entirely.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libswscale/swscale.h>
#include <libavutil/imgutils.h>
#include <libavutil/display.h>
#include <libavutil/pixdesc.h>

#include "pi_internal.h"

/* Turns a decoded AVFrame into an RGB or RGBA pi_image. Alpha is preserved here and flattened
 * later, so the caller decides the background colour rather than this file. */
int pi_frame_to_image(struct AVFrame *frame, pi_image *out, pi_error *err) {
    pi_image_init(out);

    const AVPixFmtDescriptor *desc = av_pix_fmt_desc_get((enum AVPixelFormat)frame->format);
    int has_alpha = desc && (desc->flags & AV_PIX_FMT_FLAG_ALPHA);
    enum AVPixelFormat want = has_alpha ? AV_PIX_FMT_RGBA : AV_PIX_FMT_RGB24;
    int channels = has_alpha ? 4 : 3;

    if (pi_image_alloc(out, frame->width, frame->height, channels, err) != PI_OK)
        return err ? err->code : PI_ERR_MEMORY;

    struct SwsContext *sws = sws_getContext(frame->width, frame->height,
                                            (enum AVPixelFormat)frame->format,
                                            frame->width, frame->height, want,
                                            SWS_LANCZOS | SWS_ACCURATE_RND, NULL, NULL, NULL);
    if (!sws) {
        pi_image_free(out);
        return pi_fail(err, PI_ERR_DECODE, "swscale: no context for pixel format %d", frame->format);
    }

    uint8_t *dst[4] = { out->pixels, NULL, NULL, NULL };
    int dst_stride[4] = { frame->width * channels, 0, 0, 0 };
    sws_scale(sws, (const uint8_t * const *)frame->data, frame->linesize, 0, frame->height,
              dst, dst_stride);
    sws_freeContext(sws);
    pi_ok(err);
    return PI_OK;
}

/* Decodes the first frame of whatever ffmpeg can open. Used for PNG/TIFF stills and, with a
 * seek, for video poster frames. */
static int pi_decode_first_frame(const char *path, double seek_to,
                                 pi_image *out, int *rotation, pi_error *err) {
    AVFormatContext *fmt = NULL;
    AVCodecContext *dec = NULL;
    AVFrame *frame = NULL;
    AVPacket *pkt = NULL;
    int rc = PI_ERR_DECODE;

    pi_image_init(out);
    if (rotation) *rotation = 0;

    if (avformat_open_input(&fmt, path, NULL, NULL) < 0)
        return pi_fail(err, PI_ERR_OPEN, "ffmpeg: cannot open %s", path);
    if (avformat_find_stream_info(fmt, NULL) < 0) {
        avformat_close_input(&fmt);
        return pi_fail(err, PI_ERR_DECODE, "ffmpeg: no stream info");
    }

    const AVCodec *codec = NULL;
    int vs = av_find_best_stream(fmt, AVMEDIA_TYPE_VIDEO, -1, -1, &codec, 0);
    if (vs < 0 || !codec) {
        avformat_close_input(&fmt);
        return pi_fail(err, PI_ERR_UNSUPPORTED, "ffmpeg: no decodable video stream");
    }

    if (rotation) {
        const AVPacketSideData *sd = av_packet_side_data_get(
            fmt->streams[vs]->codecpar->coded_side_data,
            fmt->streams[vs]->codecpar->nb_coded_side_data,
            AV_PKT_DATA_DISPLAYMATRIX);
        if (sd)
            *rotation = pi_display_rotation((const int32_t *)sd->data);
    }

    dec = avcodec_alloc_context3(codec);
    if (!dec) { rc = pi_fail(err, PI_ERR_MEMORY, "ffmpeg: codec context"); goto done; }
    avcodec_parameters_to_context(dec, fmt->streams[vs]->codecpar);
    dec->thread_count = 1;  /* the caller runs one worker per core already */
    if (avcodec_open2(dec, codec, NULL) < 0) {
        rc = pi_fail(err, PI_ERR_DECODE, "ffmpeg: cannot open decoder %s", codec->name);
        goto done;
    }

    if (seek_to > 0) {
        int64_t ts = (int64_t)(seek_to * AV_TIME_BASE);
        /* Backward seek lands on the keyframe at or before the target, so decoding from
         * there always produces a valid picture. If it fails we simply start at zero. */
        if (avformat_seek_file(fmt, -1, INT64_MIN, ts, ts, 0) >= 0)
            avcodec_flush_buffers(dec);
    }

    frame = av_frame_alloc();
    pkt = av_packet_alloc();
    if (!frame || !pkt) { rc = pi_fail(err, PI_ERR_MEMORY, "ffmpeg: frame/packet"); goto done; }

    while (av_read_frame(fmt, pkt) >= 0) {
        if (pkt->stream_index == vs) {
            if (avcodec_send_packet(dec, pkt) >= 0) {
                if (avcodec_receive_frame(dec, frame) >= 0) {
                    av_packet_unref(pkt);
                    rc = pi_frame_to_image(frame, out, err);
                    goto done;
                }
            }
        }
        av_packet_unref(pkt);
    }

    /* Flush: a short clip may hold its only frame in the decoder's delay queue. */
    avcodec_send_packet(dec, NULL);
    if (avcodec_receive_frame(dec, frame) >= 0) {
        rc = pi_frame_to_image(frame, out, err);
        goto done;
    }
    rc = pi_fail(err, PI_ERR_DECODE, "ffmpeg: no frame decoded");

done:
    av_packet_free(&pkt);
    av_frame_free(&frame);
    avcodec_free_context(&dec);
    avformat_close_input(&fmt);
    return rc;
}

int pi_decode_via_ffmpeg(const char *path, pi_image *out, pi_error *err) {
    return pi_decode_first_frame(path, 0, out, NULL, err);
}

int pi_video_poster(const char *path, double at, pi_image *out, pi_error *err) {
    int rotation = 0;
    int rc = pi_decode_first_frame(path, at, out, &rotation, err);
    if (rc != PI_OK) return rc;

    /* 118 files in the library carry a 90 degree display matrix and 15 carry 180. A poster
     * frame that ignores it is a sideways thumbnail. */
    int orientation = 1;
    if (rotation == 90) orientation = 6;
    else if (rotation == 180) orientation = 3;
    else if (rotation == 270) orientation = 8;
    if (orientation != 1) return pi_image_apply_orientation(out, orientation, err);
    return PI_OK;
}

static int pi_read_file(const char *path, uint8_t **data, size_t *len, pi_error *err) {
    FILE *f = fopen(path, "rb");
    if (!f) return pi_fail(err, PI_ERR_OPEN, "cannot open %s", path);
    if (fseek(f, 0, SEEK_END) != 0) { fclose(f); return pi_fail(err, PI_ERR_OPEN, "cannot seek"); }
    long size = ftell(f);
    if (size <= 0) { fclose(f); return pi_fail(err, PI_ERR_OPEN, "empty file"); }
    rewind(f);
    uint8_t *buf = (uint8_t *)malloc((size_t)size);
    if (!buf) { fclose(f); return pi_fail(err, PI_ERR_MEMORY, "out of memory"); }
    size_t got = fread(buf, 1, (size_t)size, f);
    fclose(f);
    if (got != (size_t)size) { free(buf); return pi_fail(err, PI_ERR_OPEN, "short read"); }
    *data = buf;
    *len = got;
    return PI_OK;
}

int pi_decode_memory(const uint8_t *data, size_t len, int max_long_edge,
                     pi_image *out, pi_error *err) {
    pi_image_init(out);
    if (len > 3 && data[0] == 0xFF && data[1] == 0xD8)
        return pi_decode_jpeg_mem(data, len, max_long_edge, out, err);
    return pi_fail(err, PI_ERR_UNSUPPORTED, "in-memory decode supports JPEG only");
}

int pi_decode(const char *path, int max_long_edge, pi_image *out, pi_error *err) {
    pi_image_init(out);
    switch (pi_sniff(path)) {
        case PI_FORMAT_JPEG: {
            uint8_t *data = NULL;
            size_t len = 0;
            int rc = pi_read_file(path, &data, &len, err);
            if (rc != PI_OK) return rc;
            rc = pi_decode_jpeg_mem(data, len, max_long_edge, out, err);
            free(data);
            return rc;
        }
        case PI_FORMAT_HEIF:
            return pi_decode_heif(path, out, err);
        case PI_FORMAT_PNG:
            return pi_decode_via_ffmpeg(path, out, err);
        case PI_FORMAT_TIFF: {
            int rc = pi_decode_via_ffmpeg(path, out, err);
            if (rc != PI_OK) return rc;
            /* ffmpeg's TIFF decoder reports orientation but does not apply it. Only 5 files
             * in the library, but the rule §3 states has no exceptions. */
            return pi_image_apply_orientation(out, pi_exif_orientation_of_file(path), err);
        }
        case PI_FORMAT_CR2:
            /* Deliberately not decodable. A CR2 is carved, not developed: IFD0 holds a
             * full-resolution camera JPEG, so extracting it needs no demosaic and no LibRaw. */
            return pi_fail(err, PI_ERR_UNSUPPORTED, "CR2 is extracted, not decoded");
        case PI_FORMAT_VIDEO:
            return pi_fail(err, PI_ERR_UNSUPPORTED, "video has no still decode path");
        default:
            return pi_fail(err, PI_ERR_UNSUPPORTED, "unrecognised format");
    }
}
