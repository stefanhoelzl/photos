import Foundation
#if canImport(Glibc)
import Glibc
#endif

/// Stops two syncs running at once.
///
/// The hourly unit and a run you type yourself will otherwise overlap the moment an import
/// takes longer than an hour — which the first one will, at 39 hours. Two runs derive and
/// upload the same files, then fight over the same shards: `If-Match` keeps the catalog
/// consistent, but the loser's blobs are already in the zone with nothing pointing at them,
/// so the cost is a week of paying for debris and twice the upload on a link that is the
/// bottleneck to begin with.
///
/// The lock is `flock` on a file in the cache directory, because the cache is what two runs
/// would actually corrupt: `sync_state.db` and `shards/`. It is advisory and process-scoped,
/// so the kernel releases it however the run ends — including `kill -9`, a panic, or a laptop
/// losing power. Nothing to clean up, and no stale lock file to explain to anyone.
///
/// > Two runs pointed at the same library but *different* cache directories would still
/// > collide. That is not defended against: it needs `--cache-dir` to be passed deliberately,
/// > and a rule that guessed at "same library" from a path would be wrong the first time a
/// > symlink appeared.
public final class RunLock: @unchecked Sendable {

    /// Another run holds the lock.
    ///
    /// Not an error the operator should be paged about: the sync is happening, just not this
    /// one. The CLI exits 75 so `SuccessExitStatus=75` keeps `OnFailure=` quiet.
    public struct Busy: Error, CustomStringConvertible {
        /// The pid the holder wrote, when it could be read.
        public var holder: Int32?
        public var path: String

        public var description: String {
            if let holder {
                "another sync is already running (pid \(holder))"
            } else {
                "another sync is already running (lock: \(path))"
            }
        }
    }

    private let descriptor: Int32
    public let path: URL

    private init(descriptor: Int32, path: URL) {
        self.descriptor = descriptor
        self.path = path
    }

    /// Takes the lock, or throws `Busy` at once. Never waits: a run that queued behind
    /// another would start the moment it finished, with a plan built from a library it
    /// re-walked anyway, and the hourly timer will come round again regardless.
    public static func acquire(in cacheRoot: URL) throws -> RunLock {
        try FileManager.default.createDirectory(at: cacheRoot, withIntermediateDirectories: true)
        let path = cacheRoot.appending(path: "lock")

        let descriptor = open(path.path, O_RDWR | O_CREAT | O_CLOEXEC, 0o644)
        guard descriptor >= 0 else {
            throw Busy(holder: nil, path: path.path)
        }
        guard flock(descriptor, LOCK_EX | LOCK_NB) == 0 else {
            let holder = readPID(descriptor)
            close(descriptor)
            throw Busy(holder: holder, path: path.path)
        }

        // Our pid, so the next contender can name who has it. Written after the lock is
        // held, so what is in the file is always the holder's.
        ftruncate(descriptor, 0)
        let pid = "\(getpid())\n"
        _ = pid.withCString { text in
            pwrite(descriptor, text, strlen(text), 0)
        }
        return RunLock(descriptor: descriptor, path: path)
    }

    private static func readPID(_ descriptor: Int32) -> Int32? {
        var buffer = [CChar](repeating: 0, count: 32)
        let count = pread(descriptor, &buffer, 31, 0)
        guard count > 0 else { return nil }
        return Int32(String(cString: buffer).trimmingCharacters(in: .whitespacesAndNewlines))
    }

    /// Releasing is closing: `flock` is tied to the descriptor, so the kernel does it for us
    /// whatever happens to the process.
    deinit { close(descriptor) }
}
