package net.stho.photos.pipeline

import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import net.stho.photos.exif.asText
import net.stho.photos.ports.ImageBackend
import net.stho.photos.ports.MediaUnreadable
import net.stho.photos.ports.MediaProbe

/** What one album's files turned into. */
public data class Classification(
    public val items: List<MediaItem> = emptyList(),
    public val skipped: List<SkippedFile> = emptyList(),
)

/**
 * Turns one album's files into items and strays.
 *
 * Takes a file list rather than walking, and the list it is given has already had
 * `.photosignore` applied by the library walker. So this type knows about media formats and
 * Live Photo pairing, and nothing whatever about what counts as junk.
 *
 * It does read files — pairing on `content.identifier` is not a filename question — but only
 * the HEICs and MOVs, and only their metadata.
 */
public class MediaClassifier(
    private val probe: MediaProbe,
    private val backend: ImageBackend,
) {
    private data class Sniffed(val path: String, val format: MediaFormat, val size: Long)

    public fun classify(paths: List<String>): Classification {
        val items = mutableListOf<MediaItem>()
        val skipped = mutableListOf<SkippedFile>()

        // Sniffing reads a bounded header prefix, so this pass costs one small read per file
        // even when a file is 2.66 GB of SQLite.
        val stills = mutableListOf<Sniffed>()
        val videos = mutableListOf<Sniffed>()

        for (path in paths) {
            val size = sizeOf(path)
            if (size == null) {
                skipped += SkippedFile(path, SkippedFile.Reason.Unreadable("cannot read $path"))
                continue
            }
            when (val format = probe.sniff(path)) {
                MediaFormat.VIDEO -> videos += Sniffed(path, format, size)
                MediaFormat.UNKNOWN ->
                    skipped += SkippedFile(path, SkippedFile.Reason.UnrecognisedFormat)
                else -> stills += Sniffed(path, format, size)
            }
        }

        // Decision 14: pair on content.identifier, not on filenames. Both signals agree on
        // every one of this library's 312 MOVs, but a filename match is a coincidence that
        // holds until someone drops an unrelated IMG_1234.MOV beside an IMG_1234.HEIC — and
        // then it silently swallows a real video into a Live Photo.
        val videoByIdentifier = buildMap {
            for (video in videos) {
                val identifier = probe.videoInfo(video.path)?.contentIdentifier ?: continue
                put(identifier, video.path)
            }
        }
        val pairedVideos = mutableSetOf<String>()

        for (still in stills) {
            val paired = if (still.format == MediaFormat.HEIF) {
                contentIdentifier(still.path)?.let(videoByIdentifier::get)
            } else {
                null
            }
            if (paired != null) pairedVideos += paired
            val kind = when {
                still.format == MediaFormat.CR2 -> MediaItem.Kind.Raw
                paired != null -> MediaItem.Kind.LivePhoto(paired)
                else -> MediaItem.Kind.Still
            }
            items += MediaItem(still.path, kind, still.size)
        }

        for (video in videos) {
            if (video.path in pairedVideos) {
                skipped += SkippedFile(video.path, SkippedFile.Reason.ConsumedAsLivePhotoVideo)
            } else {
                items += MediaItem(video.path, MediaItem.Kind.Video, video.size)
            }
        }

        // Stable order, so two runs over the same album report the same thing and a diff of two
        // scan reports shows real changes rather than directory-iteration order.
        return Classification(items.sortedBy(MediaItem::path), skipped.sortedBy(SkippedFile::path))
    }

    /** Null when the file cannot be stat'd at all — reported, never fatal. */
    private fun sizeOf(path: String): Long? = try {
        SystemFileSystem.metadataOrNull(Path(path))?.size
    } catch (_: IOException) {
        null
    }

    /**
     * The Live Photo identifier a HEIC carries, or null.
     *
     * A backend that cannot read this file's tags is not a reason to reject the still: it means
     * only that this HEIC has no identifier to pair on.
     */
    private fun contentIdentifier(path: String): String? = try {
        backend.rawTags(path)["AppleContentIdentifier"]?.asText
    } catch (_: MediaUnreadable) {
        null
    }
}
