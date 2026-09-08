import Foundation
import PhotosCatalog
import PhotosLibrary

/// What one run did, and what it could not make sense of.
///
/// §4's posture, applied to the CLI: a run reports what it could not resolve rather than
/// guessing. Everything here is either a fact about the zone or a condition the design
/// deliberately does not auto-correct.
public struct IngestReport: Sendable {

    public struct AlbumOutcome: Sendable {
        public var path: String
        public var uploaded: Int
        public var dropped: Int
        public var bytes: Int64
        public var created: Bool
        public var duration: TimeInterval
    }

    public struct Failure: Sendable {
        public var path: String
        public var message: String
    }

    public var albums: [AlbumOutcome] = []
    public var deletedAlbums: [(path: String, photos: Int)] = []
    public var pulledAlbums: [(path: String, files: Int, bytes: Int64)] = []

    /// Files the pipeline could not handle. Each keeps its album out of nothing: the album
    /// commits without it and it is retried next run, because a file with no row reads as
    /// new (§7).
    public var failures: [Failure] = []
    /// Files the classifier did not recognise as media.
    public var strays: [Failure] = []
    /// Loose files sitting in a directory that also holds sub-albums (§2's XOR rule).
    public var mixedFolders: [(path: String, files: Int)] = []
    /// Directories the library root holds directly. Never ingested.
    public var looseRootFiles: Int = 0
    /// Albums left alone because their shard is newer than this build.
    public var blockedByUnreadable: [ShardProbe] = []
    /// Albums that could not be written because another device wrote them twice running.
    public var contendedAlbums: [String] = []
    /// Two albums under one parent with the same name. Both shown, never merged (§2).
    public var duplicateNames: [String] = []
    /// A `parent` that resolves to no shard. The album surfaces at the top level (§2).
    public var orphanedAlbums: [UUID] = []

    public var sweptBlobs: Int = 0
    public var sweptBytes: Int64 = 0
    /// Unreferenced but younger than the sweep's age floor, so possibly still uploading.
    public var youngUnreferencedBlobs: Int = 0
    public var sweepSkipped: String?

    /// `.photosignore` rules that excluded nothing — a typo and a rule not yet needed look
    /// identical otherwise (§7).
    public var unusedRules: [IgnoreRules.Rule] = []
    public var ignoredFiles: Int = 0

    public var listedShards: Int = 0
    public var fetchedShards: Int = 0
    public var dryRun: Bool = false

    public var uploadedFiles: Int { albums.reduce(0) { $0 + $1.uploaded } }
    public var uploadedBytes: Int64 { albums.reduce(0) { $0 + $1.bytes } }
    public var droppedRows: Int { albums.reduce(0) { $0 + $1.dropped } }

    /// Whether anything needs a person. Drives the exit code, and therefore `OnFailure=`.
    public var hasProblems: Bool {
        !failures.isEmpty || !contendedAlbums.isEmpty || !mixedFolders.isEmpty
            || !blockedByUnreadable.isEmpty || looseRootFiles > 0
    }

    public init() {}
}
