import Foundation
import Crypto

/// AWS Signature Version 4.
///
/// Hand-written rather than taken from Soto, whose `SotoSignerV4` lives inside
/// soto-core and drags in swift-nio (§7). The surface needed here is small: one
/// service, one region, static credentials, no STS session tokens, path-style URLs.
///
/// A subtle bug here fails *every* request, and the local S3 server used in tests
/// does not validate signatures — so the vendored AWS vector suite is the only proof
/// this code is correct. It is compared stage by stage (canonical request,
/// string-to-sign, signature) so a failure names which stage broke.
public struct SigV4Signer: Sendable {
    public let accessKeyID: String
    public let secretAccessKey: String
    public let region: String
    public let service: String

    /// S3 signs the path exactly as sent — `/a/./b` is a real key, not `/a/b`.
    /// Every other AWS service normalises first. The vector suite covers both.
    public let normalizePath: Bool

    public init(
        accessKeyID: String,
        secretAccessKey: String,
        region: String,
        service: String = "s3",
        normalizePath: Bool = false
    ) {
        self.accessKeyID = accessKeyID
        self.secretAccessKey = secretAccessKey
        self.region = region
        self.service = service
        self.normalizePath = normalizePath
    }

    public static let algorithm = "AWS4-HMAC-SHA256"
    public static let unsignedPayload = "UNSIGNED-PAYLOAD"

    // MARK: - Canonicalisation

    /// AWS's unreserved set: everything else is percent-encoded with uppercase hex.
    /// Deliberately *not* `addingPercentEncoding(withAllowedCharacters:)` — that
    /// treats already-present `%` as literal and emits lowercase hex on some paths.
    static func uriEncode(_ s: String, encodeSlash: Bool) -> String {
        var out = ""
        out.reserveCapacity(s.utf8.count)
        for byte in Array(s.utf8) {
            switch byte {
            case UInt8(ascii: "A")...UInt8(ascii: "Z"),
                 UInt8(ascii: "a")...UInt8(ascii: "z"),
                 UInt8(ascii: "0")...UInt8(ascii: "9"),
                 UInt8(ascii: "-"), UInt8(ascii: "_"),
                 UInt8(ascii: "."), UInt8(ascii: "~"):
                out.append(Character(UnicodeScalar(byte)))
            case UInt8(ascii: "/") where !encodeSlash:
                out.append("/")
            default:
                out += String(format: "%%%02X", byte)
            }
        }
        return out
    }

    /// Removes `.` and `..` segments and collapses duplicate slashes.
    /// Applied only when `normalizePath` is true — never for S3.
    static func normalize(path: String) -> String {
        guard !path.isEmpty else { return "/" }
        let trailingSlash = path.hasSuffix("/") && path != "/"
        var stack: [String] = []
        for segment in path.split(separator: "/", omittingEmptySubsequences: true) {
            switch segment {
            case ".": continue
            case "..": if !stack.isEmpty { stack.removeLast() }
            default: stack.append(String(segment))
            }
        }
        if stack.isEmpty { return "/" }
        return "/" + stack.joined(separator: "/") + (trailingSlash ? "/" : "")
    }

    func canonicalPath(_ rawPath: String) -> String {
        let path = rawPath.isEmpty ? "/" : rawPath
        return Self.uriEncode(normalizePath ? Self.normalize(path: path) : path,
                              encodeSlash: false)
    }

    /// Sorted by encoded name, then by encoded value. Values may repeat per name.
    static func canonicalQuery(_ items: [(name: String, value: String)]) -> String {
        var encoded: [(String, String)] = []
        encoded.reserveCapacity(items.count)
        for item in items {
            encoded.append((uriEncode(item.name, encodeSlash: true),
                            uriEncode(item.value, encodeSlash: true)))
        }
        encoded.sort { lhs, rhs in
            lhs.0 == rhs.0 ? lhs.1 < rhs.1 : lhs.0 < rhs.0
        }
        var parts: [String] = []
        parts.reserveCapacity(encoded.count)
        for (name, value) in encoded {
            parts.append(name + "=" + value)
        }
        return parts.joined(separator: "&")
    }

