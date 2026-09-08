import Foundation
import PhotosCore
import PhotosPipeline
import PhotosLibrary

/// Walks a library root, derives everything, and reports what came out beside what was
/// recorded. The walk lives here rather than in the pipeline because D owns walking — the
/// classifier takes a file list precisely so it can be tested without a filesystem.
enum Scan {

    /// INGEST.md's measured figures, as of the interview that revised them.
    ///
    /// These are the "previously measured sizes and counts" §10 asks the run to be checked
    /// against. They are the bar, not an aspiration: a large deviation means the pipeline is
    /// wrong, which is the whole reason this comparison is printed rather than left to
    /// someone's memory of what the numbers used to be.
    struct Expected {
        static let thumbnailKB = 11.6      // revised from 8.7 when the tier became a square crop
        static let previewKB = 370.0
        static let thumbnailTotalGB = 0.40
        static let previewTotalGB = 12.35
        static let libraryPhotoCount = 33_582
        static let libraryVideoCount = 550
    }

    static func run(root: URL, limit: Int?, albumFilter: String?, jobs: Int,
                    verbose: Bool) async throws {
        let started = Date()
        print("scanning \(root.path)")

        // One walk, one set of rules. `.photosignore` is applied here and nowhere else; the
        // classifier below never sees a file the library asked to ignore.
        let walker = try LibraryWalker(root: root)
        let contents = walker.walk()
        if contents.rules.isEmpty {
            print("no \(IgnoreRules.filename) — nothing is excluded")
        }
        let albums = contents.albums.filter { album in
            guard let albumFilter else { return true }
            return album.relativePath.localizedCaseInsensitiveContains(albumFilter)
        }
        print("\(albums.count) directories with media\n")

        let work = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("photos-scan-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: work, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: work) }

        let pipeline = Pipeline(workDirectory: work)
        let classifier = MediaClassifier()
        let totals = Totals()

        var remaining = limit
        var allItems: [MediaItem] = []
        var allSkipped: [SkippedFile] = []

        for album in albums {
            let classified = classifier.classify(album.files)
            allSkipped.append(contentsOf: classified.skipped)

            for item in classified.items {
                if let left = remaining, allItems.count >= left { break }
                allItems.append(item)
            }
            if let left = remaining, allItems.count >= left { remaining = 0; break }
        }

        print("\(allItems.count) media files, \(allSkipped.count) skipped\n")

        // Progress, from the pipeline's own event stream. A full-library run is tens of
        // minutes; without this it is tens of minutes of silence, and there is no way to tell
        // a slow run from a wedged one.
        // Bound to a `let` before the task captures it: an escaping closure over a mutable
        // local is not Sendable, and the count is fixed by this point anyway.
        let total = allItems.count
        let progress = Task {
            var done = 0
            let started = Date()
            for await event in pipeline.events {
                guard case .finished = event else { continue }
                done += 1
                if done % 100 == 0 || done == total {
                    let elapsed = Date().timeIntervalSince(started)
                    let rate = elapsed > 0 ? Double(done) / elapsed : 0
                    let remaining = rate > 0 ? Double(total - done) / rate : 0
                    FileHandle.standardError.write(Data(
                        String(format: "\r  %d/%d  %.1f files/s  ~%.0f s left      ",
                               done, total, rate, remaining).utf8))
                }
            }
            FileHandle.standardError.write(Data("\r\u{1B}[K".utf8))
        }

        // One worker per core, on real threads rather than in a TaskGroup.
        //
        // `derive` is synchronous and spends its whole life inside C. Swift's cooperative
        // pool is sized to the core count, so sixteen blocking tasks occupy every thread it
        // has and nothing else async ever runs — which is exactly why the progress reporter
        // above printed nothing at all on a 22-minute run while its event stream quietly
        // overflowed. Blocking work does not belong on that pool.
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            let queue = DispatchQueue(label: "photos-scan.workers", attributes: .concurrent)
            let group = DispatchGroup()
            for chunk in roundRobin(allItems, into: jobs) {
                queue.async(group: group) {
                    for item in chunk {
                        do {
                            let derived = try pipeline.derive(item)
                            totals.recordBlocking(item: item, derived: derived)
                        } catch {
                            totals.recordFailureBlocking(item: item, message: "\(error)")
                        }
                    }
                }
            }
            group.notify(queue: .global()) { continuation.resume() }
        }

        pipeline.finish()
        _ = await progress.result

        try await report(totals: totals, skipped: allSkipped, contents: contents,
                         verbose: verbose, elapsed: Date().timeIntervalSince(started))
    }

