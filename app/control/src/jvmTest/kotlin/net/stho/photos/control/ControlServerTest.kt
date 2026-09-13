package net.stho.photos.control

import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.URLEncoder
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import net.stho.photos.app.Account
import net.stho.photos.app.Launcher
import net.stho.photos.app.Session
import net.stho.photos.ports.Keyring
import net.stho.photos.ports.KeyringRead

/**
 * The protocol, over real HTTP, before any app is behind it.
 *
 * The two endpoints the iOS suite leans on hardest are `/setup` and `/logout`, because they are
 * how a scenario reaches the Keychain at all — so they are checked here against a fake keyring,
 * with the one thing a host driver needs from `/state` before a session exists: that it says
 * "setup" rather than rendering an empty library.
 */
class ControlServerTest {

    private val keyring = FakeKeyring()
    private val opened = mutableListOf<String>()
    private val launcher = Launcher(Account(keyring)) { storage, _ ->
        opened += storage.zone
        NoSession
    }
    private val port = ServerSocket(0).use { it.localPort }
    private val server = ControlServer(port, launcher).also { it.start() }
    private val http = HttpClient.newHttpClient()

    @AfterTest
    fun stop() = server.stop()

    @Test
    fun beforeSetupStateSaysSo() {
        launcher.start()
        assertEquals("""{"screen":"setup"}""", get("/state").body())
    }

    @Test
    fun setupStoresBothItemsAndOpensTheSession() {
        launcher.start()
        val url = "https://de-s3.storage.bunnycdn.com/my-photos"
        val response = post("/setup?url=${url.encoded()}&password=${"hunter2".encoded()}")
        assertEquals(200, response.statusCode(), response.body())
        assertEquals("""{"saved":true,"zone":"my-photos"}""", response.body())
        assertEquals(url, keyring.stored["endpoint"])
        assertEquals("hunter2", keyring.stored["password"])
        assertEquals(listOf("my-photos"), opened)
    }

    @Test
    fun anUnparseableUrlIsRejectedAndNothingIsStored() {
        launcher.start()
        val response = post("/setup?url=${"not a url".encoded()}&password=x")
        assertEquals(422, response.statusCode())
        assertTrue(keyring.stored.isEmpty(), "nothing should be stored: ${keyring.stored}")
    }

    @Test
    fun logoutForgetsBothItems() {
        keyring.stored += mapOf("endpoint" to "https://de-s3.storage.bunnycdn.com/my-photos", "password" to "hunter2")
        launcher.start()
        assertEquals(200, post("/logout").statusCode())
        assertTrue(keyring.stored.isEmpty())
        assertEquals("""{"screen":"setup"}""", get("/state").body())
    }

    @Test
    fun aRootThatCannotRenderAnswersScreenshotWith404() {
        assertEquals(404, get("/screenshot").statusCode())
    }

    private fun get(path: String) =
        http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).GET().build(), HttpResponse.BodyHandlers.ofString())

    private fun post(path: String) =
        http.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).POST(HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun String.encoded() = URLEncoder.encode(this, Charsets.UTF_8)

    /** The session factory's product is never read here: only that setup asked for one. */
    private object NoSession : Session {
        override val model get() = throw UnsupportedOperationException()
        override val thumbnails get() = throw UnsupportedOperationException()
        override fun start() = Unit
        override fun close() = Unit
    }

    private class FakeKeyring : Keyring {
        val stored = mutableMapOf<String, String>()
        override fun read(field: String): KeyringRead = stored[field]?.let(KeyringRead::Found) ?: KeyringRead.Absent
        override fun write(field: String, secret: String) { stored[field] = secret }
        override fun remove(field: String) { stored -= field }
    }
}
