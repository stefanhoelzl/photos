package net.stho.photos.cli

import net.stho.photos.ingest.IngestEvent
import net.stho.photos.ingest.IngestReport
import net.stho.photos.ingest.formatBytes

/**
 * What a person sees of a run.
 *
 * Presentation and nothing else: what counts as unresolved is decided in `:domain` and arrives
 * here as `IngestReport` — plain data — while what it *looks* like is decided here. There is
 * deliberately no port behind this. One was declared during the design and turned out to have
 * no caller: the report being data is exactly what makes it assertable without a fake, so the
 * port had nothing to abstract and nothing to stand in for.
 *
 * Three shapes of line is the whole vocabulary: a redrawing counter, a condition nobody has
 * dealt with yet, and a subject that failed.
 */
internal class ConsoleReporter(private val console: Console) {

    fun progress(message: String) {
        console.progress(message)
    }

    fun unresolved(condition: String, detail: String) {
        console.note("$condition: $detail")
    }

    fun failed(subject: String, message: String) {
        console.note("$subject: $message")
    }

    /**
     * What a run says while it is happening.
     *
     * Split by destination, which is why [IngestEvent] is split that way: [IngestEvent.Line] is
     * the record and belongs in the journal, [IngestEvent.Status] is a counter that belongs on a
     * terminal and nowhere else. A line clears the counter first so the two never interleave into
     * one unreadable row.
     */
    fun show(event: IngestEvent) {
        when (event) {
            is IngestEvent.Planned -> console.line(plan(event))
            is IngestEvent.Reclaimed -> console.line(
                "reclaimed ${formatBytes(event.bytes)} of staging from an interrupted run",
            )

            is IngestEvent.Line -> {
                console.clearProgress()
                console.line(event.text)
            }

            is IngestEvent.Status -> progress(event.text)
        }
    }

    /**
     * The end-of-run block.
     *
     * §4's "reported, not resolved", made concrete: everything the run could not make sense of is
     * named here rather than guessed at — a shard too new to read, two albums with one name, a
     * parent that resolves to nothing, a rule that matched nothing. None of it is corrected
     * automatically, and all of it is visible on every run until a person deals with it.
     */
    fun render(report: IngestReport) {
        if (report.dryRun) console.line("dry run — nothing was changed")

        for (deleted in report.deletedAlbums) {
            console.line("- ${deleted.path}  ${deleted.photos} photos")
        }

        for (stray in report.strays) unresolved(stray.path, stray.message)
        for (failure in report.failures) failed(failure.path, failure.message)
        for (folder in report.mixedFolders) {
            unresolved(
                folder.path,
                "holds sub-albums and ${folder.files} loose file(s) — the files were not " +
                    "ingested (an album has sub-albums or photos, not both)",
            )
        }
        if (report.looseRootFiles > 0) {
            unresolved(
                "the library root",
                "${report.looseRootFiles} file(s) sit directly in it and were not ingested — " +
                    "the library is a directory of albums",
            )
        }
        for (probe in report.blockedByUnreadable) {
            unresolved(
                probe.sourcePath ?: probe.albumId.toString(),
                "is schema ${probe.schemaVersion}, newer than this build reads — left untouched",
            )
        }
        for (claim in report.doublyClaimed) {
            unresolved(
                claim.sourcePath,
                "is claimed by ${claim.albumIds.size} albums (${claim.albumIds.joinToString()}) — " +
                    "left untouched until all but one are removed",
            )
        }
        for (path in report.contendedAlbums) {
            unresolved(path, "was written by another device — skipped, retried next run")
        }
        for (name in report.duplicateNames) {
            unresolved(
                "duplicate album name",
                "two albums are named \"$name\" under one parent — both are shown",
            )
        }
        for (album in report.orphanedAlbums) {
            unresolved(
                "album $album",
                "names a parent that does not exist — shown at the top level",
            )
        }
        for (rule in report.unusedRules) {
            unresolved("ignore rule \"${rule.pattern}\"", "matched nothing")
        }

        // The two things content addressing buys are invisible unless the run says so: a
        // resumed import, and a profile bump that leaves thumbnails alone (§2).
        if (report.skippedUploads > 0) {
            console.line(
                "${report.skippedUploads} object(s) already in the zone, " +
                    "${formatBytes(report.skippedBytes)} not re-sent",
            )
        }
        if (report.abandonedUploads > 0) {
            unresolved(
                "abandoned upload(s)",
                "${report.abandonedUploads} album(s) never finished uploading and were removed",
            )
        }

        val skipped = report.sweepSkipped
        if (skipped != null) {
            unresolved("orphan sweep skipped", skipped)
        } else if (report.sweptBlobs > 0) {
            val verb = if (report.dryRun) "would sweep" else "swept"
            console.line(
                "$verb ${report.sweptBlobs} unreferenced blob(s), " +
                    formatBytes(report.sweptBytes),
            )
        }

        console.line("── " + summary(report).joinToString(", "))
    }
}

/** What the run intends, said before it does any of it (§7). */
private fun plan(event: IngestEvent.Planned): String {
    val parts = buildList {
        if (event.albums > 0) {
            // "to read", not "to upload": since §5 far less goes up than comes off the disk, and
            // how much less is not knowable until it has been derived. The counter projects the
            // upload once it has watched enough of this run to know.
            add("${event.albums} album(s), ${event.files} photos, ${formatBytes(event.bytes)} to read")
        }
        if (event.deletions > 0) add("${event.deletions} album(s) to delete")
        if (event.pulls > 0) add("${event.pulls} album(s) to pull")
    }
    return if (parts.isEmpty()) "nothing to do" else "to do: " + parts.joinToString(", ")
}

/** The one line somebody skimming the journal for "did anything happen" reads. */
private fun summary(report: IngestReport): List<String> = buildList {
    add("${report.albums.size} album(s)")
    if (report.uploadedFiles > 0) {
        add("${report.uploadedFiles} photos, ${formatBytes(report.uploadedBytes)}")
    }
    if (report.droppedRows > 0) add("${report.droppedRows} row(s) dropped")
    if (report.deletedAlbums.isNotEmpty()) add("${report.deletedAlbums.size} album(s) deleted")
    if (report.pulledAlbums.isNotEmpty()) add("${report.pulledAlbums.size} album(s) pulled")
    if (report.failures.isNotEmpty()) add("${report.failures.size} failed")
    if (report.ignoredFiles > 0) add("${report.ignoredFiles} ignored")
}
