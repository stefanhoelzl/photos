package net.stho.photos.control

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlin.uuid.Uuid
import net.stho.photos.app.AppModel
import net.stho.photos.app.AppUi
import net.stho.photos.app.CacheAction
import net.stho.photos.app.Launch
import net.stho.photos.app.Launcher
import net.stho.photos.app.MapCamera
import net.stho.photos.app.MapPin
import net.stho.photos.app.SaveOutcome
import net.stho.photos.app.Screen
import net.stho.photos.app.UploadModel

/**
 * How the app is driven and reviewed without a person at the keyboard (§6).
 *
 * The point is that state is *assertable*, not merely visible: `GET /state` returns what the app
 * believes, which is what a scenario asserts on, while a screenshot only ever proves something
 * was drawn. Every mutating endpoint calls the same model method a tap does, so what a suite
 * drives is real behaviour rather than a stand-in.
 *
 *   GET  /state           the current screen, sort, query, albums with their cache state, sync
 *                         status, notice, what the viewer holds (videoPath, livePair), and the
 *                         upload picker and uploads when the root has a gallery
 *   POST /nav?to=albums   push a screen: albums | settings | album/<uuid> |
 *                         photo/<uuid>/<index> | back
 *   POST /sort            cycle the sort, exactly as the icon does
 *   POST /search?q=text   type into the search field
 *   POST /refresh         pull-to-refresh
 *   POST /map             the representation toggle: list ↔ map, or grid ↔ map (§6)
 *   POST /map/camera?latitude=…&longitude=…&zoom=…
 *                         move the showing map's camera, as a cluster tap does
 *   POST /map/viewport?w=…&h=…
 *                         the map's size in dp, which a frame fits; a phone's until set
 *   POST /map/tap?cluster=<i>
 *                         tap the i-th entry of `/state`'s `map.clusters` -- a pin or a cluster
 *   POST /map/sheet?album=<uuid> | ?dismiss
 *                         a row of the list of albums sharing one spot, or closing it
 *   POST /cache?album=<uuid>&action=download|pause|clear
 *                         the album row's cache controls, which are a swipe or a tap on the strip
 *   POST /upload/open     the upload icon, on the album list or a container (§8)
 *   POST /upload/album?id=<gallery album id>
 *                         pick a whole gallery album; the name dialog opens prefilled
 *   POST /upload/select?ids=<asset>,<asset>
 *                         pick loose photos; the name dialog opens empty
 *   POST /upload/name?name=…&delete=true|false
 *                         the name dialog's field and checkbox
 *   POST /upload/confirm  the dialog's Upload; answers 422 while there is no name
 *   POST /upload/cancel?album=<uuid>, POST /upload/retry?album=<uuid>
 *                         the sheet's two buttons
 *   POST /setup?url=…&password=…
 *                         §1's setup screen's only action, through `Launcher.save` — so the real
 *                         credential store, the Keychain on a phone, is what a scenario exercises.
 *                         Answers `{"saved":true,"zone":…}` or 422 with the reason; poll `/state`
 *                         for the sync the save started
 *   POST /logout          Settings' log out, through `Launcher.logOut`
 *   GET  /screenshot      a PNG, when the root can render one offscreen; 404 when it cannot
 *
 * Bound to loopback only. Off unless a root starts it, and on iOS not even compiled into a
 * release build.
 */
