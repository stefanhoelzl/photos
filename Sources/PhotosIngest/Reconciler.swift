import Foundation
import PhotosCatalog
import PhotosCore
import PhotosLibrary

/// One album as the run intends to leave it.
public struct AlbumPlan: Sendable {
    public var id: UUID
    public var name: String
    /// Relative to `$LIBRARY_ROOT`. Never empty: loose files at the root are not an album.
    public var sourcePath: String
    public var parent: UUID?
    public var directory: URL
    /// The shard as the zone currently holds it, or `nil` for an album about to be minted.
    public var existing: Shard?
    /// Files with no row yet. Comes from the walk, so `.photosignore` applies (§7).
    public var uploads: [URL]
    /// Every file the walk offered for this album, claimed or not.
    ///
    /// Classification needs the whole set, not just the new files. A Live Photo is a HEIC
    /// *and* a MOV that pairs with it, and the MOV has no row of its own — so classifying
    /// only the unclaimed files would hand the classifier a lone MOV, which is an ordinary
    /// video, and every Live Photo's MOV would be transcoded and uploaded a second time.
    public var files: [URL]
    /// Rows whose file is still on disk.
    public var keep: [PhotoRow]
    /// Rows whose file is gone. Comes from `stat`, so `.photosignore` does *not* apply —
    /// broadening a rule must never look like a deletion.
    public var drop: [PhotoRow]
    /// A directory holding both files and sub-albums. §2 says an album has sub-albums XOR
    /// photos, so the loose files are not ingested and the children go up normally.
    public var mixedFileCount: Int

    public var isNew: Bool { existing == nil }

    /// Whether the shard has to be rewritten at all. A rename or a re-parent counts: both
    /// are metadata writes that move nothing (§2).
    public var needsWrite: Bool {
        if isNew { return true }
        if !uploads.isEmpty || !drop.isEmpty { return true }
        guard let info = existing?.info else { return true }
        return info.name != name || info.parent != parent || info.sourcePath != sourcePath
    }
}

/// A shard whose directory is gone. `rm -rf` is the only gesture that means this.
public struct AlbumDeletion: Sendable {
    public var shard: Shard
    public var sourcePath: String
}

/// A shard the laptop has never owned: the phone made it. Archive-only (§7) — copied down
/// and claimed, never rewritten beyond `source_path`.
public struct PullPlan: Sendable {
    public var shard: Shard
    /// Where it will land, relative to `$LIBRARY_ROOT`.
    public var sourcePath: String
}

/// What one run intends to do.
public struct IngestPlan: Sendable {
    public var albums: [AlbumPlan] = []
    public var deletions: [AlbumDeletion] = []
    public var pulls: [PullPlan] = []
    /// Directories claimed by a shard this build cannot read. Left completely alone: not
    /// uploaded to, not deleted, not counted as new (§3).
    public var blockedByUnreadable: [ShardProbe] = []
    /// Loose files directly in `$LIBRARY_ROOT`. The library is a directory of albums, so
    /// these are reported and never ingested.
    public var looseRootFiles: [URL] = []
    /// Abort conditions, collected so the report can name all of them at once.
    public var mismatches: [ByteMismatch] = []

    public var uploadCount: Int { albums.reduce(0) { $0 + $1.uploads.count } }
    public var dropCount: Int { albums.reduce(0) { $0 + $1.drop.count } }
    public var hasWork: Bool {
        albums.contains(where: \.needsWrite) || !deletions.isEmpty || !pulls.isEmpty
    }
}

/// Works out what the zone should look like, given a library and the shards it already has.
///
/// Pure apart from `stat`: no network, no imaging, no encoding. That is deliberate — this is
/// where every rule that can lose data lives, so it has to be reachable from a test with a
/// temporary directory and nothing else.
public struct Reconciler: Sendable {

    public var root: URL
    public var albumFilter: String?
    var fileExists: @Sendable (URL) -> Bool
    var fileSize: @Sendable (URL) -> Int64?

    public init(root: URL, albumFilter: String? = nil) {
        self.root = root
        self.albumFilter = albumFilter
        self.fileExists = { FileManager.default.fileExists(atPath: $0.path) }
        self.fileSize = {
            (try? FileManager.default.attributesOfItem(atPath: $0.path)[.size] as? Int64) ?? nil
        }
    }

