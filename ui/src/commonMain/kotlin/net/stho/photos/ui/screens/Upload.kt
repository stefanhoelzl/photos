package net.stho.photos.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import kotlin.uuid.Uuid
import net.stho.photos.app.UploadTarget
import net.stho.photos.app.Resolution
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.stho.photos.app.GalleryAccess
import net.stho.photos.app.GalleryAlbum
import net.stho.photos.app.GalleryAsset
import net.stho.photos.app.Naming
import net.stho.photos.app.PickerUi
import net.stho.photos.app.UploadModel
import net.stho.photos.app.UploadStage
import net.stho.photos.app.UploadStatus

/**
 * §8's gallery picker and album dialog: a gallery album whole, or loose photos.
 *
 * A pushed screen for choosing, and a dialog for where they go — one field that picks an album or
 * names a new one, pre-filled from where the upload started — and deleting from the device is
 * decided here, up front, rather than asked once the upload is done.
 */
@Composable
internal fun UploadScreen(uploads: UploadModel, onClose: () -> Unit) {
    val picker by uploads.picker.collectAsState()
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        NavBar("Choose photos", if (picker.selected.isEmpty()) "" else "${picker.selected.size} selected", onBack = { uploads.close(); onClose() }) {}
        val failure = picker.failure
        val access = picker.access
        when {
            failure != null -> EmptyState(failure)
            access == null -> EmptyState("Opening the photo library…")
            access == GalleryAccess.Full -> GalleryPicker(
                picker,
                onAlbum = { album -> scope.launch { uploads.chooseAlbum(album) } },
                onToggle = uploads::toggle,
                onSelection = uploads::setSelection,
                onUseSelection = uploads::chooseSelected,
                modifier = Modifier.weight(1f),
            )
            else -> AccessNeeded(access, onSettings = uploads::openSettings)
        }
    }
    picker.naming?.let { naming ->
        AlbumDialog(
            naming,
            onType = uploads::type,
            onAddNew = { uploads.addNew() },
            onTarget = { uploads.target(it) },
            onDelete = uploads::deleteAfterUpload,
            onUpload = { if (uploads.confirm() != null) onClose() },
            onCancel = uploads::dismissNaming,
        )
    }
}

/**
 * The library's albums, then its photos to pick loosely (§8).
 *
 * **Dragging across the photos selects a range**, the way the Photos app does: a drag that starts
 * sideways — or a press held still — selects every photo from the one it started on to the one
 * under the finger, in reading order, and dragging back shrinks the range again. Starting on a
 * photo already selected takes the range out instead. A drag that starts vertically is the list's
 * scroll, and a tap is the tile's own toggle; holding near the top or bottom edge scrolls the grid
 * while selecting.
 *
 * The gesture reads events on the *initial* pass, ahead of the list and the tiles, because the
 * first version did not: it waited for a long press behind the list, which claimed the drag as a
 * scroll first, and matched tiles by bounds that went stale as rows were recycled. Which photo is
 * under the finger now comes from the list's own layout.
 */
