package net.stho.photos.e2e

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.readByteArray
import kotlinx.io.files.SystemFileSystem
import net.stho.photos.fixtures.deleteTree
import net.stho.photos.fixtures.syntheticJpeg
import net.stho.photos.fixtures.syntheticOrientedJpeg
import net.stho.photos.fixtures.write
import net.stho.photos.fixtures.writeSyntheticHeic
import net.stho.photos.fixtures.writeSyntheticVideo

/**
 * The library tree a scenario declares.
 *
 * Sources are small on purpose. `DerivativeSpec.PREVIEW_UPSCALES` is false, so a 320x240 source
 * yields a 320x240 preview and x265 stays cheap; the shrink-on-load bound that a big source
 * would exercise is a unit-test concern, proven in `DerivativeTest` without a network.
 */
internal class LibraryBuilder(private val root: Path) {

    /** The marker that says this directory really is a library (§7). Absent means no run. */
    fun photosignore(vararg patterns: String) {
        Path(root, ".photosignore").write(patterns.joinToString("\n").encodeToByteArray())
    }

    fun album(path: String, body: AlbumBuilder.() -> Unit) {
        val directory = resolve(path)
        SystemFileSystem.createDirectories(directory)
        AlbumBuilder(directory).body()
    }

    /** An album directory that exists and holds no photos -- which must not delete it (§7). */
    fun emptyAlbum(path: String) {
        SystemFileSystem.createDirectories(resolve(path))
    }

    /** One file gone: its photo should leave the zone, and nothing else should. */
    fun remove(path: String) {
        SystemFileSystem.delete(resolve(path), mustExist = true)
    }

    /** `rm -rf` on an album: the album itself should leave the zone. */
    fun removeTree(path: String) {
        deleteTree(resolve(path))
    }

    private fun resolve(path: String): Path =
        path.split('/').filter(String::isNotEmpty).fold(root) { at, part -> Path(at, part) }
}

internal class AlbumBuilder(private val directory: Path) {

    /** A real photograph from the build's fixtures — a NASA portrait, for §12's faces. */
    fun portrait(name: String, photo: String) {
        SystemFileSystem.source(Path(faceFixture(photo))).buffered().use { source ->
            Path(directory, name).write(source.readByteArray())
        }
    }

    fun jpeg(name: String, width: Int = 320, height: Int = 240, orientation: Int? = null) {
        val bytes = if (orientation == null) {
            syntheticJpeg(width, height)
        } else {
            syntheticOrientedJpeg(width, height, orientation)
        }
        Path(directory, name).write(bytes)
    }

    fun heic(name: String, width: Int = 320, height: Int = 240) {
        writeSyntheticHeic(Path(directory, name), width, height)
    }

    fun video(name: String, width: Int = 64, height: Int = 48, frames: Int = 10) {
        writeSyntheticVideo(Path(directory, name), width, height, frames)
    }

    /**
     * A Live Photo: a HEIC and the MOV that pairs with it, both carrying the same Apple
     * `content.identifier`.
     *
     * Two files, and §3 turns them into one row — which is what makes this the only shape in the
     * library where a file on disk is not named by any row. [stem] gets `.HEIC` and `.mov`, the
     * spelling every pair in the real library uses.
     */
    fun livePhoto(
        stem: String,
        identifier: String,
        width: Int = 320,
        height: Int = 240,
    ) {
        writeSyntheticHeic(Path(directory, "$stem.HEIC"), width, height, contentIdentifier = identifier)
        writeSyntheticVideo(Path(directory, "$stem.mov"), contentIdentifier = identifier)
    }
}
