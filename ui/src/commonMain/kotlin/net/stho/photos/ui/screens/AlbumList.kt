package net.stho.photos.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.stho.photos.catalog.Album
import net.stho.photos.app.AlbumCache
import net.stho.photos.app.CacheAction
import net.stho.photos.app.Thumbnails

/**
 * The album list, and the container one level down — the same list either way.
 *
 * §2's "an album has sub-albums XOR photos" is what lets one screen serve both: a row is a
 * count and a cover, and the thing it opens is decided by the row, not by this screen.
 *
 * It is also the *only* list of albums in the app. The cache controls live here rather than on
 * a second list inside Settings, so nothing has to keep two renderings of the same 288 albums
 * consistent, the hierarchy comes free, and asking for an album happens where you are already
 * looking at it.
 */
@Composable
public fun AlbumList(
    albums: List<Album>,
    query: String,
    searchable: Boolean,
    thumbnails: Thumbnails,
    /** Bumped when a pack lands, so a row already on screen swaps placeholder for photograph. */
    arrivals: Int,
    /** A first sync, with nothing to show yet: `fetched to total`, or null when not loading. */
    loading: Pair<Int, Int>?,
    /** This album's cache state — the whole of what its strip draws. */
    cache: (Album) -> AlbumCache,
    /** Which controls the album's state affords, revealed by a swipe or by tapping the strip. */
    actions: (Album) -> List<CacheAction>,
    onSearch: (String) -> Unit,
    onOpen: (Album) -> Unit,
    onAction: (Album, CacheAction) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        if (searchable) SearchField(query, onSearch)
        if (albums.isEmpty() && loading != null) {
            // A first sync fetches every shard in the zone and takes tens of seconds (§4).
            // "No albums yet" during it is simply untrue, and untrue in the worst way: it
            // looks like an empty library rather than like work in progress.
            LoadingState(loading)
        } else if (albums.isEmpty()) {
            EmptyState(if (query.isBlank()) "No albums yet" else "Nothing matches “$query”")
        } else {
            // No side padding on the list itself: the strip is a screen-edge mark and has to
            // reach the edge. The row's content carries the inset instead.
            LazyColumn(Modifier.fillMaxSize()) {
                items(albums, key = { it.id.toString() }) { album ->
                    AlbumRow(
                        album = album,
                        thumbnails = thumbnails,
                        arrivals = arrivals,
                        cache = cache(album),
                        actions = actions(album),
                        onAction = { onAction(album, it) },
                        onOpen = { onOpen(album) },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                }
            }
        }
    }
}

/**
 * One album: a cover, what it contains, and one strip that says everything about its cache.
 *
 * The row does not narrate itself. There is no size, no state and no byte count in the text,
 * because the strip carries all four readings — grey nothing held, part blue this much held,
 * part blue pulsing and moving now, full green the whole album is offline. That replaced a
 * separate gauge plus a line of prose on every row.
 */
