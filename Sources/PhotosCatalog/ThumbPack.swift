import Foundation

/// An album's thumbnails, packed into one blob (§3).
///
/// The pack exists so a grid opens in **one request, offline**. One blob per thumbnail
/// would be uniform with everything else and would make adding a photo a 9 KB write instead
/// of a repack — but it would also cost 1,755 round trips to open the largest album here,
/// against one ~15 MB GET. bunny.net charges no per-request fee, so this is latency rather
/// than money; the promise it protects is §6's.
///
/// It is referenced from the shard by `AlbumInfo.thumbsID` rather than living under its own
/// prefix, so a changed pack is visible through the single LIST on `meta/` that §4 already
/// does: the reference *is* the change signal.
public enum ThumbPack {

    /// Packs thumbnails keyed by `PhotoRow.id`.
    public static func pack(_ thumbnails: [UUID: Data]) throws -> Data {
        let database = try Database(path: nil, options: .inMemory)
        try database.execute(CatalogSchema.thumbDDL)
        try database.transaction {
            let insert = try database.prepare("INSERT INTO thumb (id, jpeg) VALUES (?, ?)")
            // Sorted so a pack of the same thumbnails is the same bytes twice running.
            // Nothing depends on that — blobs are immutable and get a fresh uuid anyway —
            // but it makes two packs comparable when a test or a person needs to know
            // whether anything actually changed.
            for id in thumbnails.keys.sorted(by: { $0.catalogString < $1.catalogString }) {
                try insert.bind([.text(id.catalogString), .blob(thumbnails[id]!)])
                while try insert.step() {}
            }
        }
        return try database.serialized()
    }

    /// Every thumbnail in a pack. For a whole grid, which is the usual case.
    public static func unpack(_ data: Data) throws -> [UUID: Data] {
        let database = try Database.deserialized(data)
        let rows = try database.query("SELECT id, jpeg FROM thumb") { row in
            (row.uuidOrNil(0), row.data(1))
        }
        return Dictionary(uniqueKeysWithValues: rows.compactMap { id, jpeg in
            id.map { ($0, jpeg) }
        })
    }

    /// One thumbnail, without decoding the rest — for a cover tile in the album list.
    public static func thumbnail(_ id: UUID, in data: Data) throws -> Data? {
        let database = try Database.deserialized(data)
        return try database.queryOne(
            "SELECT jpeg FROM thumb WHERE id = ?", [.text(id.catalogString)]
        ) { $0.data(0) }
    }

    /// The ids a pack holds, without their bytes.
    public static func ids(in data: Data) throws -> Set<UUID> {
        let database = try Database.deserialized(data)
        let ids = try database.query("SELECT id FROM thumb") { $0.uuidOrNil(0) }
        return Set(ids.compactMap { $0 })
    }
}
