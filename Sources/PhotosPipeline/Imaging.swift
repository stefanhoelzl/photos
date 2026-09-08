import Foundation
import CImaging

/// A failure from the native imaging stack.
///
/// Every one of these is per-file and recoverable by design: decision 15 says an undecodable
/// file is skipped and reported, never a reason to abandon a run. A corrupt JPEG is not the
/// library breaking its contract the way §7's byte-size mismatch is — it is just a bad file.
public struct ImagingError: Error, CustomStringConvertible, Sendable {
    public enum Code: Int32, Sendable {
        case open = 1, decode = 2, encode = 3, unsupported = 4, memory = 5, invalid = 6
        case unknown = -1
    }

    public var code: Code
    public var message: String

    init(_ err: pi_error) {
        self.code = Code(rawValue: err.code) ?? .unknown
        self.message = withUnsafeBytes(of: err.message) { raw in
            let bytes = raw.bindMemory(to: CChar.self)
            return String(cString: bytes.baseAddress!)
        }
    }

    init(code: Code, message: String) {
        self.code = code
        self.message = message
    }

    public var description: String { message }
}

/// Runs a shim call that fills a `pi_error`, turning a nonzero return into a thrown error.
@inline(__always)
func imagingCall(_ body: (UnsafeMutablePointer<pi_error>) -> Int32) throws {
    var err = pi_error()
    if body(&err) != 0 { throw ImagingError(err) }
}

/// An owned decoded image. Frees the underlying buffer exactly once, on deinit.
///
/// A class rather than a struct: `pi_image` owns two heap allocations, and a value type would
/// need copy semantics that either duplicate a 500 MB panorama or leave a double free.
public final class PixelImage {
    /// The C struct stays internal: `photos-scan` and the tests need to decode, resize and
    /// encode, but nothing outside this module should be handling `pi_image` lifetimes.
    var raw: pi_image

    public init() {
        raw = pi_image()
        pi_image_init(&raw)
    }

    deinit { pi_image_free(&raw) }

    public var width: Int { Int(raw.width) }
    public var height: Int { Int(raw.height) }
    public var channels: Int { Int(raw.channels) }
    /// The source photograph's display dimensions, orientation applied. Differs from
    /// `width`/`height` whenever shrink-on-load produced a smaller buffer — which is most
    /// large JPEGs, so this is what §3's stored dimensions must come from.
    public var sourceWidth: Int { Int(raw.source_width) }
    public var sourceHeight: Int { Int(raw.source_height) }
    public var hasAlpha: Bool { raw.channels == 4 }
    public var hasProfile: Bool { raw.icc != nil && raw.icc_len > 0 }

    /// Borrowed access to the interleaved pixel buffer, for callers that need to compare two
    /// images -- the PSNR sweep, and tests that check a colour actually landed where it should.
    public func withPixels<R>(_ body: (UnsafeBufferPointer<UInt8>) throws -> R) rethrows -> R {
        let count = width * height * channels
        return try body(UnsafeBufferPointer(start: raw.pixels, count: max(0, count)))
    }

    /// Decodes a file, hinting the largest tier the caller will ask for.
    ///
    /// The hint is what bounds memory: libjpeg decodes straight out of the DCT coefficients at
    /// N/8 scale, so the library's 27558×5973 panorama comes back at 1/8 — about 7.7 MB rather
    /// than the 494 MB a full decode would need, times however many workers are running.
    public static func decode(contentsOf url: URL, maxLongEdge: Int) throws -> PixelImage {
        let image = PixelImage()
        try imagingCall { err in
            url.withUnsafeFileSystemRepresentation { path in
                pi_decode(path, Int32(maxLongEdge), &image.raw, err)
            }
        }
        return image
    }

    public static func decode(jpeg data: Data, maxLongEdge: Int) throws -> PixelImage {
        let image = PixelImage()
        try imagingCall { err in
            data.withUnsafeBytes { raw in
                pi_decode_memory(raw.bindMemory(to: UInt8.self).baseAddress, raw.count,
                                 Int32(maxLongEdge), &image.raw, err)
            }
        }
        return image
    }

    /// The poster still for a video, with the display matrix already baked into the pixels.
    public static func poster(of url: URL, at seconds: Double) throws -> PixelImage {
        let image = PixelImage()
        try imagingCall { err in
            url.withUnsafeFileSystemRepresentation { path in
                pi_video_poster(path, seconds, &image.raw, err)
            }
        }
        return image
    }

