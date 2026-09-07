import CSQLite
import Foundation

/// The errors SQLite can hand back, with enough context to name the statement.
public struct SQLiteError: Error, Hashable, Sendable, CustomStringConvertible {
    public let code: Int32
    public let message: String
    /// The SQL that failed, when the failure came from preparing or stepping one.
    public let sql: String?

    public var description: String {
        if let sql { "sqlite error \(code): \(message) — in: \(sql)" } else { "sqlite error \(code): \(message)" }
    }
}

/// One SQLite connection.
///
/// Deliberately a `final class` and deliberately **not** `Sendable`: a connection is
/// owned by exactly one task. §3's access model is one writer plus independent readers,
/// so sharing a handle across tasks is never the right answer — the writer lives inside
/// `CatalogWriter`'s actor isolation, and each reader opens its own.
///
/// The wrapper is thin on purpose. It exists to make `sqlite3_*` calls throw and to bind
/// values without repeating index arithmetic; it is not a query builder, and the SQL in
/// this module is written out in full so it can be read.
public final class Database {

    let handle: OpaquePointer

    /// Where the file lives, or `nil` for an in-memory database.
    public let path: String?

    public struct OpenOptions: Sendable {
        public var readOnly: Bool
        /// WAL is required rather than preferred: §4 rebuilds inside one 1–3 s write
        /// transaction while the album list may be on screen, and only WAL lets readers
        /// keep the pre-transaction snapshot for its duration instead of blocking.
        public var walMode: Bool
        public var foreignKeys: Bool

        public init(readOnly: Bool = false, walMode: Bool = true, foreignKeys: Bool = true) {
            self.readOnly = readOnly
            self.walMode = walMode
            self.foreignKeys = foreignKeys
        }

        public static let `default` = OpenOptions()
        /// For shards, which are whole files held in memory and never journalled.
        public static let inMemory = OpenOptions(walMode: false, foreignKeys: false)
    }

    public init(path: String?, options: OpenOptions = .default) throws {
        var handle: OpaquePointer?
        var flags = options.readOnly ? SQLITE_OPEN_READONLY : (SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE)
        flags |= SQLITE_OPEN_NOMUTEX      // one connection per task; no shared-handle serialising
        let target = path ?? ":memory:"
        let rc = sqlite3_open_v2(target, &handle, flags, nil)
        guard rc == SQLITE_OK, let handle else {
            let message = handle.map { String(cString: sqlite3_errmsg($0)) } ?? "unable to open \(target)"
            if let handle { sqlite3_close_v2(handle) }
            throw SQLiteError(code: rc, message: message, sql: nil)
        }
        self.handle = handle
        self.path = path

        // A blocked writer waits rather than failing instantly. Contention here is one
        // ingest run against one UI, not a server, so a short wait always beats an error.
        sqlite3_busy_timeout(handle, 5_000)

        do {
            if options.walMode, path != nil { try execute("PRAGMA journal_mode = WAL") }
            if options.foreignKeys { try execute("PRAGMA foreign_keys = ON") }
        } catch {
            sqlite3_close_v2(handle)
            throw error
        }
    }

    deinit { sqlite3_close_v2(handle) }

    // MARK: - Statements

    /// Runs one or more statements that return nothing.
    public func execute(_ sql: String) throws {
        var error: UnsafeMutablePointer<CChar>?
        let rc = sqlite3_exec(handle, sql, nil, nil, &error)
        guard rc == SQLITE_OK else {
            let message = error.map { String(cString: $0) } ?? String(cString: sqlite3_errmsg(handle))
            sqlite3_free(error)
            throw SQLiteError(code: rc, message: message, sql: sql)
        }
    }

    public func prepare(_ sql: String) throws -> Statement {
        try Statement(database: self, sql: sql)
    }

    /// Prepare, bind, step to completion. For inserts and one-shot updates.
    public func run(_ sql: String, _ values: [SQLiteValue] = []) throws {
        let statement = try prepare(sql)
        try statement.bind(values)
        while try statement.step() {}
    }

    /// Prepare, bind, and map every row.
    public func query<T>(_ sql: String, _ values: [SQLiteValue] = [], row: (Statement) throws -> T) throws -> [T] {
        let statement = try prepare(sql)
        try statement.bind(values)
        var results: [T] = []
        while try statement.step() { results.append(try row(statement)) }
        return results
    }

    /// The first row, if there is one.
    public func queryOne<T>(_ sql: String, _ values: [SQLiteValue] = [], row: (Statement) throws -> T) throws -> T? {
        let statement = try prepare(sql)
        try statement.bind(values)
        return try statement.step() ? try row(statement) : nil
    }

    // MARK: - Transactions

    /// Runs `body` inside `BEGIN IMMEDIATE` … `COMMIT`, rolling back on any throw.
    ///
    /// `IMMEDIATE` rather than deferred: the rebuild's write lock should be taken up front
    /// so contention surfaces at the start rather than partway through 34,607 inserts.
    public func transaction<T>(_ body: () throws -> T) throws -> T {
        try execute("BEGIN IMMEDIATE")
        do {
            let result = try body()
            try execute("COMMIT")
            return result
        } catch {
            try? execute("ROLLBACK")
            throw error
        }
    }

    // MARK: - Whole-database bytes

    /// The database as bytes, via `sqlite3_serialize`.
    ///
    /// Shards are built in memory and uploaded, never written to a temp file first —
    /// there is no filesystem step to fail, and nothing to clean up on a crash.
    public func serialized() throws -> Data {
        var size: sqlite3_int64 = 0
        guard let bytes = sqlite3_serialize(handle, "main", &size, 0) else {
            throw SQLiteError(code: SQLITE_NOMEM, message: "could not serialize database", sql: nil)
        }
        defer { sqlite3_free(bytes) }
        return Data(bytes: bytes, count: Int(size))
    }

