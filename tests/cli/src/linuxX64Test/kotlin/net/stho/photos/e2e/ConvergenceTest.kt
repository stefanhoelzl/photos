package net.stho.photos.e2e

import kotlin.test.Test
import net.stho.photos.ingest.ExitCode

/**
 * What a second run does, and what the library saying less means.
 *
 * The rules themselves are proven offline in `:domain` against hand-built shards. What these add
 * is that the shipped binary, over a real zone, reaches the same conclusions -- including the
 * asymmetry that a *missing* directory deletes an album while an *emptied* one does not.
 */
class ConvergenceTest {

    @Test
    fun runningTwiceOverAnUnchangedLibraryLeavesTheSameZone() = scenario("converge") {
        library {
            photosignore()
            album("Iceland") {
                jpeg("0001.jpg")
                jpeg("0002.jpg")
            }
        }
        zone { empty() }

        run("sync")
        // The cache is warm now, and warm honestly: this is the ETag-skip path (§4).
        run("sync")

        expect {
            exit(ExitCode.CLEAN)
            zone {
                album("Iceland") {
                    photo("0001.jpg")
                    photo("0002.jpg")
                }
            }
        }
    }

    @Test
    fun aDeletedFileLosesItsPhotoAndStrandsNothing() = scenario("delete-file") {
        library {
            photosignore()
            album("Iceland") {
                jpeg("0001.jpg")
                jpeg("0002.jpg")
            }
        }
        zone { empty() }
        run("sync")

        library { remove("Iceland/0002.jpg") }
        run("sync")

        expect {
            exit(ExitCode.CLEAN)
            // Closed-world, so 0002.jpg being gone is asserted by not naming it -- and the blob
            // integrity check is what proves its derivatives went with it.
            zone { album("Iceland") { photo("0001.jpg") } }
        }
    }

    /** `rm -rf album` removes it from the zone. */
    @Test
    fun aRemovedDirectoryDeletesTheAlbum() = scenario("delete-album") {
        library {
            photosignore()
            album("Iceland") { jpeg("0001.jpg") }
            album("Norway") { jpeg("0002.jpg") }
        }
        zone { empty() }
        run("sync")

        library { removeTree("Norway") }
        run("sync")

        expect {
            exit(ExitCode.CLEAN)
            zone { album("Iceland") { photo("0001.jpg") } }
        }
    }

    /**
     * Deleting an album's *contents* does not. An album with zero photos is a thing the app
     * must render (§6), and
     * reading an emptied directory as a deletion is how a reorganisation loses an album.
     */
    @Test
    fun anEmptiedDirectoryKeepsItsAlbum() = scenario("empty-album") {
        library {
            photosignore()
            album("Iceland") { jpeg("0001.jpg") }
        }
        zone { empty() }
        run("sync")

        library { remove("Iceland/0001.jpg") }
        run("sync")

        expect {
            exit(ExitCode.CLEAN)
            zone { album("Iceland") }
        }
    }
}