    public func resizedFitting(longEdge: Int, allowUpscale: Bool = false) throws -> PixelImage {
        let out = PixelImage()
        try imagingCall { err in
            pi_resize_fit(&raw, Int32(longEdge), allowUpscale ? 1 : 0, &out.raw, err)
        }
        return out
    }

    public func squareCropped(edge: Int) throws -> PixelImage {
        let out = PixelImage()
        try imagingCall { err in pi_resize_square_crop(&raw, Int32(edge), &out.raw, err) }
        return out
    }

    /// Composites transparency onto an opaque background. 26 of the library's 36 PNG/TIFF
    /// files have an alpha channel and JPEG cannot represent one at all.
    public func flattenAlpha(_ background: (r: UInt8, g: UInt8, b: UInt8)) throws {
        try imagingCall { err in
            pi_flatten_alpha(&raw, background.r, background.g, background.b, err)
        }
    }

    public func convertToSRGB() throws {
        try imagingCall { err in pi_convert_to_srgb(&raw, err) }
    }

    public func encodedJPEG(quality: Int, optimize: Bool = true) throws -> Data {
        var buffer = pi_buffer()
        pi_buffer_init(&buffer)
        defer { pi_buffer_free(&buffer) }
        try imagingCall { err in
            pi_encode_jpeg(&raw, Int32(quality), optimize ? 1 : 0, &buffer, err)
        }
        return Data(bytes: buffer.bytes, count: buffer.len)
    }

    /// `threads` bounds x265's own pool; 1 is right whenever the caller is already running
    /// one worker per core, which is what `Pipeline` assumes.
    public func encodedHEIC(quality: Int, threads: Int = 1) throws -> Data {
        var buffer = pi_buffer()
        pi_buffer_init(&buffer)
        defer { pi_buffer_free(&buffer) }
        try imagingCall { err in
            pi_encode_heic(&raw, Int32(quality), Int32(threads), &buffer, err)
        }
        return Data(bytes: buffer.bytes, count: buffer.len)
    }
}

/// What `pi_sniff` decided a file is, from a bounded header read.
///
/// Extensions are not consulted. Decision 22's denylist excludes the known junk and everything
/// else is sniffed, so a file's content decides what it is — which is also why pointing the
/// scan at a 2.66 GB SQLite database costs one small read rather than a decode attempt.
public enum MediaFormat: Sendable, Hashable {
    case jpeg, heif, png, tiff, cr2, video, unknown

    init(_ raw: pi_format) {
        switch raw {
        case PI_FORMAT_JPEG: self = .jpeg
        case PI_FORMAT_HEIF: self = .heif
        case PI_FORMAT_PNG: self = .png
        case PI_FORMAT_TIFF: self = .tiff
        case PI_FORMAT_CR2: self = .cr2
        case PI_FORMAT_VIDEO: self = .video
        default: self = .unknown
        }
    }

    public static func sniff(_ url: URL) -> MediaFormat {
        MediaFormat(url.withUnsafeFileSystemRepresentation { pi_sniff($0) })
    }

    public var isStillImage: Bool {
        switch self {
        case .jpeg, .heif, .png, .tiff: true
        case .cr2, .video, .unknown: false
        }
    }
}

/// What a video's container says about it, before any decoding.
public struct VideoInfo: Sendable, Hashable {
    public var width: Int
    public var height: Int
    public var duration: Double
    /// Degrees from the display matrix. 118 of the library's files say 90 and 15 say 180.
    public var rotation: Int
    public var hasAudio: Bool
    public var isInterlaced: Bool
    /// `com.apple.quicktime.content.identifier` — the Live Photo pairing id, and the whole
    /// basis of decision 14. Empty when absent.
    public var contentIdentifier: String?

    public static func probe(_ url: URL) throws -> VideoInfo {
        var info = pi_video_info()
        try imagingCall { err in
            url.withUnsafeFileSystemRepresentation { pi_video_probe($0, &info, err) }
        }
        let identifier = withUnsafeBytes(of: info.content_identifier) { raw -> String in
            let bytes = raw.bindMemory(to: CChar.self)
            return String(cString: bytes.baseAddress!)
        }
        return VideoInfo(
            width: Int(info.width),
            height: Int(info.height),
            duration: info.duration,
            rotation: Int(info.rotation),
            hasAudio: info.has_audio != 0,
            isInterlaced: info.interlaced != 0,
            contentIdentifier: identifier.isEmpty ? nil : identifier
        )
    }
}
