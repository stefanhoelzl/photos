import Foundation
import PhotosCore

/// An album's own record — the single row of a shard's `album_info` table.
///
/// `id` is the album's identity and equals its key (`meta/<id>.db`). Renaming or
/// re-parenting an album changes `name` or `parent`; it never changes `id`, and it moves
/// no objects (§2).
public struct AlbumInfo: Hashable, Sendable {
    public var id: UUID
    public var name: String
    /// The parent album's id, or `nil` at the root. A parent that resolves to no shard is
    /// not an error — see `SyncReport.orphanedAlbums`.
    public var parent: UUID?
    /// The folder this album came from, relative to `$LIBRARY_ROOT`. A hint the CLI uses to
    /// reconnect a local directory to its shard, never an identity.
    public var sourcePath: String?
    /// Overrides the default cover, which is otherwise the album's earliest photo.
    public var coverPhotoID: UUID?
    /// The blob holding this album's packed thumbnails, or `nil` before one exists.
    public var thumbsID: UUID?
    public var addedAt: Date
    public var schemaVersion: Int

    public init(
        id: UUID = UUID(),
        name: String,
        parent: UUID? = nil,
        sourcePath: String? = nil,
        coverPhotoID: UUID? = nil,
        thumbsID: UUID? = nil,
        addedAt: Date = Date(),
        schemaVersion: Int = CatalogSchema.version
    ) {
        // Normalised here rather than at each call site: iOS emits NFD, Linux stores
        // whatever bytes it was handed, and a name that differs only by composition would
        // sort, fold and compare as a different album (§2).
        self.id = id
        self.name = name.precomposedStringWithCanonicalMapping
        self.parent = parent
        self.sourcePath = sourcePath?.precomposedStringWithCanonicalMapping
        self.coverPhotoID = coverPhotoID
        self.thumbsID = thumbsID
        // Truncated to whole seconds, which is the resolution the shard stores. Without
        // this an in-memory value and the same value read back differ by a fraction, so
        // a round trip is not the identity — and "did this album change?" gets an answer
        // that depends on whether the value has been through SQLite yet.
        self.addedAt = addedAt.wholeSeconds
        self.schemaVersion = schemaVersion
    }
}

/// One album as the merged database sees it: the shard's own fields plus everything that
/// can only be known once every shard is present.
public struct Album: Hashable, Sendable {
    public var id: UUID
    public var name: String
    /// Case- and diacritic-folded, for search. Merged-DB only — never uploaded.
    public var nameFolded: String
    /// `nil` at the root, and also when the shard named a parent that does not exist.
    public var parent: UUID?
    public var photoCount: Int
    public var dateMin: Date?
    public var dateMax: Date?
    /// Centroid of this album's tagged photos, or of all its descendants' for a container.
    /// Computed at rebuild; `nil` means "not on the map".
    public var latitude: Double?
    public var longitude: Double?
    public var coverPhotoID: UUID?
    public var thumbsID: UUID?

    public init(
        id: UUID, name: String, nameFolded: String, parent: UUID?,
        photoCount: Int, dateMin: Date?, dateMax: Date?,
        latitude: Double?, longitude: Double?, coverPhotoID: UUID?, thumbsID: UUID?
    ) {
        self.id = id
        self.name = name
        self.nameFolded = nameFolded
        self.parent = parent
        self.photoCount = photoCount
        self.dateMin = dateMin
        self.dateMax = dateMax
        self.latitude = latitude
        self.longitude = longitude
        self.coverPhotoID = coverPhotoID
        self.thumbsID = thumbsID
    }
}

/// The contents of one shard.
public struct Shard: Hashable, Sendable {
    public var info: AlbumInfo
    public var photos: [PhotoRow]

    public init(info: AlbumInfo, photos: [PhotoRow]) {
        self.info = info
        self.photos = photos
    }

    /// Every blob the album owns, thumbnail pack included.
    public var objectIDs: [UUID] {
        photos.flatMap(\.objectIDs) + (info.thumbsID.map { [$0] } ?? [])
    }
}

/// The two prefixes the zone has (§2). Keys are built here and nowhere else.
public enum StorageKey {
    public static let metaPrefix = "meta/"
    public static let blobPrefix = "blob/"

    public static func shard(_ albumID: UUID) -> String { "\(metaPrefix)\(albumID.catalogString).db" }
    public static func blob(_ objectID: UUID) -> String { "\(blobPrefix)\(objectID.catalogString)" }

    /// The album id in `meta/<uuid>.db`, or `nil` for anything else — a directory marker,
    /// a stray key, a name that is not a uuid.
    ///
    /// The design leans on LIST being the whole sync mechanism, so this is deliberately
    /// strict: a key it cannot parse is skipped, never guessed at.
    public static func albumID(fromShardKey key: String) -> UUID? {
        guard key.hasPrefix(metaPrefix), key.hasSuffix(".db") else { return nil }
        let start = key.index(key.startIndex, offsetBy: metaPrefix.count)
        let end = key.index(key.endIndex, offsetBy: -3)
        guard start < end else { return nil }
        return UUID(uuidString: String(key[start..<end]))
    }
}
