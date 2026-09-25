package net.stho.photos.ui.desktop

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.abs
import kotlin.uuid.Uuid
import net.stho.photos.faces.FaceBox
import net.stho.photos.faces.Person
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.stho.photos.app.DesktopModel
import net.stho.photos.app.DesktopUi
import net.stho.photos.app.Face
import net.stho.photos.app.FaceChoice
import net.stho.photos.app.FaceState
import net.stho.photos.app.GroupSummary
import net.stho.photos.app.PersonSummary
import net.stho.photos.app.Showing
import net.stho.photos.model.PhotoRow
import net.stho.photos.ui.screens.EmptyState
import net.stho.photos.ui.screens.Icons
import net.stho.photos.ui.screens.NavBar
import net.stho.photos.ui.screens.Thumbnail
import net.stho.photos.ui.screens.reveal

// ------------------------------------------------------------------------------ the sidebar

/**
 * §12's PEOPLE section, above the album tree: every person, then "Unknown", which expands to the
 * groups `sync` found among the faces nobody is suggested for. Nothing at all until `sync` has
 * built an index.
 */
@Composable
internal fun PeopleSection(ui: DesktopUi, model: DesktopModel, modifier: Modifier) {
    val people = ui.people
    if (people.people.isEmpty() && people.groups.isEmpty()) return
    val searching = ui.query.isNotBlank()
    val listed = ui.listedPeople
    // Searching for a name nobody has: the albums alone, with no empty PEOPLE heading above them.
    if (searching && listed.isEmpty()) return
    SectionHeader("PEOPLE", open = ui.peopleOpen, onClick = model::togglePeople)
    if (!ui.peopleOpen) return
    val scroll = rememberScrollState()
    Box(modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().verticalScroll(scroll)) {
            for (summary in listed) PersonRow(summary, ui, model)
            // At the foot of the people, for a second look at what was set aside.
            if (!searching && people.ignored.isNotEmpty()) {
                SidebarRow(
                    selected = ui.showing == Showing.Ignored,
                    onClick = { model.show(Showing.Ignored) },
                    avatar = { Avatar(null, "–") },
                    title = "Ignored",
                    detail = people.ignored.size.toString(),
                )
            }
            if (!searching && people.groups.isNotEmpty()) {
                val open = ui.unknownOpen
                SidebarRow(
                    selected = false,
                    onClick = { model.expandUnknown(!open) },
                    avatar = { Avatar(null, "?") },
                    title = "Unknown",
                    detail = unknownDetail(people.groups) + if (open) " ▾" else " ▸",
                )
                if (open) for (group in people.groups) GroupRow(group, ui, model)
            }
        }
        // The section scrolls on its own once people outgrow its height.
        VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.matchParentSize().wrapContentWidth(Alignment.End))
    }
}

/** "17 groups", or — before there are any — how many faces wait in "Other". */
private fun unknownDetail(groups: List<GroupSummary>): String {
    val count = groups.count { it.id != Face.OTHER }
    if (count > 0) return "$count groups"
    return "${groups.sumOf { it.size }} faces"
}

@Composable
private fun PersonRow(summary: PersonSummary, ui: DesktopUi, model: DesktopModel) {
    val showing = Showing.Person(summary.person.id)
    SidebarRow(
        selected = ui.showing == showing,
        onClick = { model.show(showing) },
        avatar = { Avatar(summary.avatar?.let { ui.crops[it.id] }, summary.person.name.take(1)) },
        title = summary.person.name,
        detail = summary.confirmed.toString(),
        badge = summary.suggested.takeIf { it > 0 },
    )
}

@Composable
private fun GroupRow(group: GroupSummary, ui: DesktopUi, model: DesktopModel) {
    val showing = Showing.Group(group.id)
    SidebarRow(
        selected = ui.showing == showing,
        onClick = { model.show(showing) },
        avatar = { Avatar(ui.crops[group.sample.id], "?") },
        title = if (group.id == Face.OTHER) "Other" else "${group.size} faces",
        detail = if (group.id == Face.OTHER) group.size.toString() else "",
        indent = true,
    )
}

