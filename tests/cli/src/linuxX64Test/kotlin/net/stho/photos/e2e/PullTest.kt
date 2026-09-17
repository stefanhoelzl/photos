package net.stho.photos.e2e

import kotlin.test.Test
import kotlin.test.assertTrue
import net.stho.photos.ingest.ExitCode

/**
 * §7's pull: the one direction in which `sync` writes into the library.
 *
 * A new album from the phone is an `uploaded` addition naming an album no shard is yet (§8). The
 * laptop claims it, archives it, derives it into `meta/` under that album's id at `encoded`, and
 * deletes the addition -- after which it is an ordinary album, deletable like any other.
 *
 * This is also the scenario that needs the zone materialised directly: no CLI run produces an
 * addition, because every album a run creates is one it found on disk.
 */
class PullTest {

    @Test
    fun anUnclaimedAlbumIsArchivedIntoTheLibraryAndClaimed() = scenario("pull") {
        library { photosignore() }
        zone {
            empty()
            // An album no shard is: this is what makes it a pull rather than a merge.
            addition(anId(), "FromPhone") {
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
            addition(anId(), "FromPhone") { photo("0001.jpg", width = 160, height = 120) }
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
            addition(anId(), "FromPhone") { photo("0001.jpg", width = 160, height = 120) }
        }

        run("sync")
        run("sync")

        expect {
            exit(ExitCode.CLEAN)
            library { exists("FromPhone/0001.jpg") }
            zone { album("FromPhone") { sourcePath("FromPhone"); photo("0001.jpg") } }
        }
    }

    /**
     * Names are unique among siblings, ignoring case (§2). A new phone album whose name a folder
     * already has is neither renamed nor landed beside it: it waits in the zone, and the run says so.
     */
    @Test
    fun aNewPhoneAlbumWhoseNameIsTakenWaits() = scenario("pull-held-back") {
        library {
            photosignore()
            album("Alps") { jpeg("0001.jpg") }
        }
        run("sync")
        zone { addition(anId(), "alps") { photo("0002.jpg") } }

        run("sync")

        expect {
            exit(ExitCode.COMPLETED_WITH_FAILURES)
            library { absent("Alps/0002.jpg") }
        }
        assertTrue("alps" in output && "is taken" in output, "the run names the album that waits:\n$output")
    }

    /** `Alps` beside `alps` on a case-sensitive disk: neither is ingested, and the run says so. */
    @Test
    fun siblingFoldersNamedAlikeButForCaseAreLeftAlone() = scenario("name-clash") {
        library {
            photosignore()
            album("Alps") { jpeg("0001.jpg") }
            album("alps") { jpeg("0002.jpg") }
        }
        zone { empty() }

        run("sync")

        expect {
            exit(ExitCode.COMPLETED_WITH_FAILURES)
            zone { empty() }
        }
        assertTrue("named alike but for case" in output, "the run names the clash:\n$output")
    }
}
