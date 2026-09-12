package net.stho.photos.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.io.files.Path
import net.stho.photos.adapter.linux.FfmImaging
import net.stho.photos.app.PreviewDecoder
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

/**
 * libheif through the FFM binding, with Skia behind it.
 *
 * The order matters: the shim is the same C the pipeline used to *write* these previews, so
 * asking it first means the app and ingest cannot disagree about what a photograph looks like.
 * Skia catches whatever the shim will not take, which in practice is nothing the zone holds —
 * it is there so that a preview tier that ever changed format still renders.
 */
public class ShimPreviewDecoder(private val imaging: FfmImaging) : PreviewDecoder {
    override fun decode(file: Path): ImageBitmap? {
        imaging.decodeFile(file.toString())?.let { return it.toImageBitmap() }
        // Skia only if the shim would not take it -- and from bytes, since that is its API.
        val bytes = runCatching { java.io.File(file.toString()).readBytes() }.getOrNull() ?: return null
        return runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
    }

    /**
     * C pixels to a Skia bitmap.
     *
     * The shim hands back interleaved RGB or RGBA, top-down; Skia wants a colour type named up
     * front, so the channel count picks one rather than being assumed.
     */
    private fun FfmImaging.Decoded.toImageBitmap(): ImageBitmap {
        val info = ImageInfo(
            width = width,
            height = height,
            colorType = if (channels == 4) ColorType.RGBA_8888 else ColorType.RGB_888X,
            alphaType = ColorAlphaType.UNPREMUL,
        )
        val rgba = if (channels == 4) pixels else pixels.toRgbx()
        return Image.makeRaster(info, rgba, width * 4).toComposeImageBitmap()
    }

    /** Skia has no 24-bit raster type, so three-channel pixels get an opaque fourth. */
    private fun ByteArray.toRgbx(): ByteArray {
        val out = ByteArray(size / 3 * 4)
        var source = 0
        var target = 0
        while (source < size) {
            out[target] = this[source]
            out[target + 1] = this[source + 1]
            out[target + 2] = this[source + 2]
            out[target + 3] = 0xFF.toByte()
            source += 3
            target += 4
        }
        return out
    }
}