@Composable
internal fun GalleryPicker(
    picker: PickerUi,
    onAlbum: (GalleryAlbum) -> Unit,
    onToggle: (String) -> Unit,
    onSelection: (Set<String>) -> Unit,
    onUseSelection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val list = rememberLazyListState()
    val rows = remember(picker.assets) { picker.assets.chunked(COLUMNS) }
    val ids = remember(picker.assets) { picker.assets.map(GalleryAsset::id) }
    val rowByKey = remember(rows) { rows.withIndex().associate { (index, row) -> "row:${row.first().id}" to index } }
    // The gesture outlives a composition, so it reads these afresh on every event.
    val latestIds by rememberUpdatedState(ids)
    val latestRows by rememberUpdatedState(rowByKey)
    val latestSelected by rememberUpdatedState(picker.selected)
    val latestOnSelection by rememberUpdatedState(onSelection)
    val drag = remember { DragSelection() }
    val edge = with(LocalDensity.current) { AUTOSCROLL_EDGE.toPx() }

    fun indexAt(at: Offset): Int? {
        val layout = list.layoutInfo
        val item = layout.visibleItemsInfo.firstOrNull { at.y >= it.offset && at.y < it.offset + it.size }
            ?: return null
        val row = latestRows[item.key] ?: return null
        val column = (at.x / (layout.viewportSize.width.toFloat() / COLUMNS)).toInt().coerceIn(0, COLUMNS - 1)
        return (row * COLUMNS + column).takeIf { it < latestIds.size }
    }

    fun extendTo(index: Int) {
        val range = latestIds.subList(min(drag.anchor, index), max(drag.anchor, index) + 1).toSet()
        latestOnSelection(if (drag.selecting) drag.base + range else drag.base - range)
    }

    // Holding near an edge while selecting scrolls, and the range follows the photo that comes under the finger.
    LaunchedEffect(drag.point != null) {
        while (true) {
            val point = drag.point ?: break
            val height = list.layoutInfo.viewportSize.height.toFloat()
            val step = when {
                point.y < edge -> -(edge - point.y) / 3
                point.y > height - edge -> (point.y - (height - edge)) / 3
                else -> 0f
            }
            if (step != 0f) {
                list.scrollBy(step)
                indexAt(point)?.let(::extendTo)
            }
            delay(16)
        }
    }

    Column(modifier) {
        LazyColumn(
            state = list,
            modifier = Modifier.weight(1f).fillMaxWidth().pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val anchor = indexAt(down.position) ?: return@awaitEachGesture
                    // true: a selection. false: not this gesture's — a tap, or a vertical scroll.
                    // Still undecided when the long-press timeout passes: a press held still, which selects.
                    var decision: Boolean? = null
                    withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        while (decision == null) {
                            val change = awaitPointerEvent(PointerEventPass.Initial).changes
                                .firstOrNull { it.id == down.id }
                            val moved = change?.let { it.position - down.position }
                            decision = when {
                                change == null || !change.pressed -> false
                                moved!!.getDistance() > viewConfiguration.touchSlop -> abs(moved.x) > abs(moved.y)
                                else -> null
                            }
                        }
                    }
                    if (decision == false) return@awaitEachGesture

                    drag.anchor = anchor
                    drag.selecting = latestIds[anchor] !in latestSelected
                    drag.base = latestSelected
                    extendTo(anchor)
                    drag.point = down.position
                    while (true) {
                        val change = awaitPointerEvent(PointerEventPass.Initial).changes
                            .firstOrNull { it.id == down.id } ?: break
                        // Consumed, so the list does not scroll and the tile does not toggle again on release.
                        change.consume()
                        if (!change.pressed) break
                        drag.point = change.position
                        indexAt(change.position)?.let(::extendTo)
                    }
                    drag.point = null
                }
            },
        ) {
            item { SectionLabel("Albums") }
            items(picker.albums, key = { "album:${it.id}" }) { album -> GalleryAlbumRow(album) { onAlbum(album) } }
            item { SectionLabel("Or pick individual photos · ${picker.selected.size} selected") }
            items(rows, key = { "row:${it.first().id}" }) { row ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp)) {
                    for (asset in row) {
                        AssetTile(
                            asset,
                            picker.thumbnails[asset.id],
                            selected = asset.id in picker.selected,
                            modifier = Modifier.weight(1f).aspectRatio(1f).padding(1.dp),
                            onClick = { onToggle(asset.id) },
                        )
                    }
                    repeat(COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        if (picker.selected.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            TextButton(onClick = onUseSelection, modifier = Modifier.fillMaxWidth().padding(4.dp)) {
                Text("Upload ${picker.selected.size} selected", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp),
    )
}

@Composable
private fun GalleryAlbumRow(album: GalleryAlbum, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                album.name,
                fontSize = 17.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text("${album.count} items", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.chevron, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun AssetTile(
    asset: GalleryAsset,
    jpeg: ByteArray?,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable(onClick = onClick)) {
        if (jpeg != null) Thumbnail(jpeg, Modifier.fillMaxSize())
        TileMark(asset.mediaType, Modifier.align(Alignment.BottomStart).padding(4.dp))
        // Blue: an active state, which is what §6's colour table gives blue to.
        if (selected) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(4.dp).size(18.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(12.dp))
            }
        }
    }
}

/** Full access or nothing: albums are needed for the name and deleting needs full access too (§8). */
@Composable
private fun AccessNeeded(access: GalleryAccess, onSettings: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (access == GalleryAccess.Limited) "Only selected photos are shared with this app" else "No access to your photos",
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            "Uploading needs access to your whole library: it lists your albums to name the new one, " +
                "and it can delete the photos once they are uploaded.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        TextButton(onClick = onSettings, modifier = Modifier.padding(top = 12.dp)) {
            Text("Open Settings", color = MaterialTheme.colorScheme.primary)
        }
    }
}

