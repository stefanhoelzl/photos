import Foundation
import Testing
import PhotosCore
@testable import PhotosPipeline

/// Classification, the denylist, and Live Photo pairing.
struct ClassifierTests {

    /// Returns the identifier the classifier should find for a given HEIC, so pairing can be
    /// exercised without building an Apple maker note by hand. The real maker-note walk is
    /// covered by `ExifReadingTests` and by every run of `photos-scan`.
    struct StubBackend: ImageBackend {
        var identifiers: [String: String] = [:]
        func rawTags(at url: URL) throws -> ExifTags {
            guard let identifier = identifiers[url.lastPathComponent] else { return ExifTags() }
            return ExifTags(["AppleContentIdentifier": .string(identifier)])
        }
    }

    static func withTemporaryDirectory<R>(_ body: (URL) throws -> R) rethrows -> R {
        let url = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("photos-classify-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: url) }
        return try body(url)
    }

    @Test("junk reaching the classifier is skipped as unrecognised, never ingested")
    func junkIsNeverIngested() throws {
        try Self.withTemporaryDirectory { directory in
            // The classifier no longer knows what junk is — `.photosignore` and the walker
            // handle that. This is the property that made moving exclusions out safe: the
            // denylist only ever quieted the report, because every one of these sniffs as
            // nothing and is skipped regardless of whether a rule named it.
            let junk = ["digikam4.db", "thumbnails-digikam.db", "THUMB~JT.DBE",
                        "hochzeit50.doc", "preisliste.pdf", "akaroa_III.psd", ".hidden"]
            for name in junk {
                try Data("not a photo".utf8).write(to: directory.appendingPathComponent(name))
            }
            let photo = directory.appendingPathComponent("real.jpg")
            try Synthetic.jpeg(width: 64, height: 64).write(to: photo)

            let result = MediaClassifier(backend: StubBackend())
                .classify(junk.map { directory.appendingPathComponent($0) } + [photo])

            #expect(result.items.count == 1)
            #expect(result.items.first?.url == photo)
            #expect(result.skipped.count == junk.count)
            #expect(result.skipped.allSatisfy { $0.reason == .unrecognisedFormat })
        }
    }

    @Test("an unrecognised file is reported, not decoded and not fatal")
    func unknownFormatIsReported() throws {
        try Self.withTemporaryDirectory { directory in
            // No denylisted extension, but nothing the sniffer recognises either. The
            // denylist degrades in this direction on purpose.
            let url = directory.appendingPathComponent("mystery.xyz")
            try Data(repeating: 0x7A, count: 4096).write(to: url)

            let result = MediaClassifier(backend: StubBackend()).classify([url])
            #expect(result.items.isEmpty)
            #expect(result.skipped.first?.reason == .unrecognisedFormat)
        }
    }

    @Test("a new media format is picked up automatically")
    func newFormatsAreNotDropped() throws {
        try Self.withTemporaryDirectory { directory in
            // The payoff of a denylist over an allowlist: content decides, so a file whose
            // extension nobody enumerated is still ingested if it sniffs as media.
            let url = directory.appendingPathComponent("photo.unknownext")
            try Synthetic.jpeg(width: 32, height: 32).write(to: url)

            let result = MediaClassifier(backend: StubBackend()).classify([url])
            #expect(result.items.count == 1)
            #expect(result.skipped.isEmpty)
        }
    }

    @Test("a HEIC and a MOV sharing a content identifier become one Live Photo")
    func pairsOnContentIdentifier() throws {
        try Self.withTemporaryDirectory { directory in
            let identifier = "B34B6B99-C28F-4E16-A788-79AA0E30BB18"
            let still = directory.appendingPathComponent("IMG_0679.HEIC")
            let movie = directory.appendingPathComponent("IMG_0679.mov")
            try Synthetic.heic(width: 64, height: 48).write(to: still)
            try Synthetic.video(at: movie, width: 64, height: 48, frames: 4,
                                contentIdentifier: identifier)

            let backend = StubBackend(identifiers: ["IMG_0679.HEIC": identifier])
            let result = MediaClassifier(backend: backend).classify([still, movie])

            #expect(result.items.count == 1)
            guard case .livePhoto(let paired) = result.items.first?.kind else {
                Issue.record("expected a Live Photo, got \(String(describing: result.items.first?.kind))")
                return
            }
            #expect(paired == movie)
            // The MOV is not a separate item; it is uploaded as part of the Live Photo.
            #expect(result.skipped.contains { $0.reason == .consumedAsLivePhotoVideo })
        }
    }

    @Test("a matching filename does not pair without a matching identifier")
    func filenamesDoNotPair() throws {
        try Self.withTemporaryDirectory { directory in
            // The case that makes decision 14 worth its cost: an unrelated video that happens
            // to share a basename must stay a video, not be swallowed into a Live Photo.
            let still = directory.appendingPathComponent("IMG_1234.HEIC")
            let movie = directory.appendingPathComponent("IMG_1234.mov")
            try Synthetic.heic(width: 64, height: 48).write(to: still)
            try Synthetic.video(at: movie, width: 64, height: 48, frames: 4)  // no identifier

            let result = MediaClassifier(backend: StubBackend()).classify([still, movie])
            #expect(result.items.count == 2)
            #expect(result.items.allSatisfy {
                if case .livePhoto = $0.kind { return false }
                return true
            })
        }
    }

    @Test("classification is stable across runs")
    func orderIsStable() throws {
        try Self.withTemporaryDirectory { directory in
            var urls: [URL] = []
            for name in ["c.jpg", "a.jpg", "b.jpg"] {
                let url = directory.appendingPathComponent(name)
                try Synthetic.jpeg(width: 32, height: 32).write(to: url)
                urls.append(url)
            }
            let first = MediaClassifier(backend: StubBackend()).classify(urls)
            let second = MediaClassifier(backend: StubBackend()).classify(urls.reversed())
            #expect(first.items.map(\.filename) == second.items.map(\.filename))
            #expect(first.items.map(\.filename) == ["a.jpg", "b.jpg", "c.jpg"])
        }
    }

    @Test("a CR2 is classified as raw, never as a decodable still")
    func rawIsRecognised() throws {
        try Self.withTemporaryDirectory { directory in
            let url = directory.appendingPathComponent("IMG_7353.CR2")
            try Synthetic.cr2(wrapping: try Synthetic.jpeg(width: 128, height: 96),
                              width: 128, height: 96).write(to: url)

            let result = MediaClassifier(backend: StubBackend()).classify([url])
            #expect(result.items.first?.kind == .raw)
        }
    }
}

