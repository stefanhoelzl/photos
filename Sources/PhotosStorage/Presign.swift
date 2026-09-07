import Foundation

extension S3Client {

    /// A presigned URL that authorises one PUT of one object.
    ///
    /// §8's upload flow pre-signs every PUT for an album while the password is in
    /// memory, then hands the URLs to a background `URLSession`. That session may
    /// still be running long after the app was force-quit and relaunched, so it must
    /// never need the key again. bunny.net accepts expiries between 1 second and 7 days.
    ///
    /// The payload hash is `UNSIGNED-PAYLOAD`, which is what query auth always uses:
    /// the body is not known when the URL is minted.
    public func presignedPUT(
        _ key: String,
        expiresIn: Duration,
        now: Date = Date()
    ) throws -> URL {
        try presigned(method: "PUT", key: key, expiresIn: expiresIn, now: now)
    }

    /// The general form. `presignedPUT` is the only consumer in the design; a
    /// presigned GET has none, because the app signs its own reads.
    func presigned(method: String, key: String,
                   expiresIn: Duration, now: Date) throws -> URL {
        let seconds = expiresIn.components.seconds
        guard (1...604_800).contains(seconds) else {
            throw StorageError.malformedResponse(
                "presigned URL expiry must be between 1 second and 7 days, got \(seconds)s"
            )
        }

        let requestPath = path(forKey: key)
        guard let host = storage.endpoint.host else {
            throw StorageError.malformedResponse("storage URL has no host")
        }

        // Query auth signs the headers it lists, and here that is only `host` —
        // everything else moves into the query string. `X-Amz-SignedHeaders` must
        // therefore be computed before the query is assembled.
        let headers: [(name: String, value: String)] = [("Host", host)]
        let signedHeaders = SigV4Signer.canonicalHeaders(headers).signed

        var query: [(name: String, value: String)] = [
            ("X-Amz-Algorithm", SigV4Signer.algorithm),
            ("X-Amz-Credential", "\(signer.accessKeyID)/\(signer.credentialScope(date: now))"),
            ("X-Amz-Date", SigV4Signer.amzDate(now)),
            ("X-Amz-Expires", String(seconds)),
            ("X-Amz-SignedHeaders", signedHeaders),
        ]

        let canonical = signer.canonicalRequest(
            method: method, path: requestPath, query: query,
            headers: headers, payloadHash: SigV4Signer.unsignedPayload
        )
        let sts = signer.stringToSign(canonicalRequest: canonical.text, date: now)
        query.append(("X-Amz-Signature", signer.signature(stringToSign: sts, date: now)))

        guard let url = url(path: requestPath, query: query) else {
            throw StorageError.malformedResponse("could not build a presigned URL for \(key)")
        }
        return url
    }
}
