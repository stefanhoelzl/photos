import Foundation
import CImaging
import PhotosCore

/// Generated test inputs.
///
/// Decision 16: nothing binary is committed. The real library is personal data, and a
/// committed corpus is a set of files that quietly stop representing anything — the same
/// reasoning that made milestone B forge `Fixture.futureShard` rather than ship a `.db`.
///
/// Public rather than test-only because `native-smoke` needs it too, and native-smoke is the
/// only thing that can exercise the musl build at all.
public enum Synthetic {

    /// A deterministic image with real structure in it.
    ///
    /// Not flat colour: a flat image compresses to almost nothing, so a size assertion on it
    /// would pass no matter how badly the encoder was configured. The gradient plus a
    /// checker gives the DCT something to do, so byte counts mean something.
    public static func image(width: Int, height: Int, alpha: Bool = false) throws -> PixelImage {
        let channels = alpha ? 4 : 3
        let image = PixelImage()
        try imagingCall { err in
            pi_image_alloc(&image.raw, Int32(width), Int32(height), Int32(channels), err)
        }
        let pixels = image.raw.pixels!
        for y in 0..<height {
            for x in 0..<width {
                let i = (y * width + x) * channels
                let checker = ((x / 16) + (y / 16)) % 2 == 0
                pixels[i] = UInt8(truncatingIfNeeded: x &* 255 / max(width - 1, 1))
                pixels[i + 1] = UInt8(truncatingIfNeeded: y &* 255 / max(height - 1, 1))
                pixels[i + 2] = checker ? 230 : 40
                if alpha {
                    // A horizontal alpha ramp, so flattening onto white is visible and
                    // testable rather than a no-op.
                    pixels[i + 3] = UInt8(truncatingIfNeeded: x &* 255 / max(width - 1, 1))
                }
            }
        }
        return image
    }

    /// A JPEG, via the pipeline's own encoder.
    public static func jpeg(width: Int, height: Int, quality: Int = 90) throws -> Data {
        try image(width: width, height: height).encodedJPEG(quality: quality, optimize: true)
    }

    /// A JPEG carrying an EXIF orientation tag.
    ///
    /// Worth generating because its absence hid a real bug: libjpeg does not rotate, so
    /// without an oriented fixture nothing proved the pipeline was baking orientation at all.
    /// The APP1 is built by hand — one IFD0 entry is all it takes, and constructing it here
    /// keeps the test independent of any library's idea of how to write EXIF.
    public static func jpeg(width: Int, height: Int, orientation: Int,
                            quality: Int = 90) throws -> Data {
        let plain = try jpeg(width: width, height: height, quality: quality)

        var tiff = Data()
        tiff.append(contentsOf: [0x49, 0x49, 0x2A, 0x00])   // "II", 42
        tiff.append(contentsOf: le32(8))                     // IFD0 at offset 8
        tiff.append(contentsOf: le16(1))                     // one entry
        tiff.append(contentsOf: le16(0x0112))                // Orientation
        tiff.append(contentsOf: le16(3))                     // SHORT
        tiff.append(contentsOf: le32(1))                     // count
        tiff.append(contentsOf: le16(UInt16(orientation)))   // value, left-aligned in 4 bytes
        tiff.append(contentsOf: le16(0))
        tiff.append(contentsOf: le32(0))                     // no next IFD

        var app1 = Data("Exif".utf8)
        app1.append(contentsOf: [0x00, 0x00])
        app1.append(tiff)

        var out = Data([0xFF, 0xD8, 0xFF, 0xE1])
        let length = UInt16(app1.count + 2)
        out.append(UInt8(length >> 8))
        out.append(UInt8(length & 0xFF))
        out.append(app1)
        out.append(plain.dropFirst(2))
        return out
    }

    /// A HEIC, via the pipeline's own encoder.
    public static func heic(width: Int, height: Int, quality: Int = 70) throws -> Data {
        try image(width: width, height: height).encodedHEIC(quality: quality)
    }

    /// An EXIF APP1 payload — `"Exif\0\0"` followed by a little-endian TIFF carrying the tags
    /// given. Shared by the oriented-JPEG and EXIF-bearing-HEIC fixtures.
    ///
    /// Only the shapes the pipeline actually reads: SHORT for orientation, ASCII for strings.
    public static func exifAPP1(orientation: Int? = nil, model: String? = nil) -> Data {
        var entries: [(tag: UInt16, type: UInt16, count: UInt32, inline: [UInt8]?)] = []
        var heap = Data()
        if let orientation {
            entries.append((0x0112, 3, 1, le16(UInt16(orientation)) + [0, 0]))
        }
        if let model {
            let bytes = Data(model.utf8) + Data([0])
            entries.append((0x0110, 2, UInt32(bytes.count), nil))
            heap = bytes
        }
        entries.sort { $0.tag < $1.tag }

        let ifdSize = 2 + entries.count * 12 + 4
        let heapOffset = UInt32(8 + ifdSize)   // offsets are from the TIFF header

        var tiff = Data([0x49, 0x49, 0x2A, 0x00])
        tiff.append(contentsOf: le32(8))
        tiff.append(contentsOf: le16(UInt16(entries.count)))
        for e in entries {
            tiff.append(contentsOf: le16(e.tag))
            tiff.append(contentsOf: le16(e.type))
            tiff.append(contentsOf: le32(e.count))
            if let inline = e.inline { tiff.append(contentsOf: inline) }
            else { tiff.append(contentsOf: le32(heapOffset)) }
        }
        tiff.append(contentsOf: le32(0))
        tiff.append(heap)

        var app1 = Data("Exif".utf8)
        app1.append(contentsOf: [0x00, 0x00])
        app1.append(tiff)
        return app1
    }

