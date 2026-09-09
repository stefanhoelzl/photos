package net.stho.photos.e2e

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import net.stho.photos.fixtures.write
import net.stho.photos.ingest.ExitCode

/**
 * Where the shipped binary stages derivatives, and what it does with what it finds there.
 *
 * The run that motivated these was killed by the kernel and left its staging behind in `/tmp` —
 * which is tmpfs, so the gigabytes it was holding were memory, and they stayed held until the
 * machine was rebooted. Both halves are asserted here against the real binary: nothing is staged
 * outside the cache directory, and debris in it does not survive the next run.
 *
 * Debris is planted rather than produced by killing a run. `popen` gives the harness no pid to
 * signal, and a killed run's leftovers are, by construction, exactly what is planted here — the
 * interrupt path itself is proven a layer down, in `:adapter:linux` and `:domain`.
 */
class StagingTest {

    @Test
    fun stagingLeftByAKilledRunIsReclaimedByTheNext() = scenario("staging-reclaim") {
        library {
            photosignore()
            album("Iceland") { jpeg("0001.jpg") }
        }
        zone { empty() }

        val work = Path(cacheRoot, "work")
        SystemFileSystem.createDirectories(work)
        Path(work, "orphan.mp4").write(ByteArray(64 * 1024))

        run("sync")

        expect {
            exit(ExitCode.CLEAN)
            zone { album("Iceland") { photo("0001.jpg") } }
        }
        assertFalse(
            SystemFileSystem.exists(work),
            "staging from a killed run should not have survived the next one",
        )
        // The one line in the journal that says a previous run did not finish.
        assertTrue("reclaimed" in output, "the run should say what it reclaimed:\n$output")
    }

    /**
     * `$TMPDIR` is set for every scenario and points into its scratch tree, so a run that still
     * staged there would be caught here rather than only on a machine whose `/tmp` is tmpfs.
     */
    @Test
    fun nothingIsStagedOutsideTheCacheDirectory() = scenario("staging-not-in-tmp") {
        library {
            photosignore()
            album("Iceland") {
                jpeg("0001.jpg")
                video("clip.mp4")
            }
        }
        zone { empty() }

        run("sync")

        expect { exit(ExitCode.CLEAN) }
        val strays = SystemFileSystem.list(scratch)
            .map { it.name }
            .filter { it.startsWith("photos-cli-") }
        assertTrue(strays.isEmpty(), "a run staged in \$TMPDIR: $strays")
        assertFalse(
            SystemFileSystem.exists(Path(cacheRoot, "work")),
            "a run that finished should have emptied its own work directory",
        )
    }
}
