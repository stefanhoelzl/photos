@file:OptIn(ExperimentalForeignApi::class)

package net.stho.photos.ios

import kotlinx.cinterop.ExperimentalForeignApi
import net.stho.photos.ui.screens.OrientationPolicy
import platform.Foundation.NSSelectorFromString
import platform.UIKit.UIApplication
import platform.UIKit.UIInterfaceOrientationMaskAllButUpsideDown
import platform.UIKit.UIInterfaceOrientationMaskPortrait
import platform.UIKit.UIWindowScene
import platform.UIKit.UIWindowSceneGeometryPreferencesIOS

/**
 * §6's orientation rule on iOS: portrait everywhere, landscape too while a photo is open.
 *
 * `Info.plist` lists every orientation the app may *ever* take; this narrows it at run time.
 * iOS asks the app delegate, which asks [supported] — the one thing Swift reads from here — and
 * is told to ask again whenever the answer changes. Leaving the viewer while sideways also
 * requests portrait, since narrowing the mask alone leaves a rotated screen where it is.
 *
 * Main thread only: Compose calls it from an effect, and UIKit is the main thread's.
 */
public object IosOrientation : OrientationPolicy {
    private var landscape = false

    /** A `UIInterfaceOrientationMask`, for the app delegate. */
    public val supported: ULong
        get() = if (landscape) UIInterfaceOrientationMaskAllButUpsideDown else UIInterfaceOrientationMaskPortrait

    override fun allowLandscape(allowed: Boolean) {
        if (landscape == allowed) return
        landscape = allowed
        val scene = UIApplication.sharedApplication.connectedScenes
            .firstOrNull { it is UIWindowScene } as? UIWindowScene ?: return
        // By selector: Kotlin/Native does not import this method under its own name -- a
        // zero-argument `set…` reads to the importer as half of a property it never finds.
        scene.keyWindow?.rootViewController
            ?.performSelector(NSSelectorFromString("setNeedsUpdateOfSupportedInterfaceOrientations"))
        scene.requestGeometryUpdateWithPreferences(
            UIWindowSceneGeometryPreferencesIOS(interfaceOrientations = supported),
            errorHandler = null,
        )
    }
}
