import Foundation
import Testing
import PhotosCore
@testable import PhotosPipeline

/// What the pipeline produces, checked against the decisions that fixed it.
///
/// Every input is generated (decision 16). The real library is personal data, and a committed
/// corpus is a set of files that quietly stops representing anything — the same reasoning that
/// made milestone B forge `Fixture.futureShard` rather than ship a `.db`.
struct DerivativeTests {

    /// A scratch directory that cleans up after itself.
    static func withTemporaryDirectory<R>(_ body: (URL) throws -> R) rethrows -> R {
        let url = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("photos-tests-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: url) }
        return try body(url)
    }

    // MARK: - Thumbnails

    @Test("thumbnails are a square crop at the spec's edge, whatever the source aspect")
    func thumbnailIsSquare() throws {
        // Landscape, portrait and square sources all have to arrive at the same tile: every
        // consumer in the app is a square cover-crop box.
        for (width, height) in [(1600, 1067), (1067, 1600), (800, 800), (4000, 900)] {
            let image = try Synthetic.image(width: width, height: height)
            let thumbnail = try image.squareCropped(edge: DerivativeSpec.thumbnailEdge)
            #expect(thumbnail.width == DerivativeSpec.thumbnailEdge)
            #expect(thumbnail.height == DerivativeSpec.thumbnailEdge)
        }
    }

    @Test("a square crop takes the centre, not a corner")
    func cropIsCentred() throws {
        // A wide image whose centre column is a distinct colour: after cropping the centre
        // must survive and the edges must not. Cropping from the origin would keep the left
        // edge instead, which no geometry assertion on its own would notice.
        let image = try Synthetic.image(width: 900, height: 300)
        image.withPixels { _ in }
        let marked = image
        marked.withPixels { pixels in
            let mutable = UnsafeMutablePointer(mutating: pixels.baseAddress!)
            for y in 0..<300 {
                for x in 420..<480 {
                    let i = (y * 900 + x) * 3
                    mutable[i] = 255; mutable[i + 1] = 0; mutable[i + 2] = 0
                }
            }
        }
        let thumbnail = try marked.squareCropped(edge: 64)
        let centreIsRed = thumbnail.withPixels { pixels -> Bool in
            let i = (32 * 64 + 32) * 3
            return pixels[i] > 180 && pixels[i + 1] < 90 && pixels[i + 2] < 90
        }
        #expect(centreIsRed, "the centre stripe should survive a centre crop")
    }

    // MARK: - Previews

    @Test("previews keep aspect and are never upscaled")
    func previewNeverUpscales() throws {
        // Below the tier: must come back untouched. 25 of 120 sampled library photos are
        // already under 2048px, and inventing pixels for them would inflate the tier.
        let small = try Synthetic.image(width: 800, height: 600)
        let unchanged = try small.resizedFitting(longEdge: DerivativeSpec.previewLongEdge,
                                                 allowUpscale: false)
        #expect(unchanged.width == 800)
        #expect(unchanged.height == 600)

        // Above the tier: scaled to the long edge, aspect preserved.
        let large = try Synthetic.image(width: 4096, height: 2048)
        let scaled = try large.resizedFitting(longEdge: DerivativeSpec.previewLongEdge,
                                              allowUpscale: false)
        #expect(scaled.width == DerivativeSpec.previewLongEdge)
        #expect(scaled.height == DerivativeSpec.previewLongEdge / 2)
    }

    @Test("a portrait source scales on its long edge, not its width")
    func previewUsesLongEdge() throws {
        let portrait = try Synthetic.image(width: 2000, height: 4000)
        let scaled = try portrait.resizedFitting(longEdge: 1000, allowUpscale: false)
        #expect(scaled.height == 1000)
        #expect(scaled.width == 500)
    }

    // MARK: - Decode memory

    @Test("JPEG decode is bounded by the requested tier, not the source size")
    func decodeShrinksOnLoad() throws {
        // This is the property that keeps the library's 164 MP panorama from needing 494 MB
        // per worker. libjpeg decodes from the DCT coefficients at N/8, so asking for a 256px
        // tier from a large source must come back far smaller than the source -- and still at
        // least as large as the tier, or the resize afterwards would be an upscale.
        let big = try Synthetic.jpeg(width: 4000, height: 3000)
        let decoded = try PixelImage.decode(jpeg: big, maxLongEdge: 512)
        #expect(decoded.width < 4000, "shrink-on-load should not decode at full size")
        #expect(decoded.width >= 512, "must still cover the requested tier")
        #expect(decoded.width * 8 >= 4000, "scale denominators are eighths")

        // Asking for the full size must genuinely give the full size.
        let full = try PixelImage.decode(jpeg: big, maxLongEdge: 0)
        #expect(full.width == 4000)
        #expect(full.height == 3000)
    }

    // MARK: - Colour

    @Test("alpha is flattened onto white, not black")
    func alphaFlattensOntoWhite() throws {
        // A fully transparent pixel must become the background. White rather than black
        // because the grid is #1a1a1a and a transparent image flattened onto black
        // disappears into its own tile.
        let image = try Synthetic.image(width: 8, height: 8, alpha: true)
        image.withPixels { pixels in
            let mutable = UnsafeMutablePointer(mutating: pixels.baseAddress!)
            for i in 0..<(8 * 8) { mutable[i * 4 + 3] = 0 }
        }
        try image.flattenAlpha(DerivativeSpec.alphaBackground)
        #expect(image.channels == 3)
        image.withPixels { pixels in
            #expect(pixels[0] == 255 && pixels[1] == 255 && pixels[2] == 255)
        }
    }

    @Test("an opaque pixel survives flattening unchanged")
    func opaquePixelsSurviveFlattening() throws {
        let image = try Synthetic.image(width: 4, height: 4, alpha: true)
        image.withPixels { pixels in
            let mutable = UnsafeMutablePointer(mutating: pixels.baseAddress!)
            mutable[0] = 10; mutable[1] = 20; mutable[2] = 30; mutable[3] = 255
        }
        try image.flattenAlpha(DerivativeSpec.alphaBackground)
        image.withPixels { pixels in
            // Rounded compositing, so a fully opaque pixel is exactly itself rather than
            // one unit off from integer truncation.
            #expect(pixels[0] == 10 && pixels[1] == 20 && pixels[2] == 30)
        }
    }

    @Test("converting to sRGB with no profile is a no-op")
    func untaggedConversionIsNoop() throws {
        // 56% of the library's JPEGs carry no profile, and untagged means sRGB everywhere.
        let image = try Synthetic.image(width: 16, height: 16)
        let before = image.withPixels { Array($0) }
        try image.convertToSRGB()
        let after = image.withPixels { Array($0) }
        #expect(before == after)
        #expect(!image.hasProfile)
    }

    // MARK: - Encoding

    @Test("optimized Huffman tables produce a smaller JPEG")
    func optimizedHuffmanIsSmaller() throws {
        // The 4% that makes the recorded 8.7 KB/thumbnail reproduce.
        let image = try Synthetic.image(width: 256, height: 256)
        let plain = try image.encodedJPEG(quality: 75, optimize: false)
        let optimized = try image.encodedJPEG(quality: 75, optimize: true)
        #expect(optimized.count < plain.count)
    }

    @Test("encoded output round-trips at the geometry it was given")
    func encodingRoundTrips() throws {
        let image = try Synthetic.image(width: 200, height: 120)
        let jpeg = try image.encodedJPEG(quality: 75)
        #expect(try ImageProbe.dimensions(ofEncoded: jpeg) == (200, 120))

        let heic = try image.encodedHEIC(quality: 60)
        #expect(try ImageProbe.dimensions(ofEncoded: heic) == (200, 120))
    }

    @Test("higher HEIC quality yields a larger file")
    func heicQualityIsMonotonic() throws {
        let image = try Synthetic.image(width: 512, height: 384)
        let low = try image.encodedHEIC(quality: 40)
        let high = try image.encodedHEIC(quality: 85)
        #expect(high.count > low.count)
    }

    // MARK: - Whole pipeline

    @Test("a still yields both tiers and keeps its original")
    func stillPipeline() throws {
        try Self.withTemporaryDirectory { directory in
            let url = directory.appendingPathComponent("photo.jpg")
            try Synthetic.jpeg(width: 3000, height: 2000).write(to: url)

            let pipeline = Pipeline(workDirectory: directory)
            defer { pipeline.finish() }
            let derived = try pipeline.derive(
                MediaItem(url: url, kind: .still, byteCount: 123))

            #expect(try ImageProbe.dimensions(ofEncoded: derived.thumbnail) == (256, 256))
            let preview = try ImageProbe.dimensions(ofEncoded: derived.preview)
            #expect(max(preview.width, preview.height) == DerivativeSpec.previewLongEdge)

            // Dimensions come from the decoded pixels, not from EXIF, because EXIF can be
            // absent or can describe an embedded thumbnail instead of the photograph.
            #expect(derived.row.width == 3000)
            #expect(derived.row.height == 2000)
            #expect(derived.row.bytes == 123)
            #expect(derived.row.mediaType == .photo)
            #expect(derived.video == nil)
            if case .file(let original) = derived.original {
                #expect(original == url)
            } else {
                Issue.record("a still's original should be the file itself")
            }
        }
    }

    @Test("an undecodable file throws rather than poisoning the run")
    func corruptFileThrows() throws {
        try Self.withTemporaryDirectory { directory in
            // A valid JPEG header followed by nothing usable: sniffing succeeds, decoding
            // must not. Decision 15 makes this per-file and recoverable.
            let url = directory.appendingPathComponent("truncated.jpg")
            try Data([0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10] + Array(repeating: 0x00, count: 64))
                .write(to: url)

            let pipeline = Pipeline(workDirectory: directory)
            defer { pipeline.finish() }
            #expect(throws: (any Error).self) {
                try pipeline.derive(MediaItem(url: url, kind: .still, byteCount: 0))
            }
        }
    }
}
