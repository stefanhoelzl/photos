package net.stho.photos.adapter.linux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.io.files.Path
import net.stho.photos.fixtures.be32
import net.stho.photos.fixtures.withScratchDirectory
import net.stho.photos.fixtures.write
import net.stho.photos.pipeline.MediaFormat

/** A top-level atom: 4-byte big-endian size, then the type. */
private fun atom(type: String, size: Int): ByteArray = be32(size) + type.encodeToByteArray()

private fun sniffing(bytes: ByteArray): MediaFormat =
    withScratchDirectory("sniff") { directory ->
        CImagingProbe().sniff(Path(directory, "candidate").write(bytes).toString())
    }

/** Format detection, for containers the library actually holds. */
class SniffTest {

    @Test
    fun classicQuickTimeIsRecognisedWithoutAnFtypBox() {
        // `ftyp` is an MP4-ism. 23 Nikon Coolpix videos in the real library open with a `pnot`
        // preview atom and no ftyp at all, and were silently dropped as "unknown".
        for (first in listOf("pnot", "moov", "mdat", "wide", "skip", "free")) {
            assertEquals(
                MediaFormat.VIDEO,
                sniffing(atom(first, size = 20) + ByteArray(44)),
                "$first should sniff as video",
            )
        }
    }

    @Test
    fun anFtypBoxStillDecidesHeifByBrand() {
        for (brand in listOf("heic", "mif1")) {
            assertEquals(
                MediaFormat.HEIF,
                sniffing(atom("ftyp", size = 24) + brand.encodeToByteArray() + ByteArray(40)),
                "brand $brand should be HEIF",
            )
        }
        assertEquals(
            MediaFormat.VIDEO,
            sniffing(atom("ftyp", size = 24) + "isom".encodeToByteArray() + ByteArray(40)),
        )
    }

    @Test
    fun aLeadingAtomIsSteppedOverToReachFtyp() {
        // `wide` then `ftyp` — the walk has to advance by the first atom's size.
        val bytes = atom("wide", size = 8) +
            atom("ftyp", size = 24) + "isom".encodeToByteArray() + ByteArray(40)
        assertEquals(MediaFormat.VIDEO, sniffing(bytes))
    }

    @Test
    fun somethingThatIsNotAContainerIsStillUnknown() {
        // The walk must not be so eager that a SQLite database looks like a movie.
        val bytes = ("SQLite format 3" + Char(0)).encodeToByteArray() + ByteArray(64)
        assertEquals(MediaFormat.UNKNOWN, sniffing(bytes))
    }
}
