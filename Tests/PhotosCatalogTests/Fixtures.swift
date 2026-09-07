import Foundation
@testable import PhotosCatalog

/// Builds synthetic catalogs.
///
/// §10 verifies B with generated fixtures rather than committed `.db` files: nothing goes
/// stale, and a diff of a test stays readable. The one thing a writer-and-reader round trip
/// structurally cannot see — a shard written by a *newer* schema — is covered by
/// `futureShard`, which forges the version rather than shipping a binary.
enum Fixture {

    /// A photo with everything filled in, so a round trip proves every column.
    static func photo(
        _ name: String,
        takenAt: Date? = Date(timeIntervalSince1970: 1_372_961_531),
        latitude: Double? = 47.994563,
        longitude: Double? = 12.268569,
        mediaType: MediaType = .photo
    ) -> PhotoRow {
        PhotoRow(
            filename: name,
            takenAt: takenAt,
            latitude: latitude,
            longitude: longitude,
            width: 4000, height: 3000,
            bytes: 3_145_728,
            mediaType: mediaType,
            originalID: UUID(),
            liveVideoID: mediaType == .livePhoto ? UUID() : nil,
            previewID: UUID(),
            videoID: mediaType == .video ? UUID() : nil
        )
    }

    static func album(
        _ name: String,
        parent: UUID? = nil,
        photos: [PhotoRow] = [],
        coverPhotoID: UUID? = nil,
        thumbsID: UUID? = UUID()
    ) -> Shard {
        Shard(
            info: AlbumInfo(
                name: name,
                parent: parent,
                sourcePath: name,
                coverPhotoID: coverPhotoID,
                thumbsID: thumbsID
            ),
            photos: photos
        )
    }

    /// A container and its children, matching INGEST.md's shape: containers hold no photos
    /// of their own, and the XOR rule means a mixed album never exists.
    static func containerTree(
        _ name: String, children: [String], photosEach: Int = 3
    ) -> [Shard] {
        let container = album(name, photos: [], thumbsID: nil)
        let leaves = children.map { child in
            album(
                child,
                parent: container.info.id,
                photos: (0..<photosEach).map { index in
                    photo(
                        "\(child)_\(String(format: "%04d", index)).jpg",
                        takenAt: Date(timeIntervalSince1970: 1_372_961_531 + Double(index) * 3600)
                    )
                }
            )
        }
        return [container] + leaves
    }

    /// A shard claiming a schema version this build does not support.
    ///
    /// Written by hand rather than by `ShardWriter`, which always stamps the current
    /// version — the whole point is a file the current code could not have produced.
    static func futureShard(version: Int = CatalogSchema.version + 1) throws -> (UUID, Data) {
        let id = UUID()
        let database = try Database(path: nil, options: .inMemory)
        try database.execute(CatalogSchema.shardDDL)
        try database.run(
            "INSERT INTO album_info (id, \(CatalogSchema.albumInfoColumns)) "
            + "VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?)",
            [
                .text(id.catalogString), .text("From The Future"), .null, .null, .null, .null,
                .integer(0), .integer(Int64(version)),
            ]
        )
        return (id, try database.serialized())
    }

    /// A scratch directory that cleans itself up.
    static func temporaryDirectory(_ label: String = "catalog") throws -> URL {
        let url = URL(fileURLWithPath: NSTemporaryDirectory())
            .appending(path: "photos-tests-\(label)-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }
}
