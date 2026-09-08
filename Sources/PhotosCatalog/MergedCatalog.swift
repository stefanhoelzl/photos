import Foundation
import PhotosCore

/// Folds a name for search: lowercased and diacritic-stripped.
///
/// Verified on this toolchain: `Grün→grun`, `Rauhöd→rauhod`, `café→cafe`, `Straße→strasse`
/// — ß→ss falls out of case folding, so §3's three examples need no special-casing.
///
/// This value lives only in the merged database, which is per-device and never uploaded, so
/// an ICU difference between iOS and Linux cannot corrupt anything. At worst a search
/// behaves slightly differently on one device.
public func foldedName(_ name: String) -> String {
    name.folding(options: [.diacriticInsensitive, .caseInsensitive], locale: nil)
}

/// The merged database's write side (§3): the rebuild, and nothing else.
///
/// The one write connection, and — like `CatalogReader` — a non-`Sendable` `final class`
/// confined to a single task. `CatalogSync` is the actor that confines it in practice.
/// Readers never go through here; they open their own connection, so a 1–3 s rebuild never
/// puts the album list behind an `await`.
public final class CatalogWriter {
    private let database: Database
    public let path: URL

    public init(path: URL) throws {
        try FileManager.default.createDirectory(
            at: path.deletingLastPathComponent(), withIntermediateDirectories: true
        )
        self.path = path
        self.database = try Database(path: path.path)
        try database.execute(CatalogSchema.mergedDDL)
    }

    /// Replays every shard into the merged database, wholesale.
    ///
    /// In place and inside one transaction, not via a temp file and a rename: there is only
    /// ever one merged DB on disk, and SQLite's guarantee that readers see the old contents
    /// until commit is what makes the swap atomic (§4). WAL is what keeps those readers
    /// unblocked while it runs.
    ///
    /// Shards arrive already parsed. A shard too new to read never reaches here — the sync
    /// skips it and reports it — so anything in `shards` is replayable.
    @discardableResult
    public func rebuild(from shards: [Shard]) throws -> RebuildSummary {
        let present = Set(shards.map(\.info.id))

        try database.transaction {
            try database.execute("DELETE FROM photo; DELETE FROM album;")

            let insertAlbum = try database.prepare(
                """
                INSERT INTO album (album_id, name, name_folded, parent, photo_count,
                                   date_min, date_max, lat, lon, cover_photo_id, thumbs_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """
            )
            let insertPhoto = try database.prepare(
                "INSERT INTO photo (album_id, \(CatalogSchema.photoColumns)) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
            )

            for shard in shards {
                let info = shard.info
                // A parent naming a shard that is not here is not an error: the album
                // surfaces at the root and the sync reports it, so no album can become
                // unreachable because one object failed to arrive (§2).
                let parent = info.parent.flatMap { present.contains($0) ? $0 : nil }

                let dates = shard.photos.compactMap(\.takenAt)
                try insertAlbum.bind([
                    .text(info.id.catalogString),
                    .text(info.name),
                    .text(foldedName(info.name)),
                    SQLiteValue(parent),
                    .integer(Int64(shard.photos.count)),
                    SQLiteValue(dates.min().map { Int64($0.timeIntervalSince1970.rounded()) }),
                    SQLiteValue(dates.max().map { Int64($0.timeIntervalSince1970.rounded()) }),
                    .null,   // lat/lon are filled below, once every album is present
                    .null,
                    SQLiteValue(info.coverPhotoID),
                    SQLiteValue(info.thumbsID),
                ])
                while try insertAlbum.step() {}

                for photo in shard.photos {
                    try insertPhoto.bind([.text(info.id.catalogString)] + photo.bindings)
                    while try insertPhoto.step() {}
                }
            }

            try fillLocations()
        }

        return RebuildSummary(
            albums: shards.count,
            photos: shards.reduce(0) { $0 + $1.photos.count },
            orphanedAlbums: shards.compactMap { shard in
                guard let parent = shard.info.parent, !present.contains(parent) else { return nil }
                return shard.info.id
            }
        )
    }

