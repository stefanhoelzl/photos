import Foundation
import Testing
import PhotosCore
@testable import PhotosPipeline

/// Reading EXIF back out of the containers the library actually holds.
///
/// These exist because of a bug the rest of the suite could not have caught: `pi_encode_heic`
/// writes pixels and a colour profile but no metadata, so every synthetic HEIC was
/// metadata-free — and a HEIF reader that returned *zero tags for every file* passed
/// everything. In the real library that silently cost 1,531 photos their date and GPS, and
/// all 187 Live Photos their pairing.
struct ExifReadingTests {

    static func withTemporaryDirectory<R>(_ body: (URL) throws -> R) rethrows -> R {
        let url = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("photos-exif-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: url) }
        return try body(url)
    }

    @Test("tags are read back out of a HEIC")
    func heicTagsAreRead() throws {
        try Self.withTemporaryDirectory { directory in
            let url = directory.appendingPathComponent("live.heic")
            try Synthetic.heic(at: url, width: 64, height: 48, model: "iPhone XS")

            let tags = try NativeImageBackend().rawTags(at: url)
            #expect(!tags.values.isEmpty, "a HEIC with an EXIF block must yield tags")
            #expect(tags["Model"]?.stringValue == "iPhone XS")
        }
    }

    @Test("tags are read back out of a JPEG")
    func jpegTagsAreRead() throws {
        try Self.withTemporaryDirectory { directory in
            let url = directory.appendingPathComponent("photo.jpg")
            try Synthetic.jpeg(width: 64, height: 48, orientation: 6).write(to: url)
            let tags = try NativeImageBackend().rawTags(at: url)
            #expect(tags["Orientation"]?.intValue == 6)
        }
    }

    @Test("a HEIC with no EXIF yields no tags rather than failing")
    func missingExifIsNotAnError() throws {
        try Self.withTemporaryDirectory { directory in
            let url = directory.appendingPathComponent("bare.heic")
            try Synthetic.heic(width: 32, height: 32).write(to: url)
            let tags = try NativeImageBackend().rawTags(at: url)
            #expect(tags.values.isEmpty)
        }
    }
}
