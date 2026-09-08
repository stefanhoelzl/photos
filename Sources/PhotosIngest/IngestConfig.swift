import Foundation

/// Everything one run needs to know, resolved before it starts.
///
/// A value type rather than a pile of parameters so `PhotosCLI` stays what §7 says it is —
/// argument parsing and wiring — and every rule that matters is reachable from a test that
/// never builds a command line.
public struct IngestConfig: Sendable {

    /// The master copy. The run is read-only on it, apart from pulling down albums the
    /// laptop has never owned (§7).
    public var libraryRoot: URL
    /// Holds `sync_state.db` and `shards/`. Derived and deletable: losing it costs one
    /// re-fetch of every shard.
    public var cacheRoot: URL
    /// Where derivatives are staged before upload. Removed when the run ends.
    public var workRoot: URL

    /// Encoder workers. One per core, measured at 0.43 s/photo wall on 16 (§7).
    public var jobs: Int
    /// Upload connections. **One**, because §9 measured 1.9 MB/s on one stream against
    /// 1.5 MB/s on eight — parallel uploads are slower, not faster. The knob is here for a
    /// different link, not for this one.
    public var uploadJobs: Int

    /// Restricts the run to albums whose source path contains this, case-insensitively.
    /// It scopes deletions and pulls as well as uploads: a scoped run that deleted
    /// everything outside its scope would be a trap.
    public var albumFilter: String?
    /// Plan and print, change nothing. The only safety surface there is (§7).
    public var dryRun: Bool

    /// How old an unreferenced blob must be before the sweep may delete it.
    ///
    /// Seven days is not a guess: presigned URLs live at most 7 days (§1) and §8's
    /// background uploads run against them, so a blob older than that cannot belong to an
    /// upload that can still complete. Below it, an unreferenced blob is indistinguishable
    /// from one the phone is uploading right now.
    public var sweepAge: TimeInterval

    public init(
        libraryRoot: URL,
        cacheRoot: URL,
        workRoot: URL,
        jobs: Int = ProcessInfo.processInfo.activeProcessorCount,
        uploadJobs: Int = 1,
        albumFilter: String? = nil,
        dryRun: Bool = false,
        sweepAge: TimeInterval = 7 * 24 * 60 * 60
    ) {
        self.libraryRoot = libraryRoot
        self.cacheRoot = cacheRoot
        self.workRoot = workRoot
        self.jobs = max(1, jobs)
        self.uploadJobs = max(1, uploadJobs)
        self.albumFilter = albumFilter
        self.dryRun = dryRun
        self.sweepAge = sweepAge
    }

    /// The default cache location: `$XDG_CACHE_HOME/photos-cli`, which is also what
    /// systemd's `CacheDirectory=` produces.
    public static func defaultCacheRoot(
        environment: [String: String] = ProcessInfo.processInfo.environment
    ) -> URL {
        if let explicit = environment["PHOTOS_CACHE_DIR"], !explicit.isEmpty {
            return URL(fileURLWithPath: explicit)
        }
        let base = environment["XDG_CACHE_HOME"].flatMap { $0.isEmpty ? nil : $0 }
            .map { URL(fileURLWithPath: $0) }
            ?? FileManager.default.homeDirectoryForCurrentUser.appending(path: ".cache")
        return base.appending(path: "photos-cli")
    }

    /// A fresh staging directory under `$TMPDIR`. One per run, so debris from a run that
    /// died is a directory a tmp-cleaner can see rather than something to reason about.
    public static func newWorkRoot(
        environment: [String: String] = ProcessInfo.processInfo.environment
    ) -> URL {
        let base = environment["TMPDIR"].flatMap { $0.isEmpty ? nil : $0 }
            .map { URL(fileURLWithPath: $0) } ?? URL(fileURLWithPath: "/tmp")
        return base.appending(path: "photos-cli-\(UUID().uuidString.prefix(8))")
    }
}

/// The conditions under which a run refuses to change anything (exit 3).
///
/// All three mean the same thing: the library is not in the state the catalog was built
/// from, and proceeding would write something irreversible on a false premise.
public enum IngestAbort: Error, CustomStringConvertible {

    /// No readable `$LIBRARY_ROOT/.photosignore`.
    ///
    /// The file is the marker that says "this directory is a library root", which is what
    /// makes unattended deletion safe: an unmounted disk is a bare mount point, and a
    /// mistyped root is somebody else's directory — neither has one. A library that wants
    /// no exclusions writes an empty file.
    case notALibraryRoot(URL, detail: String)

    /// A file's size no longer matches the row that describes it. §7 asserts images never
    /// change on disk; a mismatch means the library broke that contract, so nothing is
    /// written and a person is told, rather than the file being silently re-ingested.
    case fileChanged([ByteMismatch])

    /// A shard too new to read whose two stable columns could not be read either, so the
    /// folder it claims is unknowable. Continuing would risk uploading that folder as a
    /// second album (§3).
    case unidentifiableShard(String)

    public var description: String {
        switch self {
        case .notALibraryRoot(let url, let detail):
            "\(url.path) is not a library root: \(detail)"
        case .fileChanged(let mismatches):
            "\(mismatches.count) file(s) changed on disk since they were ingested: "
            + mismatches.prefix(3).map(\.description).joined(separator: "; ")
        case .unidentifiableShard(let detail):
            "a shard could not be identified: \(detail)"
        }
    }
}

/// A file whose size disagrees with the row describing it.
public struct ByteMismatch: Hashable, Sendable, CustomStringConvertible {
    public var albumPath: String
    public var filename: String
    public var recorded: Int64
    public var found: Int64

    public var description: String {
        "\(albumPath)/\(filename): catalog says \(recorded) bytes, disk has \(found)"
    }
}

/// Process exit codes (§7).
///
/// `1` and `3` are both failures to `OnFailure=`, but they say different things to a person
/// reading the journal: `1` means the run finished and the zone changed, `3` means it
/// refused to start and the zone is untouched.
public enum ExitCode {
    public static let clean: Int32 = 0
    public static let completedWithFailures: Int32 = 1
    public static let usage: Int32 = 2
    public static let aborted: Int32 = 3
    /// `EX_TEMPFAIL`. The keyring is locked because nobody has logged in yet — not a
    /// failure, just not now. H's unit sets `SuccessExitStatus=75` so it stays quiet.
    ///
    /// Reachable only on the production path. A development run takes `PHOTOS_PASSWORD`
    /// from the environment and never consults a keyring, so it can never defer.
    public static let deferred: Int32 = 75
}
