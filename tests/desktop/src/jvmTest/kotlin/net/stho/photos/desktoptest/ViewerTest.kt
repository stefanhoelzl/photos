package net.stho.photos.desktoptest

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import net.stho.photos.app.DesktopUi
import net.stho.photos.app.Face
import net.stho.photos.app.FaceState
import net.stho.photos.app.Showing
import net.stho.photos.faces.FaceBox
import net.stho.photos.app.ListEntry
import net.stho.photos.app.MapCamera
import net.stho.photos.app.MapPin
import net.stho.photos.control.ViewerControlServer
import net.stho.photos.desktop.PhotosViewer
import net.stho.photos.media.renderFrame
import net.stho.photos.model.MediaType
import net.stho.photos.storage.S3Client
import net.stho.photos.storage.asStorageUrl
import net.stho.photos.storage.list
import net.stho.photos.ui.desktop.DesktopApp
import net.stho.photos.ui.screens.PhotosTheme

/**
 * §11 end to end: a library, the shipped `photos-cli sync` into a zone and a cache, and the
 * viewer over that cache and that library — with no zone of its own, and no network at all: the
 * basemap is the stand-in here, as it is in every frame drawn without a window.
 *
 * What only this can prove is that the two agree: that the packs the CLI keeps are the ones the
 * viewer reads, and that a row's folder and file name lead to the original on disk.
 */
class ViewerTest {

    @Test
    fun theViewerShowsWhatTheCliSynced() = scenario("synced") {
        library {
            file("Trips/Rome/IMG_0001.HEIC", media("photo.heic"))
            file("Trips/Rome/IMG_0002.HEIC", media("poster.heic"))
            file("Garden/IMG_0003.HEIC", media("live-still.heic"))
            file("Garden/IMG_0003.MOV", media("live.mov"))
            file("Garden/VID_0004.MP4", media("video.mp4"))
        }
        sync()
        assertEquals(2, packs().size, "the CLI keeps each album's pack beside its shards (§7)")

        val viewer = launch()
        val ui = viewer.model.state.value
        assertNull(ui.failure)
        val names = ui.rows.names()
        assertEquals(setOf("Trips", "Rome", "Garden"), names.toSet())
        // Trips heads its group, one album in; where Garden sorts depends on dates the fixtures share.
        assertEquals(names.indexOf("Trips") + 1, names.indexOf("Rome"))
        assertTrue(ui.rows.filterIsInstance<ListEntry.Row>().single { it.album.name == "Trips" }.header)

        // A container's header selects nothing: the album pane always shows one album.
        viewer.model.select(ui.albums.single { it.name == "Trips" })
        assertNull(viewer.model.state.value.selected)

        viewer.model.select(ui.albums.single { it.name == "Rome" })
        val rome = await(viewer, "Rome's thumbnails, from the CLI's pack") { it.thumbnails.size == 2 }
        assertEquals(listOf("IMG_0001.HEIC", "IMG_0002.HEIC"), rome.photos.map { it.diskFilename }.sorted())
        shot(viewer, "grid")

        // The original, found from the shard's folder and decoded by the shim.
        viewer.model.openPhoto(0)
        await(viewer, "the original decoded") { it.preview != null }
        assertTrue(viewer.model.state.value.viewerSubtitle.startsWith("1 of 2 · IMG_000"))
        shot(viewer, "viewer")
        viewer.model.closePhoto()

        // A Live Photo is its still and its MOV in the library; a video is its own file.
        viewer.model.select(ui.albums.single { it.name == "Garden" })
        val garden = await(viewer, "Garden's thumbnails") { it.thumbnails.size == 2 }
        val live = garden.photos.indexOfFirst { it.mediaType == MediaType.LIVE_PHOTO }
        viewer.model.openPhoto(live)
        val pair = assertNotNull(await(viewer, "the Live Photo's pair") { it.livePair != null }.livePair)
        assertTrue(pair.still.endsWith("Garden/IMG_0003.HEIC"), pair.still)
        assertTrue(pair.video.endsWith("Garden/IMG_0003.MOV"), pair.video)
        viewer.model.showPhoto(garden.photos.indexOfFirst { it.mediaType == MediaType.VIDEO })
        val video = assertNotNull(await(viewer, "the video's file") { it.videoPath != null }.videoPath)
        assertTrue(video.endsWith("Garden/VID_0004.MP4"), video)
        viewer.close()
    }

