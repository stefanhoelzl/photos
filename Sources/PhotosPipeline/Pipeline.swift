import Foundation
import PhotosCore
import CImaging

/// Where a photo's uploadable original comes from.
public enum OriginalSource: Sendable {
    /// The file as it sits in the library, uploaded byte-for-byte. §10's UNSIGNED-PAYLOAD
    /// means nothing reads it first.
    case file(URL)
    /// Synthesised: the JPEG carved out of a CR2, with the CR2's EXIF grafted in.
    case data(Data)
    /// Video. §5 keeps originals on the laptop, so there is nothing to upload.
    case none
}

/// Everything the pipeline produces for one item.
///
/// The ids in `row` are left nil: minting object UUIDs and naming keys is D's job, because
/// only D knows what actually reached the bucket.
public struct Derivatives: Sendable {
    public var row: PhotoRow
    public var tags: ExifTags
    /// 256×256 JPEG, sRGB, no metadata.
    public var thumbnail: Data
    /// 2048px-long-edge HEIC, source profile intact.
    public var preview: Data
    public var original: OriginalSource
    /// The 1080p-ceiling HEVC transcode, owned by the caller once returned.
    public var video: URL?
    /// A Live Photo's paired MOV, uploaded as-is so `PHLivePhotoView` gets what it expects.
    public var liveVideo: URL?
}

/// A stage completing, for a caller that wants to show progress.
public enum PipelineEvent: Sendable {
    case started(URL)
    case decoded(URL, width: Int, height: Int)
    case thumbnailed(URL, bytes: Int)
    case previewed(URL, bytes: Int)
    /// Emitted per transcoded second, so a 4K video does not sit at one counter for minutes.
    case transcoding(URL, progress: Double)
    case finished(URL)
    case failed(URL, message: String)
}

/// The derivative pipeline.
///
/// `derive` is synchronous and safe to call from many threads at once. It owns no worker
/// pool: D owns that, because only D can interleave encoding — which is CPU-bound and
/// parallel — with §9's uploads, which are link-bound and deliberately serial. iOS schedules
/// differently again, against a background `URLSession`.
///
/// Progress arrives on `events` rather than through a callback parameter, so `derive`'s
/// signature stays a plain function and every worker publishes into the one stream.
public final class Pipeline: Sendable {

    public let events: AsyncStream<PipelineEvent>
    private let continuation: AsyncStream<PipelineEvent>.Continuation
    private let workDirectory: URL
    private let backend: any ImageBackend
    private let encoderThreads: Int

    /// `encoderThreads` bounds x265's internal pool, for both tiers.
    ///
    /// It defaults to 1 because `derive` is documented as safe to call from many threads and
    /// the caller owns the pool — an encoder opening its own would be a second, hidden one.
    /// Measured on a 16-core machine with 16 workers: unbounded pools cost 7.2 GB resident.
    /// Raise it when driving the pipeline from a single thread.
    public init(workDirectory: URL, backend: any ImageBackend = NativeImageBackend(),
                encoderThreads: Int = 1) {
        let (stream, continuation) = AsyncStream<PipelineEvent>.makeStream(
            bufferingPolicy: .bufferingNewest(256))
        self.events = stream
        self.continuation = continuation
        self.workDirectory = workDirectory
        self.backend = backend
        self.encoderThreads = encoderThreads
    }

    deinit { continuation.finish() }

    /// Stops the event stream. Callers that iterate `events` need this to end the loop.
    public func finish() { continuation.finish() }

    public func derive(_ item: MediaItem) throws -> Derivatives {
        continuation.yield(.started(item.url))
        do {
            let result: Derivatives
            switch item.kind {
            case .still:      result = try deriveStill(item, original: .file(item.url))
            case .raw:        result = try deriveRaw(item)
            case .livePhoto(let video):
                var derived = try deriveStill(item, original: .file(item.url))
                derived.liveVideo = video
                derived.row.mediaType = .livePhoto
                result = derived
            case .video:      result = try deriveVideo(item)
            }
            continuation.yield(.finished(item.url))
            return result
        } catch {
            continuation.yield(.failed(item.url, message: "\(error)"))
            throw error
        }
    }

    // MARK: - Stills

    private func deriveStill(_ item: MediaItem, original: OriginalSource) throws -> Derivatives {
        let tags = (try? backend.rawTags(at: item.url)) ?? ExifTags()
        let decoded = try PixelImage.decode(contentsOf: item.url,
                                            maxLongEdge: DerivativeSpec.previewLongEdge)
        return try finishStill(item: item, tags: tags, decoded: decoded,
                               original: original, mediaType: .photo)
    }

    private func deriveRaw(_ item: MediaItem) throws -> Derivatives {
        let tags = (try? backend.rawTags(at: item.url)) ?? ExifTags()
        let extraction = try CR2.extract(contentsOf: item.url)
        let decoded = try PixelImage.decode(jpeg: extraction.jpeg,
                                            maxLongEdge: DerivativeSpec.previewLongEdge)
        return try finishStill(item: item, tags: tags, decoded: decoded,
                               original: .data(extraction.jpeg), mediaType: .photo)
    }