@Composable
internal fun SectionHeader(text: String, open: Boolean, onClick: () -> Unit) {
    Text(
        "$text  ${if (open) "▾" else "▸"}",
        fontSize = 11.sp,
        letterSpacing = 0.7.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun SidebarRow(
    selected: Boolean,
    onClick: () -> Unit,
    avatar: @Composable () -> Unit,
    title: String,
    detail: String,
    badge: Int? = null,
    indent: Boolean = false,
) {
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(start = if (indent) 38.dp else 16.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        avatar()
        Text(
            title,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (detail.isNotEmpty()) Text(detail, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (badge != null) {
            Text(
                badge.toString(),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.clip(RoundedCornerShape(9.dp)).background(MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
    }
}

/** A round face, or the initial standing in until its crop is cut. */
@Composable
private fun Avatar(jpeg: ByteArray?, initial: String) {
    Box(
        Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (jpeg != null) Thumbnail(jpeg, Modifier.fillMaxSize())
        else Text(initial.uppercase(), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ----------------------------------------------------------------------------- the panes

/**
 * A person's faces, or an unknown group's, in the album pane (§12). The keyboard is
 * selection-based, like the album grid's focus ring: arrows move, Shift+arrows select, and Space
 * decides — see [inFaces] for the whole of it.
 */
@Composable
internal fun FacesPane(
    ui: DesktopUi,
    model: DesktopModel,
    focused: Boolean,
    naming: FocusRequester,
    onColumns: (Int) -> Unit,
) {
    val showing = ui.showing ?: return
    val person = ui.person
    when (showing) {
        is Showing.Person -> {
            if (person == null) return
            var renaming by remember(showing) { mutableStateOf(false) }
            var merging by remember(showing) { mutableStateOf(false) }
            NavBar(
                person.person.name,
                "${person.confirmed} confirmed · ${person.suggested} suggested",
                onBack = null,
            ) {
                Chip("Rename") { renaming = true }
                Box {
                    Chip("Merge into…") { merging = true }
                    DropdownMenu(expanded = merging, onDismissRequest = { merging = false }) {
                        Text(
                            "Merge ${person.person.name} into",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                        for (other in ui.people.people.filter { it.person.id != person.person.id }) {
                            DropdownMenuItem(
                                text = { Text("${other.person.name}  ·  ${other.confirmed}") },
                                onClick = {
                                    merging = false
                                    model.mergePerson(person.person.id, other.person.id)
                                },
                            )
                        }
                    }
                }
            }
            if (renaming) RenameDialog(person.person.name, onDone = { name ->
                renaming = false
                if (name != null) model.renamePerson(person.person.id, name)
            })
            Keys("←↑↓→ move · Shift+arrows select · Ctrl+A all · Enter confirm / unconfirm · Ctrl+Enter name… · N not ${person.person.name} · I ignore · Space open photo · Esc clear")
        }
        Showing.Ignored -> {
            NavBar("Ignored", "${ui.faces.size} faces set aside", onBack = null) {
                Chip("Un-ignore") { model.confirmChosen() }
            }
            Keys("←↑↓→ move · Shift+arrows select · Ctrl+A all · Enter un-ignore · Ctrl+Enter name… · Space open photo · Esc clear")
        }
        is Showing.Group -> {
            val title = if (showing.id == Face.OTHER) "Other" else "Unknown · ${ui.faces.size} faces"
            NavBar(title, "${ui.faceSelection.size} selected", onBack = null) {
                Chip("Ignore") { model.ignoreChosen() }
            }
            NameField(ui, model, naming)
            Keys("Shift+arrows or Ctrl-click select · Space in / out · type a name, Enter to name the selection · Ctrl+Enter name… · Delete ignore · Esc clear")
        }
    }
    if (ui.faces.isEmpty()) {
        EmptyState(
            when (showing) {
                is Showing.Person -> "No faces yet — confirmations take effect at the next sync"
                Showing.Ignored -> "Nothing ignored"
                is Showing.Group -> "Nothing left in this group"
            },
        )
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val count = ((maxWidth - 16.dp) / FACE_TILE).toInt().coerceAtLeast(1)
        LaunchedEffect(count) { onColumns(count) }
        val grid = rememberLazyGridState()
        FollowFocus(grid, ui, count)
        LazyVerticalGrid(
            columns = GridCells.Fixed(count),
            state = grid,
            // Clear of the scroll bar, which is drawn beside the tiles rather than over them.
            modifier = Modifier.fillMaxSize().padding(start = 8.dp, end = 12.dp),
        ) {
            val suggested = ui.suggestedCount
            if (showing is Showing.Person && suggested > 0) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    GridHeader("Suggested", "$suggested, most alike first")
                }
            }
            // Not keyed: a keyed lazy grid holds its scroll to the first item on screen, so a
            // suggestion confirmed from the top of the grid dragged the grid down to where it
            // landed among the confirmed faces. Positional, it stays where it is.
            for ((index, face) in ui.faces.withIndex()) {
                if (showing is Showing.Person && index == suggested) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        GridHeader("Confirmed", (ui.faces.size - suggested).toString())
                    }
                }
                item {
                    Box {
                        FaceTile(
                            face = face,
                            jpeg = ui.crops[face.id],
                            selected = face.id in ui.faceSelection,
                            focused = focused && ui.faceFocus == index,
                            onClick = { range, toggle -> model.clickFace(index, range, toggle) },
                            onOpen = { model.openFace(index) },
                            onMenu = { model.contextFace(index) },
                        )
                        if (ui.faceMenu == index) FacesMenu(ui, model, onDismiss = model::closeFaceMenu)
                    }
                }
            }
        }
        if (showing is Showing.Person) StickyHeader(grid, ui)
        VerticalScrollbar(rememberScrollbarAdapter(grid), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

/**
 * The header of the section at the top of a person's grid, pinned there while it scrolls —
 * "Suggested" over the suggestions, "Confirmed" once they have scrolled past. A lazy grid has no
 * sticky headers of its own, so this is drawn over it, from where the grid stands.
 */
@Composable
private fun BoxScope.StickyHeader(grid: LazyGridState, ui: DesktopUi) {
    val suggested = ui.suggestedCount
    val first by remember { derivedStateOf { grid.firstVisibleItemIndex to grid.firstVisibleItemScrollOffset } }
    val (index, offset) = first
    // At the very top the grid's own header is showing; nothing to pin.
    if (index == 0 && offset == 0) return
    // Grid items: [Suggested header], suggestions, [Confirmed header], confirmed faces.
    val confirmedHeader = if (suggested > 0) suggested + 1 else 0
    val inConfirmed = suggested == 0 || index >= confirmedHeader
    Box(
        Modifier.align(Alignment.TopStart).fillMaxWidth().background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 8.dp),
    ) {
        if (inConfirmed) GridHeader("Confirmed", (ui.faces.size - suggested).toString())
        else GridHeader("Suggested", "$suggested, most alike first")
    }
}

/** Keeps the focused face in view as the arrow keys move it. */
@Composable
private fun FollowFocus(grid: LazyGridState, ui: DesktopUi, columns: Int) {
    val focus = ui.faceFocus ?: return
    // A person's two headers are grid items too.
    fun item(face: Int): Int = face + when {
        ui.showing !is Showing.Person || ui.suggestedCount == 0 -> 0
        face < ui.suggestedCount -> 1
        else -> 2
    }
    var was by remember { mutableStateOf(focus) }
    // Wholly into view by the shortest scroll, as the album grid does — and a row ahead: moving
    // down, the row under the focus is brought in too, so the page turns a row before the focus
    // reaches its edge rather than as it does.
    LaunchedEffect(focus) {
        val ahead = if (focus >= was) focus + columns else focus - columns
        was = focus
        grid.reveal(item(ahead.coerceIn(0, ui.faces.lastIndex)))
        grid.reveal(item(focus))
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun FaceTile(
    face: Face,
    jpeg: ByteArray?,
    selected: Boolean,
    focused: Boolean,
    onClick: (range: Boolean, toggle: Boolean) -> Unit,
    onOpen: () -> Unit,
    onMenu: () -> Unit,
) {
    var lastPress by remember { mutableStateOf(0L) }
    Box(
        Modifier.padding(4.dp).aspectRatio(1f).clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .onPointerEvent(PointerEventType.Press) { event ->
                if (event.buttons.isSecondaryPressed) {
                    onMenu()
                    return@onPointerEvent
                }
                val modifiers = event.keyboardModifiers
                val now = System.currentTimeMillis()
                if (now - lastPress < DOUBLE_CLICK_MS && !modifiers.isShiftPressed && !modifiers.isCtrlPressed) onOpen()
                else onClick(modifiers.isShiftPressed, modifiers.isCtrlPressed)
                lastPress = now
            },
    ) {
        if (jpeg != null) Thumbnail(jpeg, Modifier.fillMaxSize())
        if (face.state == FaceState.SUGGESTED && face.similarity != null) {
            Text(
                "%.2f".format(face.similarity),
                fontSize = 10.sp,
                color = Color.White,
                modifier = Modifier.align(Alignment.BottomStart).padding(4.dp)
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(4.dp)).padding(horizontal = 3.dp),
            )
        }
        if (selected) {
            Box(Modifier.fillMaxSize().border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)))
            Box(
                Modifier.align(Alignment.TopEnd).padding(4.dp).size(18.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(12.dp))
            }
        }
        if (focused) Box(Modifier.fillMaxSize().border(2.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(8.dp)))
    }
}

/**
 * The right-click menu over a selection of faces (§12): a name typed or picked from the people
 * already named — a new one when nothing matches — and, on a person, "not them", and ignore.
 */
@Composable
private fun FacesMenu(ui: DesktopUi, model: DesktopModel, onDismiss: () -> Unit) {
    val count = ui.faceSelection.size.coerceAtLeast(1)
    val person = ui.person?.person
    // Most alike first, by the last sync's scores for the faces being named (§12).
    val chosen = ui.faces.filter { it.id in ui.faceSelection }
    NameMenu(
        title = if (count == 1) "This face is…" else "These $count faces are…",
        people = ui.people.ranked(chosen),
        known = ui.people.people.map { it.person },
        onPerson = { model.nameChosen(it) },
        onNew = { model.nameChosen(null, it) },
        onDismiss = onDismiss,
    ) { done ->
        if (person != null) {
            DropdownMenuItem(text = { Text("Not ${person.name}") }, onClick = { done { model.rejectChosen() } })
        }
        DropdownMenuItem(text = { Text("Ignore") }, onClick = { done { model.ignoreChosen() } })
    }
}

/**
 * Naming one face or several: a field that narrows [people] — already in the order to offer them
 * — completes to the first match on Enter, or offers a new person when nothing matches. [extra]
 * adds what else the menu's caller can decide, below a divider.
 */
@Composable
private fun NameMenu(
    title: String,
    people: List<Person>,
    known: List<Person>,
    onPerson: (Uuid) -> Unit,
    onNew: (String) -> Unit,
    onDismiss: () -> Unit,
    extra: @Composable (done: (() -> Unit) -> Unit) -> Unit = {},
) {
    var typed by remember { mutableStateOf("") }
    val matches = people.filter { typed.isBlank() || it.name.lowercase().startsWith(typed.trim().lowercase()) }
    val exact = known.any { it.name.equals(typed.trim(), ignoreCase = true) }
    val done: (() -> Unit) -> Unit = { action ->
        onDismiss()
        action()
    }
    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        Text(
            title,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
        val field = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it },
            singleLine = true,
            placeholder = { Text("Name", maxLines = 1) },
            modifier = Modifier.padding(horizontal = 8.dp).width(240.dp).focusRequester(field).onPreviewKeyEvent { event ->
                if ((event.key == Key.Enter || event.key == Key.NumPadEnter) && event.type == KeyEventType.KeyDown) {
                    val pick = matches.firstOrNull()
                    when {
                        pick != null && typed.isNotBlank() -> done { onPerson(pick.id) }
                        typed.isNotBlank() -> done { onNew(typed) }
                    }
                    true
                } else {
                    false
                }
            },
        )
        for (match in matches.take(MENU_PEOPLE)) {
            DropdownMenuItem(text = { Text(match.name) }, onClick = { done { onPerson(match.id) } })
        }
        if (typed.isNotBlank() && !exact) {
            DropdownMenuItem(text = { Text("New person “${typed.trim()}”") }, onClick = { done { onNew(typed) } })
        }
        HorizontalDivider()
        extra(done)
    }
}

@Composable
private fun GridHeader(title: String, detail: String) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, top = 12.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        Text(detail, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Keys(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun Chip(label: String, onClick: () -> Unit) {
    Text(
        label,
        maxLines = 1,
        softWrap = false,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

/**
 * The unknown group's name field: typing on the grid lands here, completions from the people
 * already named below it, and Enter names the selected faces (§12).
 */
@Composable
private fun NameField(ui: DesktopUi, model: DesktopModel, focus: FocusRequester) {
    val completions = ui.completions
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = ui.naming.orEmpty(),
                onValueChange = { model.editName(it) },
                placeholder = { Text("Name…", maxLines = 1) },
                singleLine = true,
                // Gives way to the button beside it, never the other way round.
                modifier = Modifier.weight(1f, fill = false).widthIn(max = 300.dp).focusRequester(focus).onPreviewKeyEvent { event ->
                    if (event.key == Key.Enter || event.key == Key.NumPadEnter) {
                        if (event.type == KeyEventType.KeyDown) {
                            model.submitName(completions.firstOrNull()?.name ?: ui.naming)
                        }
                        true
                    } else {
                        false
                    }
                },
            )
            Chip("Name ${ui.faceSelection.size} selected") { model.submitName(completions.firstOrNull()?.name ?: ui.naming) }
        }
        if (completions.isNotEmpty()) {
            Text(
                completions.joinToString("  ·  ") { it.name } + "   (Enter names them ${completions.first().name})",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        } else if (!ui.naming.isNullOrBlank()) {
            Text("Enter creates “${ui.naming!!.trim()}”", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun RenameDialog(current: String, onDone: (String?) -> Unit) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text("Rename") },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onDone(name) }) { Text("Rename") } },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text("Cancel") } },
    )
}

// ----------------------------------------------------------------------- boxes in the viewer

/**
 * The open photo's faces (§12), drawn where they are once F turns them on: solid for a
 * confirmed face, dashed for a suggestion, faint for an unknown one. A click opens the same
 * choices a grid offers.
 *
 * With D, a drag over the photo draws a box around a face the detector missed, and the naming
 * menu opens over it. All of it is inside the viewer's zoom, so it stays on the photograph; the
 * drag is taken here, so it neither pages to the next photo nor pans.
 */
@Composable
internal fun BoxScope.FaceBoxes(ui: DesktopUi, model: DesktopModel, photo: PhotoRow) {
    if (!ui.faceBoxes && !ui.drawing) return
    val width = photo.width ?: return
    val height = photo.height ?: return
    BoxWithConstraints(Modifier.matchParentSize()) {
        // The photograph is fitted into the page, so its rectangle is the page's, letterboxed.
        val scale = minOf(maxWidth.value / width, maxHeight.value / height)
        val shownWidth = width * scale
        val shownHeight = height * scale
        val left = (maxWidth.value - shownWidth) / 2
        val top = (maxHeight.value - shownHeight) / 2
        fun at(box: FaceBox) = listOf(
            (left + box.x * shownWidth).dp, (top + box.y * shownHeight).dp,
            (box.width * shownWidth).dp, (box.height * shownHeight).dp,
        )
        if (ui.faceBoxes) for (face in ui.openFaces) {
            val (x, y, w, h) = at(face.box)
            FaceBox(
                face = face,
                name = face.person?.let { id -> ui.people.person(id)?.person?.name },
                ui = ui,
                model = model,
                x = x, y = y, w = w, h = h,
            )
        }
        if (ui.drawing) {
            DrawLayer(left, top, shownWidth, shownHeight, onDrawn = model::drawn)
            Text(
                "Drag over a face to mark it · D or Esc to stop",
                fontSize = 12.sp,
                color = Color.White,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp)
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        ui.drawnBox?.let { box ->
            val (x, y, w, h) = at(box)
            Box(Modifier.offset(x, y).size(w, h).dashedOutline(DRAWN)) {
                NameMenu(
                    title = "This face is…",
                    // Nobody has been compared with a box the detector never saw: newest first.
                    people = ui.people.people.map { it.person },
                    known = ui.people.people.map { it.person },
                    onPerson = { model.nameDrawn(it) },
                    onNew = { model.nameDrawn(null, it) },
                    onDismiss = model::cancelDrawn,
                )
            }
        }
    }
}

/**
 * Where a drag draws a box, over the photograph's rectangle — [left], [top], [width] and [height]
 * in dp — reported as fractions of the photograph, the way every box is kept.
 */
@Composable
private fun BoxScope.DrawLayer(left: Float, top: Float, width: Float, height: Float, onDrawn: (FaceBox) -> Unit) {
    val density = LocalDensity.current.density
    var from by remember { mutableStateOf<Offset?>(null) }
    var to by remember { mutableStateOf<Offset?>(null) }
    fun fraction(point: Offset): Pair<Float, Float> =
        ((point.x / density - left) / width).coerceIn(0f, 1f) to ((point.y / density - top) / height).coerceIn(0f, 1f)
    Box(
        Modifier.matchParentSize().pointerInput(left, top, width, height) {
            detectDragGestures(
                onDragStart = { from = it; to = it },
                onDrag = { change, _ -> to = change.position; change.consume() },
                onDragCancel = { from = null; to = null },
                onDragEnd = {
                    val start = from
                    val end = to
                    from = null
                    to = null
                    if (start != null && end != null) {
                        val (x1, y1) = fraction(start)
                        val (x2, y2) = fraction(end)
                        val box = FaceBox(minOf(x1, x2), minOf(y1, y2), abs(x2 - x1), abs(y2 - y1))
                        // A click, or a sliver, is not a face.
                        if (box.width > 0.01f && box.height > 0.01f) onDrawn(box)
                    }
                },
            )
        },
    ) {
        val start = from
        val end = to
        if (start != null && end != null) {
            Box(
                Modifier.offset((minOf(start.x, end.x) / density).dp, (minOf(start.y, end.y) / density).dp)
                    .size((abs(end.x - start.x) / density).dp, (abs(end.y - start.y) / density).dp)
                    .dashedOutline(DRAWN),
            )
        }
    }
}

private fun Modifier.dashedOutline(colour: Color): Modifier = drawBehind {
    drawRoundRect(
        color = colour,
        cornerRadius = CornerRadius(4.dp.toPx()),
        style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))),
    )
}

/** A box being drawn, or drawn and waiting for its name: apart from every face state's colour. */
private val DRAWN = Color(0xFF64D2FF)

@Composable
private fun FaceBox(face: Face, name: String?, ui: DesktopUi, model: DesktopModel, x: Dp, y: Dp, w: Dp, h: Dp) {
    var menu by remember(face.id) { mutableStateOf(false) }
    var naming by remember(face.id) { mutableStateOf<String?>(null) }
    val colour = when (face.state) {
        FaceState.CONFIRMED -> Color.White
        FaceState.SUGGESTED -> Color(0xFFFFD60A)
        // Never drawn — the viewer's boxes leave ignored faces out — but named for completeness.
        FaceState.UNKNOWN, FaceState.IGNORED -> Color.White.copy(alpha = 0.45f)
    }
    Box(
        Modifier.offset(x, y).size(w, h)
            .drawBehind {
                drawRoundRect(
                    color = colour,
                    cornerRadius = CornerRadius(4.dp.toPx()),
                    style = Stroke(
                        width = 2.dp.toPx(),
                        pathEffect = if (face.state == FaceState.SUGGESTED) PathEffect.dashPathEffect(floatArrayOf(8f, 6f)) else null,
                    ),
                )
            }
            .clickable { menu = true },
    ) {
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false; naming = null }) {
            fun choose(choice: FaceChoice) {
                menu = false
                naming = null
                model.decideOnPhoto(face, choice)
            }
            val person = face.person
            if (person != null && name != null) {
                if (face.state == FaceState.SUGGESTED) DropdownMenuItem(text = { Text("✓ $name") }, onClick = { choose(FaceChoice.Is(person)) })
                DropdownMenuItem(text = { Text("✕ Not $name") }, onClick = { choose(FaceChoice.IsNot(person)) })
                if (face.state == FaceState.CONFIRMED) DropdownMenuItem(text = { Text("Remove name") }, onClick = { choose(FaceChoice.Clear) })
                HorizontalDivider()
            }
            val typing = naming
            if (typing == null) {
                DropdownMenuItem(text = { Text("Someone else…") }, onClick = { naming = "" })
            } else {
                // Most alike first; typing narrows without reordering.
                val matches = ui.people.ranked(listOf(face)).filter { it.name.lowercase().startsWith(typing.trim().lowercase()) }
                OutlinedTextField(
                    value = typing,
                    onValueChange = { naming = it },
                    singleLine = true,
                    placeholder = { Text("Name") },
                    modifier = Modifier.padding(horizontal = 8.dp).width(220.dp).onPreviewKeyEvent { event ->
                        if (event.key == Key.Enter && event.type == KeyEventType.KeyDown) {
                            if (typing.isNotBlank()) choose(FaceChoice.Named(matches.firstOrNull()?.name ?: typing))
                            true
                        } else {
                            false
                        }
                    },
                )
                for (match in matches.take(5)) {
                    DropdownMenuItem(text = { Text(match.name) }, onClick = { choose(FaceChoice.Is(match.id)) })
                }
            }
            DropdownMenuItem(text = { Text("Ignore this face") }, onClick = { choose(FaceChoice.Ignore) })
        }
        if (name != null || face.state == FaceState.UNKNOWN) {
            Text(
                when (face.state) {
                    FaceState.CONFIRMED -> name.orEmpty()
                    FaceState.SUGGESTED -> "$name?"
                    FaceState.UNKNOWN, FaceState.IGNORED -> "Unknown"
                },
                fontSize = 11.sp,
                color = colour,
                maxLines = 1,
                modifier = Modifier.align(Alignment.BottomStart).offset(y = 18.dp)
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
    }
}

private val FACE_TILE = 112.dp
/** People listed in the right-click menu before typing narrows them. */
private const val MENU_PEOPLE = 8
private const val DOUBLE_CLICK_MS = 350L
