import Foundation
import PhotosCatalog
import PhotosCore
@testable import PhotosIngest
import Testing

/// The rules that decide what the zone should contain.
///
/// This is the file that matters most in D: every rule here can lose photographs, and none
/// of them needs a network or an encoder to be wrong. §7's whole safety story is four
/// sentences — a `stat` decides existence, `.photosignore` decides uploads, a missing
/// directory deletes, and no marker means no run — and each of them is asserted below.
@Suite("Reconciler")
struct ReconcilerTests {

    // MARK: - Shape

    @Test("every directory holding media becomes an album")
    func leafAlbums() throws {
        let library = try LibraryFixture()
        defer { library.cleanUp() }
        try library.file("Rauhöd 2019/a.jpg")
        try library.file("Rauhöd 2019/b.jpg")
        try library.file("Neuseeland/c.jpg")

        let plan = try library.plan()
        #expect(plan.albums.map(\.sourcePath) == ["Neuseeland", "Rauhöd 2019"])
        #expect(plan.albums.filter(\.isNew).count == plan.albums.count)
        #expect(plan.uploadCount == 3)
    }

    /// §10: `LibraryWalker` emits nothing for a directory that holds only sub-directories,
    /// so `Kalifornien` — 17 children, no photos of its own — has to be invented here or the
    /// hierarchy has a hole in it.
    @Test("a container with no photos of its own is synthesised, and parents its children")
    func containerAlbums() throws {
        let library = try LibraryFixture()
        defer { library.cleanUp() }
        try library.file("Kalifornien/Yosemite/a.jpg")
        try library.file("Kalifornien/Big Sur/b.jpg")

        let plan = try library.plan()
        #expect(plan.albums.map(\.sourcePath)
                == ["Kalifornien", "Kalifornien/Big Sur", "Kalifornien/Yosemite"])

