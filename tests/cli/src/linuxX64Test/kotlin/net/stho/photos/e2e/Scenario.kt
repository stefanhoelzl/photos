@file:OptIn(ExperimentalForeignApi::class, ExperimentalUuidApi::class)

package net.stho.photos.e2e

import io.ktor.client.HttpClient
import io.ktor.client.engine.curl.Curl
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import net.stho.photos.faces.FaceModelStore
import net.stho.photos.faces.FaceModels
import net.stho.photos.fixtures.withScratchDirectory
import net.stho.photos.storage.S3Client
import net.stho.photos.storage.asStorageUrl
import platform.posix.getenv
import platform.posix.symlink

/**
 * One end-to-end scenario: declare state, run the shipped binary, assert state.
 *
 * Assertions describe **state, never change** -- there is no `unchanged()` and no `added()`, so
 * an expectation reads on its own without knowing what preceded it. The library is asserted
 * open-world, naming only what matters, because a person edits it; the zone is asserted
 * closed-world, because it is machine-generated and fully determined by the input, so an
 * unexpected row should fail without anyone having predicted it.
 *
 * The local cache is not declared. Every scenario gets a fresh `--cache-dir`, and one that needs
 * a warm cache simply calls [run] twice -- so the cache is always what the real binary left
 * behind rather than something forged here.
 */
internal fun scenario(label: String, body: suspend Scenario.() -> Unit) {
    val endpoint = getenv("PHOTOS_S3MOCK_ENDPOINT")?.toKString()
    if (endpoint.isNullOrBlank()) {
        // The same bargain the round-trip tests strike: no JVM, no S3Mock, and everything
        // offline still runs. The Gradle task says so on stderr when it happens.
        println("PHOTOS_S3MOCK_ENDPOINT unset -- skipping scenario '$label'")
        return
    }
    withScratchDirectory("e2e-$label") { scratch ->
        val libraryRoot = Path(scratch, "library")
        val cacheRoot = Path(scratch, "cache")
        SystemFileSystem.createDirectories(libraryRoot)
        SystemFileSystem.createDirectories(cacheRoot)
        seedFaceModels(cacheRoot)
        val http = HttpClient(Curl)
        try {
            runBlocking {
                val s3 = S3Client(
                    storage = endpoint.asStorageUrl(),
                    secretAccessKey = TEST_PASSWORD,
                    http = http,
                    payloadSigning = S3Client.PayloadSigning.SIGNED,
                )
                s3.clearZone()
                Scenario(label, scratch, libraryRoot, cacheRoot, endpoint, s3).body()
            }
        } finally {
            http.close()
        }
    }
}

/**
 * The build's verified copies of §12's models, linked into the cache where a run looks for them —
 * so a scenario, each with a fresh cache, does not fetch 38 MB of weights from GitHub. The fetch
 * itself is `FaceModelStore`'s, and proven there.
 */
private fun seedFaceModels(cacheRoot: Path) {
    val models = Path(cacheRoot, FaceModelStore.DIRECTORY)
    SystemFileSystem.createDirectories(models)
    for (model in listOf(FaceModels.DETECTOR, FaceModels.EMBEDDER)) {
        symlink(faceFixture(model.name.removeSuffix(".onnx")), Path(models, model.name).toString())
    }
}

/** A file the build resolved for this suite: the face models, and the NASA portraits. */
internal fun faceFixture(name: String): String =
    requireNotNull(getenv("PHOTOS_FACE_FIXTURES")?.toKString()) { "PHOTOS_FACE_FIXTURES is not set; run through Gradle" }
        .split(':').single { it.substringAfterLast('/').startsWith(name) }

/** S3Mock does not validate signatures, so any secret does. The signer is proven elsewhere. */
internal const val TEST_PASSWORD: String = "e2e-secret"

internal class Scenario(
    val label: String,
    val scratch: Path,
    val libraryRoot: Path,
    val cacheRoot: Path,
    private val endpoint: String,
    val s3: S3Client,
) {
    private var lastRun: Run? = null

    /** Declares, or amends, the library tree on disk. */
    fun library(body: LibraryBuilder.() -> Unit) {
        LibraryBuilder(libraryRoot).body()
    }

    /** Declares the zone directly -- see [GivenAlbum] for why that is not done with a CLI run. */
    suspend fun zone(body: GivenZone.() -> Unit) {
        val given = GivenZone()
        given.body()
        if (given.cleared) s3.clearZone()
        for (album in given.albums) album.materialise(s3, scratch)
    }

    /**
     * Runs the shipped binary.
     *
     * `--library-path` and `--cache-dir` are always passed so a scenario cannot depend on the
     * working directory or on `$XDG_CACHE_HOME`. `TMPDIR` points into the scratch tree, so the
     * work root a run stages derivatives in dies with the scenario.
     */
    fun run(vararg args: String, endpointOverride: String? = endpoint, password: String? = TEST_PASSWORD) {
        val environment = buildMap {
            put("HOME", scratch.toString())
            put("TMPDIR", scratch.toString())
            endpointOverride?.let { put("PHOTOS_ENDPOINT", it) }
            password?.let { put("PHOTOS_PASSWORD", it) }
        }
        lastRun = runCli(
            *args,
            "--library-path", libraryRoot.toString(),
            "--cache-dir", cacheRoot.toString(),
            environment = environment,
        )
    }

    /** Runs with no endpoint and no password at all -- the "nothing is configured" case. */
    fun runUnconfigured(vararg args: String) {
        run(*args, endpointOverride = null, password = null)
    }

    suspend fun expect(body: Expectations.() -> Unit) {
        val expectations = Expectations()
        expectations.body()
        expectations.check(this, requireNotNull(lastRun) { "$label: expect { } before any run" })
    }

    val output: String get() = lastRun?.output.orEmpty()
}

/** The zone a scenario starts from. */
internal class GivenZone {
    internal val albums = mutableListOf<Given>()
    internal var cleared = false

    /** Nothing in the zone. Implied at the start of every scenario; stated where it matters. */
    fun empty() {
        cleared = true
    }

    fun album(name: String, body: GivenAlbum.() -> Unit): GivenAlbum =
        GivenAlbum(name).apply(body).also { albums += it }

    /** Photos the phone added to the album a run made from the folder [path] (§8). */
    fun addition(path: String, body: GivenAddition.() -> Unit): GivenAddition =
        GivenAddition(into = path, intoId = null, name = path.substringAfterLast('/')).apply(body).also { albums += it }

    /** Photos the phone added to the album [id] — given in this scenario, or not in the zone at all. */
    fun addition(id: Uuid, name: String, body: GivenAddition.() -> Unit): GivenAddition =
        GivenAddition(into = null, intoId = id, name = name).apply(body).also { albums += it }
}

/** A random id, for a scenario that needs to name one. */
internal fun anId(): Uuid = Uuid.random()
