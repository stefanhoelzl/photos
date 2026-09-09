package net.stho.photos.adapter.linux

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readByteArray
import kotlinx.io.write
import net.stho.photos.exif.Dimensions

/**
 * Reads an encoded image's dimensions back.
 *
 * Exists because verification needs it: the acceptance scan reports what the pipeline actually
 * produced, and "the thumbnail is 256×256" is only worth asserting if it is measured from the
 * encoded bytes rather than from the intent that produced them.
 */
public fun imageDimensions(path: String): Dimensions =
    // maxLongEdge 1 asks for the smallest scale libjpeg offers, and `sourceWidth` still reports
    // the photograph rather than the buffer — so reading the dimensions of a 164 MP panorama
    // costs a 1/8 decode instead of 494 MB. Formats without shrink-on-load decode fully
    // regardless; there is nothing cheaper to ask them for.
    PixelImage.decode(path, maxLongEdge = 1).use { Dimensions(it.sourceWidth, it.sourceHeight) }

/**
 * The same, for bytes that are not on disk yet.
 *
 * Via a temporary file rather than `pi_decode_memory`, which is JPEG-only — and half of what
 * gets measured here is HEIC.
 */
@OptIn(ExperimentalUuidApi::class)
public fun ByteArray.imageDimensions(): Dimensions {
    val path = Path(SystemTemporaryDirectory, "photos-probe-${Uuid.random()}")
    try {
        SystemFileSystem.sink(path).buffered().use { it.write(this) }
        return imageDimensions(path.toString())
    } finally {
        SystemFileSystem.delete(path, mustExist = false)
    }
}

/** Reads a whole file. Used where the alternative is a second seek-and-read layer for no gain. */
internal fun readAllBytes(path: String): ByteArray =
    SystemFileSystem.source(Path(path)).buffered().use { it.readByteArray() }
