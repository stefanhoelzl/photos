package net.stho.photos.library

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.io.files.Path
import net.stho.photos.IgnoreRulesUnreadableFailure

/** Walking a library and applying its `.photosignore`. */
class LibraryWalkerTest {

    @AfterTest
    fun cleanUp(): Unit = deleteTemporaryDirectories()

    @Test
    fun withNoPhotosignoreNothingAtAllIsExcluded() {
        // The sharp edge, stated as a test: there is no built-in dot rule, so a hidden directory
        // full of deleted photos is walked like any other.
        val root = library(".dtrash/files/deleted.jpg", "Album/real.jpg", "digikam4.db")

        val contents = LibraryWalker(root).walk()

        assertEquals(3, contents.fileCount)
        assertTrue(contents.ignoredFiles.isEmpty())
        assertTrue(contents.prunedDirectories.isEmpty())
        assertTrue(contents.albums.any { it.relativePath == ".dtrash/files" })
    }

    @Test
    fun aDirectoryRulePrunesTheWholeSubtree() {
        val root = library(
            ".dtrash/files/a.jpg",
            ".dtrash/files/b.jpg",
            ".dtrash/info.txt",
            "Album/real.jpg",
            ignore = ".dtrash/",
        )

        val contents = LibraryWalker(root).walk()

        assertEquals(1, contents.fileCount)
        assertEquals(listOf("Album"), contents.albums.map(LibraryAlbum::relativePath))
        // Pruned, not enumerated: the three files inside never appear anywhere.
        assertEquals(1, contents.prunedDirectories.size)
        assertTrue(contents.ignoredFiles.isEmpty())
        assertEquals(listOf(1), contents.ruleUsage)
    }

    @Test
    fun basenameRulesApplyAtEveryDepth() {
        val root = library("digikam4.db", "Album/stray.db", "Album/keep.jpg", ignore = "*.db")

        val contents = LibraryWalker(root).walk()

        assertEquals(1, contents.fileCount)
        assertEquals(2, contents.ignoredFiles.size)
        assertEquals(listOf(2), contents.ruleUsage)
    }

    @Test
    fun thePhotosignoreExcludesItselfWithoutBeingListed() {
        // The one rule that is not in the file.
        val root = library("Album/real.jpg", ignore = "*.db")

        val contents = LibraryWalker(root).walk()

        assertEquals(1, contents.fileCount)
        assertFalse(
            contents.albums.any { album -> album.files.any { it.name == IgnoreRules.FILENAME } },
        )
    }

    @Test
    fun rulesThatMatchedNothingAreReported() {
        val root = library(
            "Album/real.jpg",
            "Album/stray.db",
            ignore = "*.db\n*.pds\nMissing/thing.psd",
        )

        val contents = LibraryWalker(root).walk()

        assertEquals(listOf(1, 0, 0), contents.ruleUsage)
        assertEquals(
            listOf("*.pds", "Missing/thing.psd"),
            contents.unusedRules.map(IgnoreRule::source),
        )
    }

    @Test
    fun albumsAreDirectoriesThatHoldAtLeastOneSurvivingFile() {
        // An album whose every file is ignored is not an album — it is nothing at all.
        val root = library("Docs/a.db", "Docs/b.db", "Album/real.jpg", ignore = "*.db")

        val contents = LibraryWalker(root).walk()

        assertEquals(listOf("Album"), contents.albums.map(LibraryAlbum::relativePath))
    }

    @Test
    fun theRootItselfCanHoldFiles() {
        val root = library("loose.jpg", "Album/real.jpg")

        val contents = LibraryWalker(root).walk()

        assertEquals(listOf("", "Album"), contents.albums.map(LibraryAlbum::relativePath))
    }

    @Test
    fun resultsAreOrderedSoTwoRunsReportTheSameThing() {
        val root = library("c/3.jpg", "a/1.jpg", "b/2.jpg")

        val first = LibraryWalker(root).walk()
        val second = LibraryWalker(root).walk()

        assertEquals(listOf("a", "b", "c"), first.albums.map(LibraryAlbum::relativePath))
        assertEquals(
            first.albums.map(LibraryAlbum::relativePath),
            second.albums.map(LibraryAlbum::relativePath),
        )
    }

    @Test
    fun anUnreadablePhotosignoreAbortsBeforeAnyWalkingHappens() {
        val root = library("Album/real.jpg")
        Path(root, IgnoreRules.FILENAME)
            .writeBytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x80.toByte()))

        assertFailsWith<IgnoreRulesUnreadableFailure> { LibraryWalker(root) }
    }
}
