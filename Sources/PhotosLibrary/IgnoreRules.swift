import Foundation
#if canImport(Glibc)
import Glibc
#elseif canImport(Musl)
import Musl
#elseif canImport(Darwin)
import Darwin
#endif

/// The contents of a library's `.photosignore`.
///
/// The tool carries no built-in exclusions at all — not an extension list, not a name list,
/// not even a dot-file rule. The one thing it always skips is `.photosignore` itself. That is
/// deliberate: what counts as junk is a fact about a particular library, and baking this
/// library's digiKam artefacts into a package DESIGN presents as reusable is the same mixing
/// of concerns `INGEST.md` exists to prevent.
///
/// The consequence is stated plainly because it is sharp: **with no file, nothing is
/// excluded**. On a library whose `.dtrash/` holds deleted-but-decodable photos, that means
/// uploading them, and §2 has no undelete.
public struct IgnoreRules: Sendable {

    /// The filename, at the library root and nowhere else.
    public static let filename = ".photosignore"

    public struct Rule: Sendable, Hashable {
        /// Folded and NFC-normalised, with any trailing `/` removed.
        public var pattern: String
        /// Written with a trailing `/`: matches directories, which are not descended into.
        public var isDirectory: Bool
        /// Contains `/`: matched against the path relative to the library root. Otherwise it
        /// is matched against the file's own name, at any depth.
        public var isAnchored: Bool
        /// The line as written, for reporting.
        public var source: String
        public var line: Int
    }

    public var rules: [Rule]

    public var isEmpty: Bool { rules.isEmpty }

    public init(rules: [Rule] = []) { self.rules = rules }

    public enum Failure: Error, CustomStringConvertible {
        case unreadable(URL, String)

        public var description: String {
            switch self {
            case .unreadable(let url, let reason):
                "\(url.path) exists but could not be read: \(reason). "
                + "Refusing to run — a file that exists means exclusions were intended, and "
                + "continuing without them is how deleted photos get uploaded."
            }
        }
    }

    /// Loads the rules for a library root.
    ///
    /// Absent is not an error — it means no exclusions, per the rule above. *Unreadable* is a
    /// different thing entirely: the file exists, so exclusions were intended, and proceeding
    /// without them silently changes what gets uploaded. §7 already aborts a run on the
    /// byte-size mismatch for the same reason.
    public static func load(forLibraryAt root: URL) throws -> IgnoreRules {
        let url = root.appendingPathComponent(filename)
        guard FileManager.default.fileExists(atPath: url.path) else { return IgnoreRules() }
        do {
            return parse(try String(contentsOf: url, encoding: .utf8))
        } catch {
            throw Failure.unreadable(url, "\(error)")
        }
    }

    public static func parse(_ text: String) -> IgnoreRules {
        var rules: [Rule] = []
        for (offset, raw) in text.split(separator: "\n", omittingEmptySubsequences: false).enumerated() {
            let line = raw.trimmingCharacters(in: .whitespaces)
            if line.isEmpty || line.hasPrefix("#") { continue }

            let isDirectory = line.hasSuffix("/")
            let body = isDirectory ? String(line.dropLast()) : line
            if body.isEmpty { continue }

            rules.append(Rule(pattern: fold(body),
                              isDirectory: isDirectory,
                              isAnchored: body.contains("/"),
                              source: line,
                              line: offset + 1))
        }
        return IgnoreRules(rules: rules)
    }

    /// Case- and composition-folded, so a file written on a Mac (commonly NFD) still matches
    /// `Hochfügen*` against an NFC path. `AlbumInfo.name`, `sourcePath` and `PhotoRow.filename`
    /// already normalise the same way — §2 names composition as the hazard that costs an album.
    static func fold(_ text: String) -> String {
        text.precomposedStringWithCanonicalMapping.lowercased()
    }

    /// The index of the first rule excluding this entry, or `nil`.
    ///
    /// Returning the index rather than a `Bool` is what lets the caller count rule usage, and
    /// therefore report a rule that matched nothing — which is the only way a typo like
    /// `*.pds` is distinguishable from a rule that is simply not needed today.
    public func matchIndex(name: String, relativePath: String, isDirectory: Bool) -> Int? {
        let foldedName = Self.fold(name)
        let foldedPath = Self.fold(relativePath)
        for index in rules.indices {
            let rule = rules[index]
            // A directory pattern never excludes a file: `.dtrash/` should not match a stray
            // file called `.dtrash`.
            if rule.isDirectory && !isDirectory { continue }
            let subject = rule.isAnchored ? foldedPath : foldedName
            if fnmatch(rule.pattern, subject, FNM_PATHNAME) == 0 { return index }
        }
        return nil
    }
}
