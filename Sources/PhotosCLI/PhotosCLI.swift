import ArgumentParser
import Foundation
import PhotosIngest
import PhotosStorage

@main
struct PhotosCLI: AsyncParsableCommand {
    static let configuration = CommandConfiguration(
        commandName: "photos-cli",
        abstract: "Keeps a bunny.net storage zone in step with a local photo library.",
        discussion: """
            The library is the master copy and the only way to say anything. Adding a folder \
            adds an album; deleting a file deletes its photo; `rm -rf` on an album deletes \
            the album. There is no confirmation step — `--dry-run` is the one place to look \
            before it happens.

            A run refuses to touch anything unless $LIBRARY_ROOT/.photosignore exists and is \
            readable. That file is what says the directory really is the library, so an \
            unmounted disk or a mistyped root cannot be mistaken for a library whose every \
            album was deleted. A library that wants no exclusions writes an empty one.
            """,
        version: "1.0.0",
        subcommands: [Sync.self]
    )
}

extension PhotosCLI {

    struct Sync: AsyncParsableCommand {
        static let configuration = CommandConfiguration(
            abstract: "Reconcile the zone with the library."
        )

        @Option(name: .customLong("library"),
                help: "The library root. Defaults to $PHOTOS_LIBRARY_ROOT.")
        var library: String?

        @Option(name: .customLong("endpoint"),
                help: "Storage URL, e.g. https://de-s3.storage.bunnycdn.com/my-photos. Defaults to $PHOTOS_ENDPOINT.")
        var endpoint: String?

        @Option(name: .customLong("cache-dir"),
                help: "Where shards and ETags are cached. Defaults to $XDG_CACHE_HOME/photos-cli.")
        var cacheDir: String?

        @Option(help: "Encoder workers. Budget ~400 MB each.")
        var jobs: Int = ProcessInfo.processInfo.activeProcessorCount

        @Option(name: .customLong("upload-jobs"),
                help: "Upload connections. One is fastest on a domestic link; more measured slower.")
        var uploadJobs: Int = 1

        @Option(name: .customLong("album"),
                help: "Only albums whose path contains this. Scopes deletions and pulls too.")
        var albumFilter: String?

        @Flag(name: .customLong("dry-run"), help: "Print the plan and change nothing.")
        var dryRun = false

        func run() async throws {
            let console = Console()
            do {
                try await execute(console)
            } catch let error as Credentials.Failure {
                switch error {
                case .missingEndpoint, .missingLibraryRoot:
                    console.error(error.description)
                    throw ExitCode(PhotosIngest.ExitCode.usage)
                case .keyringUnavailable:
                    // Not a failure: nobody has logged in yet, so gnome-keyring is still
                    // locked. H's unit declares SuccessExitStatus=75 so this stays out of
                    // OnFailure=, and the next hourly run succeeds.
                    console.line("deferred: \(error.description)")
                    throw ExitCode(PhotosIngest.ExitCode.deferred)
                case .noSuchItem, .toolMissing:
                    // A keyring that answered and holds no such item is a real error — but
                    // one that stopped the run before it wrote anything, which is what
                    // exit 3 says.
                    console.error(error.description)
                    throw ExitCode(PhotosIngest.ExitCode.aborted)
                }
            } catch let error as IngestAbort {
                console.error("aborted: \(error.description)")
                console.error("nothing was written")
                throw ExitCode(PhotosIngest.ExitCode.aborted)
            }
        }

        private func execute(_ console: Console) async throws {
            let root = try Credentials.libraryRoot(override: library)
            let storage = try Credentials.storage(override: endpoint)
            let password = try Credentials.password()
            // Only ever true under `proton-env` or a shell that exported it. Said out loud
            // so a stale variable outranking the keyring is visible rather than an hour of
            // wondering why the wrong zone is being written.
            if password.source == .environment {
                console.error("using PHOTOS_PASSWORD from the environment (development)")
            }

            let config = IngestConfig(
                libraryRoot: root,
                cacheRoot: cacheDir.map { URL(fileURLWithPath: $0) }
                    ?? IngestConfig.defaultCacheRoot(),
                workRoot: IngestConfig.newWorkRoot(),
                jobs: jobs,
                uploadJobs: uploadJobs,
                albumFilter: albumFilter,
                dryRun: dryRun
            )

            let client = S3Client(storage: storage, secretAccessKey: password.value)
            // The per-album line is written as the album commits, not buffered to the end:
            // a 20-hour first run has to be watchable, and in the journal as it happens.
            let ingest = try Ingest(config: config, s3: client) { line in
                FileHandle.standardOutput.write(Data((line + "\n").utf8))
            }

            let report = try await ingest.run()
            console.clearProgress()
            Reporter(console: console).render(report)

            if report.hasProblems {
                throw ExitCode(PhotosIngest.ExitCode.completedWithFailures)
            }
        }
    }
}
