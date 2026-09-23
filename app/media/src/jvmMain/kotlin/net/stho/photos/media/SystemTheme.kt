package net.stho.photos.media

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import net.stho.photos.adapter.linux.Appearance
import net.stho.photos.ui.screens.PhotosTheme

/**
 * The app's theme in the desktop's scheme, and switching with it while the window is open.
 *
 * `PhotosTheme`'s own default asks `isSystemInDarkTheme()`, which on Linux sees nothing and
 * answers light whatever the desktop is set to. The portal's answer is used instead, and
 * Compose's only where the portal has none.
 */
@Composable
public fun SystemTheme(appearance: Appearance, content: @Composable () -> Unit) {
    val fallback = isSystemInDarkTheme()
    var dark by remember(appearance) { mutableStateOf(appearance.dark) }
    DisposableEffect(appearance) {
        appearance.onChange { dark = it }
        onDispose { }
    }
    PhotosTheme(dark = dark ?: fallback, content = content)
}

/**
 * Sizes the window's content for the desktop's scale — before AWT starts, which is the only time
 * the JVM reads it. A scale given on the command line wins.
 */
public fun applyDisplayScale() {
    if (System.getProperty("sun.java2d.uiScale") != null) return
    Appearance.displayScale()?.let { System.setProperty("sun.java2d.uiScale", it.toString()) }
}
