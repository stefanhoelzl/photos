import Foundation
import Testing
@testable import PhotosStorage

/// Drives the vendored AWS SigV4 suite (see Fixtures/sigv4/PROVENANCE.md).
///
/// The local S3 server used elsewhere does not validate signatures, so these
/// vectors are the sole proof the signer is correct. Each stage — canonical
/// request, string-to-sign, signature — is asserted separately so a failure
/// names which stage broke rather than just "signature mismatch".
struct SigV4VectorTests {

    // MARK: - Vector loading

    struct Vector: Sendable, CustomStringConvertible {
        let name: String
        let signer: SigV4Signer
        let date: Date
        let expiresInSeconds: Int
        let sessionToken: String?
        let signBody: Bool
        let method: String
        let path: String
        let query: [(name: String, value: String)]
        let headers: [(name: String, value: String)]
        let body: Data
        let expected: [String: String]   // file basename -> contents

        var description: String { name }
    }

    static let fixtures: URL = {
        Bundle.module.url(forResource: "Fixtures/sigv4", withExtension: nil)
            ?? URL(fileURLWithPath: #filePath)
                .deletingLastPathComponent()
                .appendingPathComponent("Fixtures/sigv4")
    }()

    static let vectors: [Vector] = {
        let fm = FileManager.default
        let names = ((try? fm.contentsOfDirectory(atPath: fixtures.path)) ?? [])
            .filter { fm.fileExists(atPath: fixtures.appendingPathComponent("\($0)/request.txt").path) }
            .sorted()
        return names.compactMap { load($0) }
    }()

    static func load(_ name: String) -> Vector? {
        let dir = fixtures.appendingPathComponent(name)
        func text(_ file: String) -> String? {
            try? String(contentsOf: dir.appendingPathComponent(file), encoding: .utf8)
        }
        guard let contextData = try? Data(contentsOf: dir.appendingPathComponent("context.json")),
              let context = try? JSONSerialization.jsonObject(with: contextData) as? [String: Any],
              let credentials = context["credentials"] as? [String: String],
              let raw = text("request.txt")
        else { return nil }

        let parsed = parseRequest(raw)
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]

        var expected: [String: String] = [:]
        for file in ["header-canonical-request", "header-string-to-sign", "header-signature",
                     "query-canonical-request", "query-string-to-sign", "query-signature"] {
            if let value = text("\(file).txt") { expected[file] = value }
        }

        return Vector(
            name: name,
            signer: SigV4Signer(
                accessKeyID: credentials["access_key_id"] ?? "",
                secretAccessKey: credentials["secret_access_key"] ?? "",
                region: context["region"] as? String ?? "us-east-1",
                service: context["service"] as? String ?? "service",
                normalizePath: context["normalize"] as? Bool ?? true
            ),
            date: formatter.date(from: context["timestamp"] as? String ?? "") ?? .distantPast,
            expiresInSeconds: context["expiration_in_seconds"] as? Int ?? 3600,
            // `omit_session_token` means the token is attached after signing,
            // so it must not appear in the canonical request.
            sessionToken: (context["omit_session_token"] as? Bool ?? false)
                ? nil : credentials["token"],
            signBody: context["sign_body"] as? Bool ?? false,
            method: parsed.method,
            path: parsed.path,
            query: parsed.query,
            headers: parsed.headers,
            body: parsed.body,
            expected: expected
        )
    }

    /// Parses the suite's raw-HTTP `request.txt`, including obsolete line folding
    /// (a continuation line joins its predecessor with a single space).
    static func parseRequest(_ raw: String)
        -> (method: String, path: String, query: [(name: String, value: String)],
            headers: [(name: String, value: String)], body: Data)
    {
        let lines = raw.components(separatedBy: "\n")
        let requestLine = lines.first ?? ""
        var parts = requestLine.split(separator: " ", omittingEmptySubsequences: false).map(String.init)
        let method = parts.first ?? "GET"
        if parts.count > 1 { parts.removeFirst() }
        if parts.count > 1, parts.last!.hasPrefix("HTTP/") { parts.removeLast() }
        // A path may itself contain spaces (get-space-*), so rejoin what is left.
        let target = parts.joined(separator: " ")

        let path: String
        let queryString: String
        if let q = target.firstIndex(of: "?") {
            path = String(target[target.startIndex..<q])
            queryString = String(target[target.index(after: q)...])
        } else {
            path = target
            queryString = ""
        }

        var query: [(name: String, value: String)] = []
        if !queryString.isEmpty {
            for pair in queryString.components(separatedBy: "&") where !pair.isEmpty {
                let kv = pair.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)
                let name = percentDecode(String(kv.first ?? ""))
                let value = kv.count > 1 ? percentDecode(String(kv[1])) : ""
                query.append((name, value))
            }
        }

