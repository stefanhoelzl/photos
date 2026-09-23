package net.stho.photos.control

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlin.uuid.Uuid
import net.stho.photos.app.DateRange
import net.stho.photos.app.Day
import net.stho.photos.app.DesktopModel
import net.stho.photos.app.DesktopUi
import net.stho.photos.app.ListEntry
import net.stho.photos.app.TileSize

/**
 * How the desktop viewer is driven and reviewed without a person at the keyboard (§11): the
 * phone's [ControlServer], for the viewer's model.
 *
 * The same bargain: `GET /state` is what the viewer believes, which a scenario asserts on, and
 * every mutating endpoint calls the model method a click or a key does.
 *
 *   GET  /state            loading and why a rebuild failed, the sort, query and range, the
 *                          sidebar's rows, the selected album and its photos, the tile size, the
 *                          focused tile, the open photo and what the viewer holds for it
 *   POST /refresh          F5: replay the CLI's shards
 *   POST /sort             the sidebar's sort icon
 *   POST /search?q=text    typing into the field
 *   POST /calendar         the field's calendar icon; `?open=false` closes the sheet
 *   POST /range?from=yyyy-mm-dd&to=yyyy-mm-dd
 *                          the sheet's Apply, 422 for a range with no photos; with no dates, the ✕
 *   POST /select?album=<uuid> | ?step=±1
 *                          a click on a sidebar row, or ↑/↓ in the sidebar
 *   POST /focus?delta=n    an arrow key on the grid: the focus ring moves n tiles
 *   POST /open?index=n | ?focused
 *                          a click on a tile, or Enter on the focused one
 *   POST /step?forward=true|false
 *                          ←/→ in the viewer
 *   POST /close            Esc, or the viewer's back
 *   POST /tile?size=small|medium|large | ?closer=true|false
 *                          the bar's S/M/L, or Ctrl±
 *   GET  /screenshot?w=…&h=…
 *                          a PNG of the window's content, rendered offscreen
 *
 * Bound to loopback only, and off unless the root is started with a port.
 */
