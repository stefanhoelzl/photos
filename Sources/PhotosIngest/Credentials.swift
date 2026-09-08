import Foundation
import PhotosStorage

/// Where a run gets its endpoint, its library root and its password.
///
/// **Production takes both credentials from the desktop keyring**, via `secret-tool`. Two
/// items under one service, told apart by a `field` attribute:
///
///     secret-tool store --label='photos-cli password' service photos-cli field password
///     secret-tool store --label='photos-cli endpoint' service photos-cli field endpoint
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
/// libsecret itself is not linked, and `secret-tool` is shelled out to instead. Reaching the
/// keyring in-process would drag meson, libffi, PCRE2, proxy-libintl, libgcrypt and
/// libgpg-error into both build prefixes for about 8 MB — and glib `dlopen`s its GIO modules,
/// which is a stub that always fails in the static musl binary §7 ships.
public enum Credentials {

    public static let service = "photos-cli"

    /// The `field` attribute distinguishing the two items under `service photos-cli`.
    public enum Field: String, Sendable {
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
        /// The keyring could not be reached — no session bus, or it is still locked because
        /// nobody has logged in yet. Not a failure: exit 75 and try again next hour.
        case keyringUnavailable(String)
        /// The keyring answered, and holds no such item. That is a real error.
        case noSuchItem(Field)
        case toolMissing

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
                  secret-tool store --label='photos-cli \(field.rawValue)' \
                    service \(Credentials.service) field \(field.rawValue)
                For development, run under proton-env instead, which injects both from Proton Pass.
                """
            case .toolMissing:
                "secret-tool is not on PATH — install libsecret's tools"
            }
        }
    }

    /// The environment if it has one, the keyring otherwise.
    public static func password(
        environment: [String: String] = ProcessInfo.processInfo.environment,
        lookUpKeyring: (Field) throws -> String = { try secretTool(field: $0) }
    ) throws -> Resolved<String> {
        if let injected = value(environment["PHOTOS_PASSWORD"]) {
            return Resolved(value: injected, source: .environment)
        }
        return Resolved(value: try lookUpKeyring(.password), source: .keyring)
    }

    /// `secret-tool lookup service photos-cli field <field>`.
    ///
    /// The two failure modes are told apart by where the noise comes out: a keyring that
    /// simply holds no such item exits nonzero and says nothing, while a keyring that cannot
    /// be reached complains on stderr first. That distinction is what lets an unattended run
    /// before the first login defer quietly instead of paging you every hour.
    public static func secretTool(field: Field,
                                  service: String = Credentials.service) throws -> String {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/env")
        // Both attributes, always: `service` alone matches either item, and which one it
        // returns is not defined.
        process.arguments = ["secret-tool", "lookup",
                             "service", service, "field", field.rawValue]
        let out = Pipe()
        let err = Pipe()
        process.standardOutput = out
        process.standardError = err

        do {
            try process.run()
        } catch {
            throw Failure.toolMissing
        }
        let stdout = out.fileHandleForReading.readDataToEndOfFile()
        let stderr = err.fileHandleForReading.readDataToEndOfFile()
        process.waitUntilExit()

        if process.terminationStatus == 0, let password = value(String(decoding: stdout, as: UTF8.self)) {
            return password
        }
        if process.terminationStatus == 127 { throw Failure.toolMissing }
        if let complaint = value(String(decoding: stderr, as: UTF8.self)) {
            throw Failure.keyringUnavailable(complaint)
        }
        throw Failure.noSuchItem(field)
    }

    /// The storage URL: host, signing region and zone in one value (§1).
    ///
    /// Resolved exactly like the password — a flag, then the environment, then the keyring —
    /// so there is one rule to remember rather than one per credential.
    public static func storage(
        environment: [String: String] = ProcessInfo.processInfo.environment,
        override: String? = nil,
        lookUpKeyring: (Field) throws -> String = { try secretTool(field: $0) }
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
}
