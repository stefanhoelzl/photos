import Foundation
import Testing
@testable import PhotosCatalog
@testable import PhotosStorage

/// §4's loop, offline. LIST is the entire sync mechanism, so what the diff does with each
/// kind of LIST entry is the whole correctness story — and a missing key means "album
/// deleted", which makes a misparse expensive.
struct CatalogSyncTests {

    private func makeSync(_ transport: KeyedTransport) throws -> (CatalogSync, URL) {
        let directory = try Fixture.temporaryDirectory("sync")
        let sync = try CatalogSync(s3: SyncFixtures.client(transport), cacheRoot: directory)
        return (sync, directory)
    }

    private func upload(_ shard: Shard, to transport: KeyedTransport, etag: String) throws {
        transport.put(StorageKey.shard(shard.info.id), try ShardWriter.write(shard), etag: etag)
    }

    // MARK: - First run

    @Test("a first sync fetches every shard and builds the catalog")
    func firstSync() async throws {
        let transport = KeyedTransport()
        let shards = Fixture.containerTree("Kalifornien", children: ["Yosemite", "Big Sur"])
        for (index, shard) in shards.enumerated() {
            try upload(shard, to: transport, etag: "etag-\(index)")
        }

        let (sync, directory) = try makeSync(transport)
        let report = try await sync.sync()

        #expect(report.fetchedShards.count == 3)
        #expect(report.rebuilt)
        #expect(report.albums == 3)
        #expect(report.photos == 6)
        #expect(!report.hasAnomalies)

        let reader = try CatalogReader(path: directory.appending(path: "merged.db"))
        #expect(try reader.allAlbums().count == 3)
    }

    /// §4 calls the single LIST "the sync plan". A run that changes nothing must cost that
    /// one request and no more — the hourly systemd unit depends on it.
    @Test("an unchanged second sync is one LIST and no fetches")
    func noOpSync() async throws {
        let transport = KeyedTransport()
        try upload(Fixture.album("Sommer", photos: [Fixture.photo("a.jpg")]), to: transport, etag: "e1")

        let (sync, _) = try makeSync(transport)
        _ = try await sync.sync()
        let before = transport.requestedKeys.count

        let report = try await sync.sync()

        #expect(report.fetchedShards.isEmpty)
        #expect(!report.rebuilt)
        #expect(transport.requestedKeys.count == before)   // no object GETs at all
        #expect(transport.listCount == 2)
    }

    @Test("only the shard whose ETag moved is re-fetched")
    func fetchesOnlyChanged() async throws {
        let transport = KeyedTransport()
        let a = Fixture.album("A", photos: [Fixture.photo("a.jpg")])
        let b = Fixture.album("B", photos: [Fixture.photo("b.jpg")])
        try upload(a, to: transport, etag: "a1")
        try upload(b, to: transport, etag: "b1")

        let (sync, _) = try makeSync(transport)
        _ = try await sync.sync()

        var changed = a
        changed.photos.append(Fixture.photo("a2.jpg"))
        try upload(changed, to: transport, etag: "a2")

        let report = try await sync.sync()

        #expect(report.fetchedShards == [a.info.id])
        #expect(report.photos == 3)
    }

    // MARK: - Deletion

    /// §4: there is no manifest, so absence *is* the signal.
    @Test("a key that vanished means the album was deleted")
    func absentKeyDeletesAlbum() async throws {
        let transport = KeyedTransport()
        let keep = Fixture.album("Keep", photos: [Fixture.photo("k.jpg")])
        let drop = Fixture.album("Drop", photos: [Fixture.photo("d.jpg")])
        try upload(keep, to: transport, etag: "k1")
        try upload(drop, to: transport, etag: "d1")

        let (sync, directory) = try makeSync(transport)
        _ = try await sync.sync()

        transport.remove(StorageKey.shard(drop.info.id))
        let report = try await sync.sync()

        #expect(report.deletedAlbums == [drop.info.id])
        let reader = try CatalogReader(path: directory.appending(path: "merged.db"))
        #expect(try reader.allAlbums().map(\.name) == ["Keep"])
    }

    // MARK: - What LIST returns that is not a shard

    /// §2: keys no longer nest, but bunny.net still materialises the prefix's own marker,
    /// and it comes back with Size 0 and no ETag. Treating it as a shard would break the diff.
    @Test("the meta/ directory marker is ignored, not treated as an album")
    func ignoresDirectoryMarker() async throws {
        let transport = KeyedTransport()
        transport.put("meta/", Data(), etag: "")
        try upload(Fixture.album("Real", photos: [Fixture.photo("a.jpg")]), to: transport, etag: "r1")

        let (sync, _) = try makeSync(transport)
        let report = try await sync.sync()

        #expect(report.albums == 1)
        #expect(report.ignoredKeys == ["meta/"])
        #expect(report.deletedAlbums.isEmpty)
    }

    @Test("a key whose name is not a uuid is ignored rather than guessed at")
    func ignoresNonUUIDKey() async throws {
        let transport = KeyedTransport()
        transport.put("meta/leftover.db", Data("junk".utf8), etag: "x1")
        try upload(Fixture.album("Real"), to: transport, etag: "r1")

        let (sync, _) = try makeSync(transport)
        let report = try await sync.sync()

        #expect(report.albums == 1)
        #expect(report.ignoredKeys == ["meta/leftover.db"])
    }

