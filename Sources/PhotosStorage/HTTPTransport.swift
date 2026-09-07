import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// A single HTTP exchange, expressed without any S3 knowledge.
///
/// The seam exists for two reasons: tests can drive retry, error mapping and LIST
/// paging offline against a stub, and milestone G can plug iOS's background
/// `URLSession` in underneath without touching the client.
public protocol HTTPTransport: Sendable {
    func send(_ request: HTTPRequest) async throws -> HTTPResponse
}

/// What goes on the wire. Headers are an ordered list, not a dictionary, because
/// SigV4 joins repeated header values in the order they were sent.
public struct HTTPRequest: Sendable {
    public var method: String
    public var url: URL
    public var headers: [(name: String, value: String)]
    public var body: Body
    /// Called with (bytesSent, totalBytes) during upload, when the transport can.
    public var progress: (@Sendable (Int64, Int64) -> Void)?
    /// When set, the response body is streamed to this file instead of held in memory.
    public var downloadTo: URL?

    public init(method: String, url: URL,
                headers: [(name: String, value: String)] = [],
                body: Body = .empty,
                progress: (@Sendable (Int64, Int64) -> Void)? = nil,
                downloadTo: URL? = nil) {
        self.method = method
        self.url = url
        self.headers = headers
        self.body = body
        self.progress = progress
        self.downloadTo = downloadTo
    }
}

/// Request payloads. `.file` exists because §8's background uploads must come from
/// a file on disk, and because a 200 MB transcode should never sit in memory.
public enum Body: Sendable {
    case empty
    case data(Data)
    case file(URL)

    public var byteCount: Int64? {
        switch self {
        case .empty: 0
        case .data(let d): Int64(d.count)
        case .file(let url):
            (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? Int64) ?? nil
        }
    }
}

public struct HTTPResponse: Sendable {
    public var status: Int
    public var headers: [String: String]   // lowercased names
    public var body: Data

    public init(status: Int, headers: [String: String] = [:], body: Data = Data()) {
        self.status = status
        self.headers = headers.reduce(into: [:]) { $0[$1.key.lowercased()] = $1.value }
        self.body = body
    }

    public subscript(header name: String) -> String? {
        headers[name.lowercased()]
    }
}

// MARK: - URLSession

/// The default transport (§7: URLSession + swift-crypto, no Soto, no swift-nio).
public struct URLSessionTransport: HTTPTransport {
    let session: URLSession

    public init(timeout: TimeInterval = 60) {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = timeout
        config.httpShouldSetCookies = false
        config.urlCache = nil
        self.session = URLSession(configuration: config)
    }

    public init(session: URLSession) {
        self.session = session
    }

    public func send(_ request: HTTPRequest) async throws -> HTTPResponse {
        var urlRequest = URLRequest(url: request.url)
        urlRequest.httpMethod = request.method
        for (name, value) in request.headers {
            urlRequest.addValue(value, forHTTPHeaderField: name)
        }

        do {
            let data: Data
            let response: URLResponse

            switch request.body {
            case .empty:
                (data, response) = try await session.data(for: urlRequest)
            case .data(let payload):
                (data, response) = try await session.upload(for: urlRequest, from: payload)
            case .file(let url):
                (data, response) = try await session.upload(for: urlRequest, fromFile: url)
            }

            guard let http = response as? HTTPURLResponse else {
                throw StorageError.malformedResponse("response was not HTTP")
            }
            var headers: [String: String] = [:]
            for (name, value) in http.allHeaderFields {
                if let n = name as? String, let v = value as? String {
                    headers[n.lowercased()] = v
                }
            }

            // A download destination means the caller wants the bytes on disk.
            // corelibs-foundation's `download(for:)` does not report progress or
            // stream any better than this, so the write happens here.
            if let destination = request.downloadTo, (200..<300).contains(http.statusCode) {
                try data.write(to: destination, options: .atomic)
                if let total = Int64(headers["content-length"] ?? "") {
                    request.progress?(total, total)
                }
                return HTTPResponse(status: http.statusCode, headers: headers, body: Data())
            }

            return HTTPResponse(status: http.statusCode, headers: headers, body: data)
        } catch let error as StorageError {
            throw error
        } catch {
            throw StorageError.transport(error)
        }
    }
}
