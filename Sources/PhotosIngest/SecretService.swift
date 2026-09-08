#if os(Linux)
import CDBus
import Foundation

/// The freedesktop Secret Service, spoken directly over D-Bus.
///
/// This replaces the `secret-tool` subprocess §7 used to admit as the one thing the binary
/// could not do for itself. libdbus-1 is linked statically (`Scripts/PROVENANCE.md`), and
/// unlike libsecret it does not drag glib in — so "no glib" survives while the shell-out goes.
///
/// **The connection is opened on an explicit address, never `dbus_bus_get`.** libdbus falls
/// back to `autolaunch:` when it has no address, which forks `dbus-launch` — an executable on
/// `PATH`, i.e. exactly the dependency this file exists to remove. A missing address is
/// treated as "no bus", which is a deferral, not a failure.
///
/// **Sessions are opened `plain`.** The secret crosses a peer-credential-authenticated
/// `AF_UNIX` socket inside the caller's own `$XDG_RUNTIME_DIR`; the encrypted algorithm would
/// add a DH exchange and an AES-CBC decrypt to the credential path, and a wrong decrypt
/// surfaces as the opaque 403 §1 forbids. The threat `plain` does not stop — a process running
/// as this uid — can simply ask the keyring itself.
///
/// **Locked items are never unlocked.** `Unlock` needs a graphical prompter, which an hourly
/// timer does not have; §1's accepted cost is that a run before the first login defers.
enum SecretService {

    // MARK: - Names

    static let destination = "org.freedesktop.secrets"
    static let servicePath = "/org/freedesktop/secrets"
    static let serviceInterface = "org.freedesktop.Secret.Service"
    static let collectionInterface = "org.freedesktop.Secret.Collection"
    static let itemInterface = "org.freedesktop.Secret.Item"
    static let defaultCollection = "/org/freedesktop/secrets/aliases/default"

    /// No reply within this and the run defers rather than hanging. An hourly unit that
    /// blocks forever on a wedged keyring holds the run lock and stops every later firing
    /// too, which is a worse failure than not syncing this hour.
    static let timeoutMilliseconds: Int32 = 10_000

    // MARK: - Type codes
    //
    // Spelled out rather than imported: libdbus writes these as `((int) 's')` cast
    // expressions, which the C importer does not reliably fold into Swift constants.

    static let typeInvalid: Int32 = 0
    static let typeByte = Int32(UInt8(ascii: "y"))
    static let typeBoolean = Int32(UInt8(ascii: "b"))
    static let typeString = Int32(UInt8(ascii: "s"))
    static let typeObjectPath = Int32(UInt8(ascii: "o"))
    static let typeArray = Int32(UInt8(ascii: "a"))
    static let typeVariant = Int32(UInt8(ascii: "v"))
    static let typeStruct = Int32(UInt8(ascii: "r"))
    static let typeDictEntry = Int32(UInt8(ascii: "e"))

    // MARK: - Errors

    /// Why a lookup did not produce a secret.
    ///
    /// The split matters: `unavailable` and `locked` are deferrals (exit 75) and `notFound`
    /// is a real error (exit 3). Under `secret-tool` these were told apart by whether the
    /// subprocess had written to stderr; here they are distinct outcomes of the protocol.
    enum Failure: Error, CustomStringConvertible {
        /// No bus address, no connection, no such service, or no reply in time.
        case unavailable(String)
        /// The keyring answered and the item exists, but the collection is locked.
        case locked
        /// The keyring answered and holds no such item.
        case notFound
        /// The keyring answered with something the spec does not allow.
        case malformed(String)

        var description: String {
            switch self {
            case .unavailable(let detail): detail
            case .locked: "the keyring is locked"
            case .notFound: "no such item"
            case .malformed(let detail): "unexpected reply from the keyring: \(detail)"
            }
        }
    }

    // MARK: - Connection

    /// One connection, closed when the caller is done with it.
    ///
    /// Private rather than shared: a shared connection is reference-counted process-wide and
    /// outlives the lookup, and this process wants the socket gone once the password is read.
    final class Bus {
        let raw: OpaquePointer

