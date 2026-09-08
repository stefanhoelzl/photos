import Foundation
import PhotosCatalog
import PhotosCore
@testable import PhotosIngest
import PhotosLibrary
import PhotosPipeline
import PhotosStorage
import Testing

/// The whole cycle, over a synthetic library: first sync, no-op sync, one photo deleted,
/// one album removed, the marker taken away, and the sweep.
///
/// §10's gate 2. It runs against `FakeZone` rather than adobe/S3Mock: the wire is A's
/// business and already proven both against S3Mock and against the live zone, whereas what
/// this has to pin down is D's own state machine — including a blob's age, which a test can
/// only state if it owns the clock.
@Suite("Ingest cycle")
struct IngestCycleTests {

    /// Real JPEGs: the pipeline decodes what it is given, so a library of zero bytes would
    /// prove only that failures are collected.
    func makeLibrary(_ label: String) throws -> LibraryFixture {
        let library = try LibraryFixture(label)
        let jpeg = try Synthetic.jpeg(width: 320, height: 240)
        for path in ["Rauhöd/a.jpg", "Rauhöd/b.jpg", "Neuseeland/c.jpg"] {
            let url = library.root.appending(path: path)
            try FileManager.default.createDirectory(at: url.deletingLastPathComponent(),
                                                    withIntermediateDirectories: true)
            try jpeg.write(to: url)
        }
        return library
    }

    func makeIngest(_ library: LibraryFixture, zone: FakeZone,
                    cache: URL, dryRun: Bool = false) throws -> Ingest {
        try Ingest(
            config: IngestConfig(
                libraryRoot: library.root,
                cacheRoot: cache,
                workRoot: cache.appending(path: "work"),
                jobs: 2,
                uploadJobs: 1,
                dryRun: dryRun
            ),
            s3: try zone.client()
        )
    }