/**
 * Where the photos go (§8): one field that is a path from the library root and a list of the albums
 * it matches. Picking an album adds to it; a path no album has offers `+`, which only adds it to the
 * list — nothing is created until Upload.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlbumDialog(
    naming: Naming,
    onType: (String) -> Unit,
    onAddNew: () -> Unit,
    onTarget: (Uuid) -> Unit,
    onDelete: (Boolean) -> Unit,
    onUpload: () -> Unit,
    onCancel: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    // The field owns its text and cursor; the model only hears about each change. Fed back from the
    // model instead, the first keystroke's value arrived a recomposition late, and on iOS the cursor
    // went back before that character — so a typed name came out with its first letter last. Opened
    // fresh per dialog, so the pre-fill starts here; a pick or a `+` replaces the text wholesale.
    var field by remember { mutableStateOf(TextFieldValue(naming.text, TextRange(naming.text.length))) }
    if (field.text != naming.text && naming.selected?.path == naming.text) {
        field = TextFieldValue(naming.text, TextRange(naming.text.length))
    }
    var expanded by remember { mutableStateOf(false) }
    // Until the text is edited the list is the whole list, with the pre-selection ticked; the first
    // edit starts filtering it.
    var edited by remember { mutableStateOf(false) }
    val resolution = naming.resolution

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Upload ${naming.count} items") },
        text = {
            Column {
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                    TextField(
                        value = field,
                        onValueChange = {
                            if (it.text != field.text) edited = true
                            field = it
                            expanded = true
                            onType(it.text)
                        },
                        singleLine = true,
                        label = { Text("Album") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
                    )
                    val query = if (edited) field.text.split('/').joinToString(" / ") { it.trim() }.trim() else ""
                    val shown = naming.entries.filter { query.isEmpty() || it.path.contains(query, ignoreCase = true) }
                    val offerNew = edited && resolution is Resolution.New
                    if (shown.isNotEmpty() || offerNew) {
                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false },
                            modifier = Modifier.heightIn(max = 260.dp),
                        ) {
                            for (entry in shown) {
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(entry.path, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                            if (entry.kind == UploadTarget.Kind.New) Tag("new")
                                        }
                                    },
                                    leadingIcon = {
                                        Text(if (entry.id == naming.selected?.id) "✓" else "", color = scheme.primary, modifier = Modifier.width(16.dp))
                                    },
                                    onClick = {
                                        onTarget(entry.id)
                                        field = TextFieldValue(entry.path, TextRange(entry.path.length))
                                        edited = false
                                        expanded = false
                                    },
                                )
                            }
                            if (offerNew) {
                                DropdownMenuItem(
                                    text = { Text("New album “${resolution.path}”", color = scheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    leadingIcon = { Text("+", color = scheme.primary, modifier = Modifier.width(16.dp)) },
                                    onClick = {
                                        onAddNew()
                                        field = TextFieldValue(resolution.path, TextRange(resolution.path.length))
                                        edited = false
                                        expanded = false
                                    },
                                )
                            }
                        }
                    }
                }
                val selected = naming.selected
                val (helper, isError) = when {
                    selected != null && selected.kind == UploadTarget.Kind.New ->
                        "New album in ${naming.targets.pathOf(selected.parent).ifEmpty { "Albums" }}" to false
                    selected != null -> "Adds to ${selected.path}" to false
                    resolution is Resolution.Refused -> resolution.reason to true
                    resolution is Resolution.New -> "Tap + to add it as a new album" to false
                    else -> "Pick an album or type a new one" to false
                }
                Text(
                    helper,
                    fontSize = 12.sp,
                    color = when {
                        isError -> scheme.error
                        selected != null -> scheme.onSurface
                        else -> scheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(start = 16.dp, top = 6.dp),
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp).clickable { onDelete(!naming.deleteFromGallery) },
                    verticalAlignment = Alignment.Top,
                ) {
                    Checkbox(checked = naming.deleteFromGallery, onCheckedChange = onDelete)
                    Column(Modifier.padding(top = 12.dp)) {
                        Text(
                            if (naming.galleryAlbum != null) {
                                "Delete the album and its photos from this device after upload"
                            } else {
                                "Delete them from this device after upload"
                            },
                            fontSize = 14.sp,
                            color = scheme.onSurface,
                        )
                        // §8's accepted risk, said where it is accepted.
                        Text(
                            "You will be asked to confirm. Until the laptop syncs, the storage zone holds the only copy.",
                            fontSize = 12.sp,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onUpload, enabled = naming.selected != null) { Text("Upload") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

/** The `new` beside an album the dialog added and nothing has created yet. */
@Composable
private fun Tag(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.padding(start = 8.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** The uploads still moving, from the model. Nothing shows once every one of them is done. */
@Composable
internal fun UploadProgress(uploads: UploadModel) {
    val statuses by uploads.statuses.collectAsState()
    UploadBar(
        statuses.filter { it.stage != UploadStage.Done },
        onCancel = uploads::cancel,
        onRetry = uploads::retry,
    )
}

/**
 * The uploads, as a pill the screen above shrinks for, expanding to a sheet (§8).
 *
 * The pill names where the album moving now is going and how many wait behind it; the sheet lists
 * them all, each with a cancel and, once one has failed, a retry.
 *
 * It takes real height rather than floating over the screen: [AppChrome] says why. **The sheet is
 * bounded at half the window** and its rows scroll inside it, so however many albums are queued the
 * screen above keeps half its space — and the picker's own button, one bar up, stays where a thumb
 * left it.
 */
@Composable
internal fun UploadBar(shown: List<UploadStatus>, onCancel: (Uuid) -> Unit, onRetry: (Uuid) -> Unit) {
    if (shown.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth()
                .heightIn(max = maxHeight / 2)
                .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                .background(scheme.surfaceContainerHigh),
        ) {
            if (expanded) {
                Row(
                    Modifier.fillMaxWidth().clickable { expanded = false }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Uploads", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = scheme.onSurface, modifier = Modifier.weight(1f))
                    Icon(Icons.collapse, contentDescription = "Minimize", tint = scheme.onSurface, modifier = Modifier.size(20.dp))
                }
                // fill = false: the rows take what they need and no more, so two uploads make a short
                // sheet rather than one padded out to the cap.
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    for (status in shown) {
                        UploadRow(status, onCancel = { onCancel(status.albumId) }, onRetry = { onRetry(status.albumId) })
                    }
                }
            } else {
                val current = shown.firstOrNull { it.stage != UploadStage.Waiting } ?: shown.first()
                val waiting = shown.count { it.stage == UploadStage.Waiting && it.albumId != current.albumId }
                Row(
                    Modifier.fillMaxWidth().clickable { expanded = true }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.upload, contentDescription = null, tint = scheme.onSurface, modifier = Modifier.size(18.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Uploading to ${current.path}",
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = scheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            current.caption() + if (waiting > 0) " · $waiting waiting" else "",
                            fontSize = 11.sp,
                            color = if (current.stage == UploadStage.Failed) scheme.error else scheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Icon(Icons.expand, contentDescription = "Expand", tint = scheme.onSurface, modifier = Modifier.size(20.dp))
                }
                ProgressLine(current)
            }
        }
    }
}

@Composable
private fun UploadRow(status: UploadStatus, onCancel: () -> Unit, onRetry: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Uploading to ${status.path}",
                    fontSize = 14.sp, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    status.caption(),
                    fontSize = 12.sp,
                    color = if (status.stage == UploadStage.Failed) scheme.error else scheme.onSurfaceVariant,
                )
            }
            if (status.stage == UploadStage.Failed) {
                TextButton(onClick = onRetry) { Text("Retry", color = scheme.primary) }
            }
            TextButton(onClick = onCancel) { Text("Cancel", color = scheme.error) }
        }
        ProgressLine(status)
    }
}

