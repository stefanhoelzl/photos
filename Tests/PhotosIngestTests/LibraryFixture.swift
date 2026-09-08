import Foundation
import PhotosCatalog
import PhotosCore
@testable import PhotosIngest
import PhotosLibrary

/// A throwaway library on disk, plus the shards a previous run would have written for it.
///
/// Everything D can get wrong is a question about a directory and a shard disagreeing, so
/// the fixtures are exactly that: real directories with real files, and hand-built shards.
/// No network and no imaging — those are the two things the rules under test never consult.
struct LibraryFixture {

    let root: URL

    init(_ label: String = "library") throws {
        root = URL(fileURLWithPath: NSTemporaryDirectory())
            .appending(path: "photos-ingest-\(label)-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        try marker()
    }

    /// The root marker. Present by default because almost every test wants a library that
    /// *is* a library; `removeMarker()` is what the guard's own test uses.
    func marker(_ contents: String = "# nothing excluded\n") throws {
        try Data(contents.utf8).write(to: root.appending(path: IgnoreRules.filename))
    }

    func removeMarker() throws {
        try FileManager.default.removeItem(at: root.appending(path: IgnoreRules.filename))
    }

    @discardableResult
    func file(_ path: String, bytes: Int = 64) throws -> URL {
        let url = root.appending(path: path)
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(),
                                                withIntermediateDirectories: true)
        try Data(repeating: 0x41, count: bytes).write(to: url)
        return url
    }

    func directory(_ path: String) throws {
        try FileManager.default.createDirectory(at: root.appending(path: path),
                                                withIntermediateDirectories: true)
    }

    func remove(_ path: String) throws {
        try FileManager.default.removeItem(at: root.appending(path: path))
    }

    func walk() throws -> LibraryContents {
        try LibraryWalker(root: root).walk()
    }

    func plan(shards: [Shard] = [], unreadable: [ShardProbe] = [],
              filter: String? = nil) throws -> IngestPlan {
        Reconciler(root: root, albumFilter: filter)
            .plan(contents: try walk(), shards: shards, unreadable: unreadable)
    }

    func cleanUp() {
        try? FileManager.default.removeItem(at: root)
    }

    // MARK: - Shards a previous run would have left

    /// A shard claiming `path`, with one row per name given.
    func shard(_ path: String, photos names: [String], id: UUID = UUID(),
               parent: UUID? = nil, thumbsID: UUID? = UUID(), bytes: Int64 = 64) -> Shard {
        Shard(
            info: AlbumInfo(id: id, name: String(path.split(separator: "/").last ?? ""),
                            parent: parent, sourcePath: path, thumbsID: thumbsID),
            photos: names.map { row($0, bytes: bytes) }
        )
    }

    /// A still: the blob in the zone *is* the file on disk, so its size is checkable.
    func row(_ name: String, bytes: Int64 = 64) -> PhotoRow {
        PhotoRow(filename: name, bytes: bytes, mediaType: .photo,
                 originalID: UUID(), previewID: UUID())
    }

    /// A video row: named after the transcode, sized after the transcode, with the camera's
    /// filename kept as the source. There is no original in the zone, so nothing on disk
    /// should equal `bytes` and the assertion must leave it alone.
    func videoRow(source: String, bytes: Int64 = 999_999) -> PhotoRow {
        PhotoRow(filename: (source as NSString).deletingPathExtension + ".mp4",
                 sourceFilename: source, bytes: bytes, mediaType: .video,
                 previewID: UUID(), videoID: UUID())
    }

    /// A carved CR2: `.jpg` in the zone, `.CR2` on disk.
    func rawRow(source: String, bytes: Int64 = 1_600_000) -> PhotoRow {
        PhotoRow(filename: (source as NSString).deletingPathExtension + ".jpg",
                 sourceFilename: source, bytes: bytes, mediaType: .photo,
                 originalID: UUID(), previewID: UUID())
    }
}