    @Test("first sync writes a shard per album and a blob per derivative")
    func firstSync() async throws {
        let library = try makeLibrary("first")
        defer { library.cleanUp() }
        let cache = try LibraryFixture("cache").root
        let zone = FakeZone()

        let report = try await makeIngest(library, zone: zone, cache: cache).run()

        #expect(report.albums.count == 2)
        #expect(report.uploadedFiles == 3)
        #expect(report.failures.isEmpty)
        #expect(!report.hasProblems)

        let shards = zone.keys.filter { $0.hasPrefix(StorageKey.metaPrefix) }
        #expect(shards.count == 2)
        // Per photo: the original and the 2048px preview. Per album: one thumbnail pack.
        #expect(zone.keys.filter { $0.hasPrefix(StorageKey.blobPrefix) }.count == 3 * 2 + 2)

        // Every shard says which folder it came from, which is the only thing reconnecting
        // a directory to its album next run.
        for key in shards {
            let shard = try ShardReader.read(try #require(zone.data(key)))
            #expect(shard.info.sourcePath != nil)
            #expect(shard.info.thumbsID != nil)
        }
    }

    /// §7's promise, taken literally: a run that changes nothing costs **one** request. Not
    /// one LIST plus a re-read of 288 shards — that is what the ETag cache beside them is
    /// for — and not one LIST plus a 34,000-key sweep of `blob/` either, which is why the
    /// sweep stands down when neither the zone nor the library moved.
    @Test("a run with nothing to do is exactly one LIST")
    func noOpRunIsOneRequest() async throws {
        let library = try makeLibrary("one-list")
        defer { library.cleanUp() }
        let cache = try LibraryFixture("cache").root
        let zone = FakeZone()
        _ = try await makeIngest(library, zone: zone, cache: cache).run()

        let listsAfterFirst = zone.listCount
        _ = try await makeIngest(library, zone: zone, cache: cache).run()
        #expect(zone.listCount == listsAfterFirst + 1)
    }

    @Test("a second sync uploads nothing and writes nothing")
    func secondSyncIsANoOp() async throws {
        let library = try makeLibrary("noop")
        defer { library.cleanUp() }
        let cache = try LibraryFixture("cache").root
        let zone = FakeZone()

        _ = try await makeIngest(library, zone: zone, cache: cache).run()
        let putsAfterFirst = zone.putCount
        let keysAfterFirst = zone.keys

        let report = try await makeIngest(library, zone: zone, cache: cache).run()
        #expect(report.uploadedFiles == 0)
        #expect(report.droppedRows == 0)
        #expect(zone.putCount == putsAfterFirst)
        #expect(zone.keys == keysAfterFirst)
    }

    /// The deletion model, end to end: `rm` on one file, and its row and its blobs go with
    /// it — automatically, with no confirmation step anywhere.
    @Test("deleting a file drops its row and its blobs")
    func deletingAFileDropsItsBlobs() async throws {
        let library = try makeLibrary("drop")
        defer { library.cleanUp() }
        let cache = try LibraryFixture("cache").root
        let zone = FakeZone()
        _ = try await makeIngest(library, zone: zone, cache: cache).run()

        let before = try shard(named: "Rauhöd", in: zone)
        let doomed = try #require(before.photos.first { $0.filename == "b.jpg" })
        try library.remove("Rauhöd/b.jpg")

        let report = try await makeIngest(library, zone: zone, cache: cache).run()
        #expect(report.droppedRows == 1)

        let after = try shard(named: "Rauhöd", in: zone)
        #expect(after.photos.map(\.filename) == ["a.jpg"])
        for id in doomed.objectIDs {
            #expect(!zone.contains(StorageKey.blob(id)))
        }
        // The pack was rebuilt, and the one it replaced is gone.
        #expect(after.info.thumbsID != before.info.thumbsID)
        #expect(!zone.contains(StorageKey.blob(try #require(before.info.thumbsID))))
    }

    /// `rm album/*` is not how an album is deleted, and the zone must reflect that: the
    /// album is still there, holding nothing.
    @Test("emptying an album leaves it in the zone with no photos")
    func emptyingAnAlbumKeepsIt() async throws {
        let library = try makeLibrary("empty")
        defer { library.cleanUp() }
        let cache = try LibraryFixture("cache").root
        let zone = FakeZone()
        _ = try await makeIngest(library, zone: zone, cache: cache).run()

        try library.remove("Rauhöd/a.jpg")
        try library.remove("Rauhöd/b.jpg")
        _ = try await makeIngest(library, zone: zone, cache: cache).run()

        let after = try shard(named: "Rauhöd", in: zone)
        #expect(after.photos.isEmpty)
        #expect(after.info.thumbsID == nil)
    }

    /// `rm -rf`, the one gesture that means "this album leaves the system".
    @Test("removing a directory deletes the album and everything it owned")
    func removingADirectoryDeletesTheAlbum() async throws {
        let library = try makeLibrary("rmrf")
        defer { library.cleanUp() }
        let cache = try LibraryFixture("cache").root
        let zone = FakeZone()
        _ = try await makeIngest(library, zone: zone, cache: cache).run()

        let doomed = try shard(named: "Rauhöd", in: zone)
        try library.remove("Rauhöd")

        let report = try await makeIngest(library, zone: zone, cache: cache).run()
        #expect(report.deletedAlbums.count == 1)
        #expect(!zone.contains(StorageKey.shard(doomed.info.id)))
        for id in doomed.objectIDs {
            #expect(!zone.contains(StorageKey.blob(id)))
        }
        // The other album is untouched.
        #expect(zone.keys.filter { $0.hasPrefix(StorageKey.metaPrefix) }.count == 1)
    }

    /// The one structural guard. An unmounted disk and a mistyped root both look like this,
    /// and both would otherwise read as a library whose every album had been deleted.
    @Test("no .photosignore means no run, and nothing is written")
    func markerGuard() async throws {
        let library = try makeLibrary("marker")
        defer { library.cleanUp() }
        let cache = try LibraryFixture("cache").root
        let zone = FakeZone()
        _ = try await makeIngest(library, zone: zone, cache: cache).run()

        let before = zone.keys
        try library.removeMarker()
        try library.remove("Rauhöd")      // would otherwise delete an album

        await #expect(throws: IngestAbort.self) {
            _ = try await makeIngest(library, zone: zone, cache: cache).run()
        }
        #expect(zone.keys == before)
        #expect(zone.deleteCount == 0)
    }

