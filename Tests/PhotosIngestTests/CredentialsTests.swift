import Foundation
@testable import PhotosIngest
import Testing

/// Which source answers, and what happens when none does.
///
/// The precedence is a safety property, not a convenience: production takes the key from the
/// keyring, development overrides it with `PHOTOS_PASSWORD` under `proton-env`, and the two
/// must not be able to quietly swap places.
@Suite("Credentials")
struct CredentialsTests {

    @Test("PHOTOS_PASSWORD wins, and says it came from the environment")
    func environmentOverridesTheKeyring() throws {
        let password = try Credentials.password(
            environment: ["PHOTOS_PASSWORD": "from-proton-env"],
            lookUpKeyring: { Issue.record("the keyring must not be consulted"); return "" }
        )
        #expect(password.value == "from-proton-env")
        #expect(password.source == .environment)
    }

    @Test("with no variable set, the keyring answers")
    func keyringIsTheProductionSource() throws {
        let password = try Credentials.password(environment: [:], lookUpKeyring: { "from-keyring" })
        #expect(password.value == "from-keyring")
        #expect(password.source == .keyring)
    }

    /// `proton-env` that cannot resolve an entry leaves the name defined and empty. Treating
    /// that as a password would send a blank secret to the signer and get back an opaque 403
    /// — the one thing §1 says a credential failure must never be.
    @Test("an empty variable is not a password, and falls through to the keyring")
    func emptyVariableIsNotAPassword() throws {
        for blank in ["", "   ", "\n"] {
            let password = try Credentials.password(
                environment: ["PHOTOS_PASSWORD": blank], lookUpKeyring: { "from-keyring" }
            )
            #expect(password.source == .keyring)
        }
    }

    @Test("a keyring that is locked defers rather than failing")
    func lockedKeyringDefers() throws {
        #expect(throws: Credentials.Failure.self) {
            try Credentials.password(
                environment: [:],
                lookUpKeyring: { throw Credentials.Failure.keyringUnavailable("no session bus") }
            )
        }
    }

    @Test("the endpoint comes from the environment, and a flag beats it")
    func endpointResolution() throws {
        let fromEnvironment = try Credentials.storage(
            environment: ["PHOTOS_ENDPOINT": "https://de-s3.storage.bunnycdn.com/my-photos"]
        )
        #expect(fromEnvironment.zone == "my-photos")
        #expect(fromEnvironment.region == "de")

        let fromFlag = try Credentials.storage(
            environment: ["PHOTOS_ENDPOINT": "https://de-s3.storage.bunnycdn.com/my-photos"],
            override: "https://uk-s3.storage.bunnycdn.com/other"
        )
        #expect(fromFlag.zone == "other")

        #expect(throws: Credentials.Failure.self) {
            try Credentials.storage(environment: [:])
        }
    }

    @Test("a tilde in the library root is expanded")
    func libraryRootExpandsTilde() throws {
        let root = try Credentials.libraryRoot(environment: ["PHOTOS_LIBRARY_ROOT": "~/Pictures/Albums"])
        #expect(!root.path.hasPrefix("~"))
        #expect(root.path.hasSuffix("Pictures/Albums"))

        #expect(throws: Credentials.Failure.self) {
            try Credentials.libraryRoot(environment: [:])
        }
    }
}