    /**
     * The hourly sync changes the cache under a running viewer; F5 is what shows it, and an album
     * deleted from the library leaves the list and takes its pack with it.
     */
    @Test
    fun aRefreshShowsWhatTheNextSyncDid() = scenario("refresh") {
        library {
            file("Rome/IMG_0001.HEIC", media("photo.heic"))
            file("Lisbon/IMG_0002.HEIC", media("poster.heic"))
        }
        sync()
        val viewer = launch()
        assertEquals(listOf("Lisbon", "Rome"), viewer.model.state.value.rows.names().sorted())

        File(libraryRoot, "Lisbon").deleteRecursively()
        sync()
        assertEquals(1, packs().size, "Lisbon's pack went with its album")
        assertEquals(listOf("Lisbon", "Rome"), viewer.model.state.value.rows.names().sorted(), "nothing moves until F5")

        viewer.model.refresh()
        val after = await(viewer, "the rebuilt list") { !it.loading && it.rows.size == 1 }
        assertEquals(listOf("Rome"), after.rows.names())
        viewer.close()
    }

    /**
     * The library's map places what the CLI read from each file's EXIF GPS; moving it narrows the
     * list, an album's pin opens that album on its own map, and Esc comes back to the same view.
     */
    @Test
    fun theMapNarrowsTheListToWhereTheCliSaysPhotosWereTaken() = scenario("map") {
        library {
            file("Trips/Rome/IMG_0001.HEIC", media("rome.heic"))
            file("Trips/Lisbon/IMG_0002.HEIC", media("lisbon.heic"))
            file("Garden/IMG_0003.HEIC", media("photo.heic"))
        }
        sync()

        val viewer = launch()
        val launched = await(viewer, "the library's map") { it.libraryMap != null && it.libraryView.camera != null }
        assertTrue(launched.showingLibraryMap, "no album selected, so the pane is the map")
        assertEquals("2 of 3 albums on the map", launched.librarySubtitle, "Garden's photo has no location")
        assertEquals(setOf("Trips", "Rome", "Lisbon", "Garden"), launched.rows.names().toSet(), "the automatic frame narrows nothing")
        shot(viewer, "map")

        viewer.model.moveCamera(MapCamera(41.9, 12.5, 7.0))
        val narrowed = viewer.model.state.value
        assertEquals(listOf("Trips", "Rome"), narrowed.rows.names())
        assertEquals("1 in map view", narrowed.albumsSubtitle)
        shot(viewer, "map-narrowed")

        val map = assertNotNull(narrowed.libraryMap)
        val rome = map.clusters.at(assertNotNull(narrowed.libraryView.camera).zoom).single { cluster ->
            cluster.isPin && (map.pins[cluster.members.single()] as MapPin.OfAlbum).album.name == "Rome"
        }
        viewer.model.tapMap(rome)
        val onItsMap = await(viewer, "Rome on its own map") { it.showingAlbumMap && it.albumMap != null && it.thumbnails.isNotEmpty() }
        assertEquals("Rome", onItsMap.selected?.name)
        assertEquals("1 of 1 photos on the map", onItsMap.photosSubtitle)
        assertEquals(listOf("Trips", "Rome"), onItsMap.rows.names(), "the list stays as it was")
        shot(viewer, "album-map")

        viewer.model.showLibrary()
        val back = viewer.model.state.value
        assertTrue(back.showingLibraryMap)
        assertEquals(MapCamera(41.9, 12.5, 7.0), back.libraryView.camera)

        viewer.model.clearInView()
        assertEquals(4, viewer.model.state.value.rows.names().size)
        viewer.close()
    }

