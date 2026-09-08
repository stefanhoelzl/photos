import Foundation
import Testing
@testable import PhotosCore

/// §7 puts the *interpretation* of EXIF in the shared package precisely because a laptop and
/// a phone disagreeing here would corrupt the catalog. These are the disagreements that were
/// available to be had.
struct ExifMapperTests {

    // MARK: - Dates

    @Test("the canonical EXIF timestamp")
    func parsesDateTimeOriginal() throws {
        let tags = ExifTags(["DateTimeOriginal": .string("2013:07:04 18:12:11")])
        let date = try #require(ExifMapper.date(from: tags))

        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        let parts = calendar.dateComponents([.year, .month, .day, .hour, .minute, .second], from: date)
        #expect(parts.year == 2013)
        #expect(parts.month == 7)
        #expect(parts.day == 4)
        #expect(parts.hour == 18)
        #expect(parts.minute == 12)
        #expect(parts.second == 11)
    }

    /// EXIF timestamps carry no zone. Parsing as UTC is what makes the value stable: the
    /// same file must yield the same instant on a phone in Munich and a laptop in Auckland.
    @Test("the same tag yields the same instant regardless of the machine's zone")
    func dateIsZoneIndependent() throws {
        let tags = ExifTags(["DateTimeOriginal": .string("2013:07:04 18:12:11")])
        let date = try #require(ExifMapper.date(from: tags))
        #expect(date.timeIntervalSince1970 == 1_372_961_531)
    }

    @Test("falls back through the other date tags in order")
    func dateFallback() throws {
        let digitized = ExifTags(["DateTimeDigitized": .string("2001:01:02 03:04:05")])
        #expect(ExifMapper.date(from: digitized) != nil)

        let both = ExifTags([
            "DateTimeOriginal": .string("2013:07:04 18:12:11"),
            "DateTimeDigitized": .string("2001:01:02 03:04:05"),
        ])
        #expect(ExifMapper.date(from: both)?.timeIntervalSince1970 == 1_372_961_531)
    }

    /// A camera with a dead clock writes zeroes. That is a default, not a date — and §3 says
    /// undated photos are absent from date filters rather than dated to year zero.
    @Test("garbage and absent dates come back nil", arguments: [
        "0000:00:00 00:00:00",
        "not a date at all",
        "2013:07:04",
        "",
    ])
    func rejectsBadDates(text: String) {
        #expect(ExifMapper.date(from: ExifTags(["DateTimeOriginal": .string(text)])) == nil)
    }

    @Test("no date tag at all is nil, not an error")
    func missingDate() {
        #expect(ExifMapper.date(from: ExifTags()) == nil)
    }

    // MARK: - Coordinates

    @Test("degrees, minutes and seconds become signed degrees")
    func parsesDMS() throws {
        let tags = ExifTags([
            "GPSLatitude": .array([.rational(numerator: 47, denominator: 1),
                                   .rational(numerator: 59, denominator: 1),
                                   .rational(numerator: 4406, denominator: 100)]),
            "GPSLatitudeRef": .string("N"),
            "GPSLongitude": .array([.rational(numerator: 12, denominator: 1),
                                    .rational(numerator: 16, denominator: 1),
                                    .rational(numerator: 668, denominator: 100)]),
            "GPSLongitudeRef": .string("E"),
        ])
        let coordinate = try #require(ExifMapper.coordinate(from: tags))
        #expect(abs(coordinate.latitude - 47.995572) < 0.0001)   // 47° 59' 44.06"
        #expect(abs(coordinate.longitude - 12.268522) < 0.0001)  // 12° 16' 6.68"
    }

    /// The sign convention is the classic way to put a European album in the Southern
    /// Hemisphere, so it gets its own test in both directions.
    @Test("S and W make the value negative")
    func hemisphereRefs() throws {
        let south = ExifTags([
            "GPSLatitude": .array([.rational(numerator: 36, denominator: 1),
                                   .rational(numerator: 51, denominator: 1),
                                   .rational(numerator: 0, denominator: 1)]),
            "GPSLatitudeRef": .string("S"),
            "GPSLongitude": .array([.rational(numerator: 174, denominator: 1),
                                    .rational(numerator: 46, denominator: 1),
                                    .rational(numerator: 0, denominator: 1)]),
            "GPSLongitudeRef": .string("E"),
        ])
        let coordinate = try #require(ExifMapper.coordinate(from: south))
        #expect(coordinate.latitude < 0)
        #expect(coordinate.longitude > 0)
        #expect(abs(coordinate.latitude - -36.85) < 0.001)
    }

