/* See photos_imaging_fixture.h: test-input generation, not part of the pipeline. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>

#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libavutil/opt.h>
#include <libavutil/display.h>
#include <libavutil/imgutils.h>
#include <libavutil/channel_layout.h>
#include <math.h>

#include <libheif/heif.h>

#include "photos_imaging_fixture.h"
#include "pi_internal.h"

int pi_fixture_write_video(const char *path, int width, int height,
                           int frames, int rotation,
                           const char *content_identifier, int with_audio, pi_error *err) {
    AVFormatContext *ofmt = NULL;
    AVCodecContext *enc = NULL;
    AVCodecContext *aenc = NULL;
    AVStream *ast = NULL;
    AVFrame *frame = NULL;
    AVPacket *pkt = NULL;
    int rc = PI_ERR_ENCODE;

    /* HEVC needs even dimensions. */
    width &= ~1;
    height &= ~1;
    if (width < 2 || height < 2 || frames < 1)
        return pi_fail(err, PI_ERR_INVALID, "fixture: bad geometry");

    const AVCodec *codec = avcodec_find_encoder_by_name("libx265");
    if (!codec) return pi_fail(err, PI_ERR_UNSUPPORTED, "fixture: libx265 missing");

    /* A .mov is written as QuickTime, not as an MP4 under a .mov name. It matters for Live
     * Photos: PHLivePhoto assembles a pair only when the MOV is a QuickTime file carrying
     * com.apple.quicktime.content.identifier in its mdta keys -- measured on macOS, where the same
     * frames and identifier in an MP4 container come back as no Live Photo at all, while the
     * still-image-time metadata track Apple also writes turned out not to be required. */
    size_t path_len = strlen(path);
    const char *muxer =
        (path_len >= 4 && strcasecmp(path + path_len - 4, ".mov") == 0) ? "mov" : "mp4";
    if (avformat_alloc_output_context2(&ofmt, NULL, muxer, path) < 0 || !ofmt)
        return pi_fail(err, PI_ERR_ENCODE, "fixture: cannot create %s", muxer);

    enc = avcodec_alloc_context3(codec);
    enc->width = width;
    enc->height = height;
    enc->pix_fmt = AV_PIX_FMT_YUV420P;
    enc->time_base = (AVRational){ 1, 25 };
    enc->framerate = (AVRational){ 25, 1 };
    enc->gop_size = 5;
    if (ofmt->oformat->flags & AVFMT_GLOBALHEADER)
        enc->flags |= AV_CODEC_FLAG_GLOBAL_HEADER;
    av_opt_set(enc->priv_data, "preset", "ultrafast", 0);
    av_opt_set(enc->priv_data, "x265-params", "log-level=none", 0);

    if (avcodec_open2(enc, codec, NULL) < 0) {
        rc = pi_fail(err, PI_ERR_ENCODE, "fixture: cannot open libx265");
        goto done;
    }

    AVStream *st = avformat_new_stream(ofmt, NULL);
    if (!st) { rc = pi_fail(err, PI_ERR_ENCODE, "fixture: cannot add stream"); goto done; }
    avcodec_parameters_from_context(st->codecpar, enc);
    st->codecpar->codec_tag = MKTAG('h', 'v', 'c', '1');
    st->time_base = enc->time_base;

    if (rotation % 360 != 0) {
        uint8_t *matrix = av_stream_new_side_data(st, AV_PKT_DATA_DISPLAYMATRIX,
                                                  sizeof(int32_t) * 9);
        if (matrix) av_display_rotation_set((int32_t *)matrix, (double)rotation);
    }

    if (with_audio) {
        const AVCodec *acodec = avcodec_find_encoder(AV_CODEC_ID_AAC);
        if (!acodec) { rc = pi_fail(err, PI_ERR_UNSUPPORTED, "fixture: aac missing"); goto done; }
        aenc = avcodec_alloc_context3(acodec);
        aenc->sample_rate = 44100;
        aenc->sample_fmt = AV_SAMPLE_FMT_FLTP;
        av_channel_layout_default(&aenc->ch_layout, 1);
        aenc->bit_rate = 64000;
        aenc->time_base = (AVRational){ 1, aenc->sample_rate };
        if (ofmt->oformat->flags & AVFMT_GLOBALHEADER)
            aenc->flags |= AV_CODEC_FLAG_GLOBAL_HEADER;
        if (avcodec_open2(aenc, acodec, NULL) < 0) {
            rc = pi_fail(err, PI_ERR_ENCODE, "fixture: cannot open aac");
            goto done;
        }
        ast = avformat_new_stream(ofmt, NULL);
        if (!ast) { rc = pi_fail(err, PI_ERR_ENCODE, "fixture: cannot add audio stream"); goto done; }
        avcodec_parameters_from_context(ast->codecpar, aenc);
        ast->time_base = aenc->time_base;
    }

    if (!(ofmt->oformat->flags & AVFMT_NOFILE)) {
        if (avio_open(&ofmt->pb, path, AVIO_FLAG_WRITE) < 0) {
            rc = pi_fail(err, PI_ERR_ENCODE, "fixture: cannot write %s", path);
            goto done;
        }
    }
    {
        /* use_metadata_tags is what makes the mov muxer write arbitrary keys into the
         * mdta metadata box, rather than dropping everything it does not recognise. */
        AVDictionary *opts = NULL;
        if (content_identifier && *content_identifier) {
            av_dict_set(&ofmt->metadata, "com.apple.quicktime.content.identifier",
                        content_identifier, 0);
            av_dict_set(&opts, "movflags", "+use_metadata_tags", 0);
        }
        int wrc = avformat_write_header(ofmt, &opts);
        av_dict_free(&opts);
        if (wrc < 0) {
            rc = pi_fail(err, PI_ERR_ENCODE, "fixture: cannot write header");
            goto done;
        }
    }

    frame = av_frame_alloc();
    pkt = av_packet_alloc();
    if (!frame || !pkt) { rc = pi_fail(err, PI_ERR_MEMORY, "fixture: alloc"); goto done; }
    frame->format = enc->pix_fmt;
    frame->width = width;
    frame->height = height;
    if (av_frame_get_buffer(frame, 0) < 0) {
        rc = pi_fail(err, PI_ERR_MEMORY, "fixture: frame buffer");
        goto done;
    }

    for (int i = 0; i < frames; i++) {
        if (av_frame_make_writable(frame) < 0) break;
        /* Moving bars: enough structure that a poster frame taken at a seek offset is
         * visibly different from frame zero, which is what the poster test checks. */
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++)
                frame->data[0][y * frame->linesize[0] + x] =
                    (uint8_t)(((x + i * 8) / 8 % 2) ? 200 : 60);
        }
        for (int y = 0; y < height / 2; y++) {
            for (int x = 0; x < width / 2; x++) {
                frame->data[1][y * frame->linesize[1] + x] = (uint8_t)(90 + i * 3);
                frame->data[2][y * frame->linesize[2] + x] = (uint8_t)(200 - i * 3);
            }
        }
        frame->pts = i;

        if (avcodec_send_frame(enc, frame) < 0) break;
        while (avcodec_receive_packet(enc, pkt) >= 0) {
            av_packet_rescale_ts(pkt, enc->time_base, st->time_base);
            pkt->stream_index = st->index;
            av_interleaved_write_frame(ofmt, pkt);
            av_packet_unref(pkt);
        }
    }

    avcodec_send_frame(enc, NULL);
    while (avcodec_receive_packet(enc, pkt) >= 0) {
        av_packet_rescale_ts(pkt, enc->time_base, st->time_base);
        pkt->stream_index = st->index;
        av_interleaved_write_frame(ofmt, pkt);
        av_packet_unref(pkt);
    }

    if (aenc) {
        /* A 440 Hz tone for as long as the pictures last, in the encoder's fixed frame size. */
        AVFrame *af = av_frame_alloc();
        if (!af) { rc = pi_fail(err, PI_ERR_MEMORY, "fixture: audio frame"); goto done; }
        af->format = aenc->sample_fmt;
        af->nb_samples = aenc->frame_size;
        af->sample_rate = aenc->sample_rate;
        av_channel_layout_copy(&af->ch_layout, &aenc->ch_layout);
        if (av_frame_get_buffer(af, 0) < 0) {
            av_frame_free(&af);
            rc = pi_fail(err, PI_ERR_MEMORY, "fixture: audio buffer");
            goto done;
        }
        long total = (long)frames * aenc->sample_rate / 25;
        for (long at = 0; at < total; at += aenc->frame_size) {
            if (av_frame_make_writable(af) < 0) break;
            float *samples = (float *)af->data[0];
            for (int i = 0; i < aenc->frame_size; i++)
                samples[i] = 0.25f * (float)sin(2.0 * 3.141592653589793 * 440.0 * (double)(at + i) / aenc->sample_rate);
            af->pts = at;
            if (avcodec_send_frame(aenc, af) < 0) break;
            while (avcodec_receive_packet(aenc, pkt) >= 0) {
                av_packet_rescale_ts(pkt, aenc->time_base, ast->time_base);
                pkt->stream_index = ast->index;
                av_interleaved_write_frame(ofmt, pkt);
                av_packet_unref(pkt);
            }
        }
        av_frame_free(&af);
        avcodec_send_frame(aenc, NULL);
        while (avcodec_receive_packet(aenc, pkt) >= 0) {
            av_packet_rescale_ts(pkt, aenc->time_base, ast->time_base);
            pkt->stream_index = ast->index;
            av_interleaved_write_frame(ofmt, pkt);
            av_packet_unref(pkt);
        }
    }

    if (av_write_trailer(ofmt) < 0) {
        rc = pi_fail(err, PI_ERR_ENCODE, "fixture: cannot finalise");
        goto done;
    }
    rc = PI_OK;
    pi_ok(err);