    static func percentOf(_ part: Int, _ whole: Int) -> String {
        whole > 0 ? String(format: "%.1f%%", Double(part) / Double(whole) * 100) : "—"
    }

    static func report(totals: Totals, skipped: [SkippedFile], contents: LibraryContents,
                       verbose: Bool, elapsed: TimeInterval) async throws {
        let snapshot = totals.snapshot()

        print(String(repeating: "─", count: 72))
        print("results")
        print(String(repeating: "─", count: 72))

        func row(_ label: String, _ measured: String, _ expected: String, _ delta: String) {
            print(label.padded(to: 26) + measured.padded(to: 15, alignRight: true)
                  + expected.padded(to: 15, alignRight: true)
                  + delta.padded(to: 13, alignRight: true))
        }
        row("", "measured", "INGEST.md", "delta")

        if snapshot.stillCount > 0 {
            let thumbKB = Double(snapshot.thumbnailBytes) / Double(snapshot.stillCount) / 1024
            let previewKB = Double(snapshot.previewBytes) / Double(snapshot.stillCount) / 1024
            row("thumbnail, average", fmt(thumbKB, "KB"), fmt(Expected.thumbnailKB, "KB"),
                percent(thumbKB, Expected.thumbnailKB))
            row("preview, average", fmt(previewKB, "KB"), fmt(Expected.previewKB, "KB"),
                percent(previewKB, Expected.previewKB))

            // Extrapolated to the whole library, which is what the storage budget is about.
            let scale = Double(Expected.libraryPhotoCount) / Double(snapshot.stillCount)
            let thumbGB = Double(snapshot.thumbnailBytes) * scale / 1e9
            let previewGB = Double(snapshot.previewBytes) * scale / 1e9
            row("thumbnails, projected", fmt(thumbGB, "GB"), fmt(Expected.thumbnailTotalGB, "GB"),
                percent(thumbGB, Expected.thumbnailTotalGB))
            row("previews, projected", fmt(previewGB, "GB"), fmt(Expected.previewTotalGB, "GB"),
                percent(previewGB, Expected.previewTotalGB))
        }

        print("")
        print("stills         \(snapshot.stillCount)")
        print("raw (CR2)      \(snapshot.rawCount)")
        print("live photos    \(snapshot.livePhotoCount)")
        print("videos         \(snapshot.videoCount)")
        print("failures       \(snapshot.failures.count)")
        print("skipped        \(skipped.count)")
        if !contents.rules.isEmpty {
            let pruned = contents.prunedDirectories.count
            print("ignored        \(contents.ignoredFiles.count) files"
                  + (pruned > 0 ? ", \(pruned) directories pruned" : "")
                  + " by \(contents.rules.rules.count) rules")
        }

        if snapshot.rawCount > 0 {
            let averageMB = Double(snapshot.rawOriginalBytes) / Double(snapshot.rawCount) / 1e6
            print("\ncarved CR2 JPEG, average \(fmt(averageMB, "MB")) "
                  + "(projected \(fmt(Double(snapshot.rawOriginalBytes) / Double(snapshot.rawCount) * 139 / 1e9, "GB")) for 139 files)")
        }
        if snapshot.videoCount > 0 {
            let averageMB = Double(snapshot.videoBytes) / Double(snapshot.videoCount) / 1e6
            print("transcoded video, average \(fmt(averageMB, "MB")) "
                  + "(projected \(fmt(Double(snapshot.videoBytes) / Double(snapshot.videoCount) * Double(Expected.libraryVideoCount) / 1e9, "GB")) for \(Expected.libraryVideoCount) files)")
        }
        if snapshot.withProfile > 0 {
            print("previews carrying an ICC profile  \(snapshot.withProfile)")
        }

        let perFile = snapshot.total > 0 ? elapsed / Double(snapshot.total) : 0
        print("\nelapsed \(fmt(elapsed, "s")) — \(String(format: "%.2f", perFile)) s/file")

        if !snapshot.failures.isEmpty {
            print("\nfailures (skipped and reported, never fatal — decision 15):")
            for failure in snapshot.failures.prefix(40) {
                print("  \(failure.name): \(failure.message)")
            }
            if snapshot.failures.count > 40 {
                print("  … and \(snapshot.failures.count - 40) more")
            }
        }

        // A rule that excluded nothing is either a typo or a leftover, and neither is
        // distinguishable from a working rule without being told.
        let unused = contents.unusedRules
        if !unused.isEmpty {
            print("\n\(IgnoreRules.filename): \(unused.count) rule(s) matched nothing:")
            for rule in unused { print("  line \(rule.line): \(rule.source)") }
        }
        if verbose && !contents.ignoredFiles.isEmpty {
            print("\nignored (\(contents.ignoredFiles.count)):")
            for url in contents.ignoredFiles { print("  \(url.lastPathComponent)") }
        }
        if verbose && !contents.prunedDirectories.isEmpty {
            print("\npruned directories:")
            for url in contents.prunedDirectories { print("  \(url.lastPathComponent)") }
        }

        let strays = skipped.filter {
            if case .consumedAsLivePhotoVideo = $0.reason { return false }
            return true
        }
        if !strays.isEmpty {
            print("\nstrays (\(strays.count)):")
            let shown = verbose ? strays : Array(strays.prefix(15))
            for stray in shown { print("  \(stray.reason) — \(stray.url.lastPathComponent)") }
            if !verbose && strays.count > shown.count {
                print("  … and \(strays.count - shown.count) more (--verbose to list)")
            }
        }
    }

