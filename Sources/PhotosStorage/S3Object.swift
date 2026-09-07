import Foundation

/// One object as reported by HEAD or LIST.
public struct S3Object: Hashable, Sendable {
    /// The key relative to the zone root, NFC-normalised and percent-decoded.
    public let key: String
    public let size: Int64
    public let etag: ETag?
    public let lastModified: Date?

    public init(key: String, size: Int64, etag: ETag? = nil, lastModified: Date? = nil) {
        self.key = key
        self.size = size
        self.etag = etag
        self.lastModified = lastModified
    }

    /// bunny.net materialises implicit directory markers: writing
    /// `meta/Trips/Iceland.db` also produces a zero-byte `meta/Trips/` key with no
    /// ETag, and LIST returns it (§2). The client reports keys raw; §4's sync diff
    /// is what must skip these.
    public var isDirectoryMarker: Bool {
        key.hasSuffix("/")
    }
}

/// Result of a conditional GET.
public enum GetResult: Sendable {
    /// The server answered 304 — the caller's ETag is still current.
    case notModified
    case object(Data, ETag?)

    public var data: Data? {
        if case .object(let d, _) = self { return d }
        return nil
    }
}

/// Result of a PUT that carried `If-Match`.
public enum PutResult: Sendable {
    case written(ETag?)
    /// The server answered 412 — the object changed since it was read, so the
    /// caller must re-read and retry (§2's guarded single-owner shard rule).
    case staleETag

    public var etag: ETag? {
        if case .written(let tag) = self { return tag }
        return nil
    }
}
