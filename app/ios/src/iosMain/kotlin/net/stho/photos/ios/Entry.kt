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

    /**
     * §1's flow, and the phone has only one way in: the setup screen, or the Keychain.
     *
     * No environment is consulted, unlike the desktop root — a TestFlight build has none to
     * read, so offering the override would be a branch that exists only to be dead.
     */
    public fun viewController(): UIViewController {
        val cacheRoot = PhotosApp.defaultCacheRoot()
        val launcher = Launcher(Account(KeychainKeyring())) { storage, password ->
            PhotosApp(storage = storage, password = password, cacheRoot = cacheRoot)
        }
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
