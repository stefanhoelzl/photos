import Foundation
import Testing
@testable import PhotosStorage

struct S3ClientKeyTests {

    @Test("keys are NFC-normalised before they reach the wire")
    func nfcNormalisation() async throws {
        // Decomposed 'ü' — what iOS emits, and what Linux may have stored.
        let decomposed = "meta/Gru\u{0308}n.db"
        let stub = StubTransport([HTTPResponse(status: 200, headers: ["etag": "\"abc\""])])
        _ = try await TestFixtures.client(stub).head(decomposed)

        let url = try #require(stub.requests.first).url.absoluteString
        // Precomposed ü is C3 BC; decomposed u + combining diaeresis is 75 CC 88.
        #expect(url.contains("Gr%C3%BCn.db"))
        #expect(!url.contains("u%CC%88"))
    }

    @Test("the zone is the first path segment, path-style")
    func pathStyle() async throws {
        let stub = StubTransport([HTTPResponse(status: 200)])
        _ = try await TestFixtures.client(stub).head("meta/Trips/Iceland.db")
        let url = try #require(stub.requests.first).url.absoluteString
        #expect(url == "https://de-s3.storage.bunnycdn.com/my-photos/meta/Trips/Iceland.db")
    }

    @Test("key characters are percent-encoded but slashes are kept")
    func encoding() async throws {
        let stub = StubTransport([HTTPResponse(status: 200)])
        _ = try await TestFixtures.client(stub).head("meta/Trips 2024/a+b&c.db")
        let url = try #require(stub.requests.first).url.absoluteString
        #expect(url.contains("/meta/Trips%202024/a%2Bb%26c.db"))
    }

    @Test("every request carries a SigV4 authorization header for the right scope")
    func signing() async throws {
        let stub = StubTransport([HTTPResponse(status: 200)])
        _ = try await TestFixtures.client(stub).head("meta/a.db")
        let sent = try #require(stub.requests.first)
        let auth = try #require(sent.authorization)
        #expect(auth.hasPrefix("AWS4-HMAC-SHA256 "))
        // On bunny.net the zone name is the access key ID (§1).
        #expect(auth.contains("Credential=my-photos/"))
        #expect(auth.contains("/de/s3/aws4_request"))
        #expect(auth.contains("SignedHeaders=host;x-amz-content-sha256;x-amz-date"))
    }
}

struct S3ClientStatusTests {

    @Test("404 on HEAD is nil, not an error")
    func headMissing() async throws {
        let stub = StubTransport([HTTPResponse(status: 404)])
        #expect(try await TestFixtures.client(stub).head("meta/gone.db") == nil)
    }

    @Test("HEAD parses size, etag and last-modified")
    func headFound() async throws {
        let stub = StubTransport([HTTPResponse(status: 200, headers: [
            "content-length": "32768",
            "etag": "\"d41d8cd98f00b204e9800998ecf8427e\"",
            "last-modified": "Wed, 15 Jan 2026 10:30:00 GMT",
        ])])
        let object = try #require(try await TestFixtures.client(stub).head("meta/a.db"))
        #expect(object.size == 32768)
        #expect(object.etag == ETag(unquoted: "d41d8cd98f00b204e9800998ecf8427e"))
        #expect(object.lastModified != nil)
    }

    @Test("304 is a value, because it is the common case in the sync diff")
    func notModified() async throws {
        let stub = StubTransport([HTTPResponse(status: 304)])
        let result = try await TestFixtures.client(stub)
            .get("meta/a.db", ifNoneMatch: ETag(unquoted: "abc"))
        guard case .notModified = result else {
            Issue.record("expected .notModified, got \(result)")
            return
        }
        let sent = try #require(stub.requests.first)
        #expect(sent.header("If-None-Match") == "\"abc\"")
    }

    @Test("412 is a value, because the caller must re-read before retrying")
    func staleETag() async throws {
        let stub = StubTransport([HTTPResponse(status: 412)])
        let result = try await TestFixtures.client(stub)
            .put("meta/a.db", body: .data(Data("x".utf8)), ifMatch: ETag(unquoted: "old"))
        guard case .staleETag = result else {
            Issue.record("expected .staleETag, got \(result)")
            return
        }
        let sent = try #require(stub.requests.first)
        #expect(sent.header("If-Match") == "\"old\"")
    }

    @Test("a range GET asks for an inclusive byte range and accepts 206")
    func rangeGet() async throws {
        let stub = StubTransport([HTTPResponse(status: 206, body: Data("partial".utf8))])
        let result = try await TestFixtures.client(stub).get("preview/a.heic", range: 0..<1024)
        #expect(result.data == Data("partial".utf8))
        let sent = try #require(stub.requests.first)
        #expect(sent.header("Range") == "bytes=0-1023")
    }
}

