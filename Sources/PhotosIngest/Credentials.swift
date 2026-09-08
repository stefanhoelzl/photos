import Foundation
import PhotosStorage

/// Where a run gets its endpoint, its library root and its password.
///
/// **Production takes both credentials from the desktop keyring**, read in-process over
/// D-Bus (`SecretService.swift`). Two items under one service, told apart by a `field`
/// attribute, both written by `photos-cli login`:
///
///     photos-cli login
///
/// The endpoint is not a secret — it is a URL — but it is *configuration the run cannot do
/// without*, and putting it beside the password means a working install is one concept
/// rather than a keyring entry plus an exported variable somebody has to remember.
///
/// **Development overrides both from the environment**, because this repository already keeps
/// them in Proton Pass and `proton-env` — which reads `.proton.yaml` and execs with the
/// entries injected — is how every other command here reaches them:
///
///     proton-env photos-cli sync --dry-run
///
/// The environment therefore wins when it is set, which is exactly what an override means. A
/// run that takes that path says so on stderr, so a stale variable silently outranking the
/// keyring is visible rather than an hour of confusion.
///
/// The attributes are the ones `secret-tool store service photos-cli field password` wrote,
/// so items stored before the client existed are found unchanged.
public enum Credentials {

    public static let service = "photos-cli"

    /// The `field` attribute distinguishing the two items under `service photos-cli`.
    public enum Field: String, Sendable, CaseIterable {
        case password
        case endpoint
    }

    /// Which source answered. The caller says so when it was not the keyring.
    public enum Source: Sendable, Equatable {
        case keyring
        /// The environment, i.e. `proton-env` or a shell — development only.
        case environment
    }

    public struct Resolved<Value: Sendable>: Sendable {
        public var value: Value
        public var source: Source
    }

    public enum Failure: Error, CustomStringConvertible {
        case missingLibraryRoot
        /// The keyring could not be reached — no session bus, nothing owning
        /// `org.freedesktop.secrets`, or a collection still locked because nobody has logged
        /// in yet. Not a failure: exit 75 and try again next hour.
        case keyringUnavailable(String)
        /// The keyring answered, and holds no such item. That is a real error.
        case noSuchItem(Field)
        /// The keyring answered with something the Secret Service spec does not allow. Not a
        /// deferral — waiting an hour will not change it — so it aborts like any other
        /// condition that stops a run before it writes.
        case keyringProtocol(String)

        public var description: String {
            switch self {
            case .missingLibraryRoot:
                "not a directory: pass --library-path, or run from inside the library"
            case .keyringUnavailable(let detail):
                "keyring unavailable: \(detail)"
            case .noSuchItem(let field):
                """
                nothing in the keyring for service \(Credentials.service), field \(field.rawValue). \
                Store it with:
                  photos-cli login
                For development, run under proton-env instead, which injects both from Proton Pass.
                """
            case .keyringProtocol(let detail):
                detail
            }
        }
    }

    /// The environment if it has one, the keyring otherwise.
    public static func password(
        environment: [String: String] = ProcessInfo.processInfo.environment,
        lookUpKeyring: (Field) throws -> String = { try keyring($0) }
    ) throws -> Resolved<String> {
        if let injected = value(environment["PHOTOS_PASSWORD"]) {
            return Resolved(value: injected, source: .environment)
        }
        return Resolved(value: try lookUpKeyring(.password), source: .keyring)
    }

    /// The storage URL: host, signing region and zone in one value (§1).
    ///
    /// Resolved exactly like the password — a flag, then the environment, then the keyring —
    /// so there is one rule to remember rather than one per credential.
    public static func storage(
        environment: [String: String] = ProcessInfo.processInfo.environment,
        override: String? = nil,
        lookUpKeyring: (Field) throws -> String = { try keyring($0) }
    ) throws -> Resolved<StorageURL> {
        if let raw = value(override ?? environment["PHOTOS_ENDPOINT"]) {
            return Resolved(value: try StorageURL(raw),
                            source: override == nil ? .environment : .keyring)
        }
        return Resolved(value: try StorageURL(try lookUpKeyring(.endpoint)), source: .keyring)
    }

    /// The library root: the flag if given, otherwise the working directory.
    ///
    /// Defaulting to the cwd pairs with the `.photosignore` marker guard: `photos-cli sync`
    /// typed in the wrong directory finds no marker and refuses, rather than deciding the
    /// library is empty. So the convenient default is also the safe one.
    public static func libraryRoot(
        override: String? = nil,
        workingDirectory: URL = URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
    ) throws -> URL {
        guard let raw = value(override) else { return workingDirectory }
        let expanded = (raw as NSString).expandingTildeInPath
        var isDirectory: ObjCBool = false
        guard FileManager.default.fileExists(atPath: expanded, isDirectory: &isDirectory),
              isDirectory.boolValue else {
            throw Failure.missingLibraryRoot
        }
        return URL(fileURLWithPath: expanded)
    }

