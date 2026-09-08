import Foundation
import PhotosCore
import PhotosPipeline

/// Re-derives the HEIC preview quality constant.
///
/// The benchmark that produced INGEST.md's 12.35 GB was PSNR-matched: HEIC quality was raised
/// until it matched a JPEG baseline, and the resulting size recorded. That script did not
/// survive, and recovering its settings took a fresh measurement pass — so this exists to make
/// sure it never has to happen twice.
///
/// The baseline is 2048px JPEG q84, which is what reproduces the 434 KB/photo the original
/// recorded. Both codecs are measured against the *source* pixels rather than against each
/// other, so "matches the baseline" means "distorts the original no more than JPEG q84 did".
///
///     photos-scan --derive-quality ~/Pictures/Albums
enum QualitySweep {

    static let baselineJPEGQuality = 84
    static let candidates = [45, 50, 55, 58, 60, 62, 65, 68, 72, 78]

    private struct Accumulator {
        var bytes = 0
        var psnr = 0.0
        var count = 0
    }

    static func run(root: URL, limit: Int) async throws {
        print("deriving HEIC preview quality against a 2048px JPEG q\(baselineJPEGQuality) baseline")
        print("sampling up to \(limit) photos from \(root.path)\n")

        let sample = try self.sample(root: root, limit: limit)
        guard !sample.isEmpty else { print("no photos found"); return }

        var baseline = Accumulator()
        var heic: [Int: Accumulator] = [:]

        for url in sample {
            // One decode and one resize per photo, shared by the baseline and every
            // candidate — the encoders are what is being compared, not the scaler.
            guard let decoded = try? PixelImage.decode(contentsOf: url,
                                                       maxLongEdge: DerivativeSpec.previewLongEdge),
                  let source = try? decoded.resizedFitting(longEdge: DerivativeSpec.previewLongEdge,
                                                           allowUpscale: false)
            else { continue }

            guard let jpeg = try? source.encodedJPEG(quality: baselineJPEGQuality, optimize: true),
                  let jpegPixels = try? PixelImage.decode(jpeg: jpeg, maxLongEdge: 0)
            else { continue }

            baseline.bytes += jpeg.count
            baseline.psnr += psnr(source, jpegPixels)
            baseline.count += 1

            for quality in candidates {
                // threads: 0 leaves x265's own pool alone — the sweep is single-threaded, so
                // here the encoder is the only thing with any parallelism to offer.
                guard let encoded = try? source.encodedHEIC(quality: quality, threads: 0),
                      let pixels = try? decodeHEIC(encoded) else { continue }
                var accumulator = heic[quality] ?? Accumulator()
                accumulator.bytes += encoded.count
                accumulator.psnr += psnr(source, pixels)
                accumulator.count += 1
                heic[quality] = accumulator
            }
        }

        guard baseline.count > 0 else { print("nothing decodable in the sample"); return }

        let targetPSNR = baseline.psnr / Double(baseline.count)
        let baselineKB = Double(baseline.bytes) / Double(baseline.count) / 1024
        print("\(baseline.count) photos measured\n")
        print(String(format: "JPEG q%d baseline: %.2f dB, %.1f KB/photo  (INGEST.md recorded 434 KB)",
                     baselineJPEGQuality, targetPSNR, baselineKB))
        print("")
        print("quality".padded(to: 9) + "PSNR dB".padded(to: 11, alignRight: true)
              + "KB/photo".padded(to: 12, alignRight: true)
              + "vs baseline".padded(to: 14, alignRight: true))

        var chosen: Int?
        for quality in candidates {
            guard let accumulator = heic[quality], accumulator.count > 0 else { continue }
            let averagePSNR = accumulator.psnr / Double(accumulator.count)
            let kb = Double(accumulator.bytes) / Double(accumulator.count) / 1024
            let matched = averagePSNR >= targetPSNR
            if matched, chosen == nil { chosen = quality }
            let delta = String(format: "%+.0f%%", (kb - baselineKB) / baselineKB * 100)
            print("\(quality)".padded(to: 9)
                  + String(format: "%.2f", averagePSNR).padded(to: 11, alignRight: true)
                  + String(format: "%.1f", kb).padded(to: 12, alignRight: true)
                  + delta.padded(to: 14, alignRight: true)
                  + (matched ? "  ← matches" : ""))
        }

        print("")
        if let chosen, let accumulator = heic[chosen] {
            let perPhoto = Double(accumulator.bytes) / Double(accumulator.count)
            print("lowest quality matching the baseline: \(chosen)")
            print(String(format: "  %.0f KB/photo, projected %.2f GB across 33,582 photos "
                                 + "(INGEST.md records 12.35 GB)",
                         perPhoto / 1024, perPhoto * 33_582 / 1e9))
            print("  DerivativeSpec.previewQuality is currently \(DerivativeSpec.previewQuality)")
        } else {
            print("no candidate reached the baseline — widen `candidates`")
        }
    }

    static func decodeHEIC(_ data: Data) throws -> PixelImage {
        let url = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("sweep-\(UUID().uuidString).heic")
        try data.write(to: url)
        defer { try? FileManager.default.removeItem(at: url) }
        return try PixelImage.decode(contentsOf: url, maxLongEdge: 0)
    }

    /// Peak signal-to-noise ratio over all channels. Both images must share a geometry.
    static func psnr(_ a: PixelImage, _ b: PixelImage) -> Double {
        guard a.width == b.width, a.height == b.height, a.channels == b.channels else { return 0 }
        let sum = a.withPixels { pa in
            b.withPixels { pb -> Double in
                var total = 0.0
                for i in 0..<min(pa.count, pb.count) {
                    let d = Double(Int(pa[i]) - Int(pb[i]))
                    total += d * d
                }
                return total
            }
        }
        let count = Double(a.width * a.height * a.channels)
        let mse = sum / count
        return mse == 0 ? 99 : 10 * log10(255 * 255 / mse)
    }

    /// An even spread across the library rather than the first N files, so the sample is not
    /// one album's worth of one camera.
    static func sample(root: URL, limit: Int) throws -> [URL] {
        var photos: [URL] = []
        guard let walker = FileManager.default.enumerator(
            at: root, includingPropertiesForKeys: [.isRegularFileKey],
            options: [.skipsHiddenFiles]) else { return [] }
        for case let url as URL in walker {
            guard (try? url.resourceValues(forKeys: [.isRegularFileKey]))?.isRegularFile == true
            else { continue }
            if MediaFormat.sniff(url) == .jpeg { photos.append(url) }
        }
        photos.sort { $0.path < $1.path }
        guard photos.count > limit else { return photos }
        let step = Double(photos.count) / Double(limit)
        return (0..<limit).map { photos[Int(Double($0) * step)] }
    }
}