        var headers: [(name: String, value: String)] = []
        var body = Data()
        var index = 1
        while index < lines.count {
            let line = lines[index]
            if line.isEmpty {
                let rest = lines[(index + 1)...].joined(separator: "\n")
                body = Data(rest.utf8)
                break
            }
            if line.hasPrefix(" ") || line.hasPrefix("\t") {
                // Obsolete folding: continuation of the previous header value.
                if let last = headers.popLast() {
                    let cont = line.trimmingCharacters(in: .whitespaces)
                    headers.append((last.name, last.value + " " + cont))
                }
            } else if let colon = line.firstIndex(of: ":") {
                headers.append((String(line[line.startIndex..<colon]),
                                String(line[line.index(after: colon)...])))
            }
            index += 1
        }
        return (method, path, query, headers, body)
    }

    static func percentDecode(_ s: String) -> String {
        s.removingPercentEncoding ?? s
    }

    // MARK: - Header auth

    @Test("suite is vendored and non-trivial")
    func suiteIsPresent() {
        #expect(Self.vectors.count >= 30,
                "only \(Self.vectors.count) vectors found — the suite is the sole proof the signer works")
    }

    @Test("header auth: canonical request", arguments: Self.vectors)
    func headerCanonicalRequest(vector: Vector) throws {
        let expected = try #require(vector.expected["header-canonical-request"])
        let payloadHash = SigV4Signer.sha256Hex(vector.body)
        var headers = vector.headers
        if !headers.contains(where: { $0.name.lowercased() == "x-amz-date" }) {
            headers.append(("X-Amz-Date", SigV4Signer.amzDate(vector.date)))
        }
        // The signer has no notion of STS or body-hash headers (§7: no session
        // tokens); it signs whatever headers it is handed, so the harness supplies
        // the ones these vectors expect.
        if let token = vector.sessionToken {
            headers.append(("X-Amz-Security-Token", token))
        }
        if vector.signBody {
            headers.append(("X-Amz-Content-Sha256", payloadHash))
        }
        let actual = vector.signer.canonicalRequest(
            method: vector.method,
            path: vector.path,
            query: vector.query,
            headers: headers,
            payloadHash: payloadHash
        ).text
        #expect(actual == expected)
    }

    @Test("header auth: string to sign", arguments: Self.vectors)
    func headerStringToSign(vector: Vector) throws {
        let canonical = try #require(vector.expected["header-canonical-request"])
        let expected = try #require(vector.expected["header-string-to-sign"])
        let actual = vector.signer.stringToSign(canonicalRequest: canonical, date: vector.date)
        #expect(actual == expected)
    }

    @Test("header auth: signature", arguments: Self.vectors)
    func headerSignature(vector: Vector) throws {
        let sts = try #require(vector.expected["header-string-to-sign"])
        let expected = try #require(vector.expected["header-signature"])
        let actual = vector.signer.signature(stringToSign: sts, date: vector.date)
        #expect(actual == expected)
    }

    // MARK: - Query auth (presigned URLs)

    @Test("query auth: canonical request", arguments: Self.vectors)
    func queryCanonicalRequest(vector: Vector) throws {
        let expected = try #require(vector.expected["query-canonical-request"])
        // Query auth signs the request's headers as sent — no X-Amz-Date header is
        // added, because the date moves into the query string. `X-Amz-SignedHeaders`
        // must therefore be computed before the query is assembled.
        let headers = vector.headers
        let signedHeaders = SigV4Signer.canonicalHeaders(headers).signed
        var query = vector.query
        query.append(("X-Amz-Algorithm", SigV4Signer.algorithm))
        query.append(("X-Amz-Credential",
                      "\(vector.signer.accessKeyID)/\(vector.signer.credentialScope(date: vector.date))"))
        query.append(("X-Amz-Date", SigV4Signer.amzDate(vector.date)))
        query.append(("X-Amz-Expires", String(vector.expiresInSeconds)))
        if let token = vector.sessionToken {
            query.append(("X-Amz-Security-Token", token))
        }
        query.append(("X-Amz-SignedHeaders", signedHeaders))

        let actual = vector.signer.canonicalRequest(
            method: vector.method,
            path: vector.path,
            query: query,
            headers: headers,
            payloadHash: SigV4Signer.sha256Hex(vector.body)
        ).text
        #expect(actual == expected)
    }

    @Test("query auth: signature", arguments: Self.vectors)
    func querySignature(vector: Vector) throws {
        let sts = try #require(vector.expected["query-string-to-sign"])
        let expected = try #require(vector.expected["query-signature"])
        let actual = vector.signer.signature(stringToSign: sts, date: vector.date)
        #expect(actual == expected)
    }
}