    static func fmt(_ value: Double, _ unit: String) -> String {
        String(format: value >= 100 ? "%.0f %@" : "%.2f %@", value, unit)
    }

    static func percent(_ measured: Double, _ expected: Double) -> String {
        guard expected > 0 else { return "—" }
        let delta = (measured - expected) / expected * 100
        return String(format: "%+.1f%%", delta)
    }
}

/// Accumulates results across workers.
///
/// A lock rather than an actor: the workers are OS threads running blocking C, so they cannot
/// `await`, and the critical section is a handful of integer adds. An actor here would force
/// the very cooperative-pool hop that starved the progress reporter.
final class Totals: @unchecked Sendable {
    private let lock = NSLock()
    struct Failure { var name: String; var message: String }
    struct Snapshot {
        var stillCount = 0, rawCount = 0, videoCount = 0, livePhotoCount = 0
        var thumbnailBytes = 0, previewBytes = 0, videoBytes = 0, rawOriginalBytes = 0
        var withProfile = 0
        var failures: [Failure] = []
        var total: Int { stillCount + videoCount }
    }

    private var snap = Snapshot()

    func recordBlocking(item: MediaItem, derived: Derivatives) {
        lock.lock()
        defer { lock.unlock() }
        record(item: item, derived: derived)
    }

    func recordFailureBlocking(item: MediaItem, message: String) {
        lock.lock()
        defer { lock.unlock() }
        recordFailure(item: item, message: message)
    }

    func snapshot() -> Snapshot {
        lock.lock()
        defer { lock.unlock() }
        return snap
    }

    private func record(item: MediaItem, derived: Derivatives) {
        switch item.kind {
        case .still:
            snap.stillCount += 1
        case .raw:
            snap.stillCount += 1
            snap.rawCount += 1
            if case .data(let bytes) = derived.original { snap.rawOriginalBytes += bytes.count }
        case .livePhoto:
            snap.stillCount += 1
            snap.livePhotoCount += 1
        case .video:
            snap.videoCount += 1
            if let video = derived.video,
               let size = try? video.resourceValues(forKeys: [.fileSizeKey]).fileSize {
                snap.videoBytes += size
            }
            // The transcode is the pipeline's output, but the scan does not keep it.
            if let video = derived.video { try? FileManager.default.removeItem(at: video) }
        }
        snap.thumbnailBytes += derived.thumbnail.count
        snap.previewBytes += derived.preview.count
    }

    private func recordFailure(item: MediaItem, message: String) {
        snap.failures.append(Failure(name: item.filename, message: message))
    }
}

extension String {
    /// Column padding done in Swift rather than through `String(format: "%s", …)`, which on
    /// Linux would need a C string pointer borrowed from a temporary bridged object — correct
    /// by luck rather than by lifetime.
    func padded(to width: Int, alignRight: Bool = false) -> String {
        let short = max(0, width - count)
        return alignRight ? String(repeating: " ", count: short) + self
                          : self + String(repeating: " ", count: short)
    }
}

/// Round-robin split, so one worker does not receive every large file in a row.
///
/// A free function rather than an `Array` extension: inside `extension Array`, a bare
/// `Array(...)` resolves to `Self`, and building an array *of arrays* there reads as a type
/// error rather than as what it is.
func roundRobin<Element>(_ items: [Element], into buckets: Int) -> [[Element]] {
    if items.isEmpty { return [] }
    guard buckets > 1, items.count > buckets else { return [items] }
    var result: [[Element]] = .init(repeating: [], count: buckets)
    for (offset, element) in items.enumerated() { result[offset % buckets].append(element) }
    return result.filter { !$0.isEmpty }
}
