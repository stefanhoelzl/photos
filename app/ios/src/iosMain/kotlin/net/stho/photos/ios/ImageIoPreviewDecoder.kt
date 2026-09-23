@file:OptIn(ExperimentalForeignApi::class)

package net.stho.photos.ios

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.io.files.Path
import net.stho.photos.app.PreviewDecoder
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.kCGBitmapByteOrder32Big
import platform.UIKit.UIImage

/**
 * §5's viewing tier, decoded by the platform.
 *
 * HEIC is why this port exists at all (`:app:harness` satisfies it with libheif behind the FFM
 * shim). On iOS there is nothing to link: the system reads HEIC, JPEG and PNG alike, through
 * the same hardware decoder Photos uses — which is a large part of why §5 chose the format.
 *
 * **`UIImage` rather than `CGImageSource` directly**, which is the shorter of two correct
 * routes: ImageIO's entry points take Core Foundation types, so reaching them from Kotlin means
 * `CFBridgingRetain`, a pointer reinterpret and a matching release, to arrive at the decoder
 * `UIImage` was already going to call.
 *
 * The pixels then have to reach Skia, because Compose draws them rather than UIKit. That is one
 * copy through a `CGBitmapContext` — the only way to get a known layout out of a `CGImage`,
 * whose own backing store may be anything from a planar YUV buffer to a compressed surface.
 *
 * Orientation is deliberately not applied. §5 writes these blobs already upright, and `CGImage`
 * is the unrotated bitmap, so a second rotation here would be a bug rather than a courtesy.
 */
public class ImageIoPreviewDecoder : PreviewDecoder {

    override fun decode(file: Path): ImageBitmap? {
        val image = UIImage.imageWithContentsOfFile(file.toString())?.CGImage ?: return null

        val width = CGImageGetWidth(image).toInt()
        val height = CGImageGetHeight(image).toInt()
        if (width <= 0 || height <= 0) return null

        val pixels = ByteArray(width * height * BYTES_PER_PIXEL)
        val drawn = pixels.usePinned { pinned ->
            val space = CGColorSpaceCreateDeviceRGB() ?: return@usePinned false
            val context = CGBitmapContextCreate(
                data = pinned.addressOf(0),
                width = width.toULong(),
                height = height.toULong(),
                bitsPerComponent = 8u,
                bytesPerRow = (width * BYTES_PER_PIXEL).toULong(),
                space = space,
                // RGBA in big-endian byte order: the byte layout Skia's RGBA_8888 expects, so
                // the buffer needs no swizzle afterwards.
                bitmapInfo = CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value or
                    kCGBitmapByteOrder32Big,
            ) ?: return@usePinned false
            CGContextDrawImage(
                context,
                CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()),
                image,
            )
            true
        }
        if (!drawn) return null

        val info = ImageInfo(
            width = width,
            height = height,
            colorType = ColorType.RGBA_8888,
            // Premultiplied, matching the bitmap info above. Claiming UNPREMUL would tell Skia
            // to divide out an alpha that was never multiplied in, which shows up as a
            // washed-out picture rather than as an error.
            alphaType = ColorAlphaType.PREMUL,
        )
        return Image.makeRaster(info, pixels, width * BYTES_PER_PIXEL).toComposeImageBitmap()
    }

    private companion object {
        const val BYTES_PER_PIXEL = 4
    }
}
