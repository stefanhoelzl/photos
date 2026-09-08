import Foundation

/// What kind of thing a photo row describes (§3).
public enum MediaType: Int, Hashable, Sendable, CaseIterable {
    case photo = 0
    case video = 1
    case livePhoto = 2
}

/// One photo, video or Live Photo.
///
/// `id` is row identity and nothing else: it is what `coverPhotoID` points at and what the
/// thumbnail pack keys by, so it survives a derivative being re-encoded — which mints a new
/// `previewID` but leaves the photo the same photo (§3).
///
/// Lives in `PhotosCore` rather than `PhotosCatalog` because the pipeline produces these and
/// the catalog stores them; a type both sides own belongs below both. The SQLite bindings and
/// row decoding stay in `PhotosCatalog`, as extensions — the shape is shared, the storage is not.
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

    /// The original as uploaded. Absent for video (originals stay on the laptop). For a
    /// developed RAW this points at the JPEG extracted from the CR2, not the RAW (§5).
    public var originalID: UUID?
    /// The paired MOV, when `mediaType == .livePhoto`.
    public var liveVideoID: UUID?
    /// 2048px HEIC. Also the poster still for a video.
    public var previewID: UUID?
    /// 1080p HEVC transcode, for video.
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
        // Whole seconds. EXIF has one-second resolution anyway, so nothing is lost that was
        // ever really there.
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

extension Date {
    /// Truncated to whole seconds — the resolution the catalog stores.
    ///
    /// `package` rather than `internal`: `AlbumInfo` in `PhotosCatalog` needs the same
    /// truncation, and the two must agree or a round trip stops being the identity.
    package var wholeSeconds: Date { Date(timeIntervalSince1970: timeIntervalSince1970.rounded()) }
}
