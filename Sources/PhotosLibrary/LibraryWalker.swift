import Foundation

/// One directory of the library that holds media, with the files it holds.
public struct LibraryAlbum: Sendable, Hashable {
    /// The directory itself. `$LIBRARY_ROOT` when the root holds loose files.
    public var url: URL
    /// Path relative to the library root; empty for the root itself. This is what
    /// `album_info.source_path` records (§3).
    public var relativePath: String
    /// Regular files, excluding everything `.photosignore` matched. Sorted.
    public var files: [URL]
}

/// What one walk found.
public struct LibraryContents: Sendable {
    public var albums: [LibraryAlbum] = []
    public var ignoredFiles: [URL] = []
    public var prunedDirectories: [URL] = []
    /// Parallel to `rules`, counting what each excluded.
    public var ruleUsage: [Int] = []
    public var rules: IgnoreRules = IgnoreRules()

    public var fileCount: Int { albums.reduce(0) { $0 + $1.files.count } }

    /// Rules that excluded nothing. A rule matching nothing is either a typo or a leftover,
    /// and both are worth naming — neither is distinguishable from a working rule otherwise.
    public var unusedRules: [IgnoreRules.Rule] {
        zip(rules.rules, ruleUsage).filter { $0.1 == 0 }.map(\.0)
    }
}

/// Walks `$LIBRARY_ROOT` and applies `.photosignore`.
///
/// Ignoring lives here and nowhere else. `MediaClassifier` never sees an excluded file, so it
/// carries no exclusion logic at all — and §7's other two consumers of "what is in the
/// library", the `--prune` sweep and the byte-size change assertion, inherit the same view
/// rather than each keeping a copy of the rules. Two components that disagreed about what the
/// library contains is exactly the class of bug §7 created the shared package to prevent.
///
/// This target sits below D rather than in `PhotosPipeline` because traversal is a CLI
/// concept: the phone uploads from `PHAssetCollection` and has no library tree to walk.
public struct LibraryWalker: Sendable {

    public var root: URL
    public var rules: IgnoreRules

    /// Loads `.photosignore` from the root. Throws only when the file exists and cannot be
    /// read; absent means no exclusions.
    public init(root: URL) throws {
        self.root = root
        self.rules = try IgnoreRules.load(forLibraryAt: root)
    }

    public init(root: URL, rules: IgnoreRules) {
        self.root = root
        self.rules = rules
    }

    public func walk() -> LibraryContents {
        var contents = LibraryContents()
        contents.rules = rules
        contents.ruleUsage = Array(repeating: 0, count: rules.rules.count)
        visit(root, into: &contents)
        contents.albums.sort { $0.relativePath < $1.relativePath }
        contents.ignoredFiles.sort { $0.path < $1.path }
        contents.prunedDirectories.sort { $0.path < $1.path }
        return contents
    }

    private func isRoot(_ url: URL) -> Bool {
        url.standardizedFileURL.path == root.standardizedFileURL.path
    }

    private func relative(_ url: URL) -> String {
        let rootPath = root.standardizedFileURL.path
        let path = url.standardizedFileURL.path
        guard path.hasPrefix(rootPath) else { return url.lastPathComponent }
        return String(path.dropFirst(rootPath.count).drop(while: { $0 == "/" }))
    }

    private func visit(_ directory: URL, into contents: inout LibraryContents) {
        let entries = (try? FileManager.default.contentsOfDirectory(
            at: directory,
            includingPropertiesForKeys: [.isDirectoryKey, .isRegularFileKey],
            // No .skipsHiddenFiles: there is no built-in dot rule, so `.dtrash/` is skipped
            // only because `.photosignore` says so.
            options: [])) ?? []

        var files: [URL] = []
        var subdirectories: [URL] = []

        for entry in entries {
            let name = entry.lastPathComponent
            // The one exclusion that is not in the file: the file itself. Compared by
            // standardised path — URL equality is sensitive to trailing slashes and to how
            // the root was spelled on the command line.
            if name == IgnoreRules.filename, isRoot(directory) { continue }

            let values = try? entry.resourceValues(forKeys: [.isDirectoryKey, .isRegularFileKey])
            let isDirectory = values?.isDirectory ?? false
            guard isDirectory || (values?.isRegularFile ?? false) else { continue }

            if let index = rules.matchIndex(name: name, relativePath: relative(entry),
                                            isDirectory: isDirectory) {
                contents.ruleUsage[index] += 1
                if isDirectory { contents.prunedDirectories.append(entry) }
                else { contents.ignoredFiles.append(entry) }
                continue
            }

            if isDirectory { subdirectories.append(entry) } else { files.append(entry) }
        }

        if !files.isEmpty {
            contents.albums.append(LibraryAlbum(url: directory,
                                                relativePath: relative(directory),
                                                files: files.sorted { $0.path < $1.path }))
        }
        for subdirectory in subdirectories.sorted(by: { $0.path < $1.path }) {
            visit(subdirectory, into: &contents)
        }
    }
}