@Composable
private fun AlbumRow(
    album: Album,
    thumbnails: Thumbnails,
    arrivals: Int,
    cache: AlbumCache,
    actions: List<CacheAction>,
    onAction: (CacheAction) -> Unit,
    onOpen: () -> Unit,
) {
    // Read lazily, per visible row: resolving a cover opens that album's pack, and doing it for
    // all 288 up front would be 288 file reads for the six rows anyone can actually see.
    val cover = remember(album.id, arrivals) { thumbnails.cover(album) }
    var revealed by remember(album.id) { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f)
                .clickable(onClick = onOpen)
                .padding(start = 16.dp, top = 7.dp, bottom = 7.dp)
                // Swipe-left reveals the actions. Swipe-RIGHT is deliberately unused: it
                // collides with the interactive back gesture, which matters at every level of
                // this list rather than only at the root.
                .pointerInput(album.id, actions) {
                    detectHorizontalDragGestures { _, delta ->
                        if (delta < -4f && actions.isNotEmpty()) revealed = true
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AlbumCover(cover, moving = cache.moving)
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(
                    album.name,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    album.contents(),
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (revealed) {
            // Icons only, no labels: which ones appear is the album's state, so each row offers
            // exactly what applies and there is nothing to read.
            actions.forEach { action ->
                RevealedAction(action) {
                    onAction(action)
                    revealed = false
                }
            }
        } else {
            CacheStrip(
                cache,
                // The strip is also the tap route to the actions, so its hit area is grown to
                // the 44dp minimum without growing the 4dp mark. Being visible is what makes
                // this discoverable where the bare gesture is not: people tap what they see.
                Modifier.clickable(enabled = actions.isNotEmpty()) { revealed = true },
            )
        }
    }
}

/**
 * The cover, or a photograph-shaped mark standing in for one that has not arrived.
 *
 * A glyph rather than an abstract texture, so the tile says what it is going to be — and it
 * pulses only while a worker is on this album, which is the same rule the strip uses.
 */
@Composable
private fun AlbumCover(cover: ByteArray?, moving: Boolean) {
    Box(
        Modifier.size(54.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (cover != null) {
            Thumbnail(cover, Modifier.fillMaxSize())
        } else {
            Icon(
                Icons.image,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(24.dp).alpha(pulseAlpha(moving)),
            )
        }
    }
}

/**
 * The one mark that carries the whole cache vocabulary.
 *
 * Full green when every blob is on disk; otherwise a grey track filled from the bottom in
 * proportion to the **bytes** held, not the count — an album whose one video is missing is not
 * nearly done, and a count would say it was. It pulses only while a worker is actually on this
 * album, which is what keeps a pulsing row worth looking at in a list of 288.
 */
@Composable
private fun CacheStrip(cache: AlbumCache, modifier: Modifier = Modifier) {
    // The hit area is 44dp so the actions have a real touch target; the mark itself is 7dp and
    // sits at the far end of it, flush with the screen edge and running the full height of the
    // row, because that is where a status mark for the whole row belongs.
    Box(modifier.width(44.dp).fillMaxHeight(), contentAlignment = Alignment.CenterEnd) {
        Box(
            Modifier.width(7.dp).fillMaxHeight()
                // `alpha` BEFORE `background`, not after: modifiers wrap left-to-right, so a
                // background declared first is drawn outside the alpha layer and the fade never
                // touches it. That is why this looked static however the transition behaved.
                .alpha(pulseAlpha(cache.moving))
                .background(
                    if (cache.complete) MaterialTheme.colorScheme.start
                    else MaterialTheme.colorScheme.outlineVariant,
                ),
            contentAlignment = Alignment.BottomCenter,
        ) {
            // The whole mark pulses, not just the filled part: a download that has not landed
            // its first blob is 0% filled, and pulsing only the fill meant the one moment you
            // most want to see movement showed none at all.
            if (!cache.complete && cache.fraction > 0f) {
                Box(
                    Modifier.fillMaxWidth()
                        .fillMaxHeight(cache.fraction)
                        .background(MaterialTheme.colorScheme.active),
                )
            }
        }
    }
}

@Composable
private fun RevealedAction(action: CacheAction, onClick: () -> Unit) {
    val tint = when (action) {
        CacheAction.Download -> MaterialTheme.colorScheme.start
        CacheAction.Pause -> MaterialTheme.colorScheme.active
        CacheAction.Clear -> MaterialTheme.colorScheme.error
    }
    val icon = when (action) {
        CacheAction.Download -> Icons.download
        CacheAction.Pause -> Icons.pause
        CacheAction.Clear -> Icons.trash
    }
    Box(
        Modifier.width(52.dp).fillMaxHeight().background(tint).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = action.name, tint = Color.White, modifier = Modifier.size(20.dp))
    }
}

/**
 * Motion means one thing everywhere in the app: bytes are moving for this item right now.
 *
 * The transition is created **unconditionally** and only its value is gated. An earlier version
 * returned early when nothing was moving, which meant `rememberInfiniteTransition` was called in
 * some compositions and not others — a conditional composable call, which breaks positional
 * memoization, and the animation simply never ran.
 */
@Composable
internal fun pulseAlpha(moving: Boolean): Float {
    val transition = rememberInfiniteTransition(label = "moving")
    val fade by transition.animateFloat(
        initialValue = 1f,
        targetValue = PULSE_FLOOR,
        // Eased rather than linear, and reversed: a linear ramp turns hard at both ends and
        // reads as a flicker, while easing in and out makes it breathe. The floor is high
        // enough that the mark never looks like it is disappearing.
        animationSpec = infiniteRepeatable(
            tween(PULSE_MS, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "alpha",
    )
    return if (moving) fade else 1f
}

private const val PULSE_MS = 620
private const val PULSE_FLOOR = 0.42f

/**
 * What the album *contains*, and nothing else.
 *
 * No size, no cache state, no year: everything about the cache is in the strip, which is what
 * let this line go back to naming contents. The mockup also shows a video count; the merged DB
 * has no per-album video column, so adding one is a schema change and this says photos only.
 */
private fun Album.contents(): String = "$photoCount photos"

@Composable
private fun SearchField(query: String, onSearch: (String) -> Unit) {
    TextField(
        value = query,
        onValueChange = onSearch,
        singleLine = true,
        placeholder = { Text("Search albums", fontSize = 13.sp) },
        leadingIcon = { Icon(Icons.search, contentDescription = null, Modifier.size(16.dp)) },
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .height(48.dp).clip(RoundedCornerShape(10.dp)),
    )
}

/**
 * The cold start §4 describes: shards first, then the catalog is built.
 *
 * Determinate wherever it can be — the sync knows how many shards it is fetching — and
 * indeterminate for the moment before the LIST comes back, because a bar sitting at zero says
 * less than a bar that is moving.
 */
@Composable
private fun LoadingState(progress: Pair<Int, Int>) {
    val (fetched, total) = progress
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (total > 0) {
            LinearProgressIndicator(
                progress = { fetched.toFloat() / total },
                modifier = Modifier.fillMaxWidth(0.6f),
            )
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth(0.6f))
        }
        Text(
            if (total > 0) "Fetching the catalog · $fetched of $total albums" else "Reading the zone…",
            fontSize = 12.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

/**
 * §10 requires the app to render an album with zero photos: emptying a directory leaves one.
 * It is a message, never an empty grid, so it cannot be mistaken for a pack that has not
 * arrived yet.
 */
@Composable
public fun EmptyState(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