public class ViewerControlServer(
    private val port: Int,
    private val model: DesktopModel,
    private val screenshot: ((width: Int, height: Int) -> ByteArray)? = null,
) {
    private var server: EmbeddedServer<*, *>? = null

    public fun start() {
        server = embeddedServer(CIO, port = port, host = "127.0.0.1") {
            routing {
                get("/state") { call.json(render(model.state.value)) }

                post("/refresh") {
                    model.refresh()
                    call.json(render(model.state.value))
                }

                post("/sort") {
                    model.cycleSort()
                    call.json(render(model.state.value))
                }

                post("/search") {
                    model.search(call.request.queryParameters["q"].orEmpty())
                    call.json(render(model.state.value))
                }

                post("/calendar") {
                    if (call.request.queryParameters["open"] == "false") model.closeCalendar() else model.openCalendar()
                    call.json(render(model.state.value))
                }

                post("/range") {
                    val from = call.request.queryParameters["from"]
                    val to = call.request.queryParameters["to"]
                    if (from == null && to == null) {
                        model.clearRange()
                        return@post call.json(render(model.state.value))
                    }
                    val start = from?.let(Day::parse)
                    val end = (to ?: from)?.let(Day::parse)
                    if (start == null || end == null || end < start) {
                        return@post call.fail(HttpStatusCode.BadRequest, "expected from=yyyy-mm-dd&to=yyyy-mm-dd")
                    }
                    if (!model.applyRange(DateRange(start, end))) {
                        return@post call.fail(HttpStatusCode.UnprocessableEntity, "no photos were taken in that range")
                    }
                    call.json(render(model.state.value))
                }

                post("/select") {
                    val step = call.request.queryParameters["step"]?.toIntOrNull()
                    if (step != null) {
                        model.selectAdjacent(step)
                        return@post call.json(render(model.state.value))
                    }
                    val id = call.request.queryParameters["album"]?.let { runCatching { Uuid.parse(it) }.getOrNull() }
                        ?: return@post call.fail(HttpStatusCode.BadRequest, "expected album=<uuid> or step=±1")
                    val album = model.state.value.albums.firstOrNull { it.id == id }
                        ?: return@post call.fail(HttpStatusCode.NotFound, "no album $id on the list")
                    model.select(album)
                    call.json(render(model.state.value))
                }

                post("/focus") {
                    val delta = call.request.queryParameters["delta"]?.toIntOrNull()
                        ?: return@post call.fail(HttpStatusCode.BadRequest, "expected delta=n")
                    model.moveFocus(delta)
                    call.json(render(model.state.value))
                }

                post("/open") {
                    if (call.request.queryParameters.contains("focused")) {
                        model.openFocused()
                    } else {
                        val index = call.request.queryParameters["index"]?.toIntOrNull()
                            ?: return@post call.fail(HttpStatusCode.BadRequest, "expected index=n or focused")
                        model.openPhoto(index)
                    }
                    call.json(render(model.state.value))
                }

                post("/step") {
                    model.step(forward = call.request.queryParameters["forward"] != "false")
                    call.json(render(model.state.value))
                }

                post("/close") {
                    model.closePhoto()
                    call.json(render(model.state.value))
                }

                post("/tile") {
                    val size = call.request.queryParameters["size"]
                    val closer = call.request.queryParameters["closer"]
                    when {
                        size != null -> model.tile(
                            TileSize.entries.firstOrNull { it.name.equals(size, ignoreCase = true) }
                                ?: return@post call.fail(HttpStatusCode.BadRequest, "size is small, medium or large"),
                        )
                        closer != null -> model.zoom(closer = closer != "false")
                        else -> return@post call.fail(HttpStatusCode.BadRequest, "expected size= or closer=")
                    }
                    call.json(render(model.state.value))
                }

                get("/screenshot") { call.screenshot() }
            }
        }.start(wait = false)
        println("control server on http://127.0.0.1:$port")
    }

    public fun stop() {
        server?.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
        server = null
    }

    private suspend fun ApplicationCall.screenshot() {
        val render = screenshot ?: return fail(HttpStatusCode.NotFound, "this root cannot render offscreen")
        val width = request.queryParameters["w"]?.toIntOrNull() ?: 1280
        val height = request.queryParameters["h"]?.toIntOrNull() ?: 800
        runCatching { render(width, height) }
            .onSuccess { respondBytes(it, ContentType.Image.PNG) }
            .onFailure { fail(HttpStatusCode.InternalServerError, "could not render: $it") }
    }

    private fun render(ui: DesktopUi): String {
        val albums = ui.rows.filterIsInstance<ListEntry.Row>().joinToString(",") { row ->
            """{"id":"${row.album.id}","name":${row.album.name.json()},"photos":${row.album.photoCount},""" +
                """"depth":${row.depth},"header":${row.header},"contents":${row.contents.json()}}"""
        }
        val photos = ui.photos.joinToString(",") {
            """{"id":"${it.id}","filename":${it.filename.json()},"type":"${it.mediaType}"}"""
        }
        val range = ui.range?.let { """{"from":"${it.start}","to":"${it.end}","label":${it.label.json()}}""" } ?: "null"
        val calendar = ui.calendar?.let { calendar ->
            calendar.days.entries.sortedBy { it.key }.joinToString(",", "{\"days\":{", "}}") { (day, photos) -> "\"$day\":$photos" }
        } ?: "null"
        val livePair = ui.livePair?.let { """{"still":${it.still.json()},"video":${it.video.json()}}""" } ?: "null"
        return "{" + listOf(
            "\"loading\":${ui.loading}",
            "\"failure\":${ui.failure?.json() ?: "null"}",
            "\"skipped\":${ui.skipped}",
            "\"sort\":\"${ui.sort}\"",
            "\"query\":${ui.query.json()}",
            "\"range\":$range",
            "\"calendar\":$calendar",
            "\"subtitle\":${ui.albumsSubtitle.json()}",
            "\"albums\":[$albums]",
            "\"selected\":${ui.selected?.let { "\"${it.id}\"" } ?: "null"}",
            "\"albumSubtitle\":${ui.photosSubtitle.json()}",
            "\"photos\":[$photos]",
            "\"thumbnails\":${ui.thumbnails.size}",
            "\"tile\":\"${ui.tile}\"",
            "\"focus\":${ui.focus ?: "null"}",
            "\"open\":${ui.open ?: "null"}",
            "\"viewerSubtitle\":${ui.viewerSubtitle.json()}",
            // Whether the open photo has been decoded; its pixels are the screenshot's to show.
            "\"preview\":${ui.preview != null}",
            "\"videoPath\":${ui.videoPath?.json() ?: "null"}",
            "\"livePair\":$livePair",
        ).joinToString(",") + "}"
    }
}
