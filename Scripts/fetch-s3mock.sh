#!/usr/bin/env bash
# Fetches the pinned adobe/S3Mock standalone jar into .tools/ (gitignored).
#
# S3Mock rather than a real S3 server: MinIO's community edition was archived in
# February 2026, SeaweedFS's conditional PUT is absent or broken, and s3proxy
# answers 412 with a 500. S3Mock is maintained, purpose-built for tests, and does
# support conditional PUT -- which milestone A needs, because If-Match is what
# guards §2's single-owner shard rule.
#
# It does NOT validate signatures. That is accepted: the vendored AWS vector suite
# is what proves the signer, and it names the failing stage where a server could
# only say pass/fail.
set -euo pipefail

VERSION="4.11.0"
JAR=".tools/s3mock-${VERSION}-exec.jar"
URL="https://repo1.maven.org/maven2/com/adobe/testing/s3mock/${VERSION}/s3mock-${VERSION}-exec.jar"

cd "$(dirname "$0")/.."
if [ -f "$JAR" ]; then
    echo "$JAR"
    exit 0
fi

mkdir -p .tools
echo "fetching S3Mock ${VERSION}..." >&2
curl -fsSL -o "${JAR}.partial" "$URL"
mv "${JAR}.partial" "$JAR"
echo "$JAR"
