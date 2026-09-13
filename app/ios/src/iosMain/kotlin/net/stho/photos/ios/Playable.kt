@file:OptIn(ExperimentalForeignApi::class)

package net.stho.photos.ios

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import platform.Foundation.NSFileManager

/**
 * A blob, under a name the platform's media stack will open.
 *
 * §2 names every blob by its content hash and nothing else, so `blobs/<id>` carries no
 * extension — and AVFoundation and PhotoKit both decide what a file *is* from its extension
 * before they read a byte. So playback goes through a symbolic link beside the cache, named
 * `<id>.<ext>`, which costs no copy and no second policy: clearing an album removes the blob,
 * and a link left pointing at nothing is simply recreated the next time that blob lands.
 *
 * The extension is read from the file's first bytes rather than assumed. A video is always §5's
 * MP4 transcode; a Live Photo's still is the *untouched source*, which may be HEIC or JPEG
 * depending on the camera that took it.
 */
internal object Playable {

    fun video(blob: String): String = link(blob, "mp4")

    fun liveVideo(blob: String): String = link(blob, "mov")

    fun liveStill(blob: String): String = link(blob, if (isJpeg(blob)) "jpg" else "heic")

    private fun link(blob: String, extension: String): String {
        val source = Path(blob)
        val directory = Path(requireNotNull(source.parent?.parent) { "not a cached blob: $blob" }, "playable")
        SystemFileSystem.createDirectories(directory)
        val target = Path(directory, "${source.name}.$extension").toString()
        val manager = NSFileManager.defaultManager
        // Recreated whenever it is missing or dangling; an existing, valid link is left alone.
        if (!manager.fileExistsAtPath(target)) {
            manager.removeItemAtPath(target, error = null)
            manager.createSymbolicLinkAtPath(target, withDestinationPath = blob, error = null)
        }
        return target
    }

    /** JPEG begins `FF D8 FF`; anything else §5 keeps as a still is HEIC. */
    private fun isJpeg(blob: String): Boolean = runCatching {
        SystemFileSystem.source(Path(blob)).buffered().use { it.readByteArray(3) }
    }.getOrNull()?.let { it.size == 3 && it[0] == 0xFF.toByte() && it[1] == 0xD8.toByte() && it[2] == 0xFF.toByte() } == true
}