    /** The control server drives the viewer as the keyboard does, and draws it offscreen. */
    @Test
    fun theControlServerDrivesTheViewer() = scenario("control") {
        library {
            file("Rome/IMG_0001.HEIC", media("photo.heic"))
            file("Rome/IMG_0002.HEIC", media("poster.heic"))
        }
        sync()
        val viewer = launch()
        val port = java.net.ServerSocket(0).use { it.localPort }
        val content = @androidx.compose.runtime.Composable {
            PhotosTheme(dark = true) { DesktopApp(viewer.model, viewer.thumbnails) }
        }
        val server = ViewerControlServer(port, viewer.model, screenshot = { w, h -> renderFrame(w, h, count = 3, content = content) })
        server.start()
        val http = HttpClient(OkHttp)
        try {
            runBlocking {
                val base = "http://127.0.0.1:$port"
                val selected = http.post("$base/select?step=1").bodyAsText()
                assertTrue("\"selected\":\"" in selected, selected)
                val focused = http.post("$base/focus?delta=1").bodyAsText()
                assertTrue("\"focus\":0" in focused, focused)
                val opened = http.post("$base/open?focused").bodyAsText()
                assertTrue("\"open\":0" in opened, opened)
                val closed = http.post("$base/close").bodyAsText()
                assertTrue("\"open\":null" in closed, closed)
                val toggled = http.post("$base/map").bodyAsText()
                assertTrue("\"showing\":\"map\"" in toggled, toggled)
                val library = http.post("$base/library").bodyAsText()
                assertTrue("\"selected\":null" in library && "\"showing\":\"library\"" in library, library)
                val png = http.get("$base/screenshot?w=1280&h=800").readRawBytes()
                assertEquals(listOf(0x89.toByte(), 'P'.code.toByte()), png.take(2), "a PNG")
                File(scratch, "control.png").writeBytes(png)
            }
        } finally {
            http.close()
            server.stop()
            viewer.close()
        }
    }

