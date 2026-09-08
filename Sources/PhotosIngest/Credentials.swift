import Foundation
import PhotosStorage

/// Where a run gets its endpoint, its library root and its password.
///
/// **Production takes the password from the desktop keyring**, via `secret-tool`. It is the
/// one source that survives a reboot without a dotfile holding the key, and it is what §1
/// specifies.
///
/// **Development overrides it with `PHOTOS_PASSWORD`**, because this repository already keeps
/// the secret in Proton Pass and `proton-env` — which reads `.proton.yaml` and execs with the
/// entries injected — is how every other command here reaches it:
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

    /// Which source answered. The caller says so when it was not the keyring.
    public enum Source: Sendable, Equatable {
        case keyring
        /// `PHOTOS_PASSWORD`, i.e. `proton-env` or a shell — development only.
        case environment
    }

    public struct Password: Sendable {
        public var value: String
        public var source: Source
    }

    public enum Failure: Error, CustomStringConvertible {
        case missingEndpoint
        case missingLibraryRoot
        /// The keyring could not be reached — no session bus, or it is still locked because
        /// nobody has logged in yet. Not a failure: exit 75 and try again next hour.
        case keyringUnavailable(String)
        /// The keyring answered, and holds no such item. That is a real error.
        case noSuchItem
        case toolMissing

        public var description: String {
            switch self {
            case .missingEndpoint:
                "no storage URL: set PHOTOS_ENDPOINT or pass --endpoint"
            case .missingLibraryRoot:
                "no library root: set PHOTOS_LIBRARY_ROOT or pass --library"
            case .keyringUnavailable(let detail):
                "keyring unavailable: \(detail)"
            case .noSuchItem:
                """
                no password in the keyring for service \(Credentials.service). Store one with:
                  secret-tool store --label='photos-cli' service \(Credentials.service)
                For development, run under proton-env instead, which injects PHOTOS_PASSWORD.
                """
            case .toolMissing:
                "secret-tool is not on PATH — install libsecret's tools"
            }
        }
    }

    /// The environment if it has one, the keyring otherwise.
    public static func password(
        environment: [String: String] = ProcessInfo.processInfo.environment,
        lookUpKeyring: () throws -> String = { try secretTool(service: service) }
    ) throws -> Password {
        if let injected = value(environment["PHOTOS_PASSWORD"]) {
            return Password(value: injected, source: .environment)
        }
        return Password(value: try lookUpKeyring(), source: .keyring)
    }

    /// `secret-tool lookup service photos-cli`.
    ///
    /// The two failure modes are told apart by where the noise comes out: a keyring that
    /// simply holds no such item exits nonzero and says nothing, while a keyring that cannot
    /// be reached complains on stderr first. That distinction is what lets an unattended run
    /// before the first login defer quietly instead of paging you every hour.
    public static func secretTool(service: String = Credentials.service) throws -> String {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/env")
        process.arguments = ["secret-tool", "lookup", "service", service]
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
        throw Failure.noSuchItem
    }

    /// The storage URL: host, signing region and zone in one value (§1). Not a secret, so it
    /// is an environment variable in both production and development.
    public static func storage(
        environment: [String: String] = ProcessInfo.processInfo.environment,
        override: String? = nil
    ) throws -> StorageURL {
        guard let raw = value(override ?? environment["PHOTOS_ENDPOINT"]) else {
            throw Failure.missingEndpoint
        }
        return try StorageURL(raw)
    }

    public static func libraryRoot(
        environment: [String: String] = ProcessInfo.processInfo.environment,
        override: String? = nil
    ) throws -> URL {
        guard let raw = value(override ?? environment["PHOTOS_LIBRARY_ROOT"]) else {
            throw Failure.missingLibraryRoot
        }
        return URL(fileURLWithPath: (raw as NSString).expandingTildeInPath)
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
