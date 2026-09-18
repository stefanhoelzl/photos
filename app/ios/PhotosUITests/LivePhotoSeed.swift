import AVFoundation
import CoreImage
import CoreMedia
import Photos
import XCTest

/// Puts a Live Photo into the simulator's library, and edits it when asked (§8's open question).
///
/// Whether an *edited* Live Photo's full-size still and paired video still carry the content
/// identifier that pairs them is something only the platform can answer. So this creates the pair
/// from the fixture's still and MOV exactly as an import would, then — unless told not to — edits it
/// through `PHLivePhotoEditingContext`, which re-renders the still *and* every frame of the video,
/// the same path the Photos app's own edits take. The scenario then uploads it through the app and
/// asks `PHLivePhoto` whether the uploaded pair still assembles.
///
///   TEST_RUNNER_PHOTOS_LIVE_STILL       the still, a host path the simulator can read
///   TEST_RUNNER_PHOTOS_LIVE_VIDEO       the paired MOV
///   TEST_RUNNER_PHOTOS_LIVE_IDENTIFIER  the content identifier the still's maker note carries
///   TEST_RUNNER_PHOTOS_LIVE_EDIT        "no" to leave it unedited, "both" for one of each
///
/// Prints `PHOTOS_SEEDED <asset id> edited|unedited` for each Live Photo it made. "both" is what
/// the suite asks for: its edited and unedited scenarios then share one `xcodebuild`, which costs
/// 20-30 s to start on the runner, rather than starting one each.
///
/// Editing an asset makes iOS ask "Allow … to modify this photo?", which this test answers itself.
final class LivePhotoSeed: XCTestCase {

    func testSeedLivePhoto() throws {
        let environment = ProcessInfo.processInfo.environment
        let still = URL(fileURLWithPath: try XCTUnwrap(environment["PHOTOS_LIVE_STILL"]))
        let video = URL(fileURLWithPath: try XCTUnwrap(environment["PHOTOS_LIVE_VIDEO"]))
        let contentIdentifier = try XCTUnwrap(environment["PHOTOS_LIVE_IDENTIFIER"])
        let edits = switch environment["PHOTOS_LIVE_EDIT"] {
        case "no": [false]
        case "both": [false, true]
        default: [true]
        }
        // Written for both cases, so the unedited control differs from the edited one only by the edit.
        let movie = try appleMovie(from: video, contentIdentifier: contentIdentifier)
        for edit in edits {
            let id = try seed(still: still, movie: movie)
            if edit { try invert(try XCTUnwrap(PHAsset.fetchAssets(withLocalIdentifiers: [id], options: nil).firstObject)) }
            print("PHOTOS_SEEDED", id, edit ? "edited" : "unedited")
        }
    }

    /// One Live Photo made from `still` and `movie`, as an import makes it. Its local identifier.
    private func seed(still: URL, movie: URL) throws -> String {
        var identifier: String?
        try PHPhotoLibrary.shared().performChangesAndWait {
            let request = PHAssetCreationRequest.forAsset()
            request.addResource(with: .photo, fileURL: still, options: nil)
            request.addResource(with: .pairedVideo, fileURL: movie, options: nil)
            identifier = request.placeholderForCreatedAsset?.localIdentifier
        }
        let id = try XCTUnwrap(identifier, "the library created no asset")
        let asset = try XCTUnwrap(PHAsset.fetchAssets(withLocalIdentifiers: [id], options: nil).firstObject)
        XCTAssertTrue(asset.mediaSubtypes.contains(.photoLive), "the still and MOV were imported as one Live Photo")
        return id
    }

    /// The fixture's video, rewritten as an iPhone writes a Live Photo's MOV.
    ///
    /// The generated fixture carries the content identifier and nothing else, which is all a pair
    /// needs to *assemble* (DESIGN §6). Editing needs more: without the timed
    /// `com.apple.quicktime.still-image-time` track, `PHLivePhotoEditingContext` cannot place the
    /// still in the video — PhotoKit logged "invalid or missing image display time" and the save
    /// failed with PHPhotosErrorDomain -1. So the movie is re-encoded here with both.
    private func appleMovie(from source: URL, contentIdentifier: String) throws -> URL {
        let asset = AVURLAsset(url: source)
        let track = try XCTUnwrap(asset.tracks(withMediaType: .video).first, "the fixture MOV has no video")
        let output = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString)
            .appendingPathExtension("mov")

        let reader = try AVAssetReader(asset: asset)
        let frames = AVAssetReaderTrackOutput(
            track: track,
            outputSettings: [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA],
        )
        reader.add(frames)

        let writer = try AVAssetWriter(outputURL: output, fileType: .mov)
        let size = track.naturalSize
        let video = AVAssetWriterInput(mediaType: .video, outputSettings: [
            AVVideoCodecKey: AVVideoCodecType.h264,
            AVVideoWidthKey: size.width,
            AVVideoHeightKey: size.height,
        ])
        video.expectsMediaDataInRealTime = false
        video.transform = track.preferredTransform
        writer.add(video)

