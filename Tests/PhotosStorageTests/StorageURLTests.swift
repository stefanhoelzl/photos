import Foundation
import Testing
@testable import PhotosStorage

/// One storage URL has to yield endpoint, signing region and zone (§1), and the
/// zone doubles as the access key ID — so a parsing slip breaks every signature.
struct StorageURLTests {

    @Test("the documented bunny.net form")
    func canonicalForm() throws {
        let url = try StorageURL("https://de-s3.storage.bunnycdn.com/my-photos")
        #expect(url.endpoint.absoluteString == "https://de-s3.storage.bunnycdn.com")
        #expect(url.region == "de")
        #expect(url.zone == "my-photos")
    }

    @Test("trailing slash, nested path and query are all tolerated",
          arguments: [
            "https://de-s3.storage.bunnycdn.com/my-photos/",
            "https://de-s3.storage.bunnycdn.com/my-photos/meta",
            "https://de-s3.storage.bunnycdn.com/my-photos/meta/Trips/",
            "  https://de-s3.storage.bunnycdn.com/my-photos  ",
          ])
    func toleratesShapes(input: String) throws {
        let url = try StorageURL(input)
        #expect(url.zone == "my-photos")
        #expect(url.region == "de")
        #expect(url.endpoint.absoluteString == "https://de-s3.storage.bunnycdn.com")
    }

    @Test("region comes from the host's <region>-s3 label",
          arguments: [
            ("https://de-s3.storage.bunnycdn.com/z", "de"),
            ("https://ny-s3.storage.bunnycdn.com/z", "ny"),
            ("https://UK-S3.storage.bunnycdn.com/z", "uk"),
            ("https://storage.bunnycdn.com/z", StorageURL.defaultRegion),
            ("https://s3.example.com/z", StorageURL.defaultRegion),
        ])
    func regionParsing(input: String, expected: String) throws {
        #expect(try StorageURL(input).region == expected)
    }

    @Test("a non-default port is kept on the endpoint")
    func port() throws {
        let url = try StorageURL("http://127.0.0.1:9090/test-zone")
        #expect(url.endpoint.absoluteString == "http://127.0.0.1:9090")
        #expect(url.zone == "test-zone")
    }

    @Test("rejects what cannot be a storage URL")
    func rejections() {
        #expect(throws: StorageURL.ParseError.missingZone) {
            try StorageURL("https://de-s3.storage.bunnycdn.com")
        }
        #expect(throws: StorageURL.ParseError.missingZone) {
            try StorageURL("https://de-s3.storage.bunnycdn.com/")
        }
        #expect(throws: StorageURL.ParseError.unsupportedScheme("ftp")) {
            try StorageURL("ftp://de-s3.storage.bunnycdn.com/zone")
        }
        #expect(throws: StorageURL.ParseError.missingScheme) {
            try StorageURL("de-s3.storage.bunnycdn.com/zone")
        }
    }

    @Test("parse errors say what is wrong, per §1's no-opaque-errors rule")
    func errorsAreLegible() {
        #expect(StorageURL.ParseError.missingZone.description.contains("<zone>"))
        #expect(StorageURL.ParseError.missingScheme.description.contains("https://"))
    }
}

struct ETagTests {

    @Test("quotes are stripped on read and restored on send")
    func quoting() {
        let tag = ETag(header: "\"d41d8cd98f00b204e9800998ecf8427e\"")
        #expect(tag.value == "d41d8cd98f00b204e9800998ecf8427e")
        #expect(tag.headerValue == "\"d41d8cd98f00b204e9800998ecf8427e\"")
    }

    @Test("a multipart ETag is carried unchanged — it is not an MD5")
    func multipartShape() {
        let tag = ETag(header: "\"9bb58f26192e4ba00f01e2e7b136bbd8-5\"")
        #expect(tag.value == "9bb58f26192e4ba00f01e2e7b136bbd8-5")
    }

    @Test("quoted and unquoted forms of the same tag compare equal")
    func equalityAcrossQuoting() {
        // The whole point: a stray pair of quotes on one side of the sync diff
        // would re-download the entire library.
        #expect(ETag(header: "\"abc\"") == ETag(unquoted: "abc"))
    }

    @Test("weak validators lose their prefix")
    func weakValidator() {
        #expect(ETag(header: "W/\"abc\"").value == "abc")
    }
}
