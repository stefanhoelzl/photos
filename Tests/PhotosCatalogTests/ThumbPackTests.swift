import Foundation
import Testing
@testable import PhotosCatalog

/// The pack exists to keep §6's promise that a grid opens in one request, offline. What has
/// to hold: the bytes come back exactly, and one thumbnail can be read without the rest.
struct ThumbPackTests {

    private func jpeg(_ seed: UInt8, size: Int = 8_700) -> Data {
        Data((0..<size).map { UInt8(($0 &+ Int(seed)) % 251) })
    }

    @Test("thumbnails round-trip byte for byte")
    func roundTrip() throws {
        let thumbnails = Dictionary(uniqueKeysWithValues: (0..<20).map { (UUID(), jpeg(UInt8($0))) })
        let restored = try ThumbPack.unpack(try ThumbPack.pack(thumbnails))

        #expect(restored.count == thumbnails.count)
        for (id, bytes) in thumbnails { #expect(restored[id] == bytes) }
    }

    @Test("one thumbnail can be read without unpacking the album")
    func singleLookup() throws {
        var thumbnails: [UUID: Data] = [:]
        for index in 0..<50 { thumbnails[UUID()] = jpeg(UInt8(index)) }
        let wanted = try #require(thumbnails.keys.sorted { $0.catalogString < $1.catalogString }.first)
        let data = try ThumbPack.pack(thumbnails)

        #expect(try ThumbPack.thumbnail(wanted, in: data) == thumbnails[wanted])
        #expect(try ThumbPack.thumbnail(UUID(), in: data) == nil)
    }

    @Test("ids can be listed without their bytes")
    func idsOnly() throws {
        let thumbnails = Dictionary(uniqueKeysWithValues: (0..<10).map { (UUID(), jpeg(UInt8($0))) })
        #expect(try ThumbPack.ids(in: try ThumbPack.pack(thumbnails)) == Set(thumbnails.keys))
    }

    @Test("an empty pack is legal — a container owns no photos")
    func emptyPack() throws {
        let data = try ThumbPack.pack([:])
        #expect(try ThumbPack.unpack(data).isEmpty)
        #expect(try ThumbPack.ids(in: data).isEmpty)
    }

    /// Not required for correctness — blobs are immutable and a repack gets a fresh uuid
    /// either way — but it makes two packs comparable when a person needs to know whether
    /// anything actually changed.
    @Test("packing the same thumbnails twice gives the same bytes")
    func packIsDeterministic() throws {
        let thumbnails = Dictionary(uniqueKeysWithValues: (0..<30).map { (UUID(), jpeg(UInt8($0))) })
        #expect(try ThumbPack.pack(thumbnails) == (try ThumbPack.pack(thumbnails)))
    }

    /// §3's sizing: ~9 KB each, so a 100-photo album is ~0.9 MB and opens in one request.
    @Test("a 100-photo album packs to roughly a megabyte")
    func packSizeMatchesDesign() throws {
        let thumbnails = Dictionary(uniqueKeysWithValues: (0..<100).map { (UUID(), jpeg(UInt8($0 % 251))) })
        let data = try ThumbPack.pack(thumbnails)

        // 100 × 8.7 KB = 870 KB of payload; the envelope must not be a meaningful share.
        #expect(data.count > 870_000)
        #expect(data.count < 1_100_000)
    }
}