    @Test("a ref given in lowercase still counts")
    func lowercaseRef() throws {
        let tags = ExifTags([
            "GPSLatitude": .double(10), "GPSLatitudeRef": .string("s"),
            "GPSLongitude": .double(20), "GPSLongitudeRef": .string("w"),
        ])
        let coordinate = try #require(ExifMapper.coordinate(from: tags))
        #expect(coordinate.latitude == -10)
        #expect(coordinate.longitude == -20)
    }

    @Test("an already-signed scalar with no ref is taken as given")
    func signedScalar() throws {
        let tags = ExifTags(["GPSLatitude": .double(-36.85), "GPSLongitude": .double(174.76)])
        let coordinate = try #require(ExifMapper.coordinate(from: tags))
        #expect(coordinate.latitude == -36.85)
        #expect(coordinate.longitude == 174.76)
    }

    /// Half a coordinate would place the photo on the null island — and then drag its
    /// album's centroid there too, since §3 computes the pin from photo rows.
    @Test("half a coordinate is no coordinate")
    func rejectsHalfCoordinate() {
        let latOnly = ExifTags(["GPSLatitude": .double(47.99), "GPSLatitudeRef": .string("N")])
        #expect(ExifMapper.coordinate(from: latOnly) == nil)
    }

    @Test("zeroed GPS fields are not a location in the Gulf of Guinea")
    func rejectsNullIsland() {
        let tags = ExifTags([
            "GPSLatitude": .double(0), "GPSLatitudeRef": .string("N"),
            "GPSLongitude": .double(0), "GPSLongitudeRef": .string("E"),
        ])
        #expect(ExifMapper.coordinate(from: tags) == nil)
    }

    @Test("out-of-range values are refused")
    func rejectsOutOfRange() {
        let tags = ExifTags([
            "GPSLatitude": .double(120), "GPSLatitudeRef": .string("N"),
            "GPSLongitude": .double(20), "GPSLongitudeRef": .string("E"),
        ])
        #expect(ExifMapper.coordinate(from: tags) == nil)
    }

    @Test("a zero denominator does not produce a NaN pin")
    func rejectsZeroDenominator() {
        let tags = ExifTags([
            "GPSLatitude": .array([.rational(numerator: 47, denominator: 0)]),
            "GPSLatitudeRef": .string("N"),
            "GPSLongitude": .double(12),
            "GPSLongitudeRef": .string("E"),
        ])
        #expect(ExifMapper.coordinate(from: tags) == nil)
    }

    // MARK: - Dimensions

    /// §3 stores display dimensions so nothing downstream applies orientation. Getting this
    /// backwards lays out every rotated photo in the wrong aspect — visible only on the
    /// minority that are rotated, which is what makes it worth pinning.
    @Test("orientations 5–8 swap width and height", arguments: [5, 6, 7, 8])
    func transposedOrientations(orientation: Int) throws {
        let tags = ExifTags([
            "PixelXDimension": .int(4000),
            "PixelYDimension": .int(3000),
            "Orientation": .int(orientation),
        ])
        let size = try #require(ExifMapper.dimensions(from: tags))
        #expect(size.width == 3000)
        #expect(size.height == 4000)
    }

    @Test("orientations 1–4 leave them alone", arguments: [1, 2, 3, 4])
    func uprightOrientations(orientation: Int) throws {
        let tags = ExifTags([
            "PixelXDimension": .int(4000),
            "PixelYDimension": .int(3000),
            "Orientation": .int(orientation),
        ])
        let size = try #require(ExifMapper.dimensions(from: tags))
        #expect(size.width == 4000)
        #expect(size.height == 3000)
    }

    @Test("a missing Orientation tag means upright")
    func defaultsToUpright() throws {
        let tags = ExifTags(["PixelXDimension": .int(4000), "PixelYDimension": .int(3000)])
        let size = try #require(ExifMapper.dimensions(from: tags))
        #expect(size.width == 4000)
    }

