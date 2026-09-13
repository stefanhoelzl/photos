package net.stho.photos.ios

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.window.ComposeUIViewController
import net.stho.photos.adapter.ios.KeychainKeyring
import net.stho.photos.app.Account
import net.stho.photos.app.Launcher
import net.stho.photos.ui.screens.LocalLivePhotoSurface
import net.stho.photos.ui.screens.LocalVideoSurface
import net.stho.photos.ui.screens.Photos
import net.stho.photos.ui.screens.PhotosTheme
import platform.UIKit.UIViewController

/**
 * The whole of the release framework's Swift-facing surface: one object, one function.
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
    public fun viewController(): UIViewController = PhotosRoot().viewController()
}

/**
 * The iOS composition root as a value: the launcher, and the composition that shows it.
 *
 * A class rather than the body of [PhotosEntry] because two entry points build it. The release
 * one presents it and nothing else; the Debug framework (`:app:ios-debug`) also hands [launcher]
 * to the control server, which drives setup and the model exactly as a tap would. Kotlin-only:
 * nothing here is exported to Swift.
 *
 * §1's flow, and the phone has only one way in: the setup screen, or the Keychain. No
 * environment is consulted for credentials — a TestFlight build has none to read.
 */
public class PhotosRoot {
    private val cacheRoot = PhotosApp.defaultCacheRoot()

    public val launcher: Launcher = Launcher(Account(KeychainKeyring())) { storage, password ->
        PhotosApp(storage = storage, password = password, cacheRoot = cacheRoot)
    }

    public fun viewController(): UIViewController {
        launcher.start()
        return ComposeUIViewController {
            PhotosTheme {
                // The two things on the viewer no shared code can draw (§6's interop table).
                CompositionLocalProvider(
                    LocalVideoSurface provides AvVideoSurface(),
                    LocalLivePhotoSurface provides PhLivePhotoSurface(),
                ) {
                    Photos(launcher)
                }
            }
        }
    }
}
