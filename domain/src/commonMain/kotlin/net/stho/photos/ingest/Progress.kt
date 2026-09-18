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
    /**
     * [files] and [bytes] are what the uploads read off the disk; [pullFiles] and friends what
     * the pulls and merges bring down from the zone — exact, from the run's listing, so the pull
     * phase can be judged before it starts.
     */
    public data class Planned(
        public val albums: Int,
        public val files: Int,
        public val bytes: Long,
        public val deletions: Int,
        public val pulls: Int,
        public val merges: Int = 0,
        public val pullFiles: Int = 0,
        public val pullBytes: Long = 0,
        public val mergeFiles: Int = 0,
        public val mergeBytes: Long = 0,
    ) : IngestEvent

    /**
     * Staging left behind by a run that was killed, and has just been removed.
     *
     * Emitted only when there was something to remove, because that is the point of it: it is the
     * one thing in the journal that says a previous run did not finish. A run that stopped
     * cleanly emptied its own work directory in a `finally`, so it leaves nothing to report.
     */
    public data class Reclaimed(
        public val files: Int,
        public val bytes: Long,
    ) : IngestEvent

    public data class Line(public val text: String) : IngestEvent

    public data class Status(public val text: String) : IngestEvent
}

/**
 * Files done, bytes moved, and what that implies about when this finishes.
 *
 * Restarted for every phase — the uploads as a whole, then each pulled or merged album — because
 * a phase's total is the only one that is honest: counting a pull's files against the uploads'
 * total is what once showed 175/70. And one rate across encoding and downloading would be the
 * average of two unrelated speeds.
 *
 * Counts *files*, the unit the total is known in before anything is classified: an item ticks
 * by the files it consumed, so a Live Photo counts two and the counter reaches its total.
 *
 * Throttled to one update a second: a status line redrawn once per uploaded photo would be several
 * a second at the start of a small album and pointless the rest of the time.
 *
 * A [Mutex] rather than confining the counters to an owning coroutine — the state is a handful
 * of numbers touched by every encoder worker, and serialising the *writer* is the whole
 * requirement.
 */
internal class ProgressMeter(private val clock: Clock) {

    private val mutex = Mutex()
    private var totalFiles = 0

    /** Bytes on disk, which is the only total known before anything has been derived. */
    private var totalSource = 0L
    private var doneFiles = 0

    /** Bytes this run has produced — what actually goes to the zone. */
    private var doneBytes = 0L

    /** Bytes on disk behind [doneBytes], which is what makes the two comparable. */
    private var doneSource = 0L
    private var started: Instant = Instant.DISTANT_PAST
    private var lastEmit: Instant = Instant.DISTANT_PAST

    suspend fun start(files: Int, sourceBytes: Long): Unit = mutex.withLock {
        totalFiles = files
        totalSource = sourceBytes
        doneFiles = 0
        doneBytes = 0
        doneSource = 0
        started = clock.now()
        lastEmit = Instant.DISTANT_PAST
    }

    /**
     * The counter to redraw, or null when it is too soon to bother. The caller says what it is
     * counting — the album, or which pull — around it.
     *
     * [files] is how many of the planned files this item consumed, [bytes] what it produced and
     * [source] what it was made from. Both byte counts, because the total that matters — how much
     * goes to the zone — cannot be known before deriving, and a constant guessing at it would be
     * one more number to keep true as §5's profile changes. The run measures its own ratio
     * instead, and re-projects the remainder from it.
     */
    suspend fun finished(files: Int, bytes: Long, source: Long): String? = mutex.withLock {
        doneFiles += files
        doneBytes += bytes
        doneSource += source
        if (totalFiles == 0) return@withLock null
        val now = clock.now()
        if (now - lastEmit < 1.seconds && doneFiles < totalFiles) return@withLock null
        lastEmit = now

        val elapsed = (now - started).toDouble(DurationUnit.SECONDS)
        val rate = if (elapsed > 0) doneBytes / elapsed else 0.0
        val projected = projectedTotal()
        buildString {
            append(doneFiles).append('/').append(totalFiles).append(" files")
            append("  ").append(formatBytes(doneBytes))
            // `~` because the far end is projected from this run's own ratio, not measured.
            // Absent entirely until there is enough of a sample to project from: a total that
            // is one photograph's guess is worse than no total.
            if (projected != null) append(" of ~").append(formatBytes(projected))
            appendRate(rate, projected?.let { it - doneBytes })
        }
    }

    /**
     * How much this run will send in total, projected from how much it has sent so far.
     *
     * The ratio of produced bytes to source bytes is a property of §5's profile and of this
     * particular library, and it settles within a few photographs — so the run measures it
     * rather than carrying a constant that would silently go stale the next time the profile
     * moves. Null until [SAMPLE_BEFORE_PROJECTING] items are in, and null once the remainder is
     * nothing, where the answer is simply what has been done.
     */
    private fun projectedTotal(): Long? {
        if (doneFiles < SAMPLE_BEFORE_PROJECTING || doneSource <= 0) return null
        val remainingSource = (totalSource - doneSource).coerceAtLeast(0)
        // Double, because source bytes times produced bytes overflows a Long at this scale.
        val ratio = doneBytes.toDouble() / doneSource.toDouble()
        return doneBytes + (remainingSource * ratio).toLong()
    }

