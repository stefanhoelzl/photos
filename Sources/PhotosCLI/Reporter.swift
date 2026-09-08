import Foundation
import PhotosIngest

/// The end-of-run block.
///
/// §4's "reported, not resolved", made concrete: everything the run could not make sense of
/// is named here rather than guessed at — a shard too new to read, two albums with one name,
/// a parent that resolves to nothing, a rule that matched nothing. None of it is corrected
/// automatically, and all of it is visible on every run until a person deals with it.
struct Reporter {
    let console: Console

    func render(_ report: IngestReport) {
        if report.dryRun { console.line("dry run — nothing was changed") }

        for outcome in report.deletedAlbums {
            console.line("- \(outcome.path)  \(outcome.photos) photos")
        }

        for stray in report.strays {
            console.note("\(stray.path): \(stray.message)")
        }
        for failure in report.failures {
            console.note("\(failure.path): \(failure.message)")
        }
        for folder in report.mixedFolders {
            console.note("""
                \(folder.path) holds sub-albums and \(folder.files) loose file(s) — \
                the files were not ingested (an album has sub-albums or photos, not both)
                """)
        }
        if report.looseRootFiles > 0 {
            console.note("""
                \(report.looseRootFiles) file(s) sit directly in the library root and were \
                not ingested — the library is a directory of albums
                """)
        }
        for probe in report.blockedByUnreadable {
            console.note("""
                \(probe.sourcePath ?? probe.albumID.uuidString) is schema \
                \(probe.schemaVersion), newer than this build reads — left untouched
                """)
        }
        for path in report.contendedAlbums {
            console.note("\(path) was written by another device — skipped, retried next run")
        }
        for name in report.duplicateNames {
            console.note("two albums are named \"\(name)\" under one parent — both are shown")
        }
        for album in report.orphanedAlbums {
            console.note("album \(album) names a parent that does not exist — shown at the top level")
        }
        for rule in report.unusedRules {
            console.note("ignore rule \"\(rule.pattern)\" matched nothing")
        }

        if let skipped = report.sweepSkipped {
            console.note("orphan sweep skipped: \(skipped)")
        } else if report.sweptBlobs > 0 {
            let verb = report.dryRun ? "would sweep" : "swept"
            console.line("\(verb) \(report.sweptBlobs) unreferenced blob(s), \(formatBytes(report.sweptBytes))")
        }
        if report.youngUnreferencedBlobs > 0 {
            console.line("""
                \(report.youngUnreferencedBlobs) unreferenced blob(s) left alone — \
                younger than the sweep's age floor, so possibly still uploading
                """)
        }

        var summary = ["\(report.albums.count) album(s)"]
        if report.uploadedFiles > 0 {
            summary.append("\(report.uploadedFiles) photos, \(formatBytes(report.uploadedBytes))")
        }
        if report.droppedRows > 0 { summary.append("\(report.droppedRows) row(s) dropped") }
        if !report.deletedAlbums.isEmpty {
            summary.append("\(report.deletedAlbums.count) album(s) deleted")
        }
        if !report.pulledAlbums.isEmpty {
            summary.append("\(report.pulledAlbums.count) album(s) pulled")
        }
        if !report.failures.isEmpty { summary.append("\(report.failures.count) failed") }
        if report.ignoredFiles > 0 { summary.append("\(report.ignoredFiles) ignored") }
        console.line("── " + summary.joined(separator: ", "))
    }
}
