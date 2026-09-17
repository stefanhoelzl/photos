package net.stho.photos.ui.screens

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.State
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import net.stho.photos.app.Scroll

/**
 * Where a remembered [Scroll] is among [keys] now: its key's current position, or — the item gone
 * since — the position it had, clamped, at the item's top. Nothing remembered is the top.
 */
internal fun Scroll?.target(keys: List<String>): Pair<Int, Int> {
    if (this == null || keys.isEmpty()) return 0 to 0
    val at = key?.let(keys::indexOf)?.takeIf { it >= 0 }
    return if (at != null) at to offset else index.coerceIn(0, keys.lastIndex) to 0
}

/** Scrolls to [scroll], then reports every place a scroll comes to rest until cancelled. */
internal suspend fun LazyListState.follow(scroll: Scroll?, keys: State<List<String>>, onScrolled: (String?, Int, Int) -> Unit) {
    val (index, offset) = scroll.target(keys.value)
    scrollToItem(index, offset)
    settled({ isScrollInProgress }, { firstVisibleItemIndex to firstVisibleItemScrollOffset }, keys, onScrolled)
}

/**
 * The grid's [follow], which also shows the photo [Scroll.reveal] names — the one the viewer was
 * left on. Restored first, so a photo already in view moves nothing.
 */
internal suspend fun LazyGridState.follow(scroll: Scroll?, keys: State<List<String>>, onScrolled: (String?, Int, Int) -> Unit) {
    val (index, offset) = scroll.target(keys.value)
    scrollToItem(index, offset)
    scroll?.reveal?.takeIf { it in keys.value.indices }?.let { reveal(it) }
    settled({ isScrollInProgress }, { firstVisibleItemIndex to firstVisibleItemScrollOffset }, keys, onScrolled)
}

/**
 * Brings the tile at [index] wholly into view by the shortest scroll: a row above the viewport
 * lands on its top edge, a row below on its bottom edge, and one already whole stays put.
 */
internal suspend fun LazyGridState.reveal(index: Int) {
    // Before the first measure there is nothing to look at: wait for the restored layout.
    snapshotFlow { layoutInfo }.first { it.visibleItemsInfo.isNotEmpty() }
    fun tile() = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
    val below = tile() == null && index > firstVisibleItemIndex
    if (tile() == null) scrollToItem(index)
    val tile = tile() ?: return
    val top = tile.offset.y
    val bottom = top + tile.size.height
    val start = layoutInfo.viewportStartOffset
    val end = layoutInfo.viewportEndOffset
    val delta = when {
        // Scrolled to, it sits on the top edge; its row belongs on the bottom one.
        below -> bottom - end
        top < start -> top - start
        bottom > end -> bottom - end
        else -> 0
    }
    if (delta != 0) scrollBy(delta.toFloat())
}

private suspend fun settled(
    moving: () -> Boolean,
    position: () -> Pair<Int, Int>,
    keys: State<List<String>>,
    onScrolled: (String?, Int, Int) -> Unit,
) {
    snapshotFlow { if (moving()) null else position() }
        .filterNotNull()
        .distinctUntilChanged()
        .collect { (index, offset) -> onScrolled(keys.value.getOrNull(index), index, offset) }
}
