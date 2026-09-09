# AWS SigV4 test vectors

Vendored from [awslabs/aws-c-auth](https://github.com/awslabs/aws-c-auth),
`tests/aws-signing-test-suite/v4`, Apache-2.0.

AWS's original `aws-sig-v4-test-suite.zip` is no longer served from
docs.aws.amazon.com (404 as of 2026-09). This mirror is a superset of it: it
carries explicit `normalized` / `unnormalized` path variants, which matters
here because **S3 does not normalise paths** — `S3Client` signs with
`normalizePath = false`, while the vectors exercise both settings via each
case's `context.json`.

Each case directory holds:

| file | meaning |
|---|---|
| `context.json` | credentials, region, service, timestamp, `normalize` flag |
| `request.txt` | the raw HTTP request to sign |
| `header-canonical-request.txt` | expected canonical request, header auth |
| `header-string-to-sign.txt` | expected string-to-sign, header auth |
| `header-signature.txt` | expected signature, header auth |
| `query-*` | the same three, for query auth (presigned URLs) |

Compared stage by stage so a failure names *which* stage broke, per the
milestone A decision that these vectors are the sole proof the signer is correct.