/// Format detection, for containers the library actually holds.
struct SniffTests {

    static func withTemporary(_ bytes: [UInt8], _ body: (URL) throws -> Void) throws {
        let url = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("sniff-\(UUID().uuidString)")
        try Data(bytes).write(to: url)
        defer { try? FileManager.default.removeItem(at: url) }
        try body(url)
    }

    /// A top-level atom: 4-byte big-endian size, then the type.
    static func atom(_ type: String, size: UInt32) -> [UInt8] {
        [UInt8(size >> 24 & 0xFF), UInt8(size >> 16 & 0xFF),
         UInt8(size >> 8 & 0xFF), UInt8(size & 0xFF)] + Array(type.utf8)
    }

    @Test("classic QuickTime is recognised without an ftyp box", arguments: [
        "pnot", "moov", "mdat", "wide", "skip", "free",
    ])
    func quickTimeWithoutFtyp(first: String) throws {
        // `ftyp` is an MP4-ism. 23 Nikon Coolpix videos in the real library open with a
        // `pnot` preview atom and no ftyp at all, and were silently dropped as "unknown".
        let bytes = Self.atom(first, size: 20) + Array(repeating: UInt8(0), count: 44)
        try Self.withTemporary(bytes) { url in
            #expect(MediaFormat.sniff(url) == .video, "\(first) should sniff as video")
        }
    }

    @Test("an ftyp box still decides HEIF by brand")
    func ftypBrandStillWins() throws {
        for brand in ["heic", "mif1"] {
            let bytes = Self.atom("ftyp", size: 24) + Array(brand.utf8)
                + Array(repeating: UInt8(0), count: 40)
            try Self.withTemporary(bytes) { url in
                #expect(MediaFormat.sniff(url) == .heif, "brand \(brand) should be HEIF")
            }
        }
        let mp4 = Self.atom("ftyp", size: 24) + Array("isom".utf8)
            + Array(repeating: UInt8(0), count: 40)
        try Self.withTemporary(mp4) { url in
            #expect(MediaFormat.sniff(url) == .video)
        }
    }

    @Test("a leading atom is stepped over to reach ftyp")
    func walksPastLeadingAtoms() throws {
        // `wide` then `ftyp` — the walk has to advance by the first atom's size.
        let bytes = Self.atom("wide", size: 8)
            + Self.atom("ftyp", size: 24) + Array("isom".utf8)
            + Array(repeating: UInt8(0), count: 40)
        try Self.withTemporary(bytes) { url in
            #expect(MediaFormat.sniff(url) == .video)
        }
    }

    @Test("something that is not a container is still unknown")
    func nonContainerStaysUnknown() throws {
        // The walk must not be so eager that a SQLite database looks like a movie.
        try Self.withTemporary(Array("SQLite format 3\u{0}".utf8)
                               + Array(repeating: UInt8(0), count: 64)) { url in
            #expect(MediaFormat.sniff(url) == .unknown)
        }
    }
}
