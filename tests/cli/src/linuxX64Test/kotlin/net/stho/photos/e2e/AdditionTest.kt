package net.stho.photos.e2e

import kotlin.test.Test
import net.stho.photos.ingest.ExitCode

/**
 * §7's merge: photos the phone added to an album that already exists (§8).
 *
 * The phone writes them as an addition — a shard of their own under `addition/`, naming the album —
 * and never touches the album's shard. `sync` brings them into the album's folder, under names only
 * the laptop makes unique, derives them into the album, and deletes the addition. The closed-world
 * zone check is what proves nothing is left behind: no addition, and no blob only it referenced.
 */
class AdditionTest {

    @Test
    fun anAdditionIsMergedIntoItsAlbumsFolderWithAClashingNameSuffixed() = scenario("addition-merge") {
        library {
            photosignore()
            album("Alps") { jpeg("0001.jpg") }
        }
        run("sync")
        zone {
            addition("Alps") {
                photo("0001.jpg")
                photo("0002.jpg")
            }
        }

        run("sync")

        expect {
            exit(ExitCode.CLEAN)
            library {
                exists("Alps/0001.jpg")
                exists("Alps/0001 (2).jpg")
                exists("Alps/0002.jpg")
            }
            zone {
                album("Alps") {
                    sourcePath("Alps")
                    encoded()
                    photo("0001.jpg")
                    photo("0001 (2).jpg")
                    photo("0002.jpg")
                }
            }
        }
    }

    /** Merged photos are the album's own from then on: the next run finds nothing to do. */
    @Test
    fun aMergedAdditionIsNotUploadedAgain() = scenario("addition-converge") {
        library {
            photosignore()
            album("Alps") { jpeg("0001.jpg") }
        }
        run("sync")
        zone { addition("Alps") { photo("0002.jpg") } }

        run("sync")
        run("sync")

        expect {
            exit(ExitCode.CLEAN)
            zone {
                album("Alps") {
                    encoded()
                    photo("0001.jpg")
                    photo("0002.jpg")
                }
            }
        }
    }

    /** Two uploads into a new album the laptop has not pulled yet: pulled from the first, the second merged, in one run. */
    @Test
    fun aSecondAdditionToANewAlbumIsMergedAfterThePull() = scenario("addition-after-pull") {
        library { photosignore() }
        zone {
            val fromPhone = anId()
            addition(fromPhone, "FromPhone") { photo("0001.jpg") }
            addition(fromPhone, "FromPhone") { photo("0002.jpg") }
        }

        run("sync")

        expect {
            exit(ExitCode.CLEAN)
            library {
                exists("FromPhone/0001.jpg")
                exists("FromPhone/0002.jpg")
            }
            zone {
                album("FromPhone") {
                    sourcePath("FromPhone")
                    encoded()
                    photo("0001.jpg")
                    photo("0002.jpg")
                }
            }
        }
    }

    /** An addition whose album is not in the zone becomes the album it records, pulled like any new phone album. */
    @Test
    fun anAdditionWhoseAlbumIsGoneBecomesThatAlbum() = scenario("addition-orphan") {
        library { photosignore() }
        zone { addition(anId(), "Gone") { photo("0001.jpg") } }

        run("sync")

        expect {
            exit(ExitCode.CLEAN)
            library { exists("Gone/0001.jpg") }
            zone {
                album("Gone") {
                    sourcePath("Gone")
                    encoded()
                    photo("0001.jpg")
                }
            }
        }
    }

    /**
     * The album's folder deleted before the merge: the album goes, and its addition becomes that album
     * again — not in the run that deleted the folder, which never brings a folder back, but on the next.
     */
    @Test
    fun anAdditionToADeletedAlbumLandsOnTheRunAfter() = scenario("addition-deleted") {
        library {
            photosignore()
            album("Alps") { jpeg("0001.jpg") }
        }
        run("sync")
        zoneAddition("Alps") { photo("0002.jpg") }
        library { removeTree("Alps") }

        run("sync")
        run("sync")

        expect {
            exit(ExitCode.CLEAN)
            library {
                absent("Alps/0001.jpg")
                exists("Alps/0002.jpg")
            }
            zone {
                album("Alps") {
                    sourcePath("Alps")
                    encoded()
                    photo("0002.jpg")
                }
            }
        }
    }

    private suspend fun Scenario.zoneAddition(path: String, body: GivenAddition.() -> Unit): GivenAddition {
        lateinit var added: GivenAddition
        zone { added = addition(path, body) }
        return added
    }
}
