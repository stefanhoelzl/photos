import Foundation

/// What kind of thing a photo row describes (§3).
public enum MediaType: Int, Hashable, Sendable, CaseIterable {
    case photo = 0
    case video = 1
    case livePhoto = 2
}

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

/// One photo, video or Live Photo.
///
/// `id` is row identity and nothing else: it is what `coverPhotoID` points at and what the
/// thumbnail pack keys by, so it survives a derivative being re-encoded — which mints a new
/// `previewID` but leaves the photo the same photo (§3).
public struct PhotoRow: Hashable, Sendable {
    public var id: UUID
    /// The on-disk name in full — NFC, with its extension — so a pull restores it exactly.
    public var filename: String
    /// EXIF `DateTimeOriginal`. `nil` for the 0.5% of photos without one; those sort last
    /// and are absent from date filters.
    public var takenAt: Date?
    public var latitude: Double?
    public var longitude: Double?
    /// Display dimensions: already rotated, so no consumer applies orientation (§3).
    public var width: Int?
    public var height: Int?
    public var bytes: Int64?
    public var mediaType: MediaType

    /// The original as uploaded. Absent for video (originals stay on the laptop) and for a
    /// developed RAW, where the JPEG derivative is what gets uploaded (§5).
    public var originalID: UUID?
    /// The paired MOV, when `mediaType == .livePhoto`.
    public var liveVideoID: UUID?
    /// 2048px HEIC. Also the poster still for a video.
    public var previewID: UUID?
    /// 1080p H.264 transcode, for video.
    public var videoID: UUID?

    public init(
        id: UUID = UUID(),
        filename: String,
        takenAt: Date? = nil,
        latitude: Double? = nil,
        longitude: Double? = nil,
        width: Int? = nil,
        height: Int? = nil,
        bytes: Int64? = nil,
        mediaType: MediaType = .photo,
        originalID: UUID? = nil,
        liveVideoID: UUID? = nil,
        previewID: UUID? = nil,
        videoID: UUID? = nil
    ) {
        self.id = id
        self.filename = filename.precomposedStringWithCanonicalMapping
        // Whole seconds, as above. EXIF has one-second resolution anyway, so nothing is
        // lost that was ever really there.
        self.takenAt = takenAt?.wholeSeconds
        self.latitude = latitude
        self.longitude = longitude
        self.width = width
        self.height = height
        self.bytes = bytes
        self.mediaType = mediaType
        self.originalID = originalID
        self.liveVideoID = liveVideoID
        self.previewID = previewID
        self.videoID = videoID
    }

    /// Every blob this row owns. What `--prune` deletes when the album goes, and what the
    /// orphan sweep counts as referenced.
    public var objectIDs: [UUID] {
        [originalID, liveVideoID, previewID, videoID].compactMap { $0 }
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


extension Date {
    /// Truncated to whole seconds — the resolution the catalog stores.
    var wholeSeconds: Date { Date(timeIntervalSince1970: timeIntervalSince1970.rounded()) }
}
