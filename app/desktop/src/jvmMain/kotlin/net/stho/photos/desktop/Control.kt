package net.stho.photos.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import kotlin.uuid.Uuid
import net.stho.photos.ui.state.AppModel
import net.stho.photos.ui.state.Screen

/**
 * How the app is driven and reviewed without a person at the keyboard (§6).
 *
 * It lives here, in the composition root, and never in `:ui` — so the iOS build cannot inherit
 * it — and it is off unless `--control-port` is given.
 *
 * The point is that state is *assertable*, not merely visible: `GET /state` returns what the
 * app believes, which is what `:tests:app` will assert on, while a screenshot only ever proves
 * something was drawn.
 *
 *   GET  /state           the current screen, sort, query, album names, sync status, notice
 *   POST /nav?to=albums   push a screen: albums | settings | album/<uuid> |
 *                         photo/<uuid>/<index> | back
 *   POST /sort            cycle the sort, exactly as the icon does
 *   POST /search?q=text   type into the search field
 *   POST /refresh         pull-to-refresh, which the desktop has no gesture for
 *   POST /cache?album=<uuid>&action=download|pause|clear
 *                         the album row's cache controls, which are a swipe or a tap on the
 *                         strip -- neither of which this server can perform. It calls the same
 *                         model method the tap does, so what the suite drives is real behaviour
 *                         rather than a stand-in.
 *   GET  /screenshot      the same composition rendered offscreen, as a PNG
 */
public class ControlServer(
    private val port: Int,
    private val model: AppModel,
    private val content: @Composable () -> Unit,
) {
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)

    public fun start() {
        server.createContext("/state") { it.replyJson(state()) }
        server.createContext("/nav") { exchange ->
            val to = exchange.query("to") ?: "albums"
            when {
                to == "back" -> model.back()
                to == "albums" -> model.navigate { it.root() }
                to == "settings" -> model.openSettings()
                // The viewer was the one screen the harness could not reach at all, which made
                // its loading state unreviewable without a device.
                to.startsWith("photo/") -> {
                    val parts = to.removePrefix("photo/").split("/")
                    val id = parts.getOrNull(0)?.let { runCatching { Uuid.parse(it) }.getOrNull() }
                    val at = parts.getOrNull(1)?.toIntOrNull()
                    if (id == null || at == null) {
                        return@createContext exchange.reply(400, "expected photo/<uuid>/<index>")
                    }
                    model.navigate { it.push(Screen.Grid(id, to)) }
                    model.openPhoto(at)
                }
                to.startsWith("album/") -> {
                    val id = runCatching { Uuid.parse(to.removePrefix("album/")) }.getOrNull()
                    if (id == null) return@createContext exchange.reply(400, "bad album id")
                    model.navigate { it.push(Screen.Grid(id, to)) }
                }
                else -> return@createContext exchange.reply(400, "unknown destination")
            }
            exchange.replyJson(state())
        }
        server.createContext("/cache") { exchange ->
            val id = runCatching { Uuid.parse(exchange.query("album").orEmpty()) }.getOrNull()
                ?: return@createContext exchange.reply(400, "bad album id")
            val album = model.state.value.albums.firstOrNull { it.id == id }
                ?: return@createContext exchange.reply(404, "no such album")
            val action = when (exchange.query("action")) {
                "download" -> net.stho.photos.ui.state.CacheAction.Download
                "pause" -> net.stho.photos.ui.state.CacheAction.Pause
                "clear" -> net.stho.photos.ui.state.CacheAction.Clear
                else -> return@createContext exchange.reply(400, "unknown action")
            }
            model.act(album, action)
            exchange.replyJson(state())
        }
        server.createContext("/sort") { model.cycleSort(); it.replyJson(state()) }
        server.createContext("/search") { model.search(it.query("q") ?: ""); it.replyJson(state()) }
        server.createContext("/refresh") { model.refresh(); it.replyJson(state()) }
        server.createContext("/screenshot") { exchange ->
            runCatching { screenshot(exchange.query("w")?.toIntOrNull() ?: 430, exchange.query("h")?.toIntOrNull() ?: 890) }
                .onSuccess { png ->
                    exchange.responseHeaders.add("Content-Type", "image/png")
                    exchange.sendResponseHeaders(200, png.size.toLong())
                    exchange.responseBody.use { it.write(png) }
                }
                .onFailure { exchange.reply(500, "could not render: $it") }
        }
        server.executor = null
        server.start()
        println("control server on http://127.0.0.1:$port")
    }

    public fun stop(): Unit = server.stop(0)

    /**
     * Rendered offscreen rather than grabbed from the window.
     *
     * The scene draws the *same* composition from the same model, so what comes back is the
     * app's real state — and it works with no display at all, which is what lets `:tests:app`
     * and an agent on a headless machine look at the UI.
     */
    private fun screenshot(width: Int, height: Int): ByteArray {
        val scene = ImageComposeScene(width = width, height = height, density = Density(2f)) {
            content()
        }
        try {
            val image = scene.render()
            return requireNotNull(image.encodeToData()) { "skia declined to encode the frame" }.bytes
        } finally {
            scene.close()
        }
    }

    /**
     * Hand-rolled JSON, deliberately: the payload is a handful of scalars and one list of
     * names, and a serialization dependency in the composition root would be carried by the
     * shipped app for the sake of a development surface.
     */
    private fun state(): String {
        val ui = model.state.value
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
                """"moving":${cache.moving},"complete":${cache.complete}},"actions":[$actions]}"""
        }
        val notice = ui.notice?.let {
            """{"kind":"${it.kind}","title":${it.title.json()},"detail":${it.detail.json()}}"""
        } ?: "null"
        return """
            {"screen":"$screen","sort":"${ui.sort}","query":${ui.query.json()},
             "subtitle":${ui.subtitle.json()},"sync":${ui.sync.toString().json()},
             "notice":$notice,
             "storage":{"media":${ui.storage.media},"packs":${ui.storage.packs},"albumsHeld":${ui.storage.albumsHeld}},
             "albums":[$albums]}
        """.trimIndent()
    }

    private fun String.json(): String =
        "\"" + replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private fun HttpExchange.query(name: String): String? =
        requestURI.rawQuery
            ?.split("&")
            ?.map { it.split("=", limit = 2) }
            ?.firstOrNull { it.first() == name }
            ?.getOrNull(1)
            ?.let { java.net.URLDecoder.decode(it, StandardCharsets.UTF_8) }

    private fun HttpExchange.replyJson(body: String) {
        responseHeaders.add("Content-Type", "application/json")
        reply(200, body)
    }

    private fun HttpExchange.reply(status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }
}
