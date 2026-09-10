package net.stho.photos.ui.screens

import androidx.compose.material.icons.Icons as MaterialIcons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Delete
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The glyphs §6's chrome names, in one place.
 *
 * E.1 has two bar icons and no more: gear and sort. F adds the map toggle and G adds upload,
 * each with its own milestone behind it — nothing on screen is ever dead.
 */
internal object Icons {
    val back: ImageVector = MaterialIcons.AutoMirrored.Filled.ArrowBack
    val gear: ImageVector = MaterialIcons.Filled.Settings
    val sort: ImageVector = MaterialIcons.Filled.SwapVert
    val search: ImageVector = MaterialIcons.Filled.Search

    /** E.2's cache controls, and the mark that stands in for a photograph not yet fetched. */
    val download: ImageVector = MaterialIcons.Filled.Download
    val pause: ImageVector = MaterialIcons.Filled.Pause
    val trash: ImageVector = MaterialIcons.Filled.Delete
    val image: ImageVector = MaterialIcons.Filled.Image
}
