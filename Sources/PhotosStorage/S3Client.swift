import Foundation

/// A minimal S3 client for one bunny.net storage zone.
///
/// Concrete methods for exactly the operations the design needs — no generic
/// request layer. A `Sendable` struct holding immutable configuration, so callers
/// may use one instance from as many concurrent tasks as they like without an actor
/// serialising them.
///
/// Deliberately absent: a concurrency limiter. §9 measured that more concurrent
/// uploads makes throughput slightly *worse*, so that knob belongs in the caller's
/// worker pool where it can be seen, not buried here.
public struct S3Client: Sendable {

    public let storage: StorageURL
    public let signer: SigV4Signer
    public let transport: any HTTPTransport
    public let retry: RetryPolicy
    public let payloadSigning: PayloadSigning
    /// Uploads at or above this size go through multipart. Ordinary photos and
    /// shards never reach it; transcoded video does.
    public let multipartThreshold: Int64
    public let multipartPartSize: Int64

    public init(
        storage: StorageURL,
        secretAccessKey: String,
        transport: (any HTTPTransport)? = nil,
        retry: RetryPolicy = .default,
        payloadSigning: PayloadSigning = .signed,
        multipartThreshold: Int64 = 64 * 1024 * 1024,
        multipartPartSize: Int64 = 16 * 1024 * 1024
    ) {
        self.storage = storage
        // On bunny.net the zone name is the access key ID (§1).
        self.signer = SigV4Signer(
            accessKeyID: storage.zone,
            secretAccessKey: secretAccessKey,
            region: storage.region,
            service: "s3",
            normalizePath: false        // S3 signs the path exactly as sent
        )
        self.transport = transport ?? URLSessionTransport()
        self.retry = retry
        self.payloadSigning = payloadSigning
        self.multipartThreshold = multipartThreshold
        self.multipartPartSize = multipartPartSize
    }

    /// How the `x-amz-content-sha256` header is filled for file bodies.
    ///
    /// `.unsigned` avoids a full read pass over every uploaded byte, but it is only
    /// usable if bunny.net accepts `UNSIGNED-PAYLOAD` on header-authenticated PUTs —
    /// which a one-off manual probe settles. `.signed` is the choice that needs no
    /// verification, so it is the default.
    public enum PayloadSigning: Sendable {
        case signed
        case unsigned
    }

    // MARK: - Keys

    /// Builds the zone-relative request path for a key.
    ///
    /// NFC-normalises here, at the last gate before the wire: Linux stores whatever
    /// bytes it was handed and iOS emits NFD, so `Grün` composed and decomposed are
    /// different keys (§2). Doing it in the client makes the invariant structural
    /// rather than a rule every call site must remember.
    func path(forKey key: String) -> String {
        let normalized = key.precomposedStringWithCanonicalMapping
        let trimmed = normalized.hasPrefix("/") ? String(normalized.dropFirst()) : normalized
        return "/\(storage.zone)/\(trimmed)"
    }

    func url(path: String, query: [(name: String, value: String)]) -> URL? {
        var string = storage.endpoint.absoluteString
        if string.hasSuffix("/") { string.removeLast() }
        string += SigV4Signer.uriEncode(path, encodeSlash: false)
        if !query.isEmpty {
            string += "?" + SigV4Signer.canonicalQuery(query)
        }
        return URL(string: string)
    }

    // MARK: - Signing

    func signedRequest(
        method: String,
        path: String,
        query: [(name: String, value: String)] = [],
        headers: [(name: String, value: String)] = [],
        body: Body,
        payloadHash: String? = nil,
        now: Date = Date()
    ) throws -> HTTPRequest {
        guard let url = url(path: path, query: query) else {
            throw StorageError.malformedResponse("could not build a URL for \(path)")
        }

        let hash: String
        if let payloadHash {
            hash = payloadHash
        } else {
            switch body {
            case .empty:
                hash = SigV4Signer.sha256Hex(Data())
            case .data(let d):
                hash = SigV4Signer.sha256Hex(d)
            case .file(let fileURL):
                switch payloadSigning {
                case .unsigned: hash = SigV4Signer.unsignedPayload
                case .signed: hash = try SigV4Signer.sha256Hex(fileAt: fileURL)
                }
            }
        }

        var allHeaders: [(name: String, value: String)] = [
            ("Host", url.host ?? storage.endpoint.host ?? ""),
            ("X-Amz-Date", SigV4Signer.amzDate(now)),
            ("X-Amz-Content-Sha256", hash),
        ]
        allHeaders.append(contentsOf: headers)
        if let length = body.byteCount, length > 0 {
            allHeaders.append(("Content-Length", String(length)))
        }

        let canonical = signer.canonicalRequest(
            method: method, path: path, query: query,
            headers: allHeaders, payloadHash: hash
        )
        let sts = signer.stringToSign(canonicalRequest: canonical.text, date: now)
        let signature = signer.signature(stringToSign: sts, date: now)
        let credential = "\(signer.accessKeyID)/\(signer.credentialScope(date: now))"
        allHeaders.append((
            "Authorization",
            "\(SigV4Signer.algorithm) Credential=\(credential), "
                + "SignedHeaders=\(canonical.signedHeaders), Signature=\(signature)"
        ))

        return HTTPRequest(method: method, url: url, headers: allHeaders, body: body)
    }

