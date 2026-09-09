@file:OptIn(ExperimentalUuidApi::class)

package net.stho.photos.e2e

import net.stho.photos.adapter.linux.NativeSqlDrivers
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import net.stho.photos.adapter.linux.CImagingProbe
import net.stho.photos.adapter.linux.imageDimensions
import net.stho.photos.catalog.AlbumState
import net.stho.photos.catalog.Shard
import net.stho.photos.catalog.ThumbPack
import net.stho.photos.catalog.blobKey
import net.stho.photos.derivative.DerivativeSpec
import net.stho.photos.fixtures.readBytes
import net.stho.photos.fixtures.write
import net.stho.photos.model.MediaType
import net.stho.photos.model.PhotoRow
import net.stho.photos.pipeline.MediaFormat
import net.stho.photos.storage.GetResult
import net.stho.photos.storage.S3Client

/** The state a scenario says should hold after its last run. */
internal class Expectations {
    private var exitCode: Int? = null
    private var libraryClaims: (LibraryExpectations.() -> Unit)? = null
    private var zoneClaims: (ZoneExpectations.() -> Unit)? = null

    fun exit(code: Int) {
        exitCode = code
    }

    fun library(body: LibraryExpectations.() -> Unit) {
        libraryClaims = body
    }

    fun zone(body: ZoneExpectations.() -> Unit) {
        zoneClaims = body
    }

    internal suspend fun check(scenario: Scenario, run: Run) {
        exitCode?.let { expected ->
            if (run.exitCode != expected) {
                throw ExitWrong(scenario.label, run.command, expected, run.exitCode, run.output)
            }
        }
        libraryClaims?.let { claims ->
            val library = LibraryExpectations(scenario.libraryRoot)
            library.claims()
            library.check(scenario.label)
        }
        zoneClaims?.let { claims ->
            val zone = ZoneExpectations()
            zone.claims()
            zone.check(scenario)
        }
    }
}

// ---------------------------------------------------------------------------------- library
//
// Open-world: a claim names one path and says what should be true of it. The library is the one
// thing here a person edits, so enumerating it would assert mostly noise.

internal class LibraryExpectations(private val root: Path) {
    private val failures = mutableListOf<String>()

    fun exists(path: String) {
        if (metadata(path) == null) failures += "$path is missing; it should exist"
    }

    fun absent(path: String) {
        if (metadata(path) != null) failures += "$path exists; it should be gone"
    }

    /** Exists, and holds exactly these bytes -- for a file a run should have written or left. */
    fun exists(path: String, bytes: ByteArray) {
        val metadata = metadata(path)
        when {
            metadata == null -> failures += "$path is missing; it should exist"
            metadata.size != bytes.size.toLong() ->
                failures += "$path is ${metadata.size} bytes; it should be ${bytes.size}"
            !resolve(path).readBytes().contentEquals(bytes) ->
                failures += "$path is the right size but not the right bytes"
        }
    }

    fun isDirectory(path: String) {
        if (metadata(path)?.isDirectory != true) failures += "$path is not a directory"
    }

    internal fun check(scenario: String) {
        if (failures.isNotEmpty()) throw LibraryWrong(scenario, failures)
    }

    private fun resolve(path: String): Path =
        path.split('/').filter(String::isNotEmpty).fold(root) { at, part -> Path(at, part) }

    private fun metadata(path: String) = SystemFileSystem.metadataOrNull(resolve(path))
}

// ------------------------------------------------------------------------------------- zone
//
// Closed-world: exactly these albums, and in each exactly these photos. The zone is generated
// from the library, so an unexpected row is a defect nobody has to have predicted.

internal class ZoneExpectations {
    private val albums = mutableMapOf<String, ExpectedAlbum>()

    fun empty() = Unit

    fun album(path: String, body: ExpectedAlbum.() -> Unit = {}) {
        albums[path] = ExpectedAlbum().apply(body)
    }

    internal suspend fun check(scenario: Scenario) {
        val state = scenario.s3.readZone(scenario.scratch)

        val declared = albums.mapValues { (_, album) -> album.files.keys.sorted() }
        val actual = state.byPath.mapValues { (_, shard) -> shard.photos.map(PhotoRow::filename).sorted() }
        if (declared != actual) throw ZoneMismatch(scenario.label, declared, actual)

        for ((path, expected) in albums) {
            val shard = state.byPath.getValue(path)
            expected.checkSourcePath(scenario.label, path, shard)
        }

        checkBlobIntegrity(scenario.label, state)
        checkShapes(scenario, state)
    }

    /**
     * Nothing referenced may be missing, nothing present may be unreferenced, and nothing may be
     * zero bytes. These are the three ways a zone loses or strands data without any row looking
     * wrong, and none of them can be expressed by naming a path.
     */
    private fun checkBlobIntegrity(scenario: String, state: ZoneState) {
        val referenced = state.albums.flatMap(Shard::objectIds).toSet()
        val present = state.blobs.keys
        val orphans = (present - referenced).map(Uuid::toString).toSet()
        val dangling = (referenced - present).map(Uuid::toString).toSet()
        val empty = state.blobs.filterValues { it == 0L }.keys.map(Uuid::toString).toSet()
        if (orphans.isNotEmpty() || dangling.isNotEmpty() || empty.isNotEmpty()) {
            throw BlobsStranded(scenario, orphans, dangling, empty)
        }
    }

