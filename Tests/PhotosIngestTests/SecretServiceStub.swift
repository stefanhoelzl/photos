#if os(Linux)
import CDBus
import Foundation
@testable import PhotosIngest

/// A Secret Service implementation for the tests to talk to, on a bus of its own.
///
/// The client is the one part of the credential path that cannot be covered by injecting a
/// closure: everything interesting about it — marshalling, the session, telling a locked
/// collection from a missing item — happens on the wire. So the wire is what the tests drive.
///
/// A private `dbus-daemon` rather than the real session bus, because the stub owns the real
/// well-known name `org.freedesktop.secrets`, and the user's own keyring already owns it on
/// the real bus. It also means these tests can never read or write the developer's actual
/// credentials, which is not a property worth leaving to care.
///
/// `dbus-daemon` is an external binary, exactly as `java` is for `S3Mock`, and it is handled
/// the same way: absent, these tests skip and the rest still run.
final class PrivateBus: @unchecked Sendable {

    let address: String
    private let daemon: Process
    private let configuration: URL

    /// Availability only. Tests take a bus each — see `SecretServiceTests.withStub` — because
    /// a well-known name has exactly one owner, so two stubs on one bus would mean the second
    /// silently answering nothing while the first served stale contents.
    static let unavailableReason: String? =
        (try? PrivateBus()) == nil ? "dbus-daemon could not be started (is dbus installed?)" : nil

    init() throws {
        configuration = FileManager.default.temporaryDirectory
            .appending(path: "photos-stub-bus-\(UUID().uuidString.prefix(8)).conf")
        // Its own socket, its own policy, no services directory: nothing on this bus but the
        // stub and whatever a test connects.
        try """
            <!DOCTYPE busconfig PUBLIC "-//freedesktop//DTD D-BUS Bus Configuration 1.0//EN"
             "http://www.freedesktop.org/standards/dbus/1.0/busconfig.dtd">
            <busconfig>
              <type>session</type>
              <listen>unix:tmpdir=/tmp</listen>
              <policy context="default">
                <allow send_destination="*"/>
                <allow own="*"/>
                <allow receive_sender="*"/>
              </policy>
            </busconfig>
            """.write(to: configuration, atomically: true, encoding: .utf8)

        daemon = Process()
        daemon.executableURL = URL(fileURLWithPath: "/usr/bin/env")
        daemon.arguments = ["dbus-daemon", "--config-file=\(configuration.path)",
                            "--print-address", "--nofork"]
        let output = Pipe()
        daemon.standardOutput = output
        daemon.standardError = FileHandle.nullDevice
        try daemon.run()

        // The daemon prints its address on the first line and then keeps running.
        var collected = Data()
        while !collected.contains(UInt8(ascii: "\n")) {
            let chunk = output.fileHandleForReading.availableData
            if chunk.isEmpty { throw StubError("dbus-daemon exited without printing an address") }
            collected.append(chunk)
        }
        let line = String(decoding: collected, as: UTF8.self)
            .split(separator: "\n").first.map(String.init) ?? ""
        guard !line.isEmpty else { throw StubError("dbus-daemon printed no address") }
        address = line
    }

    deinit {
        daemon.terminate()
        try? FileManager.default.removeItem(at: configuration)
    }

    /// What `Credentials` should see instead of the developer's real session bus.
    var environment: [String: String] { ["DBUS_SESSION_BUS_ADDRESS": address] }
}

struct StubError: Error, CustomStringConvertible {
    let description: String
    init(_ description: String) { self.description = description }
}

/// The stub itself: owns `org.freedesktop.secrets` on a `PrivateBus` and answers the five
/// methods the client uses.
///
/// Runs its dispatch loop on a thread of its own, because the client's calls block waiting for
/// replies — a single-threaded stub would deadlock against the code it exists to test.
final class SecretServiceStub: @unchecked Sendable {

    struct Item {
        var attributes: [String: String]
        var secret: [UInt8]
        var locked: Bool
    }

    private let connection: OpaquePointer
    private let lock = NSLock()
    private var items: [String: Item] = [:]
    private var nextItem = 1
    private var running = true
    private var thread: Thread?

    /// Set to make every method fail, for the "keyring is there but broken" cases.
    var failEverything = false

