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
    /// The name of the object *in the zone* — NFC, with the extension its bytes actually
    /// have — so a pull restores a file that is what it claims to be. For a still this is
    /// the on-disk name; for a carved CR2 it is `.jpg`, and for a transcode `.mp4` (§3).
    public var filename: String
    /// The name the file had at ingest, when it differs from `filename`: `IMG_1234.CR2`
    /// beside `IMG_1234.jpg`, `VID_0001.MOV` beside `VID_0001.mp4`. `nil` for the great
    /// majority of rows, where the blob simply *is* the file on disk.
    ///
    /// A hint for reconciliation, like `AlbumInfo.sourcePath` — never an identity. It is
    /// also what says whether `bytes` can be checked against the directory entry: only a
    /// row whose original is the disk file byte-for-byte can be (§7).
    public var sourceFilename: String?
    /// EXIF `DateTimeOriginal`. `nil` for the 0.5% of photos without one; those sort last
    /// and are absent from date filters.
    public var takenAt: Date?
    public var latitude: Double?
    public var longitude: Double?
    /// Display dimensions: already rotated, so no consumer applies orientation (§3).
    public var width: Int?
    public var height: Int?
    /// The size of the blob a tap fetches — the original for a still or Live Photo, the
    /// carved JPEG for a CR2, the transcode for a video (§3). Not the source file's size.
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
        sourceFilename: String? = nil,
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
        // A source name equal to the zone name says nothing, so it is not stored: the
        // column means "this row's blob is not the file that was ingested".
        let source = sourceFilename?.precomposedStringWithCanonicalMapping
        self.sourceFilename = source == self.filename ? nil : source
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

    /// The name this row's file has on disk: its source name when the blob is a
    /// derivative, otherwise the zone name. What reconciliation stats for (§7).
    public var diskFilename: String { sourceFilename ?? filename }

    /// Whether `bytes` can be checked against the directory entry. True only when the
    /// original was uploaded byte-for-byte, which excludes video (no original at all) and
    /// carved RAW (the blob is the extracted JPEG).
    public var byteCountIsCheckable: Bool { originalID != nil && sourceFilename == nil }

    /// Every blob this row owns. What deleting the album removes, and what the orphan
    /// sweep counts as referenced.
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
