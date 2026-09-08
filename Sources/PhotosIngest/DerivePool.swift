import Foundation

/// Runs the pipeline's synchronous, CPU-bound work off the cooperative pool.
///
/// `Pipeline.derive` blocks for 2.7 s per photo (§7). Swift's cooperative pool has about one
/// thread per core, so running 16 of those on it would leave nothing to run the uploader —
/// encoding would starve the one thing that is actually the bottleneck. A plain concurrent
/// `DispatchQueue` has real threads to block, which is exactly what this work wants.
struct DerivePool: Sendable {
    private let queue = DispatchQueue(label: "photos-cli.derive", attributes: .concurrent)

    func run<T: Sendable>(_ body: @escaping @Sendable () throws -> T) async throws -> T {
        try await withCheckedThrowingContinuation { continuation in
            queue.async { continuation.resume(with: Result { try body() }) }
        }
    }
}

/// Lets exactly `limit` tasks through at once.
///
/// The uploader needs this rather than an actor: an actor serialises to one, and §9's
/// measurement says one is right for *this* link — but `--upload-jobs` exists for a link
/// that is not this one, and a hard-wired actor could not honour it.
actor AsyncSemaphore {
    private var available: Int
    private var waiting: [CheckedContinuation<Void, Never>] = []

    init(limit: Int) { self.available = max(1, limit) }

    func acquire() async {
        if available > 0 {
            available -= 1
            return
        }
        await withCheckedContinuation { waiting.append($0) }
    }

    func release() {
        if waiting.isEmpty {
            available += 1
        } else {
            waiting.removeFirst().resume()
        }
    }

    func withPermit<T: Sendable>(
        _ body: @Sendable () async throws -> T
    ) async rethrows -> T {
        await acquire()
        defer { release() }
        return try await body()
    }
}