    init(bus: PrivateBus) throws {
        var error = DBusError()
        dbus_error_init(&error)
        defer { dbus_error_free(&error) }

        guard let connection = dbus_connection_open_private(bus.address, &error) else {
            throw StubError("stub cannot reach the private bus")
        }
        dbus_connection_set_exit_on_disconnect(connection, 0)
        guard dbus_bus_register(connection, &error) != 0 else {
            throw StubError("stub cannot register on the private bus")
        }
        // DBUS_NAME_FLAG_DO_NOT_QUEUE, and DBUS_REQUEST_NAME_REPLY_PRIMARY_OWNER. Spelled out
        // for the same reason the type codes are.
        //
        // Primary ownership exactly, not merely "no error": being queued behind another owner
        // returns success and then answers nothing, which presents as one test reading the
        // previous test's secrets.
        let owned = dbus_bus_request_name(connection, SecretService.destination, 4, &error)
        guard owned == 1 else {
            throw StubError("stub did not get \(SecretService.destination) (reply \(owned))")
        }
        self.connection = connection

        let thread = Thread { [weak self] in self?.loop() }
        thread.name = "secret-service-stub"
        thread.start()
        self.thread = thread
    }

    deinit {
        lock.lock(); running = false; lock.unlock()
        // The loop wakes at least every 50 ms, so it has left by the time the socket closes.
        Thread.sleep(forTimeInterval: 0.1)
        dbus_connection_close(connection)
        dbus_connection_unref(connection)
    }

    // MARK: - Contents

    @discardableResult
    func store(field: Credentials.Field, secret: String, locked: Bool = false,
               service: String = Credentials.service) -> String {
        lock.lock(); defer { lock.unlock() }
        let path = "/org/freedesktop/secrets/collection/stub/\(nextItem)"
        nextItem += 1
        items[path] = Item(attributes: ["service": service, "field": field.rawValue],
                           secret: Array(secret.utf8), locked: locked)
        return path
    }

    var storedPaths: [String] {
        lock.lock(); defer { lock.unlock() }
        return items.keys.sorted()
    }

    func secret(at path: String) -> String? {
        lock.lock(); defer { lock.unlock() }
        return items[path].map { String(decoding: $0.secret, as: UTF8.self) }
    }

    /// The item carrying this field, if any — how tests assert what `login` wrote.
    func item(for field: Credentials.Field) -> Item? {
        lock.lock(); defer { lock.unlock() }
        return items.values.first { $0.attributes["field"] == field.rawValue }
    }

    // MARK: - Dispatch

    private func loop() {
        while true {
            lock.lock(); let keepGoing = running; lock.unlock()
            guard keepGoing else { return }

            dbus_connection_read_write(connection, 50)
            while let message = dbus_connection_pop_message(connection) {
                handle(message)
                dbus_message_unref(message)
            }
        }
    }

    private func handle(_ message: OpaquePointer) {
        guard dbus_message_get_type(message) == 1 else { return }  // METHOD_CALL
        let member = dbus_message_get_member(message).map { String(cString: $0) } ?? ""
        let path = dbus_message_get_path(message).map { String(cString: $0) } ?? ""

        lock.lock(); let broken = failEverything; lock.unlock()
        if broken {
            reply(to: message) { _ in }   // a return with no arguments: well-formed, wrong shape
            return
        }

        switch member {
        case "OpenSession":
            reply(to: message) { iterator in
                var output = DBusMessageIter()
                dbus_message_iter_open_container(iterator, SecretService.typeVariant, "s", &output)
                SecretService.append(&output, SecretService.typeString, "")
                dbus_message_iter_close_container(iterator, &output)
                SecretService.append(iterator, SecretService.typeObjectPath,
                                     "/org/freedesktop/secrets/session/stub")
            }

        case "SearchItems":
            let wanted = readAttributes(message)
            lock.lock()
            let matches = items.filter { _, item in
                wanted.allSatisfy { item.attributes[$0.key] == $0.value }
            }
            let unlocked = matches.filter { !$0.value.locked }.keys.sorted()
            let locked = matches.filter { $0.value.locked }.keys.sorted()
            lock.unlock()
            reply(to: message) { iterator in
                appendPaths(iterator, unlocked)
                appendPaths(iterator, locked)
            }

        case "GetSecret":
            lock.lock(); let item = items[path]; lock.unlock()
            guard let item, !item.locked else {
                replyError(to: message, "org.freedesktop.Secret.Error.NoSuchObject", "no such item")
                return
            }
            reply(to: message) { iterator in
                var secret = DBusMessageIter()
                dbus_message_iter_open_container(iterator, SecretService.typeStruct, nil, &secret)
                SecretService.append(&secret, SecretService.typeObjectPath,
                                     "/org/freedesktop/secrets/session/stub")
                SecretService.appendBytes(&secret, [])
                SecretService.appendBytes(&secret, item.secret)
                SecretService.append(&secret, SecretService.typeString, "text/plain; charset=utf8")
                dbus_message_iter_close_container(iterator, &secret)
            }

        case "CreateItem":
            let (attributes, secret) = readCreateItem(message)
            lock.lock()
            // `replace: true` is what the client sends, and what makes storing twice an
            // update rather than two items SearchItems returns in an undefined order.
            let existing = items.first { $0.value.attributes == attributes }?.key
            let itemPath = existing ?? "/org/freedesktop/secrets/collection/stub/\(nextItem)"
            if existing == nil { nextItem += 1 }
            items[itemPath] = Item(attributes: attributes, secret: secret, locked: false)
            lock.unlock()
            reply(to: message) { iterator in
                SecretService.append(iterator, SecretService.typeObjectPath, itemPath)
                SecretService.append(iterator, SecretService.typeObjectPath, "/")
            }

        case "Delete":
            lock.lock(); items[path] = nil; lock.unlock()
            reply(to: message) { iterator in
                SecretService.append(iterator, SecretService.typeObjectPath, "/")
            }

        default:
            replyError(to: message, "org.freedesktop.DBus.Error.UnknownMethod", member)
        }
    }

