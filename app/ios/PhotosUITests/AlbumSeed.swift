import Photos
import XCTest

/// Puts a user album into the simulator's library, holding new assets made from fixture files (§8).
///
/// `simctl addmedia` adds photos but cannot group them, and deleting an album chosen whole is only
/// tested with an album to choose. Creating assets and an album needs no alert under full access.
///
///   TEST_RUNNER_PHOTOS_ALBUM_NAME   the album's title
///   TEST_RUNNER_PHOTOS_ALBUM_FILES  host paths the simulator can read, comma-separated; a
///                                   `.mp4` or `.mov` becomes a video, anything else a photo
///
/// Prints `PHOTOS_SEEDED_ALBUM <album id> <asset id>,<asset id>…`.
final class AlbumSeed: XCTestCase {

    func testSeedAlbum() throws {
        let environment = ProcessInfo.processInfo.environment
        let name = try XCTUnwrap(environment["PHOTOS_ALBUM_NAME"])
        let files = try XCTUnwrap(environment["PHOTOS_ALBUM_FILES"])
            .split(separator: ",")
            .map { URL(fileURLWithPath: String($0)) }

        var album: String?
        var assets: [String] = []
        try PHPhotoLibrary.shared().performChangesAndWait {
            let placeholders = files.compactMap { file -> PHObjectPlaceholder? in
                let request = PHAssetCreationRequest.forAsset()
                let video = ["mp4", "mov"].contains(file.pathExtension.lowercased())
                request.addResource(with: video ? .video : .photo, fileURL: file, options: nil)
                return request.placeholderForCreatedAsset
            }
            let collection = PHAssetCollectionChangeRequest.creationRequestForAssetCollection(withTitle: name)
            collection.addAssets(placeholders as NSArray)
            album = collection.placeholderForCreatedAssetCollection.localIdentifier
            assets = placeholders.map(\.localIdentifier)
        }
        let id = try XCTUnwrap(album, "the library created no album")
        XCTAssertEqual(assets.count, files.count, "every file became an asset")
        print("PHOTOS_SEEDED_ALBUM", id, assets.joined(separator: ","))
    }
}
