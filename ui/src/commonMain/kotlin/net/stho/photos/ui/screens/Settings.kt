package net.stho.photos.ui.screens

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.stho.photos.app.StorageTotals
import net.stho.photos.storage.StorageUrl
import net.stho.photos.app.SyncStatus
import net.stho.photos.app.Totals

/**
 * Settings: account, storage totals, sync, log out — and **no album list**.
 *
 * The per-album controls are not here. They live on the album list itself, where a person is
 * already looking at the album they want, which is also what stops two renderings of the same
 * 288 albums from having to agree with each other. What is left here is the totals, which are
 * about the device rather than about any one album.
 *
 * The sync row is load-bearing rather than decorative: a toast is transient, so this is the
 * durable record of a failure, and it is what makes a missed toast harmless.
 */
@Composable
public fun SettingsScreen(
    sync: SyncStatus,
    totals: Totals,
    storage: StorageTotals,
    storageUrl: StorageUrl,
    onLogOut: () -> Unit,
) {
    // Scrolls: on a small phone, with a sync failure's detail row, Log out sits below the fold.
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // §1: read-only, and nothing carries a disclosure arrow. Changing either value means
        // logging out and setting up again, so an edit affordance here would be a second path
        // to a configured state -- which is the thing §1 refuses to have.
        SectionHeader("Account")
        Cell("Storage URL", "${'$'}{storageUrl.endpoint}/${'$'}{storageUrl.zone}")
        // A masked value and its protection, never where it is stored (§1).
        Cell("Password", "••••••••")
        SectionHeader("Library")
        Cell("Albums", "${totals.albums} · ${totals.photos} photos")
        SectionHeader("Storage")
        Cell("Cached images + video", storage.media.asSize())
        Cell("Catalog + thumbnails", "${storage.packs.asSize()} · always kept")
        // Nothing is ever evicted automatically (§6), so this number only ever grows by
        // something the person did -- which is what makes stating it useful rather than alarming.
        Cell("Albums held", "${storage.albumsHeld} of ${totals.albums}")
        SectionHeader("Sync")
        when (sync) {
            is SyncStatus.Never -> Cell("Last sync", "never")
            is SyncStatus.Running -> Cell("Last sync", "running…")
            is SyncStatus.Succeeded -> Cell("Last sync", sync.at.toString().substringBefore('.'))
            is SyncStatus.Failed -> {
                Cell("Last sync", sync.notice.title, error = true)
                Cell("", sync.notice.detail)
            }
        }
        // Destructive, so it takes the error colour §6's table assigns (it is the only
        // destructive action on this screen). No confirmation: nothing in the zone is touched
        // and nothing cached is deleted -- it is reversible by setting up again.
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        TextButton(onClick = onLogOut, modifier = Modifier.fillMaxWidth()) {
            Text(
                "Log out",
                color = MaterialTheme.colorScheme.error,
                fontSize = 16.sp,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }
}


/** Decimal GB, because §9's units rule is that a number means what it says. */
private fun Long.asSize(): String = when {
    this >= 1_000_000_000L -> "${(this / 100_000_000L) / 10.0} GB"
    this >= 1_000_000L -> "${this / 1_000_000L} MB"
    this > 0L -> "${this / 1_000L} kB"
    else -> "nothing yet"
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text.uppercase(),
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun Cell(label: String, value: String, error: Boolean = false) {
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, fontSize = 13.5.sp, color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Normal, modifier = Modifier.fillMaxWidth(0.5f))
            Text(
                value,
                fontSize = 12.5.sp,
                color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
    }
}
