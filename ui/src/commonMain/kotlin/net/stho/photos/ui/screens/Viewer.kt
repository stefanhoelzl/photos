package net.stho.photos.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.uuid.Uuid
import net.stho.photos.model.MediaType
import net.stho.photos.model.PhotoRow
import net.stho.photos.app.LivePair
import net.stho.photos.app.Preview

/**
 * §6's fullscreen viewer: one screen, drawn the same for a still, a video and a Live Photo.
 *
 * Chrome is back and gear only. **Set-as-cover moved to G** — it rewrites the album's shard
 * with `If-Match`, and E is meant to be read-only — and **share is not built yet**, being
 * `UIActivityViewController` interop only a device can exercise. Motion is the platform's own:
 * each root supplies the surfaces a video and a Live Photo render into, and the harness's
 * leave the still in place.
 *
 * **Swiping pages through the album.** The open photo is the model's — its preview, its transcode,
 * its Live Photo pair. The model also decodes the photo either side, so a neighbour being dragged
 * in draws its preview, and only one not yet decoded shows the placeholder. The model is told once
 * the pager *settles*. A filmstrip tap moves the pager the other way.
 *
 * **Pinch or double-tap to zoom a still**, up to 4×. At 1× a one-finger drag pages; zoomed in it
 * pans, and the pager stops listening until the photo is back at 1×.
 *
 * **Landscape is the photograph alone:** no filmstrip, no date, and no nav bar above it (the root
 * drops that), so it carries a back button of its own. Only this screen may rotate.
 *
 * The ground is the app surface, not black: iOS Photos does it that way, and a black viewer
 * inside an otherwise light app reads as a bug.
 */
