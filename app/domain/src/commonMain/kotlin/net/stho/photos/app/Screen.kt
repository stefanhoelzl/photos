package net.stho.photos.app

import kotlin.uuid.Uuid

/**
 * Where you are (§6).
 *
 * A sealed type and a list, not a navigation library: the hierarchy is fixed and four levels
 * deep, there are no deep links, and this doubles as the jump-to-state the control server
 * needs — `POST /nav` pushes one of these, and the app is then in exactly that state.
 */
public sealed interface Screen {
    /** The root. There is no tab bar; everything else is reached from this screen's nav bar. */
    public data object Albums : Screen

    /** A container: sub-albums, same row layout as the root (§2's XOR rule makes one list do). */
    public data class Container(val albumId: Uuid, val name: String) : Screen

    /** An album's photos. */
    public data class Grid(val albumId: Uuid, val name: String) : Screen

    /** One photo, fullscreen. [index] is a position in the album's own order (§3). */
    public data class Photo(val albumId: Uuid, val name: String, val index: Int) : Screen

    public data object Settings : Screen

    /**
     * §8's gallery picker. [parent] is where the new album goes: the list it was opened from, the
     * root when null. Never an album of photos, which cannot hold a sub-album (§2).
     *
     * Opened from an album of photos instead, [addTo] is that album: the photos go into it rather
     * than into a new one, [parentName] is its name, and [parent] its parent.
     */
    public data class Upload(val parent: Uuid?, val parentName: String, val addTo: Uuid? = null) : Screen
}

/**
 * The back stack.
 *
 * Immutable, so a snapshot of it *is* the app's location and a test can assert on one. [pop]
 * never empties the stack: the root is not something you can navigate away from.
 */
public data class BackStack(
    val screens: List<Screen> = listOf(Screen.Albums),
    /**
     * Each level's map, keyed by its position in [screens].
     *
     * The map is a representation of a screen rather than a screen of its own (§6), so it is not
     * pushed: it rides on the level it draws. That is what lets the camera survive opening an
     * album and coming back, and toggling to the list and back — and why leaving the level, which
     * drops the entry, frames the map afresh next time.
     */
    val maps: Map<Int, MapView> = emptyMap(),
    /**
     * Where each list or grid was scrolled to, keyed by its position in [screens].
     *
     * The same bargain as [maps]: the composable that scrolled is gone the moment a deeper screen
     * replaces it, so where it stood rides on the level instead — and leaving the level drops it.
     */
    val scrolls: Map<Int, Scroll> = emptyMap(),
) {
    public val current: Screen get() = screens.last()
    public val canGoBack: Boolean get() = screens.size > 1

    /** The current level's map, or null when it has never been shown. */
    public val map: MapView? get() = maps[screens.lastIndex]

    public fun push(screen: Screen): BackStack = copy(screens = screens + screen)

    /** The current level's scroll, or null when it has never reported one. */
    public val scroll: Scroll? get() = scrolls[screens.lastIndex]

    /**
     * Back from the viewer, the grid is asked to show the photo last looked at: a swipe may have
     * carried it well past the tiles that were on screen when the photo was opened.
     */
    public fun pop(): BackStack {
        if (!canGoBack) return this
        val left = current
        val popped = BackStack(screens.dropLast(1), maps - screens.lastIndex, scrolls - screens.lastIndex)
        if (left !is Screen.Photo || popped.current !is Screen.Grid) return popped
        return popped.withScroll((popped.scroll ?: Scroll()).copy(reveal = left.index))
    }

    /** Swiping does not deepen the stack: the photo you are on replaces the one you were on. */
    public fun replace(screen: Screen): BackStack = copy(screens = screens.dropLast(1) + screen)

    /** Back to the root, for the control server and for Settings' own dismissal. */
    public fun root(): BackStack = BackStack()

    public fun withMap(view: MapView): BackStack = copy(maps = maps + (screens.lastIndex to view))

    public fun withScroll(scroll: Scroll): BackStack = copy(scrolls = scrolls + (screens.lastIndex to scroll))

    /**
     * Forgets where the album lists stood — every level of them, since the sort is shared — for a
     * new order, in which a remembered row is somewhere else entirely. The grid keeps its own:
     * an album's photos have one order (§3), whatever the sort says.
     */
    public fun withoutListScrolls(): BackStack =
        copy(scrolls = scrolls.filterKeys { level -> screens[level] is Screen.Grid })

    /** Forgets the album list's own, for a search or a date range, which narrow no other list. */
    public fun withoutRootScroll(): BackStack = copy(scrolls = scrolls - 0)
}

/**
 * Where a list or grid stood: the first item on screen, by its key, and how far it was scrolled past.
 *
 * By key rather than by position, because the rows can change while the level is out of sight — a
 * sync rebuilds the album list — and a position would then point at whichever row moved into it.
 * [index] is where the key was, for when it is gone: the list comes back about where it was.
 */
public data class Scroll(
    /** The first visible item's key; null for the top. */
    val key: String? = null,
    val index: Int = 0,
    /** Pixels the first visible item is scrolled past the top edge. */
    val offset: Int = 0,
    /** A photo index the grid must show whole, once, after it restores [key]: see [BackStack.pop]. */
    val reveal: Int? = null,
    /**
     * Bumped each time the model moves the list — the control server — which the screen then
     * scrolls to. A scroll the screen reports leaves it alone, since the list is already there.
     */
    val moves: Int = 0,
)