    private companion object {
        /**
         * Items to observe before showing a total. One worker-round's worth: enough that a
         * single outlier — a panorama, a 200 MB video — cannot set the projection on its own.
         */
        const val SAMPLE_BEFORE_PROJECTING = 16
    }
}

/**
 * Files and bytes coming down for one pulled or merged album.
 *
 * Unlike the upload side the total is exact — the run's listing has every object's size — so
 * there is nothing to project. Bytes are counted while a file is still arriving, because a
 * counter that moved only between files would sit still for the whole of a 200 MB video.
 *
 * Several files may be in flight: each reports under its own slot, and the line adds them up.
 * A file already on disk from an interrupted run counts as done but not towards the rate, which
 * is what the link managed and nothing else.
 */
internal class DownloadMeter(private val clock: Clock) {

    private val mutex = Mutex()
    private var totalFiles = 0
    private var totalBytes = 0L
    private var doneFiles = 0

    /** Bytes of the files that are finished, fetched or found. */
    private var doneBytes = 0L

    /** Bytes of finished files that actually came over the link. */
    private var fetchedBytes = 0L

    /** Bytes so far of each file still arriving, by slot. */
    private val arriving = mutableMapOf<Int, Long>()
    private var started: Instant = Instant.DISTANT_PAST
    private var lastEmit: Instant = Instant.DISTANT_PAST

    suspend fun start(files: Int, bytes: Long): Unit = mutex.withLock {
        totalFiles = files
        totalBytes = bytes
        doneFiles = 0
        doneBytes = 0
        fetchedBytes = 0
        arriving.clear()
        started = clock.now()
        lastEmit = Instant.DISTANT_PAST
    }

    /** [received] bytes of the file in [slot] so far. The line to redraw, or null. */
    suspend fun receiving(slot: Int, received: Long): String? = mutex.withLock {
        arriving[slot] = received
        line()
    }

    /** The file in [slot] is on disk, [bytes] long; [fetched] unless it already was. */
    suspend fun finished(slot: Int, bytes: Long, fetched: Boolean): String? = mutex.withLock {
        arriving.remove(slot)
        doneFiles++
        doneBytes += bytes
        if (fetched) fetchedBytes += bytes
        line()
    }

    private fun line(): String? {
        if (totalFiles == 0) return null
        val now = clock.now()
        if (now - lastEmit < 1.seconds && doneFiles < totalFiles) return null
        lastEmit = now

        val inFlight = arriving.values.sum()
        val done = doneBytes + inFlight
        val elapsed = (now - started).toDouble(DurationUnit.SECONDS)
        val rate = if (elapsed > 0) (fetchedBytes + inFlight) / elapsed else 0.0
        return buildString {
            append(doneFiles).append('/').append(totalFiles).append(" files")
            append("  ").append(formatBytes(done)).append(" of ").append(formatBytes(totalBytes))
            appendRate(rate, totalBytes - done)
        }
    }
}

/** `  0.33 MB/s  ~14m left`, the estimate only when there is a remainder to estimate. */
private fun StringBuilder.appendRate(rate: Double, remainingBytes: Long?) {
    if (rate <= 0) return
    append("  ").append((rate / 1_000_000).fixed(2)).append(" MB/s")
    val remaining = remainingBytes?.let { it / rate }
    if (remaining != null && remaining > 0 && remaining.isFinite()) {
        append("  ~").append(formatDuration(remaining.seconds)).append(" left")
    }
}

/**
 * Human byte sizes, decimal — so `GB` means 10⁹ bytes and not 2³⁰.
 *
 * Decimal because of what these numbers are compared against. §9 bills at $0.01/GB and
 * bunny.net means the decimal GB there, so a run reporting 19.2 GB uploaded and an invoice
 * computed on 19.2 GB agree; dividing by 1024 while writing `GB` had them differ by 7.4% with
 * nothing on screen to explain it. Link speeds are quoted decimal for the same reason.
 */
public fun formatBytes(bytes: Long): String {
    val units = listOf(1_000_000_000L to "GB", 1_000_000L to "MB", 1000L to "KB")
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

/**
 * The journal's line for an album that came down from the zone: `v` for a pull, `<` for a merge.
 *
 * One line, written once the album has committed, carrying both halves — what came down and what
 * went back up derived — rather than a line for each that reads as two albums.
 */
internal fun transferLine(
    marker: String,
    path: String,
    files: Int,
    downloaded: Long,
    album: IngestReport.AlbumOutcome?,
    duration: Duration,
): String = buildList {
    add("$marker $path")
    add(if (downloaded > 0) "$files files, ${formatBytes(downloaded)} down" else "$files files")
    if (album != null && album.uploaded > 0) {
        add("${album.uploaded} photos, ${formatBytes(album.bytes)} up")
    }
    add("${duration.toDouble(DurationUnit.SECONDS).fixed(1)}s")
}.joinToString("  ")

/** The per-album line the journal records once an album has committed. */
internal fun IngestReport.AlbumOutcome.asLine(): String = buildList {
    add("${if (created) "+" else "~"} ${path.ifEmpty { "." }}")
    if (uploaded > 0) add("$uploaded photos")
    if (dropped > 0) add("-$dropped rows")
    if (bytes > 0) add(formatBytes(bytes))
    add("${duration.toDouble(DurationUnit.SECONDS).fixed(1)}s")
}.joinToString("  ")
