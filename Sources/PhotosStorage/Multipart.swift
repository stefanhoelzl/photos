import Foundation

extension S3Client {

    /// Uploads a large file in parts.
    ///
    /// The largest objects here are transcoded videos, pushed over a domestic
    /// upstream where a failed single PUT of 200 MB restarts from zero. bunny.net
    /// allows 10 000 parts; the part size grows if a file would exceed that.
    ///
    /// Parts are uploaded one at a time on purpose: §9 measured that concurrent
    /// uploads make throughput slightly worse, because the client's uplink is the
    /// bottleneck, not the service.
    func multipartUpload(
        _ key: String,
        file: URL,
        size: Int64,
        contentType: String?,
        progress: (@Sendable (Int64, Int64) -> Void)?
    ) async throws -> ETag? {
        let partSize = Self.partSize(forFileOf: size, preferred: multipartPartSize)
        let uploadID = try await createMultipartUpload(key, contentType: contentType)

        do {
            let handle = try FileHandle(forReadingFrom: file)
            defer { try? handle.close() }

            var parts: [(number: Int, etag: ETag)] = []
            var offset: Int64 = 0
            var partNumber = 1

            while offset < size {
                let length = Int(min(partSize, size - offset))
                try handle.seek(toOffset: UInt64(offset))
                guard let chunk = try handle.read(upToCount: length), !chunk.isEmpty else { break }

                let etag = try await uploadPart(key, uploadID: uploadID,
                                                partNumber: partNumber, data: chunk)
                parts.append((partNumber, etag))

                offset += Int64(chunk.count)
                partNumber += 1
                progress?(offset, size)
            }

            return try await completeMultipartUpload(key, uploadID: uploadID, parts: parts)
        } catch {
            // Leave no half-finished upload holding storage. A failure to abort is
            // not worth masking the original error.
            try? await abortMultipartUpload(key, uploadID: uploadID)
            throw error
        }
    }

    /// bunny.net caps an upload at 10 000 parts, so the part size has to grow for
    /// very large files rather than the upload simply failing near the end.
    static func partSize(forFileOf size: Int64, preferred: Int64) -> Int64 {
        let maxParts: Int64 = 10_000
        let minimum = (size + maxParts - 1) / maxParts
        return max(preferred, minimum)
    }

    func createMultipartUpload(_ key: String, contentType: String?) async throws -> String {
        var headers: [(name: String, value: String)] = []
        if let contentType { headers.append(("Content-Type", contentType)) }
        let request = try signedRequest(
            method: "POST", path: path(forKey: key),
            query: [("uploads", "")], headers: headers, body: .empty
        )
        let response = try await send(request, expecting: [200], key: key)
        let fields = XMLFieldCollector.collect(response.body, elements: ["UploadId"])
        guard let uploadID = fields["UploadId"], !uploadID.isEmpty else {
            throw StorageError.malformedResponse("CreateMultipartUpload returned no UploadId")
        }
        return uploadID
    }

    func uploadPart(_ key: String, uploadID: String,
                    partNumber: Int, data: Data) async throws -> ETag {
        let request = try signedRequest(
            method: "PUT", path: path(forKey: key),
            query: [("partNumber", String(partNumber)), ("uploadId", uploadID)],
            body: .data(data)
        )
        let response = try await send(request, expecting: [200], key: key)
        guard let etag = response[header: "etag"] else {
            throw StorageError.malformedResponse("UploadPart \(partNumber) returned no ETag")
        }
        return ETag(header: etag)
    }

    func completeMultipartUpload(_ key: String, uploadID: String,
                                 parts: [(number: Int, etag: ETag)]) async throws -> ETag? {
        var xml = "<CompleteMultipartUpload>"
        for part in parts.sorted(by: { $0.number < $1.number }) {
            xml += "<Part><PartNumber>\(part.number)</PartNumber>"
            xml += "<ETag>\(part.etag.headerValue)</ETag></Part>"
        }
        xml += "</CompleteMultipartUpload>"

        let request = try signedRequest(
            method: "POST", path: path(forKey: key),
            query: [("uploadId", uploadID)],
            headers: [("Content-Type", "application/xml")],
            body: .data(Data(xml.utf8))
        )
        let response = try await send(request, expecting: [200], key: key)

        // CompleteMultipartUpload can return 200 with an error document in the body.
        let fields = XMLFieldCollector.collect(response.body, elements: ["Code", "Message", "ETag"])
        if let code = fields["Code"] {
            throw StorageError.http(S3HTTPError(status: 200, code: code,
                                                message: fields["Message"], key: key))
        }
        return fields["ETag"].map { ETag(header: $0) }
    }

    func abortMultipartUpload(_ key: String, uploadID: String) async throws {
        let request = try signedRequest(
            method: "DELETE", path: path(forKey: key),
            query: [("uploadId", uploadID)], body: .empty
        )
        _ = try await send(request, expecting: [200, 204, 404], key: key)
    }
}