    // MARK: - Sending, with retry

    /// Retries 5xx, 429 and transport failures only.
    ///
    /// 4xx is never retried — retrying a wrong password just delays the message §1
    /// promises to show. 412 is not a failure at all here; it is returned as a value
    /// because the caller must re-read the object before trying again.
    func send(_ request: HTTPRequest, expecting acceptable: Set<Int>,
              key: String? = nil) async throws -> HTTPResponse {
        var attempt = 0
        while true {
            attempt += 1
            do {
                let response = try await transport.send(request)
                if acceptable.contains(response.status) {
                    return response
                }
                let error = S3HTTPError.parse(
                    status: response.status,
                    body: response.body,
                    key: key,
                    requestID: response[header: "x-amz-request-id"]
                )
                guard retry.shouldRetry(status: response.status), attempt <= retry.maxAttempts else {
                    throw StorageError.http(error)
                }
                try await retry.wait(attempt: attempt,
                                     retryAfter: response[header: "retry-after"])
            } catch let error as StorageError {
                guard case .transport = error, attempt <= retry.maxAttempts else { throw error }
                try await retry.wait(attempt: attempt, retryAfter: nil)
            }
        }
    }

    // MARK: - Operations

    /// `nil` when the object does not exist.
    public func head(_ key: String) async throws -> S3Object? {
        let request = try signedRequest(method: "HEAD", path: path(forKey: key), body: .empty)
        let response = try await send(request, expecting: [200, 404], key: key)
        guard response.status == 200 else { return nil }
        return S3Object(
            key: key.precomposedStringWithCanonicalMapping,
            size: Int64(response[header: "content-length"] ?? "") ?? 0,
            etag: response[header: "etag"].map { ETag(header: $0) },
            lastModified: response[header: "last-modified"].flatMap(Self.parseHTTPDate)
        )
    }

    /// Fetches an object, optionally a byte range, optionally conditional.
    ///
    /// A 304 comes back as `.notModified` rather than an error: it is the common
    /// case in §4's sync diff, and burying the normal path inside a `catch` reads
    /// backwards and is easy to swallow.
    public func get(
        _ key: String,
        range: Range<Int64>? = nil,
        ifNoneMatch: ETag? = nil
    ) async throws -> GetResult {
        var headers: [(name: String, value: String)] = []
        if let range {
            headers.append(("Range", "bytes=\(range.lowerBound)-\(range.upperBound - 1)"))
        }
        if let ifNoneMatch {
            headers.append(("If-None-Match", ifNoneMatch.headerValue))
        }
        let request = try signedRequest(method: "GET", path: path(forKey: key),
                                        headers: headers, body: .empty)
        let response = try await send(request, expecting: [200, 206, 304], key: key)
        if response.status == 304 { return .notModified }
        return .object(response.body, response[header: "etag"].map { ETag(header: $0) })
    }

    /// Streams an object to a file. Used for previews and originals, which have no
    /// reason to pass through memory.
    @discardableResult
    public func download(
        _ key: String,
        to destination: URL,
        progress: (@Sendable (Int64, Int64) -> Void)? = nil
    ) async throws -> ETag? {
        var request = try signedRequest(method: "GET", path: path(forKey: key), body: .empty)
        request.downloadTo = destination
        request.progress = progress
        let response = try await send(request, expecting: [200], key: key)
        return response[header: "etag"].map { ETag(header: $0) }
    }

    /// Uploads an object, switching to multipart above `multipartThreshold`.
    ///
    /// `ifMatch` guards §2's single-owner shard rule: a stale ETag yields
    /// `.staleETag` (HTTP 412), meaning "someone else wrote it — re-read and retry".
    public func put(
        _ key: String,
        body: Body,
        contentType: String? = nil,
        ifMatch: ETag? = nil,
        progress: (@Sendable (Int64, Int64) -> Void)? = nil
    ) async throws -> PutResult {
        if case .file(let url) = body,
           let size = body.byteCount, size >= multipartThreshold, ifMatch == nil {
            let etag = try await multipartUpload(key, file: url, size: size,
                                                 contentType: contentType, progress: progress)
            return .written(etag)
        }

        var headers: [(name: String, value: String)] = []
        if let contentType { headers.append(("Content-Type", contentType)) }
        if let ifMatch { headers.append(("If-Match", ifMatch.headerValue)) }

        var request = try signedRequest(method: "PUT", path: path(forKey: key),
                                        headers: headers, body: body)
        request.progress = progress
        let response = try await send(request, expecting: [200, 412], key: key)
        if response.status == 412 { return .staleETag }
        return .written(response[header: "etag"].map { ETag(header: $0) })
    }

    /// Permanently removes an object. bunny.net has no versioning and no undelete,
    /// so this is the single irreversible operation in the system (§7). The iOS app
    /// never calls it.
    public func delete(_ key: String) async throws {
        let request = try signedRequest(method: "DELETE", path: path(forKey: key), body: .empty)
        _ = try await send(request, expecting: [200, 204, 404], key: key)
    }

    static func parseHTTPDate(_ s: String) -> Date? {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(identifier: "GMT")
        formatter.dateFormat = "EEE, dd MMM yyyy HH:mm:ss zzz"
        return formatter.date(from: s)
    }
}
