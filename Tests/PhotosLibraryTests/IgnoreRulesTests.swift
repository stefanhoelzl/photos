import Foundation
import Testing
@testable import PhotosLibrary

/// `.photosignore` parsing and matching.
struct IgnoreRulesTests {

    @Test("comments, blanks and surrounding space are ignored")
    func parsingSkipsNoise() {
        let rules = IgnoreRules.parse("""
        # a comment

        *.db
            THUMB~*.DBE
        # another
        """)
        #expect(rules.rules.count == 2)
        #expect(rules.rules.map(\.source) == ["*.db", "THUMB~*.DBE"])
        // Line numbers are kept so an unused-rule report can point at the line.
        #expect(rules.rules.map(\.line) == [3, 4])
    }

    @Test("a trailing slash marks a directory rule")
    func directoryRules() {
        let rules = IgnoreRules.parse(".dtrash/\n*.db")
        #expect(rules.rules[0].isDirectory)
        #expect(!rules.rules[1].isDirectory)
        // A directory rule must not exclude a *file* of the same name.
        #expect(rules.matchIndex(name: ".dtrash", relativePath: ".dtrash",
                                 isDirectory: true) == 0)
        #expect(rules.matchIndex(name: ".dtrash", relativePath: ".dtrash",
                                 isDirectory: false) == nil)
    }

    @Test("a pattern without a slash matches the basename at any depth")
    func basenameAtAnyDepth() {
        let rules = IgnoreRules.parse("*.db")
        #expect(rules.matchIndex(name: "digikam4.db", relativePath: "digikam4.db",
                                 isDirectory: false) == 0)
        #expect(rules.matchIndex(name: "stray.db", relativePath: "Album/Sub/stray.db",
                                 isDirectory: false) == 0,
                "FNM_PATHNAME stops * crossing /, so the basename must be matched explicitly")
    }

    @Test("a pattern with a slash is anchored to the library root")
    func anchoredPatterns() {
        let rules = IgnoreRules.parse("Neuseeland/akaroa.psd")
        #expect(rules.matchIndex(name: "akaroa.psd", relativePath: "Neuseeland/akaroa.psd",
                                 isDirectory: false) == 0)
        // Same basename elsewhere is not excluded — that is the point of anchoring.
        #expect(rules.matchIndex(name: "akaroa.psd", relativePath: "Kalifornien/akaroa.psd",
                                 isDirectory: false) == nil)
    }

    @Test("matching ignores case")
    func caseInsensitive() {
        let rules = IgnoreRules.parse("thumb~*.dbe")
        #expect(rules.matchIndex(name: "THUMB~JT.DBE", relativePath: "A/THUMB~JT.DBE",
                                 isDirectory: false) == 0)
    }

    @Test("matching ignores unicode composition")
    func nfcNormalised() {
        // A file written on a Mac commonly arrives NFD. On-disk names here are NFC, and
        // §2 names composition as the hazard that costs an album.
        let nfd = "Hochfu\u{0308}gen*"
        let rules = IgnoreRules.parse(nfd)
        #expect(rules.matchIndex(name: "Hochfügen 2004", relativePath: "Hochfügen 2004",
                                 isDirectory: true) == 0)
    }

    @Test("the first matching rule wins, and its index is reported")
    func reportsWhichRuleMatched() {
        let rules = IgnoreRules.parse("*.psd\n*.db\nNeuseeland/x.psd")
        #expect(rules.matchIndex(name: "a.db", relativePath: "a.db", isDirectory: false) == 1)
        #expect(rules.matchIndex(name: "a.jpg", relativePath: "a.jpg", isDirectory: false) == nil)
    }

    @Test("an absent file means no exclusions, and is not an error")
    func absentFileIsNotAnError() throws {
        try withTemporaryDirectory { root in
            let rules = try IgnoreRules.load(forLibraryAt: root)
            #expect(rules.isEmpty)
        }
    }

    @Test("a present but unreadable file aborts")
    func unreadableFileThrows() throws {
        try withTemporaryDirectory { root in
            // Invalid UTF-8: the file exists, so exclusions were intended, and continuing
            // without them is how `.dtrash` gets uploaded.
            let url = root.appendingPathComponent(IgnoreRules.filename)
            try Data([0xFF, 0xFE, 0x00, 0x80, 0x81]).write(to: url)
            #expect(throws: IgnoreRules.Failure.self) {
                try IgnoreRules.load(forLibraryAt: root)
            }
        }
    }
}

func withTemporaryDirectory<R>(_ body: (URL) throws -> R) rethrows -> R {
    let url = URL(fileURLWithPath: NSTemporaryDirectory())
        .appendingPathComponent("photos-library-\(UUID().uuidString)")
    try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: url) }
    return try body(url)
}