    /**
     * §12 end to end: faces the CLI found, named in an unknown group, the next sync's suggestion
     * confirmed with Space, and the boxes over the photograph — on NASA portraits, Buzz Aldrin
     * twice and Michael Collins once.
     */
    @Test
    fun facesAreNamedHereAndSuggestedByTheNextSync() = scenario("people") {
        library {
            portrait("Crew/aldrin-1963.jpg", "s63-20056")
            portrait("Crew/aldrin-1969.jpg", "S69-31743")
            portrait("Crew/collins-1964.jpg", "s64-29926")
        }
        sync()
        val viewer = launch()
        val model = viewer.model
        val crew = model.state.value.albums.single { it.name == "Crew" }
        model.select(crew)
        val photos = await(viewer, "Crew's photos") { it.photos.size == 3 }.photos.associate { it.diskFilename to it.id }

        // Three faces and nobody named: too few to group, so all of them are "Other".
        val start = model.state.value.people
        assertTrue(start.people.isEmpty())
        val other = start.groups.single()
        assertEquals(Face.OTHER, other.id)
        model.expandUnknown(true)
        model.show(Showing.Group(Face.OTHER))
        val group = await(viewer, "the group's crops") { ui -> ui.faces.all { it.id in ui.crops } }
        assertEquals(group.faces.map { it.id }.toSet(), group.faceSelection, "a group opens all selected")
        shot(viewer, "group")

        // Only the 1963 sitter is Buzz: select it alone, and name it.
        val sitter = group.faces.filter { it.photoId == photos.getValue("aldrin-1963.jpg") }.maxBy { it.score }
        model.clickFace(group.faces.indexOf(sitter), range = false, toggle = false)
        model.editName("Buzz")
        model.submitName()
        val named = model.state.value
        val buzz = named.people.people.single()
        assertEquals("Buzz", buzz.person.name)
        assertEquals(1, buzz.confirmed)
        assertTrue(File(libraryRoot, ".photos/people.db").isFile, "the labels file, in the library")

        // The next sync reads the labels and suggests the 1969 photograph; F5 shows it.
        sync()
        model.refresh()
        await(viewer, "the suggestion") { ui -> ui.people.person(buzz.person.id)?.suggested == 1 }
        model.show(Showing.Person(buzz.person.id))
        val pane = await(viewer, "Buzz's crops") { ui -> ui.faces.size == 2 && ui.faces.all { it.id in ui.crops } }
        val suggested = pane.faces.first()
        assertEquals(FaceState.SUGGESTED, suggested.state)
        assertEquals(photos.getValue("aldrin-1969.jpg"), suggested.photoId)
        assertTrue(pane.faces.none { it.photoId == photos.getValue("collins-1964.jpg") }, "Collins is not Buzz")
        shot(viewer, "person-suggested")

        // Enter confirms the focused suggestion.
        model.moveFaceFocus(0, extend = false)
        model.confirmChosen()
        val confirmed = model.state.value
        assertEquals(2, confirmed.person?.confirmed)
        assertEquals(0, confirmed.suggestedCount)
        shot(viewer, "person-confirmed")

        // Enter opens the face's photograph with its boxes; Esc comes back to Buzz.
        model.openFace(0)
        await(viewer, "the original decoded") { it.preview != null }
        assertTrue(model.state.value.faceBoxes)
        assertTrue(model.state.value.openFaces.any { it.person == buzz.person.id })
        shot(viewer, "viewer-faces")
        model.closePhoto()
        assertEquals(Showing.Person(buzz.person.id), model.state.value.showing)

        // A right-click on the face left in "Other" — Collins — and a new name typed into its menu.
        model.show(Showing.Group(Face.OTHER))
        val collins = model.state.value.faces.indexOfFirst { it.photoId == photos.getValue("collins-1964.jpg") }
        model.contextFace(collins)
        model.nameChosen(null, "Michael")
        assertEquals(listOf("Buzz", "Michael"), model.state.value.people.people.map { it.person.name }.sorted())

        // D on a photograph, a box drawn where the detector found nothing, and a name: the drawn face
        // is Buzz's at once, with a crop cut from the box.
        model.select(crew)
        val aldrin = model.state.value.photos.indexOfFirst { it.diskFilename == "aldrin-1963.jpg" }
        model.openPhoto(aldrin)
        await(viewer, "the original decoded") { it.preview != null }
        model.toggleDrawing()
        model.drawn(FaceBox(0.05f, 0.7f, 0.2f, 0.2f))
        shot(viewer, "viewer-drawing")
        model.nameDrawn(buzz.person.id)
        model.show(Showing.Person(buzz.person.id))
        val withDrawn = await(viewer, "the drawn face's crop") { ui -> ui.faces.any { it.drawn && it.id in ui.crops } }
        assertEquals(1, withDrawn.faces.count { it.drawn })
        viewer.close()
    }

    // -------------------------------------------------------------------------------- fixtures

    private fun List<ListEntry>.names(): List<String> = filterIsInstance<ListEntry.Row>().map { it.album.name }

    private fun scenario(label: String, body: Scenario.() -> Unit) {
        val endpoint = System.getProperty("photos.s3mock.endpoint")
        if (endpoint.isNullOrBlank()) {
            println("photos.s3mock.endpoint unset -- skipping desktop scenario '$label'")
            return
        }
        val root = File(System.getProperty("photos.test.scratch") ?: error("photos.test.scratch unset"))
        val scratch = root.resolve(label).apply {
            deleteRecursively()
            mkdirs()
        }
        // Each scenario owns the bucket: emptied first, so the CLI's first run here is a first import.
        runBlocking {
            HttpClient(OkHttp).use { http ->
                val s3 = S3Client(
                    storage = endpoint.asStorageUrl(),
                    secretAccessKey = PASSWORD,
                    http = http,
                    payloadSigning = S3Client.PayloadSigning.SIGNED,
                )
                s3.list().collect { listed -> if (!listed.isDirectoryMarker) s3.delete(listed.key) }
            }
        }
        Scenario(endpoint, scratch).body()
    }

