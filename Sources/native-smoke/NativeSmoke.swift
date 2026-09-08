import Foundation
import PhotosCore
import PhotosPipeline

/// The only thing that can exercise the shipped configuration.
///
/// The Static Linux SDK ships neither swift-testing nor XCTest, so `swift test` cannot be
/// built for musl at all — the binary that actually goes out is the one configuration the
/// suite never touches. This is a plain executable for exactly that reason: it runs the
/// pipeline over generated inputs, checks the properties that would break if something
/// behaved differently under musl, and exits nonzero.
///
/// It is not a second test suite. The real assertions live in PhotosPipelineTests; this
/// checks that the same code paths *run* where they will be shipped.
@main
struct NativeSmoke {

    static func main() async {
        var report = Report()
        let work = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("photos-smoke-\(UUID().uuidString)")

        do {
            try FileManager.default.createDirectory(at: work, withIntermediateDirectories: true)
            defer { try? FileManager.default.removeItem(at: work) }

            let pipeline = Pipeline(workDirectory: work)
            let counter = EventCounter()
            // Drained on a separate task so the AsyncStream plumbing is exercised rather than
            // merely compiled, and so a full buffer cannot stall a worker.
            let watcher = Task { for await event in pipeline.events { await counter.record(event) } }

            await stills(pipeline: pipeline, work: work, report: &report)
            await carving(work: work, report: &report)
            await video(pipeline: pipeline, work: work, report: &report)
            await pairing(work: work, report: &report)

            pipeline.finish()
            _ = await watcher.result

            report.section("events")
            report.check("stream delivered start events", await counter.count("started") >= 3)
            report.check("stream delivered finish events", await counter.count("finished") >= 3)
        } catch {
            report.check("smoke setup", false, "\(error)")
        }

        print("\n\(report.passed)/\(report.total) checks passed")
        if !report.failures.isEmpty {
            print("failed: \(report.failures.joined(separator: ", "))")
            exit(1)
        }
        print("native-smoke OK")
    }

    // MARK: - Stills

    static func stills(pipeline: Pipeline, work: URL, report: inout Report) async {
        report.section("JPEG still")
        do {
            let url = work.appendingPathComponent("still.jpg")
            try Synthetic.jpeg(width: 1600, height: 1067).write(to: url)
            let size = Int64(try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0)
            let derived = try pipeline.derive(MediaItem(url: url, kind: .still, byteCount: size))

            let thumb = try ImageProbe.dimensions(ofEncoded: derived.thumbnail)
            report.check("thumbnail is 256x256", thumb == (256, 256), "\(thumb)")

            let preview = try ImageProbe.dimensions(ofEncoded: derived.preview)
            report.check("preview long edge stays 1600 (never upscaled)",
                         max(preview.width, preview.height) == 1600, "\(preview)")
            report.check("preview keeps aspect", preview.width > preview.height, "\(preview)")
            report.check("row carries decoded dimensions",
                         derived.row.width == 1600 && derived.row.height == 1067)
            report.check("thumbnail size is plausible",
                         (2_000...60_000).contains(derived.thumbnail.count),
                         "\(derived.thumbnail.count) bytes")
            report.check("preview size is plausible", derived.preview.count > 5_000,
                         "\(derived.preview.count) bytes")
        } catch {
            report.check("JPEG still pipeline", false, "\(error)")
        }

        report.section("PNG with alpha")
        do {
            let url = work.appendingPathComponent("alpha.png")
            try Synthetic.png(width: 300, height: 200, alpha: true).write(to: url)
            report.check("sniffed as PNG", MediaFormat.sniff(url) == .png)
            let derived = try pipeline.derive(MediaItem(url: url, kind: .still, byteCount: 0))
            let thumb = try ImageProbe.dimensions(ofEncoded: derived.thumbnail)
            report.check("alpha flattened into a 256x256 thumbnail", thumb == (256, 256), "\(thumb)")
        } catch {
            report.check("PNG alpha pipeline", false, "\(error)")
        }
    }

    // MARK: - CR2

