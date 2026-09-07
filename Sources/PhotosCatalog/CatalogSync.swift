import Foundation
import PhotosStorage

/// What a sync did, and what it could not make sense of.
///
/// The design's stance is that conflicts are **reported, not resolved** (§4): every field
/// below the first four is a condition deliberately left for a person to decide about.
public struct SyncReport: Hashable, Sendable {
    public var fetchedShards: [UUID] = []
    public var deletedAlbums: [UUID] = []
    public var bytesFetched: Int64 = 0
    /// False when the LIST matched what was already on disk, so nothing was replayed.
    public var rebuilt: Bool = false

    public var albums: Int = 0
    public var photos: Int = 0

    /// Shards written by a newer schema than this build understands. Skipped.
    ///
    /// A caller reconciling a local library against the zone must treat these as
    /// **unreadable, not absent** — an absent album gets re-uploaded as a new one, and with
    /// UUID keys nothing collides to stop the duplicate (§3).
    public var unreadableShards: [UUID] = []
    /// Albums whose `parent` named a shard that is not present. Surfaced at the root.
    public var orphanedAlbums: [UUID] = []
    /// Names appearing more than once under one parent. Shown, never merged (§2).
    public var duplicateNames: [String] = []
    /// LIST entries that were not shards: the `meta/` directory marker, or a key whose
    /// name is not a uuid.
    public var ignoredKeys: [String] = []

    public init() {}

    /// True when a person should look at something.
    public var hasAnomalies: Bool {
        !unreadableShards.isEmpty || !orphanedAlbums.isEmpty || !duplicateNames.isEmpty
    }
}

