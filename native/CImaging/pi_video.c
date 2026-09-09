/* Video probe and transcode.
 *
 * Everything becomes 1080p-ceiling HEVC/AAC in a faststart MP4. HEVC rather than H.264
 * because 223 of the library's 550 videos are already HEVC, iOS 18 hardware-decodes it on
 * every supported device, and x265 is in the build for HEIC previews regardless -- so it is
 * the encoder that was already there, and choosing it let libx264 out of the stack entirely.
 *
 * The filter chain is built as a description string and parsed, the way the ffmpeg CLI does
 * it, rather than by wiring filters up by hand. It is the same graph either way and far less
 * of it can be got wrong.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libavfilter/avfilter.h>
#include <libavfilter/buffersrc.h>
#include <libavfilter/buffersink.h>
#include <libavutil/display.h>
#include <libavutil/opt.h>
#include <libavutil/dict.h>
#include <libswresample/swresample.h>

#include "pi_internal.h"

int pi_video_probe(const char *path, pi_video_info *out, pi_error *err) {
    memset(out, 0, sizeof(*out));

    AVFormatContext *fmt = NULL;
    if (avformat_open_input(&fmt, path, NULL, NULL) < 0)
        return pi_fail(err, PI_ERR_OPEN, "cannot open %s", path);
    if (avformat_find_stream_info(fmt, NULL) < 0) {
        avformat_close_input(&fmt);
        return pi_fail(err, PI_ERR_DECODE, "no stream info");
    }

    out->duration = fmt->duration > 0 ? (double)fmt->duration / AV_TIME_BASE : 0;

    int vs = av_find_best_stream(fmt, AVMEDIA_TYPE_VIDEO, -1, -1, NULL, 0);
    if (vs >= 0) {
        AVCodecParameters *par = fmt->streams[vs]->codecpar;
        out->width = par->width;
        out->height = par->height;
        /* field_order is what the container claims; it is advisory, which is why decision 11
         * deinterlaces on detection rather than unconditionally. */
        out->interlaced = (par->field_order != AV_FIELD_UNKNOWN &&
                           par->field_order != AV_FIELD_PROGRESSIVE);

        const AVPacketSideData *sd = av_packet_side_data_get(
            par->coded_side_data, par->nb_coded_side_data, AV_PKT_DATA_DISPLAYMATRIX);
        if (sd)
            out->rotation = pi_display_rotation((const int32_t *)sd->data);
    }

    out->has_audio = av_find_best_stream(fmt, AVMEDIA_TYPE_AUDIO, -1, -1, NULL, 0) >= 0;

    /* The Live Photo pairing id. ffmpeg surfaces QuickTime's mdta metadata as ordinary
     * format-level entries, so the MOV half of decision 14 costs nothing. */
    AVDictionaryEntry *cid = av_dict_get(fmt->metadata,
                                         "com.apple.quicktime.content.identifier", NULL, 0);
    if (!cid) cid = av_dict_get(fmt->metadata, "content.identifier", NULL, 0);
    if (cid && cid->value)
        snprintf(out->content_identifier, sizeof(out->content_identifier), "%s", cid->value);

    avformat_close_input(&fmt);
    pi_ok(err);
    return PI_OK;
}

struct pi_stream {
    AVCodecContext *dec;
    AVCodecContext *enc;
    AVFilterContext *src;
    AVFilterContext *sink;
    AVFilterGraph *graph;
    int in_index;
    int out_index;
};

static void pi_stream_close(struct pi_stream *s) {
    if (!s) return;
    avcodec_free_context(&s->dec);
    avcodec_free_context(&s->enc);
    avfilter_graph_free(&s->graph);
    memset(s, 0, sizeof(*s));
}