done:
    av_packet_free(&pkt);
    av_frame_free(&frame);
    avcodec_free_context(&enc);
    avcodec_free_context(&aenc);
    if (ofmt && !(ofmt->oformat->flags & AVFMT_NOFILE) && ofmt->pb) avio_closep(&ofmt->pb);
    avformat_free_context(ofmt);
    return rc;
}

int pi_fixture_write_heic_with_exif(const char *path, int width, int height,
                                    const uint8_t *app1, size_t app1_len, pi_error *err) {
    struct heif_context *ctx = heif_context_alloc();
    struct heif_image *img = NULL;
    struct heif_encoder *enc = NULL;
    struct heif_image_handle *handle = NULL;
    int rc = PI_ERR_ENCODE;

    if (heif_image_create(width, height, heif_colorspace_RGB,
                          heif_chroma_interleaved_RGB, &img).code != heif_error_Ok) {
        heif_context_free(ctx);
        return pi_fail(err, PI_ERR_ENCODE, "fixture: heif image create");
    }
    heif_image_add_plane(img, heif_channel_interleaved, width, height, 8);
    int stride = 0;
    uint8_t *plane = heif_image_get_plane(img, heif_channel_interleaved, &stride);
    for (int y = 0; y < height; y++)
        for (int x = 0; x < width; x++) {
            uint8_t *p = plane + (size_t)y * stride + (size_t)x * 3;
            p[0] = (uint8_t)(x * 255 / (width > 1 ? width - 1 : 1));
            p[1] = (uint8_t)(y * 255 / (height > 1 ? height - 1 : 1));
            p[2] = (((x / 8) + (y / 8)) % 2) ? 230 : 40;
        }

    if (heif_context_get_encoder_for_format(ctx, heif_compression_HEVC, &enc).code != heif_error_Ok) {
        rc = pi_fail(err, PI_ERR_UNSUPPORTED, "fixture: no HEVC encoder");
        goto done;
    }
    heif_encoder_set_lossy_quality(enc, 70);
    heif_encoder_set_parameter_string(enc, "x265:pools", "1");
    heif_encoder_set_parameter_string(enc, "preset", "ultrafast");

    if (heif_context_encode_image(ctx, img, enc, NULL, &handle).code != heif_error_Ok) {
        rc = pi_fail(err, PI_ERR_ENCODE, "fixture: heif encode");
        goto done;
    }
    if (app1 && app1_len > 0) {
        /* libheif prepends the 4-byte tiff-header offset itself. */
        if (heif_context_add_exif_metadata(ctx, handle, app1, (int)app1_len).code != heif_error_Ok) {
            rc = pi_fail(err, PI_ERR_ENCODE, "fixture: cannot attach exif");
            goto done;
        }
    }
    if (heif_context_write_to_file(ctx, path).code != heif_error_Ok) {
        rc = pi_fail(err, PI_ERR_ENCODE, "fixture: cannot write %s", path);
        goto done;
    }
    rc = PI_OK;
    pi_ok(err);

done:
    if (handle) heif_image_handle_release(handle);
    if (enc) heif_encoder_release(enc);
    heif_image_release(img);
    heif_context_free(ctx);
    return rc;
}