    // MARK: - Anomalies the design reports rather than resolves

    /// §3: a shard from a newer writer is skipped and named. The CLI must be able to tell
    /// "unreadable" from "absent" — an absent album gets re-uploaded, and with UUID keys
    /// nothing collides to stop the duplicate.
    @Test("a shard from a newer schema is reported unreadable, and the rest still sync")
    func reportsUnreadableShard() async throws {
        let transport = KeyedTransport()
        let (futureID, futureData) = try Fixture.futureShard()
        transport.put(StorageKey.shard(futureID), futureData, etag: "f1")
        try upload(Fixture.album("Readable", photos: [Fixture.photo("a.jpg")]), to: transport, etag: "r1")

        let (sync, _) = try makeSync(transport)
        let report = try await sync.sync()

        #expect(report.unreadableShards == [futureID])
        #expect(report.albums == 1)                 // the readable one still landed
        #expect(!report.deletedAlbums.contains(futureID))
        #expect(report.hasAnomalies)
    }

    @Test("an orphaned album is reported and still reachable")
    func reportsOrphan() async throws {
        let transport = KeyedTransport()
        let shards = Fixture.containerTree("Immling", children: ["2003"])
        try upload(shards[1], to: transport, etag: "c1")   // the child only

        let (sync, directory) = try makeSync(transport)
        let report = try await sync.sync()

        #expect(report.orphanedAlbums == [shards[1].info.id])
        let reader = try CatalogReader(path: directory.appending(path: "merged.db"))
        #expect(try reader.albums(under: nil).map(\.name) == ["2003"])
    }

    @Test("duplicate album names are reported")
    func reportsDuplicates() async throws {
        let transport = KeyedTransport()
        try upload(Fixture.album("Sommer"), to: transport, etag: "s1")
        try upload(Fixture.album("Sommer"), to: transport, etag: "s2")

        let (sync, _) = try makeSync(transport)
        let report = try await sync.sync()

        #expect(report.duplicateNames == ["Sommer"])
        #expect(report.albums == 2)
    }

    // MARK: - Local state

    /// §4: the merged DB is derived, so losing it must cost nothing but a replay.
    @Test("the merged DB can be deleted and rebuilt with no network")
    func rebuildsFromDiskWithoutNetwork() async throws {
        let transport = KeyedTransport()
        let shards = Fixture.containerTree("Rauhöd", children: ["2019", "2020"])
        for (index, shard) in shards.enumerated() {
            try upload(shard, to: transport, etag: "e\(index)")
        }

        let (sync, directory) = try makeSync(transport)
        _ = try await sync.sync()
        let listsAfterSync = transport.listCount

        try FileManager.default.removeItem(at: directory.appending(path: "merged.db"))
        let report = try await sync.rebuildFromDisk()

        #expect(report.albums == 3)
        #expect(report.photos == 6)
        #expect(transport.listCount == listsAfterSync)   // nothing went over the wire
    }

    /// Deleting `sync_state.db` alone is the free repair path §4 describes.
    @Test("losing sync_state forces a full re-fetch")
    func lostSyncStateRefetches() async throws {
        let transport = KeyedTransport()
        for (index, shard) in Fixture.containerTree("A", children: ["x", "y"]).enumerated() {
            try upload(shard, to: transport, etag: "e\(index)")
        }

        let (first, directory) = try makeSync(transport)
        _ = try await first.sync()

        try FileManager.default.removeItem(at: directory.appending(path: "sync_state.db"))
        let second = try CatalogSync(s3: SyncFixtures.client(transport), cacheRoot: directory)
        let report = try await second.sync()

        #expect(report.fetchedShards.count == 3)
        #expect(report.albums == 3)
    }

    @Test("referencedObjectIDs names every blob the catalog points at")
    func referencedObjects() async throws {
        let transport = KeyedTransport()
        let shard = Fixture.album("Sommer", photos: [
            Fixture.photo("a.jpg"),
            Fixture.photo("b.HEIC", mediaType: .livePhoto),
        ])
        try upload(shard, to: transport, etag: "s1")

        let (sync, _) = try makeSync(transport)
        _ = try await sync.sync()

        // 2 originals + 2 previews + 1 live MOV + 1 thumbnail pack
        #expect(try await sync.referencedObjectIDs().count == 6)
        #expect(try await sync.referencedObjectIDs() == Set(shard.objectIDs))
    }

    // MARK: - Writing back

    /// §2's guarded single-owner rule: 412 means someone else wrote it. It is returned, not
    /// retried — a blind retry would overwrite the other device's work.
    @Test("a stale ETag comes back as staleETag rather than an overwrite")
    func staleETagIsReported() async throws {
        final class RejectingTransport: HTTPTransport, @unchecked Sendable {
            func send(_ request: HTTPRequest) async throws -> HTTPResponse {
                HTTPResponse(status: 412, headers: [:], body: Data())
            }
        }
        let directory = try Fixture.temporaryDirectory("stale")
        let sync = try CatalogSync(
            s3: S3Client(storage: SyncFixtures.storage, secretAccessKey: "k",
                         transport: RejectingTransport(), retry: .none),
            cacheRoot: directory
        )

        let result = try await sync.writeShard(Fixture.album("Sommer"), ifMatch: ETag(header: "\"old\""))

        guard case .staleETag = result else {
            Issue.record("expected staleETag, got \(result)")
            return
        }
    }
}
