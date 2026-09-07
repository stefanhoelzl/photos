import Foundation
import Testing
@testable import PhotosCatalog

/// The merged DB is what every screen reads. §4 rebuilds it wholesale so stale rows are
/// impossible by construction — these check that the construction actually holds.
struct MergedCatalogTests {

    private func build(_ shards: [Shard]) throws -> (CatalogReader, RebuildSummary, URL) {
        let directory = try Fixture.temporaryDirectory("merged")
        let path = directory.appending(path: "merged.db")
        let writer = try CatalogWriter(path: path)
        let summary = try writer.rebuild(from: shards)
        return (try CatalogReader(path: path), summary, directory)
    }

    @Test("albums and photos both land")
    func rebuildsEverything() throws {
        let shards = [
            Fixture.album("Neuseeland", photos: (0..<5).map { Fixture.photo("IMG_\($0).jpg") }),
            Fixture.album("Island", photos: (0..<3).map { Fixture.photo("DSC_\($0).jpg") }),
        ]
        let (reader, summary, _) = try build(shards)

        #expect(summary.albums == 2)
        #expect(summary.photos == 8)
        #expect(try reader.allAlbums().count == 2)
        #expect(try reader.photoCount() == 8)
    }

    /// §4's rebuild has no incremental path. Replaying a *smaller* set must leave nothing
    /// behind from the larger one, or "stale rows are impossible" is merely a wish.
    @Test("a rebuild replaces rather than accumulates")
    func rebuildIsWholesale() throws {
        let directory = try Fixture.temporaryDirectory("wholesale")
        let path = directory.appending(path: "merged.db")
        let writer = try CatalogWriter(path: path)

        try writer.rebuild(from: [
            Fixture.album("First", photos: (0..<10).map { Fixture.photo("a\($0).jpg") }),
            Fixture.album("Second", photos: (0..<10).map { Fixture.photo("b\($0).jpg") }),
        ])
        try writer.rebuild(from: [Fixture.album("Only", photos: [Fixture.photo("c.jpg")])])

        let reader = try CatalogReader(path: path)
        #expect(try reader.allAlbums().map(\.name) == ["Only"])
        #expect(try reader.photoCount() == 1)
    }

    @Test("the same shards rebuilt twice give the same catalog")
    func rebuildIsIdempotent() throws {
        let shards = Fixture.containerTree("Rauhöd", children: ["2019", "2020", "2021"])
        let directory = try Fixture.temporaryDirectory("idempotent")
        let path = directory.appending(path: "merged.db")
        let writer = try CatalogWriter(path: path)

        try writer.rebuild(from: shards)
        let first = try CatalogReader(path: path).allAlbums()
        try writer.rebuild(from: shards.reversed())
        let second = try CatalogReader(path: path).allAlbums()

        #expect(first == second)
    }

    // MARK: - Hierarchy

    @Test("children hang off their container")
    func hierarchy() throws {
        let shards = Fixture.containerTree("Kalifornien", children: ["Yosemite", "Big Sur"])
        let container = shards[0]
        let (reader, _, _) = try build(shards)

        #expect(try reader.albums(under: nil).map(\.name) == ["Kalifornien"])
        #expect(Set(try reader.albums(under: container.info.id).map(\.name)) == ["Yosemite", "Big Sur"])
    }

    /// §2: no album may become unreachable because one object failed to arrive. A dangling
    /// parent surfaces the album at the root and is reported — it is not an error and it is
    /// not a hidden album.
    @Test("an album whose parent shard is missing surfaces at the root")
    func danglingParentSurfaces() throws {
        let shards = Fixture.containerTree("Immling", children: ["2003", "2004"])
        let orphans = Array(shards.dropFirst())     // the container never arrives
        let (reader, summary, _) = try build(orphans)

        #expect(Set(try reader.albums(under: nil).map(\.name)) == ["2003", "2004"])
        #expect(Set(summary.orphanedAlbums) == Set(orphans.map(\.info.id)))
        for album in try reader.allAlbums() { #expect(album.parent == nil) }
    }

    // MARK: - Ordering

    /// §3: oldest first, undated last by filename. There is no stored sort column, so this
    /// is checking the query and its expression index agree with the rule.
    @Test("photos come back oldest first, undated last by filename")
    func photoOrder() throws {
        let base = Date(timeIntervalSince1970: 1_000_000_000)
        let photos = [
            Fixture.photo("c.jpg", takenAt: base.addingTimeInterval(200)),
            Fixture.photo("a.jpg", takenAt: base),
            Fixture.photo("z_undated.jpg", takenAt: nil),
            Fixture.photo("b.jpg", takenAt: base.addingTimeInterval(100)),
            Fixture.photo("m_undated.jpg", takenAt: nil),
        ]
        let shard = Fixture.album("Mixed", photos: photos)
        let (reader, _, _) = try build([shard])

        #expect(try reader.photos(in: shard.info.id).map(\.filename)
                == ["a.jpg", "b.jpg", "c.jpg", "m_undated.jpg", "z_undated.jpg"])
    }

