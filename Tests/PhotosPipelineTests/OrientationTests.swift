import Foundation
import Testing
import PhotosCore
@testable import PhotosPipeline

/// §3 promises that stored dimensions are already rotated and that no consumer applies
/// orientation. libjpeg does not rotate on its own, so that promise is only true because the
/// pipeline makes it true — and nothing proved it until these existed.
struct OrientationTests {

    @Test("a transposing orientation swaps the decoded dimensions", arguments: [5, 6, 7, 8])
    func transposingOrientations(orientation: Int) throws {
        let data = try Synthetic.jpeg(width: 400, height: 300, orientation: orientation)
        let decoded = try PixelImage.decode(jpeg: data, maxLongEdge: 0)
        #expect(decoded.width == 300, "orientation \(orientation) should transpose")
        #expect(decoded.height == 400)
        #expect(decoded.sourceWidth == 300)
        #expect(decoded.sourceHeight == 400)
    }

    @Test("a non-transposing orientation leaves dimensions alone", arguments: [1, 2, 3, 4])
    func nonTransposingOrientations(orientation: Int) throws {
        let data = try Synthetic.jpeg(width: 400, height: 300, orientation: orientation)
        let decoded = try PixelImage.decode(jpeg: data, maxLongEdge: 0)
        #expect(decoded.width == 400)
        #expect(decoded.height == 300)
    }

    @Test("orientation is applied to the pixels, not merely to the geometry")
    func orientationMovesPixels() throws {
        // Orientation 6 is "rotate 90 clockwise for display", so the source's top-left corner
        // must end up in the top-right. Checking geometry alone would pass even if the buffer
        // were rotated the wrong way — which is exactly how the video path's sign bug hid.
        let image = try Synthetic.image(width: 64, height: 32)
        image.withPixels { pixels in
            let mutable = UnsafeMutablePointer(mutating: pixels.baseAddress!)
            for y in 0..<4 {
                for x in 0..<4 {
                    let i = (y * 64 + x) * 3
                    mutable[i] = 255; mutable[i + 1] = 0; mutable[i + 2] = 0
                }
            }
        }
        let marked = try image.encodedJPEG(quality: 95)

        var oriented = Data([0xFF, 0xD8, 0xFF, 0xE1])
        let plain = try Synthetic.jpeg(width: 64, height: 32, orientation: 6)
        _ = plain
        // Re-wrap the marked pixels with the orientation tag by reusing the generator's APP1.
        let template = try Synthetic.jpeg(width: 8, height: 8, orientation: 6)
        let app1Length = Int(template[4]) << 8 | Int(template[5])
        oriented.append(template[4..<(4 + app1Length)])
        oriented.append(marked.dropFirst(2))

        let decoded = try PixelImage.decode(jpeg: oriented, maxLongEdge: 0)
        #expect(decoded.width == 32 && decoded.height == 64)

        let topRightIsRed = decoded.withPixels { pixels -> Bool in
            let i = (1 * 32 + 30) * 3
            return pixels[i] > 150 && pixels[i + 1] < 110 && pixels[i + 2] < 110
        }
        #expect(topRightIsRed, "orientation 6 should carry the top-left corner to the top-right")
    }

    @Test("shrink-on-load still reports the photograph's dimensions")
    func sourceDimensionsSurviveShrinking() throws {
        // The bug this caught: row.width was reporting the decoder's buffer (2250) rather than
        // the photo (3000), because libjpeg had scaled by 6/8 on the way in.
        let data = try Synthetic.jpeg(width: 3000, height: 2000)
        let decoded = try PixelImage.decode(jpeg: data, maxLongEdge: 2048)
        #expect(decoded.width < 3000, "shrink-on-load should have engaged")
        #expect(decoded.sourceWidth == 3000)
        #expect(decoded.sourceHeight == 2000)
    }

    @Test("source dimensions survive resizing and cropping")
    func sourceDimensionsSurviveTransforms() throws {
        let data = try Synthetic.jpeg(width: 3000, height: 2000)
        let decoded = try PixelImage.decode(jpeg: data, maxLongEdge: 2048)
        let preview = try decoded.resizedFitting(longEdge: 2048)
        let thumbnail = try decoded.squareCropped(edge: 256)
        #expect(preview.sourceWidth == 3000 && preview.sourceHeight == 2000)
        #expect(thumbnail.sourceWidth == 3000 && thumbnail.sourceHeight == 2000)
    }
}
