#if os(Linux)
import Foundation
@testable import PhotosIngest
import Testing

/// The keyring client against a real bus.
///
/// These cover what the `lookUpKeyring` seam in `CredentialsTests` deliberately cannot: the
/// marshalling, the session, and — the part that decides whether an unattended run pages
/// somebody at 3 a.m. — telling a keyring that is not there from one that answers and holds
/// nothing.
@Suite("Secret Service client", .serialized)
struct SecretServiceTests {

    /// One bus and one stub per test, so nothing leaks between them.
    ///
    /// A bus each rather than one shared one: `org.freedesktop.secrets` has a single owner, so
    /// a second stub on the same bus would be queued behind the first and answer nothing while
    /// the first kept serving the previous test's contents.
    private func withStub(_ body: (SecretServiceStub, [String: String]) throws -> Void) throws {
        guard PrivateBus.unavailableReason == nil else { return }
        let bus = try PrivateBus()
        let stub = try SecretServiceStub(bus: bus)
        try body(stub, bus.environment)
    }

    @Test("a stored password comes back over the wire",
          .enabled(if: PrivateBus.unavailableReason == nil))
    func readsAStoredSecret() throws {
        try withStub { stub, environment in
            stub.store(field: .password, secret: "hunter2")
            let password = try Credentials.keyring(.password, environment: environment)
            #expect(password == "hunter2")
        }
    }

    /// Two items live under one service, so a lookup naming only the service could get
    /// either. This is the wire-level version of the same assertion `CredentialsTests` makes
    /// against the seam.
    @Test("each field finds its own item",
          .enabled(if: PrivateBus.unavailableReason == nil))
    func fieldsAreToldApart() throws {
        try withStub { stub, environment in
            stub.store(field: .password, secret: "the-password")
            stub.store(field: .endpoint, secret: "https://de-s3.storage.bunnycdn.com/zone")
            let password = try Credentials.keyring(.password, environment: environment)
            let endpoint = try Credentials.keyring(.endpoint, environment: environment)
            #expect(password == "the-password")
            #expect(endpoint == "https://de-s3.storage.bunnycdn.com/zone")
        }
    }

    /// The distinction §1 rests on. A keyring that answers and holds nothing is a real error
    /// (exit 3); everything else about the keyring is a deferral (exit 75). Getting these two
    /// the wrong way round either pages somebody hourly or hides a broken install forever.
    @Test("an empty keyring is not found, not unavailable",
          .enabled(if: PrivateBus.unavailableReason == nil))
    func missingItemIsARealError() throws {
        try withStub { _, environment in
            #expect(throws: Credentials.Failure.self) {
                try Credentials.keyring(.password, environment: environment)
            }
            do {
                _ = try Credentials.keyring(.password, environment: environment)
            } catch let error as Credentials.Failure {
                guard case .noSuchItem(let field) = error else {
                    Issue.record("expected noSuchItem, got \(error)"); return
                }
                #expect(field == .password)
            }
        }
    }

    @Test("a locked item defers rather than failing",
          .enabled(if: PrivateBus.unavailableReason == nil))
    func lockedItemDefers() throws {
        try withStub { stub, environment in
            stub.store(field: .password, secret: "hunter2", locked: true)
            do {
                _ = try Credentials.keyring(.password, environment: environment)
                Issue.record("a locked keyring must not return a secret")
            } catch let error as Credentials.Failure {
                guard case .keyringUnavailable = error else {
                    Issue.record("a locked keyring must defer, got \(error)"); return
                }
            }
        }
    }

    /// No bus at all is the state of a machine that has booted but nobody has logged into.
    @Test("no session bus defers")
    func noBusDefers() throws {
        do {
            _ = try Credentials.keyring(.password, environment: ["XDG_RUNTIME_DIR": "/nonexistent"])
            Issue.record("a missing bus must not return a secret")
        } catch let error as Credentials.Failure {
            guard case .keyringUnavailable = error else {
                Issue.record("a missing bus must defer, got \(error)"); return
            }
        }
    }

    @Test("login stores both items and logout removes them",
          .enabled(if: PrivateBus.unavailableReason == nil))
    func storeAndRemoveRoundTrip() throws {
        try withStub { stub, environment in
            try Credentials.store(password: "hunter2",
                                  endpoint: "https://de-s3.storage.bunnycdn.com/zone",
                                  environment: environment)
            #expect(stub.item(for: .password).map { String(decoding: $0.secret, as: UTF8.self) }
                    == "hunter2")
            let endpoint = try Credentials.keyring(.endpoint, environment: environment)
            #expect(endpoint == "https://de-s3.storage.bunnycdn.com/zone")

            let removed = try Credentials.remove(environment: environment)
            #expect(Set(removed) == [.password, .endpoint])
            #expect(stub.storedPaths.isEmpty)
        }
    }

    /// Storing twice must update, not leave two items that `SearchItems` returns in an order
    /// nothing defines — which would make the password a coin flip.
    @Test("storing twice replaces rather than duplicating",
          .enabled(if: PrivateBus.unavailableReason == nil))
    func storingTwiceReplaces() throws {
        try withStub { stub, environment in
            try Credentials.store(password: "first", endpoint: "https://host/zone",
                                  environment: environment)
            try Credentials.store(password: "second", endpoint: "https://host/zone",
                                  environment: environment)
            #expect(stub.storedPaths.count == 2)
            let password = try Credentials.keyring(.password, environment: environment)
            #expect(password == "second")
        }
    }

    /// Decision: removing nothing is not an error. The gesture means "make sure they are
    /// gone", and afterwards they are.
    @Test("logout on an empty keyring removes nothing and succeeds",
          .enabled(if: PrivateBus.unavailableReason == nil))
    func removeOnEmptyKeyringSucceeds() throws {
        try withStub { _, environment in
            let removed = try Credentials.remove(environment: environment)
            #expect(removed.isEmpty)
        }
    }

    /// A reply the spec does not allow is not something an hour will fix, so it aborts (3)
    /// rather than deferring (75).
    @Test("a malformed reply aborts rather than deferring",
          .enabled(if: PrivateBus.unavailableReason == nil))
    func malformedReplyAborts() throws {
        try withStub { stub, environment in
            stub.store(field: .password, secret: "hunter2")
            stub.failEverything = true
            do {
                _ = try Credentials.keyring(.password, environment: environment)
                Issue.record("a malformed reply must not produce a secret")
            } catch let error as Credentials.Failure {
                guard case .keyringProtocol = error else {
                    Issue.record("expected keyringProtocol, got \(error)"); return
                }
            }
        }
    }
}
#endif
