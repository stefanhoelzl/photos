import Foundation
import CImaging

/// Reads an encoded image's dimensions back.
///
/// Exists because verification needs it: the acceptance scan reports what the pipeline
/// actually produced, and "the thumbnail is 256×256" is only worth asserting if it is
/// measured from the encoded bytes rather than from the intent that produced them.
public enum ImageProbe {

    public static func dimensions(of url: URL) throws -> (width: Int, height: Int) {
        // maxLongEdge 1 asks for the smallest scale libjpeg offers, and `sourceWidth` still
        // reports the photograph rather than the buffer -- so reading the dimensions of a
        // 164 MP panorama costs a 1/8 decode instead of 494 MB. Formats without shrink-on-load
        // decode fully regardless; there is nothing cheaper to ask them for.
        let image = try PixelImage.decode(contentsOf: url, maxLongEdge: 1)
        return (image.sourceWidth, image.sourceHeight)
    }

    public static func dimensions(ofEncoded data: Data) throws -> (width: Int, height: Int) {
        let url = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("probe-\(UUID().uuidString)")
        try data.write(to: url)
        defer { try? FileManager.default.removeItem(at: url) }
        return try dimensions(of: url)
    }
}