    public func plan(contents: LibraryContents, shards: [Shard],
                     unreadable: [ShardProbe]) -> IngestPlan {
        var plan = IngestPlan()

        // Shards a *previous* run of this tool wrote, keyed by the folder they claim.
        var byPath: [String: Shard] = [:]
        for shard in shards {
            guard let path = shard.info.sourcePath, !path.isEmpty else { continue }
            byPath[normalise(path)] = shard
        }
        let blockedPaths = Set(unreadable.compactMap { $0.sourcePath.map(normalise) })
        plan.blockedByUnreadable = unreadable

        // Directories the walk found media in. The root itself is not an album: a stray
        // file beside the album folders must not re-parent all 240 of them under a new
        // root album, so it is reported instead.
        var filesByPath: [String: [URL]] = [:]
        for album in contents.albums {
            let path = normalise(album.relativePath)
            if path.isEmpty {
                plan.looseRootFiles = album.files
            } else {
                filesByPath[path] = album.files
            }
        }

        // Every directory that has to exist as an album: one that holds media, every
        // ancestor of one (§10's containers — the walker emits nothing for `Kalifornien`,
        // which holds 17 children and no photos of its own), and any directory a shard
        // still claims, so an album emptied of files survives with zero photos.
        var albumPaths = Set(filesByPath.keys)
        for path in filesByPath.keys {
            for ancestor in ancestors(of: path) { albumPaths.insert(ancestor) }
        }
        for (path, _) in byPath where directoryExists(path) {
            albumPaths.insert(path)
            for ancestor in ancestors(of: path) where directoryExists(ancestor) {
                albumPaths.insert(ancestor)
            }
        }
        albumPaths.subtract(blockedPaths)

        // Identity is assigned before anything is planned, so a child can name its parent
        // whether or not that parent already exists in the zone.
        let sorted = albumPaths.sorted()
        var idByPath: [String: UUID] = [:]
        for path in sorted { idByPath[path] = byPath[path]?.info.id ?? UUID() }

        for path in sorted {
            guard matchesFilter(path) else { continue }
            let directory = url(for: path)
            let existing = byPath[path]
            let children = sorted.contains { $0 != path && $0.hasPrefix(path + "/") }
            let ownFiles = filesByPath[path] ?? []
            // §2: sub-albums XOR photos. The children are unambiguous and go up; the loose
            // files do not, and are named in the report.
            let files = children ? [] : ownFiles

            var album = AlbumPlan(
                id: idByPath[path]!,
                name: name(of: path),
                sourcePath: path,
                parent: parentID(of: path, in: idByPath),
                directory: directory,
                existing: existing,
                uploads: [],
                files: files,
                keep: [],
                drop: [],
                mixedFileCount: children ? ownFiles.count : 0
            )

            let rows = existing?.photos ?? []
            var claimedNames = Set<String>()
            for row in rows {
                claimedNames.insert(normalise(row.filename))
                claimedNames.insert(normalise(row.diskFilename))
            }
            album.uploads = files.filter { !claimedNames.contains(normalise($0.lastPathComponent)) }

            for row in rows {
                // Existence is a `stat`, never the walk: `.photosignore` says what may be
                // uploaded and nothing else, so broadening a rule can only ever stop an
                // upload — it can never make a photo look deleted (§7).
                let source = directory.appending(path: row.diskFilename)
                let zone = directory.appending(path: row.filename)
                let sourceThere = fileExists(source)
                guard sourceThere || fileExists(zone) else {
                    album.drop.append(row)
                    continue
                }
                album.keep.append(row)
                // Only a row whose original *is* the file on disk can be size-checked: a
                // video has no original in the zone and a carved RAW's blob is the JPEG.
                if sourceThere, row.byteCountIsCheckable,
                   let recorded = row.bytes, let found = fileSize(source), found != recorded {
                    plan.mismatches.append(ByteMismatch(albumPath: path,
                                                        filename: row.diskFilename,
                                                        recorded: recorded, found: found))
                }
            }

            plan.albums.append(album)
        }

        // A shard claiming a directory that is gone. Nothing else means this: an album
        // emptied of files still has its directory, and so still has an album.
        for (path, shard) in byPath.sorted(by: { $0.key < $1.key })
        where !albumPaths.contains(path) && !blockedPaths.contains(path) {
            guard matchesFilter(path), !directoryExists(path) else { continue }
            plan.deletions.append(AlbumDeletion(shard: shard, sourcePath: path))
        }

        // Albums with no `source_path` were made by the phone. §7's pull is archive-only:
        // copy them down, claim them, and from then on they are ordinary albums.
        let taken = albumPaths.union(byPath.keys)
        var reserved = taken
        for shard in shards.sorted(by: { $0.info.id.uuidString < $1.info.id.uuidString })
        where shard.info.sourcePath?.isEmpty ?? true {
            let path = availablePath(for: shard, avoiding: reserved)
            guard matchesFilter(path) else { continue }
            reserved.insert(path)
            plan.pulls.append(PullPlan(shard: shard, sourcePath: path))
        }

        return plan
    }

    // MARK: - Paths

    func normalise(_ path: String) -> String {
        var p = path.precomposedStringWithCanonicalMapping
        while p.hasPrefix("/") { p.removeFirst() }
        while p.hasSuffix("/") { p.removeLast() }
        return p
    }

    func url(for path: String) -> URL {
        path.isEmpty ? root : root.appending(path: path)
    }

    func directoryExists(_ path: String) -> Bool {
        var isDirectory: ObjCBool = false
        let exists = FileManager.default.fileExists(atPath: url(for: path).path,
                                                   isDirectory: &isDirectory)
        return exists && isDirectory.boolValue
    }

    /// `a/b/c` → `a/b`, `a`. Never the empty string: the root is not an album.
    func ancestors(of path: String) -> [String] {
        var result: [String] = []
        var components = path.split(separator: "/").map(String.init)
        while components.count > 1 {
            components.removeLast()
            result.append(components.joined(separator: "/"))
        }
        return result
    }

    func name(of path: String) -> String {
        String(path.split(separator: "/").last ?? "")
    }

    func parentID(of path: String, in ids: [String: UUID]) -> UUID? {
        for ancestor in ancestors(of: path) {
            if let id = ids[ancestor] { return id }
        }
        return nil
    }

    func matchesFilter(_ path: String) -> Bool {
        guard let albumFilter, !albumFilter.isEmpty else { return true }
        return path.range(of: albumFilter, options: .caseInsensitive) != nil
    }

    /// Where a phone-owned album lands. Its name is not guaranteed unique (§2 permits
    /// duplicates), so a taken directory gets the album id appended rather than merged into.
    func availablePath(for shard: Shard, avoiding taken: Set<String>) -> String {
        let base = normalise(shard.info.name.replacingOccurrences(of: "/", with: "-"))
        let candidate = base.isEmpty ? shard.info.id.uuidString : base
        guard taken.contains(candidate) || directoryExists(candidate) else { return candidate }
        return "\(candidate) (\(shard.info.id.uuidString.prefix(8)))"
    }
}