    /// §7 asserts images never change on disk, and checks it. A changed file means the
    /// library broke its contract, so the run writes nothing at all rather than guessing.
    @Test("a file that changed size aborts the whole run")
    func byteMismatchAborts() async throws {
        let library = try makeLibrary("changed")
        defer { library.cleanUp() }
        let cache = try LibraryFixture("cache").root
        let zone = FakeZone()
        _ = try await makeIngest(library, zone: zone, cache: cache).run()

        let before = zone.keys
        try Synthetic.jpeg(width: 640, height: 480).write(to: library.root.appending(path: "Rauhöd/a.jpg"))

        await #expect(throws: IngestAbort.self) {
            _ = try await makeIngest(library, zone: zone, cache: cache).run()
        }
        #expect(zone.keys == before)
    }

    /// Decision 15: unreferenced is not the same as abandoned. Below the age floor a blob is
    /// indistinguishable from one the phone is uploading right now, so it is left alone.
    @Test("the sweep takes old crash debris and spares young blobs")
    func sweepRespectsTheAgeFloor() async throws {
        let library = try makeLibrary("sweep")
        defer { library.cleanUp() }
        let cache = try LibraryFixture("cache").root
        let zone = FakeZone()
        _ = try await makeIngest(library, zone: zone, cache: cache).run()

        let old = StorageKey.blob(UUID())
        let young = StorageKey.blob(UUID())
        zone.insert(old, Data(repeating: 0, count: 100), age: 30 * 24 * 60 * 60)
        zone.insert(young, Data(repeating: 0, count: 100), age: 60)

        // Debris is collected by the next run that has something to do. A run with nothing
        // to do skips the sweep, because it cannot have produced any and the alternative is
        // paying 34,000 keys an hour for the privilege of looking.
        try Synthetic.jpeg(width: 320, height: 240)
            .write(to: library.root.appending(path: "Rauhöd/d.jpg"))

        let report = try await makeIngest(library, zone: zone, cache: cache).run()
        #expect(report.sweptBlobs == 1)
        #expect(report.youngUnreferencedBlobs == 1)
        #expect(!zone.contains(old))
        #expect(zone.contains(young))
    }

    /// The sweep's referenced set comes from the shards it can read. One it cannot read owns
    /// blobs it cannot enumerate, so sweeping would delete a readable album's photographs on
    /// the strength of a file it never opened.
    @Test("the sweep stands down when any shard is too new to read")
    func sweepStandsDownForUnreadableShards() async throws {
        let library = try makeLibrary("sweep-blocked")
        defer { library.cleanUp() }
        let cache = try LibraryFixture("cache").root
        let zone = FakeZone()
        _ = try await makeIngest(library, zone: zone, cache: cache).run()

        zone.insert(StorageKey.shard(UUID()), try futureShard())
        let orphan = StorageKey.blob(UUID())
        zone.insert(orphan, Data(repeating: 0, count: 100), age: 30 * 24 * 60 * 60)

        let report = try await makeIngest(library, zone: zone, cache: cache).run()
        #expect(report.sweepSkipped != nil)
        #expect(report.sweptBlobs == 0)
        #expect(zone.contains(orphan))
    }

    @Test("a dry run reports the same plan and changes nothing")
    func dryRunChangesNothing() async throws {
        let library = try makeLibrary("dry")
        defer { library.cleanUp() }
        let cache = try LibraryFixture("cache").root
        let zone = FakeZone()

        let report = try await makeIngest(library, zone: zone, cache: cache, dryRun: true).run()
        #expect(report.dryRun)
        #expect(report.albums.count == 2)
        #expect(report.uploadedFiles == 3)
        #expect(zone.putCount == 0)
        #expect(zone.keys.isEmpty)
    }

    // MARK: - Helpers

    func shard(named name: String, in zone: FakeZone) throws -> Shard {
        for key in zone.keys where key.hasPrefix(StorageKey.metaPrefix) {
            let shard = try ShardReader.read(try #require(zone.data(key)))
            if shard.info.name == name.precomposedStringWithCanonicalMapping { return shard }
        }
        throw NoSuchAlbum(name: name)
    }

    struct NoSuchAlbum: Error { var name: String }

    /// A shard from a schema this build does not know, forged rather than committed.
    func futureShard() throws -> Data {
        let shard = Shard(
            info: AlbumInfo(name: "From The Future", sourcePath: "Nowhere",
                            schemaVersion: CatalogSchema.version + 1),
            photos: []
        )
        return try ShardWriter.write(shard)
    }
}

