import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
import Testing
@testable import PhotosStorage

#if canImport(Glibc)
import Glibc
#endif

/// Runs adobe/S3Mock for the round-trip tests.
///
/// Started by the harness rather than by hand, so `swift test` stays a single
/// command that works on a fresh clone. The jar is pinned and fetched into
/// `.tools/` by `Scripts/fetch-s3mock.sh`; java is already a dependency of nothing
/// else, so if it is missing the round-trip tests skip rather than fail.
///
/// S3Mock does not validate signatures. That is deliberate and accepted — the
/// vendored AWS vector suite is what proves the signer. What this server *does*
/// give is real HTTP, real XML from a real implementation, real LIST paging, real
/// multipart, and real conditional-PUT 412s.
final class S3MockServer: @unchecked Sendable {

    let port: Int
    let bucket = "my-photos"
    private let process: Process

    static let shared: S3MockServer? = try? S3MockServer()

    /// Set when the server could not be started, so tests can say why they skipped.
    static var unavailableReason: String? {
        shared == nil ? "S3Mock could not be started (is java installed? run Scripts/fetch-s3mock.sh)" : nil
    }

    var storageURL: StorageURL {
        get throws { try StorageURL("http://127.0.0.1:\(port)/\(bucket)") }
    }

    func client(retry: RetryPolicy = .none,
                payloadSigning: S3Client.PayloadSigning = .signed,
                multipartThreshold: Int64 = 64 * 1024 * 1024,
                multipartPartSize: Int64 = 16 * 1024 * 1024) throws -> S3Client {
        S3Client(storage: try storageURL,
                 secretAccessKey: "test-secret-key",
                 retry: retry,
                 payloadSigning: payloadSigning,
                 multipartThreshold: multipartThreshold,
                 multipartPartSize: multipartPartSize)
    }

    struct StartupFailure: Error, CustomStringConvertible {
        let description: String
    }

    private init() throws {
        let root = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()   // PhotosStorageTests
            .deletingLastPathComponent()   // Tests
            .deletingLastPathComponent()   // package root

        let jar = try Self.locateJar(packageRoot: root)
        self.port = Self.freePort()

        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/env")
        // `http.port` only takes effect as a JVM system property. Passed as a
        // Spring CLI argument (`--http.port=`) it is silently ignored and HTTP
        // stays on the default 9090 — which looks exactly like a server that
        // never started. Verified against S3Mock 4.11.0.
        process.arguments = ["java",
                             "-Dhttp.port=\(port)",
                             // Keep the TLS connector off a port we might collide with.
                             "-Dserver.port=\(Self.freePort())",
                             "-jar", jar.path]
        process.standardOutput = FileHandle.nullDevice
        process.standardError = FileHandle.nullDevice
        self.process = process

        do {
            try process.run()
        } catch {
            throw StartupFailure(description: "could not launch java: \(error)")
        }

        // The child must not outlive the test run.
        S3MockServer.registerCleanup(process)

        guard Self.waitUntilReady(port: port, timeout: 60) else {
            process.terminate()
            throw StartupFailure(description: "S3Mock did not become ready on port \(port)")
        }
        try createBucket()
    }

    static func locateJar(packageRoot: URL) throws -> URL {
        let tools = packageRoot.appendingPathComponent(".tools")
        let jars = ((try? FileManager.default.contentsOfDirectory(atPath: tools.path)) ?? [])
            .filter { $0.hasPrefix("s3mock-") && $0.hasSuffix("-exec.jar") }
            .sorted()
        if let jar = jars.last {
            return tools.appendingPathComponent(jar)
        }
        // Not fetched yet — fetch it, so a fresh clone still runs green.
        let script = packageRoot.appendingPathComponent("Scripts/fetch-s3mock.sh")
        guard FileManager.default.isExecutableFile(atPath: script.path) else {
            throw StartupFailure(description: "no S3Mock jar in .tools/ and no fetch script")
        }
        let fetch = Process()
        fetch.executableURL = script
        fetch.standardOutput = FileHandle.nullDevice
        try fetch.run()
        fetch.waitUntilExit()
        guard fetch.terminationStatus == 0 else {
            throw StartupFailure(description: "Scripts/fetch-s3mock.sh failed")
        }
        return try locateJar(packageRoot: packageRoot)
    }

    /// Asks the kernel for an unused port, then releases it. A racy but adequate
    /// way to avoid collisions when several packages test at once.
    static func freePort() -> Int {
        let fd = socket(AF_INET, Int32(SOCK_STREAM.rawValue), 0)
        guard fd >= 0 else { return Int.random(in: 20_000...40_000) }
        defer { close(fd) }
        var addr = sockaddr_in()
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = 0
        addr.sin_addr.s_addr = inet_addr("127.0.0.1")
        let bound = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard bound == 0 else { return Int.random(in: 20_000...40_000) }
        var result = sockaddr_in()
        var length = socklen_t(MemoryLayout<sockaddr_in>.size)
        let got = withUnsafeMutablePointer(to: &result) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                getsockname(fd, $0, &length)
            }
        }
        guard got == 0 else { return Int.random(in: 20_000...40_000) }
        return Int(UInt16(bigEndian: result.sin_port))
    }

    static func waitUntilReady(port: Int, timeout: TimeInterval) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            let fd = socket(AF_INET, Int32(SOCK_STREAM.rawValue), 0)
            if fd >= 0 {
                var addr = sockaddr_in()
                addr.sin_family = sa_family_t(AF_INET)
                addr.sin_port = UInt16(port).bigEndian
                addr.sin_addr.s_addr = inet_addr("127.0.0.1")
                let ok = withUnsafePointer(to: &addr) {
                    $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                        connect(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
                    }
                }
                close(fd)
                if ok == 0 { return true }
            }
            Thread.sleep(forTimeInterval: 0.25)
        }
        return false
    }

    func createBucket() throws {
        var request = URLRequest(url: URL(string: "http://127.0.0.1:\(port)/\(bucket)")!)
        request.httpMethod = "PUT"
        let semaphore = DispatchSemaphore(value: 0)
        let task = URLSession.shared.dataTask(with: request) { _, _, _ in semaphore.signal() }
        task.resume()
        _ = semaphore.wait(timeout: .now() + 30)
    }

    // MARK: - Cleanup

    private static let cleanupLock = NSLock()
    nonisolated(unsafe) private static var running: [Process] = []

    static func registerCleanup(_ process: Process) {
        cleanupLock.withLock {
            if running.isEmpty {
                atexit {
                    S3MockServer.cleanupLock.withLock {
                        for p in S3MockServer.running where p.isRunning { p.terminate() }
                    }
                }
            }
            running.append(process)
        }
    }
}

/// Applied to every round-trip suite, so they skip cleanly rather than failing
/// when java or the jar is missing.
func requireS3Mock() throws -> S3MockServer {
    guard let server = S3MockServer.shared else {
        throw SkipS3Mock(reason: S3MockServer.unavailableReason ?? "S3Mock unavailable")
    }
    return server
}

struct SkipS3Mock: Error, CustomStringConvertible {
    let reason: String
    var description: String { reason }
}
