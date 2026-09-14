package net.stho.photos.ui.screens

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Whether the device may rotate away from portrait — which §6 allows on the viewer alone.
 *
 * A UI-interop port like [VideoSurface]: the screens know *when* rotation is allowed, only the
 * platform knows how to allow it. On iOS that is the scene's supported orientations; a desktop
 * window has no orientation, so the default does nothing.
 */
public fun interface OrientationPolicy {
    public fun allowLandscape(allowed: Boolean)
}

/** Provided by the composition root. The default ignores the request, as a desktop must. */
public val LocalOrientationPolicy: androidx.compose.runtime.ProvidableCompositionLocal<OrientationPolicy> =
    staticCompositionLocalOf { OrientationPolicy { } }
