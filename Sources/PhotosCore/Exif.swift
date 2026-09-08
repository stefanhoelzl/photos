import Foundation

/// One EXIF value, as an imaging library hands it over.
///
/// Deliberately not `Any`: the mapper has to make the same decision from the same input on
/// both platforms, and an untyped bag makes that a matter of what each library happened to
/// box its numbers as.
public enum ExifValue: Hashable, Sendable {
    case string(String)
    case int(Int)
    case double(Double)
    /// An EXIF rational, kept unreduced — GPS coordinates arrive as three of these and lose
    /// precision if divided early.
    case rational(numerator: Double, denominator: Double)
    case array([ExifValue])

    public var doubleValue: Double? {
        switch self {
        case .int(let v): Double(v)
        case .double(let v): v
        case .rational(let n, let d): d == 0 ? nil : n / d
        case .string(let s): Double(s)
        case .array: nil
        }
    }

    public var intValue: Int? {
        switch self {
        case .int(let v): v
        case .double(let v): Int(v)
        case .rational: doubleValue.map { Int($0) }
        case .string(let s): Int(s)
        case .array: nil
        }
    }

    public var stringValue: String? {
        if case .string(let s) = self { return s }
        return nil
    }

    public var arrayValue: [ExifValue]? {
        if case .array(let a) = self { return a }
        return nil
    }
}

/// The tags an `ImageBackend` extracted from one file.
///
/// Tag names are the EXIF/TIFF names, unprefixed: `DateTimeOriginal`, `GPSLatitude`,
/// `GPSLatitudeRef`, `Orientation`, `PixelXDimension`, and so on. Both backends normalise
/// to these before returning, so the mapper sees one vocabulary.
public struct ExifTags: Hashable, Sendable {
    public var values: [String: ExifValue]

    public init(_ values: [String: ExifValue] = [:]) { self.values = values }

    public subscript(tag: String) -> ExifValue? { values[tag] }
}

/// What each platform's imaging library provides (§7).
///
/// Declared here, with the schema and the mapper, because it is a contract rather than an
/// implementation: `PhotosPipeline` imports this module and conforms to it —
/// ImageIO/AVFoundation on Apple platforms, libheif/ffmpeg on Linux.
///
/// Extraction lives behind the protocol because both platforms already ship a library that
/// reads EXIF and a hand-written container parser would only add a third opinion.
/// *Interpretation* does not: that is `ExifMapper`, shared, so a phone and a laptop cannot
/// derive different dates from the same file.
public protocol ImageBackend: Sendable {
    /// Raw tags for one file, normalised to EXIF/TIFF tag names.
    func rawTags(at url: URL) throws -> ExifTags
}

/// Turns raw tags into the fields of a catalog row.
///
/// Every judgement about what a tag *means* is here: date parsing, the sign convention on
/// GPS references, rational-to-degrees conversion, and the orientation-driven dimension
/// swap. This is the code §7 means when it says a laptop/phone disagreement would corrupt
/// the catalog.
public enum ExifMapper {

    /// EXIF dates are `YYYY:MM:DD HH:MM:SS` in **local time with no zone**, and the standard
    /// offers no way to know which zone that was. Parsing as UTC is the only choice that is
    /// stable: it gives the same instant on every device, in every locale, forever. The
    /// alternative — the reader's current zone — would move every photo in the library when
    /// you cross a border.
    public static func date(from tags: ExifTags) -> Date? {
        for tag in ["DateTimeOriginal", "DateTimeDigitized", "DateTime"] {
            if let text = tags[tag]?.stringValue, let date = parseExifDate(text) { return date }
        }
        return nil
    }

    static func parseExifDate(_ text: String) -> Date? {
        // Hand-parsed rather than DateFormatter: the format is fixed, and a formatter would
        // drag in locale and calendar behaviour that has to be pinned anyway.
        let digits = text.prefix(19)
        guard digits.count == 19 else { return nil }
        let parts = digits.split(whereSeparator: { ":- ".contains($0) })
        guard parts.count == 6 else { return nil }
        let numbers = parts.compactMap { Int($0) }
        guard numbers.count == 6 else { return nil }

        var components = DateComponents()
        components.year = numbers[0]
        components.month = numbers[1]
        components.day = numbers[2]
        components.hour = numbers[3]
        components.minute = numbers[4]
        components.second = numbers[5]

        // A camera with a dead clock writes all zeroes; that is not a date, it is a default.
        guard numbers[0] > 0, numbers[1] > 0, numbers[2] > 0 else { return nil }

        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        return calendar.date(from: components)
    }

