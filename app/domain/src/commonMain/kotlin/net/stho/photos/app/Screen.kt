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
}

/**
 * The back stack.
 *
 * Immutable, so a snapshot of it *is* the app's location and a test can assert on one. [pop]
 * never empties the stack: the root is not something you can navigate away from.
 */
public data class BackStack(val screens: List<Screen> = listOf(Screen.Albums)) {
    public val current: Screen get() = screens.last()
    public val canGoBack: Boolean get() = screens.size > 1

    public fun push(screen: Screen): BackStack = BackStack(screens + screen)

    public fun pop(): BackStack =
        if (canGoBack) BackStack(screens.dropLast(1)) else this

    /** Swiping does not deepen the stack: the photo you are on replaces the one you were on. */
    public fun replace(screen: Screen): BackStack = BackStack(screens.dropLast(1) + screen)

    /** Back to the root, for the control server and for Settings' own dismissal. */
    public fun root(): BackStack = BackStack(listOf(Screen.Albums))
}