@Composable
public fun Viewer(
    photos: List<PhotoRow>,
    index: Int,
    preview: Preview?,
    videoPath: String?,
    /** Both halves of an open Live Photo, once on disk. */
    livePair: LivePair?,
    thumbnails: Map<Uuid, ByteArray>,
    /** A worker is fetching this photo's blob right now, so the placeholder pulses. */
    moving: Boolean,
    /** The decoded previews either side of the open photo, keyed by `PhotoRow.id`. */
    nearby: Map<Uuid, Preview>,
    landscape: Boolean,
    onBack: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    val photo = photos.getOrNull(index) ?: return
    val pager = rememberPagerState(initialPage = index) { photos.size }
    val currentIndex by rememberUpdatedState(index)
    val select by rememberUpdatedState(onSelect)

    // The model's index moved (a filmstrip tap, or `/nav`): follow it without animating, since the
    // photo is already on its way.
    LaunchedEffect(index) {
        if (pager.currentPage != index) pager.scrollToPage(index)
    }
    // A swipe that came to rest on another photo is the model's to know about.
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { page ->
            if (page != currentIndex) select(page)
        }
    }

    val chrome = if (landscape) 0.dp else FILMSTRIP_BAR
    // Zoomed in, a one-finger drag pans the photograph instead of paging away from it.
    var zoomed by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize().padding(bottom = chrome),
            userScrollEnabled = !zoomed,
            key = { photos[it].id.toString() },
        ) { page ->
            if (page == index) {
                // The neighbour's decode as well as the model's preview: for the frames between the
                // pager settling and the model catching up, the model's preview is still null.
                val open = preview ?: nearby[photo.id]
                if (photo.mediaType == MediaType.PHOTO) {
                    Zoomable(photo.id, onZoomed = { zoomed = it }) {
                        OpenPhoto(photo, open, videoPath, livePair, moving)
                    }
                } else {
                    // A video and a Live Photo play in the platform's own views, which a Compose
                    // layer cannot scale, so zooming is for stills.
                    OpenPhoto(photo, open, videoPath, livePair, moving)
                }
            } else {
                val neighbour = nearby[photos[page].id]
                if (neighbour != null) {
                    Image(neighbour.image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                } else {
                    Placeholder(moving = false)
                }
            }
        }

        Row(
            Modifier.align(Alignment.TopStart).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (landscape) BarButton(Icons.back, "Back", onBack)
            // A video's mark stands on its poster only: once the player is up, its own controls
            // take this corner and it plainly is a video.
            if (photo.mediaType != MediaType.VIDEO || videoPath == null) ViewerMark(photo.mediaType)
        }

        if (!landscape) {
            Column(Modifier.align(Alignment.BottomStart)) {
                Filmstrip(photos, index, thumbnails, onSelect)
                Text(
                    photo.takenAt?.toString()?.substringBefore('T') ?: "undated",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }
}

/** The filmstrip's thumbnails, its padding and the date line beneath: what the photo sits above. */
private val FILMSTRIP_BAR = 112.dp

/** The open photo: its preview, then the platform's player or Live Photo view over it. */
@Composable
private fun OpenPhoto(photo: PhotoRow, preview: Preview?, videoPath: String?, livePair: LivePair?, moving: Boolean) {
    Box(Modifier.fillMaxSize()) {
        // `contain`, not `cover`: this is the one surface in the whole app that fits rather
        // than fills, because it is the only one showing the whole photograph (§5).
        // The poster first, then the player over it once the transcode has arrived: §5's
        // preview *is* a video's poster frame, so there is never a blank rectangle.
        if (preview != null) {
            Image(preview.image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        } else {
            Placeholder(moving)
        }
        if (photo.mediaType == MediaType.VIDEO && videoPath != null) {
            LocalVideoSurface.current.Render(videoPath, Modifier.fillMaxSize())
        }
        if (photo.mediaType == MediaType.LIVE_PHOTO && livePair != null) {
            LocalLivePhotoSurface.current.Render(livePair.still, livePair.video, Modifier.fillMaxSize())
        }
    }
}

/**
 * The same photograph-shaped mark the album list and the grid use, on the surface the wait is
 * longest on. It pulses only while bytes are actually moving, so a still glyph means the fetch is
 * queued rather than stalled.
 */
@Composable
private fun Placeholder(moving: Boolean) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Icon(
            Icons.image,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.size(72.dp).alpha(pulseAlpha(moving)),
        )
    }
}

/**
 * The strip of the album, current photo outlined and kept in view. Thumbnails, so it costs no
 * network. 48dp tiles: big enough to tell photos apart and to hit with a thumb.
 */
@Composable
private fun Filmstrip(
    photos: List<PhotoRow>,
    index: Int,
    thumbnails: Map<Uuid, ByteArray>,
    onSelect: (Int) -> Unit,
) {
    val strip = rememberLazyListState(initialFirstVisibleItemIndex = (index - 3).coerceAtLeast(0))
    LaunchedEffect(index) {
        val visible = strip.layoutInfo.visibleItemsInfo
        if (visible.none { it.index == index && it.offset >= 0 && it.offset + it.size <= strip.layoutInfo.viewportEndOffset }) {
            strip.animateScrollToItem((index - 3).coerceAtLeast(0))
        }
    }
    LazyRow(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        state = strip,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        itemsIndexed(photos, key = { _, photo -> photo.id.toString() }) { position, photo ->
            val shape = RoundedCornerShape(4.dp)
            Box(
                Modifier.size(48.dp)
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .then(if (position == index) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
                    .clickable { onSelect(position) },
            ) {
                thumbnails[photo.id]?.let { Thumbnail(it, Modifier.fillMaxSize()) }
            }
        }
    }
}

/**
 * Pinch and double-tap zoom over the open photo, sharing its gestures with the pager.
 *
 * Hand-rolled rather than `transformable`, which claims every drag: a one-finger drag at 1× is
 * left unconsumed so the pager pages. Two fingers — or any drag once zoomed — are this one's, and
 * consuming them is what keeps the pager still. Pan is clamped so the photo never leaves an edge
 * of the screen uncovered. State is keyed by photo, so a swipe always lands at 1×.
 */
@Composable
private fun Zoomable(key: Any, onZoomed: (Boolean) -> Unit, content: @Composable () -> Unit) {
    var scale by remember(key) { mutableFloatStateOf(1f) }
    var offset by remember(key) { mutableStateOf(Offset.Zero) }
    val report by rememberUpdatedState(onZoomed)
    LaunchedEffect(key, scale > 1f) { report(scale > 1f) }
    DisposableEffect(key) { onDispose { report(false) } }

    Box(
        Modifier.fillMaxSize()
            .clipToBounds()
            .pointerInput(key) {
                detectTapGestures(onDoubleTap = { tap ->
                    if (scale > 1f) {
                        scale = 1f
                        offset = Offset.Zero
                    } else {
                        // Zoom into the point tapped: keep it under the finger.
                        scale = DOUBLE_TAP_ZOOM
                        val centre = Offset(size.width / 2f, size.height / 2f)
                        offset = clampPan((centre - tap) * (DOUBLE_TAP_ZOOM - 1f), size.width, size.height, DOUBLE_TAP_ZOOM)
                    }
                })
            }
            .pointerInput(key) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        if (fingers > 1 || scale > 1f) {
                            val next = (scale * event.calculateZoom()).coerceIn(1f, MAX_ZOOM)
                            offset = if (next == 1f) Offset.Zero
                            else clampPan(offset + event.calculatePan(), size.width, size.height, next)
                            scale = next
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
    ) { content() }
}

/** How far a photo scaled by [scale] can move before an edge of it comes into view. */
private fun clampPan(pan: Offset, width: Int, height: Int, scale: Float): Offset {
    val maxX = width * (scale - 1f) / 2f
    val maxY = height * (scale - 1f) / 2f
    return Offset(pan.x.coerceIn(-maxX, maxX), pan.y.coerceIn(-maxY, maxY))
}

/** §5's 3200px cap is ~2.4× on a 3× iPhone before pixels soften; 4× lets a detail be inspected. */
private const val MAX_ZOOM = 4f
private const val DOUBLE_TAP_ZOOM = 2.5f