    /// Opens an in-memory database over `data`.
    ///
    /// The bytes are copied into SQLite-owned memory and freed with the connection, so the
    /// caller's `Data` need not outlive it.
    public static func deserialized(_ data: Data) throws -> Database {
        let database = try Database(path: nil, options: .inMemory)
        guard let buffer = sqlite3_malloc64(sqlite3_uint64(max(data.count, 1))) else {
            throw SQLiteError(code: SQLITE_NOMEM, message: "could not allocate \(data.count) bytes", sql: nil)
        }
        data.withUnsafeBytes { raw in
            if let base = raw.baseAddress { memcpy(buffer, base, data.count) }
        }
        let rc = sqlite3_deserialize(
            database.handle, "main",
            buffer.assumingMemoryBound(to: UInt8.self),
            sqlite3_int64(data.count), sqlite3_int64(data.count),
            UInt32(SQLITE_DESERIALIZE_FREEONCLOSE)
        )
        guard rc == SQLITE_OK else {
            throw SQLiteError(code: rc, message: String(cString: sqlite3_errmsg(database.handle)), sql: nil)
        }
        return database
    }
}

/// A value that can be bound to a statement parameter.
public enum SQLiteValue: Hashable, Sendable {
    case null
    case integer(Int64)
    case real(Double)
    case text(String)
    case blob(Data)

    public init(_ value: Int?) { self = value.map { .integer(Int64($0)) } ?? .null }
    public init(_ value: Int64?) { self = value.map { .integer($0) } ?? .null }
    public init(_ value: Double?) { self = value.map { .real($0) } ?? .null }
    public init(_ value: String?) { self = value.map { .text($0) } ?? .null }
    public init(_ value: Data?) { self = value.map { .blob($0) } ?? .null }
    public init(_ value: UUID?) { self = value.map { .text($0.catalogString) } ?? .null }
}

/// One prepared statement.
public final class Statement {
    private let database: Database
    private let handle: OpaquePointer
    private let sql: String

    init(database: Database, sql: String) throws {
        var handle: OpaquePointer?
        let rc = sqlite3_prepare_v2(database.handle, sql, -1, &handle, nil)
        guard rc == SQLITE_OK, let handle else {
            throw SQLiteError(code: rc, message: String(cString: sqlite3_errmsg(database.handle)), sql: sql)
        }
        self.database = database
        self.handle = handle
        self.sql = sql
    }

    deinit { sqlite3_finalize(handle) }

    /// SQLite keeps its own copy of bound text and blobs, so no lifetime juggling is needed
    /// at the call sites — `SQLITE_TRANSIENT` is what buys that.
    private static let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

    public func bind(_ values: [SQLiteValue]) throws {
        sqlite3_reset(handle)
        sqlite3_clear_bindings(handle)
        for (offset, value) in values.enumerated() {
            let index = Int32(offset + 1)
            let rc: Int32 = switch value {
            case .null: sqlite3_bind_null(handle, index)
            case .integer(let v): sqlite3_bind_int64(handle, index, v)
            case .real(let v): sqlite3_bind_double(handle, index, v)
            case .text(let v): sqlite3_bind_text(handle, index, v, -1, Self.transient)
            case .blob(let v): v.withUnsafeBytes {
                sqlite3_bind_blob64(handle, index, $0.baseAddress, sqlite3_uint64(v.count), Self.transient)
            }
            }
            guard rc == SQLITE_OK else {
                throw SQLiteError(code: rc, message: String(cString: sqlite3_errmsg(database.handle)), sql: sql)
            }
        }
    }

    /// Advances one row. Returns `false` once the statement is done.
    public func step() throws -> Bool {
        let rc = sqlite3_step(handle)
        switch rc {
        case SQLITE_ROW: return true
        case SQLITE_DONE: return false
        default:
            throw SQLiteError(code: rc, message: String(cString: sqlite3_errmsg(database.handle)), sql: sql)
        }
    }

    // MARK: - Column access

    public func isNull(_ index: Int32) -> Bool { sqlite3_column_type(handle, index) == SQLITE_NULL }

    public func int(_ index: Int32) -> Int64 { sqlite3_column_int64(handle, index) }
    public func intOrNil(_ index: Int32) -> Int64? { isNull(index) ? nil : int(index) }

    public func double(_ index: Int32) -> Double { sqlite3_column_double(handle, index) }
    public func doubleOrNil(_ index: Int32) -> Double? { isNull(index) ? nil : double(index) }

    public func string(_ index: Int32) -> String {
        guard let bytes = sqlite3_column_text(handle, index) else { return "" }
        return String(cString: bytes)
    }
    public func stringOrNil(_ index: Int32) -> String? { isNull(index) ? nil : string(index) }

    public func uuidOrNil(_ index: Int32) -> UUID? {
        guard let text = stringOrNil(index) else { return nil }
        return UUID(uuidString: text)
    }

    public func data(_ index: Int32) -> Data {
        guard let bytes = sqlite3_column_blob(handle, index) else { return Data() }
        return Data(bytes: bytes, count: Int(sqlite3_column_bytes(handle, index)))
    }
}

extension UUID {
    /// Lowercase, so a uuid that has been through a key, a column and a comparison is the
    /// same string every time. Foundation's `uuidString` is uppercase.
    public var catalogString: String { uuidString.lowercased() }
}
