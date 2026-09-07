import Foundation

/// An object's entity tag.
///
/// Deliberately opaque: never parsed, never compared against a computed MD5.
/// Multipart ETags are not MD5s at all (they carry a `-<partCount>` suffix), and
/// bunny.net is free to use any scheme it likes. Treating the value as a token
/// means none of that matters.
///
/// A distinct type rather than `String` so it cannot be mixed up with a key, and
/// so the quoted/unquoted distinction is handled in exactly one place — S3 returns
/// ETags quoted, and a stray pair of quotes on one side of a comparison would make
/// the sync diff re-download the entire library.
public struct ETag: Hashable, Sendable, CustomStringConvertible {
    /// The tag without surrounding quotes.
    public let value: String

    /// Takes a raw header value, e.g. `"d41d8cd9…"` or `W/"d41d8cd9…"`.
    public init(header: String) {
        var s = header.trimmingCharacters(in: .whitespaces)
        if s.hasPrefix("W/") { s.removeFirst(2) }
        if s.hasPrefix("\""), s.hasSuffix("\""), s.count >= 2 {
            s = String(s.dropFirst().dropLast())
        }
        self.value = s
    }

    /// Takes a value that is already unquoted (e.g. from a LIST response body).
    public init(unquoted: String) {
        self.value = unquoted
    }

    /// Re-quoted, ready for `If-Match` / `If-None-Match`.
    public var headerValue: String { "\"\(value)\"" }

    public var description: String { value }
}
