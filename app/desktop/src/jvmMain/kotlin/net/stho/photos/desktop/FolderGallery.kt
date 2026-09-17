package net.stho.photos.desktop

import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import net.stho.photos.adapter.linux.FfmImaging
import net.stho.photos.app.ExportedAsset
import net.stho.photos.app.Gallery
import net.stho.photos.app.GalleryAccess
import net.stho.photos.app.GalleryAlbum
import net.stho.photos.app.GalleryAsset
import net.stho.photos.model.MediaType

/**
 * The phone's photo library, stood in for by a directory (§8) — so upload is built, driven and
 * tested on the harness rather than only on a device.
 *
 * Each subdirectory is a gallery album and each file an asset. A still and a video sharing a stem
 * are a Live Photo, which is how an iPhone's export names the pair. What the phone reads from
 * PhotoKit's record comes from the file here: the date is its modification time and there is no
 * location. Both are provisional on the phone too, since the laptop re-derives every row from
 * EXIF when it encodes the album.
 */
internal class FolderGallery(private val root: File, private val imaging: FfmImaging?) : Gallery {

    override suspend fun requestAccess(): GalleryAccess =
        if (root.isDirectory) GalleryAccess.Full else GalleryAccess.Denied

    override suspend fun albums(): List<GalleryAlbum> =
        folders().map { GalleryAlbum(it.name, it.name, entries(it).size) }

    /**
     * Newest first (§8), which here means name descending: a directory has no date of its own
     * worth trusting — a modification time is whatever last copied the file — and the camera's
     * names already run in the order it shot them.
     */
    override suspend fun assets(album: GalleryAlbum?): List<GalleryAsset> =
        (if (album == null) folders() else listOf(File(root, album.id))).flatMap(::entries).reversed()

    override suspend fun asset(id: String): GalleryAsset? {
        val folder = File(root, id).parentFile ?: return null
        return entries(folder).firstOrNull { it.id == id }
    }

    override suspend fun thumbnail(asset: GalleryAsset): ByteArray? = withContext(Dispatchers.IO) {
        runCatching { thumbnailOf(File(root, asset.id)) }.getOrNull()
    }

    override suspend fun export(asset: GalleryAsset, directory: Path): ExportedAsset = withContext(Dispatchers.IO) {
        val source = File(root, asset.id)
        val into = File(directory.toString()).apply { mkdirs() }
        // A fresh name per copy, keeping the extension: that is what the row's filename is built on.
        fun copy(file: File): Path = Path(file.copyTo(File(into, "${UUID.randomUUID()}.${file.extension}")).path)
        val pair = if (asset.mediaType == MediaType.LIVE_PHOTO) pairOf(source) else null
        ExportedAsset(
            mediaType = asset.mediaType,
            file = copy(source),
            pairedVideo = pair?.let(::copy),
            takenAt = Instant.fromEpochMilliseconds(source.lastModified()),
            thumbnail = thumbnailOf(source),
        )
    }

    /**
     * No confirmation to show here: the name dialog's checkbox was the one this stand-in has.
     *
     * The album's folder goes once nothing is left in it — PhotoKit's rule, checked afterwards here
     * because nothing can refuse in between.
     */
    override suspend fun delete(ids: List<String>, album: String?): Boolean = withContext(Dispatchers.IO) {
        for (id in ids) {
            val file = File(root, id)
            pairOf(file)?.delete()
            file.delete()
        }
        album?.let { File(root, it) }?.takeIf { it.isDirectory && it.list().isNullOrEmpty() }?.delete()
        true
    }

    override fun openSettings() = Unit

    private fun folders(): List<File> =
        root.listFiles().orEmpty().filter(File::isDirectory).sortedBy(File::getName)

    private fun entries(folder: File): List<GalleryAsset> {
        val files = folder.listFiles().orEmpty().filter(File::isFile).sortedBy(File::getName)
        val paired = files.filter(::isStill).mapNotNull(::pairOf).toSet()
        return files.mapNotNull { file ->
            val id = file.relativeTo(root).path
            when {
                isStill(file) ->
                    GalleryAsset(id, file.name, if (pairOf(file) != null) MediaType.LIVE_PHOTO else MediaType.PHOTO)
                isVideo(file) && file !in paired -> GalleryAsset(id, file.name, MediaType.VIDEO)
                else -> null
            }
        }
    }

    private fun pairOf(still: File): File? {
        if (!isStill(still)) return null
        return still.parentFile?.listFiles().orEmpty()
            .firstOrNull { it.isFile && isVideo(it) && it.nameWithoutExtension == still.nameWithoutExtension }
    }

    private fun isStill(file: File) = file.extension.lowercase() in STILLS

    private fun isVideo(file: File) = file.extension.lowercase() in VIDEOS

    /**
     * §5's thumbnail: a 256px square centre crop, JPEG q75. Grey where nothing here can decode the
     * file — a video, or a harness started without the decode shim, as the suite is.
     */
    private fun thumbnailOf(file: File): ByteArray {
        val decoded = if (isStill(file)) imaging?.decodeFile(file.path, maxLongEdge = 2 * SIDE) else null
        val source = decoded?.toImage() ?: BufferedImage(SIDE, SIDE, BufferedImage.TYPE_INT_RGB).apply {
            createGraphics().run {
                color = Color(0x8B96A5)
                fillRect(0, 0, SIDE, SIDE)
                dispose()
            }
        }
        val side = minOf(source.width, source.height)
        val square = source.getSubimage((source.width - side) / 2, (source.height - side) / 2, side, side)
        val thumbnail = BufferedImage(SIDE, SIDE, BufferedImage.TYPE_INT_RGB)
        thumbnail.createGraphics().run {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(square, 0, 0, SIDE, SIDE, null)
            dispose()
        }
        val writer = ImageIO.getImageWritersByFormatName("jpg").next()
        return ByteArrayOutputStream().use { out ->
            ImageIO.createImageOutputStream(out).use { stream ->
                writer.output = stream
                val quality = writer.defaultWriteParam.apply {
                    compressionMode = ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = 0.75f
                }
                writer.write(null, IIOImage(thumbnail, null, null), quality)
                writer.dispose()
            }
            out.toByteArray()
        }
    }

    private fun FfmImaging.Decoded.toImage(): BufferedImage {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        var offset = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val red = pixels[offset].toInt() and 0xFF
                val green = pixels[offset + 1].toInt() and 0xFF
                val blue = pixels[offset + 2].toInt() and 0xFF
                image.setRGB(x, y, (red shl 16) or (green shl 8) or blue)
                offset += channels
            }
        }
        return image
    }

    private companion object {
        const val SIDE = 256
        val STILLS = setOf("heic", "heif", "jpg", "jpeg", "png")
        val VIDEOS = setOf("mov", "mp4", "m4v")
    }
}
