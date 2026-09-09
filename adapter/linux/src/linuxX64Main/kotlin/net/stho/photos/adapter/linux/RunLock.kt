@file:OptIn(ExperimentalForeignApi::class)

package net.stho.photos.adapter.linux

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import net.stho.photos.ports.LockHandle
import net.stho.photos.IngestAbort
import net.stho.photos.ports.LockAttempt
import net.stho.photos.ports.RunLock
import platform.posix.EWOULDBLOCK
import platform.posix.O_CLOEXEC
import platform.posix.O_CREAT
import platform.posix.O_RDONLY
import platform.posix.O_RDWR
import platform.posix.S_IRGRP
import platform.posix.S_IROTH
import platform.posix.S_IRUSR
import platform.posix.S_IWUSR
import platform.posix.errno
import platform.posix.ftruncate
import platform.posix.getpid
import platform.posix.pread
import platform.posix.pwrite
import platform.posix.strerror
import photosflock.photos_flock_exclusive_nowait

/**
 * §7's `RunLock`: `flock` on a file in the cache directory.
 *
 * The hourly unit and a run you type yourself will otherwise overlap the moment an import takes
 * longer than an hour — which the first one will, at 39 hours. Two runs derive and upload the
 * same files, then fight over the same shards: `If-Match` keeps the catalog consistent, but the
 * loser's blobs are already in the zone with nothing pointing at them, so the cost is a week of
 * paying for debris and twice the upload on a link that is the bottleneck to begin with.
 *
 * The cache is what two runs would actually corrupt — `sync_state.db` and `shards/` — so that
 * is where the lock file lives. `flock` is advisory and process-scoped, so the kernel releases
 * it however the run ends, including `kill -9`, a panic, or a laptop losing power. Nothing to
 * clean up, and no stale lock file to explain to anyone.
 *
 * > Two runs pointed at the same library but *different* cache directories would still collide.
 * > That is not defended against: it needs `--cache-dir` to be passed deliberately, and a rule
 * > that guessed at "same library" from a path would be wrong the first time a symlink appeared.
 */
public class FlockRunLock(private val cacheRoot: String) : RunLock {

    /** The lock file itself. Its contents are the holder's pid and nothing else. */
    public val path: String = Path(cacheRoot, "lock").toString()

    /**
     * Takes the lock, or answers null at once. Never waits: a run that queued behind another
     * would start the moment it finished, with a plan built from a library it re-walked anyway,
     * and the hourly timer will come round again regardless.
     *
     * [LockAttempt.HeldBy] and a thrown [IngestAbort.CacheUnusable] are different outcomes with
     * different exit codes — "the sync is happening, just not this one" (75) against "this run
     * cannot even try" (3) — which is why the port returns a result for the first and throws for
     * the second.
     */
    override fun acquire(): LockAttempt {
        try {
            SystemFileSystem.createDirectories(Path(cacheRoot))
        } catch (failure: Exception) {
            throw IngestAbort.CacheUnusable(cacheRoot, "cannot create the cache directory: $failure")
        }

        val descriptor = platform.posix.open(
            path,
            O_RDWR or O_CREAT or O_CLOEXEC,
            S_IRUSR or S_IWUSR or S_IRGRP or S_IROTH,
        )
        if (descriptor < 0) throw IngestAbort.CacheUnusable(path, "cannot open the lock file: ${lastError()}")

        if (photos_flock_exclusive_nowait(descriptor) != 0) {
            val blocked = errno == EWOULDBLOCK
            val reason = lastError()
            platform.posix.close(descriptor)
            // Read the holder before returning: the refusal is only actionable if it names
            // the process (§7), and the pid is in the file the loser just failed to lock.
            if (blocked) return LockAttempt.HeldBy(holder())
            throw IngestAbort.CacheUnusable(path, reason)
        }

        // Our pid, so the next contender can name who has it. Written after the lock is held,
        // so what is in the file is always the holder's.
        ftruncate(descriptor, 0)
        val text = "${getpid()}\n"
        memScoped { pwrite(descriptor, text.cstr.ptr, text.length.convert(), 0) }
        return LockAttempt.Acquired(FlockHandle(descriptor))
    }

    /** The pid written by whoever holds the lock, when it can be read. */
    private fun holder(): Int? {
        val descriptor = platform.posix.open(path, O_RDONLY)
        if (descriptor < 0) return null
        try {
            val buffer = ByteArray(32)
            val count = buffer.usePinned { pread(descriptor, it.addressOf(0), 31.convert(), 0) }
            if (count <= 0) return null
            return buffer.decodeToString(0, count.toInt()).trim().toIntOrNull()
        } finally {
            platform.posix.close(descriptor)
        }
    }
}

/**
 * The held lock. Releasing is closing: `flock` is tied to the descriptor, so the kernel does it
 * for us whatever happens to the process — which is why there is no `deinit` to miss.
 */
private class FlockHandle(private val descriptor: Int) : LockHandle {
    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        platform.posix.close(descriptor)
    }
}

/**
 * The lock could not be attempted at all — an unwritable cache directory, say.
 *
 * Distinct from `acquire()` answering null, which means another run holds it and is §7's exit
 * 75. This one stops the run (3).
 */
public class RunLockFailure(
    message: String,
    override val cause: Throwable? = null,
) : Exception(message)

private fun lastError(): String = strerror(errno)?.toKString() ?: "errno $errno"
