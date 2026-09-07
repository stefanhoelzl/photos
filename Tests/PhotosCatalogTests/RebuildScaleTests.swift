import Foundation
import Testing
@testable import PhotosCatalog

/// Milestone B's empirical bar, the counterpart to A's 38 SigV4 vectors and its static ELF.
///
/// §4 claims the merged rebuild takes 1–3 s, and §6's whole feel rests on it: it is the one
/// number E inherits and cannot renegotiate. So it is measured here at this library's real
/// scale rather than asserted — 337 albums, 34,607 photo rows, the shape INGEST.md records.
///
/// The budget is deliberately loose. This is a regression bar, not a benchmark: it should
/// catch an accidental per-row transaction or a missing index, and it should not fail
/// because CI was busy.
struct RebuildScaleTests {

    /// INGEST.md's inventory, reproduced exactly: 4 containers, 288 leaf albums with
    /// photos, 337 albums in total, 34,607 photo rows, and `Neuseeland` at 1,755.
    static func realisticLibrary() -> [Shard] {
        let containers = [("Immling", 3), ("Kalifornien", 17), ("Rauhöd", 15), ("Weihnachten", 17)]
        let nestedLeaves = containers.reduce(0) { $0 + $1.1 }          // 52
        let topLevelLeaves = 337 - containers.count - nestedLeaves     // 281
        let base = Date(timeIntervalSince1970: 1_000_000_000)

        // Neuseeland is the outlier the design keeps citing; the rest share what is left.
        let remainder = 34_607 - 1_755
        let leafCount = nestedLeaves + topLevelLeaves - 1
        let each = remainder / leafCount
        var counts = Array(repeating: each, count: leafCount - 1)
        counts.append(remainder - each * (leafCount - 1))

        var shards: [Shard] = []
        var next = counts.makeIterator()

        for (name, childCount) in containers {
            let parent = AlbumInfo(name: name, sourcePath: name, thumbsID: nil)
            shards.append(Shard(info: parent, photos: []))
            for index in 0..<childCount {
                shards.append(leaf("\(name)/\(index)", parent: parent.id,
                                   photos: next.next() ?? 0, base: base))
            }
        }
        shards.append(leaf("Neuseeland", parent: nil, photos: 1_755, base: base))
        for index in 0..<(topLevelLeaves - 1) {
            shards.append(leaf("Album \(index)", parent: nil, photos: next.next() ?? 0, base: base))
        }
        return shards
    }

    private static func leaf(_ name: String, parent: UUID?, photos: Int, base: Date) -> Shard {
        let info = AlbumInfo(name: name, parent: parent, sourcePath: name, thumbsID: UUID())
        let rows = (0..<photos).map { offset -> PhotoRow in
            // INGEST.md measured 24.5% of photos carrying GPS and 99.5% carrying a date.
            let hasGPS = offset % 4 == 0
            let hasDate = offset % 200 != 0
            return PhotoRow(
                filename: "IMG_\(String(format: "%05d", offset)).jpg",
                takenAt: hasDate ? base.addingTimeInterval(Double(offset) * 60) : nil,
                latitude: hasGPS ? 47.9 + Double(offset % 100) / 1000 : nil,
                longitude: hasGPS ? 12.2 + Double(offset % 100) / 1000 : nil,
                width: 4000, height: 3000, bytes: 3_145_728,
                mediaType: .photo,
                originalID: UUID(), previewID: UUID()
            )
        }
        return Shard(info: info, photos: rows)
    }

    @Test("a full-scale rebuild lands inside §4's budget", .timeLimit(.minutes(1)))
    func fullScaleRebuild() throws {
        let shards = Self.realisticLibrary()
        let photoCount = shards.reduce(0) { $0 + $1.photos.count }

        #expect(shards.count == 337)
        #expect(photoCount == 34_607)

        let directory = try Fixture.temporaryDirectory("scale")
        let path = directory.appending(path: "merged.db")
        let writer = try CatalogWriter(path: path)

        let started = ContinuousClock.now
        let summary = try writer.rebuild(from: shards)
        let elapsed = ContinuousClock.now - started

        #expect(summary.albums == 337)
        #expect(summary.photos == 34_607)
        #expect(summary.orphanedAlbums.isEmpty)

        let size = (try FileManager.default.attributesOfItem(atPath: path.path)[.size] as? Int) ?? 0

        print("""

            Full-scale rebuild — DESIGN §4's 1–3 s claim
              albums   \(summary.albums)
              photos   \(summary.photos)
              elapsed  \(elapsed)
              merged   \(String(format: "%.2f", Double(size) / 1_048_576)) MB \
            (measured; see the note in RebuildScaleTests)

            """)

        // Three times the design's upper figure. Anything near this means something
        // structural changed — a per-row transaction, a dropped index — not a slow machine.
        #expect(elapsed < .seconds(9), "rebuild took \(elapsed); §4 budgets 1–3 s")

        // §3's 4.53 MB prototype predates UUID keys: six 36-character uuid columns per
        // photo row is roughly 8 MB across 34,607 rows, which is the whole difference.
        // The bar is a regression check against that measured figure, not against 4.53 MB.
        #expect(size < 20_000_000, "merged DB is \(size) bytes; ~13 MB is expected")
    }

    /// The queries the grid and the album list run must stay indexed at full scale — an
    /// unindexed sort of 34,607 rows is invisible in a unit test and obvious on a phone.
    @Test("reads stay indexed at full scale", .timeLimit(.minutes(1)))
    func readsStayIndexed() throws {
        let shards = Self.realisticLibrary()
        let directory = try Fixture.temporaryDirectory("scale-read")
        let path = directory.appending(path: "merged.db")
        try CatalogWriter(path: path).rebuild(from: shards)

        let reader = try CatalogReader(path: path)
        let neuseeland = try #require(try reader.allAlbums().first { $0.name == "Neuseeland" })

        let started = ContinuousClock.now
        let photos = try reader.photos(in: neuseeland.id)
        let gridOpen = ContinuousClock.now - started

        #expect(photos.count == 1_755)
        #expect(gridOpen < .milliseconds(500), "opening the largest grid took \(gridOpen)")

        // Ordering must still hold across the whole album, not just the first page.
        let dated = photos.prefix { $0.takenAt != nil }
        #expect(zip(dated, dated.dropFirst()).allSatisfy { $0.takenAt! <= $1.takenAt! })
        #expect(photos.suffix(from: dated.count).allSatisfy { $0.takenAt == nil })
    }
}
