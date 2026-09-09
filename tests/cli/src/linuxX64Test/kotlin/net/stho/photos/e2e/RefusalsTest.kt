package net.stho.photos.e2e

import kotlin.test.Test
import net.stho.photos.ingest.ExitCode

/**
 * The exit codes, and the guard that protects the zone from a library that is not there.
 *
 * Nothing else in this repository executes the shipped binary, so before this suite existed no
 * exit code was proven at all -- and §7 gives usage its own number precisely so that `1` can
 * keep meaning "the run finished and something in the zone changed".
 */
class RefusalsTest {

    /**
     * The marker rule, which is the one whose failure loses everything: an unmounted disk is a
     * bare mount point and a mistyped root is somebody else's directory, and neither has a
     * `.photosignore`. Without the guard the walk finds no albums and concludes they were all
     * deleted.
     */
    @Test
    fun aLibraryWithNoMarkerAbortsAndWritesNothing() = scenario("no-marker") {
        library {
            // Deliberately no photosignore().
            album("Iceland") { jpeg("0001.jpg") }
        }
        zone {
            empty()
            album("Iceland") {
                sourcePath = "Iceland"
                photo("0001.jpg")
            }
        }

        run("sync")

        expect {
            exit(ExitCode.ABORTED)
            // The zone is untouched: the album that was there is still there, whole.
            zone { album("Iceland") { photo("0001.jpg") } }
        }
    }

    /** An empty marker is a library that wants no exclusions -- the deliberate asymmetry (§7). */
    @Test
    fun anEmptyMarkerIsALibraryThatExcludesNothing() = scenario("empty-marker") {
        library {
            photosignore()
            album("Iceland") { jpeg("0001.jpg") }
        }
        zone { empty() }

        run("sync")

        expect {
            exit(ExitCode.CLEAN)
            zone { album("Iceland") { photo("0001.jpg") } }
        }
    }

    @Test
    fun dryRunChangesNothing() = scenario("dry-run") {
        library {
            photosignore()
            album("Iceland") { jpeg("0001.jpg") }
        }
        zone { empty() }

        run("sync", "--dry-run")

        expect {
            exit(ExitCode.CLEAN)
            zone { empty() }
        }
    }

    @Test
    fun anUnknownFlagIsAUsageError() = scenario("usage") {
        library { photosignore() }

        run("sync", "--not-a-flag")

        expect { exit(ExitCode.USAGE) }
    }

    /** `.photosignore` governs uploads and nothing else (§7). */
    @Test
    fun anExcludedFileIsNeverUploaded() = scenario("exclusions") {
        library {
            photosignore("*.heic")
            album("Iceland") {
                jpeg("0001.jpg")
                heic("skip-me.heic")
            }
        }
        zone { empty() }

        run("sync")

        expect {
            exit(ExitCode.CLEAN)
            zone { album("Iceland") { photo("0001.jpg") } }
        }
    }
}