    /// A HEIC that actually carries EXIF.
    ///
    /// The plain `heic(width:height:)` above encodes pixels and nothing else, so for a long
    /// time *every* synthetic HEIC had no metadata — and a HEIF EXIF reader that returned
    /// nothing at all passed the entire suite while dropping the date, GPS and Live Photo
    /// identifier of all 1,531 HEICs in the real library.
    public static func heic(at url: URL, width: Int, height: Int,
                            orientation: Int? = nil, model: String? = nil) throws {
        let app1 = exifAPP1(orientation: orientation, model: model)
        try imagingCall { err in
            url.withUnsafeFileSystemRepresentation { path in
                app1.withUnsafeBytes { raw in
                    pi_fixture_write_heic_with_exif(path, Int32(width), Int32(height),
                                                    raw.bindMemory(to: UInt8.self).baseAddress,
                                                    raw.count, err)
                }
            }
        }
    }

    /// A short HEVC/MP4 clip, optionally carrying a display matrix.
    public static func video(at url: URL, width: Int = 64, height: Int = 48,
                             frames: Int = 10, rotation: Int = 0,
                             contentIdentifier: String? = nil) throws {
        try imagingCall { err in
            url.withUnsafeFileSystemRepresentation { path in
                if let contentIdentifier {
                    return contentIdentifier.withCString { cid in
                        pi_fixture_write_video(path, Int32(width), Int32(height),
                                               Int32(frames), Int32(rotation), cid, err)
                    }
                }
                return pi_fixture_write_video(path, Int32(width), Int32(height),
                                              Int32(frames), Int32(rotation), nil, err)
            }
        }
    }

    /// A CR2-shaped file: a little-endian TIFF whose IFD0 announces "old-style JPEG" and
    /// points at a real JPEG stream, exactly as Canon writes it.
    ///
    /// This is the shape decision 2 relies on — the full-resolution camera JPEG that makes
    /// "developing" a raw a byte-range copy rather than a demosaic.
    ///
    /// `model` adds an ASCII tag to IFD0. It exists so the EXIF graft has something to carry:
    /// without any tags, `buildAPP1` correctly produces nothing and the graft path is never
    /// actually exercised.
    public static func cr2(wrapping jpeg: Data, width: Int, height: Int,
                           model: String? = nil) -> Data {
        var entries: [(tag: UInt16, type: UInt16, count: UInt32, value: UInt32)] = [
            (0x0100, 4, 1, UInt32(width)),       // ImageWidth
            (0x0101, 4, 1, UInt32(height)),      // ImageLength
            (0x0103, 3, 1, 6),                   // Compression = old-style JPEG
            (0x0111, 4, 1, 0),                   // StripOffsets, patched below
            (0x0117, 4, 1, UInt32(jpeg.count)),  // StripByteCounts
        ]

        // ASCII values longer than four bytes live outside the entry, at an offset.
        var modelBytes = Data()
        if let model {
            modelBytes = Data(model.utf8) + Data([0])
            entries.append((0x0110, 2, UInt32(modelBytes.count), 0))
        }
        entries.sort { $0.tag < $1.tag }   // TIFF requires ascending tag order

        let ifdSize = 2 + entries.count * 12 + 4
        let modelOffset = UInt32(16 + ifdSize)
        let jpegOffset = modelOffset + UInt32(modelBytes.count)

        var out = Data()
        out.append(contentsOf: [0x49, 0x49, 0x2A, 0x00])  // "II", 42
        out.append(contentsOf: le32(16))                   // IFD0 lives at offset 16
        out.append(contentsOf: [0x43, 0x52, 0x02, 0x00])   // CR2 magic at offset 8
        out.append(contentsOf: [0, 0, 0, 0])               // pad out to 16
        out.append(contentsOf: le16(UInt16(entries.count)))
        for entry in entries {
            out.append(contentsOf: le16(entry.tag))
            out.append(contentsOf: le16(entry.type))
            out.append(contentsOf: le32(entry.count))
            switch entry.tag {
            case 0x0111: out.append(contentsOf: le32(jpegOffset))
            case 0x0110: out.append(contentsOf: le32(modelOffset))
            default:     out.append(contentsOf: le32(entry.value))
            }
        }
        out.append(contentsOf: le32(0))                    // no next IFD
        out.append(modelBytes)
        out.append(jpeg)
        return out
    }