        init(address: String) throws {
            var error = DBusError()
            dbus_error_init(&error)
            defer { dbus_error_free(&error) }

            guard let connection = dbus_connection_open_private(address, &error) else {
                throw Failure.unavailable(Bus.detail(&error, fallback: "cannot reach \(address)"))
            }
            // A keyring that restarts must not take the sync with it. libdbus's default is to
            // _exit() the process when the bus drops, which for an hourly unit would look
            // like a crash rather than the deferral it is.
            dbus_connection_set_exit_on_disconnect(connection, 0)

            // Required before any traffic: the bus daemon assigns this connection its unique
            // name. A peer-to-peer connection (the test stub) answers Hello itself.
            guard dbus_bus_register(connection, &error) != 0 else {
                dbus_connection_close(connection)
                dbus_connection_unref(connection)
                throw Failure.unavailable(Bus.detail(&error, fallback: "cannot register on \(address)"))
            }
            self.raw = connection
        }

        deinit {
            dbus_connection_close(raw)
            dbus_connection_unref(raw)
        }

        static func detail(_ error: UnsafeMutablePointer<DBusError>, fallback: String) -> String {
            guard dbus_error_is_set(error) != 0, let message = error.pointee.message else {
                return fallback
            }
            return String(cString: message)
        }
    }

    /// `$DBUS_SESSION_BUS_ADDRESS`, else the well-known socket, else nothing.
    ///
    /// Both are checked because a systemd user unit inherits the variable while a plain login
    /// shell may not, and `$XDG_RUNTIME_DIR/bus` is where every current session puts it.
    static func sessionBusAddress(
        environment: [String: String] = ProcessInfo.processInfo.environment
    ) -> String? {
        if let explicit = environment["DBUS_SESSION_BUS_ADDRESS"], !explicit.isEmpty {
            return explicit
        }
        if let runtime = environment["XDG_RUNTIME_DIR"], !runtime.isEmpty {
            let socket = runtime + "/bus"
            if FileManager.default.fileExists(atPath: socket) { return "unix:path=\(socket)" }
        }
        return nil
    }

    static func connect(
        environment: [String: String] = ProcessInfo.processInfo.environment
    ) throws -> Bus {
        guard let address = sessionBusAddress(environment: environment) else {
            throw Failure.unavailable(
                "no session bus: DBUS_SESSION_BUS_ADDRESS is unset and $XDG_RUNTIME_DIR/bus does not exist")
        }
        return try Bus(address: address)
    }

    // MARK: - Calls

    /// One blocking method call. The reply is owned by the caller and unref'd by it.
    static func call(
        _ bus: Bus,
        path: String,
        interface: String,
        method: String,
        appending arguments: (UnsafeMutablePointer<DBusMessageIter>) -> Void = { _ in }
    ) throws -> OpaquePointer {
        guard let message = dbus_message_new_method_call(destination, path, interface, method) else {
            throw Failure.unavailable("out of memory building \(method)")
        }
        defer { dbus_message_unref(message) }

        var iterator = DBusMessageIter()
        dbus_message_iter_init_append(message, &iterator)
        arguments(&iterator)

        var error = DBusError()
        dbus_error_init(&error)
        defer { dbus_error_free(&error) }

        guard let reply = dbus_connection_send_with_reply_and_block(
            bus.raw, message, timeoutMilliseconds, &error) else {
            let detail = Bus.detail(&error, fallback: "\(method) failed")
            // ServiceUnknown means nothing owns org.freedesktop.secrets: no keyring is
            // running, which is the same "not now" as no bus at all.
            throw Failure.unavailable(detail)
        }
        return reply
    }

    /// `OpenSession("plain", "")` — the session every secret is read and written through.
    static func openSession(_ bus: Bus) throws -> String {
        let reply = try call(bus, path: servicePath, interface: serviceInterface,
                             method: "OpenSession") { iterator in
            append(iterator, typeString, "plain")
            // The input is a variant even for `plain`, where it carries nothing.
            var variant = DBusMessageIter()
            dbus_message_iter_open_container(iterator, typeVariant, "s", &variant)
            append(&variant, typeString, "")
            dbus_message_iter_close_container(iterator, &variant)
        }
        defer { dbus_message_unref(reply) }

        var iterator = DBusMessageIter()
        guard dbus_message_iter_init(reply, &iterator) != 0 else {
            throw Failure.malformed("OpenSession returned nothing")
        }
        // Skip the algorithm-negotiation output variant; `plain` carries nothing in it.
        guard dbus_message_iter_next(&iterator) != 0,
              dbus_message_iter_get_arg_type(&iterator) == typeObjectPath,
              let session = string(&iterator) else {
            throw Failure.malformed("OpenSession returned no session path")
        }
        return session
    }

