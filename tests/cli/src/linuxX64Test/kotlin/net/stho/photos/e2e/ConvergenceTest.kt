package net.stho.photos.e2e

import kotlin.test.Test
import kotlin.test.assertFalse
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

    /**
     * The same convergence, over the one shape where a file on disk has no row of its own.
     *
     * A Live Photo is a HEIC and a MOV and, by §3, a single row — so the MOV is named by
     * `photo.live_video_filename` or by nothing at all. Nothing did, before schema 4, and the
     * consequence was not a wrong photograph but a library that never settled: the reconciler
     * read the MOV as never ingested, planned it as an upload, `commit` found the pair already
     * claimed and produced nothing, and the shard was rewritten identically. Every hour. On this
     * library, three albums and 187 photographs of "to read" that never was.
     *
     * It has to be asserted *here* and not only in `:domain`, because the pairing signal is the
     * Apple maker note: the offline suite states the identifier a fake probe returns, while this
     * one writes the bytes an iPhone writes and makes libexif and `pi_emit_apple_content_id`
     * find them. A build that paired nothing would converge for the wrong reason — every MOV an
     * ordinary video, every run quiet, and the Live Photos gone.
     */
    @Test
    fun runningTwiceOverLivePhotosConvergesAndSaysNothingToDo() = scenario("converge-live") {
        library {
            photosignore()
            album("Wochenende") {
                livePhoto("IMG_0679", identifier = "B34B6B99-C28F-4E16-A788-79AA0E30BB18")
                livePhoto("IMG_0680", identifier = "6D1F2C07-9A55-4B30-8E12-3C0A7F6B4411")
                jpeg("0001.jpg")
            }
        }
        zone { empty() }

        run("sync")
        run("sync")

        expect {
            exit(ExitCode.CLEAN)
            // Closed-world, and that is the point: two pairs and a still are three rows, not
            // five. The MOVs are in the zone as blobs the Live Photo rows own, and a MOV that
            // had been mistaken for an ordinary video would show up here as a fourth row.
            zone {
                album("Wochenende") {
                    photo("IMG_0679.HEIC")
                    photo("IMG_0680.HEIC")
                    photo("0001.jpg")
                }
            }
        }

        // §7 promises a run that changes nothing is one LIST and says so. The plan line and the
        // album line are what a person actually saw instead, so they are what is asserted.
        assertFalse("to do:" in output, "the second run should have found nothing to do:\n$output")
        assertFalse("~ Wochenende" in output, "the second run should not rewrite the album:\n$output")
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
