@file:OptIn(ExperimentalForeignApi::class)

package net.stho.photos.smoke

import kotlinx.cinterop.*
import photosimaging.*
import platform.posix.*

/**
 * Drives the whole native stack once, from Kotlin, and fails loudly if any of it is wrong.
 *
 * This is not a test: DESIGN §7 ships one binary, and this is the only thing that exercises
 * the configuration that actually ships -- the same cinterop, the same static archives, the
 * same link. A test harness would prove something adjacent to it.
 */
fun main() {
    memScoped {
        val err = alloc<pi_error>()
        val out = "/tmp/photos-native-smoke"
        mkdir(out, 0x1FFu)

        fun check(what: String, rc: Int) {
            if (rc != 0) {
                println("FAIL $what: code=${err.code} ${err.message.toKString()}")
                exit(1)
            }
        }

        // A synthetic 12 MP source: enough pixels that shrink-on-load and the resampler are
        // doing real work rather than rounding.
        val src = alloc<pi_image>()
        pi_image_init(src.ptr)
        check("pi_image_alloc", pi_image_alloc(src.ptr, 4000, 3000, 3, err.ptr))
        val px = src.pixels!!
        for (y in 0 until 3000) {
            val row = y * 4000 * 3
            for (x in 0 until 4000) {
                val i = row + x * 3
                px[i] = (x and 0xFF).toUByte()
                px[i + 1] = (y and 0xFF).toUByte()
                px[i + 2] = ((x + y) and 0xFF).toUByte()
            }
        }
        println("source      ${src.width}x${src.height} c=${src.channels}")

        // §5's thumbnail tier: square centre crop, sRGB, JPEG q75.
        val thumb = alloc<pi_image>(); pi_image_init(thumb.ptr)
        check("resize_square_crop", pi_resize_square_crop(src.ptr, 256, thumb.ptr, err.ptr))
        check("convert_to_srgb", pi_convert_to_srgb(thumb.ptr, err.ptr))
        val jpeg = alloc<pi_buffer>(); pi_buffer_init(jpeg.ptr)
        check("encode_jpeg", pi_encode_jpeg(thumb.ptr, 75, 1, jpeg.ptr, err.ptr))
        write("$out/thumb.jpg", jpeg)
        println("thumbnail   ${thumb.width}x${thumb.height}  ${jpeg.len} bytes  [libjpeg-turbo + lcms2 + swscale]")

        // §5's preview tier: 2048px long edge, HEIC q50.
        val preview = alloc<pi_image>(); pi_image_init(preview.ptr)
        check("resize_fit", pi_resize_fit(src.ptr, 2048, 0, preview.ptr, err.ptr))
        val heic = alloc<pi_buffer>(); pi_buffer_init(heic.ptr)
        check("encode_heic", pi_encode_heic(preview.ptr, 50, 1, heic.ptr, err.ptr))
        write("$out/preview.heic", heic)
        println("preview     ${preview.width}x${preview.height}  ${heic.len} bytes  [libheif + x265]")

        // The video path: ffmpeg's muxer plus the HEVC encoder.
        check("fixture_write_video", pi_fixture_write_video("$out/clip.mp4", 640, 480, 30, 0, null, err.ptr))
        println("video       640x480x30f  [ffmpeg mux + x265]")

        // Sniffing reads a bounded header prefix, so it must agree with what we just wrote.
        expect("sniff jpg", pi_sniff("$out/thumb.jpg"), PI_FORMAT_JPEG)
        expect("sniff heic", pi_sniff("$out/preview.heic"), PI_FORMAT_HEIF)
        expect("sniff mp4", pi_sniff("$out/clip.mp4"), PI_FORMAT_VIDEO)

        pi_buffer_free(jpeg.ptr); pi_buffer_free(heic.ptr)
        pi_image_free(thumb.ptr); pi_image_free(preview.ptr); pi_image_free(src.ptr)
        println("\nOK -- the shipped native configuration works end to end.")
    }
}

private fun write(path: String, buf: pi_buffer) {
    val f = fopen(path, "wb") ?: run { println("FAIL fopen $path"); exit(1); error("unreachable") }
    fwrite(buf.bytes, 1uL, buf.len, f)
    fclose(f)
}

private fun expect(what: String, got: pi_format, want: pi_format) {
    if (got != want) {
        println("FAIL $what: got $got, wanted $want")
        exit(1)
    }
    println("$what  ok")
}