    // MARK: - Message plumbing

    private func reply(to message: OpaquePointer,
                       _ arguments: (UnsafeMutablePointer<DBusMessageIter>) -> Void) {
        guard let response = dbus_message_new_method_return(message) else { return }
        defer { dbus_message_unref(response) }
        var iterator = DBusMessageIter()
        dbus_message_iter_init_append(response, &iterator)
        arguments(&iterator)
        dbus_connection_send(connection, response, nil)
        dbus_connection_flush(connection)
    }

    private func replyError(to message: OpaquePointer, _ name: String, _ detail: String) {
        guard let response = dbus_message_new_error(message, name, detail) else { return }
        defer { dbus_message_unref(response) }
        dbus_connection_send(connection, response, nil)
        dbus_connection_flush(connection)
    }

    private func appendPaths(_ iterator: UnsafeMutablePointer<DBusMessageIter>, _ paths: [String]) {
        var array = DBusMessageIter()
        dbus_message_iter_open_container(iterator, SecretService.typeArray, "o", &array)
        for path in paths { SecretService.append(&array, SecretService.typeObjectPath, path) }
        dbus_message_iter_close_container(iterator, &array)
    }

    private func readAttributes(_ message: OpaquePointer) -> [String: String] {
        var iterator = DBusMessageIter()
        guard dbus_message_iter_init(message, &iterator) != 0 else { return [:] }
        return Self.readStringDictionary(&iterator)
    }

    /// CreateItem is `(a{sv} properties, (oayays) secret, b replace)`.
    private func readCreateItem(_ message: OpaquePointer) -> ([String: String], [UInt8]) {
        var iterator = DBusMessageIter()
        guard dbus_message_iter_init(message, &iterator) != 0 else { return ([:], []) }

        var attributes: [String: String] = [:]
        var properties = DBusMessageIter()
        dbus_message_iter_recurse(&iterator, &properties)
        while dbus_message_iter_get_arg_type(&properties) != SecretService.typeInvalid {
            var entry = DBusMessageIter()
            dbus_message_iter_recurse(&properties, &entry)
            let key = SecretService.string(&entry)
            if dbus_message_iter_next(&entry) != 0 {
                var variant = DBusMessageIter()
                dbus_message_iter_recurse(&entry, &variant)
                if key == "org.freedesktop.Secret.Item.Attributes" {
                    attributes = Self.readStringDictionary(&variant)
                }
            }
            dbus_message_iter_next(&properties)
        }

        guard dbus_message_iter_next(&iterator) != 0 else { return (attributes, []) }
        var secret = DBusMessageIter()
        dbus_message_iter_recurse(&iterator, &secret)
        guard dbus_message_iter_next(&secret) != 0, dbus_message_iter_next(&secret) != 0 else {
            return (attributes, [])
        }
        return (attributes, SecretService.bytes(&secret))
    }

    static func readStringDictionary(_ iterator: UnsafeMutablePointer<DBusMessageIter>)
        -> [String: String] {
        guard dbus_message_iter_get_arg_type(iterator) == SecretService.typeArray else { return [:] }
        var pairs: [String: String] = [:]
        var entries = DBusMessageIter()
        dbus_message_iter_recurse(iterator, &entries)
        while dbus_message_iter_get_arg_type(&entries) != SecretService.typeInvalid {
            var pair = DBusMessageIter()
            dbus_message_iter_recurse(&entries, &pair)
            if let key = SecretService.string(&pair), dbus_message_iter_next(&pair) != 0,
               let value = SecretService.string(&pair) {
                pairs[key] = value
            }
            dbus_message_iter_next(&entries)
        }
        return pairs
    }
}
#endif