    private class Scenario(private val endpoint: String, val scratch: File) {
        val libraryRoot = scratch.resolve("library")
        val cliCache = scratch.resolve("cli-cache")
        private val media = File(System.getProperty("photos.fixture.media") ?: error("photos.fixture.media unset"))

        fun media(name: String): File = media.resolve(name).also { require(it.isFile) { "no fixture $it" } }

        fun library(body: Library.() -> Unit) {
            libraryRoot.mkdirs()
            libraryRoot.resolve(".photosignore").writeText("# nothing excluded\n")
            Library(libraryRoot).body()
        }

        /**
         * The shipped binary, as the hourly unit runs it — with the endpoint and the secret from
         * the environment, a cache of the scenario's own, and a first run that empties the zone.
         */
        fun sync() {
            seedFaceModels()
            val binary = System.getProperty("photos.cli.binary") ?: error("photos.cli.binary unset")
            val process = ProcessBuilder(
                binary, "sync", "--library-path", libraryRoot.path, "--cache-dir", cliCache.path,
            ).redirectErrorStream(true).apply {
                environment().clear()
                environment()["HOME"] = scratch.path
                environment()["TMPDIR"] = scratch.path
                environment()["PHOTOS_ENDPOINT"] = endpoint
                environment()["PHOTOS_PASSWORD"] = PASSWORD
            }.start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor(5, TimeUnit.MINUTES)) { "photos-cli sync did not finish" }
            check(process.exitValue() == 0) { "photos-cli sync exited ${process.exitValue()}:\n$output" }
        }

        /** The build's verified copies of §12's models, where a run looks for them — never fetched here. */
        private fun seedFaceModels() {
            val models = cliCache.resolve("models").apply { mkdirs() }
            for (name in listOf("face_detection_yunet_2023mar", "face_recognition_sface_2021dec")) {
                val target = models.resolve("$name.onnx")
                if (!target.exists()) java.nio.file.Files.createSymbolicLink(target.toPath(), File(faceFixture(name)).toPath())
            }
        }

        fun packs(): List<String> = cliCache.resolve("packs").list()?.filter { it.endsWith(".db") }.orEmpty()

        /** The viewer's composition root over this scenario's cache and library, minus the window. */
        fun launch(): PhotosViewer {
            val viewer = PhotosViewer(
                cliCache = Path(cliCache.path),
                libraryRoot = Path(libraryRoot.path),
                decodeLibrary = System.getProperty("photos.decode.library"),
                longEdge = 1280,
                cropCache = scratch.resolve("crops"),
            )
            viewer.start()
            await(viewer, "the first rebuild") { !it.loading }
            return viewer
        }

        fun await(viewer: PhotosViewer, what: String, done: (DesktopUi) -> Boolean): DesktopUi {
            val deadline = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < deadline) {
                val ui = viewer.model.state.value
                if (done(ui)) return ui
                Thread.sleep(25)
            }
            error("timed out waiting for $what: ${viewer.model.state.value}")
        }

        /** A frame as `/screenshot` draws it, kept beside the scenario for a person to look at. */
        fun shot(viewer: PhotosViewer, name: String) {
            val png = renderFrame(1280, 800, count = 3) {
                PhotosTheme(dark = true) { DesktopApp(viewer.model, viewer.thumbnails) }
            }
            scratch.resolve("$name.png").writeBytes(png)
        }
    }

    private class Library(private val root: File) {
        /** A NASA portrait from the build's fixtures, for §12's faces. */
        fun portrait(path: String, photo: String) = file(path, File(faceFixture(photo)))

        fun file(path: String, from: File) {
            val target = root.resolve(path)
            target.parentFile.mkdirs()
            from.copyTo(target, overwrite = true)
        }
    }

    private companion object {
        fun faceFixture(name: String): String =
            (System.getProperty("photos.face.fixtures") ?: error("photos.face.fixtures unset"))
                .split(':').single { File(it).name.startsWith(name) }

        /** S3Mock does not validate signatures, so any secret does. */
        const val PASSWORD = "desktop-secret"
    }
}
