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
        subcommands: [Sync.self, Login.self, Logout.self]
    )
}

extension PhotosCLI {

    /// Put both credentials in the desktop keyring.
    ///
    /// §7's "one verb, and nothing else" is about the *library* being the only way to say
    /// anything about photos — no `delete`, no `--prune`, no pending state. This verb and its
    /// counterpart never touch the library or the zone; they exist because reading the
    /// keyring in-process would otherwise leave setup still needing `secret-tool` on PATH,
    /// which is the dependency the client was written to remove.
    struct Login: AsyncParsableCommand {
        static let configuration = CommandConfiguration(
            abstract: "Store the endpoint and password in the desktop keyring.",
            discussion: """
                Both values are stored under service \(Credentials.service), told apart by a \
                `field` attribute, and replace whatever is already there. Nothing is written \
                to disk, to the environment, or to your shell history.
                """
        )

        @Option(name: .customLong("endpoint"),
                help: "Storage URL. Prompted for when not given.")
        var endpoint: String?

        func run() async throws {
            let console = Console()
            guard let endpoint = endpoint
                ?? console.ask("Storage URL (e.g. https://de-s3.storage.bunnycdn.com/my-photos): "),
                  !endpoint.isEmpty else {
                console.error("no endpoint given")
                throw ExitCode(PhotosIngest.ExitCode.usage)
            }
            // Parsed before it is stored: a mistyped URL that only fails on the next sync is
            // exactly the opaque error §1 refuses, and this is the one moment it is cheap
            // to catch.
            let storage = try StorageURL(endpoint)

            guard let password = console.ask("Password: ", secret: true), !password.isEmpty else {
                console.error("no password given")
                throw ExitCode(PhotosIngest.ExitCode.usage)
            }

            do {
                try Credentials.store(password: password, endpoint: endpoint)
            } catch let error as Credentials.Failure {
                throw keyringExit(error, console)
            }
            console.line("stored the endpoint and password for zone \(storage.zone)")
        }
    }

    /// Take both credentials back out again.
    struct Logout: AsyncParsableCommand {
        static let configuration = CommandConfiguration(
            abstract: "Remove the endpoint and password from the desktop keyring.",
            discussion: """
                Removes both items, because they are one concept: half an install is neither \
                working nor clean. Removing nothing is not an error — the gesture means make \
                sure they are gone, and afterwards they are.
                """
        )

        func run() async throws {
            let console = Console()
            let removed: [Credentials.Field]
            do {
                removed = try Credentials.remove()
            } catch let error as Credentials.Failure {
                throw keyringExit(error, console)
            }
            console.line(removed.isEmpty
                ? "nothing stored for service \(Credentials.service)"
                : "removed " + removed.map(\.rawValue).joined(separator: " and "))
        }
    }
}

/// Credential failures as §7's exit codes. One copy, so all three verbs agree.
private func keyringExit(_ error: Credentials.Failure,
                         _ console: Console) -> ArgumentParser.ExitCode {
    switch error {
    case .keyringUnavailable:
        // Not a failure: no session bus yet, or nobody has logged in so gnome-keyring is
        // still locked. H's unit declares SuccessExitStatus=75 so this stays out of
        // OnFailure=, and the next hourly run succeeds.
        console.line("deferred: \(error.description)")
        return ExitCode(PhotosIngest.ExitCode.deferred)
    case .missingLibraryRoot:
        console.error(error.description)
        return ExitCode(PhotosIngest.ExitCode.usage)
    case .noSuchItem, .keyringProtocol:
        // A keyring that answered and holds no such item is a real error — but one that
        // stopped the run before it wrote anything, which is what exit 3 says. A reply the
        // spec does not allow is the same: waiting an hour will not change it.
        console.error(error.description)
        return ExitCode(PhotosIngest.ExitCode.aborted)
    }
}

extension PhotosCLI {

    struct Sync: AsyncParsableCommand {
        static let configuration = CommandConfiguration(
            abstract: "Reconcile the zone with the library."
        )

        @Option(name: .customLong("library-path"),
                help: "The library root. Defaults to the working directory.")
        var libraryPath: String?

        @Option(name: .customLong("endpoint"),
                help: "Storage URL, e.g. https://de-s3.storage.bunnycdn.com/my-photos. Defaults to the keyring.")
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
                throw keyringExit(error, console)
            } catch let error as RunLock.Busy {
                // Not a failure: the sync is happening, just not this one. Exit 75 so the
                // hourly unit overlapping a long manual run stays out of OnFailure=.
                console.line("deferred: \(error.description)")
                throw ExitCode(PhotosIngest.ExitCode.deferred)
            } catch let error as IngestAbort {
                console.error("aborted: \(error.description)")
                console.error("nothing was written")
                throw ExitCode(PhotosIngest.ExitCode.aborted)
            }
        }

        private func execute(_ console: Console) async throws {
            let root = try Credentials.libraryRoot(override: libraryPath)
            let storage = try Credentials.storage(override: endpoint)
            let password = try Credentials.password()
            // Only ever true under `proton-env` or a shell that exported them. Said out loud
            // so a stale variable outranking the keyring is visible rather than an hour of
            // wondering why the wrong zone is being written.
            let injected = [
                storage.source == .environment ? "PHOTOS_ENDPOINT" : nil,
                password.source == .environment ? "PHOTOS_PASSWORD" : nil,
            ].compactMap { $0 }
            if !injected.isEmpty {
                console.error("using \(injected.joined(separator: " and ")) "
                              + "from the environment (development)")
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

            // Taken before anything is read or written, and held by the process until it
            // exits however it exits.
            let lock = try RunLock.acquire(in: config.cacheRoot)
            defer { _ = lock }

            let client = S3Client(storage: storage.value, secretAccessKey: password.value)
            // Written as it happens, never buffered to the end: a 39-hour run has to be
            // watchable, and legible in the journal while it is still going.
            let ingest = try Ingest(config: config, s3: client) { event in
                switch event {
                case .planned(let albums, let files, let bytes, let deletions, let pulls):
                    var parts: [String] = []
                    if albums > 0 { parts.append("\(albums) album(s), \(files) photos, \(formatBytes(bytes))") }
                    if deletions > 0 { parts.append("\(deletions) album(s) to delete") }
                    if pulls > 0 { parts.append("\(pulls) album(s) to pull") }
                    console.line(parts.isEmpty ? "nothing to do" : "to do: " + parts.joined(separator: ", "))
                case .line(let text):
                    console.clearProgress()
                    console.line(text)
                case .status(let text):
                    console.progress(text)
                }
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
