import Foundation
import Testing
@testable import PhotosStorage

/// Round-trips against a local S3 server: real HTTP through the URLSession
/// transport, real XML from a real implementation, real paging, real multipart.
///
/// What these do *not* prove is that the signature is correct — S3Mock accepts any
/// signature. That is the vector suite's job.
@Suite(.serialized)
struct RoundTripTests {

    func uniqueKey(_ suffix: String) -> String {
        "itest/\(UUID().uuidString)/\(suffix)"
    }

    @Test("put, head, get, delete")
    func basicLifecycle() async throws {
        let client = try requireS3Mock().client()
        let key = uniqueKey("meta/album.db")
        let payload = Data("shard contents".utf8)

        let put = try await client.put(key, body: .data(payload))
        let etag = try #require(put.etag)

        let head = try #require(try await client.head(key))
        #expect(head.size == Int64(payload.count))
        #expect(head.etag == etag)

        let got = try await client.get(key)
        #expect(got.data == payload)

        try await client.delete(key)
        #expect(try await client.head(key) == nil)
    }

    @Test("a key with umlauts round-trips through PUT, GET and LIST")
    func unicodeKeys() async throws {
        let client = try requireS3Mock().client()
        let prefix = "itest/\(UUID().uuidString)/"
        // NFD on the way in — what iOS would hand us.
        let key = prefix + "meta/Gru\u{0308}n Reise.db"
        let payload = Data("ü".utf8)

        _ = try await client.put(key, body: .data(payload))

        // NFC on the way out: the same object, addressed the composed way.
        let composed = prefix + "meta/Grün Reise.db"
        #expect(try await client.get(composed).data == payload)

        let listed = try await client.list(prefix: prefix).collect()
        #expect(listed.map(\.key) == [composed.precomposedStringWithCanonicalMapping])
    }

    @Test("conditional GET returns 304 when the ETag is current")
    func conditionalGet() async throws {
        let client = try requireS3Mock().client()
        let key = uniqueKey("meta/cond.db")
        let put = try await client.put(key, body: .data(Data("v1".utf8)))
        let etag = try #require(put.etag)

        let unchanged = try await client.get(key, ifNoneMatch: etag)
        guard case .notModified = unchanged else {
            Issue.record("expected .notModified, got \(unchanged)")
            return
        }

        _ = try await client.put(key, body: .data(Data("v2".utf8)))
        let changed = try await client.get(key, ifNoneMatch: etag)
        #expect(changed.data == Data("v2".utf8))
    }

    /// The guard behind §2's single-owner shard rule.
    @Test("If-Match on PUT: current ETag writes, stale ETag yields staleETag")
    func conditionalPut() async throws {
        let client = try requireS3Mock().client()
        let key = uniqueKey("meta/guarded.db")

        let first = try await client.put(key, body: .data(Data("v1".utf8)))
        let v1 = try #require(first.etag)

        let second = try await client.put(key, body: .data(Data("v2".utf8)), ifMatch: v1)
        guard case .written(let v2) = second else {
            Issue.record("expected .written, got \(second)")
            return
        }

        // Now write with the ETag we read *before* that update — the "someone else
        // wrote it" case. Must come back as a value, not an error.
        let stale = try await client.put(key, body: .data(Data("v3".utf8)), ifMatch: v1)
        guard case .staleETag = stale else {
            Issue.record("expected .staleETag, got \(stale)")
            return
        }
        // ...and the object must be untouched.
        #expect(try await client.get(key).data == Data("v2".utf8))
        #expect(v2 != v1)
    }

    @Test("range GET returns exactly the requested bytes")
    func rangeGet() async throws {
        let client = try requireS3Mock().client()
        let key = uniqueKey("preview/ranged.bin")
        let payload = Data((0..<4096).map { UInt8($0 % 251) })
        _ = try await client.put(key, body: .data(payload))

        let middle = try await client.get(key, range: 1000..<1100)
        #expect(middle.data == payload[1000..<1100])

        let head = try await client.get(key, range: 0..<16)
        #expect(head.data == payload[0..<16])
    }

    @Test("download streams an object to a file")
    func downloadToFile() async throws {
        let client = try requireS3Mock().client()
        let key = uniqueKey("originals/photo.heic")
        let payload = Data((0..<200_000).map { UInt8($0 % 256) })
        _ = try await client.put(key, body: .data(payload))

        let destination = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: destination) }

        _ = try await client.download(key, to: destination)
        #expect(try Data(contentsOf: destination) == payload)
    }

    @Test("upload from a file, as §8's background session requires")
    func uploadFromFile() async throws {
        let client = try requireS3Mock().client()
        let source = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: source) }
        let payload = Data((0..<100_000).map { UInt8($0 % 256) })
        try payload.write(to: source)

        let key = uniqueKey("originals/from-file.bin")
        _ = try await client.put(key, body: .file(source))
        #expect(try await client.get(key).data == payload)
    }

    @Test("404 on a missing object is nil from HEAD and an error from GET")
    func missingObject() async throws {
        let client = try requireS3Mock().client()
        let key = uniqueKey("meta/never-written.db")
        #expect(try await client.head(key) == nil)
        await #expect(throws: StorageError.self) { _ = try await client.get(key) }
    }

    @Test("a real 404 body carries the status through to the message")
    func errorMessageFromRealServer() async throws {
        let client = try requireS3Mock().client()
        do {
            _ = try await client.get(uniqueKey("meta/absent.db"))
            Issue.record("expected a failure")
        } catch let error as StorageError {
            guard case .http(let http) = error else {
                Issue.record("expected .http, got \(error)")
                return
            }
            #expect(http.status == 404)
            #expect(http.description.hasPrefix("404 Not Found"))
        }
    }
}