struct S3ClientErrorTests {

    @Test("an S3 XML error body becomes a message that names the status and cause")
    func errorParsing() async throws {
        let body = Data("""
        <?xml version="1.0" encoding="UTF-8"?>
        <Error><Code>AccessDenied</Code><Message>Access Denied</Message>\
        <Key>meta/a.db</Key><RequestId>ABC123</RequestId></Error>
        """.utf8)
        let stub = StubTransport([HTTPResponse(status: 403, body: body)])

        await #expect(throws: StorageError.self) {
            _ = try await TestFixtures.client(stub).get("meta/a.db")
        }
        do {
            _ = try await TestFixtures.client(StubTransport([HTTPResponse(status: 403, body: body)]))
                .get("meta/a.db")
        } catch let error as StorageError {
            guard case .http(let http) = error else {
                Issue.record("expected .http, got \(error)")
                return
            }
            #expect(http.status == 403)
            #expect(http.code == "AccessDenied")
            #expect(http.requestID == "ABC123")
            #expect(http.description == "403 Forbidden — AccessDenied: Access Denied (meta/a.db)")
            // §1: never an opaque error — the message must be actionable.
            #expect(http.userMessage.contains("log out and check the password"))
        }
    }

    @Test("an error body that is missing or not XML still yields the status")
    func degradedErrorBody() {
        let empty = S3HTTPError.parse(status: 503, body: Data(), key: "meta/a.db", requestID: nil)
        #expect(empty.status == 503)
        #expect(empty.description.hasPrefix("503 Service Unavailable"))

        let junk = S3HTTPError.parse(status: 500, body: Data("<<not xml".utf8),
                                     key: nil, requestID: nil)
        #expect(junk.status == 500)
    }
}

struct RetryTests {

    @Test("5xx is retried and then succeeds")
    func retriesServerErrors() async throws {
        let stub = StubTransport([
            HTTPResponse(status: 503),
            HTTPResponse(status: 503),
            HTTPResponse(status: 200, headers: ["etag": "\"ok\""]),
        ])
        let client = TestFixtures.client(
            stub, retry: RetryPolicy(maxAttempts: 4, baseDelay: .milliseconds(1))
        )
        let result = try await client.get("meta/a.db")
        #expect(result.data != nil)
        #expect(stub.requestCount == 3)
    }

    @Test("4xx is never retried — a wrong password fails immediately")
    func doesNotRetryClientErrors() async throws {
        let stub = StubTransport([
            HTTPResponse(status: 403),
            HTTPResponse(status: 200),
        ])
        let client = TestFixtures.client(
            stub, retry: RetryPolicy(maxAttempts: 4, baseDelay: .milliseconds(1))
        )
        await #expect(throws: StorageError.self) { _ = try await client.get("meta/a.db") }
        #expect(stub.requestCount == 1)
    }

    @Test("a transport failure is retried")
    func retriesTransportFailures() async throws {
        struct Dropped: Error {}
        let stub = StubTransport(exchanges: [
            .init(response: nil, error: StorageError.transport(Dropped())),
            .init(response: HTTPResponse(status: 200), error: nil),
        ])
        let client = TestFixtures.client(
            stub, retry: RetryPolicy(maxAttempts: 4, baseDelay: .milliseconds(1))
        )
        _ = try await client.get("meta/a.db")
        #expect(stub.requestCount == 2)
    }

    @Test("retries are bounded")
    func givesUp() async throws {
        let stub = StubTransport(Array(repeating: HTTPResponse(status: 500), count: 10))
        let client = TestFixtures.client(
            stub, retry: RetryPolicy(maxAttempts: 3, baseDelay: .milliseconds(1))
        )
        await #expect(throws: StorageError.self) { _ = try await client.get("meta/a.db") }
        #expect(stub.requestCount == 4)   // the first try plus 3 retries
    }

    @Test("only 429 and 5xx are retryable")
    func retryClassification() {
        let policy = RetryPolicy.default
        #expect(policy.shouldRetry(status: 429))
        #expect(policy.shouldRetry(status: 500))
        #expect(policy.shouldRetry(status: 503))
        #expect(!policy.shouldRetry(status: 403))
        #expect(!policy.shouldRetry(status: 404))
        #expect(!policy.shouldRetry(status: 412))
    }

    @Test("Retry-After is honoured over the backoff curve")
    func honoursRetryAfter() {
        let policy = RetryPolicy(maxAttempts: 4, baseDelay: .milliseconds(1), maxDelay: .seconds(30))
        #expect(policy.delay(attempt: 1, retryAfter: "5") == .seconds(5))
        // ...but never beyond the cap.
        #expect(policy.delay(attempt: 1, retryAfter: "9999") == .seconds(30))
    }
}
