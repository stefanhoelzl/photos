import Foundation
import CImaging
import PhotosCore

/// §7's `ImageBackend`, implemented with libexif and libheif.
///
/// This is the extraction half only. What a tag *means* — that `"2013:07:04 18:22:11"` is a
/// UTC instant, that `GPSLatitudeRef 'S'` makes a latitude negative — is `ExifMapper` in
/// `PhotosCore`, shared with the iOS backend, because that is where a laptop/phone
/// disagreement would corrupt the catalog.
public struct NativeImageBackend: ImageBackend {

    public init() {}

    public func rawTags(at url: URL) throws -> ExifTags {
        // Videos keep their metadata in the container rather than an EXIF block, so they take
        // the probe path and are given the same tag names as everything else.
        if MediaFormat.sniff(url) == .video {
            return try videoTags(at: url)
        }

        final class Collector { var values: [String: ExifValue] = [:] }
        let collector = Collector()

        try withExtendedLifetime(collector) {
            try imagingCall { err in
                url.withUnsafeFileSystemRepresentation { path in
                    pi_exif_read(path, { context, tag, value in
                        guard let context, let tag, let value else { return }
                        let collector = Unmanaged<Collector>.fromOpaque(context).takeUnretainedValue()
                        let name = String(cString: tag)
                        // First writer wins: IFD0 and IFD1 (the embedded thumbnail's own
                        // directory) both carry Orientation and ImageWidth, and IFD1's
                        // describe a 160px thumbnail, not the photograph.
                        if collector.values[name] == nil {
                            collector.values[name] = ExifValue.parse(String(cString: value))
                        }
                    }, Unmanaged.passUnretained(collector).toOpaque(), err)
                }
            }
        }
        return ExifTags(collector.values)
    }

    /// A video's tags, in the same vocabulary as a photo's, so `ExifMapper` needs no special
    /// case and the catalog row is built the same way for both.
    private func videoTags(at url: URL) throws -> ExifTags {
        let info = try VideoInfo.probe(url)
        var values: [String: ExifValue] = [:]

        // Rotation is baked into the poster and the transcode, so the dimensions reported here
        // are display dimensions — which is what §3 stores.
        let transposed = info.rotation == 90 || info.rotation == 270
        values["PixelXDimension"] = .int(transposed ? info.height : info.width)
        values["PixelYDimension"] = .int(transposed ? info.width : info.height)
        if let identifier = info.contentIdentifier {
            values["AppleContentIdentifier"] = .string(identifier)
        }
        return ExifTags(values)
    }
}

extension ExifValue {
    /// Parses the shim's canonical rendering back into a typed value.
    ///
    /// The shim renders integers as decimal, rationals unreduced as `num/den`, and repeats
    /// either separated by spaces — so a GPS coordinate arrives as three exact rationals
    /// rather than a number some library already divided and rounded.
    ///
    /// Anything whose tokens do not all parse as numbers is a string. That test is what keeps
    /// `"2013:07:04 18:22:11"` — which has spaces, and would otherwise look like a list — a
    /// date rather than a mangled array.
    static func parse(_ text: String) -> ExifValue {
        let tokens = text.split(separator: " ", omittingEmptySubsequences: true)
        guard !tokens.isEmpty else { return .string(text) }

        var parsed: [ExifValue] = []
        parsed.reserveCapacity(tokens.count)
        for token in tokens {
            guard let value = parseScalar(token) else { return .string(text) }
            parsed.append(value)
        }
        return parsed.count == 1 ? parsed[0] : .array(parsed)
    }

    private static func parseScalar(_ token: Substring) -> ExifValue? {
        if let slash = token.firstIndex(of: "/") {
            guard let numerator = Double(token[token.startIndex..<slash]),
                  let denominator = Double(token[token.index(after: slash)...])
            else { return nil }
            return .rational(numerator: numerator, denominator: denominator)
        }
        if let integer = Int(token) { return .int(integer) }
        // A bare float is not something EXIF stores, but a backend is free to hand one over.
        if let double = Double(token) { return .double(double) }
        return nil
    }
}
