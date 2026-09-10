package net.stho.photos.catalog

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeSource
import kotlin.uuid.Uuid
import net.stho.photos.catalog.blobId
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import net.stho.photos.model.MediaType
import net.stho.photos.model.PhotoRow

/**
 * Milestone B's empirical bar, the counterpart to A's 38 SigV4 vectors and its static ELF.
 *
 * §4 claims the merged rebuild takes 1–3 s, and §6's whole feel rests on it: it is the one number
 * E inherits and cannot renegotiate. So it is measured here at this library's real scale rather
 * than asserted — 337 albums, 34,607 photo rows, the shape INGEST.md records.
 *
 * The budget is deliberately loose. This is a regression bar, not a benchmark: it should catch an
 * accidental per-row transaction or a missing index, and it should not fail because CI was busy.
 */
class RebuildScaleTest {

    @AfterTest
    fun cleanUp(): Unit = deleteTemporaryDirectories()


    @Test
    fun aFullScaleRebuildLandsInsideSection4sBudget() {
        val shards = realisticLibrary()
        assertEquals(337, shards.size)
        assertEquals(34_607, shards.sumOf { it.photos.size })

        val path = Path(temporaryDirectory("scale"), "merged.db")
        val writer = CatalogWriter(path, testDrivers)

        val started = TimeSource.Monotonic.markNow()
        val summary = writer.rebuild(shards)
        val elapsed = started.elapsedNow()
        writer.close()

        assertEquals(337, summary.albums)
        assertEquals(34_607, summary.photos)
        assertTrue(summary.orphanedAlbums.isEmpty())

        val size = SystemFileSystem.metadataOrNull(path)?.size ?: 0
        println(
            """

            Full-scale rebuild — DESIGN §4's 1–3 s claim
              albums   ${summary.albums}
              photos   ${summary.photos}
              elapsed  $elapsed
              merged   ${size / 1_048_576.0} MB (measured; see the note in RebuildScaleTest)

            """.trimIndent(),
        )

        // Three times the design's upper figure. Anything near this means something structural
        // changed — a per-row transaction, a dropped index — not a slow machine.
        assertTrue(elapsed < 9.seconds, "rebuild took $elapsed; §4 budgets 1–3 s")

        // §3's 4.53 MB prototype predates UUID keys: six 36-character uuid columns per photo row
        // is roughly 8 MB across 34,607 rows, which is the whole difference. The bar is a
        // regression check against that measured figure, not against 4.53 MB.
        assertTrue(size < 20_000_000, "merged DB is $size bytes; ~13 MB is expected")
    }

    /**
     * The queries the grid and the album list run must stay indexed at full scale — an unindexed
     * sort of 34,607 rows is invisible in a unit test and obvious on a phone.
     */
    @Test
    fun readsStayIndexedAtFullScale() {
        val path = Path(temporaryDirectory("scale-read"), "merged.db")
        CatalogWriter(path, testDrivers).use { it.rebuild(realisticLibrary()) }

        val reader = CatalogReader(path, testDrivers)
        val neuseeland = assertNotNull(reader.allAlbums().firstOrNull { it.name == "Neuseeland" })

        val started = TimeSource.Monotonic.markNow()
        val photos = reader.photos(inAlbum = neuseeland.id)
        val gridOpen = started.elapsedNow()

        assertEquals(1_755, photos.size)
        assertTrue(gridOpen < 500.milliseconds, "opening the largest grid took $gridOpen")

        // Ordering must still hold across the whole album, not just the first page.
        val dated = photos.takeWhile { it.takenAt != null }
        assertTrue(dated.zipWithNext().all { (a, b) -> a.takenAt!! <= b.takenAt!! })
        assertTrue(photos.drop(dated.size).all { it.takenAt == null })
        reader.close()
    }
}

/**
 * INGEST.md's inventory, reproduced exactly: 4 containers, 288 leaf albums with photos, 337 albums
 * in total, 34,607 photo rows, and `Neuseeland` at 1,755.
 */
private fun realisticLibrary(): List<Shard> {
    val containers = listOf("Immling" to 3, "Kalifornien" to 17, "Rauhöd" to 15, "Weihnachten" to 17)
    val nestedLeaves = containers.sumOf { it.second } // 52
    val topLevelLeaves = 337 - containers.size - nestedLeaves // 281
    val base = Instant.fromEpochSeconds(1_000_000_000)

    // Neuseeland is the outlier the design keeps citing; the rest share what is left.
    val remainder = 34_607 - 1_755
    val leafCount = nestedLeaves + topLevelLeaves - 1
    val each = remainder / leafCount
    val counts = List(leafCount - 1) { each } + (remainder - each * (leafCount - 1))
    val next = counts.iterator()

    val shards = mutableListOf<Shard>()
    for ((name, childCount) in containers) {
        val parent = AlbumInfo(
            id = Uuid.random(), name = name, sourcePath = name, thumbsId = null, addedAt = base,
        )
        shards += Shard(parent)
        repeat(childCount) { index ->
            shards += leaf("$name/$index", parent.id, next.next(), base)
        }
    }
    shards += leaf("Neuseeland", null, 1_755, base)
    repeat(topLevelLeaves - 1) { index -> shards += leaf("Album $index", null, next.next(), base) }
    return shards
}

private fun leaf(name: String, parent: Uuid?, photos: Int, base: Instant): Shard = Shard(
    info = AlbumInfo(
        id = Uuid.random(),
        name = name,
        parent = parent,
        sourcePath = name,
        thumbsId = blobId(),
        addedAt = base,
    ),
    photos = List(photos) { offset ->
        // INGEST.md measured 24.5% of photos carrying GPS and 99.5% carrying a date.
        val hasGps = offset % 4 == 0
        val hasDate = offset % 200 != 0
        PhotoRow(
            id = Uuid.random(),
            filename = "IMG_${offset.toString().padStart(5, '0')}.jpg",
            takenAt = if (hasDate) base + (offset * 60).seconds else null,
            latitude = if (hasGps) 47.9 + (offset % 100) / 1000.0 else null,
            longitude = if (hasGps) 12.2 + (offset % 100) / 1000.0 else null,
            width = 4000,
            height = 3000,
            bytes = 3_145_728,
            mediaType = MediaType.PHOTO,
            imageId = blobId(),
        )
    },
)
