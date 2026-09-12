package net.stho.photos.ios

import androidx.compose.ui.window.ComposeUIViewController
import net.stho.photos.storage.asStorageUrl
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.UIKit.UIViewController
import platform.posix.getenv

/**
 * The whole of this framework's Swift-facing surface: one object, one function.
 *
 * Swift does not learn that there is a model, a queue or a catalog — it presents a view
 * controller, and everything inside it is Kotlin talking to Kotlin. That is what keeps the
 * Xcode project a shell around a framework rather than half of the application.
 *
 * An `object` rather than a top-level function, and the reason is the name Swift sees: a
 * top-level Kotlin function arrives as a member of a class named after its *file*
 * (`EntryKt.photosViewController()`), so renaming this file would break `App.swift`. An object
 * is named by its declaration, and reaches Swift as `PhotosEntry.shared.viewController()`.
 */
public object PhotosEntry {

    public fun viewController(): UIViewController {
        val credentials = Credentials.fromEnvironment()
            // §1 forbids opaque failures. Until the setup screen lands there is no way for a
            // person to supply these on a device, so this says what is missing rather than
            // showing an empty album list — which would look like a working app whose library
            // happens to hold no photographs.
            ?: return ComposeUIViewController { Unconfigured() }

        val app = PhotosApp(
            storage = credentials.endpoint.asStorageUrl(),
            password = credentials.password,
            cacheRoot = PhotosApp.defaultCacheRoot(),
        )
        app.model.start()
        return ComposeUIViewController { app.Content() }
    }
}

/**
 * The same `PHOTOS_ENDPOINT` / `PHOTOS_PASSWORD` the CLI and the desktop app honour.
 *
 * **A simulator-only arrangement, and it does not survive the setup screen.** A TestFlight
 * build has no environment to read, which is exactly why §1's setup screen is the next piece of
 * work; this exists so bring-up can be driven from `Scripts/ios-sim.sh` without a credential
 * store and without a screen that does not exist yet. `simctl` passes a variable on only when
 * it is named `SIMCTL_CHILD_*`, which the script does.
 */
private class Credentials(val endpoint: String, val password: String) {
    companion object {
        @OptIn(ExperimentalForeignApi::class)
        fun fromEnvironment(): Credentials? {
            fun variable(name: String): String? =
                getenv(name)?.toKString()?.takeIf { it.isNotBlank() }
            val endpoint = variable("PHOTOS_ENDPOINT") ?: return null
            val password = variable("PHOTOS_PASSWORD") ?: return null
            return Credentials(endpoint, password)
        }
    }
}
