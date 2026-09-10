package net.stho.photos.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlin.test.AfterTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import net.stho.photos.catalog.Album
import net.stho.photos.model.MediaType
import net.stho.photos.model.PhotoRow
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import net.stho.photos.ui.screens.App
import net.stho.photos.ui.screens.PhotosTheme
import net.stho.photos.ui.state.AppModel
import net.stho.photos.ui.state.Catalog
import net.stho.photos.ui.state.Notice
import net.stho.photos.ui.state.SyncOutcome
import net.stho.photos.ui.state.Preview
import net.stho.photos.ui.state.Previews
import net.stho.photos.ui.state.Syncer
import net.stho.photos.ui.state.Totals
import net.stho.photos.ui.state.Videos
import net.stho.photos.ui.state.Thumbnails

/**
 * Proof that the app actually draws, with no zone, no credentials and no display.
 *
 * The seed of `:tests:app`: it renders the real composition offscreen through the same
 * `ImageComposeScene` the control server's `/screenshot` uses, so what an agent reviews and
 * what a test asserts are produced by one path. The PNGs are written for a person to look at —
 * they are never compared, so nothing here fails because a font hinted differently (decision
 * 22).
 */
class RenderTest {

    @Test
    fun everyE1ScreenRendersInBothSchemes() = runTest {
        val out = File(System.getProperty("java.io.tmpdir"), "photos-render").apply { mkdirs() }

        for (dark in listOf(true, false)) {
            val scheme = if (dark) "dark" else "light"
            render(out, "albums-$scheme", dark) { model(this@runTest) }
            render(out, "settings-$scheme", dark) { model(this@runTest).also { it.openSettings() } }
            render(out, "error-$scheme", dark) {
                model(
                    this@runTest,
                    outcome = SyncOutcome.Failed(
                        Notice.error("Sync failed: 403 Forbidden", "Log out and check the password."),
                    ),
                )
            }
            render(out, "empty-$scheme", dark) { model(this@runTest, albums = emptyList()) }
            render(out, "grid-$scheme", dark) { gridModel(this@runTest, packed = true) }
            render(out, "grid-pending-$scheme", dark) { gridModel(this@runTest, packed = false) }
            render(out, "loading-$scheme", dark) { loadingModel(this@runTest) }
        }

        val written = out.listFiles()?.filter { it.name.endsWith(".png") }.orEmpty()
        assertTrue(written.size >= 14, "expected a frame per screen per scheme, got ${written.size}")
        assertTrue(written.all { it.length() > 1_000 }, "a frame that small drew nothing")
        println("frames in $out")
    }

    private fun render(directory: File, name: String, dark: Boolean, build: () -> AppModel) {
        val model = build()
        val scene = ImageComposeScene(width = 430, height = 890, density = Density(2f)) {
            PhotosTheme(dark = dark) { App(model, FakeThumbnails()) }
        }
        try {
            val png = requireNotNull(scene.render().encodeToData()) { "skia declined to encode" }
            File(directory, "$name.png").writeBytes(png.bytes)
        } finally {
            scene.close()
        }
    }

    private fun model(
        scope: TestScope,
        albums: List<Album> = sampleAlbums,
        outcome: SyncOutcome = SyncOutcome.Succeeded(albums.size, 14_537),
    ): AppModel {
        val own = CoroutineScope(UnconfinedTestDispatcher(scope.testScheduler))
        scopes += own
        return AppModel(FakeCatalog(albums), FakeSyncer(outcome), FakeThumbnails(), FakePreviews(), FakeVideos(), idleQueue(own), own).also { it.start() }
    }

    /**
     * A queue over a store with nothing in it: these tests render screens, and the ladder has
     * its own suite. Every album therefore draws as holding nothing, which is a real state.
     */
    private fun idleQueue(scope: CoroutineScope) = net.stho.photos.ui.state.CacheQueue(
        store = object : net.stho.photos.ui.state.BlobStore {
            override fun has(id: net.stho.photos.catalog.ObjectId) = false
            override suspend fun fetch(id: net.stho.photos.catalog.ObjectId) = Unit
            override fun delete(id: net.stho.photos.catalog.ObjectId) = Unit
            override fun present() = emptySet<net.stho.photos.catalog.ObjectId>()
        },
        scope = scope,
        backoff = { },
    )

    private val scopes = mutableListOf<CoroutineScope>()

    @AfterTest
    fun stopModels(): Unit = scopes.forEach(CoroutineScope::cancel)

    /** An album open at its grid: either its pack has landed, or it is still downloading. */
    private fun gridModel(scope: TestScope, packed: Boolean): AppModel {
        val album = sampleAlbums.first()
        val photos = (0 until 24).map { photo() }
        val thumbs = if (packed) photos.associate { it.id to jpeg() } else emptyMap()
        val own = CoroutineScope(UnconfinedTestDispatcher(scope.testScheduler))
        scopes += own
        return AppModel(
            GridCatalog(album, photos),
            FakeSyncer(SyncOutcome.Succeeded(1, photos.size)),
            FakeThumbnails(present = packed, thumbnails = thumbs),
            FakePreviews(),
            FakeVideos(),
            idleQueue(own),
            own,
        ).also { model ->
            model.start()
            model.open(album)
        }
    }