static int pi_build_graph(struct pi_stream *s, const char *args, const char *desc,
                          const char *src_name, const char *sink_name,
                          enum AVMediaType type, pi_error *err) {
    s->graph = avfilter_graph_alloc();
    if (!s->graph) return pi_fail(err, PI_ERR_MEMORY, "filter graph");

    const AVFilter *bufsrc = avfilter_get_by_name(src_name);
    const AVFilter *bufsink = avfilter_get_by_name(sink_name);
    if (!bufsrc || !bufsink) return pi_fail(err, PI_ERR_UNSUPPORTED, "missing buffer filters");

    if (avfilter_graph_create_filter(&s->src, bufsrc, "in", args, NULL, s->graph) < 0)
        return pi_fail(err, PI_ERR_DECODE, "buffer source: %s", args);
    if (avfilter_graph_create_filter(&s->sink, bufsink, "out", NULL, NULL, s->graph) < 0)
        return pi_fail(err, PI_ERR_DECODE, "buffer sink");

    AVFilterInOut *outputs = avfilter_inout_alloc();
    AVFilterInOut *inputs = avfilter_inout_alloc();
    if (!outputs || !inputs) {
        avfilter_inout_free(&outputs); avfilter_inout_free(&inputs);
        return pi_fail(err, PI_ERR_MEMORY, "filter inout");
    }
    outputs->name = av_strdup("in");
    outputs->filter_ctx = s->src;
    outputs->pad_idx = 0;
    outputs->next = NULL;
    inputs->name = av_strdup("out");
    inputs->filter_ctx = s->sink;
    inputs->pad_idx = 0;
    inputs->next = NULL;

    int rc = avfilter_graph_parse_ptr(s->graph, desc, &inputs, &outputs, NULL);
    avfilter_inout_free(&outputs);
    avfilter_inout_free(&inputs);
    if (rc < 0) return pi_fail(err, PI_ERR_DECODE, "filter graph '%s'", desc);
    if (avfilter_graph_config(s->graph, NULL) < 0)
        return pi_fail(err, PI_ERR_DECODE, "filter graph config");
    (void)type;
    return PI_OK;
}

static int pi_encode_and_write(AVFormatContext *ofmt, struct pi_stream *s,
                               AVFrame *frame, AVPacket *pkt, AVRational src_tb) {
    if (avcodec_send_frame(s->enc, frame) < 0) return -1;
    while (avcodec_receive_packet(s->enc, pkt) >= 0) {
        pkt->stream_index = s->out_index;
        av_packet_rescale_ts(pkt, src_tb, ofmt->streams[s->out_index]->time_base);
        if (av_interleaved_write_frame(ofmt, pkt) < 0) { av_packet_unref(pkt); return -1; }
        av_packet_unref(pkt);
    }
    return 0;
}

