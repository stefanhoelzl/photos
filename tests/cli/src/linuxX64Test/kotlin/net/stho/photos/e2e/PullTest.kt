package net.stho.photos.e2e

import kotlin.test.Test
import net.stho.photos.ingest.ExitCode

/**
 * §7's pull: the one direction in which `sync` writes into the library.
 *
 * An `uploaded` shard is one the phone finished uploading and nothing has encoded (§8). The
 * laptop claims it, archives it, re-derives it and writes `encoded` -- after which it is an
 * ordinary album, deletable like any other.
 *
 * This is also the scenario that needs the zone materialised directly: no CLI run produces an
 * unclaimed shard, because every album a run creates is one it found on disk.
 */
class PullTest {

    @Test
    fun anUnclaimedAlbumIsArchivedIntoTheLibraryAndClaimed() = scenario("pull") {
        library { photosignore() }
        zone {
            empty()
            album("FromPhone") {
                // No sourcePath: this is what makes it a pull rather than a reconcile.
                photo("0001.jpg", width = 160, height = 120)
                photo("0002.jpg", width = 160, height = 120)
            }
        }

        run("sync")

        expect {
            exit(ExitCode.CLEAN)
            library {
                isDirectory("FromPhone")
                exists("FromPhone/0001.jpg")
                exists("FromPhone/0002.jpg")
            }
            zone {
                album("FromPhone") {
                    sourcePath("FromPhone")
                    photo("0001.jpg")
                    photo("0002.jpg")
                }
            }
        }
    }

    /**
     * The transition has to complete, or the album is pulled again on every run forever. The
     * assertion that matters is `encoded`: it is what takes the album out of the pull set.
     */
    @Test
    fun aPulledAlbumEndsUpEncodedAndIsNotPulledAgain() = scenario("pull-completes") {
        library { photosignore() }
        zone {
            empty()
            album("FromPhone") { photo("0001.jpg", width = 160, height = 120) }
        }

        run("sync")
        run("sync")

        expect {
            exit(ExitCode.CLEAN)
            library { exists("FromPhone/0001.jpg") }
            zone {
                album("FromPhone") {
                    sourcePath("FromPhone")
                    encoded()
                    photo("0001.jpg")
                }
            }
        }
    }

    /** Once claimed it is ordinary: a second run neither re-downloads nor re-uploads it. */
    @Test
    fun aClaimedAlbumIsLeftAlone() = scenario("pull-then-converge") {
        library { photosignore() }
        zone {
            empty()
            album("FromPhone") { photo("0001.jpg", width = 160, height = 120) }
        }

        run("sync")
        run("sync")

        expect {
            exit(ExitCode.CLEAN)
            library { exists("FromPhone/0001.jpg") }
            zone { album("FromPhone") { sourcePath("FromPhone"); photo("0001.jpg") } }
        }
    }
}