    /// Latitude and longitude in signed degrees, or `nil` if either is absent or unusable.
    ///
    /// Both halves must be present: half a coordinate places a photo on the null island,
    /// which would then drag its album's centroid there too.
    public static func coordinate(from tags: ExifTags) -> (latitude: Double, longitude: Double)? {
        // GPSStatus 'V' means the fix was void — the camera wrote a GPS block while having no
        // position. 1,028 photos in this library carry one, all with latitude identical to
        // longitude and both wildly out of range. They are rejected by the range check below
        // anyway, but only by luck: a void block holding plausible-looking numbers would sail
        // through and put a photo somewhere it has never been.
        if let status = tags["GPSStatus"]?.stringValue?
            .trimmingCharacters(in: .whitespaces).uppercased().first, status == "V" {
            return nil
        }

        guard
            let latitude = degrees(tags["GPSLatitude"], ref: tags["GPSLatitudeRef"]?.stringValue, negative: "S"),
            let longitude = degrees(tags["GPSLongitude"], ref: tags["GPSLongitudeRef"]?.stringValue, negative: "W"),
            latitude >= -90, latitude <= 90, longitude >= -180, longitude <= 180
        else { return nil }

        // Exactly (0, 0) is in the Gulf of Guinea and is overwhelmingly a zeroed GPS field
        // rather than a photograph taken there.
        if latitude == 0, longitude == 0 { return nil }
        return (latitude, longitude)
    }

    /// EXIF gives degrees/minutes/seconds as three rationals plus a hemisphere letter; some
    /// libraries hand back a single already-signed number instead. Both are accepted.
    static func degrees(_ value: ExifValue?, ref: String?, negative: Character) -> Double? {
        guard let value else { return nil }

        let magnitude: Double
        if let components = value.arrayValue {
            guard let first = components.first?.doubleValue else { return nil }
            let minutes = components.count > 1 ? (components[1].doubleValue ?? 0) : 0
            let seconds = components.count > 2 ? (components[2].doubleValue ?? 0) : 0
            magnitude = first + minutes / 60 + seconds / 3600
        } else if let scalar = value.doubleValue {
            magnitude = scalar
        } else {
            return nil
        }

        guard magnitude.isFinite else { return nil }

        // A ref of "S"/"W" makes it negative. When the library already signed the number and
        // gave no ref, that sign is respected as-is.
        if let ref = ref?.trimmingCharacters(in: .whitespaces).uppercased().first {
            return ref == negative ? -abs(magnitude) : abs(magnitude)
        }
        return magnitude
    }

    /// Display dimensions — already rotated, per §3, so nothing downstream applies
    /// orientation. Orientations 5–8 transpose the image, so width and height swap.
    public static func dimensions(from tags: ExifTags) -> (width: Int, height: Int)? {
        let widthTags = ["PixelXDimension", "ImageWidth", "PixelWidth"]
        let heightTags = ["PixelYDimension", "ImageLength", "ImageHeight", "PixelHeight"]

        guard
            let width = widthTags.lazy.compactMap({ tags[$0]?.intValue }).first,
            let height = heightTags.lazy.compactMap({ tags[$0]?.intValue }).first,
            width > 0, height > 0
        else { return nil }

        let orientation = tags["Orientation"]?.intValue ?? 1
        return (5...8).contains(orientation) ? (height, width) : (width, height)
    }

    /// Builds the EXIF-derived fields of a photo row.
    ///
    /// What the caller supplies rather than EXIF: the filename, the byte size, the media
    /// type, and the object ids, all of which come from the filesystem or from C's upload
    /// rather than from any tag.
    public static func photoRow(
        id: UUID = UUID(),
        filename: String,
        sourceFilename: String? = nil,
        bytes: Int64?,
        mediaType: MediaType = .photo,
        tags: ExifTags,
        originalID: UUID? = nil,
        liveVideoID: UUID? = nil,
        previewID: UUID? = nil,
        videoID: UUID? = nil
    ) -> PhotoRow {
        let coordinate = coordinate(from: tags)
        let size = dimensions(from: tags)
        return PhotoRow(
            id: id,
            filename: filename,
            sourceFilename: sourceFilename,
            takenAt: date(from: tags),
            latitude: coordinate?.latitude,
            longitude: coordinate?.longitude,
            width: size?.width,
            height: size?.height,
            bytes: bytes,
            mediaType: mediaType,
            originalID: originalID,
            liveVideoID: liveVideoID,
            previewID: previewID,
            videoID: videoID
        )
    }
}
