package net.stho.photos.ui.screens

import androidx.compose.material.icons.Icons as MaterialIcons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Upload
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The glyphs §6's chrome names, in one place.
 *
 * Gear, sort, and F's representation toggle, which always shows the view you switch *to*: map
 * on a list or a grid, list or grid on a map. G adds upload, with its own milestone behind it —
 * nothing on screen is ever dead.
 */
internal object Icons {
    val back: ImageVector = MaterialIcons.AutoMirrored.Filled.ArrowBack
    val gear: ImageVector = MaterialIcons.Filled.Settings
    val sort: ImageVector = MaterialIcons.Filled.SwapVert
    val search: ImageVector = MaterialIcons.Filled.Search

    /** F's representation toggle. */
    val map: ImageVector = MaterialIcons.Filled.Map
    val list: ImageVector = MaterialIcons.AutoMirrored.Filled.List
    val grid: ImageVector = MaterialIcons.Filled.GridView

    /** E.2's cache controls, and the mark that stands in for a photograph not yet fetched. */
    val download: ImageVector = MaterialIcons.Filled.Download
    val pause: ImageVector = MaterialIcons.Filled.Pause
    val trash: ImageVector = MaterialIcons.Filled.Delete
    val image: ImageVector = MaterialIcons.Filled.Image

    /** G's: the bar icon, the picker's tick and disclosure, and the sheet's expand and collapse. */
    val upload: ImageVector = MaterialIcons.Filled.Upload
    val check: ImageVector = MaterialIcons.Filled.Check
    val chevron: ImageVector = MaterialIcons.Filled.ChevronRight
    val expand: ImageVector = MaterialIcons.Filled.KeyboardArrowUp
    val collapse: ImageVector = MaterialIcons.Filled.KeyboardArrowDown
}