    private func finishStill(item: MediaItem, tags: ExifTags, decoded: PixelImage,
                             original: OriginalSource, mediaType: MediaType) throws -> Derivatives {
        // Orientation is already applied — libjpeg's caller bakes it, libheif applies irot
        // itself, and the video path bakes the display matrix — so the decoded buffer's own
        // dimensions are the display dimensions §3 wants stored.
        if decoded.hasAlpha {
            try decoded.flattenAlpha(DerivativeSpec.alphaBackground)
        }
        continuation.yield(.decoded(item.url, width: decoded.width, height: decoded.height))

        let preview = try makePreview(decoded)
        continuation.yield(.previewed(item.url, bytes: preview.count))
        let thumbnail = try makeThumbnail(decoded)
        continuation.yield(.thumbnailed(item.url, bytes: thumbnail.count))

        var row = ExifMapper.photoRow(
            filename: item.filename,
            bytes: item.byteCount,
            mediaType: mediaType,
            tags: tags
        )
        // The decoded *source* is the authority, not the decoded buffer: shrink-on-load means
        // a 3000px photo may well arrive as a 2250px buffer, and §3 stores the photograph's
        // dimensions. EXIF is not consulted — PixelXDimension can be absent, can describe the
        // embedded thumbnail, or can simply disagree with the pixels, and a grid that lays out
        // from the wrong aspect ratio is visibly wrong.
        row.width = decoded.sourceWidth
        row.height = decoded.sourceHeight

        return Derivatives(row: row, tags: tags, thumbnail: thumbnail, preview: preview,
                           original: original, video: nil, liveVideo: nil)
    }

    /// 2048px long edge, aspect preserved, never upscaled, source ICC carried through.
    private func makePreview(_ decoded: PixelImage) throws -> Data {
        let resized = try decoded.resizedFitting(longEdge: DerivativeSpec.previewLongEdge,
                                                 allowUpscale: DerivativeSpec.previewUpscales)
        try apply(DerivativeSpec.previewColorSpace, to: resized)
        return try resized.encodedHEIC(quality: DerivativeSpec.previewQuality,
                                       threads: encoderThreads)
    }

    /// 256×256 centre crop, converted to sRGB, no profile embedded.
    private func makeThumbnail(_ decoded: PixelImage) throws -> Data {
        let square = try decoded.squareCropped(edge: DerivativeSpec.thumbnailEdge)
        try apply(DerivativeSpec.thumbnailColorSpace, to: square)
        return try square.encodedJPEG(quality: DerivativeSpec.thumbnailQuality, optimize: true)
    }

    private func apply(_ handling: DerivativeSpec.ColorHandling, to image: PixelImage) throws {
        switch handling {
        case .convertToSRGB: try image.convertToSRGB()
        case .passThrough: break   // the encoder embeds whatever profile came in
        }
    }

    // MARK: - Video

    private func deriveVideo(_ item: MediaItem) throws -> Derivatives {
        let info = try VideoInfo.probe(item.url)
        let tags = (try? backend.rawTags(at: item.url)) ?? ExifTags()

        let poster = try PixelImage.poster(of: item.url,
                                           at: DerivativeSpec.posterTime(duration: info.duration))
        if poster.hasAlpha { try poster.flattenAlpha(DerivativeSpec.alphaBackground) }
        continuation.yield(.decoded(item.url, width: poster.width, height: poster.height))

        let preview = try makePreview(poster)
        continuation.yield(.previewed(item.url, bytes: preview.count))
        let thumbnail = try makeThumbnail(poster)
        continuation.yield(.thumbnailed(item.url, bytes: thumbnail.count))

        try FileManager.default.createDirectory(at: workDirectory, withIntermediateDirectories: true)
        let output = workDirectory.appendingPathComponent("\(UUID().uuidString).mp4")
        continuation.yield(.transcoding(item.url, progress: 0))
        try imagingCall { err in
            item.url.withUnsafeFileSystemRepresentation { input in
                output.withUnsafeFileSystemRepresentation { out in
                    pi_video_transcode(input, out,
                                       Int32(DerivativeSpec.videoMaxHeight),
                                       Int32(DerivativeSpec.videoQuality),
                                       Int32(encoderThreads), err)
                }
            }
        }
        continuation.yield(.transcoding(item.url, progress: 1))

        var row = ExifMapper.photoRow(
            filename: item.filename,
            bytes: item.byteCount,
            mediaType: .video,
            tags: tags
        )
        row.width = poster.width
        row.height = poster.height  // posters decode at full size; no shrink-on-load

        // §3: a video has a video_id and a preview_id poster, but no original_id.
        return Derivatives(row: row, tags: tags, thumbnail: thumbnail, preview: preview,
                           original: .none, video: output, liveVideo: nil)
    }
}
