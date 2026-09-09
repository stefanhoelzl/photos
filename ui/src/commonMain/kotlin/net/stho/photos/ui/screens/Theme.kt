package net.stho.photos.ui.screens

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * §6's design system: Material 3, skinned, with **both** schemes pinned in full.
 *
 * Every container and outline token is supplied, so no component can fall back to Material's
 * stock baseline palette and introduce a hue this design never chose. The app follows the
 * system theme and offers no appearance setting — the OS already owns that preference.
 *
 * Colour carries meaning and is never decoration (§6). Bar glyphs are on-surface; the four
 * colours that mean something are error (destructive), gold (this photo *is* the cover),
 * the accent (progress and the one tappable word in a toast), and the dimming scrim.
 */
private val Gold = Color(0xFFFFD60A)
private val GoldLight = Color(0xFF8A6D00)

private val Dark = darkColorScheme(
    primary = Color(0xFF0A84FF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF16324F),
    onPrimaryContainer = Color(0xFFD3E4FF),
    secondary = Color(0xFF8B96A5),
    onSecondary = Color(0xFF0B0D10),
    secondaryContainer = Color(0xFF222933),
    onSecondaryContainer = Color(0xFFE9EDF2),
    tertiary = Gold,
    onTertiary = Color(0xFF201A00),
    background = Color(0xFF000000),
    onBackground = Color(0xFFE9EDF2),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFE9EDF2),
    surfaceVariant = Color(0xFF1C1C1E),
    onSurfaceVariant = Color(0xFF8B96A5),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF101216),
    surfaceContainer = Color(0xFF16191E),
    surfaceContainerHigh = Color(0xFF1C2026),
    surfaceContainerHighest = Color(0xFF22262C),
    inverseSurface = Color(0xFF2B3038),
    inverseOnSurface = Color(0xFFE9EDF2),
    outline = Color(0xFF3C444E),
    outlineVariant = Color(0xFF22262C),
    error = Color(0xFFFF453A),
    onError = Color(0xFF2A0F0C),
    errorContainer = Color(0xFF3A1614),
    onErrorContainer = Color(0xFFFFC9C6),
    scrim = Color(0xFF000000),
)

private val Light = lightColorScheme(
    primary = Color(0xFF0A63D6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7E6FF),
    onPrimaryContainer = Color(0xFF00274D),
    secondary = Color(0xFF5F6875),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3E6EA),
    onSecondaryContainer = Color(0xFF14171C),
    tertiary = GoldLight,
    onTertiary = Color.White,
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF14171C),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF14171C),
    surfaceVariant = Color(0xFFF1F2F5),
    onSurfaceVariant = Color(0xFF5F6875),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAFBFC),
    surfaceContainer = Color(0xFFF1F2F5),
    surfaceContainerHigh = Color(0xFFE9ECF0),
    surfaceContainerHighest = Color(0xFFE3E6EA),
    inverseSurface = Color(0xFF2B3038),
    inverseOnSurface = Color(0xFFF1F2F5),
    outline = Color(0xFF9AA3AF),
    outlineVariant = Color(0xFFE3E6EA),
    error = Color(0xFFC62D22),
    onError = Color.White,
    errorContainer = Color(0xFFFFE0DD),
    onErrorContainer = Color(0xFF5A140F),
    scrim = Color(0xFF000000),
)

/** The gold that means "this photo is the album's cover" — the one active-state colour (§6). */
public val ColorScheme.cover: Color get() = tertiary

@Composable
public fun PhotosTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}