    /// An album's pin is the centroid of its tagged photos; a container's is the centroid of
    /// all its descendants' (§3). Computed here rather than stored in a shard, so a pin can
    /// never drift from the photos beneath it, and so placing a container costs no descendant
    /// rewrites.
    ///
    /// A plain mean of latitude and longitude: §3 says centroid, and at the scale of one
    /// album it is what people expect. It is wrong across the antimeridian — no album in
    /// this library spans it, and a wrong pin is a cosmetic fault, not a data one.
    private func fillLocations() throws {
        try database.execute(
            """
            WITH RECURSIVE descendant(root, album_id) AS (
                SELECT album_id, album_id FROM album
              UNION ALL
                SELECT d.root, a.album_id FROM album a JOIN descendant d ON a.parent = d.album_id
            )
            UPDATE album SET (lat, lon) = (
              SELECT avg(p.lat), avg(p.lon)
                FROM descendant d JOIN photo p ON p.album_id = d.album_id
               WHERE d.root = album.album_id AND p.lat IS NOT NULL AND p.lon IS NOT NULL
            )
            """
        )
    }

    /// Deletes the merged database's contents without touching the file.
    public func clear() throws {
        try database.transaction { try database.execute("DELETE FROM photo; DELETE FROM album;") }
    }
}

public struct RebuildSummary: Hashable, Sendable {
    public var albums: Int
    public var photos: Int
    /// Albums whose `parent` named a shard that was not present. Surfaced at the root.
    public var orphanedAlbums: [UUID]

    public init(albums: Int, photos: Int, orphanedAlbums: [UUID]) {
        self.albums = albums
        self.photos = photos
        self.orphanedAlbums = orphanedAlbums
    }
}

/// The merged database's read side.
///
/// A `final class` and not `Sendable`: each consumer opens its own connection, confined to
/// the task that made it. That is what lets a SwiftUI query run while the writer holds its
/// transaction (§3).
public final class CatalogReader {
    private let database: Database

    public init(path: URL) throws {
        self.database = try Database(path: path.path, options: .init(readOnly: true))
    }

    private static let albumColumns =
        "album_id, name, name_folded, parent, photo_count, date_min, date_max, lat, lon, "
        + "cover_photo_id, thumbs_id"

    // MARK: - Albums

    public func albums(under parent: UUID?) throws -> [Album] {
        let sql = "SELECT \(Self.albumColumns) FROM album WHERE parent IS ? ORDER BY date_max DESC, name"
        return try database.query(sql, [SQLiteValue(parent)]) { try Album(row: $0) }
    }

    public func allAlbums() throws -> [Album] {
        try database.query("SELECT \(Self.albumColumns) FROM album ORDER BY name") { try Album(row: $0) }
    }

    public func album(_ id: UUID) throws -> Album? {
        try database.queryOne(
            "SELECT \(Self.albumColumns) FROM album WHERE album_id = ?", [.text(id.catalogString)]
        ) { try Album(row: $0) }
    }

    /// Case- and diacritic-insensitive substring search over album names (§3).
    /// No fuzzy matching — too noisy on short names — and no filename search.
    public func searchAlbums(_ text: String) throws -> [Album] {
        let needle = foldedName(text)
        guard !needle.isEmpty else { return [] }
        return try database.query(
            "SELECT \(Self.albumColumns) FROM album WHERE name_folded LIKE ? ESCAPE '\\' ORDER BY name",
            [.text("%\(escapingLike(needle))%")]
        ) { try Album(row: $0) }
    }

    /// Albums with a pin, for the map.
    public func placedAlbums() throws -> [Album] {
        try database.query(
            "SELECT \(Self.albumColumns) FROM album WHERE lat IS NOT NULL ORDER BY name"
        ) { try Album(row: $0) }
    }

    // MARK: - Photos

    /// An album's photos in §3's order: oldest first, undated last by filename.
    public func photos(in album: UUID) throws -> [PhotoRow] {
        try database.query(
            "SELECT \(CatalogSchema.photoColumns) FROM photo WHERE album_id = ? "
            + "ORDER BY \(CatalogSchema.photoOrder)",
            [.text(album.catalogString)]
        ) { try PhotoRow(row: $0) }
    }

