import Foundation
import Testing
import PhotosCore
@testable import PhotosPipeline

/// Carving the embedded JPEG out of a CR2 — decision 2.
struct CR2Tests {

    @Test("the embedded JPEG comes back byte-for-byte")
    func carvingIsExact() throws {
        // The whole premise of decision 2: this is a copy, not a re-encode. If the carved
        // bytes ever differed from the source stream, we would be paying for a decode we
        // decided not to do.
        let jpeg = try Synthetic.jpeg(width: 800, height: 533)
        let extraction = try CR2.extract(Synthetic.cr2(wrapping: jpeg, width: 800, height: 533))

        // The graft prepends an APP1, so the tail after the SOI must match exactly.
        #expect(extraction.jpeg.count >= jpeg.count)
        #expect(extraction.jpeg.suffix(jpeg.count - 2) == jpeg.suffix(jpeg.count - 2))
    }

    @Test("IFD0's declared dimensions are recovered")
    func dimensionsComeFromIFD0() throws {
        let jpeg = try Synthetic.jpeg(width: 640, height: 480)
        let extraction = try CR2.extract(Synthetic.cr2(wrapping: jpeg, width: 5184, height: 3456))
        // The tags say 5184x3456 even though this fixture's stream is smaller; the carver
        // must report what IFD0 declared rather than re-deriving it.
        #expect(extraction.width == 5184)
        #expect(extraction.height == 3456)
    }

    @Test("the carved JPEG is decodable at full resolution")
    func carvedJPEGDecodes() throws {
        let jpeg = try Synthetic.jpeg(width: 1024, height: 768)
        let extraction = try CR2.extract(Synthetic.cr2(wrapping: jpeg, width: 1024, height: 768))
        let decoded = try PixelImage.decode(jpeg: extraction.jpeg, maxLongEdge: 0)
        #expect(decoded.width == 1024)
        #expect(decoded.height == 768)
    }

    @Test("a file that is not a TIFF is rejected rather than guessed at")
    func nonTIFFRejected() throws {
        #expect(throws: (any Error).self) {
            try CR2.extract(Data(repeating: 0x00, count: 64))
        }
        #expect(throws: (any Error).self) {
            try CR2.extract(try Synthetic.jpeg(width: 32, height: 32))
        }
    }

    @Test("a TIFF with no old-style-JPEG IFD is rejected")
    func noEmbeddedJPEGRejected() throws {
        // A bare, valid little-endian TIFF header with an empty IFD.
        var tiff = Data([0x49, 0x49, 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00])
        tiff.append(contentsOf: [0x00, 0x00])              // zero entries
        tiff.append(contentsOf: [0x00, 0x00, 0x00, 0x00])  // no next IFD
        #expect(throws: (any Error).self) { try CR2.extract(tiff) }
    }

    @Test("a truncated file is refused, not read past its end")
    func truncationRefused() throws {
        let jpeg = try Synthetic.jpeg(width: 256, height: 256)
        let full = Synthetic.cr2(wrapping: jpeg, width: 256, height: 256)
        // Chop the stream in half: the IFD still claims the original byte count, so the
        // carver is being asked to read past the end of the buffer.
        let truncated = full.prefix(full.count / 2)
        #expect(throws: (any Error).self) { try CR2.extract(Data(truncated)) }
    }

    @Test("a carved original is what the pipeline uploads for a raw")
    func pipelineUsesCarvedOriginal() throws {
        let directory = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("cr2-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }

        let url = directory.appendingPathComponent("IMG_7353.CR2")
        try Synthetic.cr2(wrapping: try Synthetic.jpeg(width: 900, height: 600),
                          width: 900, height: 600).write(to: url)

        let pipeline = Pipeline(workDirectory: directory)
        defer { pipeline.finish() }
        let derived = try pipeline.derive(MediaItem(url: url, kind: .raw, byteCount: 0))

        // §3: a developed CR2's original_id points at the JPEG, not the RAW — so the
        // pipeline must hand back synthesised bytes rather than the file on disk.
        guard case .data(let bytes) = derived.original else {
            Issue.record("a raw's original should be carved data, not the CR2 file")
            return
        }
        #expect(bytes.count > 0)
        #expect(try ImageProbe.dimensions(ofEncoded: derived.thumbnail) == (256, 256))
    }
}

/// The EXIF graft — decision 9's "the extracted JPEG should look like every other original".
struct CR2GraftTests {

    @Test("a carved JPEG carries the CR2's own EXIF")
    func graftCarriesTags() throws {
        // Without a tag in IFD0 there is nothing to graft, so the graft path is silently
        // untested — which is exactly what happened until this fixture grew a Model.
        let jpeg = try Synthetic.jpeg(width: 320, height: 240)
        let cr2 = Synthetic.cr2(wrapping: jpeg, width: 320, height: 240,
                                model: "Canon EOS 100D")
        let extraction = try CR2.extract(cr2)

        // The graft prepends an APP1, so the carved file is larger than the raw stream.
        #expect(extraction.jpeg.count > jpeg.count)
        #expect(extraction.jpeg[extraction.jpeg.startIndex + 2] == 0xFF)
        #expect(extraction.jpeg[extraction.jpeg.startIndex + 3] == 0xE1, "APP1 should follow SOI")

        let directory = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("graft-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let url = directory.appendingPathComponent("carved.jpg")
        try extraction.jpeg.write(to: url)

        let tags = try NativeImageBackend().rawTags(at: url)
        #expect(tags["Model"]?.stringValue == "Canon EOS 100D")

        // And it must still be a decodable JPEG afterwards.
        #expect(try ImageProbe.dimensions(of: url) == (320, 240))
    }

    @Test("a CR2 with no taggable metadata still yields a usable JPEG")
    func graftDegradesGracefully() throws {
        // The graft is best-effort: the catalog carries date and coordinates regardless, so a
        // CR2 with nothing to graft must produce a working original rather than an error.
        let jpeg = try Synthetic.jpeg(width: 200, height: 150)
        let extraction = try CR2.extract(Synthetic.cr2(wrapping: jpeg, width: 200, height: 150))
        #expect(try ImageProbe.dimensions(ofEncoded: extraction.jpeg) == (200, 150))
    }
}