    /// `SearchItems({attributes})` on the service, which searches every collection.
    static func search(_ bus: Bus, attributes: [(String, String)]) throws
        -> (unlocked: [String], locked: [String]) {
        let reply = try call(bus, path: servicePath, interface: serviceInterface,
                             method: "SearchItems") { iterator in
            appendStringDictionary(iterator, attributes)
        }
        defer { dbus_message_unref(reply) }

        var iterator = DBusMessageIter()
        guard dbus_message_iter_init(reply, &iterator) != 0 else {
            throw Failure.malformed("SearchItems returned nothing")
        }
        let unlocked = objectPaths(&iterator)
        guard dbus_message_iter_next(&iterator) != 0 else {
            throw Failure.malformed("SearchItems returned no locked array")
        }
        let locked = objectPaths(&iterator)
        return (unlocked, locked)
    }

    /// `GetSecret(session)` on one item, returning the raw value.
    static func secret(_ bus: Bus, item: String, session: String) throws -> [UInt8] {
        let reply = try call(bus, path: item, interface: itemInterface,
                             method: "GetSecret") { iterator in
            append(iterator, typeObjectPath, session)
        }
        defer { dbus_message_unref(reply) }

        var iterator = DBusMessageIter()
        guard dbus_message_iter_init(reply, &iterator) != 0,
              dbus_message_iter_get_arg_type(&iterator) == typeStruct else {
            throw Failure.malformed("GetSecret did not return a Secret struct")
        }
        // Secret is (o session, ay parameters, ay value, s content_type); only value matters.
        var fields = DBusMessageIter()
        dbus_message_iter_recurse(&iterator, &fields)
        guard dbus_message_iter_next(&fields) != 0, dbus_message_iter_next(&fields) != 0 else {
            throw Failure.malformed("Secret struct is too short")
        }
        return bytes(&fields)
    }

    /// `CreateItem` on the default collection, replacing any item with the same attributes.
    ///
    /// Replace rather than add: the attributes are the identity of the item here, so storing
    /// twice must update rather than leave two items that `SearchItems` returns in an order
    /// nothing defines.
    static func createItem(
        _ bus: Bus,
        label: String,
        attributes: [(String, String)],
        value: [UInt8],
        session: String,
        collection: String = defaultCollection
    ) throws {
        let reply = try call(bus, path: collection, interface: collectionInterface,
                             method: "CreateItem") { iterator in
            var properties = DBusMessageIter()
            dbus_message_iter_open_container(iterator, typeArray, "{sv}", &properties)

            var labelEntry = DBusMessageIter()
            dbus_message_iter_open_container(&properties, typeDictEntry, nil, &labelEntry)
            append(&labelEntry, typeString, "org.freedesktop.Secret.Item.Label")
            var labelValue = DBusMessageIter()
            dbus_message_iter_open_container(&labelEntry, typeVariant, "s", &labelValue)
            append(&labelValue, typeString, label)
            dbus_message_iter_close_container(&labelEntry, &labelValue)
            dbus_message_iter_close_container(&properties, &labelEntry)

            var attributeEntry = DBusMessageIter()
            dbus_message_iter_open_container(&properties, typeDictEntry, nil, &attributeEntry)
            append(&attributeEntry, typeString, "org.freedesktop.Secret.Item.Attributes")
            var attributeValue = DBusMessageIter()
            dbus_message_iter_open_container(&attributeEntry, typeVariant, "a{ss}", &attributeValue)
            appendStringDictionary(&attributeValue, attributes)
            dbus_message_iter_close_container(&attributeEntry, &attributeValue)
            dbus_message_iter_close_container(&properties, &attributeEntry)

            dbus_message_iter_close_container(iterator, &properties)

            // Secret is (o session, ay parameters, ay value, s content_type). `plain` leaves
            // parameters empty.
            var secret = DBusMessageIter()
            dbus_message_iter_open_container(iterator, typeStruct, nil, &secret)
            append(&secret, typeObjectPath, session)
            appendBytes(&secret, [])
            appendBytes(&secret, value)
            append(&secret, typeString, "text/plain; charset=utf8")
            dbus_message_iter_close_container(iterator, &secret)

            var replace: dbus_bool_t = 1
            dbus_message_iter_append_basic(iterator, typeBoolean, &replace)
        }
        defer { dbus_message_unref(reply) }

        // (o item, o prompt). A prompt means the collection is locked, and §1 never prompts.
        var iterator = DBusMessageIter()
        guard dbus_message_iter_init(reply, &iterator) != 0,
              dbus_message_iter_next(&iterator) != 0,
              let prompt = string(&iterator) else {
            throw Failure.malformed("CreateItem returned no prompt path")
        }
        if prompt != "/" { throw Failure.locked }
    }

