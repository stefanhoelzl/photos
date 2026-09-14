@file:OptIn(ExperimentalForeignApi::class, kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package net.stho.photos.ios.debug

import kotlin.concurrent.AtomicReference
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import net.stho.photos.control.ControlServer
import net.stho.photos.ios.PhotosRoot
import platform.UIKit.UIViewController
import platform.posix.getenv

/**
 * The Debug framework's Swift-facing surface: the release entry point, plus a control server.
 *
 * The server starts only when the app is launched with `PHOTOS_CONTROL_PORT`, which `simctl`
 * passes on as `SIMCTL_CHILD_PHOTOS_CONTROL_PORT` — so an ordinary Debug run from Xcode opens no
 * port either. It is bound to loopback, and none of this exists in a Release build (decision 5):
 * that one links `:app:ios`, which cannot see `:app:control`.
 *
 * No screenshot hook: iOS has no offscreen Compose scene, so `/screenshot` answers 404 and the
 * host takes `simctl io screenshot`, which shows what the device really drew.
 */
public object PhotosDebugEntry {

    /** Held so the server lives as long as the app does. */
    private var server: ControlServer? = null

    /**
     * What `PHLivePhoto` last answered for the open Live Photo. Written on the main queue, read on
     * the server's, hence the atomic.
     */
    private val livePhoto = AtomicReference("none")

    public fun viewController(): UIViewController {
        val root = PhotosRoot(onLivePhoto = { livePhoto.value = it })
        getenv("PHOTOS_CONTROL_PORT")?.toKString()?.toIntOrNull()?.let { port ->
            server = ControlServer(port, root.launcher, extras = { mapOf("livePhoto" to livePhoto.value) })
                .also { it.start() }
        }
        return root.viewController()
    }
}
