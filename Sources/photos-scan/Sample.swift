import Foundation
import PhotosCore
import PhotosPipeline

/// Chooses a subset of the library that still exercises every code path.
///
/// A full derivation pass is hours; the value of it is not that every file is encoded but
/// that every *kind* of file is, and that the aggregate sizes hold. Sampling buys both at a
/// fraction of the cost — provided the rare kinds are not sampled away, which is exactly what
/// taking the first N files, or a uniform random N, would do. This library's 139 CR2s and 187
/// Live Photos are 1% of it, and they are the two paths with the most bespoke code.
enum Sample {

    /// A stratum: one kind of item, in one container format.
    struct Key: Hashable, CustomStringConvertible {
        var kind: String
        var format: MediaFormat

        var description: String { "\(kind)/\(format)" }
    }

    static func key(for item: MediaItem) -> Key {
        let kind: String
        switch item.kind {
        case .still: kind = "still"
        case .raw: kind = "raw"
        case .livePhoto: kind = "live"
        case .video: kind = "video"
        }
        return Key(kind: kind, format: MediaFormat.sniff(item.url))
    }

    /// Videos are capped at this fraction of the sample.
    ///
    /// Fair-share allocation counts items, but items are not equally priced: a transcode costs
    /// tens of seconds where a still costs about one. Left uncapped, the 340 videos took ~187
    /// of 1500 slots — 1% of the library consuming most of the run, measured at 0.4 files/s
    /// against 1.4 for an ordinary album. They are also the most homogeneous stratum, so a
    /// few dozen exercise the path as well as two hundred do.
    static let videoShare = 0.05

    /// Fair-share allocation: strata are filled smallest-first, each taking at most an equal
    /// share of what is left. A stratum smaller than its share is taken *whole* and its
    /// surplus passes to the larger ones — which is what guarantees every CR2 and every Live
    /// Photo is derived while the 31,000 ordinary JPEGs are merely represented.
    ///
    /// Deterministic, so two runs sample the same files and their reports are comparable.
    static func take(_ count: Int, from items: [MediaItem]) -> (chosen: [MediaItem],
                                                               coverage: [(Key, Int, Int)]) {
        guard count > 0, items.count > count else {
            return (items, Dictionary(grouping: items, by: key(for:))
                .map { ($0.key, $0.value.count, $0.value.count) }
                .sorted { $0.0.description < $1.0.description })
        }

        var strata = Dictionary(grouping: items, by: key(for:))
        var remaining = count
        var pending = strata.count
        var chosen: [MediaItem] = []
        var coverage: [(Key, Int, Int)] = []

        for stratumKey in strata.keys.sorted(by: { ($0.description) < ($1.description) })
            .sorted(by: { (strata[$0]?.count ?? 0) < (strata[$1]?.count ?? 0) }) {
            let pool = strata[stratumKey]!.sorted { $0.url.path < $1.url.path }
            var share = pending > 0 ? max(1, remaining / pending) : 0
            if stratumKey.kind == "video" {
                share = min(share, max(1, Int(Double(count) * videoShare)))
            }
            let takeCount = min(pool.count, share)

            // Even stride rather than a prefix, so a stratum's sample spans the whole library
            // instead of whichever album sorts first.
            let step = Double(pool.count) / Double(max(takeCount, 1))
            var picked: [MediaItem] = []
            for i in 0..<takeCount {
                let index = min(pool.count - 1, Int(Double(i) * step))
                picked.append(pool[index])
            }
            chosen.append(contentsOf: picked)
            coverage.append((stratumKey, picked.count, pool.count))
            remaining -= picked.count
            pending -= 1
            strata[stratumKey] = nil
        }

        chosen.sort { $0.url.path < $1.url.path }
        coverage.sort { $0.0.description < $1.0.description }
        return (chosen, coverage)
    }
}