@Suite(.serialized)
struct RoundTripListTests {

    /// The behaviour §4's whole sync algorithm rests on.
    @Test("LIST returns every shard with its ETag and size in one request")
    func listIsTheSyncPlan() async throws {
        let client = try requireS3Mock().client()
        let prefix = "itest/\(UUID().uuidString)/meta/"
        let albums = ["Iceland", "Trips/Norway", "Trips/Sweden", "Grün"]
        for album in albums {
            _ = try await client.put(prefix + album + ".db",
                                     body: .data(Data("shard for \(album)".utf8)))
        }

        let objects = try await client.list(prefix: prefix).collect()
        let keys = Set(objects.map(\.key))
        for album in albums {
            let key = (prefix + album + ".db").precomposedStringWithCanonicalMapping
            #expect(keys.contains(key), "missing \(key)")
        }
        // Nested keys come back flat, because we send no delimiter (§4).
        #expect(objects.allSatisfy { $0.etag != nil })
        #expect(objects.allSatisfy { $0.size > 0 })
    }

    @Test("paging is transparent across more objects than fit in a page")
    func pagesAcrossRealPages() async throws {
        let client = try requireS3Mock().client()
        let prefix = "itest/\(UUID().uuidString)/paged/"
        let count = 25
        for i in 0..<count {
            _ = try await client.put(prefix + String(format: "%03d.db", i),
                                     body: .data(Data("x".utf8)))
        }

        // Force several round-trips rather than uploading 1000+ objects.
        let objects = try await client.list(prefix: prefix, maxKeysPerPage: 7).collect()
        #expect(objects.count == count)
        #expect(objects.map(\.key) == objects.map(\.key).sorted())
    }

    @Test("an empty prefix lists nothing rather than failing")
    func emptyPrefix() async throws {
        let client = try requireS3Mock().client()
        let objects = try await client.list(prefix: "itest/\(UUID().uuidString)/nothing/").collect()
        #expect(objects.isEmpty)
    }
}

@Suite(.serialized)
struct RoundTripMultipartTests {

    @Test("a file above the threshold uploads in parts and reassembles byte-exact")
    func multipartUpload() async throws {
        // Threshold lowered so the test moves 12 MB rather than 64. The part size
        // cannot go below S3's 5 MB minimum, so this is three parts.
        let client = try requireS3Mock().client(
            multipartThreshold: 1 << 20,                  // 1 MB
            multipartPartSize: S3Client.minimumPartSize   // 5 MB -> 3 parts
        )
        let source = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: source) }

        let byteCount = 12 * 1024 * 1024
        var bytes = [UInt8](repeating: 0, count: byteCount)
        for i in 0..<byteCount { bytes[i] = UInt8((i &* 31) % 256) }
        let payload = Data(bytes)
        try payload.write(to: source)

        let key = "itest/\(UUID().uuidString)/video/clip.mp4"
        let progressed = Mutex(0 as Int64)
        let result = try await client.put(key, body: .file(source),
                                          contentType: "video/mp4") { sent, _ in
            progressed.withLock { $0 = max($0, sent) }
        }

        guard case .written = result else {
            Issue.record("expected .written, got \(result)")
            return
        }
        #expect(try await client.head(key)?.size == Int64(payload.count))
        #expect(try await client.get(key).data == payload)
        #expect(progressed.withLock { $0 } == Int64(payload.count))
    }

    @Test("a file below the threshold takes the single-PUT path")
    func belowThresholdIsSinglePut() async throws {
        let client = try requireS3Mock().client(multipartThreshold: 10 << 20)
        let source = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: source) }
        let payload = Data(repeating: 0x41, count: 1 << 20)
        try payload.write(to: source)

        let key = "itest/\(UUID().uuidString)/small.bin"
        _ = try await client.put(key, body: .file(source))
        let etag = try #require(try await client.head(key)?.etag)
        // A single-part ETag has no '-<partCount>' suffix.
        #expect(!etag.value.contains("-"))
    }
}

/// A minimal mutex, so the progress callback can be observed from a `@Sendable`
/// closure without pulling in a dependency.
final class Mutex<Value>: @unchecked Sendable {
    private let lock = NSLock()
    private var value: Value
    init(_ value: Value) { self.value = value }
    func withLock<T>(_ body: (inout Value) -> T) -> T {
        lock.lock(); defer { lock.unlock() }
        return body(&value)
    }
}
