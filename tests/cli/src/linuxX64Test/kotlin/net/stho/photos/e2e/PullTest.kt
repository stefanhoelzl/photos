package net.stho.photos.e2e

import kotlin.test.Test
import net.stho.photos.ingest.ExitCode

/**
 * §7's archive-only pull: the one direction in which `sync` writes into the library.
 *
 * A shard with no `source_path` is an album no local directory has been connected to -- what an
 * album the phone created looks like (§8). The laptop archives it and claims it by writing
 * `source_path`, after which it is an ordinary album, deletable like any other.
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