        let identifierItem = AVMutableMetadataItem()
        identifierItem.keySpace = .quickTimeMetadata
        identifierItem.key = "com.apple.quicktime.content.identifier" as NSString
        identifierItem.value = contentIdentifier as NSString
        identifierItem.dataType = "com.apple.metadata.datatype.UTF-8"
        writer.metadata = [identifierItem]

        var format: CMFormatDescription?
        let specification = [
            kCMMetadataFormatDescriptionMetadataSpecificationKey_Identifier: "mdta/com.apple.quicktime.still-image-time",
            kCMMetadataFormatDescriptionMetadataSpecificationKey_DataType: "com.apple.metadata.datatype.int8",
        ] as NSDictionary
        CMMetadataFormatDescriptionCreateWithMetadataSpecifications(
            allocator: kCFAllocatorDefault,
            metadataType: kCMMetadataFormatType_Boxed,
            metadataSpecifications: [specification] as CFArray,
            formatDescriptionOut: &format,
        )
        let timed = AVAssetWriterInput(mediaType: .metadata, outputSettings: nil, sourceFormatHint: format)
        let adaptor = AVAssetWriterInputMetadataAdaptor(assetWriterInput: timed)
        writer.add(timed)

        XCTAssertTrue(writer.startWriting(), "the movie writer would not start: \(String(describing: writer.error))")
        XCTAssertTrue(reader.startReading(), "the fixture MOV would not read: \(String(describing: reader.error))")
        writer.startSession(atSourceTime: .zero)

        let stillTime = AVMutableMetadataItem()
        stillTime.keySpace = .quickTimeMetadata
        stillTime.key = "com.apple.quicktime.still-image-time" as NSString
        stillTime.value = 0 as NSNumber
        stillTime.dataType = "com.apple.metadata.datatype.int8"
        while !timed.isReadyForMoreMediaData { Thread.sleep(forTimeInterval: 0.01) }
        adaptor.append(AVTimedMetadataGroup(
            items: [stillTime],
            timeRange: CMTimeRange(start: .zero, duration: CMTime(value: 1, timescale: 30)),
        ))
        timed.markAsFinished()

        while let sample = frames.copyNextSampleBuffer() {
            while !video.isReadyForMoreMediaData { Thread.sleep(forTimeInterval: 0.01) }
            video.append(sample)
        }
        video.markAsFinished()

        let written = expectation(description: "movie written")
        writer.finishWriting { written.fulfill() }
        wait(for: [written], timeout: 60)
        XCTAssertEqual(writer.status, .completed, "the movie did not finish: \(String(describing: writer.error))")
        return output
    }

    /// An inverted still and video, saved as the asset's current version.
    private func invert(_ asset: PHAsset) throws {
        let options = PHContentEditingInputRequestOptions()
        options.isNetworkAccessAllowed = true
        let gotInput = expectation(description: "editing input")
        var input: PHContentEditingInput?
        asset.requestContentEditingInput(with: options) { received, _ in
            input = received
            gotInput.fulfill()
        }
        wait(for: [gotInput], timeout: 60)
        let editingInput = try XCTUnwrap(input, "no editing input for the Live Photo")
        let context = try XCTUnwrap(
            PHLivePhotoEditingContext(livePhotoEditingInput: editingInput),
            "the asset has no Live Photo to edit",
        )
        context.frameProcessor = { frame, _ in frame.image.applyingFilter("CIColorInvert") }

        let output = PHContentEditingOutput(contentEditingInput: editingInput)
        output.adjustmentData = PHAdjustmentData(
            formatIdentifier: "net.stho.photos.uitests",
            formatVersion: "1",
            data: Data("invert".utf8),
        )
        let saved = expectation(description: "rendered")
        var renderFailure: Error?
        context.saveLivePhoto(to: output, options: nil) { success, error in
            if !success { renderFailure = error ?? NSError(domain: "LivePhotoSeed", code: 1) }
            saved.fulfill()
        }
        wait(for: [saved], timeout: 180)
        if let renderFailure { throw renderFailure }

        // Committing an edit is where iOS asks; the change only completes once someone answers. A
        // semaphore rather than an expectation: this waits in short slices so it can tap between
        // them, and XCTest allows an expectation to be waited on only once.
        let committed = DispatchSemaphore(value: 0)
        var commitFailure: Error?
        PHPhotoLibrary.shared().performChanges({
            PHAssetChangeRequest(for: asset).contentEditingOutput = output
        }) { success, error in
            if !success { commitFailure = error ?? NSError(domain: "LivePhotoSeed", code: 2) }
            committed.signal()
        }
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let deadline = Date().addingTimeInterval(60)
        var done = false
        while !done, Date() < deadline {
            done = committed.wait(timeout: .now() + 0.5) == .success
            if done { break }
            for label in ["Modify", "Allow"] where springboard.buttons[label].exists {
                springboard.buttons[label].tap()
            }
        }
        XCTAssertTrue(done, "the edit was not committed within 60 s")
        if let commitFailure { throw commitFailure }
    }
}
