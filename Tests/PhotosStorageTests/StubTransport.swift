import Foundation
@testable import PhotosStorage

/// A scripted transport, so the logic that surrounds the network — retry, error
/// mapping, LIST paging, key encoding — can be tested without one.
final class StubTransport: HTTPTransport, @unchecked Sendable {
    struct Exchange: Sendable {
        var response: HTTPResponse?
        var error: (any Error)?
    }

    private let lock = NSLock()
    private var queue: [Exchange]
    private(set) var recorded: [HTTPRequest] = []

    init(_ responses: [HTTPResponse]) {
        self.queue = responses.map { Exchange(response: $0, error: nil) }
    }

    init(exchanges: [Exchange]) {
        self.queue = exchanges
    }

    func send(_ request: HTTPRequest) async throws -> HTTPResponse {
        // `withLock` rather than lock()/unlock(): the latter is unavailable from
        // async contexts under strict concurrency.
        let next: Exchange? = lock.withLock {
            recorded.append(request)
            return queue.isEmpty ? nil : queue.removeFirst()
        }
        guard let next else {
            throw StorageError.malformedResponse("stub ran out of scripted responses")
        }
        if let error = next.error { throw error }
        return next.response!
    }

    var requests: [HTTPRequest] { lock.withLock { recorded } }
    var requestCount: Int { lock.withLock { recorded.count } }
}

extension HTTPRequest {
    func header(_ name: String) -> String? {
        headers.first { $0.name.lowercased() == name.lowercased() }?.value
    }
    var authorization: String? { header("Authorization") }
}

enum TestFixtures {
    static let storage = try! StorageURL("https://de-s3.storage.bunnycdn.com/my-photos")
    static let secret = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"

    static func client(_ transport: any HTTPTransport,
                       retry: RetryPolicy = .none,
                       payloadSigning: S3Client.PayloadSigning = .signed) -> S3Client {
        S3Client(storage: storage, secretAccessKey: secret,
                 transport: transport, retry: retry, payloadSigning: payloadSigning)
    }

    static func listXML(keys: [(String, Int, String)],
                        truncated: Bool = false,
                        nextToken: String? = nil,
                        encodingType: String? = "url") -> Data {
        var xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        xml += "<ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
        xml += "<Name>my-photos</Name>"
        if let encodingType { xml += "<EncodingType>\(encodingType)</EncodingType>" }
        xml += "<IsTruncated>\(truncated)</IsTruncated>"
        if let nextToken { xml += "<NextContinuationToken>\(nextToken)</NextContinuationToken>" }
        for (key, size, etag) in keys {
            xml += "<Contents><Key>\(key)</Key>"
            xml += "<LastModified>2026-01-15T10:30:00.000Z</LastModified>"
            xml += "<ETag>&quot;\(etag)&quot;</ETag>"
            xml += "<Size>\(size)</Size></Contents>"
        }
        xml += "</ListBucketResult>"
        return Data(xml.utf8)
    }
}
