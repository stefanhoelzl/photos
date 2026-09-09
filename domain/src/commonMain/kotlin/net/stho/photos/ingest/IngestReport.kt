package net.stho.photos.ingest

import kotlin.time.Duration
import kotlin.uuid.Uuid
import net.stho.photos.catalog.ShardProbe
import net.stho.photos.library.IgnoreRule

/**
 * What one run did, and what it could not make sense of.
 *
 * §4's posture, applied to the CLI: a run reports what it could not resolve rather than guessing.
 * Everything here is either a fact about the zone or a condition the design deliberately does not
 * auto-correct — and it is named on *every* run until a person deals with it.
 *
 * This is the second of §1's two failure tiers. What stops the run is thrown — see
 * `net.stho.photos.IngestAbort` — and what stops one photo or one album is returned here as data.
 */
public data class IngestReport(
    public val albums: List<AlbumOutcome> = emptyList(),
    public val deletedAlbums: List<DeletedAlbum> = emptyList(),
    public val pulledAlbums: List<PulledAlbum> = emptyList(),
    /**
     * Files the pipeline could not handle. Each keeps its album out of nothing: the album commits
     * without it and it is retried next run, because a file with no row reads as new (§7).
     */
    public val failures: List<Failure> = emptyList(),
    /** Files the classifier did not recognise as media. */
    public val strays: List<Failure> = emptyList(),
    /** Loose files sitting in a directory that also holds sub-albums (§2's XOR rule). */
    public val mixedFolders: List<MixedFolder> = emptyList(),
    /** Files the library root holds directly. Never ingested. */
    public val looseRootFiles: Int = 0,
    /** Albums left alone because their shard is newer than this build. */
    public val blockedByUnreadable: List<ShardProbe> = emptyList(),
    /** Albums that could not be written because another device wrote them twice running. */
    public val contendedAlbums: List<String> = emptyList(),
    /** Two albums under one parent with the same name. Both shown, never merged (§2). */
    public val duplicateNames: List<String> = emptyList(),
    /** A `parent` that resolves to no shard. The album surfaces at the top level (§2). */
    public val orphanedAlbums: List<Uuid> = emptyList(),
    public val sweptBlobs: Int = 0,
    public val sweptBytes: Long = 0,
    /**
     * Phone uploads abandoned before they finished: still [AlbumState.UPLOADING] past the
     * sweep's floor, so the presigned PUTs they were using have expired (§8).
     */
    public val abandonedUploads: Int = 0,
    /** Unreferenced but younger than the sweep's age floor, so possibly still uploading. */
    public val youngUnreferencedBlobs: Int = 0,
    public val sweepSkipped: String? = null,
    /**
     * `.photosignore` rules that excluded nothing — a typo and a rule not yet needed look
     * identical otherwise (§7).
     */
    public val unusedRules: List<IgnoreRule> = emptyList(),
    public val ignoredFiles: Int = 0,
    public val listedShards: Int = 0,
    public val fetchedShards: Int = 0,
    public val dryRun: Boolean = false,
) {
    public data class AlbumOutcome(
        public val path: String,
        public val uploaded: Int,
        public val dropped: Int,
        public val bytes: Long,
        public val created: Boolean,
        public val duration: Duration,
    )

    public data class DeletedAlbum(public val path: String, public val photos: Int)

    public data class PulledAlbum(
        public val path: String,
        public val files: Int,
        public val bytes: Long,
    )

    public data class Failure(public val path: String, public val message: String)

    public data class MixedFolder(public val path: String, public val files: Int)

    public val uploadedFiles: Int get() = albums.sumOf(AlbumOutcome::uploaded)

    public val uploadedBytes: Long get() = albums.sumOf(AlbumOutcome::bytes)

    public val droppedRows: Int get() = albums.sumOf(AlbumOutcome::dropped)

    /** Whether anything needs a person. Drives the exit code, and therefore `OnFailure=`. */
    public val hasProblems: Boolean
        get() = failures.isNotEmpty() ||
            contendedAlbums.isNotEmpty() ||
            mixedFolders.isNotEmpty() ||
            blockedByUnreadable.isNotEmpty() ||
            looseRootFiles > 0
}