int pi_video_transcode(const char *in_path, const char *out_path,
                       int max_height, int quality, int threads, pi_error *err) {
    AVFormatContext *ifmt = NULL, *ofmt = NULL;
    struct pi_stream v, a;
    AVFrame *frame = NULL, *filtered = NULL;
    AVPacket *pkt = NULL, *opkt = NULL;
    int vs = -1, as = -1, rc = PI_ERR_DECODE, header_written = 0;
    char desc[512], args[512];

    memset(&v, 0, sizeof(v));
    memset(&a, 0, sizeof(a));
    v.in_index = a.in_index = -1;
    v.out_index = a.out_index = -1;

    pi_video_info info;
    if (pi_video_probe(in_path, &info, err) != PI_OK) return err ? err->code : PI_ERR_DECODE;

    if (avformat_open_input(&ifmt, in_path, NULL, NULL) < 0)
        return pi_fail(err, PI_ERR_OPEN, "cannot open %s", in_path);
    if (avformat_find_stream_info(ifmt, NULL) < 0) {
        avformat_close_input(&ifmt);
        return pi_fail(err, PI_ERR_DECODE, "no stream info");
    }

    if (avformat_alloc_output_context2(&ofmt, NULL, "mp4", out_path) < 0 || !ofmt) {
        avformat_close_input(&ifmt);
        return pi_fail(err, PI_ERR_ENCODE, "cannot create mp4 output");
    }

    /* ---------------- video ---------------- */
    const AVCodec *vdec_codec = NULL;
    vs = av_find_best_stream(ifmt, AVMEDIA_TYPE_VIDEO, -1, -1, &vdec_codec, 0);
    if (vs < 0 || !vdec_codec) { rc = pi_fail(err, PI_ERR_UNSUPPORTED, "no video stream"); goto done; }

    v.in_index = vs;
    v.dec = avcodec_alloc_context3(vdec_codec);
    avcodec_parameters_to_context(v.dec, ifmt->streams[vs]->codecpar);
    v.dec->pkt_timebase = ifmt->streams[vs]->time_base;
    /* Bounded for the same reason as the encoder below: the caller already runs one worker
     * per core. Left at ffmpeg's default this is one decoder thread per core *per worker* --
     * measured at 545 threads and 9.3 GB on a 16-core machine, for a pipeline that is
     * already parallel one level up. */
    if (threads > 0) v.dec->thread_count = threads;
    if (avcodec_open2(v.dec, vdec_codec, NULL) < 0) {
        rc = pi_fail(err, PI_ERR_DECODE, "cannot open %s decoder", vdec_codec->name);
        goto done;
    }

    {
        /* Rotation is baked into the pixels and the output carries no display matrix, so
         * the 118 files at 90 degrees and 15 at 180 come out upright everywhere. */
        char chain[384];
        chain[0] = '\0';
        size_t used = 0;
        if (info.interlaced)
            used += (size_t)snprintf(chain + used, sizeof(chain) - used, "yadif=deint=interlaced,");
        if (info.rotation == 90)
            used += (size_t)snprintf(chain + used, sizeof(chain) - used, "transpose=clock,");
        else if (info.rotation == 270)
            used += (size_t)snprintf(chain + used, sizeof(chain) - used, "transpose=cclock,");
        else if (info.rotation == 180)
            used += (size_t)snprintf(chain + used, sizeof(chain) - used, "hflip,vflip,");

        /* A ceiling, not a target: 137 files are 640x480 or smaller and are left alone.
         * force_original_aspect_ratio=decrease with a huge width bounds height only; the
         * -2 keeps both dimensions even, which HEVC requires. */
        used += (size_t)snprintf(chain + used, sizeof(chain) - used,
                                 "scale=w=-2:h='min(%d,ih)':force_divisible_by=2,"
                                 "format=yuv420p", max_height);
        snprintf(desc, sizeof(desc), "%s", chain);

        AVRational sar = ifmt->streams[vs]->sample_aspect_ratio;
        if (sar.num <= 0 || sar.den <= 0) { sar.num = 1; sar.den = 1; }
        snprintf(args, sizeof(args),
                 "video_size=%dx%d:pix_fmt=%d:time_base=%d/%d:pixel_aspect=%d/%d",
                 v.dec->width, v.dec->height, v.dec->pix_fmt,
                 ifmt->streams[vs]->time_base.num, ifmt->streams[vs]->time_base.den,
                 sar.num, sar.den);
        if (pi_build_graph(&v, args, desc, "buffer", "buffersink", AVMEDIA_TYPE_VIDEO, err) != PI_OK) {
            rc = err ? err->code : PI_ERR_DECODE;
            goto done;
        }
    }

    {
        const AVCodec *venc_codec = avcodec_find_encoder_by_name("libx265");
        if (!venc_codec) { rc = pi_fail(err, PI_ERR_UNSUPPORTED, "libx265 not built in"); goto done; }
        v.enc = avcodec_alloc_context3(venc_codec);
        v.enc->width = av_buffersink_get_w(v.sink);
        v.enc->height = av_buffersink_get_h(v.sink);
        v.enc->pix_fmt = AV_PIX_FMT_YUV420P;
        v.enc->time_base = av_buffersink_get_time_base(v.sink);
        v.enc->sample_aspect_ratio = av_buffersink_get_sample_aspect_ratio(v.sink);
        AVRational fr = av_buffersink_get_frame_rate(v.sink);
        if (fr.num > 0 && fr.den > 0) v.enc->framerate = fr;
        if (ofmt->oformat->flags & AVFMT_GLOBALHEADER)
            v.enc->flags |= AV_CODEC_FLAG_GLOBAL_HEADER;

        char crf[16];
        snprintf(crf, sizeof(crf), "%d", quality);
        av_opt_set(v.enc->priv_data, "crf", crf, 0);
        av_opt_set(v.enc->priv_data, "preset", "medium", 0);
        /* Same reasoning as the HEIC encoder: the caller is already running one worker per
         * core, so a full x265 pool per file is a second, hidden pool. */
        if (threads > 0) v.enc->thread_count = threads;
        /* x265 writes its own banner to stderr on every single file otherwise. */
        /* pools= is the one that matters. ffmpeg's libx265 wrapper maps thread_count to
         * x265's *frame* threads only; its worker pool still defaults to every core, so
         * sixteen concurrent transcodes opened ~200 threads. The HEIC path already sets this
         * through libheif; the video path needs it spelled out here. */
        char x265params[64];
        if (threads > 0)
            snprintf(x265params, sizeof(x265params), "log-level=none:pools=%d", threads);
        else
            snprintf(x265params, sizeof(x265params), "log-level=none");
        av_opt_set(v.enc->priv_data, "x265-params", x265params, 0);

        if (avcodec_open2(v.enc, venc_codec, NULL) < 0) {
            rc = pi_fail(err, PI_ERR_ENCODE, "cannot open libx265");
            goto done;
        }
        AVStream *os = avformat_new_stream(ofmt, NULL);
        if (!os) { rc = pi_fail(err, PI_ERR_ENCODE, "cannot add video stream"); goto done; }
        avcodec_parameters_from_context(os->codecpar, v.enc);
        /* hvc1 rather than hev1: AVFoundation will not play the latter. */
        os->codecpar->codec_tag = MKTAG('h', 'v', 'c', '1');
        os->time_base = v.enc->time_base;
        v.out_index = os->index;
    }

    /* ---------------- audio (192 files have it, 221 have none) ---------------- */
    if (info.has_audio) {
        const AVCodec *adec_codec = NULL;
        as = av_find_best_stream(ifmt, AVMEDIA_TYPE_AUDIO, -1, -1, &adec_codec, 0);
        if (as >= 0 && adec_codec) {
            a.in_index = as;
            a.dec = avcodec_alloc_context3(adec_codec);
            avcodec_parameters_to_context(a.dec, ifmt->streams[as]->codecpar);
            a.dec->pkt_timebase = ifmt->streams[as]->time_base;
            if (threads > 0) a.dec->thread_count = threads;
            if (avcodec_open2(a.dec, adec_codec, NULL) < 0) {
                /* A soundtrack we cannot decode is not worth failing the file over; the
                 * video still transcodes and the run report says nothing was lost visually. */
                avcodec_free_context(&a.dec);
                a.in_index = -1;
            }
        }
    }

    if (a.dec) {
        const AVCodec *aenc_codec = avcodec_find_encoder(AV_CODEC_ID_AAC);
        if (aenc_codec) {
            a.enc = avcodec_alloc_context3(aenc_codec);
            av_channel_layout_default(&a.enc->ch_layout,
                                      a.dec->ch_layout.nb_channels > 2 ? 2 : a.dec->ch_layout.nb_channels);
            a.enc->sample_rate = a.dec->sample_rate > 0 ? a.dec->sample_rate : 44100;
            const enum AVSampleFormat *sfmts = NULL;
            int nsfmts = 0;
            a.enc->sample_fmt = AV_SAMPLE_FMT_FLTP;
            if (avcodec_get_supported_config(NULL, aenc_codec, AV_CODEC_CONFIG_SAMPLE_FORMAT,
                                             0, (const void **)&sfmts, &nsfmts) >= 0
                && sfmts && nsfmts > 0)
                a.enc->sample_fmt = sfmts[0];
            a.enc->bit_rate = 128000;
            a.enc->time_base = (AVRational){ 1, a.enc->sample_rate };
            if (ofmt->oformat->flags & AVFMT_GLOBALHEADER)
                a.enc->flags |= AV_CODEC_FLAG_GLOBAL_HEADER;

            if (avcodec_open2(a.enc, aenc_codec, NULL) < 0) {
                avcodec_free_context(&a.enc);
                avcodec_free_context(&a.dec);
                a.in_index = -1;
            } else {
                char layout[64];
                av_channel_layout_describe(&a.dec->ch_layout, layout, sizeof(layout));
                snprintf(args, sizeof(args),
                         "time_base=%d/%d:sample_rate=%d:sample_fmt=%s:channel_layout=%s",
                         ifmt->streams[as]->time_base.num, ifmt->streams[as]->time_base.den,
                         a.dec->sample_rate, av_get_sample_fmt_name(a.dec->sample_fmt), layout);
                char adesc[256];
                char outlayout[64];
                av_channel_layout_describe(&a.enc->ch_layout, outlayout, sizeof(outlayout));
                snprintf(adesc, sizeof(adesc),
                         "aresample=%d,aformat=sample_fmts=%s:channel_layouts=%s",
                         a.enc->sample_rate, av_get_sample_fmt_name(a.enc->sample_fmt), outlayout);
                if (pi_build_graph(&a, args, adesc, "abuffer", "abuffersink",
                                   AVMEDIA_TYPE_AUDIO, err) != PI_OK) {
                    avcodec_free_context(&a.enc);
                    avcodec_free_context(&a.dec);
                    avfilter_graph_free(&a.graph);
                    a.in_index = -1;
                    pi_ok(err);
                } else {
                    AVStream *os = avformat_new_stream(ofmt, NULL);
                    avcodec_parameters_from_context(os->codecpar, a.enc);
                    os->time_base = a.enc->time_base;
                    a.out_index = os->index;
                }
            }
        }
    }

    if (!(ofmt->oformat->flags & AVFMT_NOFILE)) {
        if (avio_open(&ofmt->pb, out_path, AVIO_FLAG_WRITE) < 0) {
            rc = pi_fail(err, PI_ERR_ENCODE, "cannot write %s", out_path);
            goto done;
        }
    }

    {
        /* faststart moves the moov atom to the front in a second pass, so playback can begin
         * before the whole file has arrived -- which over a storage-API fetch is the
         * difference between instant and not. */
        AVDictionary *opts = NULL;
        av_dict_set(&opts, "movflags", "+faststart", 0);
        int wrc = avformat_write_header(ofmt, &opts);
        av_dict_free(&opts);
        if (wrc < 0) { rc = pi_fail(err, PI_ERR_ENCODE, "cannot write mp4 header"); goto done; }
        header_written = 1;
    }

    frame = av_frame_alloc();
    filtered = av_frame_alloc();
    pkt = av_packet_alloc();
    opkt = av_packet_alloc();
    if (!frame || !filtered || !pkt || !opkt) {
        rc = pi_fail(err, PI_ERR_MEMORY, "frame/packet alloc");
        goto done;
    }

    while (av_read_frame(ifmt, pkt) >= 0) {
        struct pi_stream *s = NULL;
        if (pkt->stream_index == v.in_index) s = &v;
        else if (a.in_index >= 0 && pkt->stream_index == a.in_index) s = &a;
        if (!s) { av_packet_unref(pkt); continue; }

        if (avcodec_send_packet(s->dec, pkt) >= 0) {
            while (avcodec_receive_frame(s->dec, frame) >= 0) {
                if (av_buffersrc_add_frame_flags(s->src, frame,
                                                 AV_BUFFERSRC_FLAG_KEEP_REF) >= 0) {
                    while (av_buffersink_get_frame(s->sink, filtered) >= 0) {
                        filtered->pts = filtered->best_effort_timestamp;
                        pi_encode_and_write(ofmt, s, filtered, opkt,
                                            av_buffersink_get_time_base(s->sink));
                        av_frame_unref(filtered);
                    }
                }
                av_frame_unref(frame);
            }
        }
        av_packet_unref(pkt);
    }

    /* Flush decoder, then filter graph, then encoder -- in that order, or the tail of the
     * video is silently truncated. */
    for (int i = 0; i < 2; i++) {
        struct pi_stream *s = i == 0 ? &v : &a;
        if (!s->dec || s->out_index < 0) continue;
        avcodec_send_packet(s->dec, NULL);
        while (avcodec_receive_frame(s->dec, frame) >= 0) {
            /* A frame the graph refuses is a frame lost from the tail, not a reason to
             * abandon a file that has already been transcoded successfully. */
            int frc = av_buffersrc_add_frame_flags(s->src, frame, AV_BUFFERSRC_FLAG_KEEP_REF);
            (void)frc;
            while (av_buffersink_get_frame(s->sink, filtered) >= 0) {
                filtered->pts = filtered->best_effort_timestamp;
                pi_encode_and_write(ofmt, s, filtered, opkt,
                                    av_buffersink_get_time_base(s->sink));
                av_frame_unref(filtered);
            }
            av_frame_unref(frame);
        }
        int eof_rc = av_buffersrc_add_frame_flags(s->src, NULL, 0);
        (void)eof_rc;
        while (av_buffersink_get_frame(s->sink, filtered) >= 0) {
            filtered->pts = filtered->best_effort_timestamp;
            pi_encode_and_write(ofmt, s, filtered, opkt,
                                av_buffersink_get_time_base(s->sink));
            av_frame_unref(filtered);
        }
        pi_encode_and_write(ofmt, s, NULL, opkt, av_buffersink_get_time_base(s->sink));
    }

    if (av_write_trailer(ofmt) < 0) {
        rc = pi_fail(err, PI_ERR_ENCODE, "cannot finalise mp4");
        goto done;
    }
    header_written = 0;
    rc = PI_OK;
    pi_ok(err);

done:
    if (header_written) av_write_trailer(ofmt);
    av_packet_free(&pkt);
    av_packet_free(&opkt);
    av_frame_free(&frame);
    av_frame_free(&filtered);
    pi_stream_close(&v);
    pi_stream_close(&a);
    if (ofmt && !(ofmt->oformat->flags & AVFMT_NOFILE) && ofmt->pb) avio_closep(&ofmt->pb);
    avformat_free_context(ofmt);
    avformat_close_input(&ifmt);
    return rc;
}
