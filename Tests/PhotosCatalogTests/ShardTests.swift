import Foundation
import Testing
@testable import PhotosCatalog

/// A shard is the unit of write in §2's conflict model — rewritten wholesale, guarded by
/// `If-Match`. If a round trip loses a field, the loss is silent and permanent, so every
/// column is checked rather than sampled.
struct ShardTests {

    @Test("every column survives a round trip")
    func roundTrip() throws {
        let photos = [
            Fixture.photo("IMG_0001.jpg"),
            Fixture.photo("IMG_0002.HEIC", mediaType: .livePhoto),
            Fixture.photo("MVI_0003.MOV", mediaType: .video),
        ]
        let original = Fixture.album("Neuseeland", photos: photos, coverPhotoID: photos[1].id)

        let restored = try ShardReader.read(try ShardWriter.write(original))

        #expect(restored.info == original.info)
        #expect(Set(restored.photos) == Set(original.photos))
    }

    @Test("an album with no photos is legal — containers have none by the XOR rule")
    func emptyAlbum() throws {
        let container = Fixture.album("Weihnachten", photos: [], thumbsID: nil)
        let restored = try ShardReader.read(try ShardWriter.write(container))

        #expect(restored.photos.isEmpty)
        #expect(restored.info.thumbsID == nil)
        #expect(restored.info.name == "Weihnachten")
    }

    @Test("nullable fields stay nil rather than becoming zero")
    func nullsSurvive() throws {
        let bare = PhotoRow(filename: "scan.png")
        let restored = try ShardReader.read(try ShardWriter.write(Fixture.album("Scans", photos: [bare])))

        let photo = try #require(restored.photos.first)
        #expect(photo.takenAt == nil)
        #expect(photo.latitude == nil)
        #expect(photo.width == nil)
        #expect(photo.bytes == nil)
        #expect(photo.originalID == nil)
        #expect(photo.previewID == nil)
    }

    /// §2: iOS emits NFD and Linux stores whatever bytes it was handed. A name that differs
    /// only by composition must not become a second album or a missed search hit.
    @Test("names and filenames are NFC-normalised on the way in")
    func normalisesToNFC() throws {
        let decomposed = "Gru\u{0308}n"          // "Grün" as u + combining diaeresis
        let composed = "Gr\u{00FC}n"

        let shard = Fixture.album(decomposed, photos: [Fixture.photo("cafe\u{0301}.jpg")])
        let restored = try ShardReader.read(try ShardWriter.write(shard))

        #expect(restored.info.name == composed)
        #expect(restored.info.name.unicodeScalars.count == 4)
        #expect(restored.photos[0].filename == "caf\u{00E9}.jpg")
    }

    @Test("all three media types round-trip with the right object ids")
    func mediaTypes() throws {
        let live = Fixture.photo("IMG_0099.HEIC", mediaType: .livePhoto)
        let video = Fixture.photo("clip.avi", mediaType: .video)
        let still = Fixture.photo("IMG_0001.jpg")

        let restored = try ShardReader.read(
            try ShardWriter.write(Fixture.album("Mixed", photos: [live, video, still]))
        )
        let byName = Dictionary(uniqueKeysWithValues: restored.photos.map { ($0.filename, $0) })

        #expect(byName["IMG_0099.HEIC"]?.mediaType == .livePhoto)
        #expect(byName["IMG_0099.HEIC"]?.liveVideoID != nil)
        #expect(byName["clip.avi"]?.videoID != nil)
        #expect(byName["IMG_0001.jpg"]?.liveVideoID == nil)
        #expect(byName["IMG_0001.jpg"]?.videoID == nil)
    }

    /// §3: a reader reads anything at or below its own version and skips what is newer.
    /// The skip must be a *distinguishable* error — the CLI has to tell "unreadable" from
    /// "absent", or it re-uploads the album as a duplicate.
    @Test("a shard from a newer schema is refused, not misread")
    func refusesNewerSchema() throws {
        let (_, data) = try Fixture.futureShard()

        #expect(throws: ShardError.unsupportedVersion(
            found: CatalogSchema.version + 1, supported: CatalogSchema.version
        )) {
            try ShardReader.read(data)
        }
        #expect(try ShardReader.schemaVersion(of: data) == CatalogSchema.version + 1)
    }

    @Test("a shard at the current version reads normally")
    func acceptsCurrentSchema() throws {
        let data = try ShardWriter.write(Fixture.album("Now"))
        #expect(try ShardReader.schemaVersion(of: data) == CatalogSchema.version)
        #expect(try ShardReader.read(data).info.name == "Now")
    }

    @Test("a database that is not a shard is rejected")
    func rejectsNonShard() throws {
        let database = try Database(path: nil, options: .inMemory)
        try database.execute("CREATE TABLE unrelated (x)")

        #expect(throws: ShardError.missingAlbumInfo) {
            try ShardReader.read(try database.serialized())
        }
    }

    @Test("objectIDs names every blob the album owns, pack included")
    func objectIDs() throws {
        let live = Fixture.photo("IMG_0099.HEIC", mediaType: .livePhoto)
        let shard = Fixture.album("Sommer", photos: [live])

        let ids = Set(shard.objectIDs)
        #expect(ids.contains(try #require(live.originalID)))
        #expect(ids.contains(try #require(live.liveVideoID)))
        #expect(ids.contains(try #require(live.previewID)))
        #expect(ids.contains(try #require(shard.info.thumbsID)))
        #expect(ids.count == 4)
    }
}

/// Keys are the entire sync mechanism, so parsing one has to be strict: a key this cannot
/// read is skipped, never guessed at.
struct StorageKeyTests {

    @Test("shard keys round-trip through their uuid")
    func shardKeyRoundTrip() {
        let id = UUID()
        let key = StorageKey.shard(id)

        #expect(key == "meta/\(id.uuidString.lowercased()).db")
        #expect(StorageKey.albumID(fromShardKey: key) == id)
    }

    @Test("blob keys carry no extension")
    func blobKey() {
        let id = UUID()
        #expect(StorageKey.blob(id) == "blob/\(id.uuidString.lowercased())")
    }

    @Test("anything that is not a shard key is refused", arguments: [
        "meta/",                                    // the prefix's own directory marker
        "meta/not-a-uuid.db",
        "meta/9F2C1AB7-3E44-4C2A-9D81-77B0E5C1AF02", // no .db
        "blob/9f2c1ab7-3e44-4c2a-9d81-77b0e5c1af02", // wrong prefix
        "meta/.db",
        "",
    ])
    func refusesOtherKeys(key: String) {
        #expect(StorageKey.albumID(fromShardKey: key) == nil)
    }

    @Test("uuid case does not matter when reading a key back")
    func caseInsensitive() {
        let id = UUID()
        let upper = "meta/\(id.uuidString.uppercased()).db"
        #expect(StorageKey.albumID(fromShardKey: upper) == id)
    }
}