/// §4's sync loop, end to end.
///
/// Lives here rather than in the CLI and the app separately: the ingest tool and the phone
/// running slightly different versions of marker filtering, ETag diffing or delete-means-gone
/// is exactly the laptop/phone disagreement §7 says the shared package exists to prevent.
///
/// Talks to `S3Client` directly. A's injectable `HTTPTransport` is already the seam, so
/// tests drive this offline with canned `<ListBucketResult>` XML rather than through a
/// second protocol of its own.
public actor CatalogSync {

    public let s3: S3Client
    /// Holds `sync_state.db`, `shards/`, `merged.db` and `blobs/` (§4).
    public let cacheRoot: URL

    private let state: SyncState
    private var writer: CatalogWriter

    public init(s3: S3Client, cacheRoot: URL) throws {
        self.s3 = s3
        self.cacheRoot = cacheRoot
        try FileManager.default.createDirectory(at: cacheRoot.appending(path: "shards"),
                                                withIntermediateDirectories: true)
        self.state = try SyncState(path: cacheRoot.appending(path: "sync_state.db"))
        self.writer = try CatalogWriter(path: cacheRoot.appending(path: "merged.db"))
    }

    public var mergedPath: URL { cacheRoot.appending(path: "merged.db") }

    /// §4 says `merged.db` can be deleted at any moment and rebuilt. If it is deleted while
    /// this connection is open, the handle still points at the unlinked inode — the rebuild
    /// would succeed into a file that no longer has a name, and the catalog would silently
    /// fail to reappear. So the file's existence is checked before every rebuild, not only
    /// at startup.
    private func ensureWriter() throws {
        guard !FileManager.default.fileExists(atPath: mergedPath.path) else { return }
        writer = try CatalogWriter(path: mergedPath)
    }

    func shardPath(_ albumID: UUID) -> URL {
        cacheRoot.appending(path: "shards").appending(path: "\(albumID.catalogString).db")
    }

    // MARK: - The loop

    /// LIST, diff, fetch, rebuild, record.
    ///
    /// One LIST covers thumbnails and every derivative too: a pack is a blob referenced by
    /// `AlbumInfo.thumbsID`, so nothing in the zone can change without some shard's ETag
    /// moving (§4).
    @discardableResult
    public func sync() async throws -> SyncReport {
        var report = SyncReport()

        let diff = try await plan()
        report.ignoredKeys = diff.ignoredKeys

        for (albumID, etag) in diff.changed {
            let data = try await fetchShard(albumID)
            report.bytesFetched += Int64(data.count)
            try data.write(to: shardPath(albumID), options: .atomic)
            // Recorded only once the file has landed, so a crash leaves the state behind
            // reality rather than ahead of it — one re-download, not a missing album.
            try state.record(albumID, etag: etag)
            report.fetchedShards.append(albumID)
        }

        for albumID in diff.deleted {
            try? FileManager.default.removeItem(at: shardPath(albumID))
            try state.forget(albumID)
            report.deletedAlbums.append(albumID)
        }

        guard !diff.isEmpty else { return report }

        let (shards, unreadable) = try loadShards()
        report.unreadableShards = unreadable

        try ensureWriter()
        let summary = try writer.rebuild(from: shards)
        report.rebuilt = true
        report.albums = summary.albums
        report.photos = summary.photos
        report.orphanedAlbums = summary.orphanedAlbums
        report.duplicateNames = try duplicateNames()

        return report
    }

    /// The single LIST, diffed against what is on disk. The whole sync plan (§4).
    public func plan() async throws -> ShardDiff {
        let known = try state.known()
        var diff = ShardDiff()
        var seen: Set<UUID> = []

        for object in try await s3.list(prefix: StorageKey.metaPrefix).collect() {
            // bunny.net still materialises the `meta/` marker itself — one per prefix now
            // that keys do not nest, but LIST returns it and it is not a shard (§2).
            if object.isDirectoryMarker {
                diff.ignoredKeys.append(object.key)
                continue
            }
            guard let albumID = StorageKey.albumID(fromShardKey: object.key) else {
                diff.ignoredKeys.append(object.key)
                continue
            }
            guard let etag = object.etag?.value else {
                // No ETag means nothing to diff against, so the shard cannot be trusted to
                // be current. Skipped rather than guessed at.
                diff.ignoredKeys.append(object.key)
                continue
            }
            seen.insert(albumID)

            let onDisk = known[albumID]
            let present = FileManager.default.fileExists(atPath: shardPath(albumID).path)
            if onDisk != etag || !present { diff.changed[albumID] = etag }
        }

        // A key that vanished means the album was deleted (§4). There is no manifest to
        // consult, so absence *is* the signal.
        diff.deleted = known.keys.filter { !seen.contains($0) }.sorted { $0.catalogString < $1.catalogString }
        return diff
    }

    private func fetchShard(_ albumID: UUID) async throws -> Data {
        let result = try await s3.get(StorageKey.shard(albumID))
        guard let data = result.data else {
            throw CatalogSyncError.shardUnavailable(albumID)
        }
        return data
    }

    /// Reads every shard on disk. A shard too new to read is skipped and named, never
    /// treated as absent.
    private func loadShards() throws -> (shards: [Shard], unreadable: [UUID]) {
        let directory = cacheRoot.appending(path: "shards")
        let files = (try? FileManager.default.contentsOfDirectory(
            at: directory, includingPropertiesForKeys: nil
        )) ?? []

        var shards: [Shard] = []
        var unreadable: [UUID] = []

        for file in files.sorted(by: { $0.lastPathComponent < $1.lastPathComponent })
        where file.pathExtension == "db" {
            let name = file.deletingPathExtension().lastPathComponent
            guard let albumID = UUID(uuidString: name) else { continue }
            do {
                shards.append(try ShardReader.read(try Data(contentsOf: file)))
            } catch ShardError.unsupportedVersion {
                unreadable.append(albumID)
            }
        }
        return (shards, unreadable)
    }

    /// Asked of the merged database rather than recomputed from shards: the rule for what
    /// counts as a duplicate lives in exactly one place, and it has just been rebuilt.
    private func duplicateNames() throws -> [String] {
        try CatalogReader(path: mergedPath).duplicateNames().map(\.name)
    }

    // MARK: - Writing a shard back

    /// Uploads a shard, guarded by `If-Match` (§2's single-owner rule).
    ///
    /// A 412 means another device wrote it first. That is returned rather than retried:
    /// re-deciding what the shard should contain is the caller's business, not this
    /// method's, and a blind retry would overwrite the other device's work.
    public func writeShard(_ shard: Shard, ifMatch etag: ETag?) async throws -> ShardWriteResult {
        let data = try ShardWriter.write(shard)
        let result = try await s3.put(
            StorageKey.shard(shard.info.id), body: .data(data), ifMatch: etag
        )
        switch result {
        case .staleETag:
            return .staleETag
        case .written(let newETag):
            if let newETag { try state.record(shard.info.id, etag: newETag.value) }
            try data.write(to: shardPath(shard.info.id), options: .atomic)
            return .written(newETag)
        }
    }

    public enum ShardWriteResult: Sendable {
        case written(ETag?)
        /// Someone else wrote this shard. Re-read it, re-decide, and try again.
        case staleETag
    }

    // MARK: - Local state

    /// Drops the merged database's contents and replays every shard on disk. No network.
    ///
    /// This is the repair path §4 says the UI does not need a button for — nothing can need
    /// it, because the merged DB is derived.
    @discardableResult
    public func rebuildFromDisk() throws -> SyncReport {
        var report = SyncReport()
        let (shards, unreadable) = try loadShards()
        try ensureWriter()
        let summary = try writer.rebuild(from: shards)
        report.rebuilt = true
        report.unreadableShards = unreadable
        report.albums = summary.albums
        report.photos = summary.photos
        report.orphanedAlbums = summary.orphanedAlbums
        report.duplicateNames = try duplicateNames()
        return report
    }

    /// Every blob id the catalog references, for `--prune`'s orphan sweep (§7).
    public func referencedObjectIDs() throws -> Set<UUID> {
        let (shards, _) = try loadShards()
        return Set(shards.flatMap(\.objectIDs))
    }
}

public enum CatalogSyncError: Error, Hashable, Sendable, CustomStringConvertible {
    case shardUnavailable(UUID)

    public var description: String {
        switch self {
        case .shardUnavailable(let id): "shard \(id.catalogString) listed but could not be fetched"
        }
    }
}
