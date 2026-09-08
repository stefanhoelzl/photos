import Foundation
import Testing
@testable import PhotosLibrary

/// Walking a library and applying its `.photosignore`.
struct LibraryWalkerTests {

    /// Builds a small library on disk. Paths are `dir/file`; a bare name is a root file.
    static func makeLibrary(_ paths: [String], ignore: String? = nil,
                            _ body: (URL) throws -> Void) throws {
        try withTemporaryDirectory { root in
            for path in paths {
                let url = root.appendingPathComponent(path)
                try FileManager.default.createDirectory(
                    at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
                try Data("x".utf8).write(to: url)
            }
            if let ignore {
                try ignore.write(to: root.appendingPathComponent(IgnoreRules.filename),
                                 atomically: true, encoding: .utf8)
            }
            try body(root)
        }
    }

    @Test("with no .photosignore, nothing at all is excluded")
    func noFileMeansNoExclusions() throws {
        // Decision 2, stated as a test because it is the sharp edge: there is no built-in
        // dot rule, so a hidden directory full of deleted photos is walked like any other.
        try Self.makeLibrary([".dtrash/files/deleted.jpg", "Album/real.jpg", "digikam4.db"]) { root in
            let contents = try LibraryWalker(root: root).walk()
            #expect(contents.fileCount == 3)
            #expect(contents.ignoredFiles.isEmpty)
            #expect(contents.prunedDirectories.isEmpty)
            #expect(contents.albums.contains { $0.relativePath == ".dtrash/files" })
        }
    }

    @Test("a directory rule prunes the whole subtree")
    func directoryRulePrunes() throws {
        try Self.makeLibrary([".dtrash/files/a.jpg", ".dtrash/files/b.jpg",
                              ".dtrash/info.txt", "Album/real.jpg"],
                             ignore: ".dtrash/") { root in
            let contents = try LibraryWalker(root: root).walk()
            #expect(contents.fileCount == 1)
            #expect(contents.albums.map(\.relativePath) == ["Album"])
            // Pruned, not enumerated: the three files inside never appear anywhere.
            #expect(contents.prunedDirectories.count == 1)
            #expect(contents.ignoredFiles.isEmpty)
            #expect(contents.ruleUsage == [1])
        }
    }

    @Test("basename rules apply at every depth")
    func basenameRulesAtDepth() throws {
        try Self.makeLibrary(["digikam4.db", "Album/stray.db", "Album/keep.jpg"],
                             ignore: "*.db") { root in
            let contents = try LibraryWalker(root: root).walk()
            #expect(contents.fileCount == 1)
            #expect(contents.ignoredFiles.count == 2)
            #expect(contents.ruleUsage == [2])
        }
    }

    @Test(".photosignore excludes itself without being listed")
    func ignoreFileExcludesItself() throws {
        // The one rule that is not in the file.
        try Self.makeLibrary(["Album/real.jpg"], ignore: "*.db") { root in
            let contents = try LibraryWalker(root: root).walk()
            #expect(contents.fileCount == 1)
            #expect(!contents.albums.contains { $0.files.contains {
                $0.lastPathComponent == IgnoreRules.filename } })
        }
    }

    @Test("rules that matched nothing are reported")
    func unusedRulesReported() throws {
        try Self.makeLibrary(["Album/real.jpg", "Album/stray.db"],
                             ignore: "*.db\n*.pds\nMissing/thing.psd") { root in
            let contents = try LibraryWalker(root: root).walk()
            #expect(contents.ruleUsage == [1, 0, 0])
            #expect(contents.unusedRules.map(\.source) == ["*.pds", "Missing/thing.psd"])
        }
    }

    @Test("albums are directories that hold at least one surviving file")
    func albumsNeedSurvivingFiles() throws {
        // An album whose every file is ignored is not an album — it is nothing at all.
        try Self.makeLibrary(["Docs/a.db", "Docs/b.db", "Album/real.jpg"],
                             ignore: "*.db") { root in
            let contents = try LibraryWalker(root: root).walk()
            #expect(contents.albums.map(\.relativePath) == ["Album"])
        }
    }

    @Test("the root itself can hold files")
    func rootCanBeAnAlbum() throws {
        try Self.makeLibrary(["loose.jpg", "Album/real.jpg"]) { root in
            let contents = try LibraryWalker(root: root).walk()
            #expect(contents.albums.map(\.relativePath).sorted() == ["", "Album"])
        }
    }

    @Test("results are ordered, so two runs report the same thing")
    func orderIsStable() throws {
        try Self.makeLibrary(["c/3.jpg", "a/1.jpg", "b/2.jpg"]) { root in
            let first = try LibraryWalker(root: root).walk()
            let second = try LibraryWalker(root: root).walk()
            #expect(first.albums.map(\.relativePath) == ["a", "b", "c"])
            #expect(first.albums.map(\.relativePath) == second.albums.map(\.relativePath))
        }
    }

    @Test("an unreadable .photosignore aborts before any walking happens")
    func unreadableAborts() throws {
        try withTemporaryDirectory { root in
            try Data([0xFF, 0xFE, 0x80]).write(
                to: root.appendingPathComponent(IgnoreRules.filename))
            #expect(throws: IgnoreRules.Failure.self) { try LibraryWalker(root: root) }
        }
    }
}