    /// The album's cover: the explicit choice if there is one, otherwise its earliest photo.
    ///
    /// A container owns no photos, so its cover is resolved by descending into children until
    /// one is found — unless it too has an explicit choice, which is why `coverPhotoID` is
    /// storable on containers (§3).
    public func coverPhoto(of album: UUID) throws -> PhotoRow? {
        guard let record = try self.album(album) else { return nil }

        if let chosen = record.coverPhotoID {
            let photo = try database.queryOne(
                "SELECT \(CatalogSchema.photoColumns) FROM photo WHERE id = ?",
                [.text(chosen.catalogString)],
                row: { try PhotoRow(row: $0) }
            )
            if let photo { return photo }
        }

        // One statement rather than a walk: the recursive CTE reaches every descendant, and
        // the ordering rule is applied across all of them at once.
        return try database.queryOne(
            """
            WITH RECURSIVE descendant(album_id) AS (
                SELECT ?
              UNION ALL
                SELECT a.album_id FROM album a JOIN descendant d ON a.parent = d.album_id
            )
            SELECT \(CatalogSchema.photoColumns) FROM photo
             WHERE album_id IN (SELECT album_id FROM descendant)
             ORDER BY \(CatalogSchema.photoOrder) LIMIT 1
            """,
            [.text(album.catalogString)]
        ) { try PhotoRow(row: $0) }
    }

    /// Photos with coordinates, for the map's photo layer.
    ///
    /// `album_id` is selected *after* the photo columns so `PhotoRow(row:)` still finds them
    /// at the indices it expects.
    public func placedPhotos() throws -> [(album: UUID, photo: PhotoRow)] {
        try database.query(
            "SELECT \(CatalogSchema.photoColumns), album_id FROM photo WHERE lat IS NOT NULL"
        ) { row in
            guard let album = row.uuidOrNil(13) else { throw ShardError.malformed("album_id is not a uuid") }
            return (album, try PhotoRow(row: row))
        }
    }

    public func photoCount() throws -> Int {
        try database.queryOne("SELECT count(*) FROM photo") { Int($0.int(0)) } ?? 0
    }

    /// Every album name that appears more than once under the same parent.
    ///
    /// Two devices creating one album no longer collide on a key, so duplicates are possible.
    /// They are shown and reported, never merged: merging is a destructive guess about intent
    /// (§2).
    public func duplicateNames() throws -> [(name: String, albums: [UUID])] {
        let rows = try database.query(
            """
            SELECT name, group_concat(album_id) FROM album
             GROUP BY parent, name HAVING count(*) > 1 ORDER BY name
            """
        ) { row in (row.string(0), row.string(1)) }
        return rows.map { name, ids in
            (name, ids.split(separator: ",").compactMap { UUID(uuidString: String($0)) })
        }
    }
}

/// `LIKE` treats `%` and `_` as wildcards, so a search for "50%" would match everything.
private func escapingLike(_ text: String) -> String {
    var escaped = ""
    for character in text {
        if character == "%" || character == "_" || character == "\\" { escaped.append("\\") }
        escaped.append(character)
    }
    return escaped
}

extension Album {
    /// Column order is `CatalogReader.albumColumns`.
    init(row: Statement) throws {
        guard let id = row.uuidOrNil(0) else { throw ShardError.malformed("album_id is not a uuid") }
        self.init(
            id: id,
            name: row.string(1),
            nameFolded: row.string(2),
            parent: row.uuidOrNil(3),
            photoCount: Int(row.int(4)),
            dateMin: row.intOrNil(5).map { Date(timeIntervalSince1970: TimeInterval($0)) },
            dateMax: row.intOrNil(6).map { Date(timeIntervalSince1970: TimeInterval($0)) },
            latitude: row.doubleOrNil(7),
            longitude: row.doubleOrNil(8),
            coverPhotoID: row.uuidOrNil(9),
            thumbsID: row.uuidOrNil(10)
        )
    }
}
