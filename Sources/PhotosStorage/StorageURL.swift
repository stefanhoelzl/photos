import Foundation

/// One storage URL carries endpoint, signing region and zone (§1).
///
/// e.g. `https://de-s3.storage.bunnycdn.com/my-photos` gives
/// endpoint `https://de-s3.storage.bunnycdn.com`, region `de`, zone `my-photos`.
///
/// On bunny.net the zone name *is* the access key ID, so a single URL plus a
/// password is a complete credential. Both the app's setup screen and the CLI's
/// `PHOTOS_ENDPOINT` parse the same string, which is why this lives beside the
/// signer rather than in either caller.
public struct StorageURL: Hashable, Sendable {
    /// Scheme and host only, no trailing slash.
    public let endpoint: URL
    /// SigV4 signing region, taken from the host's `<region>-s3` prefix.
    public let region: String
    /// The zone name — also the access key ID on bunny.net.
    public let zone: String

    public enum ParseError: Error, Equatable, CustomStringConvertible {
        case notAURL(String)
        case missingScheme
        case unsupportedScheme(String)
        case missingHost
        case missingZone

        public var description: String {
            switch self {
            case .notAURL(let s): "not a valid URL: \(s)"
            case .missingScheme: "missing scheme — the URL must start with https://"
            case .unsupportedScheme(let s): "unsupported scheme \(s) — must be http or https"
            case .missingHost: "missing host"
            case .missingZone: "missing storage zone — expected https://<host>/<zone>"
            }
        }
    }

    /// Default when the host carries no `<region>-s3` prefix. bunny.net's
    /// signer accepts this for its unprefixed endpoint.
    public static let defaultRegion = "de"

    public init(_ string: String) throws {
        let trimmed = string.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let components = URLComponents(string: trimmed) else {
            throw ParseError.notAURL(trimmed)
        }
        guard let scheme = components.scheme?.lowercased() else { throw ParseError.missingScheme }
        guard scheme == "https" || scheme == "http" else {
            throw ParseError.unsupportedScheme(scheme)
        }
        guard let host = components.host, !host.isEmpty else { throw ParseError.missingHost }

        // Path may be '/zone', '/zone/', or '/zone/nested/prefix' — only the
        // first segment is the zone; anything deeper is ignored, since keys are
        // always addressed from the zone root.
        let segments = components.path.split(separator: "/", omittingEmptySubsequences: true)
        guard let first = segments.first, !first.isEmpty else { throw ParseError.missingZone }

        var endpointComponents = URLComponents()
        endpointComponents.scheme = scheme
        endpointComponents.host = host
        endpointComponents.port = components.port
        guard let endpoint = endpointComponents.url else { throw ParseError.missingHost }

        self.endpoint = endpoint
        self.zone = String(first)
        self.region = Self.region(fromHost: host)
    }

    /// `de-s3.storage.bunnycdn.com` → `de`; `storage.bunnycdn.com` → the default.
    ///
    /// Hostnames are case-insensitive, so the label is lowercased before the `-s3`
    /// test. Without that, a URL typed as `DE-S3.…` would silently fall back to the
    /// default region and every signature would fail with an unexplained 403.
    static func region(fromHost host: String) -> String {
        guard let first = host.split(separator: ".").first else { return defaultRegion }
        let label = first.lowercased()
        guard label.hasSuffix("-s3") else { return defaultRegion }
        let region = label.dropLast(3)
        return region.isEmpty ? defaultRegion : String(region)
    }
}