public class ControlServer(
    private val port: Int,
    /**
     * The launcher rather than a model, because before setup there is no model to hold: the S3
     * client and the sync loop are built from a credential. Every endpoint resolves it per
     * request, so a suite can drive setup and then drive the app through the same server.
     */
    private val launcher: Launcher,
    /**
     * The one per-root endpoint. The desktop renders the same composition offscreen; iOS has no
     * offscreen scene, so it passes null and the host takes `simctl io screenshot` instead.
     */
    private val screenshot: ((width: Int, height: Int) -> ByteArray)? = null,
    /**
     * Fields a root adds to `/state` about things the model cannot see — on iOS, what `PHLivePhoto`
     * last answered for the open Live Photo, which lives in a native view rather than in `AppUi`.
     * Strings only, and added to every state body, set up or not.
     */
    private val extras: () -> Map<String, String> = { emptyMap() },
) {
    private var server: EmbeddedServer<*, *>? = null

    /** The running session's model, or null while the setup screen is up. */
    private val model: AppModel?
        get() = (launcher.state.value as? Launch.Running)?.session?.model

    /** The running session's upload, or null when there is no session or no gallery. */
    private val uploads: UploadModel?
        get() = (launcher.state.value as? Launch.Running)?.session?.uploads

    public fun start() {
        server = embeddedServer(CIO, port = port, host = "127.0.0.1") {
            routing {
                get("/state") { call.json(state()) }

                post("/nav") {
                    val to = call.request.queryParameters["to"] ?: "albums"
                    val model = model ?: return@post call.fail(HttpStatusCode.Conflict, "not set up")
                    when {
                        to == "back" -> model.back()
                        to == "albums" -> model.navigate { it.root() }
                        to == "settings" -> model.openSettings()
                        to.startsWith("photo/") -> {
                            val parts = to.removePrefix("photo/").split("/")
                            val id = parts.getOrNull(0)?.let { runCatching { Uuid.parse(it) }.getOrNull() }
                            val at = parts.getOrNull(1)?.toIntOrNull()
                            if (id == null || at == null) {
                                return@post call.fail(HttpStatusCode.BadRequest, "expected photo/<uuid>/<index>")
                            }
                            // Already inside that album -- how a person reaches a photo -- the album
                            // list is the grid's now and no longer holds it, so it is not looked up
                            // again. Found by the iOS suite, which navigates exactly that way.
                            val here = model.state.value.screen
                            if (!(here is Screen.Grid && here.albumId == id)) {
                                model.open(id) ?: return@post call.fail(HttpStatusCode.NotFound, "no such album")
                            }
                            model.openPhoto(at)
                        }
                        to.startsWith("album/") -> {
                            val id = runCatching { Uuid.parse(to.removePrefix("album/")) }.getOrNull()
                                ?: return@post call.fail(HttpStatusCode.BadRequest, "bad album id")
                            model.open(id) ?: return@post call.fail(HttpStatusCode.NotFound, "no such album")
                        }
                        else -> return@post call.fail(HttpStatusCode.BadRequest, "unknown destination")
                    }
                    call.json(state())
                }

                post("/cache") {
                    val model = model ?: return@post call.fail(HttpStatusCode.Conflict, "not set up")
                    val id = runCatching { Uuid.parse(call.request.queryParameters["album"].orEmpty()) }.getOrNull()
                        ?: return@post call.fail(HttpStatusCode.BadRequest, "bad album id")
                    val album = model.state.value.albums.firstOrNull { it.id == id }
                        ?: return@post call.fail(HttpStatusCode.NotFound, "no such album")
                    val action = when (call.request.queryParameters["action"]) {
                        "download" -> CacheAction.Download
                        "pause" -> CacheAction.Pause
                        "clear" -> CacheAction.Clear
                        else -> return@post call.fail(HttpStatusCode.BadRequest, "unknown action")
                    }
                    model.act(album, action)
                    call.json(state())
                }

                post("/sort") { model?.cycleSort(); call.json(state()) }
                post("/search") { model?.search(call.request.queryParameters["q"].orEmpty()); call.json(state()) }
                post("/refresh") { model?.refresh(); call.json(state()) }

                post("/map") { model?.toggleMap(); call.json(state()) }

                post("/map/camera") {
                    val model = model ?: return@post call.fail(HttpStatusCode.Conflict, "not set up")
                    val parameters = call.request.queryParameters
                    val latitude = parameters["latitude"]?.toDoubleOrNull()
                    val longitude = parameters["longitude"]?.toDoubleOrNull()
                    val zoom = parameters["zoom"]?.toDoubleOrNull()
                    if (latitude == null || longitude == null || zoom == null) {
                        return@post call.fail(HttpStatusCode.BadRequest, "expected latitude, longitude and zoom")
                    }
                    model.moveCamera(MapCamera(latitude, longitude, zoom))
                    call.json(state())
                }

                post("/map/viewport") {
                    val model = model ?: return@post call.fail(HttpStatusCode.Conflict, "not set up")
                    val width = call.request.queryParameters["w"]?.toDoubleOrNull()
                    val height = call.request.queryParameters["h"]?.toDoubleOrNull()
                    if (width == null || height == null) return@post call.fail(HttpStatusCode.BadRequest, "expected w and h")
                    model.mapViewport(width, height)
                    call.json(state())
                }

                post("/map/tap") {
                    val model = model ?: return@post call.fail(HttpStatusCode.Conflict, "not set up")
                    val ui = model.state.value
                    val map = ui.map ?: return@post call.fail(HttpStatusCode.Conflict, "no map is showing")
                    val camera = ui.stack.map?.camera ?: return@post call.fail(HttpStatusCode.Conflict, "the map is not framed yet")
                    val at = call.request.queryParameters["cluster"]?.toIntOrNull()
                        ?: return@post call.fail(HttpStatusCode.BadRequest, "expected cluster=<index>")
                    val cluster = map.clusters.at(camera.zoom).getOrNull(at)
                        ?: return@post call.fail(HttpStatusCode.NotFound, "no cluster $at at this zoom")
                    model.tapMap(cluster)
                    call.json(state())
                }

                post("/map/sheet") {
                    val model = model ?: return@post call.fail(HttpStatusCode.Conflict, "not set up")
                    if (call.request.queryParameters.contains("dismiss")) {
                        model.dismissSheet()
                        return@post call.json(state())
                    }
                    val id = runCatching { Uuid.parse(call.request.queryParameters["album"].orEmpty()) }.getOrNull()
                        ?: return@post call.fail(HttpStatusCode.BadRequest, "bad album id")
                    val album = model.state.value.map?.sheet?.firstOrNull { it.id == id }
                        ?: return@post call.fail(HttpStatusCode.NotFound, "no such album in the sheet")
                    model.openFromSheet(album)
                    call.json(state())
                }

                post("/upload/open") {
                    val model = model ?: return@post call.fail(HttpStatusCode.Conflict, "not set up")
                    val uploads = uploads ?: return@post call.fail(HttpStatusCode.NotFound, "this root has no gallery")
                    val upload = model.openUpload()
                        ?: return@post call.fail(HttpStatusCode.Conflict, "upload starts from the album list or a container")
                    uploads.open(upload.parent, upload.parentName)
                    call.json(state())
                }

                post("/upload/album") {
                    val uploads = uploads ?: return@post call.fail(HttpStatusCode.NotFound, "this root has no gallery")
                    val id = call.request.queryParameters["id"].orEmpty()
                    val album = uploads.picker.value.albums.firstOrNull { it.id == id }
                        ?: return@post call.fail(HttpStatusCode.NotFound, "no such gallery album")
                    uploads.chooseAlbum(album)
                    call.json(state())
                }

                post("/upload/select") {
                    val uploads = uploads ?: return@post call.fail(HttpStatusCode.NotFound, "this root has no gallery")
                    val ids = call.request.queryParameters["ids"].orEmpty().split(',').filter(String::isNotBlank)
                    uploads.select(ids)
                    uploads.chooseSelected()
                    call.json(state())
                }

                post("/upload/name") {
                    val uploads = uploads ?: return@post call.fail(HttpStatusCode.NotFound, "this root has no gallery")
                    call.request.queryParameters["name"]?.let(uploads::rename)
                    call.request.queryParameters["delete"]?.let { uploads.deleteAfterUpload(it == "true") }
                    call.json(state())
                }

                post("/upload/confirm") {
                    val model = model ?: return@post call.fail(HttpStatusCode.Conflict, "not set up")
                    val uploads = uploads ?: return@post call.fail(HttpStatusCode.NotFound, "this root has no gallery")
                    uploads.confirm() ?: return@post call.fail(HttpStatusCode.UnprocessableEntity, "choose photos and name the album first")
                    if (model.state.value.screen is Screen.Upload) model.back()
                    call.json(state())
                }

                post("/upload/cancel") {
                    val uploads = uploads ?: return@post call.fail(HttpStatusCode.NotFound, "this root has no gallery")
                    val id = runCatching { Uuid.parse(call.request.queryParameters["album"].orEmpty()) }.getOrNull()
                        ?: return@post call.fail(HttpStatusCode.BadRequest, "bad album id")
                    uploads.cancel(id)
                    call.json(state())
                }

                post("/upload/retry") {
                    val uploads = uploads ?: return@post call.fail(HttpStatusCode.NotFound, "this root has no gallery")
                    val id = runCatching { Uuid.parse(call.request.queryParameters["album"].orEmpty()) }.getOrNull()
                        ?: return@post call.fail(HttpStatusCode.BadRequest, "bad album id")
                    uploads.retry(id)
                    call.json(state())
                }

                post("/setup") {
                    val url = call.request.queryParameters["url"].orEmpty()
                    val password = call.request.queryParameters["password"].orEmpty()
                    when (val outcome = launcher.save(url, password)) {
                        // What was saved, not `/state`: the session has only just been opened and
                        // its first sync is still running, so a caller polls `/state` for that
                        // rather than this reply waiting on a model mid-construction.
                        is SaveOutcome.Saved -> call.json("""{"saved":true,"zone":${outcome.storage.zone.json()}}""")
                        is SaveOutcome.Rejected -> call.fail(HttpStatusCode.UnprocessableEntity, outcome.why)
                    }
                }

                post("/logout") {
                    launcher.logOut()
                    call.json(state())
                }

                get("/screenshot") {
                    val render = screenshot
                        ?: return@get call.fail(HttpStatusCode.NotFound, "this root cannot render offscreen")
                    val width = call.request.queryParameters["w"]?.toIntOrNull() ?: 430
                    val height = call.request.queryParameters["h"]?.toIntOrNull() ?: 890
                    runCatching { render(width, height) }
                        .onSuccess { call.respondBytes(it, ContentType.Image.PNG) }
                        .onFailure { call.fail(HttpStatusCode.InternalServerError, "could not render: $it") }
                }
            }
        }.start(wait = false)
        println("control server on http://127.0.0.1:$port")
    }

    public fun stop() {
        server?.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
        server = null
    }

    /** Opens an album by id from the list the model already holds. Null when it is not there. */
    private fun AppModel.open(id: Uuid): Unit? =
        state.value.albums.firstOrNull { it.id == id }?.let { open(it) }

    /**
     * Hand-rolled JSON, deliberately: the payload is scalars and one list, and a serialization
     * dependency would be a second one for the sake of a development surface.
     */
    private fun state(): String {
        val launch = launcher.state.value
        val ui = (launch as? Launch.Running)?.session?.model?.state?.value
            ?: return when (launch) {
                // Saying so is more use than an empty album list that looks like a working app
                // whose library happens to hold no photographs.
                is Launch.Blocked -> body(listOf("\"screen\":\"blocked\"", "\"reason\":${launch.reason.json()}"))
                else -> body(listOf("\"screen\":\"setup\""))
            }
        return render(ui, uploads)
    }

    private fun body(fields: List<String>): String =
        "{" + (fields + extras().map { (name, value) -> "${name.json()}:${value.json()}" }).joinToString(",") + "}"

    private fun render(ui: AppUi, uploads: UploadModel?): String {
        val screen = when (val s = ui.screen) {
            is Screen.Albums -> "albums"
            is Screen.Container -> "container/${s.albumId}"
            is Screen.Grid -> "grid/${s.albumId}"
            is Screen.Photo -> "photo/${s.albumId}/${s.index}"
            is Screen.Settings -> "settings"
            is Screen.Upload -> "upload"
        }
        val albums = ui.albums.joinToString(",") {
            val cache = ui.cacheOf(it)
            val actions = ui.actionsOf(it).joinToString(",") { action -> "\"$action\"" }
            """{"id":"${it.id}","name":${it.name.json()},"photos":${it.photoCount},""" +
                """"cache":{"held":${cache.heldBytes},"total":${cache.totalBytes},""" +
                """"moving":${cache.moving},"complete":${cache.complete},"empty":${cache.empty}},"actions":[$actions]}"""
        }
        val photos = ui.photos.joinToString(",") {
            """{"id":"${it.id}","filename":${it.filename.json()},"type":"${it.mediaType}"}"""
        }
        val notice = ui.notice?.let {
            """{"kind":"${it.kind}","title":${it.title.json()},"detail":${it.detail.json()}}"""
        } ?: "null"
        val livePair = ui.livePair?.let { """{"still":${it.still.json()},"video":${it.video.json()}}""" } ?: "null"
        val upload = uploads?.let { model ->
            val picker = model.picker.value
            val galleryAlbums = picker.albums.joinToString(",") {
                """{"id":${it.id.json()},"name":${it.name.json()},"count":${it.count}}"""
            }
            val naming = picker.naming?.let {
                """{"name":${it.name.json()},"count":${it.count},"delete":${it.deleteFromGallery}}"""
            } ?: "null"
            val statuses = model.statuses.value.joinToString(",") {
                """{"album":"${it.albumId}","name":${it.name.json()},"stage":"${it.stage}",""" +
                    """"files":${it.files},"filesDone":${it.filesDone},"bytes":${it.bytes},"bytesDone":${it.bytesDone},""" +
                    """"failure":${it.failure?.json() ?: "null"}}"""
            }
            listOf(
                """"picker":{"access":${picker.access?.name?.json() ?: "null"},"albums":[$galleryAlbums],""" +
                    """"assets":${picker.assets.size},"selected":${picker.selected.size},"naming":$naming}""",
                "\"uploads\":[$statuses]",
            )
        }.orEmpty()
        return body(listOf(
            "\"map\":${mapState(ui)}",
            "\"screen\":\"$screen\"",
            "\"sort\":\"${ui.sort}\"",
            "\"query\":${ui.query.json()}",
            // The line the nav bar shows: an album's own on its grid or its map, the list's elsewhere.
            // Always the list's used to read "2 of 3 albums on the map" over an album's photos.
            "\"subtitle\":${(if (ui.screen is Screen.Grid) ui.photosSubtitle else ui.subtitle).json()}",
            "\"sync\":${ui.sync.toString().json()}",
            "\"notice\":$notice",
            "\"storage\":{\"media\":${ui.storage.media},\"packs\":${ui.storage.packs},\"albumsHeld\":${ui.storage.albumsHeld}}",
            "\"albums\":[$albums]",
            "\"photos\":[$photos]",
            "\"videoPath\":${ui.videoPath?.json() ?: "null"}",
            "\"livePair\":$livePair",
        ) + upload)
    }

    /**
     * The level's map: null when it has never been shown, and otherwise whether it is, where the
     * camera is, and what it draws at that camera's zoom -- in the order `/map/tap` indexes.
     */
    private fun mapState(ui: AppUi): String {
        val view = ui.stack.map ?: return "null"
        val looking = view.camera
        val camera = looking?.let {
            """{"latitude":${it.latitude},"longitude":${it.longitude},"zoom":${it.zoom}}"""
        } ?: "null"
        val map = ui.map
        val clusters = if (map != null && looking != null) {
            map.clusters.at(looking.zoom).joinToString(",") { cluster ->
                val members = cluster.members.map { map.pins[it] }
                val albums = members.filterIsInstance<MapPin.OfAlbum>().joinToString(",") { "\"${it.album.id}\"" }
                val photos = members.filterIsInstance<MapPin.OfPhoto>().joinToString(",") { it.index.toString() }
                """{"count":${cluster.members.size},"albums":[$albums],"photos":[$photos]}"""
            }
        } else ""
        val sheet = map?.sheet?.joinToString(",", "[", "]") { "\"${it.id}\"" } ?: "null"
        return """{"showing":${view.showing},"camera":$camera,"moves":${view.moves},""" +
            """"pins":${map?.pins?.size ?: 0},"total":${map?.total ?: 0},"clusters":[$clusters],"sheet":$sheet}"""
    }

    private fun String.json(): String =
        "\"" + replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private suspend fun ApplicationCall.json(body: String) =
        respondText(body, ContentType.Application.Json)

    private suspend fun ApplicationCall.fail(status: HttpStatusCode, why: String) =
        respondText(why, ContentType.Text.Plain, status)
}
