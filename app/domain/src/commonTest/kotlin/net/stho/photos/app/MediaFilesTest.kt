package net.stho.photos.app

import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import net.stho.photos.catalog.ObjectId

/** `blobs/` names each blob for what its bytes are, and still answers in ids. */
class MediaFilesTest {

    // Absolute, never the module directory: a relative temp root would leave files beside source.
    private val root = Path(
        SystemTemporaryDirectory.takeIf { it.isAbsolute } ?: Path("/tmp"),
        "photos-tests-media-${Random.nextLong().toString(16)}",
    )
    private val files = MediaFiles(root)

    @AfterTest
    fun cleanUp() {
        SystemFileSystem.list(files.directory).forEach { SystemFileSystem.delete(it) }
        SystemFileSystem.delete(files.directory)
        SystemFileSystem.delete(root)
    }

    @Test
    fun theExtensionIsReadFromTheBytes() {
        assertEquals("jpg", MediaFiles.extensionOf(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte())))
        assertEquals("png", MediaFiles.extensionOf(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A)))
        assertEquals("heic", MediaFiles.extensionOf(isoMedia("heic")))
        assertEquals("heic", MediaFiles.extensionOf(isoMedia("mif1")))
        // PHLivePhoto pairs only a QuickTime MOV, so a Live Photo's video must never become an .mp4.
        assertEquals("mov", MediaFiles.extensionOf(isoMedia("qt  ")))
        assertEquals("mp4", MediaFiles.extensionOf(isoMedia("isom")))
        assertEquals("bin", MediaFiles.extensionOf(isoMedia("avif")))
        assertEquals("bin", MediaFiles.extensionOf(byteArrayOf(1, 2, 3)))
        assertEquals("bin", MediaFiles.extensionOf(ByteArray(0)))
    }

    @Test
    fun aBlobIsFoundByIdWhateverItsExtension() {
        val id = id("a")
        val landed = files.named(id, write("landed", isoMedia("qt  ")))
        SystemFileSystem.atomicMove(Path(root, "landed"), landed)

        assertEquals("$id.mov", landed.name)
        assertEquals(landed, files.find(id))
        assertNull(files.find(id("b")))
    }

    @Test
    fun aBlobCachedWithoutAnExtensionIsRenamedOnFirstLookup() {
        val id = id("c")
        write("blobs/$id", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))

        val found = files.find(id)

        assertEquals(Path(files.directory, "$id.jpg"), found)
        assertFalse(SystemFileSystem.exists(Path(files.directory, id.toString())), "renamed, not copied")
        assertEquals(found, files.find(id), "and found under its new name after")
    }

    @Test
    fun namesOnDiskAreReadBackAsIdsAndAFetchInFlightIsNot() {
        val id = id("d")
        assertEquals(id, files.idOf("$id.heic"))
        assertEquals(id, files.idOf(id.toString()))
        assertNull(files.idOf("$id.${Random.nextLong()}.part"))
        assertNull(files.idOf("notes.txt"))
    }

    @Test
    fun deletingRemovesTheBlobUnderAnyName() {
        val id = id("e")
        write("blobs/$id.heic", isoMedia("heic"))
        files.delete(id)
        assertNull(files.find(id))
        assertTrue(SystemFileSystem.list(files.directory).isEmpty())
    }

    private fun id(seed: String): ObjectId = ObjectId.ofContent(seed.encodeToByteArray())

    private fun isoMedia(brand: String): ByteArray = byteArrayOf(0, 0, 0, 24) + "ftyp$brand".encodeToByteArray() + ByteArray(12)

    private fun write(name: String, bytes: ByteArray): Path {
        val path = Path(root, name)
        SystemFileSystem.sink(path).buffered().use { it.write(bytes) }
        return path
    }
}
