package net.stho.photos.app

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import net.stho.photos.catalog.ObjectId

/**
 * `blobs/`, where each media blob lives as `<id>.<ext>`.
 *
 * §2 names a blob by its hash and nothing else, but AVFoundation and PhotoKit decide what a file
 * *is* from its extension before they read a byte, so on disk a blob carries one. Measured on a
 * phone: `PHLivePhoto` refuses a pair under bare hashes outright (`PHPhotosErrorDomain` 3303),
 * and also refuses the same bytes reached through symbolic links named `<id>.heic`/`<id>.mov` —
 * though a simulator accepts the links — while the same bytes as real files play. So the players
 * are handed the blob itself.
 *
 * The extension is read from the file's first bytes when it lands rather than assumed: a Live
 * Photo's still is the camera's own file, HEIC or JPEG, and its MOV must stay a `.mov` while a
 * transcode is an `.mp4`. The id is everything before the first dot, so [present] and every
 * lookup still speak in ids.
 */
internal class MediaFiles(cacheRoot: Path) {

    val directory: Path = Path(cacheRoot, "blobs")

    init {
        SystemFileSystem.createDirectories(directory)
    }

    /**
     * The blob's file, or null when it is not on disk.
     *
     * A blob cached before blobs carried an extension is renamed in place the first time it is
     * looked up — a rename, never a copy or a download — so an existing cache keeps working.
     */
    fun find(id: ObjectId): Path? {
        for (extension in EXTENSIONS) {
            val path = Path(directory, "$id.$extension")
            if (SystemFileSystem.exists(path)) return path
        }
        val legacy = Path(directory, id.toString())
        if (!SystemFileSystem.exists(legacy)) return null
        val named = named(id, legacy)
        try {
            SystemFileSystem.atomicMove(legacy, named)
        } catch (failure: kotlinx.io.IOException) {
            // Another lookup renamed it first; losing that race is not a failure.
            if (!SystemFileSystem.exists(named)) throw failure
        }
        return named
    }

    /** Where the bytes in [file], which are blob [id], belong. */
    fun named(id: ObjectId, file: Path): Path = Path(directory, "$id.${extensionOf(file)}")

    fun delete(id: ObjectId) {
        SystemFileSystem.delete(Path(directory, id.toString()), mustExist = false)
        for (extension in EXTENSIONS) SystemFileSystem.delete(Path(directory, "$id.$extension"), mustExist = false)
    }

    /** The id a directory entry names, or null for anything else. A fetch in flight is not held. */
    fun idOf(name: String): ObjectId? =
        if (name.endsWith(".part")) null else ObjectId.parse(name.substringBefore('.'))

    private fun extensionOf(file: Path): String {
        val size = SystemFileSystem.metadataOrNull(file)?.size ?: 0L
        return extensionOf(SystemFileSystem.source(file).buffered().use { it.readByteArray(minOf(size, 12L).toInt()) })
    }

    companion object {
        /** Every extension [extensionOf] can answer, so a lookup is a bounded handful of `stat`s. */
        val EXTENSIONS: List<String> = listOf("heic", "jpg", "png", "mov", "mp4", "bin")

        /**
         * JPEG and PNG by signature; an ISO base media file by its `ftyp` brand — `qt  ` is a
         * QuickTime MOV, the HEIF brands are a still, anything else is an MP4. What none of these
         * describe is `bin`: still decodable by content, just named for nothing.
         */
        fun extensionOf(head: ByteArray): String {
            fun at(index: Int) = head.getOrNull(index)?.toInt()?.and(0xFF)
            if (at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF) return "jpg"
            if (at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47) return "png"
            if (head.size >= 12 && head.decodeToString(4, 8) == "ftyp") {
                return when (head.decodeToString(8, 12)) {
                    "qt  " -> "mov"
                    in HEIF_BRANDS -> "heic"
                    "avif", "avis" -> "bin"
                    else -> "mp4"
                }
            }
            return "bin"
        }

        private val HEIF_BRANDS = setOf("heic", "heix", "heim", "heis", "hevc", "hevx", "hevm", "hevs", "mif1", "msf1")
    }
}
