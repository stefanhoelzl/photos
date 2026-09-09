@file:OptIn(ExperimentalForeignApi::class)

package net.stho.photos.fixtures

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import photosimaging.pi_buffer
import photosimaging.pi_buffer_free
import photosimaging.pi_buffer_init
import photosimaging.pi_encode_heic
import photosimaging.pi_encode_jpeg
import photosimaging.pi_error
import photosimaging.pi_image
import photosimaging.pi_image_alloc
import photosimaging.pi_image_free
import photosimaging.pi_image_init

/**
 * The C shim's error convention, as the fixtures need it.
 *
 * The adapter has its own copy of this, `internal` to that module. This one exists because the
 * fixtures reach the shim directly rather than through `PixelImage`, whose constructor is
 * internal to the adapter -- and opening that constructor would widen the shipped module's API
 * for the benefit of tests.
 */
internal inline fun imagingCall(body: MemScope.(CPointer<pi_error>) -> Int) {
    memScoped {
        val error = alloc<pi_error>()
        val code = body(error.ptr)
        if (code != 0) {
            error("imaging call failed: code=${error.code} ${error.message.toKString()}")
        }
    }
}

/** Runs [body] over a freshly allocated image holding [syntheticPixels], then frees it. */
internal fun <R> withSyntheticImage(
    width: Int,
    height: Int,
    alpha: Boolean = false,
    body: MemScope.(CPointer<pi_image>) -> R,
): R = memScoped {
    val channels = if (alpha) 4 else 3
    val image = alloc<pi_image>()
    pi_image_init(image.ptr)
    try {
        imagingCall { err -> pi_image_alloc(image.ptr, width, height, channels, err) }
        val pixels = image.pixels!!
        val source = syntheticPixels(width, height, channels)
        for (i in source.indices) pixels[i] = source[i].toUByte()
        body(image.ptr)
    } finally {
        pi_image_free(image.ptr)
    }
}

/** Copies an encoder's output buffer out and frees it. */
private fun MemScope.encoded(fill: (CPointer<pi_buffer>) -> Unit): ByteArray {
    val buffer = alloc<pi_buffer>()
    pi_buffer_init(buffer.ptr)
    try {
        fill(buffer.ptr)
        val bytes = buffer.bytes!!
        return ByteArray(buffer.len.toInt()) { bytes[it].toByte() }
    } finally {
        pi_buffer_free(buffer.ptr)
    }
}

/** A JPEG, via the pipeline's own encoder. */
public fun syntheticJpeg(width: Int, height: Int, quality: Int = 90): ByteArray =
    withSyntheticImage(width, height) { image ->
        encoded { out -> imagingCall { err -> pi_encode_jpeg(image, quality, 1, out, err) } }
    }

/** A HEIC, via the pipeline's own encoder. */
public fun syntheticHeic(width: Int, height: Int, quality: Int = 70): ByteArray =
    withSyntheticImage(width, height) { image ->
        encoded { out -> imagingCall { err -> pi_encode_heic(image, quality, 1, out, err) } }
    }
