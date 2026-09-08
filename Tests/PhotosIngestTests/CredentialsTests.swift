import Foundation
@testable import PhotosIngest
import Testing

/// Which source answers, and what happens when none does.
///
/// The precedence is a safety property, not a convenience: production takes both credentials
/// from the keyring, development overrides them from the environment under `proton-env`, and
/// the two must not be able to quietly swap places.
@Suite("Credentials")
struct CredentialsTests {

    @Test("PHOTOS_PASSWORD wins, and says it came from the environment")
    func environmentOverridesTheKeyring() throws {
        let password = try Credentials.password(
            environment: ["PHOTOS_PASSWORD": "from-proton-env"],
            lookUpKeyring: { _ in Issue.record("the keyring must not be consulted"); return "" }
        )
        #expect(password.value == "from-proton-env")
        #expect(password.source == .environment)
    }

    @Test("with no variable set, the keyring answers")
    func keyringIsTheProductionSource() throws {
        let password = try Credentials.password(environment: [:],
                                                lookUpKeyring: { _ in "from-keyring" })
        #expect(password.value == "from-keyring")
        #expect(password.source == .keyring)
    }

    /// Two items live under one service, so a lookup that named only the service could get
    /// either one. Each credential must ask for its own field by name.
    @Test("the password and the endpoint ask the keyring for different fields")
    func eachCredentialNamesItsField() throws {
        var asked: [Credentials.Field] = []
        _ = try Credentials.password(environment: [:], lookUpKeyring: { field in
            asked.append(field); return "secret"
        })
        _ = try Credentials.storage(environment: [:], lookUpKeyring: { field in
            asked.append(field)
            return "https://de-s3.storage.bunnycdn.com/my-photos"
        })
        #expect(asked == [.password, .endpoint])
    }

    /// `proton-env` that cannot resolve an entry leaves the name defined and empty. Treating
    /// that as a password would send a blank secret to the signer and get back an opaque 403
    /// — the one thing §1 says a credential failure must never be.
    @Test("an empty variable is not a password, and falls through to the keyring")
    func emptyVariableIsNotAPassword() throws {
        for blank in ["", "   ", "\n"] {
            let password = try Credentials.password(
                environment: ["PHOTOS_PASSWORD": blank], lookUpKeyring: { _ in "from-keyring" }
            )
            #expect(password.source == .keyring)
        }
    }

    @Test("a keyring that is locked defers rather than failing")
    func lockedKeyringDefers() throws {
        #expect(throws: Credentials.Failure.self) {
            try Credentials.password(
                environment: [:],
                lookUpKeyring: { _ in throw Credentials.Failure.keyringUnavailable("no session bus") }
            )
        }
    }

    @Test("the endpoint resolves flag, then environment, then keyring")
    func endpointResolution() throws {
        let keyring = "https://de-s3.storage.bunnycdn.com/from-keyring"
        let fromKeyring = try Credentials.storage(environment: [:], lookUpKeyring: { _ in keyring })
        #expect(fromKeyring.value.zone == "from-keyring")
        #expect(fromKeyring.source == .keyring)

        let fromEnvironment = try Credentials.storage(
            environment: ["PHOTOS_ENDPOINT": "https://de-s3.storage.bunnycdn.com/my-photos"],
            lookUpKeyring: { _ in keyring }
        )
        #expect(fromEnvironment.value.zone == "my-photos")
        #expect(fromEnvironment.value.region == "de")
        #expect(fromEnvironment.source == .environment)

        let fromFlag = try Credentials.storage(
            environment: ["PHOTOS_ENDPOINT": "https://de-s3.storage.bunnycdn.com/my-photos"],
            override: "https://uk-s3.storage.bunnycdn.com/other",
            lookUpKeyring: { _ in keyring }
        )
        #expect(fromFlag.value.zone == "other")
    }

    /// The default pairs with the marker guard: typed in the wrong directory it finds no
    /// `.photosignore` and refuses, rather than deciding the library is empty.
    @Test("the library root defaults to the working directory")
    func libraryRootDefaultsToCWD() throws {
        let cwd = URL(fileURLWithPath: NSTemporaryDirectory())
        let root = try Credentials.libraryRoot(override: nil, workingDirectory: cwd)
        #expect(root == cwd)
    }

    @Test("--library-path expands a tilde, and must name a directory that exists")
    func libraryPathIsChecked() throws {
        let home = try Credentials.libraryRoot(override: "~")
        #expect(!home.path.hasPrefix("~"))

        #expect(throws: Credentials.Failure.self) {
            try Credentials.libraryRoot(override: "/definitely/not/here")
        }
    }
}
