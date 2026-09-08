import Foundation
import PhotosCatalog
import PhotosCore
import PhotosLibrary
import PhotosPipeline
import PhotosStorage

/// One run of `photos-cli sync`.
///
/// The shape §7 asks for: walk the library, refresh the catalog, reconcile, then act —
/// uploads first, the shard last, always. The shard is the commit point, so a crash costs
/// at most the album in flight and the next run's LIST sees it as absent and redoes it.
/// Nothing here keeps state between runs beyond the shard cache, which is why it is
/// self-correcting.
public actor Ingest {

    let config: IngestConfig
    let s3: S3Client
    let catalog: CatalogSync
    let pipeline: Pipeline
    let classifier: MediaClassifier
    let pool = DerivePool()
    let uploadPermits: AsyncSemaphore
    let progress: (@Sendable (String) -> Void)?

    public init(config: IngestConfig, s3: S3Client,
                backend: (any ImageBackend)? = nil,
                progress: (@Sendable (String) -> Void)? = nil) throws {
        self.config = config
        self.s3 = s3
        self.catalog = try CatalogSync(s3: s3, cacheRoot: config.cacheRoot)
        let backend = backend ?? NativeImageBackend()
        self.pipeline = Pipeline(workDirectory: config.workRoot, backend: backend)
        self.classifier = MediaClassifier(backend: backend)
        self.uploadPermits = AsyncSemaphore(limit: config.uploadJobs)
        self.progress = progress
    }

    // MARK: - The run

    public func run() async throws -> IngestReport {
        var report = IngestReport()
        report.dryRun = config.dryRun

        // The one structural guard. `.photosignore` is the marker that says this directory
        // is a library root, so an unmounted disk and a mistyped root both fail here rather
        // than looking like a library whose every album was deleted (§7).
        let rules = try loadRules()

        let walker = LibraryWalker(root: config.libraryRoot, rules: rules)
        let contents = walker.walk()
        report.unusedRules = contents.unusedRules
        report.ignoredFiles = contents.ignoredFiles.count

        let refreshed = try await catalog.refresh()
        report.listedShards = refreshed.shards.count + refreshed.report.unreadableShards.count
        report.fetchedShards = refreshed.report.fetchedShards.count
        report.duplicateNames = refreshed.report.duplicateNames
        report.orphanedAlbums = refreshed.report.orphanedAlbums

        let reconciler = Reconciler(root: config.libraryRoot, albumFilter: config.albumFilter)
        let plan = reconciler.plan(contents: contents, shards: refreshed.shards,
                                   unreadable: refreshed.report.unreadableShards)

        // §7 asserts images never change on disk, and checks the assertion. A mismatch
        // means the library broke its contract, so nothing at all is written this run.
        guard plan.mismatches.isEmpty else { throw IngestAbort.fileChanged(plan.mismatches) }

        report.blockedByUnreadable = plan.blockedByUnreadable
        report.looseRootFiles = plan.looseRootFiles.count
        for album in plan.albums where album.mixedFileCount > 0 {
            report.mixedFolders.append((album.sourcePath, album.mixedFileCount))
        }

        if config.dryRun {
            fillDryRun(&report, plan: plan, etags: refreshed.etags)
            try await sweep(&report, shards: refreshed.shards,
                            unreadable: refreshed.report.unreadableShards, dryRun: true)
            return report
        }

        // §7 promises a no-op run is one LIST. It stays true only if a run with nothing to
        // do also skips the sweep — and it can: debris appears when something writes, so a
        // run where neither the zone nor the library moved cannot have produced any. The
        // phone's crash debris waits for the next run that does something, which costs
        // nothing but a week of $0.01/GB.
        guard refreshed.changed || plan.hasWork else { return report }

        try FileManager.default.createDirectory(at: config.workRoot,
                                                withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: config.workRoot) }

        for album in plan.albums where album.needsWrite {
            await commit(album, etags: refreshed.etags, into: &report)
        }
        for deletion in plan.deletions {
            await delete(deletion, into: &report)
        }
        for pull in plan.pulls {
            await archive(pull, etags: refreshed.etags, into: &report)
        }

        // The sweep reads every shard now on disk, so it must run after the writes above.
        let after = try await catalog.refresh()
        try await sweep(&report, shards: after.shards,
                        unreadable: after.report.unreadableShards, dryRun: false)

        return report
    }

    /// Reads `$LIBRARY_ROOT/.photosignore`, and refuses the run without it.
    ///
    /// §7 used to say a missing file means no exclusions, silently. It cannot: the file is
    /// also what proves the directory is the library, and this run deletes albums whose
    /// directory is gone. A library that genuinely wants no exclusions writes an empty file.
    func loadRules() throws -> IgnoreRules {
        let marker = config.libraryRoot.appending(path: IgnoreRules.filename)
        guard FileManager.default.fileExists(atPath: marker.path) else {
            throw IngestAbort.notALibraryRoot(config.libraryRoot,
                                              detail: "no \(IgnoreRules.filename)")
        }
        do {
            return try IgnoreRules.load(forLibraryAt: config.libraryRoot)
        } catch {
            throw IngestAbort.notALibraryRoot(config.libraryRoot, detail: "\(error)")
        }
    }

    func fillDryRun(_ report: inout IngestReport, plan: IngestPlan, etags: [UUID: String]) {
        for album in plan.albums where album.needsWrite {
            let bytes = album.uploads.reduce(Int64(0)) { total, url in
                total + (Body.file(url).byteCount ?? 0)
            }
            report.albums.append(.init(path: album.sourcePath, uploaded: album.uploads.count,
                                       dropped: album.drop.count, bytes: bytes,
                                       created: album.isNew, duration: 0))
        }
        for deletion in plan.deletions {
            report.deletedAlbums.append((deletion.sourcePath, deletion.shard.photos.count))
        }
        for pull in plan.pulls {
            report.pulledAlbums.append((pull.sourcePath, pull.shard.photos.count, 0))
        }
    }

    // MARK: - One album

    func commit(_ album: AlbumPlan, etags: [UUID: String], into report: inout IngestReport) async {
        let started = Date()
        var outcome = IngestReport.AlbumOutcome(
            path: album.sourcePath, uploaded: 0, dropped: album.drop.count,
            bytes: 0, created: album.isNew, duration: 0
        )

        // The whole album, not just the new files: pairing is a property of the set. A
        // Live Photo's MOV carries no row, so classifying the unclaimed files alone would
        // present it as an ordinary video and transcode it all over again.
        let claimed = Set(album.files.map(\.lastPathComponent))
            .subtracting(album.uploads.map(\.lastPathComponent))
        let classified = classifier.classify(album.files)
        for skipped in classified.skipped
        where skipped.reason == .unrecognisedFormat && !claimed.contains(skipped.url.lastPathComponent) {
            report.strays.append(.init(path: relative(skipped.url), message: "unrecognised format"))
        }

        // §3: two rows in one album may not claim the same name. It can only happen when a
        // derivative renames its source onto a sibling — an `a.CR2` beside an `a.jpg`.
        let existingNames = Set(album.keep.map { $0.filename })
        var planned = existingNames
        var items: [MediaItem] = []
        for item in classified.items where !claimed.contains(item.url.lastPathComponent) {
            let name = predictedName(for: item)
            if planned.contains(name) {
                report.failures.append(.init(
                    path: relative(item.url),
                    message: "would claim the name \(name), which another photo in this album already has"
                ))
                continue
            }
            planned.insert(name)
            items.append(item)
        }

        var produced: [Produced] = []
        do {
            produced = try await derive(items, album: album, report: &report)
        } catch {
            report.failures.append(.init(path: album.sourcePath, message: "\(error)"))
            return
        }
        outcome.uploaded = produced.count
        outcome.bytes = produced.reduce(0) { $0 + $1.uploadedBytes }



        do {
            let rows = album.keep + produced.map(\.row)
            let thumbs = try await packThumbnails(album: album, produced: produced, rows: rows)
            let info = albumInfo(album, thumbsID: thumbs.id)
            let shard = Shard(info: info, photos: rows)

            let etag = etags[album.id].map { ETag(unquoted: $0) }
            switch try await catalog.writeShard(shard, ifMatch: album.isNew ? nil : etag) {
            case .written:
                break
            case .staleETag:
                // §2: someone else wrote it. Re-read, re-decide against what actually
                // landed, and try once more — a blind retry would overwrite their work.
                guard try await rewriteAfterConflict(album, produced: produced) else {
                    report.contendedAlbums.append(album.sourcePath)
                    return
                }
            }

            // Only now that the shard no longer points at them: dropped rows' blobs, and
            // the pack the album used to have. The reverse order would leave the catalog
            // naming objects that are gone (§2).
            for row in album.drop { await deleteBlobs(row.objectIDs) }
            if let old = album.existing?.info.thumbsID, old != thumbs.id {
                await deleteBlobs([old])
            }
        } catch {
            report.failures.append(.init(path: album.sourcePath, message: "\(error)"))
            return
        }

        outcome.duration = Date().timeIntervalSince(started)
        report.albums.append(outcome)
        progress?(line(for: outcome))
    }

    /// What a derived row will be called, so a name collision is caught before the CPU is
    /// spent rather than after.
    func predictedName(for item: MediaItem) -> String {
        switch item.kind {
        case .raw: Pipeline.renaming(item.filename, to: "jpg")
        case .video: Pipeline.renaming(item.filename, to: "mp4")
        case .still, .livePhoto: item.filename
        }
    }

    struct Produced: Sendable {
        var row: PhotoRow
        var thumbnail: Data
        var uploadedBytes: Int64
    }

    /// Derives and uploads every item, `jobs` in flight.
    ///
    /// The window *is* the backpressure: a worker cannot start a new encode until its own
    /// upload has finished, so at most `jobs` derivatives are ever staged — a few megabytes,
    /// rather than the 100 GB an unbounded producer would put on disk (§7).
    func derive(_ items: [MediaItem], album: AlbumPlan,
                report: inout IngestReport) async throws -> [Produced] {
        guard !items.isEmpty else { return [] }
        var produced: [Produced] = []
        var failures: [IngestReport.Failure] = []

        await withTaskGroup(of: Result<Produced, ItemError>.self) { group in
            var next = 0
            func start() {
                guard next < items.count else { return }
                let item = items[next]
                next += 1
                group.addTask { [self] in
                    do {
                        return .success(try await process(item))
                    } catch {
                        return .failure(ItemError(url: item.url, message: "\(error)"))
                    }
                }
            }
            for _ in 0..<min(config.jobs, items.count) { start() }
            while let result = await group.next() {
                switch result {
                case .success(let value): produced.append(value)
                case .failure(let error):
                    failures.append(.init(path: relative(error.url), message: error.message))
                }
                start()
            }
        }

        report.failures.append(contentsOf: failures)
        // Deterministic, so two runs over the same album write the same shard.
        produced.sort { $0.row.filename < $1.row.filename }
        return produced
    }

    struct ItemError: Error, Sendable {
        var url: URL
        var message: String
    }

    /// Derive one item, then upload every blob it owns. Blobs before the shard, always.
    func process(_ item: MediaItem) async throws -> Produced {
        let derived = try await pool.run { [pipeline] in try pipeline.derive(item) }
        var row = derived.row
        var bytes: Int64 = 0

        switch derived.original {
        case .file(let url):
            let id = UUID()
            bytes += try await upload(StorageKey.blob(id), body: .file(url))
            row.originalID = id
        case .data(let data):
            let id = UUID()
            bytes += try await upload(StorageKey.blob(id), body: .data(data))
            row.originalID = id
        case .none:
            break
        }

        if let video = derived.video {
            let id = UUID()
            bytes += try await upload(StorageKey.blob(id), body: .file(video))
            row.videoID = id
            try? FileManager.default.removeItem(at: video)
        }
        if let liveVideo = derived.liveVideo {
            let id = UUID()
            bytes += try await upload(StorageKey.blob(id), body: .file(liveVideo))
            row.liveVideoID = id
        }

        let previewID = UUID()
        bytes += try await upload(StorageKey.blob(previewID), body: .data(derived.preview))
        row.previewID = previewID

        return Produced(row: row, thumbnail: derived.thumbnail, uploadedBytes: bytes)
    }

    @discardableResult
    func upload(_ key: String, body: Body) async throws -> Int64 {
        try await uploadPermits.withPermit {
            _ = try await s3.put(key, body: body)
            return body.byteCount ?? 0
        }
    }

    // MARK: - Thumbnails

    /// The album's pack, rebuilt.
    ///
    /// §3 stores one blob per album, so adding a photo means repacking. The thumbnails
    /// already up there come back down rather than being re-derived: 20 MB at 7.5 MB/s for
    /// the largest album here, against about twelve minutes of CPU to re-encode 1,754
    /// thumbnails that were already correct.
    func packThumbnails(album: AlbumPlan, produced: [Produced],
                        rows: [PhotoRow]) async throws -> (id: UUID?, uploaded: Bool) {
        guard !rows.isEmpty else { return (nil, false) }
        guard !produced.isEmpty || !album.drop.isEmpty else {
            return (album.existing?.info.thumbsID, false)
        }

        var thumbnails: [UUID: Data] = [:]
        if let existingID = album.existing?.info.thumbsID, !album.keep.isEmpty {
            if let data = try await s3.get(StorageKey.blob(existingID)).data {
                let kept = Set(album.keep.map(\.id))
                for (id, jpeg) in try ThumbPack.unpack(data) where kept.contains(id) {
                    thumbnails[id] = jpeg
                }
            }
        }
        for item in produced { thumbnails[item.row.id] = item.thumbnail }
        guard !thumbnails.isEmpty else { return (nil, false) }

        let id = UUID()
        try await upload(StorageKey.blob(id), body: .data(try ThumbPack.pack(thumbnails)))
        return (id, true)
    }

    func albumInfo(_ album: AlbumPlan, thumbsID: UUID?) -> AlbumInfo {
        var info = album.existing?.info ?? AlbumInfo(id: album.id, name: album.name)
        info.name = album.name
        info.parent = album.parent
        info.sourcePath = album.sourcePath
        info.thumbsID = thumbsID
        info.schemaVersion = CatalogSchema.version
        // A cover pointing at a photo that is gone would resolve to nothing; §3's default
        // (the album's earliest photo) is correct again once it is cleared.
        if let cover = info.coverPhotoID, !album.keep.contains(where: { $0.id == cover }) {
            info.coverPhotoID = nil
        }
        return info
    }

    /// The second attempt after a 412, against the shard that actually landed.
    func rewriteAfterConflict(_ album: AlbumPlan, produced: [Produced]) async throws -> Bool {
        let fresh = try await catalog.reload(album.id)
        let mine = produced.map(\.row)
        let taken = Set(mine.map(\.filename))
        // Their rows survive; ours are added. Merge is concatenation (§2) — the only rows
        // dropped are ones whose file we just proved is gone.
        let goneNames = Set(album.drop.map(\.filename))
        let theirs = fresh.photos.filter { !taken.contains($0.filename) && !goneNames.contains($0.filename) }

        var info = fresh.info
        info.sourcePath = album.sourcePath
        info.name = album.name
        info.parent = album.parent
        let shard = Shard(info: info, photos: theirs + mine)
        let etag = try await catalog.etag(of: album.id)
        if case .written = try await catalog.writeShard(shard, ifMatch: etag) { return true }
        return false
    }

    // MARK: - Deleting an album

    func delete(_ deletion: AlbumDeletion, into report: inout IngestReport) async {
        do {
            // Shard first: the album stops existing before its objects do, so the catalog
            // never names a blob that is gone (§2).
            try await catalog.deleteShard(deletion.shard.info.id)
            await deleteBlobs(deletion.shard.objectIDs)
            report.deletedAlbums.append((deletion.sourcePath, deletion.shard.photos.count))
            progress?("- \(deletion.sourcePath)  \(deletion.shard.photos.count) photos deleted")
        } catch {
            report.failures.append(.init(path: deletion.sourcePath, message: "\(error)"))
        }
    }

    /// A failed delete is swallowed on purpose: the shard no longer references the object,
    /// so it is now debris, and the sweep collects debris once it is a week old. Failing the
    /// album over an object nothing points at would be worse than leaving it.
    func deleteBlobs(_ ids: [UUID]) async {
        for id in ids {
            try? await s3.delete(StorageKey.blob(id))
        }
    }

    // MARK: - Pulling a phone album down

    /// §7's archive-only pull: copy the album into `$LIBRARY_ROOT` and claim it by writing
    /// `source_path`. That is metadata, not the objects the laptop must not rewrite — and
    /// once claimed it is an ordinary album, deletable like any other.
    ///
    /// `.photosignore` is not consulted: the rules govern what goes up (§7).
    func archive(_ pull: PullPlan, etags: [UUID: String], into report: inout IngestReport) async {
        let directory = config.libraryRoot.appending(path: pull.sourcePath)
        var files = 0
        var bytes: Int64 = 0
        do {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            for row in pull.shard.photos {
                guard let primary = row.originalID ?? row.videoID else { continue }
                let destination = directory.appending(path: row.filename)
                if !FileManager.default.fileExists(atPath: destination.path) {
                    try await s3.download(StorageKey.blob(primary), to: destination)
                }
                files += 1
                bytes += row.bytes ?? 0
                // The paired MOV has no name of its own in the catalog. `<stem>.MOV` is the
                // convention every pair in this library follows, and pairing is by content
                // identifier rather than by name, so the walker re-pairs it either way.
                if let live = row.liveVideoID {
                    let name = Pipeline.renaming(row.filename, to: "MOV")
                    let path = directory.appending(path: name)
                    if !FileManager.default.fileExists(atPath: path.path) {
                        try await s3.download(StorageKey.blob(live), to: path)
                    }
                    files += 1
                }
            }

            var info = pull.shard.info
            info.sourcePath = pull.sourcePath
            let etag = etags[info.id].map { ETag(unquoted: $0) }
            let shard = Shard(info: info, photos: pull.shard.photos)
            if case .staleETag = try await catalog.writeShard(shard, ifMatch: etag) {
                report.contendedAlbums.append(pull.sourcePath)
                return
            }
            report.pulledAlbums.append((pull.sourcePath, files, bytes))
            progress?("v \(pull.sourcePath)  \(files) files pulled")
        } catch {
            report.failures.append(.init(path: pull.sourcePath, message: "\(error)"))
        }
    }

    // MARK: - The orphan sweep

    /// Deletes blobs no shard references and that are older than the age floor.
    ///
    /// Skipped entirely when any shard is unreadable: the referenced set would then be
    /// missing whatever that album owns, and the sweep would delete a readable album's
    /// photographs on the strength of a shard it could not open.
    func sweep(_ report: inout IngestReport, shards: [Shard], unreadable: [ShardProbe],
               dryRun: Bool) async throws {
        guard unreadable.isEmpty else {
            report.sweepSkipped = "\(unreadable.count) shard(s) too new to read"
            return
        }
        guard config.albumFilter == nil else {
            report.sweepSkipped = "--album restricts this run"
            return
        }

        let referenced = Set(shards.flatMap(\.objectIDs))
        let floor = Date().addingTimeInterval(-config.sweepAge)

        for try await object in s3.list(prefix: StorageKey.blobPrefix) {
            guard !object.isDirectoryMarker,
                  let id = StorageKey.objectID(fromBlobKey: object.key),
                  !referenced.contains(id) else { continue }
            guard let modified = object.lastModified, modified < floor else {
                report.youngUnreferencedBlobs += 1
                continue
            }
            report.sweptBlobs += 1
            report.sweptBytes += object.size
            if !dryRun { try await s3.delete(object.key) }
        }
    }

    // MARK: - Helpers

    func relative(_ url: URL) -> String {
        let root = config.libraryRoot.standardizedFileURL.path
        let path = url.standardizedFileURL.path
        guard path.hasPrefix(root) else { return path }
        return String(path.dropFirst(root.count).drop(while: { $0 == "/" }))
    }

    func line(for outcome: IngestReport.AlbumOutcome) -> String {
        var parts = ["\(outcome.created ? "+" : "~") \(outcome.sourcePathDisplay)"]
        if outcome.uploaded > 0 { parts.append("\(outcome.uploaded) photos") }
        if outcome.dropped > 0 { parts.append("-\(outcome.dropped) rows") }
        if outcome.bytes > 0 { parts.append(formatBytes(outcome.bytes)) }
        parts.append(String(format: "%.1fs", outcome.duration))
        return parts.joined(separator: "  ")
    }
}

extension IngestReport.AlbumOutcome {
    var sourcePathDisplay: String { path.isEmpty ? "." : path }
}

/// Human byte sizes, in the units the design's own numbers are quoted in.
public func formatBytes(_ bytes: Int64) -> String {
    let units: [(Double, String)] = [(1_073_741_824, "GB"), (1_048_576, "MB"), (1024, "KB")]
    for (scale, suffix) in units where Double(bytes) >= scale {
        return String(format: "%.1f %@", Double(bytes) / scale, suffix)
    }
    return "\(bytes) B"
}