        let container = try #require(plan.albums.first { $0.sourcePath == "Kalifornien" })
        #expect(container.parent == nil)
        #expect(container.uploads.isEmpty)
        for child in plan.albums where child.sourcePath != "Kalifornien" {
            #expect(child.parent == container.id)
        }
    }

    /// §2: an album has sub-albums XOR photos. The children are unambiguous, so they go up;
    /// the loose files are not, so they do not.
    @Test("a mixed folder loses its own files, never its children")
    func mixedFolder() throws {
        let library = try LibraryFixture()
        defer { library.cleanUp() }
        try library.file("Weihnachten/stray.jpg")
        try library.file("Weihnachten/2002/a.jpg")

        let plan = try library.plan()
        let parent = try #require(plan.albums.first { $0.sourcePath == "Weihnachten" })
        #expect(parent.uploads.isEmpty)
        #expect(parent.mixedFileCount == 1)

        let child = try #require(plan.albums.first { $0.sourcePath == "Weihnachten/2002" })
        #expect(child.uploads.count == 1)
    }

    /// A stray beside the album folders must not re-parent all 240 of them under a new root
    /// album, so the root is never an album at all.
    @Test("loose files at the library root are reported, not ingested")
    func looseRootFiles() throws {
        let library = try LibraryFixture()
        defer { library.cleanUp() }
        try library.file("stray.jpg")
        try library.file("Neuseeland/a.jpg")

        let plan = try library.plan()
        #expect(plan.looseRootFiles.count == 1)
        #expect(plan.albums.map(\.sourcePath) == ["Neuseeland"])
        #expect(plan.albums[0].parent == nil)
    }

    // MARK: - Adding and dropping

    @Test("a new file in a known album is the only thing uploaded")
    func addsOneFile() throws {
        let library = try LibraryFixture()
        defer { library.cleanUp() }
        try library.file("Rauhöd/a.jpg")
        try library.file("Rauhöd/b.jpg")
        let shard = library.shard("Rauhöd", photos: ["a.jpg"])

        let plan = try library.plan(shards: [shard])
        let album = try #require(plan.albums.first)
        #expect(album.id == shard.info.id)          // identity survives; no new album
        #expect(album.uploads.map(\.lastPathComponent) == ["b.jpg"])
        #expect(album.keep.count == 1)
        #expect(album.drop.isEmpty)
    }

    @Test("a deleted file drops its row and leaves the rest alone")
    func dropsOneRow() throws {
        let library = try LibraryFixture()
        defer { library.cleanUp() }
        try library.file("Rauhöd/a.jpg")
        let shard = library.shard("Rauhöd", photos: ["a.jpg", "b.jpg"])

        let plan = try library.plan(shards: [shard])
        let album = try #require(plan.albums.first)
        #expect(album.drop.map(\.filename) == ["b.jpg"])
        #expect(album.keep.map(\.filename) == ["a.jpg"])
        #expect(album.uploads.isEmpty)
    }

    /// The rule the whole deletion model rests on. `.photosignore` says what may be
    /// *uploaded*; existence is a `stat`. If the two were the same question, broadening a
    /// rule would silently delete every photo it newly matched.
    @Test("an ignored file that still exists is not a deletion")
    func ignoredIsNotAbsent() throws {
        let library = try LibraryFixture()
        defer { library.cleanUp() }
        try library.marker("*.jpg\n")
        try library.file("Rauhöd/a.jpg")
        let shard = library.shard("Rauhöd", photos: ["a.jpg"])

        let plan = try library.plan(shards: [shard])
        let album = try #require(plan.albums.first)
        #expect(album.drop.isEmpty)        // the file is there; the rule only stops uploads
        #expect(album.keep.count == 1)
        #expect(album.uploads.isEmpty)
        #expect(plan.deletions.isEmpty)
    }

    /// `rm album/*` is not how an album is deleted. The directory is still there, so the
    /// album is still there — with nothing in it.
    @Test("an album emptied of files survives with zero photos")
    func emptiedAlbumSurvives() throws {
        let library = try LibraryFixture()
        defer { library.cleanUp() }
        try library.directory("Rauhöd")
        let shard = library.shard("Rauhöd", photos: ["a.jpg", "b.jpg"])

        let plan = try library.plan(shards: [shard])
        #expect(plan.deletions.isEmpty)
        let album = try #require(plan.albums.first)
        #expect(album.drop.count == 2)
        #expect(album.keep.isEmpty)
        #expect(album.id == shard.info.id)
    }

    /// `rm -rf` is the unambiguous gesture, and the only one that means this.
    @Test("a directory that is gone deletes the album")
    func missingDirectoryDeletes() throws {
        let library = try LibraryFixture()
        defer { library.cleanUp() }
        try library.file("Neuseeland/a.jpg")
        let gone = library.shard("Rauhöd", photos: ["a.jpg"])
        let kept = library.shard("Neuseeland", photos: ["a.jpg"])

        let plan = try library.plan(shards: [gone, kept])
        #expect(plan.deletions.map(\.sourcePath) == ["Rauhöd"])
        #expect(plan.albums.map(\.sourcePath) == ["Neuseeland"])
    }

    /// §7's decision 3, in one test: identity comes from `source_path` alone, so a rename is
    /// an upload and a deletion rather than a metadata write. Expensive and never wrong.
    @Test("a renamed folder is a new album plus a deletion")
    func renameIsNewPlusDelete() throws {
        let library = try LibraryFixture()
        defer { library.cleanUp() }
        try library.file("Neuseeland 2019/a.jpg")
        let old = library.shard("Neuseeland", photos: ["a.jpg"])

        let plan = try library.plan(shards: [old])
        #expect(plan.deletions.map(\.sourcePath) == ["Neuseeland"])
        let album = try #require(plan.albums.first)
        #expect(album.sourcePath == "Neuseeland 2019")
        #expect(album.isNew)
        #expect(album.id != old.info.id)
    }

    // MARK: - The byte-size assertion

    @Test("a file whose size changed aborts, naming the file")
    func byteMismatchIsRecorded() throws {
        let library = try LibraryFixture()
        defer { library.cleanUp() }
        try library.file("Rauhöd/a.jpg", bytes: 128)
        let shard = library.shard("Rauhöd", photos: ["a.jpg"], bytes: 64)

        let plan = try library.plan(shards: [shard])
        #expect(plan.mismatches.count == 1)
        #expect(plan.mismatches[0].filename == "a.jpg")
        #expect(plan.mismatches[0].recorded == 64)
        #expect(plan.mismatches[0].found == 128)
    }

    /// §3: `bytes` describes the blob a tap fetches. For a video that is the transcode and
    /// for a carved RAW the extracted JPEG, so neither has anything on disk it should equal.
    @Test("video and carved-RAW rows are never size-checked")
    func derivativeRowsAreNotChecked() throws {
        let library = try LibraryFixture()
        defer { library.cleanUp() }
        try library.file("Kalifornien/IMG_1234.CR2", bytes: 22_000)
        try library.file("Kalifornien/VID_0001.MOV", bytes: 41_000)
        var shard = library.shard("Kalifornien", photos: [])
        shard.photos = [library.rawRow(source: "IMG_1234.CR2"),
                        library.videoRow(source: "VID_0001.MOV")]

        let plan = try library.plan(shards: [shard])
        #expect(plan.mismatches.isEmpty)
        let album = try #require(plan.albums.first)
        #expect(album.keep.count == 2)     // found by their source names
        #expect(album.uploads.isEmpty)
        #expect(album.drop.isEmpty)
    }

    /// After a pull, `IMG_1234.CR2` is gone and `IMG_1234.jpg` is what is on disk. The row
    /// has to survive that, or the next run deletes it and uploads the JPEG as new.
    @Test("a row matches its zone name too, so a pulled album still reconciles")
    func matchesZoneName() throws {
        let library = try LibraryFixture()
        defer { library.cleanUp() }
        try library.file("Kalifornien/IMG_1234.jpg", bytes: 1_600_000)
        var shard = library.shard("Kalifornien", photos: [])
        shard.photos = [library.rawRow(source: "IMG_1234.CR2")]

        let plan = try library.plan(shards: [shard])
        let album = try #require(plan.albums.first)
        #expect(album.drop.isEmpty)
        #expect(album.uploads.isEmpty)
    }

    // MARK: - Shards this build cannot read

    /// §3's hazard: a shard too new to read must be *unreadable*, never *absent*. Its
    /// directory is left completely alone — not uploaded, not deleted, not re-minted.
    @Test("a directory claimed by a too-new shard is left entirely alone")
    func unreadableShardBlocksItsDirectory() throws {
        let library = try LibraryFixture()
        defer { library.cleanUp() }
        try library.file("Neuseeland/a.jpg")
        let probe = ShardProbe(albumID: UUID(), sourcePath: "Neuseeland",
                               schemaVersion: CatalogSchema.version + 1)

        let plan = try library.plan(shards: [], unreadable: [probe])
        #expect(plan.albums.isEmpty)
        #expect(plan.deletions.isEmpty)
        #expect(plan.blockedByUnreadable.count == 1)
    }

    // MARK: - Scope

    /// A scoped run that deleted everything outside its scope would be a trap.
    @Test("--album scopes deletions as well as uploads")
    func filterScopesDeletions() throws {
        let library = try LibraryFixture()
        defer { library.cleanUp() }
        try library.file("Kalifornien/a.jpg")
        let outside = library.shard("Neuseeland", photos: ["a.jpg"])   // directory is gone

        let plan = try library.plan(shards: [outside], filter: "Kalifornien")
        #expect(plan.deletions.isEmpty)
        #expect(plan.albums.map(\.sourcePath) == ["Kalifornien"])
    }

    // MARK: - Pulling the phone's albums down

    @Test("an album with no source_path is pulled, into a name that is free")
    func pullsPhoneAlbums() throws {
        let library = try LibraryFixture()
        defer { library.cleanUp() }
        try library.file("Wochenende/a.jpg")
        let mine = library.shard("Wochenende", photos: ["a.jpg"])
        let phone = Shard(info: AlbumInfo(name: "Wochenende", sourcePath: nil),
                          photos: [library.row("p.jpg")])

        let plan = try library.plan(shards: [mine, phone])
        let pull = try #require(plan.pulls.first)
        #expect(pull.shard.info.id == phone.info.id)
        // §2 permits duplicate names, so the phone's album cannot simply land on top of the
        // laptop's directory of the same name.
        #expect(pull.sourcePath != "Wochenende")
        #expect(pull.sourcePath.hasPrefix("Wochenende ("))
    }

    @Test("a phone album is never a deletion candidate, having never had a directory")
    func phoneAlbumsAreNotDeletions() throws {
        let library = try LibraryFixture()
        defer { library.cleanUp() }
        let phone = Shard(info: AlbumInfo(name: "Garten", sourcePath: nil),
                          photos: [library.row("p.jpg")])

        let plan = try library.plan(shards: [phone])
        #expect(plan.deletions.isEmpty)
        #expect(plan.pulls.count == 1)
    }
}