    /** A first sync in progress, with nothing to show yet — §4's cold start. */
    private fun loadingModel(scope: TestScope): AppModel {
        val own = CoroutineScope(UnconfinedTestDispatcher(scope.testScheduler))
        scopes += own
        val syncer = object : Syncer {
            override suspend fun sync(onProgress: (Int, Int) -> Unit): SyncOutcome {
                onProgress(37, 288)
                kotlinx.coroutines.awaitCancellation()
            }
        }
        return AppModel(
            FakeCatalog(emptyList()), syncer, FakeThumbnails(present = false),
            FakePreviews(), FakeVideos(), idleQueue(own), own,
        ).also { it.start() }
    }

    private fun photo() = PhotoRow(
        id = Uuid.random(),
        filename = "IMG.jpg",
        takenAt = Instant.parse("2024-06-01T10:00:00Z"),
        width = 4032,
        height = 3024,
        bytes = 3_400_000,
        mediaType = MediaType.PHOTO,
    )

    /** A 256px square, so the tiles are real decoded JPEG rather than coloured boxes. */
    private fun jpeg(): ByteArray {
        val surface = Surface.makeRasterN32Premul(256, 256)
        surface.canvas.clear(0xFF4C6E8A.toInt())
        return surface.makeImageSnapshot().encodeToData(EncodedImageFormat.JPEG)!!.bytes
    }

    private class GridCatalog(private val album: Album, private val photos: List<PhotoRow>) : Catalog {
        override fun albums(under: Uuid?): List<Album> = if (under == null) listOf(album) else emptyList()
        override fun search(text: String): List<Album> = emptyList()
        override fun photos(inAlbum: Uuid): List<PhotoRow> = photos
        override fun album(id: Uuid): Album? = album
        override fun totals() = Totals(1, photos.size)
        override fun blobs(): Map<Uuid, List<net.stho.photos.ui.state.BlobRef>> = emptyMap()
    }

    private val sampleAlbums = listOf(
        album("Iceland", 412, 2024),
        album("Alps Traverse", 870, 2023),
        album("Coast Road 2019", 1_462, 2019),
        album("Harbour Nights", 204, 2022),
        album("Lake District", 338, 2021),
        album("City Break", 96, 2020),
        album("Ahnenfotos", 8, null),
    )

    private fun album(name: String, photos: Int, year: Int?) = Album(
        id = Uuid.random(),
        name = name,
        nameFolded = name.lowercase(),
        parent = null,
        photoCount = photos,
        dateMin = year?.let { Instant.parse("$it-01-01T00:00:00Z") },
        dateMax = year?.let { Instant.parse("$it-12-31T00:00:00Z") },
        latitude = null,
        longitude = null,
        coverPhotoId = null,
        thumbsId = null,
    )

    private class FakeCatalog(private val albums: List<Album>) : Catalog {
        override fun albums(under: Uuid?): List<Album> = if (under == null) albums else emptyList()
        override fun search(text: String): List<Album> =
            albums.filter { it.nameFolded.contains(text.lowercase()) }
        override fun photos(inAlbum: Uuid): List<PhotoRow> = emptyList()
        override fun album(id: Uuid): Album? = albums.firstOrNull { it.id == id }
        override fun totals() = Totals(albums.size, albums.sumOf { it.photoCount })
        override fun blobs(): Map<Uuid, List<net.stho.photos.ui.state.BlobRef>> = emptyMap()
    }

    /**
     * Packs that are always present and always empty.
     *
     * Enough for the state tier: what it decides is *whether* a pack has landed and what to
     * show when it has not, never what a thumbnail looks like.
     */
    private class FakeThumbnails(
        private val present: Boolean = true,
        private val thumbnails: Map<Uuid, ByteArray> = emptyMap(),
    ) : Thumbnails {
        override val arrivals = MutableStateFlow(0)
        override val outstanding = MutableStateFlow(0)
        override fun has(album: Album) = present
        override fun cover(album: Album): ByteArray? = thumbnails.values.firstOrNull()
        override fun all(album: Album): Map<Uuid, ByteArray> = thumbnails
        override fun prioritise(album: Album) = Unit
    }


    private class FakeSyncer(private val outcome: SyncOutcome) : Syncer {
        override suspend fun sync(onProgress: (Int, Int) -> Unit): SyncOutcome = outcome
    }

    /** No transcode ever arrives, so the poster is what the viewer keeps showing. */
    private class FakeVideos : Videos {
        override suspend fun localFile(photo: PhotoRow): String? = null
    }

    /** No previews: enough for the state tier, which decides *when* to ask, not what comes back. */
    private class FakePreviews : Previews {
        override fun cached(photo: PhotoRow): Preview? = null
        override suspend fun load(photo: PhotoRow): Preview? = null
        override fun prefetch(photos: List<PhotoRow>, index: Int) = Unit
        override fun cancelPrefetch() = Unit
    }
}
