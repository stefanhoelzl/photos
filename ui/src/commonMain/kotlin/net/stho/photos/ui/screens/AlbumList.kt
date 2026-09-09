package net.stho.photos.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.remember
import net.stho.photos.catalog.Album
import net.stho.photos.ui.state.Thumbnails

/**
 * The album list, and the container one level down — the same list either way.
 *
 * §2's "an album has sub-albums XOR photos" is what lets one screen serve both: a row is a
 * count and a cover, and the thing it opens is decided by the row, not by this screen.
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
    onSearch: (String) -> Unit,
    onOpen: (Album) -> Unit,
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
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                items(albums, key = { it.id.toString() }) { album ->
                    AlbumRow(album, thumbnails, arrivals) { onOpen(album) }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                }
            }
        }
    }
}

@Composable
private fun AlbumRow(album: Album, thumbnails: Thumbnails, arrivals: Int, onOpen: () -> Unit) {
    // Read lazily, per visible row: resolving a cover opens that album's pack, and doing it for
    // all 288 up front would be 288 file reads for the six rows anyone can actually see.
    val cover = remember(album.id, arrivals) { thumbnails.cover(album) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A placeholder until the album's thumbnail pack lands. Nothing is ever disabled while
        // loading (§6): the row opens, and opening it promotes its pack to the head of the
        // queue -- which is phase 2's job, not this screen's.
        Box(
            Modifier.size(54.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            if (cover != null) Thumbnail(cover, Modifier.fillMaxSize())
        }
        Column(Modifier.padding(start = 12.dp).fillMaxWidth()) {
            Text(
                album.name,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                album.subtitle(),
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** "412 photos · 2024" — the count, then the years the album actually spans (§3). */
private fun Album.subtitle(): String {
    val years = listOfNotNull(dateMin?.year(), dateMax?.year()).distinct()
    val span = when (years.size) {
        0 -> null
        1 -> years.first().toString()
        else -> "${years.first()}–${years.last()}"
    }
    return listOfNotNull("$photoCount photos", span).joinToString(" · ")
}

private fun kotlin.time.Instant.year(): Int =
    toString().substringBefore('-').toIntOrNull() ?: 0

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
 * §10 requires the app to render an album with zero photos: emptying a directory leaves one.
 * It is a message, never an empty grid, so it cannot be mistaken for a pack that has not
 * arrived yet.
 */
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

@Composable
public fun EmptyState(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
