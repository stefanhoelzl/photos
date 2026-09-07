import Foundation

public enum ShardError: Error, Hashable, Sendable, CustomStringConvertible {
    /// The shard was written by a newer schema than this build understands.
    ///
    /// Not a corruption: the caller skips the album and reports it. The CLI must record it
    /// as *unreadable*, never *absent* — an absent album gets re-uploaded as a new one, and
    /// with UUID keys nothing collides to stop the duplicate (§3).
    case unsupportedVersion(found: Int, supported: Int)
    /// No `album_info` row, so the file is not a shard at all.
    case missingAlbumInfo
    case malformed(String)

    public var description: String {
        switch self {
        case .unsupportedVersion(let found, let supported):
            "shard schema version \(found) is newer than this build supports (\(supported))"
        case .missingAlbumInfo:
            "shard has no album_info row"
        case .malformed(let detail):
            "malformed shard: \(detail)"
        }
    }
}

/// Builds a shard's bytes, ready to PUT at `meta/<album-uuid>.db`.
///
/// The whole shard is rewritten every time; there is no incremental path, which is what
/// makes stale rows impossible by construction (§2's single-owner rule).
public enum ShardWriter {

    public static func write(_ shard: Shard) throws -> Data {
        let database = try Database(path: nil, options: .inMemory)
        try database.execute(CatalogSchema.shardDDL)

        try database.transaction {
            let info = shard.info
            try database.run(
                "INSERT INTO album_info (id, \(CatalogSchema.albumInfoColumns)) "
                + "VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?)",
                [
                    .text(info.id.catalogString),
                    .text(info.name),
                    SQLiteValue(info.parent),
                    SQLiteValue(info.sourcePath),
                    SQLiteValue(info.coverPhotoID),
                    SQLiteValue(info.thumbsID),
                    .integer(Int64(info.addedAt.timeIntervalSince1970.rounded())),
                    .integer(Int64(info.schemaVersion)),
                ]
            )

            let insert = try database.prepare(
                "INSERT INTO photo (\(CatalogSchema.photoColumns)) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
            )
            for photo in shard.photos {
                try insert.bind(photo.bindings)
                while try insert.step() {}
            }
        }

        return try database.serialized()
    }
}

/// Reads a shard back out of its bytes.
public enum ShardReader {

    public static func read(_ data: Data) throws -> Shard {
        let database = try Database.deserialized(data)
        return try read(from: database)
    }

    static func read(from database: Database) throws -> Shard {
        // A file with no `album_info` at all is not a shard — a truncated download, a
        // wrong key, an unrelated database. That is `missingAlbumInfo`, not a raw SQLite
        // error, so a caller can tell "not a shard" from "SQLite is unwell".
        guard try hasAlbumInfo(database) else { throw ShardError.missingAlbumInfo }

        let info = try database.queryOne(
            "SELECT \(CatalogSchema.albumInfoColumns) FROM album_info WHERE id = 1"
        ) { row -> AlbumInfo in
            let version = Int(row.int(7))
            guard version <= CatalogSchema.version else {
                throw ShardError.unsupportedVersion(found: version, supported: CatalogSchema.version)
            }
            guard let id = row.uuidOrNil(0) else {
                throw ShardError.malformed("album_id is not a uuid")
            }
            return AlbumInfo(
                id: id,
                name: row.string(1),
                parent: row.uuidOrNil(2),
                sourcePath: row.stringOrNil(3),
                coverPhotoID: row.uuidOrNil(4),
                thumbsID: row.uuidOrNil(5),
                addedAt: Date(timeIntervalSince1970: TimeInterval(row.int(6))),
                schemaVersion: version
            )
        }
        guard let info else { throw ShardError.missingAlbumInfo }

        let photos = try database.query(
            "SELECT \(CatalogSchema.photoColumns) FROM photo"
        ) { try PhotoRow(row: $0) }

        return Shard(info: info, photos: photos)
    }

    static func hasAlbumInfo(_ database: Database) throws -> Bool {
        try database.queryOne(
            "SELECT 1 FROM sqlite_schema WHERE type = 'table' AND name = 'album_info'"
        ) { _ in true } ?? false
    }

    /// The schema version alone, without decoding the rest.
    ///
    /// Lets a caller decide to skip a shard before paying to read it.
    public static func schemaVersion(of data: Data) throws -> Int {
        let database = try Database.deserialized(data)
        guard try hasAlbumInfo(database) else { throw ShardError.missingAlbumInfo }
        let version = try database.queryOne("SELECT schema_version FROM album_info WHERE id = 1") {
            Int($0.int(0))
        }
        guard let version else { throw ShardError.missingAlbumInfo }
        return version
    }
}

// MARK: - Row mapping

extension PhotoRow {
    var bindings: [SQLiteValue] {
        [
            .text(id.catalogString),
            .text(filename),
            SQLiteValue(takenAt.map { Int64($0.timeIntervalSince1970.rounded()) }),
            SQLiteValue(latitude),
            SQLiteValue(longitude),
            SQLiteValue(width),
            SQLiteValue(height),
            SQLiteValue(bytes),
            .integer(Int64(mediaType.rawValue)),
            SQLiteValue(originalID),
            SQLiteValue(liveVideoID),
            SQLiteValue(previewID),
            SQLiteValue(videoID),
        ]
    }

    /// Column order is `CatalogSchema.photoColumns`, which both the shard and the merged DB
    /// select in that order — so one initialiser serves both.
    init(row: Statement) throws {
        guard let id = row.uuidOrNil(0) else { throw ShardError.malformed("photo.id is not a uuid") }
        guard let mediaType = MediaType(rawValue: Int(row.int(8))) else {
            throw ShardError.malformed("unknown media_type \(row.int(8))")
        }
        self.init(
            id: id,
            filename: row.string(1),
            takenAt: row.intOrNil(2).map { Date(timeIntervalSince1970: TimeInterval($0)) },
            latitude: row.doubleOrNil(3),
            longitude: row.doubleOrNil(4),
            width: row.intOrNil(5).map(Int.init),
            height: row.intOrNil(6).map(Int.init),
            bytes: row.intOrNil(7),
            mediaType: mediaType,
            originalID: row.uuidOrNil(9),
            liveVideoID: row.uuidOrNil(10),
            previewID: row.uuidOrNil(11),
            videoID: row.uuidOrNil(12)
        )
    }
}
