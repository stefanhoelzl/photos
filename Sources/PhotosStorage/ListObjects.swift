import Foundation
#if canImport(FoundationXML)
import FoundationXML
#endif

extension S3Client {

    /// Lists every object under a prefix, paging internally.
    ///
    /// §4's whole sync mechanism is one LIST on `meta/`: the response carries the
    /// ETag and size of every shard, so that single request *is* the sync plan.
    ///
    /// Keys are reported **raw**. bunny.net returns zero-byte directory markers
    /// ending in `/`, and §4 puts the responsibility for skipping them in the sync
    /// diff, not here — see `S3Object.isDirectoryMarker`.
    public func list(prefix: String = "", maxKeysPerPage: Int = 1000) -> S3ListSequence {
        S3ListSequence(client: self, prefix: prefix, maxKeysPerPage: maxKeysPerPage)
    }

    /// One page of a LIST.
    func listPage(prefix: String, continuationToken: String?,
                  maxKeys: Int) async throws -> ListObjectsPage {
        var query: [(name: String, value: String)] = [
            ("list-type", "2"),
            // LIST responses are XML, and C1 control characters are illegal in XML.
            // A single such key would corrupt the parse — and LIST *is* the sync
            // mechanism — so the server percent-encodes keys for us (§2).
            ("encoding-type", "url"),
            ("max-keys", String(maxKeys)),
        ]
        if !prefix.isEmpty {
            query.append(("prefix", prefix.precomposedStringWithCanonicalMapping))
        }
        if let continuationToken {
            query.append(("continuation-token", continuationToken))
        }

        let request = try signedRequest(
            method: "GET", path: "/\(storage.zone)", query: query, body: .empty
        )
        let response = try await send(request, expecting: [200])
        return try ListObjectsParser.parse(response.body)
    }
}

struct ListObjectsPage: Sendable {
    var objects: [S3Object]
    var nextContinuationToken: String?
    var isTruncated: Bool
}

/// An `AsyncSequence` over every object under a prefix.
///
/// Callers write `for try await object in client.list(prefix:)` and never see a
/// continuation token. A full-bucket scan for `--prune` streams rather than
/// materialising ~140 000 keys at once; `collect()` covers the small `meta/` case.
public struct S3ListSequence: AsyncSequence, Sendable {
    public typealias Element = S3Object

    let client: S3Client
    let prefix: String
    let maxKeysPerPage: Int

    public struct AsyncIterator: AsyncIteratorProtocol {
        let client: S3Client
        let prefix: String
        let maxKeysPerPage: Int
        var buffer: [S3Object] = []
        var index = 0
        var continuationToken: String?
        var exhausted = false

        public mutating func next() async throws -> S3Object? {
            while index >= buffer.count {
                if exhausted { return nil }
                let page = try await client.listPage(
                    prefix: prefix,
                    continuationToken: continuationToken,
                    maxKeys: maxKeysPerPage
                )
                buffer = page.objects
                index = 0
                continuationToken = page.nextContinuationToken
                exhausted = !page.isTruncated || page.nextContinuationToken == nil
                if buffer.isEmpty && exhausted { return nil }
            }
            defer { index += 1 }
            return buffer[index]
        }
    }

    public func makeAsyncIterator() -> AsyncIterator {
        AsyncIterator(client: client, prefix: prefix, maxKeysPerPage: maxKeysPerPage)
    }

    /// Drains the sequence into an array. Fine for `meta/` (a few hundred shards);
    /// avoid it for a full-bucket scan.
    public func collect() async throws -> [S3Object] {
        var result: [S3Object] = []
        for try await object in self { result.append(object) }
        return result
    }
}

/// Parses a `ListObjectsV2` response.
///
/// Foundation's `XMLParser` rather than a hand-rolled scanner: entity escapes and
/// odd codepoints land on exactly the data path §2 flags as fragile, and this is a
/// real XML parser. On Linux it lives in `FoundationXML`, not `Foundation`.
enum ListObjectsParser {
    static func parse(_ data: Data) throws -> ListObjectsPage {
        final class Delegate: NSObject, XMLParserDelegate {
            var objects: [S3Object] = []
            var isTruncated = false
            var nextToken: String?
            var encodingType: String?

            var inContents = false
            var element = ""
            var buffer = ""
            var key = ""
            var size: Int64 = 0
            var etag: ETag?
            var lastModified: Date?

            // Instance-scoped: ISO8601DateFormatter is not Sendable, and one
            // delegate belongs to one parse.
            let iso8601: ISO8601DateFormatter = {
                let f = ISO8601DateFormatter()
                f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
                return f
            }()
            let iso8601NoFraction: ISO8601DateFormatter = {
                let f = ISO8601DateFormatter()
                f.formatOptions = [.withInternetDateTime]
                return f
            }()

            func parser(_ p: XMLParser, didStartElement e: String, namespaceURI: String?,
                        qualifiedName: String?, attributes: [String: String]) {
                element = e
                buffer = ""
                if e == "Contents" {
                    inContents = true
                    key = ""; size = 0; etag = nil; lastModified = nil
                }
            }

            func parser(_ p: XMLParser, foundCharacters s: String) { buffer += s }

            func parser(_ p: XMLParser, didEndElement e: String, namespaceURI: String?,
                        qualifiedName: String?) {
                defer { buffer = "" }
                if e == "Contents" {
                    inContents = false
                    objects.append(S3Object(key: key, size: size,
                                            etag: etag, lastModified: lastModified))
                    return
                }
                if inContents {
                    switch e {
                    case "Key": key = buffer
                    case "Size": size = Int64(buffer.trimmingCharacters(in: .whitespaces)) ?? 0
                    case "ETag": etag = ETag(header: buffer)
                    case "LastModified":
                        lastModified = iso8601.date(from: buffer)
                            ?? iso8601NoFraction.date(from: buffer)
                    default: break
                    }
                } else {
                    switch e {
                    case "IsTruncated":
                        isTruncated = buffer.trimmingCharacters(in: .whitespaces) == "true"
                    case "NextContinuationToken":
                        nextToken = buffer
                    case "EncodingType":
                        encodingType = buffer.trimmingCharacters(in: .whitespaces)
                    default: break
                    }
                }
            }
        }

        let delegate = Delegate()
        let parser = XMLParser(data: data)
        parser.delegate = delegate
        guard parser.parse() else {
            let reason = parser.parserError.map { "\($0)" } ?? "unknown XML error"
            throw StorageError.malformedResponse("could not parse the LIST response: \(reason)")
        }

        // We always ask for encoding-type=url, so keys arrive percent-encoded and
        // must be decoded exactly once. A server that ignored the parameter would
        // otherwise have its literal '%' sequences mangled — hence the echo check.
        let decode = delegate.encodingType?.lowercased() == "url"
        let objects = delegate.objects.map { object in
            guard decode else { return object }
            let decoded = object.key.removingPercentEncoding ?? object.key
            return S3Object(key: decoded, size: object.size,
                            etag: object.etag, lastModified: object.lastModified)
        }
        let nextToken = delegate.nextToken.flatMap {
            decode ? ($0.removingPercentEncoding ?? $0) : $0
        }

        return ListObjectsPage(objects: objects,
                               nextContinuationToken: nextToken,
                               isTruncated: delegate.isTruncated)
    }
}
