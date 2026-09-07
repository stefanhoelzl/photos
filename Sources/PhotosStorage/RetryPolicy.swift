import Foundation

/// Exponential backoff with jitter, for the failures that are worth repeating.
public struct RetryPolicy: Sendable {
    public var maxAttempts: Int
    public var baseDelay: Duration
    public var maxDelay: Duration

    public init(maxAttempts: Int = 4,
                baseDelay: Duration = .milliseconds(200),
                maxDelay: Duration = .seconds(20)) {
        self.maxAttempts = maxAttempts
        self.baseDelay = baseDelay
        self.maxDelay = maxDelay
    }

    public static let `default` = RetryPolicy()
    /// For tests, and for callers that want failures to surface immediately.
    public static let none = RetryPolicy(maxAttempts: 0)

    /// 5xx and 429 only. A 403 means the password is wrong and a 404 means the
    /// object is not there; repeating either just wastes time before showing the
    /// same message.
    public func shouldRetry(status: Int) -> Bool {
        status == 429 || (500...599).contains(status)
    }

    func delay(attempt: Int, retryAfter: String?) -> Duration {
        if let retryAfter, let seconds = Double(retryAfter), seconds > 0 {
            return min(.seconds(seconds), maxDelay)
        }
        let exponential = baseDelay * Int(pow(2.0, Double(max(0, attempt - 1))))
        let capped = min(exponential, maxDelay)
        // Full jitter: spreads a thundering herd of retries after a 503.
        let jittered = Double.random(in: 0...1) * Double(capped.components.seconds)
            + Double.random(in: 0...1) * Double(capped.components.attoseconds) / 1e18
        return .seconds(jittered)
    }

    func wait(attempt: Int, retryAfter: String?) async throws {
        try await Task.sleep(for: delay(attempt: attempt, retryAfter: retryAfter))
    }
}
