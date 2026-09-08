import Foundation
@testable import PhotosIngest
import Testing

/// One sync at a time.
///
/// It matters because of the arithmetic: the first import is about 39 hours and the unit
/// fires hourly, so the second run overlaps the first thirty-eight times unless something
/// stops it.
@Suite("Run lock")
struct RunLockTests {

    func temporaryCache() throws -> URL {
        let url = URL(fileURLWithPath: NSTemporaryDirectory())
            .appending(path: "photos-lock-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    @Test("a second run is refused while the first holds it")
    func secondRunIsRefused() throws {
        let cache = try temporaryCache()
        defer { try? FileManager.default.removeItem(at: cache) }

        let first = try RunLock.acquire(in: cache)
        #expect(throws: RunLock.Busy.self) {
            _ = try RunLock.acquire(in: cache)
        }
        _ = first
    }

    @Test("the refusal names the process holding it")
    func refusalNamesTheHolder() throws {
        let cache = try temporaryCache()
        defer { try? FileManager.default.removeItem(at: cache) }

        let first = try RunLock.acquire(in: cache)
        do {
            _ = try RunLock.acquire(in: cache)
            Issue.record("expected the second acquire to be refused")
        } catch let busy as RunLock.Busy {
            #expect(busy.holder == ProcessInfo.processInfo.processIdentifier)
            #expect(busy.description.contains("already running"))
        }
        _ = first
    }

    /// Releasing is closing, so nothing has to be cleaned up — which is the point of using
    /// `flock` rather than a pid file. A run killed with -9 leaves no stale lock.
    @Test("the lock is released when the holder goes away")
    func lockIsReleasedOnRelease() throws {
        let cache = try temporaryCache()
        defer { try? FileManager.default.removeItem(at: cache) }

        do {
            let first = try RunLock.acquire(in: cache)
            _ = first
        }
        // The file is still there; that is not what the lock is.
        #expect(FileManager.default.fileExists(atPath: cache.appending(path: "lock").path))
        let second = try RunLock.acquire(in: cache)
        _ = second
    }

    @Test("different cache directories do not contend")
    func differentCachesAreIndependent() throws {
        let a = try temporaryCache()
        let b = try temporaryCache()
        defer {
            try? FileManager.default.removeItem(at: a)
            try? FileManager.default.removeItem(at: b)
        }
        let first = try RunLock.acquire(in: a)
        let second = try RunLock.acquire(in: b)
        _ = (first, second)
    }

    @Test("the cache directory is created if it is not there yet")
    func createsTheCacheDirectory() throws {
        let parent = try temporaryCache()
        defer { try? FileManager.default.removeItem(at: parent) }
        let cache = parent.appending(path: "not-yet")

        let lock = try RunLock.acquire(in: cache)
        #expect(FileManager.default.fileExists(atPath: cache.path))
        _ = lock
    }
}