    /// Lowercased names, values trimmed and inner runs of whitespace collapsed.
    /// Repeated names are joined with `,` in the order they were sent.
    static func canonicalHeaders(_ headers: [(name: String, value: String)])
        -> (canonical: String, signed: String)
    {
        var grouped: [String: [String]] = [:]
        var order: [String] = []
        for (name, value) in headers {
            let key = name.lowercased()
            if grouped[key] == nil { order.append(key) }
            grouped[key, default: []].append(collapseWhitespace(value))
        }
        let names = order.sorted()
        let canonical = names
            .map { "\($0):\(grouped[$0]!.joined(separator: ","))\n" }
            .joined()
        return (canonical, names.joined(separator: ";"))
    }

    /// Trims outer whitespace and collapses inner runs to a single space.
    ///
    /// Applies inside double-quoted sections too — `"a   b   c"` signs as `"a b c"`.
    /// An earlier draft preserved quoted runs, which the `get-header-value-trim`
    /// vector rejects.
    static func collapseWhitespace(_ value: String) -> String {
        var out = ""
        var pendingSpace = false
        var started = false
        for ch in value {
            if ch == " " || ch == "\t" {
                if started { pendingSpace = true }
                continue
            }
            if pendingSpace { out.append(" "); pendingSpace = false }
            out.append(ch)
            started = true
        }
        return out
    }

    // MARK: - The three stages

    public func canonicalRequest(
        method: String,
        path: String,
        query: [(name: String, value: String)],
        headers: [(name: String, value: String)],
        payloadHash: String
    ) -> (text: String, signedHeaders: String) {
        let (canonicalHeaders, signedHeaders) = Self.canonicalHeaders(headers)
        let text = [
            method,
            canonicalPath(path),
            Self.canonicalQuery(query),
            canonicalHeaders,
            signedHeaders,
            payloadHash,
        ].joined(separator: "\n")
        return (text, signedHeaders)
    }

    public func stringToSign(canonicalRequest: String, date: Date) -> String {
        [
            Self.algorithm,
            Self.amzDate(date),
            credentialScope(date: date),
            Self.sha256Hex(Data(canonicalRequest.utf8)),
        ].joined(separator: "\n")
    }

    public func signature(stringToSign: String, date: Date) -> String {
        var key = SymmetricKey(data: Data("AWS4\(secretAccessKey)".utf8))
        for component in [Self.dateStamp(date), region, service, "aws4_request"] {
            key = SymmetricKey(data: Data(Self.hmac(component, key: key)))
        }
        return Self.hex(Self.hmac(stringToSign, key: key))
    }

    public func credentialScope(date: Date) -> String {
        "\(Self.dateStamp(date))/\(region)/\(service)/aws4_request"
    }

    // MARK: - Primitives

    static func hmac(_ message: String, key: SymmetricKey) -> [UInt8] {
        Array(HMAC<SHA256>.authenticationCode(for: Data(message.utf8), using: key))
    }

    static func hex(_ bytes: some Sequence<UInt8>) -> String {
        bytes.map { String(format: "%02x", $0) }.joined()
    }

    public static func sha256Hex(_ data: Data) -> String {
        hex(SHA256.hash(data: data))
    }

    /// SHA-256 of a file, read in chunks so a 200 MB video never lands in memory.
    public static func sha256Hex(fileAt url: URL) throws -> String {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        var hasher = SHA256()
        while let chunk = try handle.read(upToCount: 1 << 20), !chunk.isEmpty {
            hasher.update(data: chunk)
        }
        return hex(hasher.finalize())
    }

    static let amzDateFormatter: @Sendable () -> DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "yyyyMMdd'T'HHmmss'Z'"
        f.timeZone = TimeZone(identifier: "UTC")
        f.locale = Locale(identifier: "en_US_POSIX")
        return f
    }

    public static func amzDate(_ date: Date) -> String {
        amzDateFormatter().string(from: date)
    }

    public static func dateStamp(_ date: Date) -> String {
        String(amzDate(date).prefix(8))
    }
}
