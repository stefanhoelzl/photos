@file:OptIn(ExperimentalForeignApi::class, ExperimentalUuidApi::class)

package net.stho.photos.adapter.linux

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import net.stho.photos.derivative.DerivativeSpec
import net.stho.photos.exif.ExifTags
import net.stho.photos.exif.toPhotoRow
import net.stho.photos.faces.DetectedFace
import net.stho.photos.faces.FaceModels
import net.stho.photos.model.MediaType
import net.stho.photos.pipeline.Derivatives
import net.stho.photos.pipeline.MediaItem
import net.stho.photos.pipeline.PipelineEvent
import net.stho.photos.storage.sha256Hex
import net.stho.photos.pipeline.withExtension
import net.stho.photos.ports.Ids
import net.stho.photos.ports.ImageBackend
import net.stho.photos.ports.MediaUnreadable
import net.stho.photos.ports.Pipeline
import photosimaging.pi_video_transcode

/**
 * The derivative pipeline over libjpeg-turbo, libheif and ffmpeg.
 *
 * [derive] is synchronous and safe to call from many threads at once. It owns no worker pool:
 * ingest owns that, because only ingest can interleave encoding — which is CPU-bound and
 * parallel — with §9's uploads, which are link-bound and deliberately serial.
 */
public class CImagingPipeline(
    private val workDirectory: String,
    private val backend: ImageBackend = CImagingBackend(),
    /**
     * Bounds x265's internal pool, for both tiers.
     *
     * It defaults to 1 because [derive] is documented as safe to call from many threads and the
     * caller owns the pool — an encoder opening its own would be a second, hidden one. Measured
     * on a 16-core machine with 16 workers: unbounded pools cost 7.2 GB resident. Raise it when
     * driving the pipeline from a single thread.
     */
    private val encoderThreads: Int = 1,
    /** Row identity. Injected so a run's rows can be asserted on at all (§7). */
    private val ids: Ids = Ids { Uuid.random() },
    /** §12's face models. Null derives without looking for faces. */
    private val faces: CImagingFaces? = null,
) : Pipeline {

    // `bufferingNewest(256)` by another name: a caller that stops reading progress must never
    // stall a worker mid-encode, so the oldest event is what gives way.
    private val progress = MutableSharedFlow<PipelineEvent>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val events: SharedFlow<PipelineEvent> = progress.asSharedFlow()

    override fun derive(item: MediaItem): Derivatives {
        progress.tryEmit(PipelineEvent.Started(item.path))
        try {
            val kind = item.kind
            val result = when (kind) {
                MediaItem.Kind.Still -> deriveStill(item)
                MediaItem.Kind.Raw -> deriveRaw(item)
                is MediaItem.Kind.LivePhoto -> deriveStill(item).let {
                    it.copy(
                        row = it.row.copy(mediaType = MediaType.LIVE_PHOTO),
                        liveStill = item.path,
                        liveVideo = kind.video,
                    )
                }
                MediaItem.Kind.Video -> deriveVideo(item)
            }
            progress.tryEmit(PipelineEvent.Finished(item.path))
            return result
        } catch (failure: Exception) {
            progress.tryEmit(PipelineEvent.Failed(item.path, failure.message ?: failure.toString()))
            throw failure
        }
    }

    // ---------------------------------------------------------------- stills

    private fun deriveStill(item: MediaItem): Derivatives {
        val tags = tagsOrEmpty(item.path)
        return PixelImage.decode(item.path, DerivativeSpec.IMAGE_LONG_EDGE).use { decoded ->
            finishStill(item, tags, decoded, MediaType.PHOTO)
        }
    }

    private fun deriveRaw(item: MediaItem): Derivatives {
        val tags = tagsOrEmpty(item.path)
        val extraction = carveEmbeddedJpeg(item.path)
        return PixelImage.decodeJpeg(extraction.jpeg, DerivativeSpec.IMAGE_LONG_EDGE).use { decoded ->
            // §3: the row describes what is in the zone. What is in the zone is a HEIC derived
            // from the carved JPEG — so that is the name, and the CR2's own name is kept as
            // `sourceFilename` so reconciliation can still find the file on disk.
            finishStill(
                item = item,
                tags = tags,
                decoded = decoded,
                mediaType = MediaType.PHOTO,
                filename = item.filename.withExtension("heic"),
                sourceFilename = item.filename,
            )
        }
    }

    private fun finishStill(
        item: MediaItem,
        tags: ExifTags,
        decoded: PixelImage,
        mediaType: MediaType,
        filename: String? = null,
        sourceFilename: String? = null,
    ): Derivatives {
        // Orientation is already applied — libjpeg's caller bakes it, libheif applies irot
        // itself, and the video path bakes the display matrix — so the decoded buffer's own
        // dimensions are the display dimensions §3 wants stored.
        if (decoded.hasAlpha) decoded.flattenAlpha(DerivativeSpec.ALPHA_BACKGROUND)
        progress.tryEmit(PipelineEvent.Decoded(item.path, decoded.width, decoded.height))

        val image = makeImage(decoded)
        progress.tryEmit(PipelineEvent.Imaged(item.path, image.size))
        val thumbnail = makeThumbnail(decoded)
        progress.tryEmit(PipelineEvent.Thumbnailed(item.path, thumbnail.size))

        val row = tags.toPhotoRow(
            id = ids.next(),
            filename = filename ?: item.filename,
            sourceFilename = sourceFilename,
            bytes = image.size.toLong(),
            sourceBytes = item.byteCount,
            // The file is read once more to digest it. That is a real cost on first import and
            // it buys the one integrity record nothing else can reconstruct: every other blob
            // in the zone is a derivative this pipeline can rebuild from the original (§7).
            originalHash = Path(item.path).sha256Hex(),
            mediaType = mediaType,
        ).copy(
            // The decoded *source* is the authority, not the decoded buffer: shrink-on-load
            // means a 3000px photo may well arrive as a 2250px buffer, and §3 stores the
            // photograph's dimensions. EXIF is not consulted — PixelXDimension can be absent,
            // can describe the embedded thumbnail, or can simply disagree with the pixels, and
            // a grid that lays out from the wrong aspect ratio is visibly wrong.
            width = decoded.sourceWidth,
            height = decoded.sourceHeight,
        )

        // The decode the image and thumbnail were made from, so a new photograph costs its faces
        // no second decode (§12).
        return Derivatives(row, tags, thumbnail, image, faces = faces?.find(decoded))
    }

    /** 3200px long edge, aspect preserved, never upscaled, source ICC carried through. */
    private fun makeImage(decoded: PixelImage): ByteArray =
        decoded.resizedFitting(
            longEdge = DerivativeSpec.IMAGE_LONG_EDGE,
            allowUpscale = DerivativeSpec.IMAGE_UPSCALES,
        ).use { resized ->
            resized.applyColorHandling(DerivativeSpec.IMAGE_COLOR)
            resized.encodedHeic(quality = DerivativeSpec.IMAGE_QUALITY, threads = encoderThreads)
        }

    /** 256×256 centre crop, converted to sRGB, no profile embedded. */
    private fun makeThumbnail(decoded: PixelImage): ByteArray =
        decoded.squareCropped(DerivativeSpec.THUMBNAIL_EDGE).use { square ->
            square.applyColorHandling(DerivativeSpec.THUMBNAIL_COLOR)
            square.encodedJpeg(quality = DerivativeSpec.THUMBNAIL_QUALITY, optimize = true)
        }

    override fun findFaces(item: MediaItem, sensitive: Boolean): List<DetectedFace>? {
        val faces = faces ?: return null
        val find: (PixelImage) -> List<DetectedFace> = if (sensitive) {
            { faces.find(it, FaceModels.SENSITIVE_LONG_EDGE, FaceModels.SENSITIVE_MIN_SCORE) }
        } else {
            { faces.find(it) }
        }
        return try {
            when (item.kind) {
                MediaItem.Kind.Still, is MediaItem.Kind.LivePhoto ->
                    PixelImage.decode(item.path, DerivativeSpec.IMAGE_LONG_EDGE).use(find)
                MediaItem.Kind.Raw ->
                    PixelImage.decodeJpeg(carveEmbeddedJpeg(item.path).jpeg, DerivativeSpec.IMAGE_LONG_EDGE)
                        .use(find)
                // §12 leaves video out of the first version.
                MediaItem.Kind.Video -> emptyList()
            }
        } catch (failure: ImagingException) {
            throw MediaUnreadable(failure.message, failure)
        }
    }

    // ---------------------------------------------------------------- video

    private fun deriveVideo(item: MediaItem): Derivatives {
        val info = probeVideo(item.path)
        val tags = tagsOrEmpty(item.path)

        return PixelImage.poster(item.path, DerivativeSpec.posterTime(info.duration)).use { poster ->
            if (poster.hasAlpha) poster.flattenAlpha(DerivativeSpec.ALPHA_BACKGROUND)
            progress.tryEmit(PipelineEvent.Decoded(item.path, poster.width, poster.height))

            val image = makeImage(poster)
            progress.tryEmit(PipelineEvent.Imaged(item.path, image.size))
            val thumbnail = makeThumbnail(poster)
            progress.tryEmit(PipelineEvent.Thumbnailed(item.path, thumbnail.size))

            val work = Path(workDirectory)
            SystemFileSystem.createDirectories(work)
            val output = Path(work, "${Uuid.random()}.mp4")

            progress.tryEmit(PipelineEvent.Transcoding(item.path, 0.0))
            imagingCall { err ->
                pi_video_transcode(
                    item.path,
                    output.toString(),
                    DerivativeSpec.VIDEO_MAX_HEIGHT,
                    DerivativeSpec.VIDEO_QUALITY,
                    encoderThreads,
                    err,
                )
            }
            progress.tryEmit(PipelineEvent.Transcoding(item.path, 1.0))

            // §3 again: the zone holds the transcode, not the camera's file, so the row is
            // named and sized after the transcode. The source name survives in
            // `sourceFilename` so reconciliation can find the file on disk, and `sourceBytes`
            // now carries the check that used to ride on `bytes` (§7).
            val row = tags.toPhotoRow(
                id = ids.next(),
                filename = item.filename.withExtension("mp4"),
                sourceFilename = item.filename,
                bytes = SystemFileSystem.metadataOrNull(output)?.size,
                sourceBytes = item.byteCount,
                originalHash = Path(item.path).sha256Hex(),
                mediaType = MediaType.VIDEO,
            ).copy(
                width = poster.width,
                // Posters decode at full size; no shrink-on-load.
                height = poster.height,
            )

            // §3: a video has a video_id and an image_id poster, and no still of its own.
            Derivatives(row, tags, thumbnail, image, video = output.toString())
        }
    }

    /**
     * Tags are a courtesy: a file whose EXIF block is missing or malformed still has
     * derivatives, and decision 15 says only an undecodable file is skipped.
     */
    private fun tagsOrEmpty(path: String): ExifTags = try {
        backend.rawTags(path)
    } catch (_: MediaUnreadable) {
        ExifTags()
    }
}
