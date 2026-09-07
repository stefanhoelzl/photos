import Foundation
#if canImport(FoundationXML)
import FoundationXML
#endif

/// An HTTP failure from the storage API.
///
/// §1 requires that sync and upload failures report the HTTP status and name the
/// cause — *"Sync failed: 403 Forbidden — log out and check the password"* — never
/// an opaque error. `description` composes that sentence in one place so the wording
/// cannot drift between the CLI and the app.
public struct S3HTTPError: Error, Hashable, Sendable, CustomStringConvertible {
    public let status: Int
    /// The `<Code>` element from S3's XML error body, when there is one.
    public let code: String?
    public let message: String?
    public let key: String?
    public let requestID: String?

    public init(status: Int, code: String? = nil, message: String? = nil,
                key: String? = nil, requestID: String? = nil) {
        self.status = status
        self.code = code
        self.message = message
        self.key = key
        self.requestID = requestID
    }

    public var description: String {
        var s = "\(status) \(Self.reasonPhrase(status))"
        if let code { s += " — \(code)" }
        if let message, message != code { s += ": \(message)" }
        if let key { s += " (\(key))" }
        return s
    }

    /// Actionable one-liner for the UI, following §1's example wording.
    public var userMessage: String {
        switch status {
        case 401, 403:
            "\(description) — log out and check the password"
        case 404:
            "\(description) — the object is missing from the storage zone"
        case 412:
            "\(description) — the object changed since it was read"
        case 429:
            "\(description) — too many requests, try again shortly"
        case 500...599:
            "\(description) — the storage service is failing, try again later"
        default:
            description
        }
    }

    static func reasonPhrase(_ status: Int) -> String {
        switch status {
        case 400: "Bad Request"
        case 401: "Unauthorized"
        case 403: "Forbidden"
        case 404: "Not Found"
        case 405: "Method Not Allowed"
        case 409: "Conflict"
        case 412: "Precondition Failed"
        case 416: "Range Not Satisfiable"
        case 429: "Too Many Requests"
        case 500: "Internal Server Error"
        case 501: "Not Implemented"
        case 502: "Bad Gateway"
        case 503: "Service Unavailable"
        case 504: "Gateway Timeout"
        default: "HTTP \(status)"
        }
    }

    /// Parses S3's XML error body. Best-effort: a body that is missing, truncated
    /// or not XML at all still yields an error carrying the status.
    public static func parse(status: Int, body: Data, key: String?, requestID: String?) -> S3HTTPError {
        guard !body.isEmpty else {
            return S3HTTPError(status: status, key: key, requestID: requestID)
        }
        let fields = XMLFieldCollector.collect(body, elements: ["Code", "Message", "Key", "RequestId"])
        return S3HTTPError(
            status: status,
            code: fields["Code"],
            message: fields["Message"],
            key: fields["Key"] ?? key,
            requestID: fields["RequestId"] ?? requestID
        )
    }
}

/// Everything that can go wrong below the catalog.
public enum StorageError: Error, CustomStringConvertible {
    case http(S3HTTPError)
    /// The request never produced a response — DNS, TLS, a dropped connection.
    case transport(any Error)
    /// A 2xx response whose body could not be understood.
    case malformedResponse(String)

    public var description: String {
        switch self {
        case .http(let e): e.description
        case .transport(let e): "transport failure: \(e)"
        case .malformedResponse(let why): "malformed response: \(why)"
        }
    }

    /// The HTTP status, when the failure had one.
    public var status: Int? {
        if case .http(let e) = self { return e.status }
        return nil
    }
}

/// Pulls the first occurrence of each named element out of a small XML document.
/// Used only for error bodies, where the document is a handful of elements.
enum XMLFieldCollector {
    static func collect(_ data: Data, elements: Set<String>) -> [String: String] {
        final class Delegate: NSObject, XMLParserDelegate {
            let wanted: Set<String>
            var found: [String: String] = [:]
            var current: String?
            var buffer = ""
            init(wanted: Set<String>) { self.wanted = wanted }

            func parser(_ p: XMLParser, didStartElement e: String, namespaceURI: String?,
                        qualifiedName: String?, attributes: [String: String]) {
                current = wanted.contains(e) && found[e] == nil ? e : nil
                buffer = ""
            }
            func parser(_ p: XMLParser, foundCharacters s: String) {
                if current != nil { buffer += s }
            }
            func parser(_ p: XMLParser, didEndElement e: String, namespaceURI: String?,
                        qualifiedName: String?) {
                if let current, current == e { found[current] = buffer }
                current = nil
                buffer = ""
            }
        }
        let delegate = Delegate(wanted: elements)
        let parser = XMLParser(data: data)
        parser.delegate = delegate
        _ = parser.parse()
        return delegate.found
    }
}
