import Foundation
import PhotosStorage

/// An in-process storage zone: PUT, GET, HEAD, DELETE, LIST, and `If-Match`.
///
/// The wire itself is not what these tests are about. A's own round-trip suite already
/// drives the real client against adobe/S3Mock — real HTTP, real XML, real LIST paging, real
/// 412s — and §10's table records the same operations verified against the live bunny.net
/// zone. What D needs proved is its own state machine: that a second run is a no-op, that a
/// deleted file takes its blobs with it, that the sweep spares a blob that is too young.
/// Those want a zone whose clock and contents a test can state exactly, which is this.
final class FakeZone: HTTPTransport, @unchecked Sendable {

    struct Object {
        var data: Data
        var etag: String
        var lastModified: Date
    }

    private let lock = NSLock()
    private var objects: [String: Object] = [:]
    private var counter = 0

    private(set) var putCount = 0
    private(set) var deleteCount = 0
    private(set) var listCount = 0

    var keys: [String] { lock.withLock { objects.keys.sorted() } }
    func contains(_ key: String) -> Bool { lock.withLock { objects[key] != nil } }
    func data(_ key: String) -> Data? { lock.withLock { objects[key]?.data } }

    /// Backdates an object, so the sweep's age floor can be tested without waiting a week.
    func age(_ key: String, by interval: TimeInterval) {
        lock.withLock { objects[key]?.lastModified = Date().addingTimeInterval(-interval) }
    }

    func insert(_ key: String, _ data: Data, age: TimeInterval = 0) {
        lock.withLock {
            counter += 1
            objects[key] = Object(data: data, etag: "e\(counter)",
                                  lastModified: Date().addingTimeInterval(-age))
        }
    }

    func send(_ request: HTTPRequest) async throws -> HTTPResponse {
        let components = URLComponents(url: request.url, resolvingAgainstBaseURL: false)
        if components?.queryItems?.contains(where: { $0.name == "list-type" }) ?? false {
            let prefix = components?.queryItems?.first { $0.name == "prefix" }?.value ?? ""
            let decodedPrefix = prefix.removingPercentEncoding ?? prefix
            return lock.withLock {
                listCount += 1
                let matching = objects.filter { $0.key.hasPrefix(decodedPrefix) }
                    .sorted { $0.key < $1.key }
                return HTTPResponse(status: 200, headers: [:], body: Self.listXML(matching))
            }
        }

        let path = request.url.path
        let raw = String(path.split(separator: "/", maxSplits: 1,
                                    omittingEmptySubsequences: true).dropFirst().first ?? "")
        let key = raw.removingPercentEncoding ?? raw

        switch request.method {
        case "PUT":
            return lock.withLock {
                putCount += 1
                let ifMatch = request.headers.first { $0.name.lowercased() == "if-match" }?.value
                if let ifMatch {
                    let wanted = ifMatch.trimmingCharacters(in: CharacterSet(charactersIn: "\"W/"))
                    guard let existing = objects[key], existing.etag == wanted else {
                        return HTTPResponse(status: 412, headers: [:], body: Data())
                    }
                }
                counter += 1
                let body: Data
                switch request.body {
                case .data(let d): body = d
                case .file(let url): body = (try? Data(contentsOf: url)) ?? Data()
                case .empty: body = Data()
                }
                objects[key] = Object(data: body, etag: "e\(counter)", lastModified: Date())
                return HTTPResponse(status: 200, headers: ["etag": "\"e\(counter)\""], body: Data())
            }

        case "DELETE":
            return lock.withLock {
                deleteCount += 1
                objects.removeValue(forKey: key)
                return HTTPResponse(status: 204, headers: [:], body: Data())
            }

        case "HEAD", "GET":
            let object = lock.withLock { objects[key] }
            guard let object else {
                return HTTPResponse(status: 404, headers: [:], body: Data(
                    "<Error><Code>NoSuchKey</Code><Message>not here</Message></Error>".utf8))
            }
            let headers = ["etag": "\"\(object.etag)\"",
                           "content-length": String(object.data.count)]
            if request.method == "HEAD" {
                return HTTPResponse(status: 200, headers: headers, body: Data())
            }
            if let destination = request.downloadTo {
                try object.data.write(to: destination)
                return HTTPResponse(status: 200, headers: headers, body: Data())
            }
            return HTTPResponse(status: 200, headers: headers, body: object.data)

        default:
            return HTTPResponse(status: 405, headers: [:], body: Data())
        }
    }

    private static func listXML(_ objects: [(key: String, value: Object)]) -> Data {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        var xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        xml += "<ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
        xml += "<Name>my-photos</Name><EncodingType>url</EncodingType>"
        xml += "<IsTruncated>false</IsTruncated>"
        for (key, object) in objects {
            let encoded = key.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? key
            xml += "<Contents><Key>\(encoded)</Key>"
            xml += "<LastModified>\(formatter.string(from: object.lastModified))</LastModified>"
            xml += "<ETag>&quot;\(object.etag)&quot;</ETag>"
            xml += "<Size>\(object.data.count)</Size></Contents>"
        }
        xml += "</ListBucketResult>"
        return Data(xml.utf8)
    }

    func client() throws -> S3Client {
        S3Client(storage: try StorageURL("https://de-s3.storage.bunnycdn.com/my-photos"),
                 secretAccessKey: "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY",
                 transport: self, retry: .none)
    }
}
