package net.stho.photos.desktop

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.stho.photos.app.Face
import net.stho.photos.app.FaceCrops
import net.stho.photos.app.LocalLibrary
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface

/**
 * Face crops for the people grids and the sidebar (§12), cut from the originals and kept on disk.
 *
 * The viewer's own cache, `$XDG_CACHE_HOME/photos-viewer/crops/`, and never the CLI's: that
 * directory has one writer, the run holding its lock (§7). A crop is named by its photo and its
 * box — an original never changes (§7), so the same face in the same place is the same crop for
 * good — which is also why nothing here is ever invalidated. The directory can be deleted at any
 * moment and refills as grids are opened.
 */
public class FaceCropStore(
    private val library: LocalLibrary,
    private val originals: OriginalPreviews,
    private val directory: File,
) : FaceCrops {

    override suspend fun crop(face: Face): ByteArray? = withContext(Dispatchers.IO) {
        val file = File(directory, name(face))
        if (file.isFile) return@withContext runCatching { file.readBytes() }.getOrNull()
        val original = library.original(face.photoId) ?: return@withContext null
        val jpeg = cut(File(original.toString()), face) ?: return@withContext null
        directory.mkdirs()
        // Written beside and moved into place, so a grid never reads half a crop.
        val partial = File(directory, "${file.name}.${kotlin.uuid.Uuid.random()}.part")
        runCatching {
            partial.writeBytes(jpeg)
            partial.renameTo(file)
        }
        partial.delete()
        jpeg
    }

    private fun cut(original: File, face: Face): ByteArray? {
        val image = originals.skiaImage(original, DECODE_LONG_EDGE) ?: return null
        image.use {
            val box = face.box
            // Square, centred on the face, with room for hair and chin.
            val side = maxOf(box.width * image.width, box.height * image.height) * MARGIN
            val centreX = (box.x + box.width / 2) * image.width
            val centreY = (box.y + box.height / 2) * image.height
            val source = Rect.makeXYWH(centreX - side / 2, centreY - side / 2, side, side)
            val surface = Surface.makeRasterN32Premul(EDGE, EDGE)
            surface.use {
                surface.canvas.drawImageRect(image, source, Rect.makeWH(EDGE.toFloat(), EDGE.toFloat()))
                return surface.makeImageSnapshot().use { snapshot ->
                    snapshot.encodeToData(EncodedImageFormat.JPEG, QUALITY)?.bytes
                }
            }
        }
    }

    private fun name(face: Face): String {
        val box = face.box
        return "${face.photoId}-%.4f-%.4f-%.4f-%.4f.jpg".format(box.x, box.y, box.width, box.height)
    }

    private companion object {
        /** Enough for a face in a group photograph to come out sharp at [EDGE]. */
        const val DECODE_LONG_EDGE = 2048
        const val EDGE = 192
        const val QUALITY = 85
        const val MARGIN = 1.5f
    }
}