    /// An empty variable is an unset one. `proton-env` failing to resolve an entry leaves the
    /// name defined and empty, and a blank secret would otherwise reach the signer and come
    /// back as an opaque 403 — which is exactly the error §1 says must never be opaque.
    static func value(_ raw: String?) -> String? {
        guard let raw else { return nil }
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }

    static func attributes(for field: Field, service: String = Credentials.service)
        -> [(String, String)] {
        [("service", service), ("field", field.rawValue)]
    }

    // MARK: - The keyring

#if os(Linux)

    /// Read one item out of the desktop keyring.
    ///
    /// A locked collection is reported as unavailable rather than unlocked: `Unlock` needs a
    /// graphical prompter, and the case §1 cares about is an hourly timer that has none. The
    /// run defers and the next one succeeds once somebody has logged in.
    public static func keyring(
        _ field: Field,
        service: String = Credentials.service,
        environment: [String: String] = ProcessInfo.processInfo.environment
    ) throws -> String {
        do {
            let bus = try SecretService.connect(environment: environment)
            let found = try SecretService.search(bus, attributes: attributes(for: field,
                                                                            service: service))
            guard let item = found.unlocked.first else {
                if !found.locked.isEmpty { throw SecretService.Failure.locked }
                throw SecretService.Failure.notFound
            }
            let session = try SecretService.openSession(bus)
            let raw = try SecretService.secret(bus, item: item, session: session)
            guard let secret = value(String(decoding: raw, as: UTF8.self)) else {
                throw SecretService.Failure.malformed("the stored \(field.rawValue) is empty")
            }
            return secret
        } catch let error as SecretService.Failure {
            throw translate(error, field: field)
        }
    }

    /// Write both items, replacing whatever is there. Used by `photos-cli login`.
    public static func store(
        password: String,
        endpoint: String,
        service: String = Credentials.service,
        environment: [String: String] = ProcessInfo.processInfo.environment
    ) throws {
        do {
            let bus = try SecretService.connect(environment: environment)
            let session = try SecretService.openSession(bus)
            for (field, secret) in [(Field.password, password), (Field.endpoint, endpoint)] {
                try SecretService.createItem(
                    bus,
                    label: "\(service) \(field.rawValue)",
                    attributes: attributes(for: field, service: service),
                    value: Array(secret.utf8),
                    session: session)
            }
        } catch let error as SecretService.Failure {
            throw translate(error, field: .password)
        }
    }

    /// Remove both items, and say which were actually there. Used by `photos-cli logout`.
    ///
    /// Removing nothing is not an error: the gesture means "make sure they are gone", and
    /// afterwards they are.
    @discardableResult
    public static func remove(
        service: String = Credentials.service,
        environment: [String: String] = ProcessInfo.processInfo.environment
    ) throws -> [Field] {
        do {
            let bus = try SecretService.connect(environment: environment)
            var removed: [Field] = []
            for field in Field.allCases {
                let found = try SecretService.search(bus, attributes: attributes(for: field,
                                                                                service: service))
                if found.unlocked.isEmpty && !found.locked.isEmpty {
                    throw SecretService.Failure.locked
                }
                for item in found.unlocked {
                    try SecretService.delete(bus, item: item)
                }
                if !found.unlocked.isEmpty { removed.append(field) }
            }
            return removed
        } catch let error as SecretService.Failure {
            throw translate(error, field: .password)
        }
    }

    /// The one place the protocol's outcomes become §7's exit codes.
    static func translate(_ error: SecretService.Failure, field: Field) -> Failure {
        switch error {
        case .unavailable(let detail): .keyringUnavailable(detail)
        case .locked: .keyringUnavailable("the keyring is locked; log in and it will unlock")
        case .notFound: .noSuchItem(field)
        case .malformed(let detail): .keyringProtocol(detail)
        }
    }

#else

    /// The desktop keyring is a freedesktop concept. macOS builds of this package exist so
    /// the shared targets can be tested from Xcode; they do not run the ingest CLI.
    public static func keyring(
        _ field: Field,
        service: String = Credentials.service,
        environment: [String: String] = ProcessInfo.processInfo.environment
    ) throws -> String {
        throw Failure.keyringUnavailable("the Secret Service keyring is Linux-only")
    }

#endif
}