    static func carving(work: URL, report: inout Report) async {
        report.section("CR2 carving")
        do {
            let jpeg = try Synthetic.jpeg(width: 640, height: 426)
            let url = work.appendingPathComponent("raw.CR2")
            try Synthetic.cr2(wrapping: jpeg, width: 640, height: 426).write(to: url)

            report.check("sniffed as CR2", MediaFormat.sniff(url) == .cr2)
            let extraction = try CR2.extract(contentsOf: url)
            report.check("carved stream starts with SOI",
                         extraction.jpeg.count > 4
                         && extraction.jpeg[extraction.jpeg.startIndex] == 0xFF
                         && extraction.jpeg[extraction.jpeg.startIndex + 1] == 0xD8)
            let size = try ImageProbe.dimensions(ofEncoded: extraction.jpeg)
            report.check("carved JPEG decodes at full size", size == (640, 426), "\(size)")
            report.check("IFD0 dimensions recovered",
                         extraction.width == 640 && extraction.height == 426)
        } catch {
            report.check("CR2 extraction", false, "\(error)")
        }
    }

    // MARK: - Video

    static func video(pipeline: Pipeline, work: URL, report: inout Report) async {
        report.section("video")
        do {
            let url = work.appendingPathComponent("clip.mp4")
            try Synthetic.video(at: url, width: 320, height: 240, frames: 12, rotation: 90)
            report.check("sniffed as video", MediaFormat.sniff(url) == .video)

            let info = try VideoInfo.probe(url)
            report.check("rotation read from display matrix", info.rotation == 90, "\(info.rotation)")
            report.check("dimensions probed", info.width == 320 && info.height == 240,
                         "\(info.width)x\(info.height)")

            let derived = try pipeline.derive(MediaItem(url: url, kind: .video, byteCount: 0))
            report.check("video has no original (§5 keeps them on the laptop)",
                         { if case .none = derived.original { return true }; return false }())
            report.check("transcode produced a file",
                         derived.video.map { FileManager.default.fileExists(atPath: $0.path) } ?? false)
            // 90 degrees baked in means the poster is portrait though the stream is landscape.
            report.check("poster rotation baked in",
                         derived.row.width == 240 && derived.row.height == 320,
                         "\(derived.row.width ?? -1)x\(derived.row.height ?? -1)")
            if let video = derived.video {
                let out = try VideoInfo.probe(video)
                report.check("transcode is upright, no residual matrix", out.rotation == 0,
                             "\(out.rotation)")
                report.check("transcode respects the 1080p ceiling", out.height <= 1080,
                             "\(out.height)")
            }
        } catch {
            report.check("video pipeline", false, "\(error)")
        }
    }

    static func pairing(work: URL, report: inout Report) async {
        report.section("Live Photo pairing")
        do {
            let identifier = "B34B6B99-C28F-4E16-A788-79AA0E30BB18"
            let movie = work.appendingPathComponent("pair.mov")
            try Synthetic.video(at: movie, width: 64, height: 48, frames: 6,
                                contentIdentifier: identifier)
            let probed = try VideoInfo.probe(movie)
            report.check("content.identifier round-trips through the container",
                         probed.contentIdentifier == identifier, probed.contentIdentifier ?? "nil")
        } catch {
            report.check("Live Photo pairing", false, "\(error)")
        }
    }
}

// MARK: - Support

struct Report {
    var total = 0
    var failures: [String] = []
    var passed: Int { total - failures.count }

    mutating func section(_ name: String) { print("\n\(name)") }

    mutating func check(_ label: String, _ condition: Bool,
                        _ detail: @autoclosure () -> String = "") {
        total += 1
        if condition {
            print("  ok   \(label)")
        } else {
            let extra = detail()
            print("  FAIL \(label)\(extra.isEmpty ? "" : " — \(extra)")")
            failures.append(label)
        }
    }
}

actor EventCounter {
    private var counts: [String: Int] = [:]

    func record(_ event: PipelineEvent) {
        let key: String
        switch event {
        case .started: key = "started"
        case .decoded: key = "decoded"
        case .thumbnailed: key = "thumbnailed"
        case .previewed: key = "previewed"
        case .transcoding: key = "transcoding"
        case .finished: key = "finished"
        case .failed: key = "failed"
        }
        counts[key, default: 0] += 1
    }

    func count(_ key: String) -> Int { counts[key] ?? 0 }
}
