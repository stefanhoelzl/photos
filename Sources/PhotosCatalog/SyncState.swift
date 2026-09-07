import Foundation

/// The ETag of every shard currently on disk (§4).
///
/// A separate file from `merged.db` on purpose. If the ETags lived in the merged database,
/// losing it would mean re-fetching all 288 shards; here it stays purely derived and can be
/// deleted and rebuilt with no network. Deleting `sync_state.db` on its own forces a full
/// re-fetch, which is a free repair path.
///
/// Not `Sendable`, like every other connection here: `CatalogSync` owns one inside its actor.
final class SyncState {
    private let database: Database

    init(path: URL) throws {
        try FileManager.default.createDirectory(
            at: path.deletingLastPathComponent(), withIntermediateDirectories: true
        )
        self.database = try Database(path: path.path)
        try database.execute(CatalogSchema.syncStateDDL)
    }

    /// album id → the ETag of the shard on disk.
    func known() throws -> [UUID: String] {
        let rows = try database.query("SELECT album_id, etag FROM shard_state") {
            ($0.uuidOrNil(0), $0.string(1))
        }
        return Dictionary(uniqueKeysWithValues: rows.compactMap { id, etag in id.map { ($0, etag) } })
    }

    /// Recorded only once the shard is on disk, so a crash between the two leaves the state
    /// behind reality rather than ahead of it — which costs one re-download, not a missing
    /// album.
    func record(_ albumID: UUID, etag: String, at date: Date = Date()) throws {
        try database.run(
            "INSERT INTO shard_state (album_id, etag, fetched_at) VALUES (?, ?, ?) "
            + "ON CONFLICT(album_id) DO UPDATE SET etag = excluded.etag, fetched_at = excluded.fetched_at",
            [.text(albumID.catalogString), .text(etag), .integer(Int64(date.timeIntervalSince1970))]
        )
    }

    func forget(_ albumID: UUID) throws {
        try database.run("DELETE FROM shard_state WHERE album_id = ?", [.text(albumID.catalogString)])
    }
}

/// What one LIST implies for the local copy.
public struct ShardDiff: Hashable, Sendable {
    /// Shards to download: new, or whose ETag moved.
    public var changed: [UUID: String]
    /// Albums whose key is gone from the zone. §4: absent means deleted.
    public var deleted: [UUID]
    /// Keys the LIST returned that are not shards — the `meta/` directory marker, or
    /// anything whose name is not a uuid. Skipped, never guessed at.
    public var ignoredKeys: [String]

    public var isEmpty: Bool { changed.isEmpty && deleted.isEmpty }

    public init(changed: [UUID: String] = [:], deleted: [UUID] = [], ignoredKeys: [String] = []) {
        self.changed = changed
        self.deleted = deleted
        self.ignoredKeys = ignoredKeys
    }
}
