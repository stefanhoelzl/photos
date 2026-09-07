import Foundation
import Testing
@testable import PhotosStorage

/// §4 calls one LIST on `meta/` the entire sync plan, and §2 warns that a single
/// bad key could corrupt the parse. These are the tests for that claim.
struct ListObjectsTests {

    @Test("a single page yields every object with its etag and size")
    func singlePage() async throws {
        let stub = StubTransport([HTTPResponse(status: 200, body: TestFixtures.listXML(keys: [
            ("meta/Iceland.db", 32768, "abc"),
            ("meta/Trips/Norway.db", 88000, "def"),
        ]))])
        let objects = try await TestFixtures.client(stub).list(prefix: "meta/").collect()

        #expect(objects.count == 2)
        #expect(objects[0].key == "meta/Iceland.db")
        #expect(objects[0].size == 32768)
        #expect(objects[0].etag == ETag(unquoted: "abc"))
        #expect(objects[1].key == "meta/Trips/Norway.db")
    }

    @Test("the request asks for v2, url encoding and the prefix")
    func requestShape() async throws {
        let stub = StubTransport([HTTPResponse(status: 200, body: TestFixtures.listXML(keys: []))])
        _ = try await TestFixtures.client(stub).list(prefix: "meta/").collect()

        let url = try #require(stub.requests.first).url.absoluteString
        #expect(url.contains("list-type=2"))
        #expect(url.contains("encoding-type=url"))
        #expect(url.contains("prefix=meta%2F"))
        // LIST addresses the zone itself, not a key under it.
        #expect(url.hasPrefix("https://de-s3.storage.bunnycdn.com/my-photos?"))
    }

    @Test("paging is internal — the caller never sees a continuation token")
    func pagesTransparently() async throws {
        let stub = StubTransport([
            HTTPResponse(status: 200, body: TestFixtures.listXML(
                keys: [("meta/a.db", 1, "a"), ("meta/b.db", 2, "b")],
                truncated: true, nextToken: "TOKEN1")),
            HTTPResponse(status: 200, body: TestFixtures.listXML(
                keys: [("meta/c.db", 3, "c")],
                truncated: false)),
        ])
        let objects = try await TestFixtures.client(stub).list(prefix: "meta/").collect()

        #expect(objects.map(\.key) == ["meta/a.db", "meta/b.db", "meta/c.db"])
        #expect(stub.requestCount == 2)
        let second = try #require(stub.requests.last)
        #expect(second.url.absoluteString.contains("continuation-token=TOKEN1"))
    }

    @Test("a truncated page with no token stops rather than looping forever")
    func stopsWithoutToken() async throws {
        let stub = StubTransport([HTTPResponse(status: 200, body: TestFixtures.listXML(
            keys: [("meta/a.db", 1, "a")], truncated: true, nextToken: nil))])
        let objects = try await TestFixtures.client(stub).list().collect()
        #expect(objects.count == 1)
        #expect(stub.requestCount == 1)
    }

    @Test("percent-encoded keys are decoded exactly once")
    func decodesUrlEncoding() async throws {
        // What the server returns for `meta/Grün Reise.db` under encoding-type=url.
        let stub = StubTransport([HTTPResponse(status: 200, body: TestFixtures.listXML(
            keys: [("meta/Gr%C3%BCn%20Reise.db", 100, "x")]))])
        let objects = try await TestFixtures.client(stub).list().collect()
        #expect(objects.first?.key == "meta/Grün Reise.db")
    }

    @Test("a server that ignores encoding-type leaves keys untouched")
    func honoursEchoedEncodingType() async throws {
        // Without the echo check, a literal '%20' in a key would be mangled.
        let stub = StubTransport([HTTPResponse(status: 200, body: TestFixtures.listXML(
            keys: [("meta/100%20percent.db", 100, "x")], encodingType: nil))])
        let objects = try await TestFixtures.client(stub).list().collect()
        #expect(objects.first?.key == "meta/100%20percent.db")
    }

    @Test("XML entities in a key survive the parse")
    func entityDecoding() async throws {
        let xml = Data("""
        <ListBucketResult><EncodingType>url</EncodingType><IsTruncated>false</IsTruncated>\
        <Contents><Key>meta/Tom%20&amp;%20Jerry.db</Key><Size>10</Size>\
        <ETag>&quot;e1&quot;</ETag></Contents></ListBucketResult>
        """.utf8)
        let stub = StubTransport([HTTPResponse(status: 200, body: xml)])
        let objects = try await TestFixtures.client(stub).list().collect()
        #expect(objects.first?.key == "meta/Tom & Jerry.db")
        #expect(objects.first?.etag == ETag(unquoted: "e1"))
    }

    @Test("directory markers are reported raw, for §4's diff to skip")
    func reportsDirectoryMarkersRaw() async throws {
        // bunny.net materialises `meta/Trips/` as a zero-byte key with no ETag when
        // `meta/Trips/Iceland.db` is written (§2). The client does not filter it.
        let xml = Data("""
        <ListBucketResult><EncodingType>url</EncodingType><IsTruncated>false</IsTruncated>\
        <Contents><Key>meta/Trips/</Key><Size>0</Size></Contents>\
        <Contents><Key>meta/Trips/Iceland.db</Key><Size>32768</Size>\
        <ETag>&quot;abc&quot;</ETag></Contents></ListBucketResult>
        """.utf8)
        let stub = StubTransport([HTTPResponse(status: 200, body: xml)])
        let objects = try await TestFixtures.client(stub).list(prefix: "meta/").collect()

        #expect(objects.count == 2)
        #expect(objects[0].isDirectoryMarker)
        #expect(objects[0].etag == nil)
        #expect(!objects[1].isDirectoryMarker)
        // What §4's sync diff is expected to do with them.
        #expect(objects.filter { !$0.isDirectoryMarker }.map(\.key) == ["meta/Trips/Iceland.db"])
    }

