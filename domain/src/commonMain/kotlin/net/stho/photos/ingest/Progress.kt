package net.stho.photos.ingest

import kotlin.math.roundToLong
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What a run tells the caller while it is happening.
 *
 * Split by *destination* rather than by kind: [Line] is the record, and belongs on stdout and in
 * the journal; [Status] is a redrawing counter that belongs on a terminal and nowhere else.
 * Deciding that here would mean this type knowing what a journal is.
 */
public sealed interface IngestEvent {
    public data class Planned(
        public val albums: Int,
        public val files: Int,
        public val bytes: Long,
        public val deletions: Int,
        public val pulls: Int,
    ) : IngestEvent

    public data class Line(public val text: String) : IngestEvent

    public data class Status(public val text: String) : IngestEvent
}

/**
 * Files done, bytes moved, and what that implies about when this finishes.
 *
 * Throttled to one update a second: a status line redrawn once per uploaded photo would be several
 * a second at the start of a small album and pointless the rest of the time.
 *
 * A [Mutex] rather than Swift's actor — the state is a handful of counters touched by every
 * encoder worker, and serialising the *writer* is the whole requirement.
 */
internal class ProgressMeter(private val clock: Clock) {

    private val mutex = Mutex()
    private var totalFiles = 0
    private var totalBytes = 0L
    private var doneFiles = 0
    private var doneBytes = 0L
    private var started: Instant = Instant.DISTANT_PAST
    private var lastEmit: Instant = Instant.DISTANT_PAST

    suspend fun start(files: Int, bytes: Long): Unit = mutex.withLock {
        totalFiles = files
        totalBytes = bytes
        doneFiles = 0
        doneBytes = 0
        started = clock.now()
        lastEmit = Instant.DISTANT_PAST
    }

    /** The line to redraw, or null when it is too soon to bother. */
    suspend fun finished(bytes: Long, album: String): String? = mutex.withLock {
        doneFiles++
        doneBytes += bytes
        if (totalFiles == 0) return@withLock null
        val now = clock.now()
        if (now - lastEmit < 1.seconds && doneFiles != totalFiles) return@withLock null
        lastEmit = now

        val elapsed = (now - started).toDouble(DurationUnit.SECONDS)
        val rate = if (elapsed > 0) doneBytes / elapsed else 0.0
        buildString {
            append(doneFiles).append('/').append(totalFiles)
            append("  ").append(formatBytes(doneBytes))
            if (totalBytes > 0) append(" of ").append(formatBytes(totalBytes))
            if (rate > 0) {
                append("  ").append((rate / 1_048_576).fixed(2)).append(" MB/s")
                val remaining = (totalBytes - doneBytes) / rate
                if (remaining > 0 && remaining.isFinite()) {
                    append("  ~").append(formatDuration(remaining.seconds)).append(" left")
                }
            }
            append("  ").append(album)
        }
    }
}

/** Human byte sizes, in the units the design's own numbers are quoted in. */
public fun formatBytes(bytes: Long): String {
    val units = listOf(1_073_741_824L to "GB", 1_048_576L to "MB", 1024L to "KB")
    for ((scale, suffix) in units) {
        if (bytes >= scale) return "${(bytes.toDouble() / scale).fixed(1)} $suffix"
    }
    return "$bytes B"
}

/** Hours and minutes, because the number this is usually reporting is "tomorrow". */
public fun formatDuration(duration: Duration): String {
    val total = duration.toDouble(DurationUnit.SECONDS).roundToLong()
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${total % 60}s"
        else -> "${total}s"
    }
}

/**
 * Fixed-point formatting, because Kotlin common has no `String.format`.
 *
 * Only ever applied to the non-negative quantities this file prints — byte counts, rates and
 * durations.
 */
internal fun Double.fixed(places: Int): String {
    var scale = 1L
    repeat(places) { scale *= 10 }
    val scaled = (this * scale).roundToLong()
    if (places == 0) return scaled.toString()
    val fraction = (scaled % scale).toString().padStart(places, '0')
    return "${scaled / scale}.$fraction"
}

/** The per-album line the journal records once an album has committed. */
internal fun IngestReport.AlbumOutcome.asLine(): String = buildList {
    add("${if (created) "+" else "~"} ${path.ifEmpty { "." }}")
    if (uploaded > 0) add("$uploaded photos")
    if (dropped > 0) add("-$dropped rows")
    if (bytes > 0) add(formatBytes(bytes))
    add("${duration.toDouble(DurationUnit.SECONDS).fixed(1)}s")
}.joinToString("  ")
