@file:OptIn(ExperimentalForeignApi::class)

package net.stho.photos.adapter.linux

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import net.stho.photos.fixtures.syntheticPixels
import photosimaging.pi_image_alloc

/**
 * [syntheticPixels] as a [PixelImage], for the tests that compare buffers rather than files.
 *
 * The only fixture that cannot live in `:tests:fixtures`: `PixelImage`'s constructor is internal
 * to this module, and opening it would widen the shipped module's API for the benefit of tests.
 * The pattern itself is shared, so a fixture here and a fixture there are the same image.
 */
internal fun syntheticImage(width: Int, height: Int, alpha: Boolean = false): PixelImage {
    val channels = if (alpha) 4 else 3
    val image = PixelImage()
    try {
        imagingCall { err -> pi_image_alloc(image.raw.ptr, width, height, channels, err) }
    } catch (failure: Throwable) {
        image.close()
        throw failure
    }
    val pixels = image.pixels!!
    val source = syntheticPixels(width, height, channels)
    for (i in source.indices) pixels[i] = source[i].toUByte()
    return image
}