/// The one case where an album's *claimed* files still matter to a later run.
///
/// A Live Photo is a HEIC and a MOV that pair on `content.identifier`, and only the HEIC gets
/// a row — §3 keeps a Live Photo as one row with two blobs. So a second sync that classified
/// only the unclaimed files would be handed a lone MOV, decide it was an ordinary video, and
/// transcode and upload all 187 of them a second time as separate photos. Nothing else in the
/// design notices: the shard would simply grow rows that look legitimate.
@Suite("Live Photo pairing across runs")
struct LivePhotoCycleTests {

    /// Pairing needs the maker-note identifier, which a synthetic HEIC does not carry, so the
    /// backend is stubbed exactly as `ClassifierTests` does.
    struct PairingBackend: ImageBackend {
        var identifiers: [String: String]
        func rawTags(at url: URL) throws -> ExifTags {
            guard let identifier = identifiers[url.lastPathComponent] else { return ExifTags() }
            return ExifTags(["AppleContentIdentifier": .string(identifier)])
        }
    }

    @Test("a Live Photo's MOV is not re-uploaded as a video on the next run")
    func movIsNotReuploaded() async throws {
        let library = try LibraryFixture("live")
        defer { library.cleanUp() }
        let cache = try LibraryFixture("cache").root
        let zone = FakeZone()

        let identifier = "B34B6B99-C28F-4E16-A788-79AA0E30BB18"
        let still = library.root.appending(path: "Wochenende/IMG_0679.HEIC")
        let movie = library.root.appending(path: "Wochenende/IMG_0679.mov")
        try FileManager.default.createDirectory(at: still.deletingLastPathComponent(),
                                                withIntermediateDirectories: true)
        try Synthetic.heic(at: still, width: 64, height: 48)
        try Synthetic.video(at: movie, width: 64, height: 48, frames: 4,
                            contentIdentifier: identifier)
        let backend = PairingBackend(identifiers: ["IMG_0679.HEIC": identifier])

        func ingest() throws -> Ingest {
            try Ingest(
                config: IngestConfig(libraryRoot: library.root, cacheRoot: cache,
                                     workRoot: cache.appending(path: "work"),
                                     jobs: 1, uploadJobs: 1),
                s3: try zone.client(),
                backend: backend
            )
        }

        let first = try await ingest().run()
        #expect(first.uploadedFiles == 1)          // one Live Photo, not a photo and a video

        let second = try await ingest().run()
        #expect(second.uploadedFiles == 0)
        #expect(second.failures.isEmpty)

        let key = try #require(zone.keys.first { $0.hasPrefix(StorageKey.metaPrefix) })
        let shard = try ShardReader.read(try #require(zone.data(key)))
        #expect(shard.photos.count == 1)
        #expect(shard.photos[0].mediaType == .livePhoto)
        #expect(shard.photos[0].liveVideoID != nil)
        #expect(shard.photos[0].videoID == nil)    // never became a video of its own
    }
}
