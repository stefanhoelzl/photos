import Foundation
import PhotosStorage

/// A transport that answers by *what was asked for*, not by call order.
///
/// A's own `StubTransport` is a scripted queue, which suits testing one request's headers.
/// The sync loop issues a LIST and then a GET per changed shard, in an order that is the
/// loop's business rather than a test's — so this one is keyed by path instead, and B's
/// tests never encode an ordering they do not care about.
///
/// A separate file rather than a shared test-support target: the two stubs answer different
/// questions, and sharing would mean generalising both into something neither wants.
final class KeyedTransport: HTTPTransport, @unchecked Sendable {

    /// Objects the fake zone holds, by key.
    private let lock = NSLock()
    private var objects: [String: (data: Data, etag: String)] = [:]
    private(set) var requestedKeys: [String] = []
    private(set) var listCount = 0

    init(_ objects: [String: (data: Data, etag: String)] = [:]) {
        self.objects = objects
    }

    func put(_ key: String, _ data: Data, etag: String) {
        lock.withLock { objects[key] = (data, etag) }
    }

    func remove(_ key: String) {
        _ = lock.withLock { objects.removeValue(forKey: key) }
    }

    var keys: [String] { lock.withLock { Array(objects.keys) } }

    func send(_ request: HTTPRequest) async throws -> HTTPResponse {
        let components = URLComponents(url: request.url, resolvingAgainstBaseURL: false)
        let isList = components?.queryItems?.contains { $0.name == "list-type" } ?? false

        if isList {
            let prefix = components?.queryItems?.first { $0.name == "prefix" }?.value ?? ""
            return lock.withLock {
                listCount += 1
                let matching = objects
                    .filter { $0.key.hasPrefix(prefix) }
                    .sorted { $0.key < $1.key }
                return HTTPResponse(status: 200, headers: [:], body: Self.listXML(matching))
            }
        }

        // Path is /<zone>/<key>; the zone is the first segment.
        let path = request.url.path
        let key = String(path.split(separator: "/", maxSplits: 1, omittingEmptySubsequences: true)
            .dropFirst().first ?? "")
        let decoded = key.removingPercentEncoding ?? key

        return lock.withLock {
            requestedKeys.append(decoded)
            guard let object = objects[decoded] else {
                return HTTPResponse(status: 404, headers: [:], body: Data(
                    "<Error><Code>NoSuchKey</Code><Message>not here</Message></Error>".utf8
                ))
            }
            return HTTPResponse(
                status: 200, headers: ["etag": "\"\(object.etag)\""], body: object.data
            )
        }
    }

    private static func listXML(_ objects: [(key: String, value: (data: Data, etag: String))]) -> Data {
        var xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        xml += "<ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
        xml += "<Name>my-photos</Name><EncodingType>url</EncodingType><IsTruncated>false</IsTruncated>"
        for (key, value) in objects {
            let encoded = key.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? key
            xml += "<Contents><Key>\(encoded)</Key>"
            xml += "<LastModified>2026-01-15T10:30:00.000Z</LastModified>"
            // bunny.net's directory markers come back with no ETag and Size 0 (§2).
            if !key.hasSuffix("/") { xml += "<ETag>&quot;\(value.etag)&quot;</ETag>" }
            xml += "<Size>\(value.data.count)</Size></Contents>"
        }
        xml += "</ListBucketResult>"
        return Data(xml.utf8)
    }
}

enum SyncFixtures {
    static let storage = try! StorageURL("https://de-s3.storage.bunnycdn.com/my-photos")

    static func client(_ transport: KeyedTransport) -> S3Client {
        S3Client(
            storage: storage,
            secretAccessKey: "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY",
            transport: transport,
            retry: .none
        )
    }
}