/** Blue while bytes move, green once the album has landed — §6's progress fill. */
@Composable
private fun ProgressLine(status: UploadStatus) {
    LinearProgressIndicator(
        progress = { status.fraction },
        modifier = Modifier.fillMaxWidth().height(3.dp),
        color = if (status.stage == UploadStage.Done) MaterialTheme.colorScheme.start else MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    )
}

private fun UploadStatus.caption(): String = when (stage) {
    UploadStage.Waiting -> "Waiting · $files items"
    UploadStage.Preparing -> "Preparing $filesDone/$files"
    UploadStage.Uploading -> "$filesDone/$files · ${bytesDone.megabytes()} of ${bytes.megabytes()}"
    UploadStage.Finishing -> "Finishing"
    UploadStage.Done -> "Uploaded"
    UploadStage.Failed -> failure ?: "Upload failed"
}

/** Decimal megabytes, as §9 has every figure the app and the invoice share. */
private fun Long.megabytes(): String {
    val tenths = (this + 50_000) / 100_000
    return "${tenths / 10}.${tenths % 10} MB"
}

private const val COLUMNS = 4

/** How close to the list's top or bottom a selecting finger has to be for the grid to scroll. */
private val AUTOSCROLL_EDGE = 56.dp

/** One drag's selection: where it started, whether it adds or removes, and what it started from. */
private class DragSelection {
    /** Where the finger is while a selection drag is under way; null otherwise. */
    var point by mutableStateOf<Offset?>(null)
    var anchor = 0
    var selecting = true
    var base: Set<String> = emptySet()
}