    @Test("the TIFF spellings are accepted too")
    func alternateDimensionTags() throws {
        let tags = ExifTags(["ImageWidth": .int(1920), "ImageLength": .int(1080)])
        let size = try #require(ExifMapper.dimensions(from: tags))
        #expect(size.width == 1920)
        #expect(size.height == 1080)
    }

    @Test("zero or missing dimensions are nil, not zero")
    func rejectsBadDimensions() {
        #expect(ExifMapper.dimensions(from: ExifTags()) == nil)
        #expect(ExifMapper.dimensions(from: ExifTags(["PixelXDimension": .int(0),
                                                      "PixelYDimension": .int(100)])) == nil)
    }

    // MARK: - Whole rows

    @Test("a full tag set becomes a full row")
    func buildsPhotoRow() throws {
        let tags = ExifTags([
            "DateTimeOriginal": .string("2013:07:04 18:12:11"),
            "GPSLatitude": .double(47.99), "GPSLatitudeRef": .string("N"),
            "GPSLongitude": .double(12.26), "GPSLongitudeRef": .string("E"),
            "PixelXDimension": .int(4000), "PixelYDimension": .int(3000),
            "Orientation": .int(6),
        ])
        let previewID = UUID()
        let row = ExifMapper.photoRow(
            filename: "IMG_0042.jpg", bytes: 3_145_728, tags: tags, previewID: previewID
        )

        #expect(row.filename == "IMG_0042.jpg")
        #expect(row.takenAt?.timeIntervalSince1970 == 1_372_961_531)
        #expect(row.latitude == 47.99)
        #expect(row.width == 3000)          // transposed by orientation 6
        #expect(row.height == 4000)
        #expect(row.bytes == 3_145_728)
        #expect(row.previewID == previewID)
    }

    /// 0.5% of this library has no EXIF date and 75.5% has no GPS, so the empty case is the
    /// common one, not an edge.
    @Test("no tags at all still produces a usable row")
    func buildsRowFromNothing() {
        let row = ExifMapper.photoRow(filename: "scan.png", bytes: 4096, tags: ExifTags())

        #expect(row.filename == "scan.png")
        #expect(row.takenAt == nil)
        #expect(row.latitude == nil)
        #expect(row.width == nil)
        #expect(row.bytes == 4096)
        #expect(row.mediaType == .photo)
    }
}

/// GPS blocks that declare themselves invalid.
struct GPSStatusTests {

    static func coordinate(lat: [Double], lon: [Double], status: String?) -> ExifTags {
        var values: [String: ExifValue] = [
            "GPSLatitude": .array(lat.map { .rational(numerator: $0, denominator: 1) }),
            "GPSLatitudeRef": .string("N"),
            "GPSLongitude": .array(lon.map { .rational(numerator: $0, denominator: 1) }),
            "GPSLongitudeRef": .string("E"),
        ]
        if let status { values["GPSStatus"] = .string(status) }
        return ExifTags(values)
    }

    @Test("a void fix is rejected even when the numbers look plausible")
    func voidStatusRejected() {
        // The case the range check cannot catch: 1,028 photos in the real library carry a
        // void block, and they are only rejected today because their garbage happens to be
        // out of range. Plausible numbers in a void block would be accepted.
        let tags = Self.coordinate(lat: [47, 35, 0], lon: [11, 30, 0], status: "V")
        #expect(ExifMapper.coordinate(from: tags) == nil)
    }

    @Test("an active fix is accepted")
    func activeStatusAccepted() {
        let tags = Self.coordinate(lat: [47, 35, 0], lon: [11, 30, 0], status: "A")
        let c = ExifMapper.coordinate(from: tags)
        #expect(c != nil)
        #expect(abs((c?.latitude ?? 0) - 47.5833) < 0.001)
    }

    @Test("no GPSStatus at all is accepted — most cameras omit it")
    func absentStatusAccepted() {
        let tags = Self.coordinate(lat: [47, 35, 0], lon: [11, 30, 0], status: nil)
        #expect(ExifMapper.coordinate(from: tags) != nil)
    }

    @Test("the library's actual void signature is rejected")
    func realWorldVoidBlock() {
        let tags = Self.coordinate(lat: [17056881, 40, 0], lon: [17056881, 40, 0], status: "V")
        #expect(ExifMapper.coordinate(from: tags) == nil)
    }
}
