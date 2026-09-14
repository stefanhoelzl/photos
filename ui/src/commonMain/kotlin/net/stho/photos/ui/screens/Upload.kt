package net.stho.photos.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import net.stho.photos.app.GalleryAccess
import net.stho.photos.app.GalleryAlbum
import net.stho.photos.app.GalleryAsset
import net.stho.photos.app.Naming
import net.stho.photos.app.PickerUi
import net.stho.photos.app.UploadModel
import net.stho.photos.app.UploadStage
import net.stho.photos.app.UploadStatus
import net.stho.photos.model.MediaType

/**
 * §8's gallery picker and name dialog: a gallery album whole, or loose photos.
 *
 * A pushed screen for choosing, and a dialog for naming — the name is prefilled from the gallery
 * album, the parent is the list the upload icon was on, and deleting from the device is decided
 * here, up front, rather than asked once the upload is done.
 */
@Composable
internal fun UploadScreen(uploads: UploadModel, onClose: () -> Unit) {
    val picker by uploads.picker.collectAsState()
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        NavBar("Choose photos", "into ${picker.parentName}", onBack = { uploads.close(); onClose() }) {}
        when (val access = picker.access) {
            null -> EmptyState("Opening the photo library…")
            GalleryAccess.Full -> GalleryPicker(
                picker,
                onAlbum = { album -> scope.launch { uploads.chooseAlbum(album) } },
                onToggle = uploads::toggle,
                onSelect = uploads::select,
                onUseSelection = uploads::chooseSelected,
                modifier = Modifier.weight(1f),
            )
            else -> AccessNeeded(access, onSettings = uploads::openSettings)
        }
    }
    picker.naming?.let { naming ->
        NameDialog(
            naming,
            picker.parentName,
            onName = uploads::rename,
            onDelete = uploads::deleteAfterUpload,
            onUpload = { if (uploads.confirm() != null) onClose() },
            onCancel = uploads::dismissNaming,
        )
    }
}

@Composable
private fun GalleryPicker(
    picker: PickerUi,
    onAlbum: (GalleryAlbum) -> Unit,
    onToggle: (String) -> Unit,
    onSelect: (Collection<String>) -> Unit,
    onUseSelection: () -> Unit,
    modifier: Modifier,
) {
    // Where each tile sits on screen, so a drag across the grid can say which tiles it crossed.
    val tiles = remember { mutableMapOf<String, Rect>() }
    var origin by remember { mutableStateOf<LayoutCoordinates?>(null) }
    fun hit(at: Offset): String? {
        val point = origin?.localToRoot(at) ?: return null
        return tiles.entries.firstOrNull { it.value.contains(point) }?.key
    }

    Column(modifier) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth()
                .onGloballyPositioned { origin = it }
                // Drag across the thumbnails to select a range: press, hold, and sweep (§8).
                .pointerInput(picker.assets) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { at -> hit(at)?.let { onSelect(listOf(it)) } },
                        onDrag = { change, _ -> hit(change.position)?.let { onSelect(listOf(it)) } },
                    )
                },
        ) {
            item { SectionLabel("Albums") }
            items(picker.albums, key = { "album:${it.id}" }) { album -> GalleryAlbumRow(album) { onAlbum(album) } }
            item { SectionLabel("Or pick individual photos · ${picker.selected.size} selected") }
            items(picker.assets.chunked(COLUMNS), key = { "row:${it.first().id}" }) { row ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp)) {
                    for (asset in row) {
                        AssetTile(
                            asset,
                            picker.thumbnails[asset.id],
                            selected = asset.id in picker.selected,
                            modifier = Modifier.weight(1f).aspectRatio(1f).padding(1.dp)
                                .onGloballyPositioned { tiles[asset.id] = it.boundsInRoot() },
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
        if (asset.mediaType != MediaType.PHOTO) {
            Text(
                if (asset.mediaType == MediaType.VIDEO) "VIDEO" else "LIVE",
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
            )
        }
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

@Composable
private fun NameDialog(
    naming: Naming,
    parentName: String,
    onName: (String) -> Unit,
    onDelete: (Boolean) -> Unit,
    onUpload: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("New album") },
        text = {
            Column {
                Text("${naming.count} items · in $parentName", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextField(
                    value = naming.name,
                    onValueChange = onName,
                    singleLine = true,
                    placeholder = { Text("Album name") },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp).clickable { onDelete(!naming.deleteFromGallery) },
                    verticalAlignment = Alignment.Top,
                ) {
                    Checkbox(checked = naming.deleteFromGallery, onCheckedChange = onDelete)
                    Column(Modifier.padding(top = 12.dp)) {
                        Text("Delete them from this device after upload", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                        // §8's accepted risk, said where it is accepted.
                        Text(
                            "You will be asked to confirm. Until the laptop syncs, the storage zone holds the only copy.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onUpload, enabled = naming.name.isNotBlank()) { Text("Upload") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

/**
 * The uploads, as a pill that browsing continues around, expanding to a sheet (§8).
 *
 * The pill names the album moving now and how many wait behind it; the sheet lists them all, each
 * with a cancel and, once one has failed, a retry. Nothing shows once every upload is done.
 */
@Composable
internal fun UploadProgress(uploads: UploadModel) {
    val statuses by uploads.statuses.collectAsState()
    val shown = statuses.filter { it.stage != UploadStage.Done }
    if (shown.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth()
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
            for (status in shown) {
                UploadRow(status, onCancel = { uploads.cancel(status.albumId) }, onRetry = { uploads.retry(status.albumId) })
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
                        "Uploading “${current.name}”",
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

@Composable
private fun UploadRow(status: UploadStatus, onCancel: () -> Unit, onRetry: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(status.name, fontSize = 14.sp, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