    static func le16(_ v: UInt16) -> [UInt8] { [UInt8(v & 0xFF), UInt8(v >> 8)] }
    static func le32(_ v: UInt32) -> [UInt8] {
        [UInt8(v & 0xFF), UInt8((v >> 8) & 0xFF), UInt8((v >> 16) & 0xFF), UInt8(v >> 24)]
    }

    /// A PNG, written by hand.
    ///
    /// Deflate's "stored" block type lets a valid PNG be produced with no compressor at all,
    /// which is what keeps a PNG *encoder* out of decision 23's enumerated ffmpeg build for
    /// the sake of test fixtures. The output is larger than a real PNG and entirely legal;
    /// what matters is that ffmpeg's real PNG decoder reads it, alpha included.
    public static func png(width: Int, height: Int, alpha: Bool = true) throws -> Data {
        let channels = alpha ? 4 : 3
        var raw = Data(capacity: height * (1 + width * channels))
        for y in 0..<height {
            raw.append(0)  // filter type: none
            for x in 0..<width {
                raw.append(UInt8(truncatingIfNeeded: x &* 255 / max(width - 1, 1)))
                raw.append(UInt8(truncatingIfNeeded: y &* 255 / max(height - 1, 1)))
                raw.append(((x / 16) + (y / 16)) % 2 == 0 ? 230 : 40)
                if alpha { raw.append(UInt8(truncatingIfNeeded: x &* 255 / max(width - 1, 1))) }
            }
        }

        var png = Data([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A])

        var ihdr = Data()
        ihdr.append(contentsOf: be32(UInt32(width)))
        ihdr.append(contentsOf: be32(UInt32(height)))
        ihdr.append(8)                      // bit depth
        ihdr.append(alpha ? 6 : 2)          // colour type: RGBA or RGB
        ihdr.append(contentsOf: [0, 0, 0])  // deflate, adaptive filtering, no interlace
        png.append(chunk("IHDR", ihdr))
        png.append(chunk("IDAT", zlibStored(raw)))
        png.append(chunk("IEND", Data()))
        return png
    }

    private static func be32(_ value: UInt32) -> [UInt8] {
        [UInt8(value >> 24 & 0xFF), UInt8(value >> 16 & 0xFF),
         UInt8(value >> 8 & 0xFF), UInt8(value & 0xFF)]
    }

    private static func chunk(_ type: String, _ payload: Data) -> Data {
        var out = Data()
        out.append(contentsOf: be32(UInt32(payload.count)))
        let body = Data(type.utf8) + payload
        out.append(body)
        out.append(contentsOf: be32(crc32(body)))
        return out
    }

    /// A zlib stream made only of stored (uncompressed) deflate blocks.
    private static func zlibStored(_ data: Data) -> Data {
        var out = Data([0x78, 0x01])  // zlib header: deflate, 32K window, no dictionary
        var offset = data.startIndex
        if data.isEmpty {
            out.append(contentsOf: [0x01, 0x00, 0x00, 0xFF, 0xFF])
        }
        while offset < data.endIndex {
            let count = min(0xFFFF, data.distance(from: offset, to: data.endIndex))
            let end = data.index(offset, offsetBy: count)
            let isFinal = end == data.endIndex
            out.append(isFinal ? 0x01 : 0x00)
            out.append(UInt8(count & 0xFF))
            out.append(UInt8(count >> 8 & 0xFF))
            out.append(UInt8(~count & 0xFF))
            out.append(UInt8((~count >> 8) & 0xFF))
            out.append(data[offset..<end])
            offset = end
        }
        out.append(contentsOf: be32(adler32(data)))
        return out
    }

    private static let crcTable: [UInt32] = (0..<256).map { i -> UInt32 in
        var c = UInt32(i)
        for _ in 0..<8 { c = (c & 1) != 0 ? 0xEDB8_8320 ^ (c >> 1) : c >> 1 }
        return c
    }

    private static func crc32(_ data: Data) -> UInt32 {
        var c: UInt32 = 0xFFFF_FFFF
        for byte in data { c = crcTable[Int((c ^ UInt32(byte)) & 0xFF)] ^ (c >> 8) }
        return c ^ 0xFFFF_FFFF
    }

    private static func adler32(_ data: Data) -> UInt32 {
        var a: UInt32 = 1, b: UInt32 = 0
        for byte in data {
            a = (a + UInt32(byte)) % 65521
            b = (b + a) % 65521
        }
        return (b << 16) | a
    }
}
