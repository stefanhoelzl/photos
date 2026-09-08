import Foundation
import CImaging

/// Carving the full-resolution JPEG out of a Canon CR2.
///
/// §5 calls this "developing the RAW", which suggests a demosaic. It is not one. Every CR2 in
/// the library carries a complete, full-resolution baseline JPEG at IFD0 — 5184×3456 at about
/// 2.5 MB, the camera's own rendering — so the derivative §5 wants already exists inside the
/// file and extracting it is a byte-range copy.
///
/// That is what kept LibRaw, a demosaic pass and a set of development parameters out of
/// milestone C entirely, and it revises the projected cost of the 139 CR2s from ~1.2 GB to
/// ~0.35 GB. What it gives up is the latitude a real raw development would have recovered;
/// for a 2012 archive of holiday photographs, the camera's own JPEG is the picture that would
/// have been taken had the camera been set to JPEG.
///
/// IFD3 holds the actual sensor data and IFD2 a 660×441 preview; neither is touched.
public enum CR2 {

    public struct Extraction: Sendable {
        /// The embedded JPEG, with an EXIF APP1 grafted in from the CR2's own IFDs.
        public var jpeg: Data
        public var width: Int
        public var height: Int
    }

    public enum Failure: Error, CustomStringConvertible {
        case notATIFF
        case noEmbeddedJPEG
        case truncated

        public var description: String {
            switch self {
            case .notATIFF: "not a TIFF-rooted file"
            case .noEmbeddedJPEG: "no full-resolution JPEG at IFD0"
            case .truncated: "file ends inside the embedded JPEG"
            }
        }
    }

    /// One IFD entry, only as far as we need to read it.
    private struct Entry {
        var type: UInt16
        var count: UInt32
        var value: UInt32
    }

    private struct Reader {
        let data: Data
        let bigEndian: Bool

        func u16(_ offset: Int) -> UInt16? {
            guard offset >= 0, offset + 2 <= data.count else { return nil }
            let a = UInt16(data[data.startIndex + offset])
            let b = UInt16(data[data.startIndex + offset + 1])
            return bigEndian ? (a << 8 | b) : (b << 8 | a)
        }

        func u32(_ offset: Int) -> UInt32? {
            guard offset >= 0, offset + 4 <= data.count else { return nil }
            let bytes = (0..<4).map { UInt32(data[data.startIndex + offset + $0]) }
            return bigEndian
                ? (bytes[0] << 24 | bytes[1] << 16 | bytes[2] << 8 | bytes[3])
                : (bytes[3] << 24 | bytes[2] << 16 | bytes[1] << 8 | bytes[0])
        }
    }

    /// Reads the whole file, since the JPEG at IFD0 is a couple of megabytes into a ~23 MB
    /// file and the EXIF graft wants the IFDs too. 139 files, once.
    public static func extract(contentsOf url: URL) throws -> Extraction {
        let data = try Data(contentsOf: url)
        return try extract(data)
    }

    public static func extract(_ data: Data) throws -> Extraction {
        guard data.count > 16 else { throw Failure.notATIFF }
        let marker = data.prefix(2)
        let bigEndian: Bool
        if marker.elementsEqual([0x4D, 0x4D]) { bigEndian = true }
        else if marker.elementsEqual([0x49, 0x49]) { bigEndian = false }
        else { throw Failure.notATIFF }

        let reader = Reader(data: data, bigEndian: bigEndian)
        guard let firstIFD = reader.u32(4) else { throw Failure.notATIFF }

        // IFD0 is the one that carries the full-resolution JPEG. Walking the chain rather
        // than assuming a fixed layout, because "IFD0 is first" is a convention of Canon's
        // writer, not a guarantee of the format.
        var offset = Int(firstIFD)
        var index = 0
        while offset > 0, offset + 2 <= data.count, index < 8 {
            guard let count = reader.u16(offset) else { break }
            var entries: [UInt16: Entry] = [:]
            for i in 0..<Int(count) {
                let base = offset + 2 + i * 12
                guard let tag = reader.u16(base),
                      let type = reader.u16(base + 2),
                      let n = reader.u32(base + 4),
                      let value = reader.u32(base + 8) else { break }
                entries[tag] = Entry(type: type, count: n, value: value)
            }

            // compression 6 == "old-style JPEG", which is how the CR2 stores its full-size
            // preview. StripOffsets/StripByteCounts then bound the JPEG exactly.
            if entries[0x0103]?.value == 6,
               let start = entries[0x0111]?.value,
               let length = entries[0x0117]?.value,
               length > 0 {
                let from = Int(start), size = Int(length)
                guard from + size <= data.count else { throw Failure.truncated }
                let jpeg = data.subdata(in: (data.startIndex + from)..<(data.startIndex + from + size))
                guard jpeg.count > 3, jpeg[jpeg.startIndex] == 0xFF,
                      jpeg[jpeg.startIndex + 1] == 0xD8 else { throw Failure.noEmbeddedJPEG }

                let width = Int(entries[0x0100]?.value ?? 0)
                let height = Int(entries[0x0101]?.value ?? 0)
                return Extraction(jpeg: graftEXIF(into: jpeg, from: data),
                                  width: width, height: height)
            }

            guard let next = reader.u32(offset + 2 + Int(count) * 12) else { break }
            offset = Int(next)
            index += 1
        }
        throw Failure.noEmbeddedJPEG
    }

    /// Inserts an EXIF APP1 segment built from the CR2's own TIFF IFDs.
    ///
    /// The extracted stream has no APP1 of its own — it is a bare JPEG living inside a TIFF,
    /// so its date, GPS and orientation are all in the CR2's directories rather than in the
    /// bytes being copied out. Every other original in the bucket is uploaded byte-for-byte
    /// with its metadata intact; this is the only original the pipeline synthesises, and
    /// without the graft it would be the only one that arrives blank.
    ///
    /// A failed graft is not fatal: the catalog carries the date and coordinates regardless,
    /// so the worst case is an uploaded JPEG that is merely less self-describing.
    static func graftEXIF(into jpeg: Data, from cr2: Data) -> Data {
        guard let app1 = buildAPP1(from: cr2), !app1.isEmpty else { return jpeg }
        // APP1 length is a 16-bit field covering itself, so a large block cannot be written.
        guard app1.count + 2 <= 0xFFFF else { return jpeg }

        var out = Data(capacity: jpeg.count + app1.count + 4)
        out.append(contentsOf: [0xFF, 0xD8])            // SOI
        out.append(contentsOf: [0xFF, 0xE1])            // APP1
        let length = UInt16(app1.count + 2)
        out.append(UInt8(length >> 8))
        out.append(UInt8(length & 0xFF))
        out.append(app1)
        out.append(jpeg.dropFirst(2))                   // everything after the original SOI
        return out
    }

    private static func buildAPP1(from cr2: Data) -> Data? {
        var buffer = pi_buffer()
        pi_buffer_init(&buffer)
        defer { pi_buffer_free(&buffer) }

        var err = pi_error()
        let ok = cr2.withUnsafeBytes { raw -> Bool in
            pi_exif_app1_from_tiff(raw.bindMemory(to: UInt8.self).baseAddress,
                                   raw.count, &buffer, &err) == 0
        }
        guard ok, let bytes = buffer.bytes, buffer.len > 0 else { return nil }
        return Data(bytes: bytes, count: buffer.len)
    }
}
