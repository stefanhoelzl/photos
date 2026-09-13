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
import net.stho.photos.app.SaveOutcome
import net.stho.photos.app.Screen

/**
 * How the app is driven and reviewed without a person at the keyboard (§6).
 *
 * The point is that state is *assertable*, not merely visible: `GET /state` returns what the app
 * believes, which is what a scenario asserts on, while a screenshot only ever proves something
 * was drawn. Every mutating endpoint calls the same model method a tap does, so what a suite
 * drives is real behaviour rather than a stand-in.
 *
 *   GET  /state           the current screen, sort, query, albums with their cache state, sync
 *                         status, notice, and what the viewer holds (videoPath, livePair)
 *   POST /nav?to=albums   push a screen: albums | settings | album/<uuid> |
 *                         photo/<uuid>/<index> | back
 *   POST /sort            cycle the sort, exactly as the icon does
 *   POST /search?q=text   type into the search field
 *   POST /refresh         pull-to-refresh
 *   POST /cache?album=<uuid>&action=download|pause|clear
 *                         the album row's cache controls, which are a swipe or a tap on the strip
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
) {
    private var server: EmbeddedServer<*, *>? = null

    /** The running session's model, or null while the setup screen is up. */
    private val model: AppModel?
        get() = (launcher.state.value as? Launch.Running)?.session?.model

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
                            model.open(id) ?: return@post call.fail(HttpStatusCode.NotFound, "no such album")
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
                is Launch.Blocked -> """{"screen":"blocked","reason":${launch.reason.json()}}"""
                else -> """{"screen":"setup"}"""
            }
        return render(ui)
    }

    private fun render(ui: AppUi): String {
        val screen = when (val s = ui.screen) {
            is Screen.Albums -> "albums"
            is Screen.Container -> "container/${s.albumId}"
            is Screen.Grid -> "grid/${s.albumId}"
            is Screen.Photo -> "photo/${s.albumId}/${s.index}"
            is Screen.Settings -> "settings"
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
        return "{" + listOf(
            "\"screen\":\"$screen\"",
            "\"sort\":\"${ui.sort}\"",
            "\"query\":${ui.query.json()}",
            "\"subtitle\":${ui.subtitle.json()}",
            "\"sync\":${ui.sync.toString().json()}",
            "\"notice\":$notice",
            "\"storage\":{\"media\":${ui.storage.media},\"packs\":${ui.storage.packs},\"albumsHeld\":${ui.storage.albumsHeld}}",
            "\"albums\":[$albums]",
            "\"photos\":[$photos]",
            "\"videoPath\":${ui.videoPath?.json() ?: "null"}",
            "\"livePair\":$livePair",
        ).joinToString(",") + "}"
    }

    private fun String.json(): String =
        "\"" + replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private suspend fun ApplicationCall.json(body: String) =
        respondText(body, ContentType.Application.Json)

    private suspend fun ApplicationCall.fail(status: HttpStatusCode, why: String) =
        respondText(why, ContentType.Text.Plain, status)
}