    /// `Delete()` on one item.
    static func delete(_ bus: Bus, item: String) throws {
        let reply = try call(bus, path: item, interface: itemInterface, method: "Delete")
        defer { dbus_message_unref(reply) }

        var iterator = DBusMessageIter()
        guard dbus_message_iter_init(reply, &iterator) != 0, let prompt = string(&iterator) else {
            throw Failure.malformed("Delete returned no prompt path")
        }
        if prompt != "/" { throw Failure.locked }
    }

    // MARK: - Marshalling helpers

    static func append(_ iterator: UnsafeMutablePointer<DBusMessageIter>,
                       _ type: Int32, _ value: String) {
        value.withCString { text in
            var pointer: UnsafePointer<CChar>? = text
            dbus_message_iter_append_basic(iterator, type, &pointer)
        }
    }

    static func appendBytes(_ iterator: UnsafeMutablePointer<DBusMessageIter>, _ value: [UInt8]) {
        var array = DBusMessageIter()
        dbus_message_iter_open_container(iterator, typeArray, "y", &array)
        if !value.isEmpty {
            value.withUnsafeBufferPointer { buffer in
                var base = buffer.baseAddress
                dbus_message_iter_append_fixed_array(&array, typeByte, &base, Int32(buffer.count))
            }
        }
        dbus_message_iter_close_container(iterator, &array)
    }

    static func appendStringDictionary(_ iterator: UnsafeMutablePointer<DBusMessageIter>,
                                       _ pairs: [(String, String)]) {
        var array = DBusMessageIter()
        dbus_message_iter_open_container(iterator, typeArray, "{ss}", &array)
        for (key, value) in pairs {
            var entry = DBusMessageIter()
            dbus_message_iter_open_container(&array, typeDictEntry, nil, &entry)
            append(&entry, typeString, key)
            append(&entry, typeString, value)
            dbus_message_iter_close_container(&array, &entry)
        }
        dbus_message_iter_close_container(iterator, &array)
    }

    static func string(_ iterator: UnsafeMutablePointer<DBusMessageIter>) -> String? {
        var pointer: UnsafePointer<CChar>?
        dbus_message_iter_get_basic(iterator, &pointer)
        return pointer.map { String(cString: $0) }
    }

    static func objectPaths(_ iterator: UnsafeMutablePointer<DBusMessageIter>) -> [String] {
        guard dbus_message_iter_get_arg_type(iterator) == typeArray else { return [] }
        var element = DBusMessageIter()
        dbus_message_iter_recurse(iterator, &element)
        var paths: [String] = []
        while dbus_message_iter_get_arg_type(&element) != typeInvalid {
            if let path = string(&element) { paths.append(path) }
            dbus_message_iter_next(&element)
        }
        return paths
    }

    static func bytes(_ iterator: UnsafeMutablePointer<DBusMessageIter>) -> [UInt8] {
        guard dbus_message_iter_get_arg_type(iterator) == typeArray else { return [] }
        var element = DBusMessageIter()
        dbus_message_iter_recurse(iterator, &element)
        guard dbus_message_iter_get_arg_type(&element) == typeByte else { return [] }
        var base: UnsafePointer<UInt8>?
        var count: Int32 = 0
        withUnsafeMutablePointer(to: &base) { pointer in
            dbus_message_iter_get_fixed_array(&element, UnsafeMutableRawPointer(pointer), &count)
        }
        guard let base, count > 0 else { return [] }
        return Array(UnsafeBufferPointer(start: base, count: Int(count)))
    }
}
#endif