    @Test("the ordering index is actually used")
    func orderingUsesTheIndex() throws {
        let shard = Fixture.album("Big", photos: (0..<50).map { Fixture.photo("IMG_\($0).jpg") })
        let directory = try Fixture.temporaryDirectory("plan")
        let path = directory.appending(path: "merged.db")
        try CatalogWriter(path: path).rebuild(from: [shard])

        // If the query and the index ever state the rule differently, the planner silently
        // stops using the index — costing speed, not correctness. This is what notices.
        let database = try Database(path: path.path, options: .init(readOnly: true))
        let plan = try database.query(
            "EXPLAIN QUERY PLAN SELECT id FROM photo WHERE album_id = ? "
            + "ORDER BY \(CatalogSchema.photoOrder)",
            [.text(shard.info.id.catalogString)]
        ) { $0.string(3) }

        #expect(plan.contains { $0.contains("ix_photo_album") })
        #expect(!plan.contains { $0.contains("TEMP B-TREE") })
    }

    // MARK: - Covers

    @Test("the default cover is the album's earliest photo")
    func defaultCover() throws {
        let base = Date(timeIntervalSince1970: 1_000_000_000)
        let photos = [
            Fixture.photo("late.jpg", takenAt: base.addingTimeInterval(500)),
            Fixture.photo("early.jpg", takenAt: base),
        ]
        let shard = Fixture.album("Trip", photos: photos)
        let (reader, _, _) = try build([shard])

        #expect(try reader.coverPhoto(of: shard.info.id)?.filename == "early.jpg")
    }

    @Test("an explicit cover wins")
    func explicitCover() throws {
        let photos = [Fixture.photo("a.jpg"), Fixture.photo("chosen.jpg")]
        let shard = Fixture.album("Trip", photos: photos, coverPhotoID: photos[1].id)
        let (reader, _, _) = try build([shard])

        #expect(try reader.coverPhoto(of: shard.info.id)?.filename == "chosen.jpg")
    }

    /// §3: a container owns no photos, so its cover is found by descending into children.
    @Test("a container's cover is found by descending into its children")
    func containerCoverDescends() throws {
        let shards = Fixture.containerTree("Weihnachten", children: ["2002", "2003"])
        let (reader, _, _) = try build(shards)

        let cover = try reader.coverPhoto(of: shards[0].info.id)
        #expect(cover != nil)
        #expect(cover?.filename.hasPrefix("2002_") == true)   // earliest across the subtree
    }

    @Test("an explicit cover on a container is not overridden by the descent")
    func containerExplicitCover() throws {
        var shards = Fixture.containerTree("Weihnachten", children: ["2002", "2003"])
        let chosen = shards[2].photos[1]
        shards[0].info.coverPhotoID = chosen.id
        let (reader, _, _) = try build(shards)

        #expect(try reader.coverPhoto(of: shards[0].info.id)?.id == chosen.id)
    }

    // MARK: - Locations

    /// §3: the pin is computed at rebuild from photo rows, never stored in a shard — so it
    /// cannot drift from the photos beneath it.
    @Test("an album's pin is the centroid of its tagged photos")
    func albumCentroid() throws {
        let photos = [
            Fixture.photo("a.jpg", latitude: 10, longitude: 20),
            Fixture.photo("b.jpg", latitude: 20, longitude: 40),
        ]
        let shard = Fixture.album("Somewhere", photos: photos)
        let (reader, _, _) = try build([shard])

        let album = try #require(try reader.album(shard.info.id))
        #expect(album.latitude == 15)
        #expect(album.longitude == 30)
    }

    @Test("untagged photos do not drag the centroid toward zero")
    func centroidIgnoresUntagged() throws {
        let photos = [
            Fixture.photo("a.jpg", latitude: 47.99, longitude: 12.26),
            Fixture.photo("b.jpg", latitude: nil, longitude: nil),
            Fixture.photo("c.jpg", latitude: nil, longitude: nil),
        ]
        let shard = Fixture.album("Mostly Untagged", photos: photos)
        let (reader, _, _) = try build([shard])

        let album = try #require(try reader.album(shard.info.id))
        #expect(abs(try #require(album.latitude) - 47.99) < 0.0001)
    }