    /// The most dangerous failure mode in the whole design.
    ///
    /// swift-corelibs-foundation's `XMLParser` returns **true** for a truncated
    /// document, only setting `parserError` — verified directly against the Linux
    /// toolchain. So a connection dropped mid-LIST parses as *zero objects*, and
    /// §4's diff treats a missing key as a deleted album. Without these checks a
    /// short read would drop the entire catalog.
    @Test("a truncated LIST response throws rather than reading as an empty bucket",
          arguments: [
            "<ListBucket",
            "<ListBucketResult><Contents><Key>meta/a.db</Key></Contents>",
            "<ListBucketResult><IsTruncated>false</IsTruncated>",
            "",
            "not xml at all",
          ])
    func truncatedResponseIsNeverAnEmptyBucket(body: String) async throws {
        let stub = StubTransport([HTTPResponse(status: 200, body: Data(body.utf8))])
        await #expect(throws: StorageError.self) {
            _ = try await TestFixtures.client(stub).list().collect()
        }
    }

    @Test("a response cut off after some objects throws, rather than reporting a short list")
    func partialResponseIsNotAShortList() async throws {
        var xml = String(data: TestFixtures.listXML(keys: [
            ("meta/a.db", 1, "a"), ("meta/b.db", 2, "b"), ("meta/c.db", 3, "c"),
        ]), encoding: .utf8)!
        // Simulate the connection dying two objects in.
        xml = String(xml.prefix(xml.count - 120))
        let stub = StubTransport([HTTPResponse(status: 200, body: Data(xml.utf8))])
        await #expect(throws: StorageError.self) {
            _ = try await TestFixtures.client(stub).list().collect()
        }
    }

    @Test("an empty bucket is an empty sequence")
    func emptyList() async throws {
        let stub = StubTransport([HTTPResponse(status: 200, body: TestFixtures.listXML(keys: []))])
        #expect(try await TestFixtures.client(stub).list().collect().isEmpty)
    }
}

struct PresignTests {

    @Test("a presigned PUT carries every query parameter and no Authorization header")
    func shape() throws {
        let client = TestFixtures.client(StubTransport([]))
        let url = try client.presignedPUT("originals/Trip/IMG_1.heic", expiresIn: .seconds(3600))
        let string = url.absoluteString

        #expect(string.contains("X-Amz-Algorithm=AWS4-HMAC-SHA256"))
        #expect(string.contains("X-Amz-Credential=my-photos%2F"))
        #expect(string.contains("X-Amz-Expires=3600"))
        #expect(string.contains("X-Amz-SignedHeaders=host"))
        #expect(string.contains("X-Amz-Signature="))
        #expect(string.hasPrefix("https://de-s3.storage.bunnycdn.com/my-photos/originals/Trip/IMG_1.heic?"))
    }

    @Test("the same inputs give the same signature")
    func deterministic() throws {
        let client = TestFixtures.client(StubTransport([]))
        let at = Date(timeIntervalSince1970: 1_440_938_160)
        let a = try client.presignedPUT("meta/a.db", expiresIn: .seconds(60), now: at)
        let b = try client.presignedPUT("meta/a.db", expiresIn: .seconds(60), now: at)
        #expect(a == b)
    }

    @Test("keys are NFC-normalised in presigned URLs too")
    func normalises() throws {
        let client = TestFixtures.client(StubTransport([]))
        let url = try client.presignedPUT("originals/Gru\u{0308}n/a.heic", expiresIn: .seconds(60))
        #expect(url.absoluteString.contains("Gr%C3%BCn"))
    }

    @Test("bunny.net's 1 second – 7 day expiry window is enforced",
          arguments: [Duration.seconds(0), .seconds(-1), .seconds(604_801)])
    func rejectsBadExpiry(expiry: Duration) {
        let client = TestFixtures.client(StubTransport([]))
        #expect(throws: StorageError.self) {
            _ = try client.presignedPUT("meta/a.db", expiresIn: expiry)
        }
    }

    @Test("the window's edges are accepted", arguments: [Duration.seconds(1), .seconds(604_800)])
    func acceptsEdges(expiry: Duration) throws {
        let client = TestFixtures.client(StubTransport([]))
        _ = try client.presignedPUT("meta/a.db", expiresIn: expiry)
    }
}

struct MultipartSizingTests {

    @Test("an ordinary file keeps the preferred part size")
    func preferred() {
        let size = S3Client.partSize(forFileOf: 100 * 1024 * 1024, preferred: 16 * 1024 * 1024)
        #expect(size == 16 * 1024 * 1024)
    }

    @Test("the part size grows so bunny's 10 000-part cap is never hit")
    func growsForHugeFiles() {
        // 1 TB at 16 MB parts would be 65 536 parts — beyond the limit.
        let huge: Int64 = 1024 * 1024 * 1024 * 1024
        let size = S3Client.partSize(forFileOf: huge, preferred: 16 * 1024 * 1024)
        #expect(huge / size <= S3Client.maximumPartCount)
    }

    /// S3 rejects a short part only at CompleteMultipartUpload — after the entire
    /// file has been uploaded. Clamping means a caller cannot configure that.
    @Test("a part size below S3's 5 MB minimum is raised, not passed through",
          arguments: [Int64(1024), Int64(512 * 1024), Int64(4 * 1024 * 1024)])
    func clampsToMinimum(preferred: Int64) {
        let size = S3Client.partSize(forFileOf: 100 * 1024 * 1024, preferred: preferred)
        #expect(size == S3Client.minimumPartSize)
    }
}