    /**
     * One representative per media type is decoded, not every derivative.
     *
     * That is enough to prove each codec path survived the shipped link without re-asserting per
     * file what `DerivativeTest` already proves offline in milliseconds.
     */
    private suspend fun checkShapes(scenario: Scenario, state: ZoneState) {
        val seen = mutableSetOf<MediaType>()
        for (shard in state.albums) {
            for (row in shard.photos) {
                if (!seen.add(row.mediaType)) continue
                checkThumbnail(scenario, shard, row)
                row.imageId?.let { checkImage(scenario, row, it) }
                row.videoId?.let { checkVideo(scenario, row, it) }
            }
        }
    }

    private suspend fun checkThumbnail(scenario: Scenario, shard: Shard, row: PhotoRow) {
        val thumbsId = shard.info.thumbsId ?: throw DerivativeWrong(
            "the thumbnail pack", "album ${shard.info.name}", "the catalog writer",
            "a thumbs blob", "album_info.thumbs_id is null",
        )
        val pack = Path(scenario.scratch, "thumbs-${Uuid.random()}.db")
            .write(scenario.s3.fetch(thumbsId))
        val jpeg = ThumbPack(pack, NativeSqlDrivers()).thumbnail(row.id) ?: throw DerivativeWrong(
            "the thumbnail", row.filename, "the catalog writer",
            "a row in the album's thumbs pack", "no row for ${row.id}",
        )
        val size = jpeg.imageDimensions()
        val edge = DerivativeSpec.THUMBNAIL_EDGE
        if (size.width != edge || size.height != edge) {
            throw DerivativeWrong(
                "the thumbnail", row.filename, "libjpeg-turbo + lcms2 + swscale",
                "${edge}x$edge JPEG", "${size.width}x${size.height}",
            )
        }
    }

    private suspend fun checkImage(scenario: Scenario, row: PhotoRow, imageId: Uuid) {
        val file = Path(scenario.scratch, "image-${Uuid.random()}.heic")
            .write(scenario.s3.fetch(imageId))
        if (CImagingProbe().sniff(file.toString()) != MediaFormat.HEIF) {
            throw DerivativeWrong(
                "the viewing image", row.filename, "libheif + x265",
                "a HEIF file", "sniffed as ${CImagingProbe().sniff(file.toString())}",
            )
        }
        val size = imageDimensions(file.toString())
        val longEdge = maxOf(size.width, size.height)
        // Never upscaled (§5), so a small source stays small -- the ceiling is what is asserted.
        if (longEdge > DerivativeSpec.IMAGE_LONG_EDGE) {
            throw DerivativeWrong(
                "the viewing image", row.filename, "libheif + x265 + swscale",
                "long edge at most ${DerivativeSpec.IMAGE_LONG_EDGE}", "long edge $longEdge",
            )
        }
    }

    private suspend fun checkVideo(scenario: Scenario, row: PhotoRow, videoId: Uuid) {
        val file = Path(scenario.scratch, "video-${Uuid.random()}.mp4")
            .write(scenario.s3.fetch(videoId))
        val format = CImagingProbe().sniff(file.toString())
        if (format != MediaFormat.VIDEO) {
            throw DerivativeWrong(
                "the video", row.filename, "ffmpeg + x265",
                "an MP4 container", "sniffed as $format",
            )
        }
    }
}

internal class ExpectedAlbum {
    internal val files = mutableMapOf<String, Unit>()
    private var sourcePath: String? = null
    private var state: AlbumState? = null

    /** A still: a thumbnail and one viewing image are implied. */
    fun photo(filename: String) {
        files[filename] = Unit
    }

    /** A video: a thumbnail, an image_id poster and a video blob (§3). */
    fun video(filename: String) {
        files[filename] = Unit
    }

    fun sourcePath(path: String) {
        sourcePath = path
    }

    /** The laptop owns this album and its images are at the current profile. */
    fun encoded() {
        state = AlbumState.ENCODED
    }

    internal fun checkSourcePath(scenario: String, album: String, shard: Shard) {
        val problems = mutableListOf<String>()
        sourcePath?.let {
            if (shard.info.sourcePath != it) {
                problems += "album $album has source_path ${shard.info.sourcePath}, expected $it"
            }
        }
        state?.let {
            if (shard.info.state != it) {
                problems += "album $album is ${shard.info.state}, expected $it"
            }
            if (it == AlbumState.ENCODED &&
                shard.info.encodingVersion != DerivativeSpec.ENCODING_VERSION
            ) {
                problems += "album $album is at encoding version ${shard.info.encodingVersion}, " +
                    "expected ${DerivativeSpec.ENCODING_VERSION}"
            }
        }
        if (problems.isNotEmpty()) throw LibraryWrong(scenario, problems)
    }
}

private suspend fun S3Client.fetch(id: Uuid): ByteArray =
    (get(id.blobKey) as GetResult.Content).bytes