    /// This replaces §3's old downward cascade: placing a container happens by placing its
    /// descendants' photos, and the container's pin rolls up from them.
    @Test("a container's pin rolls up from its descendants")
    func containerRollsUp() throws {
        var shards = Fixture.containerTree("Rauhöd", children: ["2019", "2020"], photosEach: 1)
        shards[1].photos[0].latitude = 10
        shards[1].photos[0].longitude = 20
        shards[2].photos[0].latitude = 20
        shards[2].photos[0].longitude = 40

        let (reader, _, _) = try build(shards)
        let container = try #require(try reader.album(shards[0].info.id))

        #expect(container.photoCount == 0)      // owns none itself
        #expect(container.latitude == 15)       // but is placed by its children
        #expect(container.longitude == 30)
    }

    @Test("an album with no tagged photos is not on the map")
    func unplacedAlbum() throws {
        let photos = [Fixture.photo("a.jpg", latitude: nil, longitude: nil)]
        let shard = Fixture.album("Weihnachten", photos: photos)
        let (reader, _, _) = try build([shard])

        #expect(try reader.album(shard.info.id)?.latitude == nil)
        #expect(try reader.placedAlbums().isEmpty)
    }

    // MARK: - Search

    @Test("search is case- and diacritic-insensitive")
    func foldedSearch() throws {
        let shards = ["Grün", "Rauhöd", "Straße", "Kalifornien"].map { Fixture.album($0) }
        let (reader, _, _) = try build(shards)

        #expect(try reader.searchAlbums("grun").map(\.name) == ["Grün"])
        #expect(try reader.searchAlbums("RAUHOD").map(\.name) == ["Rauhöd"])
        #expect(try reader.searchAlbums("strasse").map(\.name) == ["Straße"])
        #expect(try reader.searchAlbums("kali").map(\.name) == ["Kalifornien"])
    }

    @Test("substring, not prefix")
    func substringSearch() throws {
        let (reader, _, _) = try build([Fixture.album("Neuseeland 2019")])
        #expect(try reader.searchAlbums("seeland").count == 1)
    }

    /// A name containing `%` would otherwise match everything.
    @Test("LIKE wildcards in the query text are escaped")
    func escapesWildcards() throws {
        let (reader, _, _) = try build([Fixture.album("Rabatt 50%"), Fixture.album("Andere")])

        #expect(try reader.searchAlbums("50%").map(\.name) == ["Rabatt 50%"])
        #expect(try reader.searchAlbums("%").count == 1)
    }

    @Test("an empty query matches nothing rather than everything")
    func emptySearch() throws {
        let (reader, _, _) = try build([Fixture.album("Anything")])
        #expect(try reader.searchAlbums("").isEmpty)
    }

    @Test("the fold handles §3's three examples")
    func foldingExamples() {
        #expect(foldedName("Grün") == "grun")
        #expect(foldedName("Rauhöd") == "rauhod")
        #expect(foldedName("Straße") == "strasse")
    }

    // MARK: - Duplicates

    /// §2: two devices creating one album no longer collide on a key. Both are shown and
    /// reported; merging would be a destructive guess about intent.
    @Test("duplicate names under one parent are reported, and both albums survive")
    func duplicatesAreReportedNotMerged() throws {
        let shards = [
            Fixture.album("Sommer", photos: [Fixture.photo("a.jpg")]),
            Fixture.album("Sommer", photos: [Fixture.photo("b.jpg"), Fixture.photo("c.jpg")]),
            Fixture.album("Winter"),
        ]
        let (reader, _, _) = try build(shards)

        #expect(try reader.allAlbums().count == 3)
        let duplicates = try reader.duplicateNames()
        #expect(duplicates.count == 1)
        #expect(duplicates.first?.name == "Sommer")
        #expect(duplicates.first?.albums.count == 2)
        #expect(try reader.photoCount() == 3)
    }

    @Test("the same name under different parents is not a duplicate")
    func sameNameDifferentParents() throws {
        var shards = Fixture.containerTree("A", children: ["2019"], photosEach: 1)
        shards += Fixture.containerTree("B", children: ["2019"], photosEach: 1)
        let (reader, _, _) = try build(shards)

        #expect(try reader.duplicateNames().isEmpty)
    }
}
