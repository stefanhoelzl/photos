@file:OptIn(ExperimentalForeignApi::class)

package net.stho.photos.adapter.linux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import net.stho.photos.ports.LockAttempt
import net.stho.photos.ports.LockHandle
import platform.posix.getpid

/**
 * One sync at a time.
 *
 * It matters because of the arithmetic: the first import is about 39 hours and the unit fires
 * hourly, so the second run overlaps the first thirty-eight times unless something stops it.
 */
class RunLockTest {

    @Test
    fun secondRunIsRefused() = withScratchDirectory("lock") { cache ->
        FlockRunLock(cache.toString()).acquire().granted().use {
            val second = FlockRunLock(cache.toString()).acquire()
            assertTrue(second is LockAttempt.HeldBy, "a second run must be refused, got $second")
        }
    }

    @Test
    fun refusalNamesTheProcessHoldingIt() = withScratchDirectory("lock") { cache ->
        val lock = FlockRunLock(cache.toString())
        lock.acquire().granted().use {
            // §7's refusal has to name the process — "another sync is already running (pid N)"
            // — so the pid rides on the result rather than needing a second call.
            val refusal = assertIs<LockAttempt.HeldBy>(FlockRunLock(cache.toString()).acquire())
            assertEquals(getpid(), refusal.pid)
        }
    }

    /**
     * Releasing is closing, so nothing has to be cleaned up — which is the point of using
     * `flock` rather than a pid file. A run killed with -9 leaves no stale lock.
     */
    @Test
    fun theLockIsReleasedWhenTheHolderGoesAway() = withScratchDirectory("lock") { cache ->
        FlockRunLock(cache.toString()).acquire().granted().use { }
        // The file is still there; that is not what the lock is.
        assertTrue(SystemFileSystem.exists(Path(cache, "lock")))
        FlockRunLock(cache.toString()).acquire().granted().use { }
    }

    @Test
    fun differentCacheDirectoriesDoNotContend() = withScratchDirectory("lock-a") { a ->
        withScratchDirectory("lock-b") { b ->
            FlockRunLock(a.toString()).acquire().granted().use {
                FlockRunLock(b.toString()).acquire().granted().use { }
            }
        }
    }

    @Test
    fun theCacheDirectoryIsCreatedIfItIsNotThereYet() = withScratchDirectory("lock") { parent ->
        val cache = Path(parent, "not-yet")
        FlockRunLock(cache.toString()).acquire().granted().use {
            assertTrue(SystemFileSystem.exists(cache))
        }
    }
}

/** The handle a test expects to have been granted. */
private fun LockAttempt.granted(): LockHandle =
    assertIs<LockAttempt.Acquired>(this, "the lock should have been granted").handle
