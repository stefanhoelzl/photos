import Foundation
import PhotosCore

/// One thing the pipeline can be asked to derive from.
public struct MediaItem: Sendable, Hashable {
    public enum Kind: Sendable, Hashable {
        /// A photograph, in any container the still path can decode.
        case still
        /// A Canon raw. Carved rather than decoded — see `CR2`.
        case raw
        /// A Live Photo: the HEIC still, plus the MOV that pairs with it.
        case livePhoto(video: URL)
        /// A video. §5 keeps the original on the laptop, so only the transcode is uploaded.
        case video
    }

    public var url: URL
    public var kind: Kind
    public var byteCount: Int64

    public init(url: URL, kind: Kind, byteCount: Int64) {
        self.url = url
        self.kind = kind
        self.byteCount = byteCount
    }

    public var filename: String { url.lastPathComponent }
}

/// A file the classifier deliberately did not turn into a `MediaItem`.
///
/// Reported rather than discarded: a run that silently omitted things would be a run you
/// could not audit.
///
/// There is no `excluded` case. Exclusions are `.photosignore`'s business and the walker
/// applies them, so a file the library asked to ignore never reaches the classifier at all.
public struct SkippedFile: Sendable, Hashable {
    public enum Reason: Sendable, Hashable {
        /// Sniffed as nothing the pipeline handles.
        case unrecognisedFormat
        /// A MOV that pairs with a HEIC; it is uploaded as part of that Live Photo, not alone.
        case consumedAsLivePhotoVideo
        case unreadable(String)
    }

    public var url: URL
    public var reason: Reason
}

/// Turns one album's files into items and strays.
///
/// Takes a file list rather than walking, and the list it is given has already had
/// `.photosignore` applied by `LibraryWalker`. So this type knows about media formats and
/// Live Photo pairing, and nothing whatever about what counts as junk.
///
/// It does read files — pairing on `content.identifier` is not a filename question — but only
/// the HEICs and MOVs, and only their metadata.
public struct MediaClassifier: Sendable {

    public var backend: any ImageBackend

    public init(backend: any ImageBackend = NativeImageBackend()) {
        self.backend = backend
    }

    public struct Result: Sendable {
        public var items: [MediaItem] = []
        public var skipped: [SkippedFile] = []
    }

    public func classify(_ urls: [URL]) -> Result {
        var result = Result()

        // Sniffing reads a bounded header prefix, so this pass costs one small read per file
        // even when a file is 2.66 GB of SQLite.
        var stills: [(url: URL, format: MediaFormat, size: Int64)] = []
        var videos: [(url: URL, size: Int64)] = []

        for url in urls {
            let size: Int64
            do {
                let values = try url.resourceValues(forKeys: [.fileSizeKey])
                size = Int64(values.fileSize ?? 0)
            } catch {
                result.skipped.append(SkippedFile(url: url, reason: .unreadable("\(error)")))
                continue
            }

            switch MediaFormat.sniff(url) {
            case .video: videos.append((url, size))
            case .unknown: result.skipped.append(SkippedFile(url: url, reason: .unrecognisedFormat))
            case let format: stills.append((url, format, size))
            }
        }

        // Decision 14: pair on content.identifier, not on filenames. Both signals agree on
        // every one of this library's 312 MOVs, but a filename match is a coincidence that
        // holds until someone drops an unrelated IMG_1234.MOV beside an IMG_1234.HEIC — and
        // then it silently swallows a real video into a Live Photo.
        var videoByIdentifier: [String: URL] = [:]
        var pairedVideos: Set<URL> = []
        for video in videos {
            guard let info = try? VideoInfo.probe(video.url),
                  let identifier = info.contentIdentifier else { continue }
            videoByIdentifier[identifier] = video.url
        }

        for still in stills {
            var kind = MediaItem.Kind.still
            if still.format == .cr2 {
                kind = .raw
            } else if still.format == .heif,
                      let tags = try? backend.rawTags(at: still.url),
                      let identifier = tags["AppleContentIdentifier"]?.stringValue,
                      let video = videoByIdentifier[identifier] {
                kind = .livePhoto(video: video)
                pairedVideos.insert(video)
            }
            result.items.append(MediaItem(url: still.url, kind: kind, byteCount: still.size))
        }

        for video in videos {
            if pairedVideos.contains(video.url) {
                result.skipped.append(SkippedFile(url: video.url,
                                                  reason: .consumedAsLivePhotoVideo))
            } else {
                result.items.append(MediaItem(url: video.url, kind: .video,
                                              byteCount: video.size))
            }
        }

        // Stable order, so two runs over the same album report the same thing and a diff of
        // two scan reports shows real changes rather than directory-iteration order.
        result.items.sort { $0.url.path < $1.url.path }
        result.skipped.sort { $0.url.path < $1.url.path }
        return result
    }
}
