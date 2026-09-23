plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
}

// The app's own domain: everything the Linux app and the iOS app agree about.
//
// `:domain` is the tier the app shares with the **CLI** — the catalog, the shard schema, the
// S3 client, the ingest rules. This is the tier the two *apps* share with each other, and the
// CLI has no use for any of it: it writes thumbnail packs rather than collecting them, and it
// has no `blobs/` directory, no download queue and no screen to keep in order.
//
// What is here:
//   the ports         Catalog, Syncer, BlobStore, Thumbnails, Previews, Videos -- what a
//                     composition root supplies, and what a test substitutes
//   the model         AppModel, AlbumCache, Screen, Notice, SyncStatus, AlbumSort
//   the scheduler     CacheQueue: §6's ladder, its three worker roles and its retry policy
//   the cache         FileBlobStore, PackFetcher, BlobPreviews, MergedCatalogSource -- §4's
//                     on-device layout, which needs a filesystem and a zone but no platform
//   the upload        Uploads, UploadModel, and §8's two ports, Gallery and BackgroundUploader
//
// The last group used to live in `:app:harness`, where it was reachable by one of the two
// apps. None of it is platform-specific: kotlinx-io reaches a filesystem on both, and the
// pieces that genuinely differ -- decoding a HEIC, playing a video, opening a SQLite file --
// were already ports before the move.
//
// Compose appears here for exactly one type. `Preview` carries an `ImageBitmap`, because a
// decoded picture is what the viewer draws and converting it twice would be worse. That is
// affordable in an app module and is precisely why this is not in `:domain`, which `:app:cli`
// links into a 26.7 MiB binary.
kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    compilerOptions { optIn.add("kotlin.uuid.ExperimentalUuidApi") }
    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":domain"))
            // `compose.ui` alone -- no foundation, no material3. Nothing here draws; the one
            // Compose type in the tier is `ImageBitmap`.
            implementation(compose.ui)
            implementation(libs.coroutines.core)
            implementation(libs.kotlinx.io.core)
            // For the foreground uploader's PUT to a pre-signed URL, which is not an S3Client call:
            // the URL already carries its signature (§8).
            implementation(libs.ktor.client.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
    }
}
